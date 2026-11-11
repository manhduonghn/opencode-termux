package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.BuildConfig;
import com.termux.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An in-app code viewer and editor, modelled after the
 * <a href="https://github.com/Acode-Foundation/Acode">Acode</a> editor: the file is rendered
 * with CodeMirror 5 inside a WebView ({@code assets/editor/editor.html}) and offers syntax
 * highlighting for the languages of the CodeMirror modes bundled with the app, line numbers,
 * search, undo/redo, go to line and word wrap. Tapping a text or code file in the file
 * manager opens it here instead of handing it to another app.
 *
 * <p>The page talks to this dialog through the {@code AndroidBridge} object injected with
 * {@link WebView#addJavascriptInterface}: the content itself is pulled from Java with
 * {@code fetchContent()} so that large files never travel through
 * {@code evaluateJavascript()}, which is limited by the binder transaction size. Saving
 * uses the atomic {@link FileManagerUtils#writeText} that preserves the POSIX mode, and a
 * file that turned into a symbolic link while the viewer was open is never written.</p>
 */
final class CodeViewerDialog {

    /** Receives the actions that the viewer cannot perform on its own. */
    interface Listener {
        /** Called after the file was written back to disk. */
        void onSaved(File file);
    }

    private static final String PREFS_NAME = "opencode_cfg";
    private static final String PREF_WRAP = "code_viewer_wrap";
    private static final String PREF_FONT_SIZE = "code_viewer_font_size";

    private static final String ASSET_URL = "file:///android_asset/editor/editor.html";
    private static final String BRIDGE_NAME = "AndroidBridge";
    private static final String THEME_DARK = "material-darker";
    private static final String THEME_LIGHT = "eclipse";
    private static final String FILE_PROVIDER_SUFFIX = ".fileprovider";

    private static final int DEFAULT_FONT_SIZE = 14;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 28;
    private static final long PAGE_TIMEOUT_MS = 15_000L;

    private static final int ID_UNDO = 1;
    private static final int ID_REDO = 2;
    private static final int ID_GOTO = 3;
    private static final int ID_WRAP = 4;
    private static final int ID_FONT_SMALLER = 5;
    private static final int ID_FONT_LARGER = 6;
    private static final int ID_RELOAD = 7;
    private static final int ID_SHARE = 8;
    private static final int ID_OPEN_WITH = 9;
    private static final int ID_PROPERTIES = 10;

    /** The viewer that is currently shown, so a second request replaces it instead of stacking. */
    private static WeakReference<CodeViewerDialog> activeDialog;

    private final Activity activity;
    private final File file;
    private final boolean canWrite;
    private final Listener listener;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Dialog dialog;
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView titleView;
    private TextView subtitleView;
    private TextView statusView;
    private TextView errorView;
    private ProgressBar progressView;
    private ImageButton closeButton;
    private ImageButton searchButton;
    private ImageButton editButton;
    private ImageButton saveButton;
    private ImageButton moreButton;

    /** The file content as it is on disk, read on the executor before it is pushed to the page. */
    private volatile String originalContent;
    private boolean pageReady;
    private boolean contentReady;
    private boolean editing;
    private boolean dirty;
    private boolean saving;
    private boolean wrap;
    private int fontSize;
    private int cursorLine = 1;
    private int cursorColumn = 1;
    private String language = "Text";
    private final String theme;

    private CodeViewerDialog(Activity activity, File file, boolean canWrite, boolean startEditing,
                            Listener listener) {
        this.activity = activity;
        this.file = file;
        this.canWrite = canWrite;
        this.editing = canWrite && startEditing;
        this.listener = listener;
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.wrap = preferences.getBoolean(PREF_WRAP, true);
        this.fontSize = preferences.getInt(PREF_FONT_SIZE, DEFAULT_FONT_SIZE);
        int nightMode = activity.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK;
        this.theme = nightMode == Configuration.UI_MODE_NIGHT_YES ? THEME_DARK : THEME_LIGHT;
    }

    /** Show the file, starting in edit mode when requested and allowed. Must run on the UI thread. */
    static void show(Activity activity, File file, boolean canWrite, boolean startEditing,
                     Listener listener) {
        if (activity == null || file == null || activity.isFinishing()
            || activity.isDestroyed()) {
            return;
        }
        if (activeDialog != null) {
            CodeViewerDialog previous = activeDialog.get();
            if (previous != null) previous.dismiss();
        }
        CodeViewerDialog viewer =
            new CodeViewerDialog(activity, file, canWrite, startEditing, listener);
        activeDialog = new WeakReference<>(viewer);
        viewer.show();
    }

    private void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void show() {
        View view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_code_viewer, null, false);
        titleView = view.findViewById(R.id.code_viewer_title);
        subtitleView = view.findViewById(R.id.code_viewer_subtitle);
        statusView = view.findViewById(R.id.code_viewer_status);
        errorView = view.findViewById(R.id.code_viewer_error);
        progressView = view.findViewById(R.id.code_viewer_progress);
        closeButton = view.findViewById(R.id.code_viewer_close);
        searchButton = view.findViewById(R.id.code_viewer_search);
        editButton = view.findViewById(R.id.code_viewer_edit);
        saveButton = view.findViewById(R.id.code_viewer_save);
        moreButton = view.findViewById(R.id.code_viewer_more);
        webView = view.findViewById(R.id.code_viewer_web);

        titleView.setText(file.getName());
        File parent = file.getParentFile();
        subtitleView.setText(parent == null ? file.getAbsolutePath() : parent.getAbsolutePath());
        updateToolbar();
        updateStatus();
        configureWebView();

        closeButton.setOnClickListener(v -> handleBack());
        searchButton.setOnClickListener(v -> evaluate("window.editorApi.openSearch()"));
        editButton.setOnClickListener(v -> beginEditing());
        saveButton.setOnClickListener(v -> requestSave());
        moreButton.setOnClickListener(v -> showMenu());

        dialog = new Dialog(activity, R.style.Theme_Opencode_FullScreenDialog);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            executor.shutdownNow();
            handler.removeCallbacksAndMessages(null);
            destroyWebView();
            if (activeDialog != null && activeDialog.get() == this) activeDialog = null;
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK || event.getAction() != KeyEvent.ACTION_UP) {
                return false;
            }
            return handleBack();
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        readContent(false);
    }

    /** Confirm unsaved changes, then close. */
    private boolean handleBack() {
        if (saving) return true;
        if (dirty) {
            confirmDiscard(null);
            return true;
        }
        dismiss();
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // Only the bundled editor page is ever needed: deny everything else, including
        // the file system (android_asset stays reachable by design) and the network.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setTextZoom(100);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
        });
        webView.addJavascriptInterface(new Bridge(), BRIDGE_NAME);
        webView.loadUrl(ASSET_URL);
        // If the page never initialises, for example because the device WebView is
        // missing or broken, stop waiting instead of spinning forever.
        handler.postDelayed(() -> {
            if (isAlive() && !pageReady) {
                showError(activity.getString(R.string.opencode_files_viewer_init_failed));
            }
        }, PAGE_TIMEOUT_MS);
    }

    private void destroyWebView() {
        if (webView == null) return;
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.setWebViewClient(null);
        webView.setWebChromeClient(null);
        if (webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }
        webView.destroy();
        webView = null;
    }

    /* ==========================================================================================
     * Content
     * ========================================================================================== */

    /** Read the file on the executor; {@code reload} replaces an already shown document. */
    private void readContent(final boolean reload) {
        setBusy(true, R.string.opencode_files_editor_loading);
        executor.execute(() -> {
            String text = null;
            String error = null;
            try {
                text = FileManagerUtils.readText(file);
            } catch (Throwable t) {
                error = activity.getString(R.string.opencode_files_editor_read_failed);
            }
            final String loadedText = text;
            final String loadedError = error;
            activity.runOnUiThread(() -> {
                if (!isAlive()) return;
                if (loadedError != null) {
                    showError(loadedError);
                    return;
                }
                originalContent = loadedText == null ? "" : loadedText;
                if (reload) {
                    dirty = false;
                    updateToolbar();
                    evaluate("window.editorApi.reload()");
                } else {
                    contentReady = true;
                    maybePushDocument();
                }
            });
        });
    }

    /** Push the document once both the page and the content are ready. */
    private void maybePushDocument() {
        if (!pageReady || !contentReady) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("fileName", file.getName());
            payload.put("readOnly", !editing);
            payload.put("wrap", wrap);
            payload.put("fontSize", fontSize);
            payload.put("theme", theme);
        } catch (JSONException e) {
            showError(activity.getString(R.string.opencode_files_editor_read_failed));
            return;
        }
        setBusy(false, 0);
        if (errorView != null) errorView.setVisibility(View.GONE);
        evaluate("window.editorApi.setDocument(" + payload.toString() + ")");
    }

    private void beginEditing() {
        if (!isAlive()) return;
        if (!canWrite) {
            toast(R.string.opencode_files_editor_read_only);
            return;
        }
        if (editing) return;
        editing = true;
        evaluate("window.editorApi.setEditable(true)");
        updateToolbar();
        updateStatus();
    }

    /** Ask the page for the current content; the answer arrives in {@link Bridge#saveContent}. */
    private void requestSave() {
        if (!isAlive() || saving) return;
        if (!editing || !canWrite) {
            toast(R.string.opencode_files_editor_read_only);
            return;
        }
        evaluate("window.editorApi.getContent()");
    }

    private void saveDocument(String content) {
        if (!isAlive() || !editing || !canWrite || saving || content == null) return;
        saving = true;
        setBusy(true, R.string.opencode_files_editor_saving);
        final String text = content;
        executor.execute(() -> {
            String error = null;
            try {
                // The file could have been replaced by a symlink while the viewer was open.
                if (FileManagerUtils.isSymbolicLink(file)) {
                    throw new IOException("File is now a symbolic link");
                }
                FileManagerUtils.writeText(file, text);
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String savedError = error;
            activity.runOnUiThread(() -> {
                saving = false;
                if (!isAlive()) return;
                setBusy(false, 0);
                if (savedError != null) {
                    toast(activity.getString(
                        R.string.opencode_files_editor_save_failed, savedError));
                    return;
                }
                originalContent = text;
                dirty = false;
                evaluate("window.editorApi.markClean()");
                updateToolbar();
                updateStatus();
                toast(R.string.opencode_files_editor_saved);
                if (listener != null) listener.onSaved(file);
            });
        });
    }

    /* ==========================================================================================
     * Toolbar and menu
     * ========================================================================================== */

    private void updateToolbar() {
        if (editButton != null) {
            editButton.setVisibility(editing ? View.GONE : View.VISIBLE);
        }
        if (saveButton != null) {
            saveButton.setVisibility(editing ? View.VISIBLE : View.GONE);
            saveButton.setEnabled(dirty && !saving);
            saveButton.setAlpha(dirty && !saving ? 1.0f : 0.4f);
        }
    }

    private void updateStatus() {
        if (statusView == null) return;
        String status = activity.getString(R.string.opencode_files_viewer_status,
            cursorLine, cursorColumn, language);
        if (!canWrite) {
            status += " · " + activity.getString(R.string.opencode_files_editor_read_only);
        }
        if (dirty) {
            status += " · " + activity.getString(R.string.opencode_files_editor_modified);
        }
        statusView.setText(status);
    }

    private void showMenu() {
        PopupMenu menu = new PopupMenu(activity, moreButton);
        android.view.Menu items = menu.getMenu();
        items.add(0, ID_UNDO, 0, R.string.opencode_files_viewer_undo);
        items.add(0, ID_REDO, 0, R.string.opencode_files_viewer_redo);
        items.add(0, ID_GOTO, 0, R.string.opencode_files_viewer_goto_line);
        MenuItem wrapItem = items.add(0, ID_WRAP, 0, R.string.opencode_files_viewer_wrap);
        wrapItem.setCheckable(true);
        wrapItem.setChecked(wrap);
        items.add(0, ID_FONT_SMALLER, 0, R.string.opencode_files_viewer_font_smaller);
        items.add(0, ID_FONT_LARGER, 0, R.string.opencode_files_viewer_font_larger);
        items.add(0, ID_RELOAD, 0, R.string.opencode_files_viewer_reload);
        items.add(0, ID_SHARE, 0, R.string.opencode_files_share);
        items.add(0, ID_OPEN_WITH, 0, R.string.opencode_files_open_with);
        items.add(0, ID_PROPERTIES, 0, R.string.opencode_files_details);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case ID_UNDO:
                    evaluate("window.editorApi.undo()");
                    return true;
                case ID_REDO:
                    evaluate("window.editorApi.redo()");
                    return true;
                case ID_GOTO:
                    promptGotoLine();
                    return true;
                case ID_WRAP:
                    toggleWrap();
                    return true;
                case ID_FONT_SMALLER:
                    changeFontSize(-1);
                    return true;
                case ID_FONT_LARGER:
                    changeFontSize(1);
                    return true;
                case ID_RELOAD:
                    confirmDiscard(this::readContentFromDisk);
                    return true;
                case ID_SHARE:
                    shareFile();
                    return true;
                case ID_OPEN_WITH:
                    openWithOtherApp();
                    return true;
                case ID_PROPERTIES:
                    FilePropertiesDialog.show(activity, file, canWrite, null);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void readContentFromDisk() {
        readContent(true);
    }

    private void toggleWrap() {
        wrap = !wrap;
        preferences.edit().putBoolean(PREF_WRAP, wrap).apply();
        evaluate("window.editorApi.setLineWrapping(" + wrap + ")");
    }

    private void changeFontSize(int direction) {
        int newSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, fontSize + direction));
        if (newSize == fontSize) return;
        fontSize = newSize;
        preferences.edit().putInt(PREF_FONT_SIZE, fontSize).apply();
        evaluate("window.editorApi.setFontSize(" + fontSize + ")");
    }

    private void promptGotoLine() {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.opencode_files_viewer_goto_hint);
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_viewer_goto_line)
            .setView(input)
            .setPositiveButton(R.string.opencode_picker_go, (d, which) -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                int line;
                try {
                    line = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    line = 0;
                }
                if (line < 1) {
                    toast(R.string.opencode_files_viewer_goto_invalid);
                    return;
                }
                evaluate("window.editorApi.gotoLine(" + line + ")");
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void confirmDiscard(Runnable onDiscard) {
        if (saving) return;
        if (!dirty) {
            if (onDiscard != null) onDiscard.run();
            else dismiss();
            return;
        }
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_editor_discard_title)
            .setMessage(R.string.opencode_files_editor_discard_message)
            .setPositiveButton(R.string.opencode_files_editor_discard, (d, which) -> {
                dirty = false;
                updateToolbar();
                if (onDiscard != null) onDiscard.run();
                else dismiss();
            })
            .setNegativeButton(R.string.opencode_files_editor_keep, null)
            .show();
    }

    private void shareFile() {
        try {
            Uri uri = uriFor(file);
            Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(FileManagerUtils.mimeType(file))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(
                Intent.createChooser(intent, activity.getString(R.string.opencode_files_share)));
        } catch (Throwable t) {
            toast(R.string.opencode_files_share_failed);
        }
    }

    private void openWithOtherApp() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(file), FileManagerUtils.mimeType(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent,
                activity.getString(R.string.opencode_files_open_with)));
        } catch (ActivityNotFoundException e) {
            toast(R.string.opencode_files_open_no_app);
        } catch (Throwable t) {
            toast(R.string.opencode_files_open_failed);
        }
    }

    private Uri uriFor(File target) {
        return FileProvider.getUriForFile(activity,
            BuildConfig.APPLICATION_ID + FILE_PROVIDER_SUFFIX, target);
    }

    /* ==========================================================================================
     * Bridge, helpers
     * ========================================================================================== */

    /**
     * The object that the editor page calls. The methods run on the WebView's JavaBridge
     * thread, so everything except {@link #fetchContent()} is posted back to the UI thread.
     */
    private final class Bridge {

        @JavascriptInterface
        public void onReady() {
            activity.runOnUiThread(() -> {
                if (!isAlive()) return;
                handler.removeCallbacksAndMessages(null);
                pageReady = true;
                maybePushDocument();
            });
        }

        @JavascriptInterface
        public String fetchContent() {
            String content = originalContent;
            return content == null ? "" : content;
        }

        @JavascriptInterface
        public void onDirtyChanged(final boolean value) {
            activity.runOnUiThread(() -> {
                if (!isAlive()) return;
                dirty = value;
                updateToolbar();
                updateStatus();
            });
        }

        @JavascriptInterface
        public void onCursor(final int line, final int column) {
            activity.runOnUiThread(() -> {
                if (!isAlive()) return;
                cursorLine = line;
                cursorColumn = column;
                updateStatus();
            });
        }

        @JavascriptInterface
        public void onMode(final String label) {
            activity.runOnUiThread(() -> {
                if (!isAlive()) return;
                language = label == null || label.trim().isEmpty() ? "Text" : label.trim();
                updateStatus();
            });
        }

        @JavascriptInterface
        public void saveContent(final String content) {
            activity.runOnUiThread(() -> saveDocument(content));
        }

        @JavascriptInterface
        public void requestSave() {
            activity.runOnUiThread(() -> {
                if (isAlive()) CodeViewerDialog.this.requestSave();
            });
        }

        @JavascriptInterface
        public void onError(final String message) {
            activity.runOnUiThread(() -> {
                if (!isAlive() || message == null) return;
                toast(activity.getString(
                    R.string.opencode_files_viewer_script_error, message));
            });
        }
    }

    private void evaluate(String script) {
        if (webView != null && pageReady) webView.evaluateJavascript(script, null);
    }

    private void setBusy(boolean busy, int messageResId) {
        if (progressView != null) progressView.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (saveButton != null) saveButton.setEnabled(!busy && dirty && editing);
        if (statusView != null) {
            if (busy && messageResId != 0) {
                statusView.setText(messageResId);
            } else {
                updateStatus();
            }
        }
    }

    private void showError(String message) {
        setBusy(false, 0);
        if (errorView != null) {
            errorView.setText(message);
            errorView.setVisibility(View.VISIBLE);
        }
    }

    private boolean isAlive() {
        return dialog != null && dialog.isShowing()
            && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void toast(int resId) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
