package com.termux.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.text.TextUtils;
import android.util.Base64;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bootstraps the official OpenCode CLI natively inside Termux and keeps it runnable.
 *
 * <p>The official OpenCode CLI is only published as a <b>glibc</b> ELF binary, while Android only
 * ships <b>bionic</b>. The binary therefore has to be patched before it can be executed:</p>
 *
 * <pre>
 *   apt-get install glibc-repo glibc-runner patchelf tar
 *     -&gt; download the official npm tarball @opencode/cli-linux-&lt;arch&gt;
 *     -&gt; tar -xzf package/bin/opencode
 *     -&gt; patchelf --set-interpreter $PREFIX/glibc/lib/ld-linux-*.so.*   (interpreter ONLY)
 *     -&gt; verify with --version -&gt; atomic rename -&gt; $PREFIX/bin/opencode launcher
 * </pre>
 *
 * <p>Everything is done from this single class so that the installation keeps its progress,
 * survives the app being killed mid download and is able to repair itself afterwards. No root, no
 * proot and no chroot is needed.</p>
 *
 * <p>The state of the installation is kept in {@link OpenCodeInstallState} and written to the
 * disk after every change, so that the app shows the real progress again after it was closed.
 * The installation itself is not bound to an activity, it keeps running in the background and the
 * user is informed through a notification.</p>
 *
 * @see <a href="https://github.com/manhduonghn/opencode-install-technique">opencode-install-technique</a>
 */
public final class OpenCodeBootstrap {

    private static final String LOG_TAG = "OpenCodeBootstrap";

    /* ==========================================================================================
     * Public API
     * ========================================================================================== */

    /** Intent extra asking the app to (re)install the OpenCode runtime. */
    public static final String EXTRA_FORCE_UPDATE = "com.termux.opencode.FORCE_UPDATE";

    /** The command the user types inside Termux to start the OpenCode TUI. */
    public static final String OPENCODE_COMMAND = "opencode";

    /**
     * A listener that is notified every time the state of the installation changes. The callback
     * is always invoked on the UI thread.
     */
    public interface InstallListener {
        /**
         * @param state the new state of the installation, never null
         */
        void onOpenCodeInstallStateChanged(OpenCodeInstallState state);
    }

    /* ==========================================================================================
     * Paths
     * ========================================================================================== */

    /** Termux root, eg. {@code /data/data/com.termux/files/usr}. */
    public static final String PREFIX = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
    /** Termux home, eg. {@code /data/data/com.termux/files/home}. */
    public static final String HOME = TermuxConstants.TERMUX_HOME_DIR_PATH;

    /** State directory of the installer. */
    public static final String OC_HOME = HOME + "/.opencode";
    /** Directory holding the patched binaries. */
    public static final String INSTALL_DIR = OC_HOME + "/installed";
    /** Directory holding the {@code opencode} symlink. */
    public static final String LINK_DIR = OC_HOME + "/bin";
    /** Symlink pointing to the currently installed binary. */
    public static final String LINK_PATH = LINK_DIR + "/opencode";
    /** Contains the version that is currently installed. */
    public static final String READY_MARKER_PATH = OC_HOME + "/.native-ready";
    /** Fingerprint ({@code <size> <mtime>}) of the binary that has been patched. */
    public static final String PATCHED_MARKER_PATH = OC_HOME + "/.patched";
    /** Full log of every installer run. */
    public static final String SETUP_LOG_PATH = OC_HOME + "/setup.log";
    /** Human readable report of the last failure. */
    public static final String ERROR_DETAIL_PATH = OC_HOME + "/last-error.txt";
    /** Snapshot of the environment, written on every run. */
    public static final String DIAGNOSTICS_PATH = OC_HOME + "/diagnostics.txt";
    /** Directory used for the temporary {@code sources.list} of the fallback mirrors. */
    public static final String APT_TMP_DIR = OC_HOME + "/apt";
    /** Scratch directory used while downloading and patching. */
    public static final String TMP_DIR = OC_HOME + "/tmp";
    /** Directory holding the glibc loader shipped by {@code glibc-runner}. */
    public static final String GLIBC_LIB_DIR = PREFIX + "/glibc/lib";
    /** Cache directory of apt, the downloaded packages are put there. */
    public static final String APT_ARCHIVES_DIR = PREFIX + "/var/cache/apt/archives";
    /** Launcher script, {@code opencode}. */
    public static final String OPENCODE_BIN_PATH = PREFIX + "/bin/opencode";
    /** Launcher script, {@code oc-install}. */
    public static final String OC_INSTALL_PATH = PREFIX + "/bin/oc-install";
    /** Installer script of older versions, removed on sight. */
    public static final String LEGACY_SETUP_SCRIPT_PATH = OC_HOME + "/setup.sh";
    /** Directory holding the second stage script of the Termux bootstrap. */
    public static final String SECOND_STAGE_DIR = PREFIX + "/etc/termux/termux-bootstrap/second-stage";
    /** Second stage script of the Termux bootstrap. */
    public static final String SECOND_STAGE_SCRIPT_PATH = SECOND_STAGE_DIR + "/termux-bootstrap-second-stage.sh";
    /** Lock file created by the second stage script once it ran. */
    public static final String SECOND_STAGE_LOCK_PATH = SECOND_STAGE_DIR + "/termux-bootstrap-second-stage.sh.lock";
    /** DNS configuration of Termux. */
    public static final String RESOLV_CONF_PATH = PREFIX + "/etc/resolv.conf";
    /** APT configuration of the user. */
    public static final String APT_SOURCES_LIST_PATH = PREFIX + "/etc/apt/sources.list";
    /** APT configuration fragments of the user. */
    public static final String APT_SOURCEPARTS_DIR = PREFIX + "/etc/apt/sources.list.d";

    /* ==========================================================================================
     * Constants
     * ========================================================================================== */

    /** Bumped whenever the launcher scripts change so that they get rewritten. */
    private static final int LAUNCHER_VERSION = 3;

    private static final String LAUNCHER_MARKER = "Managed by the OpenCode for Termux app. opencode-launcher-version: " + LAUNCHER_VERSION;

    private static final String USER_AGENT = "Termux-OpenCode";

    private static final long HTTP_CONNECT_TIMEOUT_MS = 20_000L;
    private static final long HTTP_READ_TIMEOUT_MS = 60_000L;
    private static final long APT_UPDATE_TIMEOUT_MS = 240_000L;
    private static final long APT_INSTALL_TIMEOUT_MS = 900_000L;
    private static final long SECOND_STAGE_TIMEOUT_MS = 240_000L;
    private static final long VERIFY_TIMEOUT_MS = 90_000L;
    private static final long PATCH_TIMEOUT_MS = 300_000L;
    private static final long EXTRACT_TIMEOUT_MS = 180_000L;
    private static final long PROCESS_POLL_INTERVAL_MS = 100L;

    /** Maximum number of characters of a single command output kept in memory. */
    private static final int MAX_OUTPUT_CHARS = 256 * 1024;
    /** Number of steps kept for the error report. */
    private static final int MAX_STEPS = 40;
    /** Number of log lines appended to the error report. */
    private static final int FAILURE_LOG_LINES = 14;
    /** Maximum length of the detail part of a notification. */
    private static final int MAX_DETAIL_CHARS = 900;

    /** Mirrors of the main Termux repository, tried in order. */
    private static final String[] MAIN_MIRRORS = {
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
        "https://grimler.se/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
    };

    /** Mirrors of the Termux glibc repository, tried in order. */
    private static final String[] GLIBC_MIRRORS = {
        "https://packages-cf.termux.dev/apt/termux-glibc",
        "https://packages.termux.dev/apt/termux-glibc"
    };

    /** Endpoint telling which OpenCode version is the latest one. */
    private static final String VERSION_API_URL = "https://opencode.ai/update/api/latest/cli/npm";
    /** npm registry used as fallback for the version lookup and for the download. */
    private static final String NPM_REGISTRY_URL = "https://registry.npmjs.org";

    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_OUTPUT_PATTERN = Pattern.compile("(?s).*\\d+\\.\\d+.*");
    /** The uri of a package, printed by {@code apt-get install --print-uris}. */
    private static final Pattern APT_URI_PATTERN = Pattern.compile("^'([^']+)'\\s+(\\S+)\\s+(\\d+)\\s+(?:(\\w+):)?([0-9a-fA-F]+)");
    /** The size of a package, printed by apt at the end of a line. */
    private static final Pattern APT_SIZE_PATTERN = Pattern.compile("\\[([0-9][0-9.,]*)\\s*([kKmMgG]?B)?\\]");

    /** Notification channel used for the progress and the result of an installation. */
    public static final String NOTIFICATION_CHANNEL_ID = "opencode_notification_channel";
    /** Name of the notification channel. */
    public static final String NOTIFICATION_CHANNEL_NAME = "OpenCode";
    /** Id of the notification used for the progress and the result of an installation. */
    public static final int NOTIFICATION_ID = 1338;

    private static final Object sLock = new Object();
    /** The installer that is currently running, null when nothing is being installed. */
    private static Installer sInstaller = null;
    /** True while the silent maintenance of the runtime runs in the background. */
    private static boolean sMaintenanceRunning = false;
    /** The listeners that want to know about the progress of the installation. */
    private static final List<InstallListener> sListeners = new CopyOnWriteArrayList<>();
    /** The callbacks that are waiting for the installation to finish. */
    private static final List<Runnable> sInstallWaiters = new ArrayList<>();
    /** The state of the installation, it is mirrored on the disk. */
    private static OpenCodeInstallState sState = new OpenCodeInstallState();
    /** The handler of the UI thread, used to notify the listeners. */
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private OpenCodeBootstrap() {}

    /* ==========================================================================================
     * Public API
     * ========================================================================================== */

    /**
     * Make sure that the OpenCode runtime is complete and call {@code onReady} afterwards.
     *
     * <p>If something is missing, the installer is started and {@code onReady} is called once the
     * installation finished (successfully or not). The callback is always invoked on the UI
     * thread. When an installation is already running, the request is queued and the progress is
     * shown by the listeners.</p>
     */
    public static void setupIfNeeded(final Context context, final Runnable onReady) {
        if (context == null) return;

        if (!isTermuxBootstrapReady()) {
            Logger.logWarn(LOG_TAG, "The Termux bootstrap is not ready yet, skipping the OpenCode setup.");
            runOnUiThread(context, onReady);
            return;
        }

        // Nothing has to be downloaded. Refresh the launchers and the patched binary in the
        // background without showing any progress UI, otherwise the installation dialog would
        // flash on every cold start even though OpenCode is already installed.
        if (!isInstallRunning() && runtimeProblems().isEmpty()) {
            clearFinishedState();
            maintainInBackground(context.getApplicationContext());
            runOnUiThread(context, onReady);
            return;
        }

        final Installer installer;
        synchronized (sLock) {
            if (sInstaller != null) {
                Logger.logInfo(LOG_TAG, "An installation is already running, queueing the request.");
                if (onReady != null) sInstallWaiters.add(onReady);
                return;
            }
            installer = new Installer(context, false);
            sInstaller = installer;
            if (onReady != null) sInstallWaiters.add(onReady);
        }

        installer.start();
    }

    /**
     * Force a (re)installation of the OpenCode runtime. The user is asked through the
     * notification, the installation itself runs in the background.
     */
    public static void requestUpdate(final Activity activity) {
        if (activity == null) return;

        if (!isTermuxBootstrapReady()) {
            Logger.logWarn(LOG_TAG, "The Termux bootstrap is not ready yet, cannot update OpenCode.");
            return;
        }

        final Context context = activity.getApplicationContext();

        final Installer installer;
        synchronized (sLock) {
            if (sInstaller != null) {
                Logger.logInfo(LOG_TAG, "An installation is already running, ignoring the update request.");
                return;
            }
            installer = new Installer(context, true);
            sInstaller = installer;
        }

        installer.start();
    }

    /**
     * Cheap check run whenever the app comes to the foreground: if the runtime is broken it is
     * repaired, otherwise nothing happens.
     */
    public static void checkRuntime(final Activity activity) {
        if (activity == null) return;
        if (!isTermuxBootstrapReady()) return;

        final List<String> problems = runtimeProblems();
        if (problems.isEmpty()) {
            // Everything is fine, but the binary may have been replaced by the OpenCode
            // auto updater, so make sure it is still patched.
            clearFinishedState();
            maintainInBackground(activity.getApplicationContext());
        } else {
            Logger.logInfo(LOG_TAG, "The OpenCode runtime is incomplete: " + TextUtils.join(", ", problems));
            setupIfNeeded(activity, null);
        }
    }

    /**
     * Drop the state of an installation that finished (successfully or not) when the runtime is
     * complete, so that a stale result is not shown again on the next start.
     */
    private static void clearFinishedState() {
        synchronized (sLock) {
            if (sInstaller != null) return;
            sState = new OpenCodeInstallState();
        }
        OpenCodeInstallState.clear();
    }

    /**
     * Refresh the launchers and repair the patched binary in the background. No progress is
     * published and no dialog is shown, so this is safe to call on every start of the app.
     */
    private static void maintainInBackground(final Context context) {
        synchronized (sLock) {
            // Never touch the runtime while an installation is replacing it.
            if (sInstaller != null || sMaintenanceRunning) return;
            sMaintenanceRunning = true;
        }

        new Thread(() -> {
            try {
                Installer installer = new Installer(context, false);
                installer.logOnly = true;
                installer.doMaintenance();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to maintain the OpenCode runtime", e);
            } finally {
                synchronized (sLock) {
                    sMaintenanceRunning = false;
                }
            }
        }, "opencode-maintenance").start();
    }

    /**
     * Read the state of the last installation from the disk. It has to be called once when the
     * process of the app starts, so that the screen of the app shows the real progress even when
     * Android killed the app while it was downloading something.
     */
    public static void restoreState() {
        synchronized (sLock) {
            // An installation that is still running has a state that is up to date.
            if (sInstaller != null) return;

            OpenCodeInstallState saved = OpenCodeInstallState.load();
            if (saved == null) return;

            // A succeeded or failed installation is stale when the runtime is complete, the app
            // must not show the result of an earlier run again on every start.
            if (!saved.running && (saved.done || saved.failed) && runtimeProblems().isEmpty()) {
                OpenCodeInstallState.clear();
                return;
            }

            if (saved.running) {
                // The process was killed, the installation is not running anymore.
                saved.running = false;
                saved.message = saved.message == null ? "" : saved.message;
                saved.detail = "The app was closed, the installation was interrupted.";
                saved.addStep("The previous installation was interrupted, it is resumed automatically.");
            }

            sState = saved;
        }
    }

    /** @return the current state of the installation, never null. */
    public static OpenCodeInstallState getInstallState() {
        synchronized (sLock) {
            return copyOf(sState);
        }
    }

    /** @return true while an installation is running. */
    public static boolean isInstallRunning() {
        synchronized (sLock) {
            return sInstaller != null;
        }
    }

    /** Register a listener that is notified about every change of the state. */
    public static void addInstallListener(InstallListener listener) {
        if (listener == null) return;
        sListeners.add(listener);
    }

    /** Unregister a listener. */
    public static void removeInstallListener(InstallListener listener) {
        if (listener == null) return;
        sListeners.remove(listener);
    }

    /**
     * @return the list of things that are missing for OpenCode to run. An empty list means that
     * the runtime is complete.
     */
    public static List<String> runtimeProblems() {
        List<String> problems = new ArrayList<>();

        if (!new File(READY_MARKER_PATH).isFile())
            problems.add("the installed version marker (" + READY_MARKER_PATH + ")");

        if (!new File(OPENCODE_BIN_PATH).exists())
            problems.add("the opencode launcher (" + OPENCODE_BIN_PATH + ")");

        if (!new File(OC_INSTALL_PATH).exists())
            problems.add("the oc-install launcher (" + OC_INSTALL_PATH + ")");

        if (!new File(LINK_PATH).exists())
            problems.add("the opencode symlink (" + LINK_PATH + ")");

        if (findGlibcLoader() == null)
            problems.add("the glibc loader (" + GLIBC_LIB_DIR + "/ld-linux-*.so.*)");

        if (!isExecutable(new File(PREFIX + "/bin/patchelf")))
            problems.add("the patchelf tool (" + PREFIX + "/bin/patchelf)");

        if (!isExecutable(new File(PREFIX + "/bin/tar")))
            problems.add("the tar tool (" + PREFIX + "/bin/tar)");

        return problems;
    }

    /** @return true if the OpenCode runtime is complete and can be started. */
    public static boolean isRuntimeComplete() {
        return runtimeProblems().isEmpty();
    }

    /** @return the version of OpenCode that is currently installed, or an empty string. */
    public static String getInstalledVersion() {
        return readTrimmed(new File(READY_MARKER_PATH));
    }

    /** @return true if the Termux bootstrap has been extracted, ie. if {@code $PREFIX} is usable. */
    public static boolean isTermuxBootstrapReady() {
        return new File(PREFIX + "/bin/apt-get").exists();
    }

    /* ==========================================================================================
     * Installer
     * ========================================================================================== */

    private static final class Installer {

        private final Context context;
        private final boolean forceUpdate;
        private final Map<String, String> env = new HashMap<>();
        private final List<String> steps = new ArrayList<>();

        private String lastOutput = "";
        private boolean logOnly = false;
        /** apt options pointing at the temporary sources.list, null when the user config works. */
        private List<String> mTempSourcesOptions = null;

        Installer(Context context, boolean forceUpdate) {
            this.context = context == null ? null : context.getApplicationContext();
            this.forceUpdate = forceUpdate;

            try {
                env.putAll(new TermuxShellEnvironment().getEnvironment(this.context, false));
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to build the shell environment", e);
                env.put("HOME", HOME);
                env.put("PREFIX", PREFIX);
                env.put("PATH", PREFIX + "/bin");
                env.put("TMPDIR", PREFIX + "/tmp");
            }

            env.put("TERM", "xterm-256color");
            env.put("LANG", "en_US.UTF-8");
            env.put("COLORTERM", "truecolor");
            env.put("DEBIAN_FRONTEND", "noninteractive");
            // Do not let maintainer scripts change the mirror of the user.
            env.put("TERMUX_PKG_NO_MIRROR_SELECT", "1");
            // The bionic preload of Termux must never reach a glibc process.
            env.remove("LD_PRELOAD");
            env.remove("LD_LIBRARY_PATH");
        }

        void start() {
            new Thread(this::run, "opencode-setup").start();
        }

        private void run() {
            boolean success = false;
            String errorMessage = null;
            try {
                writeDiagnostics();
                if (forceUpdate) {
                    setStage(OpenCodeInstallState.STAGE_PREPARE, "Updating OpenCode...");
                    doInstall();
                } else if (!runtimeProblems().isEmpty()) {
                    setStage(OpenCodeInstallState.STAGE_PREPARE, "Preparing the OpenCode runtime...");
                    doInstall();
                } else {
                    setStage(OpenCodeInstallState.STAGE_PREPARE, "Checking the OpenCode runtime...");
                    doMaintenance();
                }
                success = true;
            } catch (InstallException e) {
                errorMessage = e.getMessage();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "The OpenCode installation failed", e);
                errorMessage = "The OpenCode installation failed: " + e;
            }

            if (success) {
                setStageDone();
                notifyFinished("OpenCode is ready", "Version " + getInstalledVersion() + " is installed.", false);
            } else {
                handleFailure(errorMessage == null ? "Unknown error" : errorMessage);
            }

            releaseWaiters();
        }

        /* ------------------------------------------------------------------ install pipeline */

        private void doInstall() {
            ensureSecondStage();
            ensureResolvConf();

            setStage(OpenCodeInstallState.STAGE_APT_UPDATE, "Refreshing the package lists...");
            if (!aptUpdate(false))
                throw new InstallException("Could not refresh the package lists from any mirror. Check the Wi-Fi/mobile connection and try again.");

            setStage(OpenCodeInstallState.STAGE_APT_INSTALL, "Installing the glibc repository...");
            if (!aptInstall(Arrays.asList("glibc-repo")))
                throw new InstallException("Could not install the 'glibc-repo' package. Check the Wi-Fi/mobile connection and try again.");

            setStage(OpenCodeInstallState.STAGE_APT_UPDATE, "Refreshing the package lists of the glibc repository...");
            if (!aptUpdate(true))
                throw new InstallException("Could not refresh the package lists of the glibc repository.");

            // 'termux-am' is needed by the 'oc-install' launcher, it asks the app to install
            // or update the runtime.
            setStage(OpenCodeInstallState.STAGE_APT_INSTALL, "Installing the helper packages...");
            if (!aptInstall(Arrays.asList("glibc-runner", "patchelf", "tar", "termux-am")))
                Logger.logWarn(LOG_TAG, "Some of the helper packages could not be installed, continuing anyway.");

            // 'patchelf' links against libc++_shared.so, an old libc++ makes it die with
            // "cannot locate symbol __hash_memory". Verify it before downloading ~100 MB.
            if (!patchelfWorks()) {
                setStage(OpenCodeInstallState.STAGE_APT_INSTALL, "Repairing the patchelf tool...");
                aptInstall(Arrays.asList("patchelf", "libc++"));
                if (!patchelfWorks()) {
                    aptReinstall(Arrays.asList("patchelf", "libc++"));
                    if (!patchelfWorks())
                        throw new InstallException("The 'patchelf' tool cannot run on this device: " + tail(lastOutput, 400));
                }
            }

            String loader = findGlibcLoader();
            if (loader == null)
                throw new InstallException("The glibc loader (ld-linux-*.so.*) was not found in " + GLIBC_LIB_DIR + ". Install the 'glibc-runner' package and try again.");

            String arch = getOpenCodeArch();
            if (arch == null)
                throw new InstallException("The CPU architecture \"" + Build.SUPPORTED_ABIS[0] + "\" has no official OpenCode build.");

            String version = fetchLatestVersion();

            setStage(OpenCodeInstallState.STAGE_DOWNLOAD, "Downloading OpenCode " + version + "...");
            File tarball = downloadTarball(arch, version);
            try {
                setStage(OpenCodeInstallState.STAGE_EXTRACT, "Extracting the OpenCode package...");
                File binary = extractBinary(tarball);
                File installed = installBinary(binary, version, loader);
                if (installed == null) {
                    // The new build does not run, keep the old one if there is one.
                    if (!new File(LINK_PATH).exists())
                        throw new InstallException("The downloaded OpenCode build does not run on this device.");
                    status("WARNING: the downloaded build does not run on this device, keeping the installed one.");
                } else {
                    linkTo(installed);
                    try {
                        writeTextFile(new File(PATCHED_MARKER_PATH), binaryStamp(installed));
                        writeTextFile(new File(READY_MARKER_PATH), version);
                    } catch (IOException e) {
                        throw new InstallException("Could not write the installation markers: " + e.getMessage());
                    }
                }
                writeLaunchers();
            } finally {
                deleteQuietly(tarball);
            }

            // The old installer script must not stay around, it would fight with the app.
            deleteQuietly(new File(LEGACY_SETUP_SCRIPT_PATH));
            deleteQuietly(new File(APT_TMP_DIR));
        }

        private void doMaintenance() {
            deleteQuietly(new File(LEGACY_SETUP_SCRIPT_PATH));
            restoreLauncher(new File(OPENCODE_BIN_PATH), buildOpenCodeLauncher());
            restoreLauncher(new File(OC_INSTALL_PATH), buildOcInstallLauncher());
            repairInterpreterIfNeeded(context);
        }

        /* ------------------------------------------------------------------ apt */

        private boolean aptUpdate(boolean withGlibc) {
            if (apt(Arrays.asList("update"), APT_UPDATE_TIMEOUT_MS, true, new AptUpdateListener()))
                return true;

            // The configuration of the user may be broken, try the known mirrors with a
            // temporary sources.list so that nothing of the user is touched.
            for (String main : MAIN_MIRRORS) {
                for (String glibc : (withGlibc ? GLIBC_MIRRORS : new String[]{null})) {
                    status("Trying the mirror " + main + "...");
                    writeTempSources(main, glibc);
                    List<String> args = new ArrayList<>();
                    args.add("update");
                    if (apt(args, APT_UPDATE_TIMEOUT_MS, true, new AptUpdateListener())) {
                        // Use the working mirrors for the installs as well, the configuration
                        // of the user is most likely broken.
                        mTempSourcesOptions = tempSourcesOptions();
                        return true;
                    }
                }
            }

            deleteQuietly(new File(APT_TMP_DIR));
            mTempSourcesOptions = null;
            return false;
        }

        private boolean aptInstall(List<String> packages) {
            List<String> args = new ArrayList<>();
            args.add("install");
            args.add("-y");
            args.addAll(packages);

            // Download the packages with a progress bar of our own, apt does not tell how much
            // has been downloaded already. When that is not possible apt does the download and
            // the progress is guessed from its output.
            boolean downloaded = preDownloadPackages(packages);
            if (downloaded)
                setProgress(100, "");

            if (apt(args, APT_INSTALL_TIMEOUT_MS, true, downloaded ? null : new AptInstallListener()))
                return true;

            // The package database may be broken if the app was killed during an install.
            status("Repairing the package database...");
            run(Arrays.asList(PREFIX + "/bin/dpkg", "--configure", "-a"), APT_INSTALL_TIMEOUT_MS, true, null);
            apt(Arrays.asList("-f", "install", "-y"), APT_INSTALL_TIMEOUT_MS, true, null);
            return apt(args, APT_INSTALL_TIMEOUT_MS, true, null);
        }

        private boolean aptReinstall(List<String> packages) {
            List<String> args = new ArrayList<>();
            args.add("install");
            args.add("--reinstall");
            args.add("-y");
            args.addAll(packages);
            return apt(args, APT_INSTALL_TIMEOUT_MS, true, null);
        }

        private boolean apt(List<String> extraArgs, long timeoutMs, boolean stream, LineListener listener) {
            List<String> cmd = new ArrayList<>();
            cmd.add(PREFIX + "/bin/apt-get");
            cmd.add("-o");
            cmd.add("Acquire::Retries=3");
            cmd.add("-o");
            cmd.add("Acquire::http::Timeout=30");
            cmd.add("-o");
            cmd.add("Acquire::https::Timeout=30");
            if (mTempSourcesOptions != null)
                cmd.addAll(mTempSourcesOptions);
            cmd.addAll(extraArgs);

            return run(cmd, timeoutMs, stream, listener).exitCode == 0;
        }

        /** @return the apt options that make apt use the temporary sources.list. */
        private static List<String> tempSourcesOptions() {
            List<String> options = new ArrayList<>();
            options.add("-o");
            options.add("Dir::Etc::sourcelist=" + new File(APT_TMP_DIR, "sources.list").getAbsolutePath());
            options.add("-o");
            options.add("Dir::Etc::sourceparts=-");
            return options;
        }

        private void writeTempSources(String mainMirror, String glibcMirror) {
            StringBuilder sb = new StringBuilder();
            sb.append("deb ").append(mainMirror).append(" stable main\n");
            if (glibcMirror != null)
                sb.append("deb ").append(glibcMirror).append(" glibc stable\n");
            try {
                writeTextFile(new File(APT_TMP_DIR, "sources.list"), sb.toString());
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write the temporary sources.list", e);
            }
        }

        /**
         * Download the packages of an apt transaction with a progress bar, so that the user knows
         * how much has been downloaded already.
         *
         * <p>The files are put into the cache of apt, apt then installs them without downloading
         * them a second time.</p>
         *
         * @return true when all packages were downloaded, false when apt has to do it itself
         */
        private boolean preDownloadPackages(List<String> packages) {
            List<String> cmd = new ArrayList<>();
            cmd.add(PREFIX + "/bin/apt-get");
            cmd.add("-o");
            cmd.add("Acquire::Retries=3");
            cmd.add("-o");
            cmd.add("Acquire::http::Timeout=30");
            cmd.add("-o");
            cmd.add("Acquire::https::Timeout=30");
            if (mTempSourcesOptions != null)
                cmd.addAll(mTempSourcesOptions);
            cmd.add("install");
            cmd.add("-y");
            cmd.add("--print-uris");
            cmd.addAll(packages);

            CmdResult result = run(cmd, APT_UPDATE_TIMEOUT_MS, false, null);
            if (result.exitCode != 0)
                return false;

            List<PackageUri> uris = parsePackageUris(result.output);
            if (uris.isEmpty())
                return false;

            long total = 0;
            for (PackageUri uri : uris) {
                if (uri.size > 0)
                    total += uri.size;
            }
            if (total <= 0)
                return false;

            File archivesDir = new File(APT_ARCHIVES_DIR);
            File partialDir = new File(archivesDir, "partial");
            if (!partialDir.isDirectory() && !partialDir.mkdirs())
                return false;

            long done = 0;
            int index = 0;
            for (final PackageUri uri : uris) {
                index++;
                File target = new File(archivesDir, uri.fileName);
                if (target.isFile() && uri.matches(target)) {
                    done += uri.size;
                    continue;
                }

                File temp = new File(partialDir, uri.fileName);
                final long base = done;
                final long totalBytes = total;
                final int number = index;
                final int count = uris.size();

                try {
                    downloadFile(uri.url, temp, true, (bytesDone, bytesTotal, detail) -> {
                        long overall = base + bytesDone;
                        setBytes(overall, totalBytes, "Package " + number + "/" + count + ": " +
                            uri.fileName + " - " + detail);
                    });
                } catch (IOException e) {
                    Logger.logWarn(LOG_TAG, "Could not download " + uri.url + ": " + e.getMessage());
                    deleteQuietly(temp);
                    return false;
                }

                if (!uri.matches(temp)) {
                    Logger.logWarn(LOG_TAG, "The checksum of " + uri.fileName + " does not match, letting apt download it.");
                    deleteQuietly(temp);
                    return false;
                }

                deleteQuietly(target);
                if (!temp.renameTo(target)) {
                    if (!copyFile(temp, target)) {
                        deleteQuietly(temp);
                        return false;
                    }
                    deleteQuietly(temp);
                }

                done += uri.size;
                setBytes(done, total, "Package " + number + "/" + count + ": " + uri.fileName + " - done");
            }

            return true;
        }

        /** @return the packages that apt would download for the given transaction. */
        private static List<PackageUri> parsePackageUris(String output) {
            List<PackageUri> uris = new ArrayList<>();
            if (output == null) return uris;

            for (String line : output.split("\n")) {
                line = line.trim();
                if (!line.startsWith("'")) continue;

                Matcher matcher = APT_URI_PATTERN.matcher(line);
                if (!matcher.find()) continue;

                PackageUri uri = new PackageUri();
                uri.url = matcher.group(1);
                uri.fileName = matcher.group(2);
                uri.size = Long.parseLong(matcher.group(3));
                uri.hashAlgorithm = matcher.group(4);
                uri.hash = matcher.group(5);
                if (uri.url == null || uri.fileName == null) continue;

                uris.add(uri);
            }

            return uris;
        }

        /* ------------------------------------------------------------------ version & download */

        private String fetchLatestVersion() {
            String version = httpGetString(VERSION_API_URL);
            String match = extractVersion(version);
            if (match != null) return match;

            // The API did not answer, try the npm registry.
            String arch = getOpenCodeArch();
            if (arch != null) {
                String meta = httpGetString(NPM_REGISTRY_URL + "/@opencode/cli-linux-" + arch + "/latest");
                match = extractVersion(meta);
                if (match != null) return match;
            }

            throw new InstallException("Could not determine the OpenCode version (check the network).");
        }

        private static String extractVersion(String json) {
            if (json == null) return null;
            Matcher matcher = VERSION_PATTERN.matcher(json);
            if (matcher.find()) return matcher.group(1);
            return null;
        }

        private File downloadTarball(String arch, String version) {
            String fileName = "cli-linux-" + arch + "-" + version + ".tgz";
            String[] urls = {
                NPM_REGISTRY_URL + "/@opencode/cli-linux-" + arch + "/-/" + fileName,
                NPM_REGISTRY_URL + "/@opencode-ai/cli-linux-" + arch + "/-/" + fileName
            };

            File tmpDir = new File(TMP_DIR);
            if (!tmpDir.isDirectory() && !tmpDir.mkdirs())
                throw new InstallException("Could not create the temporary directory " + TMP_DIR);

            String expectedIntegrity = fetchIntegrity(arch);

            IOException lastError = null;
            for (String url : urls) {
                File target = new File(tmpDir, fileName);
                try {
                    setStage(OpenCodeInstallState.STAGE_DOWNLOAD, "Downloading OpenCode " + version + "...");
                    downloadTarballTo(url, target, expectedIntegrity);
                    return target;
                } catch (IOException e) {
                    lastError = e;
                    Logger.logWarn(LOG_TAG, "Download of " + url + " failed: " + e.getMessage());
                    status("The first source failed, trying the alternative npm package name...");
                }
            }

            throw new InstallException("Could not download the OpenCode package: " + (lastError == null ? "unknown error" : lastError.getMessage()));
        }

        /**
         * Download the tarball of OpenCode. A file that was downloaded before is continued where
         * it stopped, so that the progress is kept when the app is closed in the middle of the
         * download.
         */
        private void downloadTarballTo(String url, File target, String expectedIntegrity) throws IOException {
            // The file may have been downloaded completely before the app was closed.
            if (expectedIntegrity != null && target.isFile() && verifySha512(target, expectedIntegrity))
                return;

            try {
                downloadFile(url, target, true, this::reportDownloadProgress);
            } catch (IOException e) {
                // The partial file may be useless, download it again from the beginning.
                Logger.logWarn(LOG_TAG, "Could not continue the download of " + url + ": " + e.getMessage());
                deleteQuietly(target);
                downloadFile(url, target, false, this::reportDownloadProgress);
            }

            if (expectedIntegrity != null && !verifySha512(target, expectedIntegrity)) {
                deleteQuietly(target);
                downloadFile(url, target, false, this::reportDownloadProgress);
                if (!verifySha512(target, expectedIntegrity))
                    throw new IOException("The checksum of the downloaded file does not match the one of the npm registry.");
            }
        }

        /** @return the base64 encoded sha512 digest of the tarball as published by npm. */
        private String fetchIntegrity(String arch) {
            try {
                String meta = httpGetString(NPM_REGISTRY_URL + "/@opencode/cli-linux-" + arch + "/latest");
                if (meta == null) return null;
                Matcher matcher = Pattern.compile("\"integrity\"\\s*:\\s*\"sha512-([^\"]+)\"").matcher(meta);
                if (matcher.find()) return matcher.group(1);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Could not fetch the checksum of the OpenCode package: " + e.getMessage());
            }
            return null;
        }

        private static boolean verifySha512(File file, String expectedBase64) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-512");
                byte[] buffer = new byte[64 * 1024];
                try (InputStream in = new FileInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1)
                        digest.update(buffer, 0, read);
                }
                String actual = Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
                return actual.equals(expectedBase64);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not verify the checksum of " + file, e);
                return false;
            }
        }

        /**
         * Download a file and report the progress.
         *
         * @param url      the url of the file
         * @param target   the file that is written
         * @param resume   true when the download may be continued where it stopped
         * @param listener called with the number of bytes and the total size
         * @return the number of bytes that were downloaded in this run
         */
        private long downloadFile(String url, File target, boolean resume, DownloadListener listener) throws IOException {
            long alreadyHave = 0;
            if (resume && target.isFile())
                alreadyHave = target.length();

            HttpURLConnection connection = openConnection(url, alreadyHave);
            try {
                connection.connect();
                long contentLength = connection.getContentLength();
                long total;
                boolean append;

                if (connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL) {
                    append = true;
                    total = alreadyHave + (contentLength > 0 ? contentLength : 0);
                } else {
                    // The server ignored the range request, the file is downloaded again.
                    append = false;
                    alreadyHave = 0;
                    total = contentLength;
                }

                long startedAt = SystemClock.uptimeMillis();
                long lastReportAt = startedAt;
                int lastPercent = -1;
                long read = alreadyHave;

                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(target, append)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                        read += count;

                        int percent = total > 0 ? (int) (read * 100 / total) : 0;
                        long now = SystemClock.uptimeMillis();
                        boolean step = percent >= lastPercent + 2 || percent == 100;
                        if (percent != lastPercent && (step || now - lastReportAt >= 3000)) {
                            lastPercent = percent;
                            lastReportAt = now;
                            long elapsedMs = Math.max(1, now - startedAt);
                            double rate = (read / 1024.0 / 1024.0) / (elapsedMs / 1000.0);
                            long eta = rate > 0.01 ? (long) ((total - read) / (rate * 1024 * 1024)) : 0;
                            if (listener != null)
                                listener.onDownloadProgress(read, total, formatTransferDetail(read, total, rate, eta));
                        }
                    }
                    out.flush();
                }

                return read - alreadyHave;
            } finally {
                connection.disconnect();
            }
        }

        /** Report the progress of the download of the OpenCode package. */
        private void reportDownloadProgress(long bytesDone, long bytesTotal, String detail) {
            setBytes(bytesDone, bytesTotal, detail);
        }

        private static String formatTransferDetail(long done, long total, double rate, long etaSeconds) {
            StringBuilder sb = new StringBuilder();
            sb.append(OpenCodeInstallDialog.formatSize(done));
            if (total > 0)
                sb.append(" / ").append(OpenCodeInstallDialog.formatSize(total));
            if (rate > 0.01)
                sb.append(String.format(Locale.US, " - %.2f MB/s", rate));
            if (etaSeconds > 0)
                sb.append(" - ").append(formatEta(etaSeconds)).append(" left");
            return sb.toString();
        }

        private static String formatEta(long seconds) {
            if (seconds <= 0) return "?";
            long minutes = seconds / 60;
            long rest = seconds % 60;
            if (minutes <= 0) return rest + "s";
            return minutes + "m" + rest + "s";
        }

        private HttpURLConnection openConnection(String url) throws IOException {
            return openConnection(url, 0);
        }

        private HttpURLConnection openConnection(String url, long rangeStart) throws IOException {
            String current = url;
            HttpURLConnection connection = null;
            for (int hop = 0; hop < 6; hop++) {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout((int) HTTP_CONNECT_TIMEOUT_MS);
                connection.setReadTimeout((int) HTTP_READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "*/*");
                if (rangeStart > 0)
                    connection.setRequestProperty("Range", "bytes=" + rangeStart + "-");
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 ||
                    code == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null) throw new IOException("Missing Location header for " + current);
                    current = new URL(new URL(current), location).toString();
                    connection = null;
                    continue;
                }
                if (code < 200 || code >= 300)
                    throw new IOException("The server answered with HTTP " + code + " for " + current);
                return connection;
            }
            throw new IOException("Too many redirects for " + url);
        }

        private String httpGetString(String url) {
            HttpURLConnection connection = null;
            try {
                connection = openConnection(url);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                        if (sb.length() > 1024 * 1024) break;
                    }
                }
                return sb.toString();
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "The request to " + url + " failed: " + e.getMessage());
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        /* ------------------------------------------------------------------ extraction & patching */

        private File extractBinary(File tarball) {
            File tmpDir = new File(TMP_DIR);
            File packageDir = new File(tmpDir, "package");

            status("Extracting the OpenCode package...");
            deleteQuietly(packageDir);

            CmdResult result = run(Arrays.asList(PREFIX + "/bin/tar", "-xzf", tarball.getAbsolutePath(),
                "-C", tmpDir.getAbsolutePath(), "package/bin/opencode"), EXTRACT_TIMEOUT_MS, false, null);
            if (result.exitCode != 0) {
                deleteQuietly(packageDir);
                throw new InstallException("Extracting the tarball failed (exit " + result.exitCode + "): " + tail(result.output, 400));
            }

            File binary = new File(packageDir, "bin/opencode");
            if (!binary.isFile())
                throw new InstallException("The downloaded package does not contain the expected binary.");

            return binary;
        }

        /**
         * Patch, verify and install the binary.
         *
         * @return the installed binary or null if the downloaded build does not run and an older
         * version is already installed.
         */
        private File installBinary(File binary, String version, String loader) {
            File installDir = new File(INSTALL_DIR);
            if (!installDir.isDirectory() && !installDir.mkdirs())
                throw new InstallException("Could not create the directory " + INSTALL_DIR);

            File target = new File(installDir, "opencode-v" + version);
            File staged = new File(installDir, ".opencode-v" + version + ".new");
            deleteQuietly(staged);

            // Move the file to the staging area of the same filesystem so that it can be
            // replaced atomically later on.
            if (!binary.renameTo(staged)) {
                if (!copyFile(binary, staged)) {
                    deleteQuietly(staged);
                    throw new InstallException("Could not stage the OpenCode binary.");
                }
                deleteQuietly(binary);
            }

            setStage(OpenCodeInstallState.STAGE_PATCH, "Patching the OpenCode binary...");
            CmdResult patch = patchInterpreter(staged, loader);
            if (patch.exitCode != 0) {
                status("The patch failed, reinstalling patchelf and retrying...");
                aptReinstall(Arrays.asList("patchelf", "libc++"));
                patch = patchInterpreter(staged, loader);
            }
            if (patch.exitCode != 0) {
                deleteQuietly(staged);
                throw new InstallException("Could not patch the OpenCode binary: " + tail(patch.output, 400));
            }

            try {
                Os.chmod(staged.getAbsolutePath(), 0755);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not make the OpenCode binary executable", e);
            }

            setStage(OpenCodeInstallState.STAGE_VERIFY, "Verifying the OpenCode binary...");
            CmdResult verify = run(Arrays.asList(staged.getAbsolutePath(), "--version"), VERIFY_TIMEOUT_MS, false, null);
            if (!looksLikeAVersion(verify)) {
                deleteQuietly(staged);
                if (target.isFile()) {
                    status("WARNING: the downloaded build does not run on this device, keeping the installed one.");
                    return null;
                }
                throw new InstallException("The downloaded OpenCode build does not run on this device: " + tail(verify.output, 400));
            }

            // Atomic replace of the installed binary.
            deleteQuietly(target);
            if (!staged.renameTo(target)) {
                if (!copyFile(staged, target)) {
                    deleteQuietly(staged);
                    throw new InstallException("Could not install the OpenCode binary.");
                }
                deleteQuietly(staged);
            }

            try {
                Os.chmod(target.getAbsolutePath(), 0755);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not make the OpenCode binary executable", e);
            }

            return target;
        }

        private CmdResult patchInterpreter(File file, String loader) {
            return run(Arrays.asList(PREFIX + "/bin/patchelf", "--set-interpreter", loader, file.getAbsolutePath()),
                PATCH_TIMEOUT_MS, false, null);
        }

        private boolean patchelfWorks() {
            return run(Arrays.asList(PREFIX + "/bin/patchelf", "--version"), VERIFY_TIMEOUT_MS, false, null).exitCode == 0;
        }

        private static boolean looksLikeAVersion(CmdResult result) {
            return result.exitCode == 0 && VERSION_OUTPUT_PATTERN.matcher(result.output == null ? "" : result.output).matches();
        }

        private void linkTo(File target) {
            try {
                File linkDir = new File(LINK_DIR);
                if (!linkDir.isDirectory() && !linkDir.mkdirs())
                    throw new IOException("Could not create " + LINK_DIR);
                deleteQuietly(new File(LINK_PATH));
                Os.symlink(target.getAbsolutePath(), LINK_PATH);
            } catch (Exception e) {
                throw new InstallException("Could not create the opencode symlink: " + e.getMessage());
            }
        }

        /* ------------------------------------------------------------------ launchers */

        private void writeLaunchers() {
            restoreLauncher(new File(OPENCODE_BIN_PATH), buildOpenCodeLauncher());
            restoreLauncher(new File(OC_INSTALL_PATH), buildOcInstallLauncher());
        }

        /**
         * Write the launcher, but only if the file on disk is not the launcher of this app
         * already. A launcher written by an older build is replaced, a correct one is kept.
         */
        private void restoreLauncher(File file, String content) {
            File temp = null;
            try {
                String current = readTextFile(file);
                if (current != null && current.contains(LAUNCHER_MARKER))
                    return;

                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Could not create the directory " + parent);

                // Write to a temporary file and rename it, so that a process that starts the
                // launcher while the maintenance is running never sees a half written script.
                temp = File.createTempFile(file.getName(), ".tmp", parent);
                writeTextFile(temp, content);
                try {
                    Os.chmod(temp.getAbsolutePath(), 0755);
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Could not make " + temp + " executable", e);
                }

                if (!temp.renameTo(file)) {
                    // Some file systems cannot replace an existing file, write it directly.
                    writeTextFile(file, content);
                    try {
                        Os.chmod(file.getAbsolutePath(), 0755);
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Could not make " + file + " executable", e);
                    }
                    deleteQuietly(temp);
                    temp = null;
                }
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not write the launcher " + file, e);
            } finally {
                if (temp != null)
                    deleteQuietly(temp);
            }
        }

        private String buildOpenCodeLauncher() {
            StringBuilder sb = new StringBuilder();
            sb.append("#!").append(PREFIX).append("/bin/sh\n");
            sb.append("# ").append(LAUNCHER_MARKER).append("\n");
            sb.append("P=\"${PREFIX:-").append(PREFIX).append("}\"\n");
            sb.append("H=\"${HOME:-").append(HOME).append("}\"\n");
            sb.append("PATH=\"$P/bin:$PATH\"; export PATH\n");
            sb.append("\n");
            sb.append("case \"${1:-}\" in\n");
            sb.append("  upgrade|update) exec \"$P/bin/oc-install\" ;;\n");
            sb.append("esac\n");
            sb.append("\n");
            sb.append("BIN=\"$H/.opencode/bin/opencode\"\n");
            sb.append("GLIBCLIB=\"$P/glibc/lib\"\n");
            sb.append("PATCHED=\"$H/.opencode/.patched\"\n");
            sb.append("\n");
            sb.append("[ -x \"$BIN\" ] || { echo \"opencode: not installed yet. Open the app to install it.\" >&2; exit 127; }\n");
            sb.append("LOADER=\"\"; for c in \"$GLIBCLIB\"/ld-linux*.so.*; do [ -x \"$c\" ] && { LOADER=\"$c\"; break; }; done\n");
            sb.append("[ -z \"$LOADER\" ] && { echo \"opencode: the glibc runtime is missing. Run 'oc-install' to install it.\" >&2; exit 127; }\n");
            sb.append("\n");
            sb.append("# The auto updater of OpenCode would replace the patched binary with an unpatched one.\n");
            sb.append("export OPENCODE_DISABLE_AUTOUPDATE=1\n");
            sb.append("# Never load the bionic preload of Termux into a glibc process.\n");
            sb.append("unset LD_PRELOAD\n");
            sb.append("unset LD_LIBRARY_PATH\n");
            sb.append("\n");
            sb.append("# Keep the GitHub credentials of the gh CLI visible to the native runtime. Without\n");
            sb.append("# this the token is only available to interactive shells and GitHub access is\n");
            sb.append("# hit or miss depending on how the process was started.\n");
            sb.append("if [ -z \"${GH_TOKEN:-}\" ] && [ -x \"$P/bin/gh\" ]; then\n");
            sb.append("  GH_TOKEN=$(\"$P/bin/gh\" auth token --hostname github.com 2>/dev/null)\n");
            sb.append("  [ -n \"$GH_TOKEN\" ] && export GH_TOKEN || unset GH_TOKEN\n");
            sb.append("fi\n");
            sb.append("if [ -z \"${GITHUB_TOKEN:-}\" ] && [ -n \"${GH_TOKEN:-}\" ]; then\n");
            sb.append("  GITHUB_TOKEN=\"$GH_TOKEN\"; export GITHUB_TOKEN\n");
            sb.append("fi\n");
            sb.append("\n");
            sb.append("MARK=$(cat \"$PATCHED\" 2>/dev/null)\n");
            sb.append("NOW=$(stat -L -c '%s %Y' \"$BIN\" 2>/dev/null)\n");
            sb.append("if [ -z \"$MARK\" ] || [ \"$MARK\" != \"$NOW\" ]; then\n");
            sb.append("  # The binary changed after the last patch, patch a copy of it and only then replace it.\n");
            sb.append("  REAL=$(readlink -f \"$BIN\" 2>/dev/null || echo \"$BIN\"); TMP=\"$REAL.repair.$$\"\n");
            sb.append("  if [ -x \"$P/bin/patchelf\" ] && \"$P/bin/patchelf\" --version >/dev/null 2>&1 \\\n");
            sb.append("     && cp \"$REAL\" \"$TMP\" 2>/dev/null \\\n");
            sb.append("     && \"$P/bin/patchelf\" --set-interpreter \"$LOADER\" \"$TMP\" >/dev/null 2>&1 \\\n");
            sb.append("     && chmod 755 \"$TMP\" 2>/dev/null \\\n");
            sb.append("     && \"$TMP\" --version >/dev/null 2>&1 \\\n");
            sb.append("     && mv \"$TMP\" \"$REAL\" 2>/dev/null; then\n");
            sb.append("    echo \"$(stat -L -c '%s %Y' \"$BIN\")\" > \"$PATCHED\"\n");
            sb.append("    exec \"$BIN\" \"$@\"\n");
            sb.append("  fi\n");
            sb.append("  rm -f \"$TMP\" 2>/dev/null\n");
            sb.append("  echo \"opencode: this build is not patched yet; running it through the glibc loader ...\" >&2\n");
            sb.append("  exec \"$LOADER\" --library-path \"$GLIBCLIB\" \"$BIN\" \"$@\"\n");
            sb.append("fi\n");
            sb.append("\n");
            sb.append("exec \"$BIN\" \"$@\"\n");
            return sb.toString();
        }

        private String buildOcInstallLauncher() {
            StringBuilder sb = new StringBuilder();
            sb.append("#!").append(PREFIX).append("/bin/sh\n");
            sb.append("# ").append(LAUNCHER_MARKER).append("\n");
            sb.append("P=\"${PREFIX:-").append(PREFIX).append("}\"; AM=\"$P/bin/am\"\n");
            sb.append("[ -x \"$AM\" ] || { echo \"oc-install: the 'am' command is missing (termux-am package).\" >&2; exit 127; }\n");
            sb.append("echo \"oc-install: asking the OpenCode app to install/update the runtime (watch the notification)\"\n");
            sb.append("exec \"$AM\" start -a android.intent.action.VIEW \\\n");
            sb.append("  -n ").append(TermuxConstants.TERMUX_PACKAGE_NAME).append("/")
                .append(OpenCodeWebActivity.class.getName()).append(" \\\n");
            sb.append("  --ez ").append(EXTRA_FORCE_UPDATE).append(" true >/dev/null 2>&1\n");
            return sb.toString();
        }

        /* ------------------------------------------------------------------ repair */

        /**
         * Patch the installed binary again if it is not pointing to the glibc loader anymore,
         * eg. because the auto updater of OpenCode replaced it.
         */
        private void repairInterpreterIfNeeded(Context context) {
            File link = new File(LINK_PATH);
            if (!link.exists()) return;

            File binary = resolveLink(link);
            if (binary == null || !binary.isFile()) return;

            String loader = findGlibcLoader();
            if (loader == null) return;

            if (binaryStamp(binary).equals(readTrimmed(new File(PATCHED_MARKER_PATH))))
                return; // Already patched.

            status("Repairing the OpenCode binary...");
            Logger.logInfo(LOG_TAG, "The OpenCode binary is not patched, patching it again.");

            File tmp = new File(binary.getAbsolutePath() + ".repair");
            deleteQuietly(tmp);
            if (!copyFile(binary, tmp)) {
                deleteQuietly(tmp);
                return;
            }

            CmdResult patch = patchInterpreter(tmp, loader);
            if (patch.exitCode != 0) {
                deleteQuietly(tmp);
                Logger.logWarn(LOG_TAG, "Could not patch the OpenCode binary again: " + tail(patch.output, 200));
                return;
            }

            try {
                Os.chmod(tmp.getAbsolutePath(), 0755);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not make the OpenCode binary executable", e);
            }

            CmdResult verify = run(Arrays.asList(tmp.getAbsolutePath(), "--version"), VERIFY_TIMEOUT_MS, false, null);
            if (!looksLikeAVersion(verify)) {
                deleteQuietly(tmp);
                Logger.logWarn(LOG_TAG, "The repaired OpenCode binary does not run, keeping the old one.");
                return;
            }

            deleteQuietly(binary);
            if (!tmp.renameTo(binary)) {
                if (!copyFile(tmp, binary))
                    Logger.logWarn(LOG_TAG, "Could not replace the OpenCode binary.");
                deleteQuietly(tmp);
            }

            writeTextFileQuietly(new File(PATCHED_MARKER_PATH), binaryStamp(binary));
        }

        /* ------------------------------------------------------------------ environment */

        private void ensureSecondStage() {
            if (new File(SECOND_STAGE_LOCK_PATH).exists()) return;

            File script = new File(SECOND_STAGE_SCRIPT_PATH);
            if (!script.isFile()) return;

            status("Finishing the Termux bootstrap...");
            CmdResult result = run(Arrays.asList(PREFIX + "/bin/bash", script.getAbsolutePath()), SECOND_STAGE_TIMEOUT_MS, true, null);
            if (result.exitCode != 0)
                Logger.logWarn(LOG_TAG, "The second stage of the Termux bootstrap failed (exit " + result.exitCode + ")");
        }

        private void ensureResolvConf() {
            File resolvConf = new File(RESOLV_CONF_PATH);
            if (resolvConf.exists()) return;
            if (!resolvConf.getParentFile().isDirectory()) return;

            try {
                writeTextFile(resolvConf, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not write " + RESOLV_CONF_PATH, e);
            }
        }

        private void writeDiagnostics() {
            StringBuilder sb = new StringBuilder();
            sb.append("app version: ").append(TermuxConstants.TERMUX_APP_NAME).append(" ").append(BuildConfig.VERSION_NAME).append("\n");
            sb.append("package: ").append(TermuxConstants.TERMUX_PACKAGE_NAME).append("\n");
            sb.append("abi: ").append(TextUtils.join(", ", Build.SUPPORTED_ABIS)).append("\n");
            sb.append("sdk: ").append(Build.VERSION.SDK_INT).append("\n");
            sb.append("device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("prefix: ").append(PREFIX).append("\n");
            sb.append("home: ").append(HOME).append("\n");
            sb.append("path: ").append(env.get("PATH")).append("\n");
            sb.append("tmpdir: ").append(env.get("TMPDIR")).append("\n");
            sb.append("ld_preload: ").append(String.valueOf(env.get("LD_PRELOAD"))).append("\n");
            sb.append("installed version: ").append(getInstalledVersion()).append("\n");
            sb.append("second stage: ").append(new File(SECOND_STAGE_LOCK_PATH).exists() ? "done" : "not run").append("\n");
            sb.append("glibc loader: ").append(String.valueOf(findGlibcLoader())).append("\n");
            sb.append("sources.list:\n").append(readTextFileQuietly(new File(APT_SOURCES_LIST_PATH))).append("\n");
            sb.append("sources.list.d:\n");
            File[] parts = new File(APT_SOURCEPARTS_DIR).listFiles();
            if (parts != null) {
                for (File part : parts)
                    sb.append("  ").append(part.getName()).append("\n");
            }

            writeTextFileQuietly(new File(DIAGNOSTICS_PATH), sb.toString());
        }

        /* ------------------------------------------------------------------ process handling */

        private CmdResult run(List<String> cmd, long timeoutMs, boolean stream) {
            return run(cmd, timeoutMs, stream, null);
        }

        private CmdResult run(List<String> cmd, long timeoutMs, boolean stream, LineListener listener) {
            StringBuilder output = new StringBuilder();
            try {
                ProcessBuilder builder = new ProcessBuilder(cmd);
                Map<String, String> processEnv = builder.environment();
                processEnv.clear();
                processEnv.putAll(env);
                builder.directory(new File(HOME));
                builder.redirectErrorStream(true);

                // The process is used by the reader thread, so it must be final.
                final Process process = builder.start();

                final StringBuilder sink = output;
                Thread reader = new Thread(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            synchronized (sink) {
                                if (sink.length() < MAX_OUTPUT_CHARS)
                                    sink.append(line).append('\n');
                            }
                            if (listener != null) {
                                try {
                                    listener.onLine(line);
                                } catch (Exception e) {
                                    // The listener must never break the installation.
                                }
                            }
                            if (stream)
                                Logger.logInfo(LOG_TAG, line);
                        }
                    } catch (Exception e) {
                        // The process was killed, ignore.
                    }
                }, "opencode-cmd-reader");
                reader.setDaemon(true);
                reader.start();

                long deadline = SystemClock.uptimeMillis() + timeoutMs;
                Integer exitCode = null;
                while (exitCode == null) {
                    try {
                        exitCode = process.exitValue();
                    } catch (IllegalThreadStateException e) {
                        if (SystemClock.uptimeMillis() > deadline) {
                            process.destroy();
                            try {
                                process.waitFor();
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            return new CmdResult(-1, output.toString(), "timeout");
                        }
                        try {
                            Thread.sleep(PROCESS_POLL_INTERVAL_MS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return new CmdResult(-1, output.toString(), "interrupted");
                        }
                    }
                }

                String text;
                synchronized (output) {
                    text = output.toString();
                }
                lastOutput = text;
                return new CmdResult(exitCode, text, null);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run " + cmd, e);
                return new CmdResult(-1, output.toString(), e.getMessage());
            }
        }

        /* ------------------------------------------------------------------ reporting */

        private void status(final String message) {
            steps.add(message);
            while (steps.size() > MAX_STEPS)
                steps.remove(0);

            Logger.logInfo(LOG_TAG, message);
            appendLog("== " + message);

            if (logOnly) return;

            synchronized (sLock) {
                sState.message = message;
                sState.updatedAt = System.currentTimeMillis();
                sState.addStep(message);
            }

            publish();
        }

        /** Set the stage of the installation and the message that describes it. */
        private void setStage(String stage, String message) {
            if (logOnly) return;

            Logger.logInfo(LOG_TAG, message);
            appendLog("== " + message);

            steps.add(message);
            while (steps.size() > MAX_STEPS)
                steps.remove(0);

            synchronized (sLock) {
                sState.stage = stage;
                sState.stagePercent = 0;
                sState.overallPercent = 0;
                sState.message = message;
                sState.detail = "";
                sState.error = "";
                sState.bytesDone = 0;
                sState.bytesTotal = 0;
                sState.running = true;
                sState.done = false;
                sState.failed = false;
                sState.updatedAt = System.currentTimeMillis();
                sState.recomputeOverallPercent();
                sState.addStep(message);
            }

            publish();
        }

        /** Set the percentage of the current stage. */
        private void setProgress(int stagePercent, String detail) {
            if (logOnly) return;

            synchronized (sLock) {
                sState.stagePercent = Math.max(0, Math.min(100, stagePercent));
                if (detail != null)
                    sState.detail = detail;
                sState.updatedAt = System.currentTimeMillis();
                sState.recomputeOverallPercent();
            }

            publish();
        }

        /** Set the number of bytes that were downloaded and the total size. */
        private void setBytes(long bytesDone, long bytesTotal, String detail) {
            if (logOnly) return;

            synchronized (sLock) {
                sState.bytesDone = bytesDone;
                sState.bytesTotal = bytesTotal;
                if (bytesTotal > 0)
                    sState.stagePercent = (int) Math.max(0, Math.min(100, bytesDone * 100 / bytesTotal));
                if (detail != null)
                    sState.detail = detail;
                sState.updatedAt = System.currentTimeMillis();
                sState.recomputeOverallPercent();
            }

            publish();
        }

        /** The installation finished successfully. */
        private void setStageDone() {
            if (logOnly) return;

            synchronized (sLock) {
                sState.stage = OpenCodeInstallState.STAGE_DONE;
                sState.stagePercent = 100;
                sState.overallPercent = 100;
                sState.message = "OpenCode is ready.";
                sState.detail = "Version " + getInstalledVersion() + " is installed.";
                sState.running = false;
                sState.done = true;
                sState.failed = false;
                sState.updatedAt = System.currentTimeMillis();
                sState.addStep(sState.message);
            }

            publish();
        }

        private void appendLog(String line) {
            try {
                File logFile = new File(SETUP_LOG_PATH);
                if (!logFile.getParentFile().isDirectory() && !logFile.getParentFile().mkdirs())
                    return;
                try (PrintWriter writer = new PrintWriter(new FileOutputStream(logFile, true))) {
                    writer.println(System.currentTimeMillis() + " " + line);
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not write the setup log", e);
            }
        }

        private void showProgressNotification(String message) {
            if (logOnly) return;
            NotificationManager manager = NotificationUtils.getNotificationManager(context);
            if (manager == null) return;

            NotificationUtils.setupNotificationChannel(context, NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);

            PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                OpenCodeWebActivity.newInstance(context), PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder builder = NotificationUtils.geNotificationBuilder(context, NOTIFICATION_CHANNEL_ID,
                Notification.PRIORITY_LOW, "OpenCode", message, null, contentIntent, null,
                NotificationUtils.NOTIFICATION_MODE_SILENT);
            if (builder == null) return;
            builder.setSmallIcon(R.drawable.ic_service_notification);
            builder.setOngoing(true);

            int percent;
            String detail;
            synchronized (sLock) {
                percent = sState.getOverallPercent();
                detail = sState.detail;
            }
            builder.setProgress(100, percent, false);
            if (detail != null && !detail.isEmpty())
                builder.setSubText(detail);

            manager.notify(NOTIFICATION_ID, builder.build());
        }

        private void notifyFinished(String title, String text, boolean isError) {
            NotificationManager manager = NotificationUtils.getNotificationManager(context);
            if (manager == null) return;

            NotificationUtils.setupNotificationChannel(context, NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);

            PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                OpenCodeWebActivity.newInstance(context), PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder builder = NotificationUtils.geNotificationBuilder(context, NOTIFICATION_CHANNEL_ID,
                Notification.PRIORITY_LOW, title, text, null, contentIntent, null,
                NotificationUtils.NOTIFICATION_MODE_SILENT);
            if (builder == null) return;
            builder.setSmallIcon(R.drawable.ic_service_notification);
            builder.setAutoCancel(true);
            manager.notify(NOTIFICATION_ID, builder.build());
        }

        /** Save the state, show it in the notification and tell the listeners about it. */
        private void publish() {
            publishState();

            String message;
            synchronized (sLock) {
                message = sState.message;
            }
            if (message != null)
                showProgressNotification(message);
        }

        private void handleFailure(String message) {
            Logger.logError(LOG_TAG, "The OpenCode installation failed: " + message);

            StringBuilder report = new StringBuilder();
            report.append("OpenCode installation failed\n\n");
            report.append(message).append("\n\n");
            report.append("Last steps:\n");
            for (String step : steps)
                report.append("  - ").append(step).append('\n');
            report.append("\n");
            report.append("Log tail (").append(SETUP_LOG_PATH).append("):\n");
            report.append(tail(readTextFileQuietly(new File(SETUP_LOG_PATH)), FAILURE_LOG_LINES));
            report.append("\nDiagnostics (").append(DIAGNOSTICS_PATH).append("):\n");
            report.append(readTextFileQuietly(new File(DIAGNOSTICS_PATH)));

            writeTextFileQuietly(new File(ERROR_DETAIL_PATH), report.toString());

            notifyFinished("OpenCode: installation failed", tail(message, MAX_DETAIL_CHARS), true);

            if (logOnly) return;

            synchronized (sLock) {
                sState.stage = OpenCodeInstallState.STAGE_FAILED;
                sState.error = message == null ? "Unknown error" : message;
                sState.running = false;
                sState.done = false;
                sState.failed = true;
                sState.updatedAt = System.currentTimeMillis();
                sState.addStep("FAILED: " + sState.error);
            }

            publish();
        }

        /* ------------------------------------------------------------------ lifecycle */

        private void releaseWaiters() {
            List<Runnable> waiters;
            synchronized (sLock) {
                waiters = new ArrayList<>(sInstallWaiters);
                sInstallWaiters.clear();
                sInstaller = null;
            }
            for (Runnable waiter : waiters)
                runOnUiThread(context, waiter);
        }
    }

    /* ==========================================================================================
     * Listeners of the output of a process
     * ========================================================================================== */

    /** Called for every line of the output of a process. */
    private interface LineListener {
        void onLine(String line);
    }

    /** Called while a file is downloaded. */
    private interface DownloadListener {
        void onDownloadProgress(long bytesDone, long bytesTotal, String detail);
    }

    /**
     * Guesses the progress of {@code apt-get update} from its output. Apt does not tell how much
     * has been downloaded already, but it prints the size of every file it fetches.
     */
    private static final class AptUpdateListener implements LineListener {

        private long knownTotal = 0;
        private long doneBytes = 0;
        private long currentSize = 0;

        @Override
        public void onLine(String line) {
            if (line == null) return;

            if (line.startsWith("Get:")) {
                // The file that was fetched before is complete now.
                if (currentSize > 0)
                    doneBytes += currentSize;
                currentSize = Math.max(0, parseAptSize(line));
                if (currentSize > 0)
                    knownTotal += currentSize;
                report(line);
                return;
            }

            if (line.startsWith("Fetched ") || line.startsWith("Ign:") || line.startsWith("Hit:")) {
                report(line);
            }
        }

        private void report(String line) {
            long total = Math.max(knownTotal, doneBytes + currentSize);
            int percent = total > 0 ? (int) Math.min(100, doneBytes * 100 / total) : 0;
            setStageProgress(percent, doneBytes, total, line.trim());
        }
    }

    /**
     * Guesses the progress of {@code apt-get install} from its output, used when the packages
     * could not be downloaded by the app itself.
     */
    private static final class AptInstallListener implements LineListener {

        private long knownTotal = 0;
        private long doneBytes = 0;
        private long currentSize = 0;

        @Override
        public void onLine(String line) {
            if (line == null) return;

            if (line.startsWith("Get:")) {
                if (currentSize > 0)
                    doneBytes += currentSize;
                currentSize = Math.max(0, parseAptSize(line));
                if (currentSize > 0)
                    knownTotal += currentSize;
            } else if (line.startsWith("Fetched ")) {
                if (currentSize > 0)
                    doneBytes += currentSize;
                currentSize = 0;
            }

            if (line.startsWith("Get:") || line.startsWith("Fetched ") || line.startsWith("Unpacking ") ||
                line.startsWith("Setting up ")) {
                long total = Math.max(knownTotal, doneBytes + currentSize);
                int percent = total > 0 ? (int) Math.min(100, doneBytes * 100 / total) : 0;
                setStageProgress(percent, doneBytes, total, line.trim());
            }
        }
    }

    /** @return the size of a package in bytes, -1 when it cannot be parsed. */
    private static long parseAptSize(String line) {
        if (line == null) return -1;
        Matcher matcher = APT_SIZE_PATTERN.matcher(line);
        if (!matcher.find()) return -1;

        try {
            String number = matcher.group(1).replace(',', '.');
            double value = Double.parseDouble(number);
            String unit = matcher.group(2) == null ? "b" : matcher.group(2).toLowerCase(Locale.US);
            switch (unit) {
                case "kb":
                    return (long) (value * 1024);
                case "mb":
                    return (long) (value * 1024 * 1024);
                case "gb":
                    return (long) (value * 1024 * 1024 * 1024);
                default:
                    return (long) value;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** Report the progress of the current stage, used by the listeners of apt. */
    private static void setStageProgress(int percent, long bytesDone, long bytesTotal, String detail) {
        synchronized (sLock) {
            if (!sState.running) return;
            sState.stagePercent = Math.max(0, Math.min(100, percent));
            sState.bytesDone = bytesDone;
            sState.bytesTotal = bytesTotal;
            sState.detail = detail;
            sState.updatedAt = System.currentTimeMillis();
            sState.recomputeOverallPercent();
            sState.addStep(detail);
        }
        publishState();
    }

    /* ==========================================================================================
     * Helpers
     * ========================================================================================== */

    /** The result of a child process. */
    private static final class CmdResult {
        final int exitCode;
        final String output;
        final String error;

        CmdResult(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.error = error;
        }
    }

    /** A package that apt would download. */
    private static final class PackageUri {
        String url;
        String fileName;
        long size;
        String hashAlgorithm;
        String hash;

        /** @return true when the file on disk has the checksum that apt expects. */
        boolean matches(File file) {
            if (hash == null || hash.isEmpty()) return file.isFile() && file.length() == size;
            if (!file.isFile()) return false;

            try {
                MessageDigest digest;
                String algorithm = hashAlgorithm == null ? "MD5" : hashAlgorithm;
                if ("sha256".equalsIgnoreCase(algorithm))
                    digest = MessageDigest.getInstance("SHA-256");
                else if ("sha512".equalsIgnoreCase(algorithm))
                    digest = MessageDigest.getInstance("SHA-512");
                else
                    digest = MessageDigest.getInstance("MD5");

                byte[] buffer = new byte[64 * 1024];
                try (InputStream in = new FileInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1)
                        digest.update(buffer, 0, read);
                }

                StringBuilder sb = new StringBuilder();
                for (byte b : digest.digest())
                    sb.append(String.format(Locale.US, "%02x", b));
                return sb.toString().equalsIgnoreCase(hash);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not check the checksum of " + file, e);
                return false;
            }
        }
    }

    /** Thrown when the installation cannot continue. */
    private static final class InstallException extends RuntimeException {
        InstallException(String message) {
            super(message);
        }
    }

    /** @return the npm architecture of the OpenCode build for this device, or null. */
    public static String getOpenCodeArch() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) return null;

        String abi = abis[0].toLowerCase();
        if (abi.startsWith("arm64")) return "arm64";
        if (abi.equals("x86_64") || abi.equals("amd64")) return "x64";
        return null;
    }

    /** @return the glibc loader of Termux, or null if the glibc runtime is not installed. */
    public static String findGlibcLoader() {
        File dir = new File(GLIBC_LIB_DIR);
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("ld-linux") && name.contains(".so.") && file.canExecute())
                return file.getAbsolutePath();
        }
        return null;
    }

    /** @return the file the opencode symlink points to. */
    private static File resolveLink(File link) {
        try {
            String target = Os.readlink(link.getAbsolutePath());
            File file = new File(target);
            if (!file.isAbsolute())
                file = new File(link.getParentFile(), target);
            return file;
        } catch (Exception e) {
            return link;
        }
    }

    /** @return the fingerprint of a file, the same format as {@code stat -L -c '%s %Y'}. */
    private static String binaryStamp(File file) {
        File real = file;
        try {
            if (!file.getCanonicalPath().equals(file.getAbsolutePath()))
                real = file.getCanonicalFile();
        } catch (IOException ignored) {
            // Use the file itself.
        }
        return real.length() + " " + (real.lastModified() / 1000L);
    }

    private static boolean isExecutable(File file) {
        return file.exists() && file.canExecute();
    }

    private static void runOnUiThread(Context context, final Runnable runnable) {
        if (runnable == null) return;
        if (context == null) {
            runnable.run();
            return;
        }
        sMainHandler.post(runnable);
    }

    /** Save the state on the disk and notify the listeners about it. */
    private static void publishState() {
        final OpenCodeInstallState snapshot;
        synchronized (sLock) {
            sState.updatedAt = System.currentTimeMillis();
            snapshot = copyOf(sState);
        }

        OpenCodeInstallState.save(snapshot);

        for (InstallListener listener : sListeners) {
            final InstallListener current = listener;
            sMainHandler.post(() -> {
                try {
                    current.onOpenCodeInstallStateChanged(snapshot);
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "An install listener failed", e);
                }
            });
        }
    }

    /** @return a copy of the given state, the copy is safe to hand out. */
    private static OpenCodeInstallState copyOf(OpenCodeInstallState state) {
        OpenCodeInstallState copy = new OpenCodeInstallState();
        copy.stage = state.stage;
        copy.stagePercent = state.stagePercent;
        copy.overallPercent = state.overallPercent;
        copy.message = state.message;
        copy.detail = state.detail;
        copy.error = state.error;
        copy.bytesDone = state.bytesDone;
        copy.bytesTotal = state.bytesTotal;
        copy.running = state.running;
        copy.done = state.done;
        copy.failed = state.failed;
        copy.updatedAt = state.updatedAt;
        for (String step : state.getSteps())
            copy.steps.add(step);
        return copy;
    }

    private static void writeTextFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Could not create the directory " + parent);

        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
        }
    }

    private static void writeTextFileQuietly(File file, String content) {
        try {
            writeTextFile(file, content);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not write " + file, e);
        }
    }

    private static String readTextFile(File file) throws IOException {
        if (!file.isFile()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String readTextFileQuietly(File file) {
        try {
            String text = readTextFile(file);
            return text == null ? "" : text;
        } catch (IOException e) {
            return "";
        }
    }

    private static String readTrimmed(File file) {
        String text = readTextFileQuietly(file);
        return text.trim();
    }

    private static boolean copyFile(File source, File target) {
        try {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1)
                    out.write(buffer, 0, read);
            }
            return true;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not copy " + source + " to " + target, e);
            return false;
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children)
                        deleteQuietly(child);
                }
            }
            file.delete();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not delete " + file, e);
        }
    }

    /** @return the last lines of a text, used for the error reports. */
    private static String tail(String text, int lines) {
        if (text == null) return "";
        String[] split = text.split("\n");
        int from = Math.max(0, split.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < split.length; i++)
            sb.append(split[i]).append('\n');
        return sb.toString();
    }
}
