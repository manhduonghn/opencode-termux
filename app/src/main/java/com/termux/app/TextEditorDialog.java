package com.termux.app;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small UTF-8 text editor for files that live inside the directories the file manager is
 * allowed to modify. It never follows symbolic links: editing a link would replace the link
 * itself with a regular file.
 */
final class TextEditorDialog {

    interface Listener {
        void onSaved(File file);
    }

    private final Activity activity;
    private final File file;
    private final boolean canWrite;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AlertDialog dialog;
    private EditText input;
    private TextView status;
    private ProgressBar progress;
    private String original = "";
    private boolean loaded;
    private boolean modified;
    private boolean saving;
    private boolean ignoreChanges;

    private TextEditorDialog(Activity activity, File file, boolean canWrite, Listener listener) {
        this.activity = activity;
        this.file = file;
        this.canWrite = canWrite;
        this.listener = listener;
    }

    static void show(Activity activity, File file, boolean canWrite, Listener listener) {
        if (activity == null || file == null || activity.isFinishing()
            || activity.isDestroyed()) {
            return;
        }
        new TextEditorDialog(activity, file, canWrite, listener).show();
    }

    private void show() {
        View view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_text_editor, null, false);
        input = view.findViewById(R.id.text_editor_input);
        status = view.findViewById(R.id.text_editor_status);
        progress = view.findViewById(R.id.text_editor_progress);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (ignoreChanges || !loaded) return;
                modified = !String.valueOf(s).equals(original);
                updateStatus();
            }
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
            .setTitle(file.getName())
            .setView(view)
            .setPositiveButton(R.string.opencode_files_editor_save, null)
            .setNegativeButton(R.string.opencode_files_cancel, null);
        dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> confirmDiscard());
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                confirmDiscard();
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(d -> executor.shutdownNow());
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        load();
    }

    private void load() {
        setBusy(true, activity.getString(R.string.opencode_files_editor_loading));
        executor.execute(() -> {
            String text = null;
            String error = null;
            try {
                if (FileManagerUtils.isSymbolicLink(file)) {
                    error = activity.getString(R.string.opencode_files_editor_symlink);
                } else if (!FileManagerUtils.isTextFile(file)) {
                    error = activity.getString(R.string.opencode_files_editor_not_text);
                } else {
                    text = FileManagerUtils.readText(file);
                }
            } catch (Throwable t) {
                error = activity.getString(R.string.opencode_files_editor_read_failed);
            }
            final String loadedText = text;
            final String loadedError = error;
            activity.runOnUiThread(() -> {
                if (!isAlive()) return;
                setBusy(false, null);
                if (loadedError != null) {
                    loaded = true;
                    ignoreChanges = true;
                    input.setText("");
                    input.setEnabled(false);
                    status.setText(loadedError);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    return;
                }
                original = loadedText == null ? "" : loadedText;
                ignoreChanges = true;
                input.setText(original);
                input.setSelection(0);
                ignoreChanges = false;
                loaded = true;
                modified = false;
                input.setEnabled(true);
                input.requestFocus();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(canWrite);
                updateStatus();
            });
        });
    }

    private void updateStatus() {
        if (status == null) return;
        if (!loaded) return;
        String state;
        if (!canWrite) {
            state = activity.getString(R.string.opencode_files_editor_read_only);
        } else if (modified) {
            state = activity.getString(R.string.opencode_files_editor_modified);
        } else {
            state = activity.getString(R.string.opencode_files_editor_unchanged);
        }
        status.setText(activity.getString(R.string.opencode_files_editor_status,
            state, original.length(), input.getText() == null ? 0 : input.getText().length()));
    }

    private void save() {
        if (saving) return;
        if (!canWrite) {
            toast(R.string.opencode_files_editor_read_only);
            return;
        }
        if (!loaded) return;
        if (!modified) {
            dialog.dismiss();
            return;
        }
        final String text = input.getText() == null ? "" : input.getText().toString();
        saving = true;
        setBusy(true, activity.getString(R.string.opencode_files_editor_saving));
        executor.execute(() -> {
            String error = null;
            try {
                // The file could have been replaced by a symlink while the editor was open.
                if (FileManagerUtils.isSymbolicLink(file)) {
                    throw new java.io.IOException("File is now a symbolic link");
                }
                FileManagerUtils.writeText(file, text);
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String savedError = error;
            activity.runOnUiThread(() -> {
                saving = false;
                if (!isAlive()) return;
                setBusy(false, null);
                if (savedError != null) {
                    toast(activity.getString(R.string.opencode_files_editor_save_failed,
                        savedError == null ? "" : savedError));
                    return;
                }
                original = text;
                modified = false;
                updateStatus();
                toast(R.string.opencode_files_editor_saved);
                if (listener != null) listener.onSaved(file);
                dialog.dismiss();
            });
        });
    }

    private void confirmDiscard() {
        if (saving) return;
        if (!modified) {
            dialog.dismiss();
            return;
        }
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_editor_discard_title)
            .setMessage(R.string.opencode_files_editor_discard_message)
            .setPositiveButton(R.string.opencode_files_editor_discard,
                (d, which) -> dialog.dismiss())
            .setNegativeButton(R.string.opencode_files_editor_keep, null)
            .show();
    }

    private void setBusy(boolean busy, String message) {
        if (progress != null) {
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (input != null) input.setEnabled(!busy && loaded);
        if (status != null) {
            if (message != null) status.setText(message);
            else updateStatus();
        }
        if (dialog != null) {
            android.widget.Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (save != null) save.setEnabled(!busy && loaded && canWrite);
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
