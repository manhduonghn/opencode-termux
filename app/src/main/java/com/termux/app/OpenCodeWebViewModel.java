package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

/**
 * Keeps the OpenCode {@link WebView} and the state of the page it displays alive across activity
 * recreation, for example when the system light/dark mode changes. Without it every recreation
 * would create a new WebView, pair with the service again and reload the whole web UI.
 *
 * <p>The WebView is created with a {@link MutableContextWrapper} instead of a concrete activity,
 * so the retained instance never keeps a destroyed activity alive. The wrapper is re-pointed at
 * the current activity on every creation, which also makes any window the WebView itself creates
 * (for example a {@code <select>} popup or a {@code prompt()} dialog) use the activity that is
 * actually on screen. The activity re-attaches the WebView and installs fresh clients on every
 * creation.</p>
 */
public final class OpenCodeWebViewModel extends AndroidViewModel {

    /** Wraps the activity that currently owns the WebView so it can be swapped on recreation. */
    private MutableContextWrapper mContextWrapper;
    private WebView mWebView;

    private boolean mWebUiStarted = false;
    private boolean mAuthCompleted = false;
    private boolean mPageError = false;
    private int mLoadGeneration = 0;

    public OpenCodeWebViewModel(@NonNull Application application) {
        super(application);
    }

    /**
     * Return the retained WebView, creating it on first use. Must be called on the UI thread.
     *
     * @param activityContext the activity that will host the WebView; it replaces the previous
     *                        one so that the retained WebView always uses the live activity.
     */
    @NonNull
    public WebView getOrCreateWebView(@NonNull Context activityContext) {
        if (mWebView == null) {
            mContextWrapper = new MutableContextWrapper(activityContext);
            mWebView = new WebView(mContextWrapper);
        } else {
            mContextWrapper.setBaseContext(activityContext);
        }
        return mWebView;
    }

    public boolean isWebUiStarted() {
        return mWebUiStarted;
    }

    public void setWebUiStarted(boolean webUiStarted) {
        mWebUiStarted = webUiStarted;
    }

    public boolean isAuthCompleted() {
        return mAuthCompleted;
    }

    public void setAuthCompleted(boolean authCompleted) {
        mAuthCompleted = authCompleted;
    }

    public boolean isPageError() {
        return mPageError;
    }

    public void setPageError(boolean pageError) {
        mPageError = pageError;
    }

    public int getLoadGeneration() {
        return mLoadGeneration;
    }

    /** Start a new load attempt and return its generation. */
    public int nextLoadGeneration() {
        return ++mLoadGeneration;
    }

    /** Forget everything about the current page and cancel a load that may still be running. */
    public void resetWebUiState() {
        mLoadGeneration++;
        mWebUiStarted = false;
        mAuthCompleted = false;
        mPageError = false;
        if (mWebView != null) mWebView.stopLoading();
    }

    @Override
    protected void onCleared() {
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.setWebChromeClient(null);
            mWebView.setWebViewClient(null);
            if (mWebView.getParent() instanceof ViewGroup) {
                ((ViewGroup) mWebView.getParent()).removeView(mWebView);
            }
            mWebView.destroy();
            mWebView = null;
        }
        // Drop the reference to the last activity so nothing can leak through the wrapper.
        if (mContextWrapper != null) {
            mContextWrapper.setBaseContext(getApplication());
            mContextWrapper = null;
        }
    }
}
