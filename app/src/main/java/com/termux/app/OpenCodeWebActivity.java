package com.termux.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
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
 * session cookie. The terminal/OpenTUI is a secondary screen and is opened with the button in
 * the toolbar.</p>
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
    private TextView mStatusText;
    private Button mRetryButton;

    @Nullable
    private ValueCallback<Uri[]> mFilePathCallback;

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

        if (OpenCodeBootstrap.isRuntimeComplete()) maybeLoadWebUi();
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
        } else {
            super.onBackPressed();
        }
    }

    /** The terminal/OpenTUI is a separate Termux screen. */
    private void openTerminal() {
        startActivity(new Intent(this, TermuxActivity.class));
    }

    /* ==========================================================================================
     * Runtime setup
     * ========================================================================================== */

    /**
     * Make sure that the Termux bootstrap and the OpenCode runtime are available. The default
     * Termux progress dialog is shown for the whole setup. Once everything is ready the web UI
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
                TermuxInstaller.showStartupProgress(this, message);
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
                    TermuxInstaller.setStartupProgressMessage(OpenCodeWebActivity.this, state.message);
                } else {
                    TermuxInstaller.showStartupProgress(OpenCodeWebActivity.this, state.message);
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
            return;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATION_PERMISSION);
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
        android.widget.CheckBox githubCheck = findViewById(R.id.opencode_extension_github);
        android.view.View githubPanel = findViewById(R.id.opencode_github_panel);
        final android.widget.TextView status = findViewById(R.id.opencode_github_status);
        final android.widget.TextView detail = findViewById(R.id.opencode_github_detail);
        final android.widget.TextView loginLink = findViewById(R.id.opencode_github_login_link);
        final android.widget.Button installBtn = findViewById(R.id.opencode_github_install_button);
        final android.widget.Button loginBtn = findViewById(R.id.opencode_github_login_button);
        final android.widget.Button logoutBtn = findViewById(R.id.opencode_github_logout_button);
        final android.widget.TextView user = findViewById(R.id.opencode_github_user);
        final android.widget.Button cloneBtn = findViewById(R.id.opencode_github_clone_button);
        final ListView reposList = findViewById(R.id.opencode_github_repos_list);

        if (githubCheck != null && githubPanel != null) {
            githubCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (isChecked) {
                        githubPanel.setVisibility(View.VISIBLE);
                        ensureGitHubReady(status, detail, loginLink, installBtn, loginBtn, logoutBtn, user, cloneBtn, reposList);
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
                    ensureGitHubReady(status, detail, loginLink, installBtn, loginBtn, logoutBtn, user, cloneBtn, reposList);
                }
            });
        }
        if (loginBtn != null) {
            loginBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startGitHubLogin(status, detail, loginLink, installBtn, loginBtn, logoutBtn, user, cloneBtn, reposList);
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
                                    refreshGitHubStatus(status, detail, loginLink, installBtn, loginBtn, logoutBtn, user, cloneBtn, reposList);
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
                    final EditText input = new EditText(OpenCodeWebActivity.this);
                    new android.app.AlertDialog.Builder(OpenCodeWebActivity.this)
                        .setTitle(R.string.opencode_github_clone)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                String repo = input.getText().toString();
                                if (repo != null) {
                                    repo = repo.trim();
                                }
                                if (repo != null && repo.length() > 0) {
                                    final String r = repo;
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            runGitHub(new String[]{"repo", "clone", r});
                                        }
                                    }, "opencode-gh-clone").start();
                                }
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                }
            });
        }
    }

    private void setGhDetail(final android.widget.TextView detail, final String text) {
        if (detail == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (text == null || text.trim().isEmpty()) {
                    detail.setVisibility(View.GONE);
                    detail.setText("");
                } else {
                    detail.setVisibility(View.VISIBLE);
                    detail.setText(text.trim());
                }
            }
        });
    }

    private void ensureGitHubReady(final android.widget.TextView status, final android.widget.TextView detail, final android.widget.TextView loginLink, final android.widget.Button installBtn, final android.widget.Button loginBtn, final android.widget.Button logoutBtn, final android.widget.TextView user, final android.widget.Button cloneBtn, final ListView reposList) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText("Trạng thái: Đang kiểm tra môi trường Termux (PREFIX/HOME/PATH)");
                if (installBtn != null) installBtn.setEnabled(false);
                if (loginBtn != null) { loginBtn.setVisibility(View.GONE); loginBtn.setEnabled(false); }
                if (logoutBtn != null) { logoutBtn.setVisibility(View.GONE); logoutBtn.setEnabled(false); }
                if (cloneBtn != null) { cloneBtn.setVisibility(View.GONE); cloneBtn.setEnabled(false); }
                if (loginLink != null) { loginLink.setVisibility(View.GONE); loginLink.setText(""); }
                if (user != null) { user.setVisibility(View.GONE); user.setText(""); }
                if (reposList != null) { reposList.setVisibility(View.GONE); reposList.setAdapter(null); reposList.setOnItemClickListener(null); }
            }
        });
        setGhDetail(detail, "Prefix: " + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "\nHome: " + TermuxConstants.TERMUX_HOME_DIR_PATH);
        new Thread(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("Đang kiểm tra gh auth status...");
                    }
                });
                String out = runGitHub(new String[]{"auth", "status", "-h", "github.com"});
                if (out != null && out.toLowerCase().contains("logged in")) {
                    setGhDetail(detail, out.trim());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (installBtn != null) installBtn.setEnabled(true);
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refreshGitHubStatus(status, detail, loginLink, installBtn, loginBtn, logoutBtn, user, cloneBtn, reposList);
                        }
                    });
                    return;
                }
                setGhDetail(detail, "gh chưa đăng nhập hoặc chưa sẵn sàng\n\n" + (out == null ? "" : out.trim()));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("Chưa đăng nhập. Bắt đầu cài đặt git và gh bằng pkg install -y git gh");
                        if (installBtn != null) { installBtn.setEnabled(true); installBtn.setVisibility(View.VISIBLE); }
                        if (loginBtn != null) { loginBtn.setVisibility(View.GONE); loginBtn.setEnabled(false); }
                    }
                });
                // Don't auto-install here; wait for user to press install button. But if we want to be safe, install once.
            }
        }, "opencode-gh-ready").start();
    }

    private void startGitHubLogin(final android.widget.TextView status, final android.widget.TextView detail, final android.widget.TextView loginLink, final android.widget.Button installBtn, final android.widget.Button loginBtn, final android.widget.Button logoutBtn, final android.widget.TextView user, final android.widget.Button cloneBtn, final ListView reposList) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("Đang chuẩn bị đăng nhập GitHub...");
                        if (installBtn != null) installBtn.setEnabled(false);
                        if (loginBtn != null) { loginBtn.setVisibility(View.GONE); loginBtn.setEnabled(false); }
                        if (logoutBtn != null) { logoutBtn.setVisibility(View.GONE); logoutBtn.setEnabled(false); }
                        if (loginLink != null) { loginLink.setVisibility(View.GONE); loginLink.setText(""); }
                    }
                });
                setGhDetail(detail, "Bước 1: Kiểm tra đăng nhập hiện tại\n$ gh auth status -h github.com");
                String out = runGitHub(new String[]{"auth", "status", "-h", "github.com"});
                if (out != null && out.toLowerCase().contains("logged in")) {
                    setGhDetail(detail, out.trim());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refreshGitHubStatus(status, detail, loginLink, installBtn, loginBtn, logoutBtn, user, cloneBtn, reposList);
                        }
                    });
                    return;
                }
                setGhDetail(detail, (out == null ? "" : out.trim()) + "\n\nBước 2: Đảm bảo đã cài git, gh\n$ pkg install -y git gh");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("Đang cài đặt git và gh (pkg install -y git gh)...");
                    }
                });
                String pkgOut = runShell("pkg install -y git gh 2>&1", 300000L);
                setGhDetail(detail, "pkg install -y git gh\n" + (pkgOut == null ? "" : pkgOut.trim()));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("Đang khởi động gh auth login (github.com, https, web)...");
                    }
                });
                setGhDetail(detail, "Bước 3: Chạy đăng nhập\n$ gh auth login -h github.com -p https --web -s repo,read:org");
                String linkOut = runGitHub(new String[]{"auth", "login", "-h", "github.com", "-p", "https", "--web", "-s", "repo", "-s", "read:org"});
                final String link = extractGhLink(linkOut);
                final String code = extractGhCode(linkOut);
                setGhDetail(detail, (linkOut == null ? "" : linkOut.trim()));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        StringBuilder msg = new StringBuilder();
                        msg.append("Hướng dẫn đăng nhập (gh auth login --web)");
                        if (code != null && code.length() > 0) {
                            msg.append("\n\nMã xác minh (device code): ").append(code).append("\n");
                        }
                        if (link != null && link.length() > 0) {
                            msg.append("Link: ").append(link).append("\n");
                        }
                        msg.append("\nMở link trên trình duyệt, nhập mã (nếu có) và xác nhận.");
                        status.setText(msg.toString());
                        if (link != null && link.length() > 0) {
                            if (loginLink != null) {
                                loginLink.setText(link);
                                loginLink.setVisibility(View.VISIBLE);
                                loginLink.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        try {
                                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                                        } catch (Exception ignored) {
                                        }
                                    }
                                });
                            }
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                            } catch (Exception ignored) {
                            }
                        }
                        if (installBtn != null) installBtn.setEnabled(true);
                        if (loginBtn != null) { loginBtn.setVisibility(View.GONE); }
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(status.getText() + "\n\nĐang chờ hoàn tất xác thực trên trình duyệt...");
                    }
                });
                for (int i = 0; i < 300; i++) {
                    try {
                        Thread.sleep(2000L);
                    } catch (Exception ignored) {
                    }
                    String s2 = runGitHub(new String[]{"auth", "status", "-h", "github.com"});
                    if (s2 != null && s2.toLowerCase().contains("logged in")) {
                        setGhDetail(detail, s2.trim());
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                status.setText("Đăng nhập thành công. Đang lấy thông tin tài khoản...");
                            }
                        });
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                refreshGitHubStatus(status, detail, loginLink, installBtn, loginBtn, logoutBtn, user, cloneBtn, reposList);
                            }
                        });
                        break;
                    }
                }
            }
        }, "opencode-gh-login").start();
    }

    private void refreshGitHubStatus(final android.widget.TextView status, final android.widget.TextView detail, final android.widget.TextView loginLink, final android.widget.Button installBtn, final android.widget.Button loginBtn, final android.widget.Button logoutBtn, final android.widget.TextView user, final android.widget.Button cloneBtn, final ListView reposList) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String auth = runGitHub(new String[]{"auth", "status", "-h", "github.com"});
                String who = runGitHub(new String[]{"api", "user", "-q", ".login"});
                String repos = runGitHub(new String[]{"repo", "list", "--limit", "50", "--json", "nameWithOwner", "-q", ".[].nameWithOwner"});
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (auth != null && auth.toLowerCase().contains("logged in") && who != null && who.trim().length() > 0 && who.indexOf("error") < 0 && who.toLowerCase().indexOf("not logged") < 0) {
                            status.setText(R.string.opencode_github_logged_in);
                            setGhDetail(detail, "gh auth status:\n" + auth.trim() + "\n\nTài khoản: " + who.trim());
                            user.setText(who.trim());
                            user.setVisibility(View.VISIBLE);
                            if (loginLink != null) { loginLink.setVisibility(View.GONE); loginLink.setText(""); }
                            if (installBtn != null) { installBtn.setVisibility(View.VISIBLE); installBtn.setEnabled(true); }
                            if (loginBtn != null) { loginBtn.setVisibility(View.GONE); loginBtn.setEnabled(false); }
                            if (logoutBtn != null) { logoutBtn.setVisibility(View.VISIBLE); logoutBtn.setEnabled(true); }
                            if (cloneBtn != null) { cloneBtn.setVisibility(View.VISIBLE); cloneBtn.setEnabled(true); }
                            if (reposList != null) {
                                reposList.setVisibility(View.VISIBLE);
                                String[] items;
                                if (repos == null || repos.trim().length() == 0) {
                                    items = new String[0];
                                } else {
                                    items = repos.trim().split("\\n");
                                }
                                final String[] repoItems = items;
                                reposList.setAdapter(new ArrayAdapter(OpenCodeWebActivity.this, android.R.layout.simple_list_item_1, items));
                                reposList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                                    @Override
                                    public void onItemClick(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                                        if (repoItems == null || position < 0 || position >= repoItems.length) return;
                                        String repo = repoItems[position];
                                        if (repo == null) return;
                                        repo = repo.trim();
                                        if (repo.length() == 0) return;
                                        int slash = repo.indexOf('/');
                                        String name = slash >= 0 ? repo.substring(slash + 1) : repo;
                                        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
                                        String path = home + "/" + name;
                                        Intent intent = new Intent(OpenCodeWebActivity.this, com.termux.app.TermuxActivity.class);
                                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                        try {
                                            intent.putExtra("com.termux.RUN_COMMAND_PATH", path);
                                            intent.putExtra("com.termux.app.extra.CD_PATH", path);
                                            intent.putExtra("path", path);
                                            intent.putExtra("cwd", path);
                                        } catch (Exception ignored) {
                                        }
                                        try {
                                            startActivity(intent);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                });
                            }
                        } else {
                            status.setText(R.string.opencode_github_not_logged_in);
                            user.setVisibility(View.GONE);
                            if (loginLink != null) { loginLink.setVisibility(View.GONE); loginLink.setText(""); }
                            if (installBtn != null) { installBtn.setVisibility(View.VISIBLE); installBtn.setEnabled(true); }
                            if (loginBtn != null) { loginBtn.setVisibility(View.VISIBLE); loginBtn.setEnabled(true); }
                            if (logoutBtn != null) { logoutBtn.setVisibility(View.GONE); logoutBtn.setEnabled(false); }
                            if (cloneBtn != null) { cloneBtn.setVisibility(View.GONE); cloneBtn.setEnabled(false); }
                            setGhDetail(detail, (auth == null ? "" : auth.trim()));
                            if (reposList != null) {
                                reposList.setVisibility(View.GONE);
                                reposList.setOnItemClickListener(null);
                                reposList.setAdapter(null);
                            }
                        }
                    }
                });
            }
        }, "opencode-gh-refresh").start();
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

    private void applyTermuxEnv(ProcessBuilder builder) {
        Map<String, String> processEnv = builder.environment();
        processEnv.clear();
        try {
            processEnv.putAll(new TermuxShellEnvironment().getEnvironment(this, false));
        } catch (Exception ignored) {
            processEnv.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            processEnv.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            String path = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + processEnv.getOrDefault("PATH", "");
            processEnv.put("PATH", path);
            processEnv.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        }
        processEnv.put("TERM", "xterm-256color");
        processEnv.put("LANG", "en_US.UTF-8");
        processEnv.put("COLORTERM", "truecolor");
        processEnv.remove("LD_PRELOAD");
        processEnv.remove("LD_LIBRARY_PATH");
    }

    private String runGitHub(String[] args) {
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
            while (true) {
                try {
                    process.exitValue();
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
                return output.toString();
            }
        } catch (Exception ex) {
            return ex.toString();
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

    public static Intent newInstance(Context context) {
        Intent intent = new Intent(context, OpenCodeWebActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Nothing to forward: the notification permission is requested by the activity itself.
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
