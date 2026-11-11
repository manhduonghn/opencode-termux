package com.termux.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The main screen of the app: the OpenCode web UI inside an in-app WebView.
 *
 * <p>The UI itself is the web app served by the OpenCode background service. This activity makes
 * sure that the Termux bootstrap and the OpenCode runtime are present, starts the service,
 * obtains a one time pair link from the OpenCode CLI, and lets the WebView establish its own
 * session cookie. A foreground service keeps the daemon alive while the app is in the
 * background.</p>
 */
public final class OpenCodeWebActivity extends AppCompatActivity {

    private static final String LOG_TAG = "OpenCodeWebActivity";

    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1002;
    private static final int REQUEST_STORAGE_PERMISSION = 1003;

    /**
     * Absolute path of a directory that the file manager should open. Used by
     * {@link com.termux.app.api.file.FileReceiverActivity} after files are received from the
     * Android share sheet.
     */
    public static final String EXTRA_OPEN_DIRECTORY = "com.termux.opencode.OPEN_DIRECTORY";
    private static final long SERVICE_START_TIMEOUT_MS = 60_000L;
    private static final long CLI_TIMEOUT_MS = 30_000L;

    /** Preferences file shared by the settings panel. */
    private static final String PREFS_NAME = "opencode_cfg";
    /** Absolute path of the last project that was opened from the folder picker. */
    private static final String PREF_LAST_PROJECT_DIR = "storage_last_project_dir";

    /** The WebView retained by {@link OpenCodeWebViewModel} across activity recreation. */
    private WebView mWebView;
    private OpenCodeWebViewModel mViewModel;
    private View mStatusView;
    private ProgressBar mProgressBar;
    private TextView mStatusText;
    private Button mRetryButton;
    private TextView mStorageStatus;
    private Button mStorageGrantButton;
    private boolean mStorageSetupRunning;
    private boolean mStorageLegacyFallbackRequested;
    /** Set when the file manager entry triggered the storage permission flow. */
    private boolean mOpenFileManagerAfterStorage;

    @Nullable
    private ValueCallback<Uri[]> mFilePathCallback;

    /**
     * Refreshes the page when the daemon was stopped from the notification action while this
     * activity is visible. Without it the dead page would stay on screen until the user leaves
     * and returns to the app.
     */
    private final BroadcastReceiver mServiceStoppedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    reloadIfStopped();
                }
            });
        }
    };
    private boolean mServiceStoppedReceiverRegistered = false;

    private boolean mStarted = false;
    private boolean mReloadWebAfterInstall = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_opencode_web);

        // The WebView is owned by the ViewModel, so it survives an activity recreation (for
        // example a light/dark mode switch) instead of being recreated and reloaded.
        mViewModel = new ViewModelProvider(this).get(OpenCodeWebViewModel.class);
        mWebView = mViewModel.getOrCreateWebView(this);
        attachWebView();

        mStatusView = findViewById(R.id.opencode_web_status);
        mProgressBar = findViewById(R.id.opencode_web_progress);
        mStatusText = findViewById(R.id.opencode_web_status_text);
        mRetryButton = findViewById(R.id.opencode_web_retry);

        findViewById(R.id.opencode_web_reload_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reloadWebUi();
            }
        });
        mRetryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reloadWebUi();
            }
        });

        View settingsButton = findViewById(R.id.opencode_web_settings_button);
        View settingsPanel = findViewById(R.id.opencode_settings_panel);
        View settingsClose = findViewById(R.id.opencode_settings_close);
        if (settingsButton != null) {
            settingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (settingsPanel != null) settingsPanel.setVisibility(View.VISIBLE);
                }
            });
        }
        if (settingsClose != null) {
            settingsClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (settingsPanel != null) settingsPanel.setVisibility(View.GONE);
                }
            });
        }

        configureUpdateCheck();
        setupWakeLock();
        configureStorageSettings();
        configureFileManagerSettings();

        // A share intent can create the activity. Post the dialog so that it is shown after the
        // content view has been laid out.
        getWindow().getDecorView().post(() -> handleOpenDirectoryIntent(getIntent()));

        configureWebView();
        restoreWebUiState();
        requestNotificationPermissionIfNeeded();
        ensureRuntime();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStarted = true;

        OpenCodeBootstrap.addInstallListener(mInstallListener);
        if (TermuxInstaller.isStartupFlowActive()) {
            TermuxInstaller.showStartupProgress(this, null);
        }
        refreshInstallState();
        handleForceUpdateIntent();

        if (!mServiceStoppedReceiverRegistered) {
            ContextCompat.registerReceiver(this, mServiceStoppedReceiver,
                new IntentFilter(OpenCodeForegroundService.ACTION_STOPPED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
            mServiceStoppedReceiverRegistered = true;
        }

        if (OpenCodeBootstrap.isRuntimeComplete()) {
            OpenCodeForegroundService.start(this);
            maybeLoadWebUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) mWebView.onResume();
        if (OpenCodeBootstrap.isTermuxBootstrapReady()) {
            OpenCodeBootstrap.checkRuntime(this);
        }
        reloadIfStopped();
        // The user may have granted the permission in the system settings while the app was
        // in the background, without returning through onActivityResult().
        updateStorageUi();
        ensureStorageLinksIfNeeded();
        FileManagerDialog.refreshActive();
    }

    @Override
    protected void onPause() {
        if (mWebView != null) mWebView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mStarted = false;
        if (mServiceStoppedReceiverRegistered) {
            try {
                unregisterReceiver(mServiceStoppedReceiver);
            } catch (Throwable ignored) {
            }
            mServiceStoppedReceiverRegistered = false;
        }
        OpenCodeBootstrap.removeInstallListener(mInstallListener);
        // The dialog belongs to this activity. The installation keeps running in the background
        // and the progress is shown again by the next activity or when this one comes back.
        TermuxInstaller.hideStartupProgress(this);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleForceUpdateIntent();
        handleOpenDirectoryIntent(intent);

        // The app is a singleTask activity: tapping the launcher again does not recreate it but
        // delivers this intent. If the service was stopped in the meantime (notification exit or
        // the last terminal session was closed), the old page is dead even though the activity
        // is still alive, so pair again.
        reloadIfStopped();
        if (OpenCodeBootstrap.isRuntimeComplete()) OpenCodeForegroundService.start(this);
    }

    @Override
    protected void onDestroy() {
        if (mFilePathCallback != null) {
            mFilePathCallback.onReceiveValue(null);
            mFilePathCallback = null;
        }
        if (mWebView != null) {
            // Detach the retained WebView from this activity's view tree and drop the clients that
            // reference this activity. The WebView itself is only stopped and destroyed by the
            // ViewModel when the activity is really finishing, so a configuration change (for
            // example a light/dark switch or a rotation) keeps the loaded page and any load that
            // is still in progress.
            mWebView.setWebChromeClient(null);
            mWebView.setWebViewClient(null);
            View container = findViewById(R.id.opencode_web_container);
            ViewParent parent = mWebView.getParent();
            if (parent != null && parent == container) {
                ((ViewGroup) parent).removeView(mWebView);
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        View settingsPanel = findViewById(R.id.opencode_settings_panel);
        if (settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE) {
            settingsPanel.setVisibility(View.GONE);
            return;
        }
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
            return;
        }
        OpenCodeForegroundService.start(this);
        moveTaskToBack(true);
    }

    /* ==========================================================================================
     * Runtime setup
     * ========================================================================================== */

    /**
     * Make sure that the Termux bootstrap and the OpenCode runtime are available. The branded
     * startup dialog is shown for the whole setup. Once everything is ready the web UI
     * is loaded.
     */
    private void ensureRuntime() {
        if (OpenCodeBootstrap.isRuntimeComplete()) {
            // Refresh launchers and check that the binary is still patched.
            OpenCodeBootstrap.setupIfNeeded(getApplicationContext(), null);
            maybeLoadWebUi();
            return;
        }

        if (TermuxInstaller.isStartupFlowActive() || OpenCodeBootstrap.isInstallRunning()) {
            if (!TermuxInstaller.isStartupProgressShowing(this)) {
                OpenCodeInstallState state = OpenCodeBootstrap.getInstallState();
                String message = state != null && !OpenCodeInstallState.isEmpty(state.message)
                    ? state.message : getString(R.string.opencode_installing);
                int percent = state != null && state.running ? state.getOverallPercent() : -1;
                String detail = state != null ? state.detail : null;
                TermuxInstaller.showStartupProgress(this, message, percent, detail);
            }
            return;
        }

        if (!OpenCodeBootstrap.isTermuxBootstrapReady()) {
            TermuxInstaller.setupBootstrapIfNeeded(this, new Runnable() {
                @Override
                public void run() {
                    // This callback is also invoked when this activity was destroyed in the
                    // meantime. The runtime installation is tied to the application, not to one
                    // activity, so keep going and let the next activity show the progress.
                    if (mStarted && !isFinishing() && !isDestroyed()
                        && !OpenCodeBootstrap.isRuntimeComplete()) {
                        TermuxInstaller.showStartupProgress(OpenCodeWebActivity.this, getString(R.string.opencode_installing));
                    }
                    Context context = getApplicationContext();
                    OpenCodeBootstrap.setupIfNeeded(context, null);
                }
            });
            return;
        }

        TermuxInstaller.showStartupProgress(this, getString(R.string.opencode_installing));
        OpenCodeBootstrap.setupIfNeeded(getApplicationContext(), null);
    }

    /** Re-attach the progress dialog after the activity was recreated or came back to front. */
    private void refreshInstallState() {
        OpenCodeInstallState state = OpenCodeBootstrap.getInstallState();
        if (state == null) return;
        if (state.running || state.failed) {
            mInstallListener.onOpenCodeInstallStateChanged(state);
        } else if (state.done && OpenCodeBootstrap.isRuntimeComplete()) {
            TermuxInstaller.dismissStartupProgress();
            maybeLoadWebUi();
        }
    }

    /** The `oc-install` command asks the app to update the runtime through this intent. */
    private void handleForceUpdateIntent() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra(OpenCodeBootstrap.EXTRA_FORCE_UPDATE, false))
            return;

        intent.removeExtra(OpenCodeBootstrap.EXTRA_FORCE_UPDATE);
        setIntent(intent);

        if (!OpenCodeBootstrap.isRuntimeComplete()) return;
        mReloadWebAfterInstall = true;
        TermuxInstaller.showStartupProgress(this, getString(R.string.opencode_installing));
        OpenCodeBootstrap.requestUpdate(this);
    }

    /**
     * Discard the current page and pair with the service again. This is used by the retry and
     * reload buttons: a pair link is single use, so simply reloading the WebView would request the
     * same expired link again and show a 401 page.
     */
    private void reloadWebUi() {
        resetWebUiState();
        if (!OpenCodeBootstrap.isRuntimeComplete()) {
            ensureRuntime();
            return;
        }
        loadWebUi();
    }

    /** Called on the UI thread whenever the OpenCode installation makes progress. */
    private final OpenCodeBootstrap.InstallListener mInstallListener = new OpenCodeBootstrap.InstallListener() {
        @Override
        public void onOpenCodeInstallStateChanged(OpenCodeInstallState state) {
            if (state == null || isFinishing() || isDestroyed()) return;

            if (state.running) {
                if (TermuxInstaller.isStartupProgressShowing(OpenCodeWebActivity.this)) {
                    TermuxInstaller.setStartupProgress(OpenCodeWebActivity.this,
                        state.message, state.getOverallPercent(), state.detail);
                } else {
                    TermuxInstaller.showStartupProgress(OpenCodeWebActivity.this,
                        state.message, state.getOverallPercent(), state.detail);
                }
                return;
            }

            if (state.failed) {
                TermuxInstaller.dismissStartupProgress();
                showError(state.error);
                return;
            }

            if (state.done) {
                TermuxInstaller.dismissStartupProgress();
                if (!OpenCodeBootstrap.isRuntimeComplete()) return;
                if (mReloadWebAfterInstall) {
                    mReloadWebAfterInstall = false;
                    loadWebUi();
                } else {
                    maybeLoadWebUi();
                }
            }
        }
    };

    /* ==========================================================================================
     * WebView
     * ========================================================================================== */

    @SuppressWarnings("deprecation")
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            OpenCodeForegroundService.start(this);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            OpenCodeForegroundService.start(this);
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATION_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            OpenCodeForegroundService.start(this);
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            onStoragePermissionReturned();
        }
    }

    /** Attach the retained WebView to the container of the current activity layout. */
    private void attachWebView() {
        FrameLayout container = findViewById(R.id.opencode_web_container);
        if (container == null || mWebView == null) return;

        ViewParent parent = mWebView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(mWebView);
        }
        container.addView(mWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /**
     * After a recreation the layout starts with the loading overlay visible. Hide it again when the
     * retained WebView already shows the authenticated web UI.
     */
    private void restoreWebUiState() {
        if (mViewModel.isPageError()) return;
        if (mViewModel.isAuthCompleted()) showContent();
    }

    private void configureWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(mWebView, true);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternalUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternalUrl(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (isFinishing() || isDestroyed()) return;

                if (!mViewModel.isPageError() && !mViewModel.isAuthCompleted()
                        && url != null && !url.contains("/auth/connect/")) {
                    mViewModel.setAuthCompleted(true);
                    // Drop the one time pair redirect from the history, so the system back
                    // button leaves the web UI instead of showing the expired pair page.
                    view.clearHistory();
                }

                if (!mViewModel.isPageError()) showContent();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                showError(description);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    CharSequence description = error == null ? null : error.getDescription();
                    showError(description == null ? null : description.toString());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request == null || !request.isForMainFrame()) return;

                int statusCode = errorResponse == null ? 0 : errorResponse.getStatusCode();
                // A pair code is single use. Reusing it returns 401, and a failure of the service
                // itself is reported with a 5xx status. In both cases the page must not be
                // treated as successfully loaded.
                if (statusCode == 401 || statusCode >= 500) {
                    showError(getString(R.string.opencode_web_http_error, statusCode));
                }
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                    mFilePathCallback = null;
                }
                mFilePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    mFilePathCallback = null;
                    Logger.logStackTraceWithMessage(LOG_TAG, "No activity for the file chooser", e);
                    return false;
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                if (isFinishing() || isDestroyed()) {
                    result.cancel();
                    return true;
                }
                new MaterialAlertDialogBuilder(OpenCodeWebActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                if (isFinishing() || isDestroyed()) {
                    result.cancel();
                    return true;
                }
                new MaterialAlertDialogBuilder(OpenCodeWebActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (request == null) return;
                String[] resources = request.getResources();
                if (resources == null || resources.length == 0) {
                    request.deny();
                    return;
                }
                // The web UI only needs its own notification permission, which is requested
                // from the activity when it is created.
                request.grant(resources);
            }


        });
    }

    /** Open links that do not belong to the local OpenCode server in the normal browser. */
    private boolean openExternalUrl(String url) {
        if (url == null) return false;

        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null) return false;
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
            return false;

        if (host != null && ("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)))
            return false;

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "No activity to open " + url, e);
        }
        return true;
    }

    private void maybeLoadWebUi() {
        // The service was stopped while this activity existed. The WebView still contains the old
        // authenticated page, but the one time pair link behind it is gone, so start over.
        if (OpenCodeService.consumeStoppedByUser()) {
            resetWebUiState();
            loadWebUi();
            return;
        }

        if (mViewModel.isWebUiStarted()) return;
        loadWebUi();
    }

    /**
     * Pair again when the service was stopped while this activity was alive. A pair link is single
     * use, so the page that is still displayed belongs to a service that no longer runs. Called
     * from {@link #onNewIntent(Intent)} and {@link #onResume()} so that both a launcher intent and
     * a return from the background replace the dead page.
     */
    private void reloadIfStopped() {
        if (!OpenCodeService.consumeStoppedByUser()) return;

        resetWebUiState();
        if (OpenCodeBootstrap.isRuntimeComplete()) {
            loadWebUi();
        } else {
            ensureRuntime();
        }
    }

    /** Forget everything about the current page and cancel a load that may still be running. */
    private void resetWebUiState() {
        mViewModel.resetWebUiState();
    }

    /** Start the service, pair this WebView and load the web UI. */
    private void loadWebUi() {
        final int generation = mViewModel.nextLoadGeneration();

        mViewModel.setWebUiStarted(true);
        mViewModel.setPageError(false);
        if (mWebView != null) mWebView.stopLoading();
        showStatus(getString(R.string.opencode_web_starting), false);

        new Thread(() -> {
            String error = null;
            String pairUrl = null;
            try {
                OpenCodeForegroundService.start(getApplicationContext());
                pairUrl = OpenCodeService.startAndPair(
                    getApplicationContext(), SERVICE_START_TIMEOUT_MS, CLI_TIMEOUT_MS);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start the OpenCode web UI", e);
                error = e.getMessage();
            }

            final String url = pairUrl;
            final String message = error;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    // A newer load was started (reload button, stop detected, ...) while this one was
                    // waiting for the service. Do not let the stale result win.
                    if (generation != mViewModel.getLoadGeneration()) return;
                    if (mWebView == null) return;

                    if (url != null) {
                        // The server only answers the one time pair link with the session cookie
                        // redirect when the request looks like a browser navigation. WebView sends
                        // this by default, but be explicit so the pair link can never return JSON.
                        HashMap<String, String> headers = new HashMap<String, String>();
                        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                        mWebView.loadUrl(url, headers);
                    } else {
                        mViewModel.setWebUiStarted(false);
                        showError(message);
                    }
                }
            });
        }, "opencode-web-start").start();
    }

    private void showStatus(String text, boolean showRetry) {
        if (mStatusView == null) return;
        mStatusView.setVisibility(View.VISIBLE);
        mProgressBar.setVisibility(showRetry ? View.GONE : View.VISIBLE);
        mStatusText.setText(text);
        mRetryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
    }

    private void showContent() {
        if (mStatusView != null) mStatusView.setVisibility(View.GONE);
    }

    private void showError(@Nullable String details) {
        if (isFinishing() || isDestroyed()) return;
        mViewModel.setPageError(true);
        mViewModel.setWebUiStarted(false);

        String message = getString(R.string.opencode_web_failed);
        if (details != null && !details.trim().isEmpty()) {
            message += "\n\n" + details.trim();
        }
        showStatus(message, true);
    }


    private String runShell(String cmd) {
        return runShell(cmd, 120000L);
    }

    private String runShell(String cmd, long timeoutMs) {
        try {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", cmd);
            applyTermuxEnv(builder);
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);

            final Process process = builder.start();
            final StringBuilder output = new StringBuilder();

            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "opencode-gh-shell-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = SystemClock.uptimeMillis() + timeoutMs;
            while (true) {
                try {
                    process.exitValue();
                    break;
                } catch (IllegalThreadStateException e) {
                    if (SystemClock.uptimeMillis() > deadline) {
                        process.destroy();
                        try {
                            process.waitFor();
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        process.destroy();
                        break;
                    }
                }
            }

            reader.join(1000L);
            synchronized (output) {
                return output.toString();
            }
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    private interface StreamListener {
        void onLine(String line);
    }

    /**
     * Run a shell command and deliver each output line while it is still running, so long
     * operations such as {@code pkg install} or {@code git clone} can show live progress.
     */
    private String runShellStreaming(String cmd, long timeoutMs, final StreamListener listener) {
        try {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", cmd);
            applyTermuxEnv(builder);
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);

            final Process process = builder.start();
            final StringBuilder output = new StringBuilder();

            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            synchronized (output) {
                                output.append(line).append('\n');
                            }
                            if (listener != null) {
                                try {
                                    listener.onLine(line);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "opencode-gh-stream-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = SystemClock.uptimeMillis() + timeoutMs;
            while (true) {
                try {
                    process.exitValue();
                    break;
                } catch (IllegalThreadStateException e) {
                    if (SystemClock.uptimeMillis() > deadline) {
                        process.destroy();
                        try {
                            process.waitFor();
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        process.destroy();
                        break;
                    }
                }
            }

            reader.join(1000L);
            synchronized (output) {
                return output.toString();
            }
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    private void applyTermuxEnv(ProcessBuilder builder) {
        Map<String, String> processEnv = builder.environment();
        processEnv.clear();
        try {
            processEnv.putAll(new TermuxShellEnvironment().getEnvironment(this, false));
        } catch (Exception ignored) {
            processEnv.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            processEnv.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            String existingPath = processEnv.get("PATH");
            String path = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                + (existingPath == null || existingPath.isEmpty() ? "" : ":" + existingPath);
            processEnv.put("PATH", path);
            processEnv.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        }
        processEnv.put("TERM", "xterm-256color");
        processEnv.put("LANG", "en_US.UTF-8");
        processEnv.put("COLORTERM", "truecolor");
        processEnv.remove("LD_PRELOAD");
        processEnv.remove("LD_LIBRARY_PATH");
    }

    private void configureUpdateCheck() {
        TextView currentVersion = findViewById(R.id.opencode_updates_current_version);
        TextView statusText = findViewById(R.id.opencode_updates_status);
        Button checkButton = findViewById(R.id.opencode_updates_check_button);
        Button updateButton = findViewById(R.id.opencode_updates_update_button);

        String installed = OpenCodeBootstrap.getInstalledVersion();
        if (currentVersion != null) {
            if (installed == null || installed.isEmpty()) {
                currentVersion.setText(getString(R.string.opencode_updates_current, "-"));
            } else {
                currentVersion.setText(getString(R.string.opencode_updates_current, installed));
            }
        }
        if (statusText != null) {
            statusText.setText("");
        }
        if (updateButton != null) {
            updateButton.setVisibility(View.GONE);
        }

        if (checkButton != null) {
            checkButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checkForUpdates();
                }
            });
        }
        if (updateButton != null) {
            updateButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isFinishing() || isDestroyed()) return;
                    OpenCodeBootstrap.requestUpdate(OpenCodeWebActivity.this);
                    if (updateButton != null) updateButton.setVisibility(View.GONE);
                    if (statusText != null) statusText.setText(R.string.opencode_updates_installing);
                }
            });
        }
    }

    private void checkForUpdates() {
        TextView currentVersion = findViewById(R.id.opencode_updates_current_version);
        TextView statusText = findViewById(R.id.opencode_updates_status);
        Button checkButton = findViewById(R.id.opencode_updates_check_button);
        Button updateButton = findViewById(R.id.opencode_updates_update_button);

        String installed = OpenCodeBootstrap.getInstalledVersion();
        if (currentVersion != null) {
            if (installed == null || installed.isEmpty()) {
                currentVersion.setText(getString(R.string.opencode_updates_current, "-"));
            } else {
                currentVersion.setText(getString(R.string.opencode_updates_current, installed));
            }
        }
        if (statusText != null) {
            statusText.setText(R.string.opencode_updates_checking);
        }
        if (checkButton != null) {
            checkButton.setEnabled(false);
        }
        if (updateButton != null) {
            updateButton.setVisibility(View.GONE);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String latest = fetchLatestVersion();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (checkButton != null) checkButton.setEnabled(true);
                        if (latest == null || latest.isEmpty()) {
                            if (statusText != null) {
                                statusText.setText(R.string.opencode_updates_failed);
                            }
                            return;
                        }
                        if (installed != null && !installed.isEmpty() && installed.equals(latest)) {
                            if (statusText != null) {
                                statusText.setText(R.string.opencode_updates_uptodate);
                            }
                            return;
                        }
                        if (statusText != null) {
                            statusText.setText(getString(R.string.opencode_updates_available, latest));
                        }
                        if (updateButton != null) {
                            updateButton.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }, "opencode-update-check").start();
    }

    private String fetchLatestVersion() {
        try {
            java.net.URL url = new java.net.URL("https://opencode.ai/update/api/latest/cli/npm");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Termux-OpenCode");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            java.io.InputStream in = conn.getInputStream();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            in.close();
            String body = sb.toString();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Matcher m = p.matcher(body);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void setupWakeLock() {
        androidx.appcompat.widget.SwitchCompat sw = findViewById(R.id.opencode_wakelock_switch);
        android.widget.Button installBtn = findViewById(R.id.opencode_wakelock_install_btn);
        TextView status = findViewById(R.id.opencode_wakelock_status);
        TextView installStatus = findViewById(R.id.opencode_wakelock_install_status);
        if (sw == null) return;
        android.content.SharedPreferences p = getSharedPreferences("opencode_cfg", Context.MODE_PRIVATE);

        String prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        java.io.File wakeLock = new java.io.File(prefix + "/bin/termux-wake-lock");
        java.io.File wakeUnlock = new java.io.File(prefix + "/bin/termux-wake-unlock");
        boolean hasApi = wakeLock.exists() && wakeUnlock.exists();

        if (hasApi) {
            if (installBtn != null) installBtn.setVisibility(View.GONE);
            sw.setVisibility(View.VISIBLE);
            boolean on = p.getBoolean("wake_lock", false);
            sw.setChecked(on);
            updateWakeLockText(status, on);
            if (on) {
                new Thread(() -> {
                    try { Runtime.getRuntime().exec(prefix + "/bin/termux-wake-lock").waitFor(); } catch (Throwable ignored) {}
                }).start();
            }
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    p.edit().putBoolean("wake_lock", checked).apply();
                    if (checked) {
                        try {
                            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                                try { startActivity(intent); } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                    new Thread(() -> {
                        try {
                            String cmd = checked ? prefix + "/bin/termux-wake-lock" : prefix + "/bin/termux-wake-unlock";
                            Runtime.getRuntime().exec(cmd).waitFor();
                        } catch (Throwable t) {
                            runOnUiThread(() -> {
                                if (status != null) status.setText(R.string.opencode_battery_wakelock_missing);
                            });
                        }
                        runOnUiThread(() -> updateWakeLockText(status, checked));
                    }).start();
                }
            });
        } else {
            if (sw != null) sw.setVisibility(View.GONE);
            if (installBtn != null) {
                installBtn.setVisibility(View.VISIBLE);
                installBtn.setOnClickListener(v -> installTermuxApi(installBtn, installStatus, sw, status));
            }
            if (status != null) status.setText(R.string.opencode_battery_wakelock_missing);
        }
    }

    private void installTermuxApi(android.widget.Button btn, TextView status, androidx.appcompat.widget.SwitchCompat sw, TextView st) {
        if (btn == null) return;
        btn.setEnabled(false);
        if (status != null) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.opencode_battery_wakelock_installing);
        }
        new Thread(() -> {
            try {
                String out = runShellStreaming("pkg install -y termux-api", 300000L, new StreamListener() {
                    @Override
                    public void onLine(String line) {
                        runOnUiThread(() -> {
                            if (status != null) {
                                String s = line;
                                if (s.length() > 400) s = s.substring(s.length() - 400);
                                status.setText(s);
                            }
                        });
                    }
                });
                runOnUiThread(() -> {
                    String prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
                    java.io.File wakeLock = new java.io.File(prefix + "/bin/termux-wake-lock");
                    java.io.File wakeUnlock = new java.io.File(prefix + "/bin/termux-wake-unlock");
                    if (wakeLock.exists() && wakeUnlock.exists()) {
                        if (btn != null) btn.setVisibility(View.GONE);
                        if (sw != null) {
                            sw.setVisibility(View.VISIBLE);
                            android.content.SharedPreferences p = getSharedPreferences("opencode_cfg", Context.MODE_PRIVATE);
                            boolean on = p.getBoolean("wake_lock", false);
                            sw.setChecked(on);
                            updateWakeLockText(st, on);
                            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                                @Override
                                public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                                    p.edit().putBoolean("wake_lock", checked).apply();
                                    if (checked) {
                                        try {
                                            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                                            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                                                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                                                try { startActivity(intent); } catch (Throwable ignored) {}
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                    new Thread(() -> {
                                        try {
                                            String cmd = checked ? prefix + "/bin/termux-wake-lock" : prefix + "/bin/termux-wake-unlock";
                                            Runtime.getRuntime().exec(cmd).waitFor();
                                        } catch (Throwable ignored) {}
                                        runOnUiThread(() -> updateWakeLockText(st, checked));
                                    }).start();
                                }
                            });
                        }
                        if (status != null) status.setText(R.string.opencode_battery_wakelock_installed);
                    } else {
                        if (btn != null) {
                            btn.setEnabled(true);
                            btn.setText(R.string.opencode_battery_wakelock_install);
                        }
                        if (status != null) status.setText(R.string.opencode_battery_wakelock_missing);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (btn != null) {
                        btn.setEnabled(true);
                        btn.setText(R.string.opencode_battery_wakelock_install);
                    }
                    if (status != null) status.setText(R.string.opencode_battery_wakelock_missing);
                });
            }
        }).start();
    }

    private void updateWakeLockText(TextView status, boolean on) {
        if (status == null) return;
        status.setText(on ? R.string.opencode_battery_wakelock_on : R.string.opencode_battery_wakelock_off);
    }

    /* ==========================================================================================
     * Shared storage / SD card
     * ========================================================================================== */

    private void configureStorageSettings() {
        ensureStorageLinksIfNeeded();
    }

    /**
     * The folder button in the top bar opens the file manager. Storage access is requested first
     * when it is still missing. GitHub now lives inside the file manager.
     */
    private void configureFileManagerSettings() {
        View files = findViewById(R.id.opencode_web_files_button);
        if (files != null) {
            files.setOnClickListener(v -> openFileManager());
        }
    }

    /** Open the file manager, asking for storage access first when it has not been granted. */
    private void openFileManager() {
        if (!StorageAccess.hasStoragePermission(this)) {
            mOpenFileManagerAfterStorage = true;
            onStorageActionClicked();
            return;
        }
        FileManagerDialog.show(this, fileManagerListener());
    }

    /** The callbacks that the file manager needs from the activity. */
    private FileManagerDialog.Listener fileManagerListener() {
        return new FileManagerDialog.Listener() {
            @Override
            public void onOpenProject(File directory) {
                openProjectDirectory(directory);
            }

            @Override
            public void onManageStorage() {
                onStorageActionClicked();
            }

            @Override
            public void onGitHubAccountChanged() {
                // Restart the native service so it inherits GH_TOKEN from the launcher. Editing
                // the gh configuration alone is not enough for an already running daemon.
                OpenCodeService.stopAsync(getApplicationContext(), false, () -> {
                    if (isFinishing() || isDestroyed()) return;
                    reloadWebUi();
                });
            }
        };
    }

    /**
     * Open the file manager at a directory received from the share sheet. The extra is removed
     * immediately so that a configuration change or a new intent cannot show the dialog twice.
     */
    private void handleOpenDirectoryIntent(@Nullable Intent intent) {
        if (intent == null) return;
        String path = intent.getStringExtra(EXTRA_OPEN_DIRECTORY);
        if (path == null || path.isEmpty()) return;
        intent.removeExtra(EXTRA_OPEN_DIRECTORY);

        File directory = new File(path);
        if (!directory.isDirectory()) directory = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        FileManagerDialog.show(this, directory, fileManagerListener());
    }

    /**
     * Open the folder picker so the user can choose a project without typing its path. When the
     * storage permission is still missing the grant flow is offered first.
     */
    private void onPickProjectClicked() {
        if (!StorageAccess.hasStoragePermission(this)) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.opencode_picker_permission_title)
                .setMessage(R.string.opencode_picker_permission_message)
                .setPositiveButton(R.string.opencode_storage_grant,
                    (dialog, which) -> onStorageActionClicked())
                .setNegativeButton(R.string.opencode_picker_cancel, null)
                .show();
            return;
        }

        DirectoryPickerDialog.show(this, lastProjectDirectory(), new DirectoryPickerDialog.Listener() {
            @Override
            public void onDirectorySelected(File directory) {
                openProjectDirectory(directory);
            }
        });
    }

    /** The directory the picker starts in: the last opened project, else the internal storage. */
    private File lastProjectDirectory() {
        android.content.SharedPreferences preferences =
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = preferences.getString(PREF_LAST_PROJECT_DIR, null);
        if (saved != null && !saved.isEmpty()) {
            File directory = new File(saved);
            if (directory.isDirectory()) return directory;
        }

        File primary = StorageAccess.primaryRoot();
        try {
            if (primary != null && primary.isDirectory() && primary.list() != null) return primary;
        } catch (SecurityException ignored) {
            // Fall back to the Termux home directory below.
        }
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    /**
     * Remember the chosen project, copy its path to the clipboard and ask the running OpenCode
     * server to open it. The clipboard is the fallback when the web UI is not ready or the server
     * rejects the request.
     */
    private void openProjectDirectory(File directory) {
        if (directory == null) return;
        final String path = directory.getAbsolutePath();

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_PROJECT_DIR, path)
            .apply();
        copyToClipboard(path);

        final String origin = webUiOrigin();
        if (origin == null) {
            android.widget.Toast.makeText(this,
                getString(R.string.opencode_picker_copied, path),
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        android.widget.Toast.makeText(this,
            getString(R.string.opencode_picker_opening, path),
            android.widget.Toast.LENGTH_SHORT).show();

        final String cookie = CookieManager.getInstance().getCookie(origin);
        new Thread(() -> {
            int status;
            String failure = null;
            try {
                status = createSession(origin, cookie, path);
            } catch (Exception e) {
                status = -1;
                failure = e.getMessage();
            }
            final int result = status;
            final String error = failure;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    if (result >= 200 && result < 300) {
                        View settingsPanel = findViewById(R.id.opencode_settings_panel);
                        if (settingsPanel != null) settingsPanel.setVisibility(View.GONE);
                        if (mWebView != null) mWebView.loadUrl(origin + "/");
                    } else {
                        Logger.logError(LOG_TAG, "Could not open the project \"" + path
                            + "\": HTTP " + result + (error == null ? "" : " " + error));
                        android.widget.Toast.makeText(OpenCodeWebActivity.this,
                            getString(R.string.opencode_picker_open_failed, path),
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
        }, "opencode-open-project").start();
    }

    /** @return the origin of the OpenCode web UI, or {@code null} when it is not ready. */
    @Nullable
    private String webUiOrigin() {
        if (mWebView == null || mViewModel.isPageError() || !mViewModel.isAuthCompleted())
            return null;

        String url = mWebView.getUrl();
        if (url == null || url.contains("/auth/connect/")) return null;

        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return null;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (scheme == null || host == null) return null;
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host) && !"::1".equals(host)) return null;

        String hostPart = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return port >= 0 ? scheme + "://" + hostPart + ":" + port : scheme + "://" + hostPart;
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager manager =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.opencode_storage_title), text));
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not copy the project path", e);
        }
    }

    /**
     * Create a session in the given directory. The OpenCode server derives its project list from
     * the sessions, so the project appears in the web UI after the next page load.
     *
     * @return the HTTP status code of the request.
     */
    private static int createSession(String origin, @Nullable String cookie, String directory)
        throws Exception {
        JSONObject location = new JSONObject();
        location.put("directory", directory);
        JSONObject body = new JSONObject();
        body.put("location", location);

        HttpURLConnection connection =
            (HttpURLConnection) new URL(origin + "/api/session").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int status = connection.getResponseCode();
            java.io.InputStream stream =
                status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                try (java.io.InputStream in = stream) {
                    byte[] buffer = new byte[512];
                    while (in.read(buffer) != -1) {
                        // Drain the response so the connection can be reused.
                    }
                }
            }
            return status;
        } finally {
            connection.disconnect();
        }
    }

    private void onStorageActionClicked() {
        mStorageLegacyFallbackRequested = false;
        boolean fullAccess = StorageAccess.hasAllFilesAccess(this);
        boolean legacy = StorageAccess.hasLegacyStoragePermission(this);

        // The symlinks already exist and the user only wants to refresh them, for example
        // after inserting an SD card.
        if (fullAccess || (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && legacy)) {
            runStorageSetup();
            return;
        }

        boolean started = StorageAccess.requestStoragePermission(this, REQUEST_STORAGE_PERMISSION);
        if (!started && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The "All files access" settings screen is not available on this device. Fall
            // back to the legacy runtime permission so that the primary storage still works.
            started = StorageAccess.requestLegacyStoragePermission(this, REQUEST_STORAGE_PERMISSION);
        }
        if (!started) {
            android.widget.Toast.makeText(this, R.string.opencode_storage_permission_failed,
                android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Called after the "All files access" settings screen or the legacy runtime permission
     * dialog returns. The result code is not reliable here, so the actual permission state is
     * checked instead.
     */
    private void onStoragePermissionReturned() {
        boolean fullAccess = StorageAccess.hasAllFilesAccess(this);
        boolean legacy = StorageAccess.hasLegacyStoragePermission(this);

        if (fullAccess || legacy) {
            mStorageLegacyFallbackRequested = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !fullAccess) {
                android.widget.Toast.makeText(this, R.string.opencode_storage_primary_only_toast,
                    android.widget.Toast.LENGTH_LONG).show();
            } else {
                android.widget.Toast.makeText(this, R.string.opencode_storage_granted_toast,
                    android.widget.Toast.LENGTH_SHORT).show();
            }
            runStorageSetup();
            FileManagerDialog.refreshActive();
            if (mOpenFileManagerAfterStorage) {
                mOpenFileManagerAfterStorage = false;
                openFileManager();
            }
            return;
        }

        // On Android 11+ the user may decline "All files access". Fall back to the legacy
        // permission once so that at least the primary shared storage keeps working.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !mStorageLegacyFallbackRequested) {
            mStorageLegacyFallbackRequested = true;
            if (StorageAccess.requestLegacyStoragePermission(this, REQUEST_STORAGE_PERMISSION)) {
                return;
            }
        }

        mStorageLegacyFallbackRequested = false;
        mOpenFileManagerAfterStorage = false;
        updateStorageUi();
        android.widget.Toast.makeText(this, R.string.opencode_storage_denied,
            android.widget.Toast.LENGTH_LONG).show();
    }

    private void ensureStorageLinksIfNeeded() {
        if (mStorageSetupRunning || !StorageAccess.hasStoragePermission(this)) return;

        File shared = new File(TermuxConstants.TERMUX_STORAGE_HOME_DIR, "shared");
        if (!shared.exists()) {
            runStorageSetup();
            return;
        }

        // A secondary volume may have been inserted after the last setup, or the permission
        // needed for it may have been granted after the primary storage was already set up.
        if (!StorageAccess.hasSecondaryVolumeAccess(this)) return;
        int index = 0;
        for (File root : StorageAccess.volumeRoots(this)) {
            if (StorageAccess.isPrimaryRoot(root)) continue;
            index++;
            File link = new File(TermuxConstants.TERMUX_STORAGE_HOME_DIR, "sdcard" + index);
            if (!link.exists()) {
                runStorageSetup();
                return;
            }
        }
    }

    private void runStorageSetup() {
        if (mStorageSetupRunning) return;
        mStorageSetupRunning = true;
        if (mStorageStatus != null) mStorageStatus.setText(R.string.opencode_storage_setting_up);
        if (mStorageGrantButton != null) mStorageGrantButton.setEnabled(false);

        try {
            TermuxInstaller.setupStorageSymlinks(getApplicationContext(), new Runnable() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mStorageSetupRunning = false;
                            if (isFinishing() || isDestroyed()) return;
                            updateStorageUi();
                            // The file manager may be open while the symlinks are created.
                            FileManagerDialog.refreshActive();
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start the storage setup", t);
            mStorageSetupRunning = false;
            updateStorageUi();
        }
    }

    private void updateStorageUi() {
        if (mStorageStatus == null || mStorageSetupRunning) return;

        boolean fullAccess = StorageAccess.hasAllFilesAccess(this);
        boolean legacy = StorageAccess.hasLegacyStoragePermission(this);

        if (mStorageGrantButton != null) {
            mStorageGrantButton.setEnabled(true);
            if (fullAccess || (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && legacy)) {
                mStorageGrantButton.setText(R.string.opencode_storage_refresh);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && legacy) {
                mStorageGrantButton.setText(R.string.opencode_storage_grant_sd);
            } else {
                mStorageGrantButton.setText(R.string.opencode_storage_grant);
            }
        }

        if (!fullAccess && !legacy) {
            mStorageStatus.setText(R.string.opencode_storage_not_granted);
            return;
        }

        StringBuilder status = new StringBuilder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !fullAccess) {
            status.append(getString(R.string.opencode_storage_primary_only));
        } else {
            status.append(getString(R.string.opencode_storage_granted));
        }

        File shared = new File(TermuxConstants.TERMUX_STORAGE_HOME_DIR, "shared");
        if (shared.exists()) {
            status.append('\n').append(getString(R.string.opencode_storage_shared_path,
                StorageAccess.primaryRoot().getAbsolutePath()));
        } else {
            status.append('\n').append(getString(R.string.opencode_storage_links_missing));
        }

        if (StorageAccess.hasSecondaryVolumeAccess(this)) {
            int sdIndex = 0;
            for (File root : StorageAccess.volumeRoots(this)) {
                if (StorageAccess.isPrimaryRoot(root)) continue;
                sdIndex++;
                status.append('\n').append(getString(R.string.opencode_storage_sd_path,
                    sdIndex, root.getAbsolutePath()));
            }
            if (sdIndex == 0) {
                status.append('\n').append(getString(R.string.opencode_storage_no_sd));
            }
        }

        status.append('\n').append(getString(R.string.opencode_storage_hint));
        mStorageStatus.setText(status.toString());
    }

    public static Intent newInstance(Context context) {
        Intent intent = new Intent(context, OpenCodeWebActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            onStoragePermissionReturned();
            return;
        }
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (mFilePathCallback == null) return;

            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                if (data != null && data.getClipData() != null) {
                    ClipData clipData = data.getClipData();
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data != null && data.getData() != null) {
                    results = new Uri[] { data.getData() };
                }
            }

            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }
}
