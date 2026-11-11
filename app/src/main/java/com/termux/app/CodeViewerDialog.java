package com.termux.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small in-app text viewer and editor for the file manager. Text and code files are
 * shown in a plain monospace {@link EditText}, with no WebView involved: the content is
 * read and written directly, so it works even when the device has no usable WebView and
 * the editing is immediate. Writable files open ready to type; files that must not be
 * changed (symbolic links, protected paths) open read only.
 *
 * <p>While the file is open the toolbar offers search, undo/redo, go to line, word wrap,
 * font size, reload, share, open with another app and properties. Saving uses the atomic
 * {@link FileManagerUtils#writeText}, which preserves the POSIX mode, and a file that
 * turned into a symbolic link while the editor was open is never written.</p>
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
    private static final String FILE_PROVIDER_SUFFIX = ".fileprovider";

    private static final int DEFAULT_FONT_SIZE = 14;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 28;

    /** Undo keeps at most this many snapshots, and is disabled for very large files. */
    private static final int UNDO_LIMIT = 30;
    private static final int UNDO_MAX_TEXT = 200_000;
    private static final long UNDO_DEBOUNCE_MS = 600L;

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
    private final boolean startEditing;
    private final Listener listener;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Dialog dialog;
    private EditText editor;
    private TextView titleView;
    private TextView subtitleView;
    private TextView statusView;
    private TextView errorView;
    private ProgressBar progressView;
    private ImageButton closeButton;
    private ImageButton searchButton;
    private ImageButton saveButton;
    private ImageButton moreButton;

    /** The file content as it is on disk. */
    private String original = "";
    private boolean loaded;
    private boolean modified;
    private boolean saving;
    /** Set while the text is changed programmatically, so the watcher does not react. */
    private boolean suppressWatcher;
    private boolean wrap;
    private int fontSize;
    private String lastQuery = "";

    private final ArrayDeque<Snapshot> undoStack = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redoStack = new ArrayDeque<>();
    private long lastUndoAt;

    private CodeViewerDialog(Activity activity, File file, boolean canWrite, boolean startEditing,
                             Listener listener) {
        this.activity = activity;
        this.file = file;
        this.canWrite = canWrite;
        this.startEditing = canWrite && startEditing;
        this.listener = listener;
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.wrap = preferences.getBoolean(PREF_WRAP, true);
        this.fontSize = preferences.getInt(PREF_FONT_SIZE, DEFAULT_FONT_SIZE);
    }

    /** Show the file, ready to edit when it is writable. Must run on the UI thread. */
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
        saveButton = view.findViewById(R.id.code_viewer_save);
        moreButton = view.findViewById(R.id.code_viewer_more);
        editor = view.findViewById(R.id.code_viewer_input);

        titleView.setText(file.getName());
        File parent = file.getParentFile();
        subtitleView.setText(parent == null ? file.getAbsolutePath() : parent.getAbsolutePath());
        configureEditor();
        updateToolbar();
        updateStatus();

        closeButton.setOnClickListener(v -> handleBack());
        searchButton.setOnClickListener(v -> promptFind());
        saveButton.setOnClickListener(v -> save());
        moreButton.setOnClickListener(v -> showMenu());

        dialog = new Dialog(activity, R.style.Theme_Opencode_FullScreenDialog);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            executor.shutdownNow();
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

        load();
    }

    /** Confirm unsaved changes, then close. */
    private boolean handleBack() {
        if (saving) return true;
        if (modified) {
            confirmDiscard(null);
            return true;
        }
        dismiss();
        return true;
    }

    private void configureEditor() {
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        applyWrap();
        if (canWrite) {
            editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            // Read only, but the text stays selectable so it can be copied.
            editor.setInputType(InputType.TYPE_NULL);
            editor.setTextIsSelectable(true);
        }
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (suppressWatcher || !loaded) return;
                recordUndo(s == null ? "" : s.toString());
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressWatcher || !loaded) return;
                String text = s == null ? "" : s.toString();
                modified = text.length() != original.length() || !text.equals(original);
                updateToolbar();
                updateStatus();
            }
        });
    }

    /* ==========================================================================================
     * Content
     * ========================================================================================== */

    private void load() {
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
                setBusy(false, 0);
                if (loadedError != null) {
                    showError(loadedError);
                    return;
                }
                original = loadedText == null ? "" : loadedText;
                suppressWatcher = true;
                editor.setText(original);
                editor.setSelection(0);
                suppressWatcher = false;
                undoStack.clear();
                redoStack.clear();
                loaded = true;
                modified = false;
                if (errorView != null) errorView.setVisibility(View.GONE);
                if (editor != null) editor.setVisibility(View.VISIBLE);
                updateToolbar();
                updateStatus();
                if (startEditing) {
                    editor.requestFocus();
                    showKeyboard();
                }
            });
        });
    }

    private void save() {
        if (!isAlive() || saving || !loaded) return;
        if (!canWrite) {
            toast(R.string.opencode_files_editor_read_only);
            return;
        }
        if (!modified) return;
        final String text = currentText();
        saving = true;
        setBusy(true, R.string.opencode_files_editor_saving);
        executor.execute(() -> {
            String error = null;
            try {
                // The file could have been replaced by a symlink while the editor was open.
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
                original = text;
                modified = false;
                updateToolbar();
                updateStatus();
                toast(R.string.opencode_files_editor_saved);
                if (listener != null) listener.onSaved(file);
            });
        });
    }

    /* ==========================================================================================
     * Undo and redo
     * ========================================================================================== */

    private void recordUndo(String oldText) {
        long now = SystemClock.uptimeMillis();
        // Coalesce the edits of one typing burst into a single undo step.
        if (!undoStack.isEmpty() && now - lastUndoAt < UNDO_DEBOUNCE_MS) {
            lastUndoAt = now;
            return;
        }
        lastUndoAt = now;
        if (oldText.length() > UNDO_MAX_TEXT) return;
        undoStack.push(new Snapshot(oldText, editor.getSelectionStart(), editor.getSelectionEnd()));
        while (undoStack.size() > UNDO_LIMIT) undoStack.removeLast();
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(new Snapshot(currentText(), editor.getSelectionStart(),
            editor.getSelectionEnd()));
        restore(undoStack.pop());
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(new Snapshot(currentText(), editor.getSelectionStart(),
            editor.getSelectionEnd()));
        restore(redoStack.pop());
    }

    private void restore(Snapshot snapshot) {
        suppressWatcher = true;
        editor.setText(snapshot.text);
        int length = editor.getText() == null ? 0 : editor.getText().length();
        editor.setSelection(Math.min(snapshot.start, length), Math.min(snapshot.end, length));
        suppressWatcher = false;
        modified = !snapshot.text.equals(original);
        lastUndoAt = 0;
        updateToolbar();
        updateStatus();
    }

    /* ==========================================================================================
     * Search, go to line, wrap and font size
     * ========================================================================================== */

    private void promptFind() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(R.string.opencode_files_viewer_find_hint);
        input.setText(lastQuery);
        input.setSelection(input.getText().length());
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_viewer_search)
            .setView(input)
            .setPositiveButton(R.string.opencode_picker_go, (d, which) -> {
                String query = input.getText() == null ? "" : input.getText().toString();
                findNext(query);
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void findNext(String query) {
        if (editor == null || query == null || query.isEmpty()) return;
        lastQuery = query;
        String text = currentText();
        int from = editor.getSelectionEnd();
        if (from < 0 || from > text.length()) from = 0;
        int index = text.indexOf(query, from);
        if (index < 0 && from > 0) index = text.indexOf(query);
        if (index < 0) {
            toast(R.string.opencode_files_viewer_not_found);
            return;
        }
        editor.requestFocus();
        editor.setSelection(index, index + query.length());
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
                gotoLine(line);
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void gotoLine(int line) {
        if (editor == null) return;
        String text = currentText();
        int offset = 0;
        for (int i = 1; i < line && offset < text.length(); i++) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) {
                offset = text.length();
                break;
            }
            offset = newline + 1;
        }
        editor.requestFocus();
        editor.setSelection(Math.min(offset, text.length()));
    }

    private void toggleWrap() {
        wrap = !wrap;
        preferences.edit().putBoolean(PREF_WRAP, wrap).apply();
        applyWrap();
    }

    private void applyWrap() {
        if (editor == null) return;
        // Without wrapping the view keeps the lines on one line and scrolls horizontally.
        editor.setHorizontallyScrolling(!wrap);
        editor.requestLayout();
    }

    private void changeFontSize(int direction) {
        int newSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, fontSize + direction));
        if (newSize == fontSize) return;
        fontSize = newSize;
        preferences.edit().putInt(PREF_FONT_SIZE, fontSize).apply();
        if (editor != null) editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
    }

    /* ==========================================================================================
     * Toolbar and menu
     * ========================================================================================== */

    private void updateToolbar() {
        if (saveButton != null) {
            saveButton.setVisibility(canWrite ? View.VISIBLE : View.GONE);
            boolean enabled = canWrite && loaded && modified && !saving;
            saveButton.setEnabled(enabled);
            saveButton.setAlpha(enabled ? 1.0f : 0.4f);
        }
    }

    private void updateStatus() {
        if (statusView == null || editor == null || !loaded) return;
        int line = 1;
        int column = 1;
        int selection = editor.getSelectionStart();
        Layout layout = editor.getLayout();
        if (layout != null && selection >= 0) {
            int lineIndex = layout.getLineForOffset(selection);
            line = lineIndex + 1;
            column = selection - layout.getLineStart(lineIndex) + 1;
        }
        String status = activity.getString(R.string.opencode_files_viewer_status,
            line, column, languageLabel());
        if (!canWrite) {
            status += " · " + activity.getString(R.string.opencode_files_editor_read_only);
        }
        if (modified) {
            status += " · " + activity.getString(R.string.opencode_files_editor_modified);
        }
        statusView.setText(status);
    }

    private String languageLabel() {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "Text";
        return name.substring(dot + 1).toUpperCase(Locale.ROOT);
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
                    undo();
                    return true;
                case ID_REDO:
                    redo();
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
                    confirmDiscard(this::load);
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

    private void confirmDiscard(Runnable onDiscard) {
        if (saving) return;
        if (!modified) {
            if (onDiscard != null) onDiscard.run();
            else dismiss();
            return;
        }
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_editor_discard_title)
            .setMessage(R.string.opencode_files_editor_discard_message)
            .setPositiveButton(R.string.opencode_files_editor_discard, (d, which) -> {
                modified = false;
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
     * Helpers
     * ========================================================================================== */

    private String currentText() {
        return editor == null || editor.getText() == null ? "" : editor.getText().toString();
    }

    private void showKeyboard() {
        if (editor == null) return;
        InputMethodManager manager =
            (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
    }

    private void setBusy(boolean busy, int messageResId) {
        if (progressView != null) progressView.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (saveButton != null) {
            boolean enabled = canWrite && loaded && modified && !busy;
            saveButton.setEnabled(enabled);
            saveButton.setAlpha(enabled ? 1.0f : 0.4f);
        }
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
        if (editor != null) editor.setVisibility(View.GONE);
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

    /** A single undo/redo state: the text and the selection that produced it. */
    private static final class Snapshot {
        final String text;
        final int start;
        final int end;

        Snapshot(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }
}
