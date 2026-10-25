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
import android.webkit.CookieManager;
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
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
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
    private static final long SERVICE_START_TIMEOUT_MS = 60_000L;
    private static final long CLI_TIMEOUT_MS = 30_000L;

    private WebView mWebView;
    private View mStatusView;
    private ProgressBar mProgressBar;
    private ProgressBar mGitHubProgress;
    private Button mGitHubCopyButton;
    private TextView mGitHubCodeView;
    private TextView mGitHubChip;
    private TextView mStatusText;
    private Button mRetryButton;
    private String mGitHubCode;

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
    private boolean mWebUiStarted = false;
    private boolean mReloadWebAfterInstall = false;
    private boolean mAuthCompleted = false;
    private boolean mPageError = false;

    /**
     * Incremented for every load attempt. A result from a start thread that finished after the
     * user reloaded, exited and reopened the app, is discarded so that it cannot overwrite the
     * state of the newer load.
     */
    private int mLoadGeneration = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_opencode_web);

        mWebView = findViewById(R.id.opencode_web_view);
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

        configureGitHubSettings();
        configureUpdateCheck();
        setupWakeLock();

        configureWebView();
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
            mWebView.stopLoading();
            mWebView.setWebChromeClient(null);
            mWebView.setWebViewClient(null);
            mWebView.destroy();
            mWebView = null;
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
        }
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

                if (!mPageError && !mAuthCompleted && url != null && !url.contains("/auth/connect/")) {
                    mAuthCompleted = true;
                    // Drop the one time pair redirect from the history, so the system back
                    // button leaves the web UI instead of showing the expired pair page.
                    view.clearHistory();
                }

                if (!mPageError) showContent();
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

        if (mWebUiStarted) return;
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
        mLoadGeneration++;
        mWebUiStarted = false;
        mAuthCompleted = false;
        mPageError = false;
        if (mWebView != null) mWebView.stopLoading();
    }

    /** Start the service, pair this WebView and load the web UI. */
    private void loadWebUi() {
        final int generation = ++mLoadGeneration;

        mWebUiStarted = true;
        mPageError = false;
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
                    if (generation != mLoadGeneration) return;
                    if (mWebView == null) return;

                    if (url != null) {
                        // The server only answers the one time pair link with the session cookie
                        // redirect when the request looks like a browser navigation. WebView sends
                        // this by default, but be explicit so the pair link can never return JSON.
                        HashMap<String, String> headers = new HashMap<String, String>();
                        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                        mWebView.loadUrl(url, headers);
                    } else {
                        mWebUiStarted = false;
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
        mPageError = true;
        mWebUiStarted = false;

        String message = getString(R.string.opencode_web_failed);
        if (details != null && !details.trim().isEmpty()) {
            message += "\n\n" + details.trim();
        }
        showStatus(message, true);
    }

    private void configureGitHubSettings() {
        final android.widget.CompoundButton githubCheck = findViewById(R.id.opencode_extension_github);
        final android.view.View githubRow = findViewById(R.id.opencode_github_row);
        final android.view.View githubPanel = findViewById(R.id.opencode_github_panel);
        final android.widget.TextView status = findViewById(R.id.opencode_github_status);
        final android.widget.TextView installStatus = findViewById(R.id.opencode_github_install_status);
        final android.widget.Button installBtn = findViewById(R.id.opencode_github_install_button);
        final android.widget.Button loginBtn = findViewById(R.id.opencode_github_login_button);
        final android.widget.Button logoutBtn = findViewById(R.id.opencode_github_logout_button);
        final android.widget.TextView user = findViewById(R.id.opencode_github_user);
        final android.widget.TextView codeView = findViewById(R.id.opencode_github_code);
        final android.widget.TextView linkView = findViewById(R.id.opencode_github_link);
        final android.widget.TextView hintView = findViewById(R.id.opencode_github_hint);
        final android.view.View cloneSection = findViewById(R.id.opencode_github_clone_section);
        final EditText cloneUrl = findViewById(R.id.opencode_github_clone_url);
        final android.widget.Button cloneBtn = findViewById(R.id.opencode_github_clone_button);
        mGitHubProgress = findViewById(R.id.opencode_github_progress);
        mGitHubChip = findViewById(R.id.opencode_github_chip);
        mGitHubCodeView = findViewById(R.id.opencode_github_code);
        mGitHubCopyButton = findViewById(R.id.opencode_github_copy_button);
        if (mGitHubCopyButton != null) {
            mGitHubCopyButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mGitHubCode == null || mGitHubCode.isEmpty()) return;
                    android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("GitHub device code", mGitHubCode));
                        android.widget.Toast.makeText(OpenCodeWebActivity.this,
                            "Đã sao chép mã " + mGitHubCode, android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (githubRow != null && githubCheck != null) {
            githubRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    githubCheck.setChecked(!githubCheck.isChecked());
                }
            });
        }

        if (githubCheck != null && githubPanel != null) {
            githubCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (isChecked) {
                        githubPanel.setVisibility(View.VISIBLE);
                        if (loginBtn != null) { loginBtn.setVisibility(View.VISIBLE); loginBtn.setEnabled(true); loginBtn.setText("Đăng nhập GitHub"); }
                        if (logoutBtn != null) { logoutBtn.setVisibility(View.GONE); }
                        updateGitHubState(status, installStatus, installBtn, loginBtn, logoutBtn, user, codeView, linkView, hintView, cloneSection);
                    } else {
                        githubPanel.setVisibility(View.GONE);
                    }
                }
            });
        }
        if (installBtn != null) {
            installBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doInstallGitHub(status, installStatus, installBtn, loginBtn, logoutBtn, user, codeView, linkView, hintView, cloneSection);
                }
            });
        }
        if (loginBtn != null) {
            loginBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doGitHubLogin(status, installStatus, installBtn, loginBtn, logoutBtn, user, codeView, linkView, hintView, cloneSection);
                }
            });
        }
        if (logoutBtn != null) {
            logoutBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            runGitHub(new String[]{"auth", "logout", "-h", "github.com", "-y"});
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updateGitHubState(status, installStatus, installBtn, loginBtn, logoutBtn, user, codeView, linkView, hintView, cloneSection);
                                    syncNativeWithGitHub(status);
                                }
                            });
                        }
                    }, "opencode-gh-logout").start();
                }
            });
        }
        if (cloneBtn != null) {
            cloneBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String repo = null;
                    if (cloneUrl != null) {
                        repo = cloneUrl.getText().toString();
                    }
                    if (repo != null) repo = repo.trim();
                    if (repo == null || repo.length() == 0) return;
                    final String r = repo;
                    setGitHubBusy(true);
                    if (installStatus != null) {
                        installStatus.setVisibility(View.VISIBLE);
                        installStatus.setText("Đang chuẩn bị clone...");
                    }
                    if (status != null) status.setText("Đang clone repository...");
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            String url = r;
                            if (url.indexOf("github.com") < 0 && !url.startsWith("http") && url.indexOf("/") > 0) {
                                url = "https://github.com/" + url;
                                if (!url.endsWith(".git")) url = url + ".git";
                            }
                            final String cmd = "git clone " + shellQuote(url);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (installStatus != null) {
                                        installStatus.setVisibility(View.VISIBLE);
                                        installStatus.setText("Đang chạy:\n" + cmd);
                                    }
                                }
                            });
                            final StringBuilder liveLog = new StringBuilder();
                            final long[] lastUiUpdate = new long[]{0L};
                            String out = runShellStreaming(cmd, 900000L, new StreamListener() {
                                @Override
                                public void onLine(String line) {
                                    synchronized (liveLog) {
                                        liveLog.append(line).append('\n');
                                    }
                                    long now = SystemClock.uptimeMillis();
                                    if (now - lastUiUpdate[0] < 200L) return;
                                    lastUiUpdate[0] = now;
                                    final String tail = tailOf(liveLog.toString(), 500);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (installStatus != null) {
                                                installStatus.setVisibility(View.VISIBLE);
                                                installStatus.setText("git clone\n" + tail);
                                            }
                                        }
                                    });
                                }
                            });
                            setGitHubBusy(false);
                            final String trimmed = out == null ? "" : out.trim();
                            final boolean ok = !trimmed.toLowerCase().contains("fatal:")
                                && !trimmed.toLowerCase().contains("error:");
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (installStatus != null) {
                                        String shown = trimmed;
                                        if (shown.length() > 600) shown = shown.substring(0, 600) + "...";
                                        installStatus.setText("git clone\n" + (shown.isEmpty() ? "(không có output)" : shown));
                                        installStatus.setVisibility(View.VISIBLE);
                                    }
                                    if (status != null) {
                                        status.setText(ok ? "Clone hoàn tất" : "Clone thất bại, xem log bên dưới");
                                    }
                                    android.widget.Toast.makeText(OpenCodeWebActivity.this,
                                        ok ? "Clone hoàn tất" : "Clone thất bại",
                                        android.widget.Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }, "opencode-gh-clone").start();
                }
            });
        }
    }

    private void setGitHubChip(final String text, final boolean connected) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mGitHubChip == null) return;
                mGitHubChip.setText(text);
                android.content.res.ColorStateList background = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(OpenCodeWebActivity.this,
                        connected ? R.color.github_chip_ok_bg : R.color.github_chip_off_bg));
                mGitHubChip.setBackgroundTintList(background);
                mGitHubChip.setTextColor(ContextCompat.getColor(OpenCodeWebActivity.this,
                    connected ? R.color.github_accent : R.color.github_muted));
            }
        });
    }

    private void showGitHubCode(final String code) {
        if (code == null || code.isEmpty()) return;
        mGitHubCode = code;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mGitHubCodeView != null) {
                    mGitHubCodeView.setText("MÃ XÁC MINH: " + code);
                    mGitHubCodeView.setVisibility(View.VISIBLE);
                }
                if (mGitHubCopyButton != null) {
                    mGitHubCopyButton.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void setGitHubBusy(final boolean busy) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mGitHubProgress != null) {
                    mGitHubProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    private static String tailOf(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return "..." + text.substring(text.length() - maxChars);
    }

    private String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private boolean isGitInstalled() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "git").canExecute();
    }

    private boolean isGhInstalled() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "gh").canExecute();
    }

    private boolean isGhLoggedIn() {
        GhResult r = runGitHubResult(new String[]{"auth", "status", "-h", "github.com"});
        if (!r.isSuccess()) return false;
        String lower = r.output.toLowerCase();
        // Logged out output is "You are not logged into any GitHub hosts", which contains the
        // substring "logged in", so check for the exact success wording instead.
        if (lower.contains("not logged")) return false;
        return lower.contains("logged in to github.com");
    }

    private boolean isGitHubUsername(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || v.indexOf('\n') >= 0 || v.indexOf(' ') >= 0) return false;
        return v.matches("[A-Za-z0-9][A-Za-z0-9-]*");
    }

    private void updateGitHubState(final android.widget.TextView status, final android.widget.TextView installStatus, final android.widget.Button installBtn, final android.widget.Button loginBtn, final android.widget.Button logoutBtn, final android.widget.TextView user, final android.widget.TextView codeView, final android.widget.TextView linkView, final android.widget.TextView hintView, final android.view.View cloneSection) {
        setGitHubBusy(false);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mGitHubCode = null;
                if (codeView != null) { codeView.setVisibility(View.GONE); codeView.setText(""); }
                if (mGitHubCopyButton != null) mGitHubCopyButton.setVisibility(View.GONE);
                if (linkView != null) { linkView.setVisibility(View.GONE); linkView.setText(""); }
                if (hintView != null) { hintView.setVisibility(View.GONE); hintView.setText(""); }
                if (user != null) { user.setVisibility(View.GONE); user.setText(""); }
                if (cloneSection != null) cloneSection.setVisibility(View.GONE);
                if (installBtn != null) installBtn.setVisibility(View.GONE);
                if (loginBtn != null) { loginBtn.setVisibility(View.VISIBLE); loginBtn.setEnabled(true); loginBtn.setText("Đăng nhập GitHub"); }
                if (logoutBtn != null) { logoutBtn.setVisibility(View.GONE); }
                setGitHubChip("Đang kiểm tra", false);
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean gitOk = isGitInstalled();
                boolean ghOk = isGhInstalled();
                boolean loggedIn = ghOk && isGhLoggedIn();
                String who = null;
                if (loggedIn) {
                    GhResult whoResult = runGitHubResult(new String[]{"api", "user", "-q", ".login"});
                    if (whoResult.isSuccess()) {
                        String candidate = whoResult.output.trim();
                        if (isGitHubUsername(candidate)) who = candidate;
                    }
                    if (who == null) loggedIn = false;
                }
                final boolean gitOkF = gitOk;
                final boolean ghOkF = ghOk;
                final boolean loggedInF = loggedIn && who != null && !who.isEmpty();
                final String whoF = who;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (installStatus != null) {
                            StringBuilder isb = new StringBuilder();
                            isb.append("git: ").append(gitOkF ? "đã cài" : "chưa cài").append(" | ");
                            isb.append("gh: ").append(ghOkF ? "đã cài" : "chưa cài");
                            installStatus.setText(isb.toString());
                            installStatus.setVisibility(View.VISIBLE);
                        }
                        if (!gitOkF || !ghOkF) {
                            status.setText("Chưa có git/gh trong Termux. Cài đặt để bắt đầu.");
                            setGitHubChip("Chưa cài đặt", false);
                            if (installBtn != null) { installBtn.setVisibility(View.VISIBLE); installBtn.setEnabled(true); installBtn.setText("Cài đặt git & gh"); }
                            if (loginBtn != null) { loginBtn.setVisibility(View.VISIBLE); loginBtn.setEnabled(true); loginBtn.setText("Đăng nhập GitHub"); }
                            if (logoutBtn != null) { logoutBtn.setVisibility(View.GONE); }
                            return;
                        }
                        if (installBtn != null) { installBtn.setVisibility(View.GONE); }
                        if (!loggedInF) {
                            status.setText("Chưa đăng nhập. Đăng nhập để clone cả repository riêng tư.");
                            setGitHubChip("Chưa đăng nhập", false);
                            if (loginBtn != null) { loginBtn.setVisibility(View.VISIBLE); loginBtn.setEnabled(true); loginBtn.setText("Đăng nhập GitHub"); }
                            if (logoutBtn != null) { logoutBtn.setVisibility(View.GONE); }
                            return;
                        }
                        status.setText("Đã kết nối GitHub");
                        setGitHubChip("Đã kết nối", true);
                        if (user != null && whoF != null) {
                            user.setText("Tài khoản: " + whoF);
                            user.setVisibility(View.VISIBLE);
                        }
                        if (loginBtn != null) { loginBtn.setVisibility(View.GONE); }
                        if (logoutBtn != null) { logoutBtn.setVisibility(View.VISIBLE); }
                        if (cloneSection != null) cloneSection.setVisibility(View.VISIBLE);
                    }
                });
            }
        }, "opencode-gh-state").start();
    }

    private void doInstallGitHub(final android.widget.TextView status, final android.widget.TextView installStatus, final android.widget.Button installBtn, final android.widget.Button loginBtn, final android.widget.Button logoutBtn, final android.widget.TextView user, final android.widget.TextView codeView, final android.widget.TextView linkView, final android.widget.TextView hintView, final android.view.View cloneSection) {
        setGitHubBusy(true);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (installBtn != null) { installBtn.setEnabled(false); installBtn.setText("Đang cài..."); }
                if (installStatus != null) { installStatus.setVisibility(View.VISIBLE); installStatus.setText("Đang chạy: pkg install -y git gh"); }
                status.setText("Đang cài đặt git và gh theo chuẩn Termux...");
                setGitHubChip("Đang cài đặt", false);
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder liveLog = new StringBuilder();
                final long[] lastUiUpdate = new long[]{0L};
                String out = runShellStreaming("pkg install -y git gh", 900000L, new StreamListener() {
                    @Override
                    public void onLine(String line) {
                        synchronized (liveLog) {
                            liveLog.append(line).append('\n');
                        }
                        long now = SystemClock.uptimeMillis();
                        if (now - lastUiUpdate[0] < 200L) return;
                        lastUiUpdate[0] = now;
                        final String tail = tailOf(liveLog.toString(), 500);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (installStatus != null) {
                                    installStatus.setVisibility(View.VISIBLE);
                                    installStatus.setText("pkg install -y git gh\n" + tail);
                                }
                            }
                        });
                    }
                });
                setGitHubBusy(false);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (installStatus != null) {
                            String s = out == null ? "" : out.trim();
                            if (s.length() > 400) s = s.substring(0, 400) + "...";
                            installStatus.setText("pkg install -y git gh\n" + s);
                        }
                        if (installBtn != null) { installBtn.setEnabled(true); installBtn.setText("Cài lại git & gh"); }
                        updateGitHubState(status, installStatus, installBtn, loginBtn, logoutBtn, user, codeView, linkView, hintView, cloneSection);
                    }
                });
            }
        }, "opencode-gh-install").start();
    }

    private void doGitHubLogin(final android.widget.TextView status, final android.widget.TextView installStatus, final android.widget.Button installBtn, final android.widget.Button loginBtn, final android.widget.Button logoutBtn, final android.widget.TextView user, final android.widget.TextView codeView, final android.widget.TextView linkView, final android.widget.TextView hintView, final android.view.View cloneSection) {
        setGitHubBusy(true);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (loginBtn != null) { loginBtn.setEnabled(false); loginBtn.setText("Đang đăng nhập..."); }
                mGitHubCode = null;
                if (codeView != null) { codeView.setVisibility(View.GONE); codeView.setText(""); }
                if (mGitHubCopyButton != null) mGitHubCopyButton.setVisibility(View.GONE);
                if (linkView != null) { linkView.setVisibility(View.GONE); linkView.setText(""); }
                if (hintView != null) { hintView.setVisibility(View.GONE); hintView.setText(""); }
                status.setText("Đang chạy: gh auth login -h github.com -p https --web -s repo,read:org");
                setGitHubChip("Đang đăng nhập", false);
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!isGitInstalled() || !isGhInstalled()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("Chưa có git/gh, đang cài đặt trước khi đăng nhập...");
                            setGitHubChip("Đang cài đặt", false);
                        }
                    });
                    final StringBuilder installLog = new StringBuilder();
                    final long[] lastInstallUpdate = new long[]{0L};
                    runShellStreaming("pkg install -y git gh", 900000L, new StreamListener() {
                        @Override
                        public void onLine(String line) {
                            synchronized (installLog) {
                                installLog.append(line).append('\n');
                            }
                            long now = SystemClock.uptimeMillis();
                            if (now - lastInstallUpdate[0] < 200L) return;
                            lastInstallUpdate[0] = now;
                            final String tail = tailOf(installLog.toString(), 400);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (installStatus != null) {
                                        installStatus.setVisibility(View.VISIBLE);
                                        installStatus.setText("pkg install -y git gh\n" + tail);
                                    }
                                }
                            });
                        }
                    });
                    if (!isGitInstalled() || !isGhInstalled()) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                status.setText("Cài đặt git/gh thất bại. Hãy thử lại nút 'Cài đặt git & gh'.");
                                setGitHubChip("Chưa cài đặt", false);
                                if (loginBtn != null) { loginBtn.setEnabled(true); loginBtn.setText("Đăng nhập GitHub"); }
                                if (installBtn != null) { installBtn.setVisibility(View.VISIBLE); installBtn.setEnabled(true); }
                                setGitHubBusy(false);
                            }
                        });
                        return;
                    }
                }
                final String[] shownCode = new String[]{null};
                final String[] openedUrl = new String[]{null};
                final String deviceUrl = "https://github.com/login/device";

                GhResult result = runGitHubStreaming(
                    new String[]{"auth", "login", "-h", "github.com", "-p", "https", "--web", "-s", "repo", "-s", "read:org"},
                    900000L,
                    new GhLineListener() {
                        @Override
                        public void onLine(String line, String outputSoFar, java.io.BufferedWriter stdin) {
                            final String ghLine = line == null ? "" : line.trim();
                            if (!ghLine.isEmpty()) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (installStatus != null) {
                                            installStatus.setVisibility(View.VISIBLE);
                                            installStatus.setText("gh: " + ghLine);
                                        }
                                    }
                                });
                            }
                            String code = extractGhCode(outputSoFar);
                            if (code != null && !code.isEmpty() && !code.equals(shownCode[0])) {
                                shownCode[0] = code;
                                showGitHubCode(code);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (hintView != null) {
                                            hintView.setText("Sao chép mã ở trên, mở link bên dưới và nhập mã để đăng nhập.");
                                            hintView.setVisibility(View.VISIBLE);
                                        }
                                        status.setText("Nhập mã " + shownCode[0] + " tại github.com/login/device");
                                        setGitHubChip("Chờ xác thực", false);
                                    }
                                });
                            }

                            String link = extractGhLink(outputSoFar);
                            final String currentLink = (link == null || link.isEmpty()) ? null : link;
                            if (currentLink != null && !currentLink.equals(openedUrl[0])) {
                                openedUrl[0] = currentLink;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (linkView != null) {
                                            linkView.setText(currentLink);
                                            linkView.setVisibility(View.VISIBLE);
                                        }
                                    }
                                });
                            }
                            if (openedUrl[0] == null) {
                                openedUrl[0] = deviceUrl;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (linkView != null) {
                                            linkView.setText(deviceUrl);
                                            linkView.setVisibility(View.VISIBLE);
                                        }
                                        try {
                                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(deviceUrl)));
                                        } catch (Exception ignored) {
                                        }
                                    }
                                });
                            }
                            // Interactive gh waits for Enter; ignored in non-interactive mode.
                            String lower = line == null ? "" : line.toLowerCase();
                            if (lower.contains("press enter") || lower.contains("(y/n)")
                                || lower.contains("continue?") || lower.contains("one-time code")) {
                                try {
                                    stdin.write("\n");
                                    stdin.flush();
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    });

                if (!result.isSuccess() && !isGhLoggedIn()) {
                    if (openedUrl[0] == null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (linkView != null) {
                                    linkView.setText(deviceUrl);
                                    linkView.setVisibility(View.VISIBLE);
                                }
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(deviceUrl)));
                                } catch (Exception ignored) {
                                }
                            }
                        });
                    }
                    if (shownCode[0] == null) {
                        String code = extractGhCode(result.output);
                        if (code != null && !code.isEmpty()) {
                            shownCode[0] = code;
                            showGitHubCode(code);
                        }
                    }
                }

                boolean loggedIn = isGhLoggedIn();
                if (loggedIn) {
                    // Let plain "git clone" use the gh credential helper, including private repos.
                    runGitHub(new String[]{"auth", "setup-git"});
                }
                setGitHubBusy(false);
                final boolean success = loggedIn;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (loginBtn != null) { loginBtn.setEnabled(true); loginBtn.setText("Đăng nhập lại"); }
                        if (success) {
                            status.setText("Đăng nhập thành công");
                            setGitHubChip("Đã kết nối", true);
                            if (codeView != null) { codeView.setVisibility(View.GONE); codeView.setText(""); }
                            if (linkView != null) { linkView.setVisibility(View.GONE); linkView.setText(""); }
                            if (hintView != null) { hintView.setVisibility(View.GONE); hintView.setText(""); }
                            updateGitHubState(status, installStatus, installBtn, loginBtn, logoutBtn, user, codeView, linkView, hintView, cloneSection);
                            syncNativeWithGitHub(status);
                        } else {
                            status.setText("Chưa hoàn tất đăng nhập. Nhập mã rồi bấm 'Đăng nhập lại' nếu cần.");
                            setGitHubChip("Chưa đăng nhập", false);
                            if (hintView != null) hintView.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }, "opencode-gh-login").start();
    }

    /**
     * Restart the OpenCode background service so its native process inherits GH_TOKEN from the
     * launcher. Editing the gh configuration alone is not enough for an already running daemon.
     */
    private void syncNativeWithGitHub(final android.widget.TextView status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (status != null) status.setText("Đang đồng bộ GitHub với OpenCode native...");
                setGitHubBusy(true);
                OpenCodeService.stopAsync(getApplicationContext(), false, new Runnable() {
                    @Override
                    public void run() {
                        setGitHubBusy(false);
                        if (isFinishing() || isDestroyed()) return;
                        reloadWebUi();
                    }
                });
            }
        });
    }

    private String extractGhLink(String s) {
        if (s == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://github\\.com/login/device\\S+");
        java.util.regex.Matcher m = p.matcher(s);
        if (m.find()) return m.group().trim();
        p = java.util.regex.Pattern.compile("https?://github\\.com/[^\\s]+device[^\\s]*", java.util.regex.Pattern.CASE_INSENSITIVE);
        m = p.matcher(s);
        if (m.find()) return m.group().trim();
        p = java.util.regex.Pattern.compile("https?://[^\\s]+/login[^\\s]*", java.util.regex.Pattern.CASE_INSENSITIVE);
        m = p.matcher(s);
        if (m.find()) return m.group().trim();
        p = java.util.regex.Pattern.compile("https?://[^\\s]+");
        m = p.matcher(s);
        if (m.find()) return m.group().trim();
        return null;
    }

    private String extractGhCode(String s) {
        if (s == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("([A-Z0-9]{4}-[A-Z0-9]{4})");
        java.util.regex.Matcher m = p.matcher(s);
        if (m.find()) return m.group(1);
        p = java.util.regex.Pattern.compile("([A-Z0-9]{8})");
        m = p.matcher(s);
        if (m.find()) {
            String c = m.group(1);
            if (c != null && c.length() == 8) return c;
        }
        return null;
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

    private static final class GhResult {
        final int exitCode;
        final String output;

        GhResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private String runGitHub(String[] args) {
        return runGitHubResult(args).output;
    }

    private GhResult runGitHubResult(String[] args) {
        try {
            ProcessBuilder builder = new ProcessBuilder(buildGh(args));
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
            }, "opencode-gh-runner-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = SystemClock.uptimeMillis() + 180000L;
            int exitCode = -1;
            while (true) {
                try {
                    exitCode = process.exitValue();
                    break;
                } catch (IllegalThreadStateException e) {
                    if (SystemClock.uptimeMillis() > deadline) {
                        process.destroy();
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
                return new GhResult(exitCode, output.toString());
            }
        } catch (Exception ex) {
            return new GhResult(-1, ex.toString());
        }
    }

    private interface GhLineListener {
        void onLine(String line, String outputSoFar, java.io.BufferedWriter stdin);
    }

    /**
     * Run gh and deliver its combined output line by line while it is still running. This is
     * required for {@code gh auth login --web}, which prints the one time code and then blocks
     * waiting for the user, so buffering until exit would never show the code or open a browser.
     */
    private GhResult runGitHubStreaming(String[] args, long timeoutMs, GhLineListener listener) {
        try {
            ProcessBuilder builder = new ProcessBuilder(buildGh(args));
            applyTermuxEnv(builder);
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);

            final Process process = builder.start();
            final java.io.BufferedWriter stdin = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(process.getOutputStream()));
            final StringBuilder output = new StringBuilder();

            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        String snapshot;
                        synchronized (output) {
                            output.append(line).append('\n');
                            snapshot = output.toString();
                        }
                        if (listener != null) {
                            try {
                                listener.onLine(line, snapshot, stdin);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "opencode-gh-stream-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = SystemClock.uptimeMillis() + timeoutMs;
            int exitCode = -1;
            while (true) {
                try {
                    exitCode = process.exitValue();
                    break;
                } catch (IllegalThreadStateException e) {
                    if (SystemClock.uptimeMillis() > deadline) {
                        process.destroy();
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
            try {
                stdin.close();
            } catch (Exception ignored) {
            }
            synchronized (output) {
                return new GhResult(exitCode, output.toString());
            }
        } catch (Exception ex) {
            return new GhResult(-1, ex.toString());
        }
    }

    private String[] buildGh(String[] args) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String gh = prefix + "/bin/gh";
        java.io.File f = new java.io.File(gh);
        if (f.exists()) {
            String[] c = new String[1 + (args == null ? 0 : args.length)];
            c[0] = gh;
            if (args != null) System.arraycopy(args, 0, c, 1, args.length);
            return c;
        }
        String[] c = new String[1 + (args == null ? 0 : args.length)];
        c[0] = "gh";
        if (args != null) System.arraycopy(args, 0, c, 1, args.length);
        return c;
    }

    private String quote(String a) {
        if (a == null) return "''";
        return "'" + a.replace("'", "'\\''") + "'";
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

    public static Intent newInstance(Context context) {
        Intent intent = new Intent(context, OpenCodeWebActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
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
