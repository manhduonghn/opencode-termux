package com.termux.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.util.Collections;

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

        findViewById(R.id.opencode_web_terminal_button).setOnClickListener(v -> openTerminal());
        findViewById(R.id.opencode_web_reload_button).setOnClickListener(v -> reloadWebUi());
        mRetryButton.setOnClickListener(v -> reloadWebUi());

        configureWebView();
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
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** The terminal/OpenTUI is a separate Termux screen, opened from the web toolbar. */
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
            TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
                // This callback is also invoked when this activity was destroyed in the
                // meantime. The runtime installation is tied to the application, not to one
                // activity, so keep going and let the next activity show the progress.
                if (mStarted && !isFinishing() && !isDestroyed()
                    && !OpenCodeBootstrap.isRuntimeComplete()) {
                    TermuxInstaller.showStartupProgress(this, getString(R.string.opencode_installing));
                }
                Context context = getApplicationContext();
                OpenCodeBootstrap.setupIfNeeded(context, null);
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
    private final OpenCodeBootstrap.InstallListener mInstallListener = state -> {
        if (state == null || isFinishing() || isDestroyed()) return;

        if (state.running) {
            if (TermuxInstaller.isStartupProgressShowing(this)) {
                TermuxInstaller.setStartupProgressMessage(this, state.message);
            } else {
                TermuxInstaller.showStartupProgress(this, state.message);
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
    };

    /* ==========================================================================================
     * WebView
     * ========================================================================================== */

    @SuppressWarnings("deprecation")
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
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // A newer load was started (reload button, stop detected, ...) while this one was
                // waiting for the service. Do not let the stale result win.
                if (generation != mLoadGeneration) return;
                if (mWebView == null) return;

                if (url != null) {
                    // The server only answers the one time pair link with the session cookie
                    // redirect when the request looks like a browser navigation. WebView sends
                    // this by default, but be explicit so the pair link can never return JSON.
                    mWebView.loadUrl(url, Collections.singletonMap(
                        "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
                } else {
                    mWebUiStarted = false;
                    showError(message);
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
