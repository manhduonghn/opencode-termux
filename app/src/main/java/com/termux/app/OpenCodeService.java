package com.termux.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.shell.TermuxShellUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serializes the lifecycle of the managed OpenCode background service.
 *
 * <p>The service is a separate process that is started with {@code opencode service start} and
 * stopped with {@code opencode service stop}. Each of those commands takes over the registration
 * file and the listening port, so they must never run concurrently. They can be triggered from
 * several places at the same time: the web activity while it loads the UI and the GitHub settings
 * while they restart the service. Without a process wide lock the commands
 * interleave, a stop can kill a service that was just started (or the other way around) and the
 * next {@code onStart()} of the web activity can reuse a stale one time pair link.</p>
 *
 * <p>All start/stop/pair operations are therefore executed while holding {@link #LOCK}. A stop is
 * performed on a helper thread so that the UI is never blocked. When a
 * stop has been requested, {@link #consumeStoppedByUser()} tells the web activity that its current
 * page belongs to a dead service and that it has to pair again.</p>
 */
public final class OpenCodeService {

    private static final String LOG_TAG = "OpenCodeService";

    /** Upper bound for a single {@code service stop} call. */
    private static final long STOP_TIMEOUT_MS = 20_000L;

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern PAIR_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PAIR_LINK_PATTERN = Pattern.compile("https?://\\S+/auth/connect/[^\\s]+");

    /** Serializes every command that changes the state of the background service. */
    private static final Object LOCK = new Object();

    /** True when the web UI must pair again because the service was stopped. */
    private static final AtomicBoolean sStopped = new AtomicBoolean(false);

    /**
     * True when a stop has been requested but its helper thread has not run yet. It is used to let
     * a concurrent start finish the pending stop itself instead of starting a service that the
     * stop thread would immediately kill again.
     */
    private static volatile boolean sStopRequested = false;

    /**
     * True when {@code $PREFIX/tmp} has to be wiped after the service stopped. The cleanup is done
     * while holding {@link #LOCK}, before any new start, and only if the stop actually succeeded,
     * so that a daemon can never inherit a directory that is removed underneath it.
     */
    private static final AtomicBoolean sClearTmpRequested = new AtomicBoolean(false);

    private OpenCodeService() {
    }

    /**
     * Make sure that the service is running and return a fresh one time URL that gives the WebView
     * its authentication cookie.
     *
     * <p>If the service cannot be reached, it is stopped and started one more time. This clears
     * stale registrations and recovers from a daemon that died while its registration was left
     * behind.</p>
     *
     * @throws Exception when the service could not be started or no pair link was returned
     */
    public static String startAndPair(Context context, long startTimeoutMs, long cliTimeoutMs)
        throws Exception {
        final Context appContext = context.getApplicationContext();

        synchronized (LOCK) {
            // A stop was requested while this start was waiting for the lock. The caller (the web
            // activity) is opening the UI again, so perform the stop here and then start fresh.
            boolean serviceStopped = false;
            if (sStopRequested) {
                sStopRequested = false;
                serviceStopped = stopLocked(appContext);
            }
            clearTmpIfRequestedLocked(serviceStopped);

            sStopped.set(false);

            Exception failure = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    Logger.logWarn(LOG_TAG, "The OpenCode service did not come up, restarting it");
                    stopLocked(appContext);
                }

                try {
                    String startOutput = OpenCodeCli.run(appContext, startTimeoutMs, "service", "start");
                    String baseUrl = firstMatch(URL_PATTERN, startOutput, 0);
                    if (baseUrl == null) {
                        throw new Exception(
                            "The OpenCode service did not report its URL: " + startOutput.trim());
                    }
                    String pairUrl = createPairUrl(appContext, cliTimeoutMs,
                        trimTrailingSlash(baseUrl));

                    // A stop was requested while we held the lock, typically because the last
                    // terminal session ended right as the web UI was opening. The caller needs a
                    // running service, so consume the request instead of letting the waiting stop
                    // thread kill the daemon that was just paired with. Its TMPDIR wipe belongs to
                    // that stop and must not run under a live daemon either.
                    if (sStopRequested) {
                        sStopRequested = false;
                        sClearTmpRequested.set(false);
                        sStopped.set(false);
                        Logger.logInfo(LOG_TAG,
                            "Ignoring a pending stop because the web UI is being opened");
                    }
                    return pairUrl;
                } catch (Exception e) {
                    failure = e;
                    Logger.logStackTraceWithMessage(LOG_TAG,
                        "Attempt " + (attempt + 1) + " to start the OpenCode service failed", e);
                }
            }

            throw new Exception("The OpenCode service could not be started", failure);
        }
    }

    /**
     * Stop the background service on a helper thread.
     *
     * <p>The callback is always invoked on the main thread, even when stopping failed. It may be
     * used to call {@code stopSelf()} only after the daemon is gone.</p>
     *
     * @param clearTmp also wipe {@code $PREFIX/tmp} after the daemon has stopped successfully.
     *                 The wipe is done while holding the start/stop lock, so it can never run
     *                 after a new daemon was started.
     */
    public static void stopAsync(Context context, boolean clearTmp, @Nullable Runnable onStopped) {
        final Context appContext = context.getApplicationContext();

        // Set the flags synchronously, so that an activity that starts in the meantime knows that
        // the service is going away and that its current page has to be replaced.
        sStopped.set(true);
        sStopRequested = true;
        if (clearTmp) sClearTmpRequested.set(true);

        Thread thread = new Thread(() -> {
            try {
                synchronized (LOCK) {
                    boolean serviceStopped = false;
                    // A concurrent start may already have performed this pending stop, in which
                    // case there is nothing left to do (and TMPDIR must not be wiped).
                    if (sStopRequested) {
                        sStopRequested = false;
                        serviceStopped = stopLocked(appContext);
                    }
                    clearTmpIfRequestedLocked(serviceStopped);
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop the OpenCode service", e);
            } finally {
                if (onStopped != null) {
                    new Handler(Looper.getMainLooper()).post(onStopped);
                }
            }
        }, "opencode-service-stop");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * @return true once after the service was stopped, so that the caller can discard the current
     * WebView page and pair again. The flag is reset by this call.
     */
    public static boolean consumeStoppedByUser() {
        return sStopped.getAndSet(false);
    }

    /* ==========================================================================================
     * Helpers (always called while holding LOCK)
     * ========================================================================================== */

    private static boolean stopLocked(Context context) {
        try {
            String output = OpenCodeCli.run(context, STOP_TIMEOUT_MS, "service", "stop");
            Logger.logInfo(LOG_TAG, "Stopped the OpenCode service: " + output.trim());
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop the OpenCode service", e);
            return false;
        }
    }

    /**
     * Wipe {@code $PREFIX/tmp} after a confirmed stop, before the lock is released for a new
     * start. If stopping failed the daemon may still be running, so its TMPDIR must be left alone.
     */
    private static void clearTmpIfRequestedLocked(boolean serviceStopped) {
        if (!sClearTmpRequested.getAndSet(false)) return;
        if (!serviceStopped) {
            Logger.logWarn(LOG_TAG,
                "Not clearing TMPDIR because the OpenCode service may still be running");
            return;
        }
        TermuxShellUtils.clearTermuxTMPDIR(true);
    }

    /** @return the one time URL that gives the WebView its authentication cookie. */
    private static String createPairUrl(Context context, long cliTimeoutMs, String baseUrl)
        throws Exception {
        Exception apiFailure = null;
        try {
            String pairOutput = OpenCodeCli.run(context, cliTimeoutMs, "api", "POST", "/api/pair");
            String code = firstMatch(PAIR_CODE_PATTERN, pairOutput, 1);
            if (code != null) {
                return baseUrl + "/auth/connect/" + code;
            }
            apiFailure = new Exception(
                "OpenCode did not return a pair code: " + pairOutput.trim());
        } catch (Exception e) {
            apiFailure = e;
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Could not create a pair code through the API", e);
        }

        // Older versions do not expose /api/pair, but the pair command prints the complete link.
        String pairOutput = OpenCodeCli.run(context, cliTimeoutMs, "pair");
        String link = firstMatch(PAIR_LINK_PATTERN, pairOutput, 0);
        if (link == null) {
            throw new Exception(
                "OpenCode did not return a pair link: " + pairOutput.trim(), apiFailure);
        }
        return link;
    }

    @Nullable
    private static String firstMatch(Pattern pattern, @Nullable String text, int group) {
        if (text == null) return null;
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(group) : null;
    }

    private static String trimTrailingSlash(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') end--;
        return url.substring(0, end);
    }
}
