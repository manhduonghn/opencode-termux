package com.termux.app;

import android.app.Activity;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A small folder browser that lets the user pick a project directory without typing its path.
 *
 * <p>Only directories that this app can actually list are shown. On Android 11 and higher the
 * {@code Android/data} and {@code Android/obb} trees are always hidden because the system does not
 * allow access to them, even with the "All files access" permission.</p>
 */
final class DirectoryPickerDialog {

    /** Receives the directory the user selected. */
    interface Listener {
        void onDirectorySelected(File directory);
    }

    private final Activity activity;
    private final Listener listener;
    private final File startDirectory;

    private AlertDialog dialog;
    private EditText pathInput;
    private ImageButton upButton;
    private ListView listView;
    private TextView emptyView;
    private ChipGroup shortcuts;

    private final List<File> entries = new ArrayList<>();
    private DirectoryAdapter adapter;
    private File currentDirectory;

    private DirectoryPickerDialog(Activity activity, File startDirectory, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.startDirectory = startDirectory;
    }

    /** Show the picker. Must be called on the UI thread. */
    static void show(Activity activity, File startDirectory, Listener listener) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        new DirectoryPickerDialog(activity, startDirectory, listener).show();
    }

    private void show() {
        View view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_directory_picker, null, false);

        pathInput = view.findViewById(R.id.directory_picker_path);
        upButton = view.findViewById(R.id.directory_picker_up);
        listView = view.findViewById(R.id.directory_picker_list);
        emptyView = view.findViewById(R.id.directory_picker_empty);
        shortcuts = view.findViewById(R.id.directory_picker_shortcuts);

        adapter = new DirectoryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, item, position, id) -> {
            if (position >= 0 && position < entries.size()) navigateTo(entries.get(position));
        });

        upButton.setOnClickListener(v -> {
            if (currentDirectory == null) return;
            File parent = currentDirectory.getParentFile();
            if (parent != null) navigateTo(parent);
        });

        View goButton = view.findViewById(R.id.directory_picker_go);
        if (goButton != null) goButton.setOnClickListener(v -> goToTypedPath());

        pathInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                goToTypedPath();
                return true;
            }
            return false;
        });

        addShortcut(activity.getString(R.string.opencode_picker_internal), StorageAccess.primaryRoot());
        for (File root : StorageAccess.volumeRoots(activity)) {
            if (StorageAccess.isPrimaryRoot(root)) continue;
            addShortcut(activity.getString(R.string.opencode_picker_sd, root.getName()), root);
        }
        addShortcut(activity.getString(R.string.opencode_picker_termux),
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle(R.string.opencode_picker_title);
        builder.setView(view);
        builder.setPositiveButton(R.string.opencode_picker_choose, (d, which) -> {
            if (currentDirectory != null) listener.onDirectorySelected(currentDirectory);
        });
        builder.setNegativeButton(R.string.opencode_picker_cancel, null);

        dialog = builder.create();
        dialog.show();

        File start = startDirectory != null ? startDirectory : new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        navigateTo(start);
    }

    private void addShortcut(CharSequence label, File directory) {
        if (shortcuts == null || !isReadableDirectory(directory)) return;

        Chip chip = new Chip(activity);
        chip.setText(label);
        chip.setCheckable(false);
        chip.setClickable(true);
        chip.setOnClickListener(v -> navigateTo(directory));
        shortcuts.addView(chip);
    }

    private void goToTypedPath() {
        String raw = pathInput.getText() == null ? "" : pathInput.getText().toString();
        String expanded = expandPath(raw);
        if (expanded == null) {
            toast(R.string.opencode_picker_not_directory);
            return;
        }
        File directory = canonical(new File(expanded));
        if (!directory.isDirectory()) {
            toast(R.string.opencode_picker_not_directory);
            return;
        }
        navigateTo(directory);
    }

    private void navigateTo(File directory) {
        if (directory == null) return;

        File target = canonical(directory);
        if (!target.isDirectory()) {
            toast(R.string.opencode_picker_not_directory);
            return;
        }

        boolean readable = !isRestrictedDirectory(target) && isReadableDirectory(target);
        currentDirectory = target;

        pathInput.setText(target.getAbsolutePath());
        pathInput.setSelection(pathInput.length());
        File parent = target.getParentFile();
        upButton.setEnabled(parent != null && isReadableDirectory(parent));

        entries.clear();
        if (readable) {
            File[] children = listDirectories(target);
            if (children == null) {
                readable = false;
            } else {
                entries.addAll(Arrays.asList(children));
            }
        }
        adapter.notifyDataSetChanged();

        boolean showEmpty = entries.isEmpty();
        emptyView.setText(readable ? R.string.opencode_picker_empty : R.string.opencode_picker_unreadable);
        emptyView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        listView.setVisibility(showEmpty ? View.GONE : View.VISIBLE);

        if (dialog != null && dialog.isShowing()) {
            Button choose = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (choose != null) choose.setEnabled(readable);
        }
    }

    private void toast(int resId) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show();
    }

    /* ==========================================================================================
     * Filesystem helpers
     * ========================================================================================== */

    /** Expand a user typed path, resolving {@code ~} to the Termux home directory. */
    private static String expandPath(String raw) {
        if (raw == null) return null;
        String path = raw.trim();
        if (path.isEmpty()) return null;
        if (path.equals("~")) return TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (path.startsWith("~/")) return TermuxConstants.TERMUX_HOME_DIR_PATH + path.substring(1);
        if (!path.startsWith("/")) return TermuxConstants.TERMUX_HOME_DIR_PATH + "/" + path;
        return path;
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (Exception e) {
            return file;
        }
    }

    private static boolean isReadableDirectory(File directory) {
        if (directory == null) return false;
        try {
            return directory.isDirectory() && directory.list() != null;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static File[] listDirectories(File directory) {
        File[] files;
        try {
            files = directory.listFiles(file -> file.isDirectory()
                && !file.getName().startsWith(".")
                && !isRestrictedDirectory(file));
        } catch (SecurityException e) {
            return null;
        }
        if (files == null) return null;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return files;
    }

    /**
     * Android 11 and higher hides the app specific directories of every other app, even when the
     * "All files access" permission is granted.
     */
    private static boolean isRestrictedDirectory(File directory) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        String path = directory.getAbsolutePath();
        return path.matches(".*/Android/(data|obb)(/.*)?");
    }

    /* ==========================================================================================
     * List adapter
     * ========================================================================================== */

    private final class DirectoryAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public File getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(activity).inflate(R.layout.item_directory, parent, false);
            }
            TextView name = view.findViewById(R.id.directory_name);
            name.setText(entries.get(position).getName());
            return view;
        }
    }
}
