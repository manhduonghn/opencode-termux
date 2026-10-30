package com.termux.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.StatFs;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.BuildConfig;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A file manager for the files that the user can reach from Termux: the Termux home directory,
 * the shared device storage and an SD card.
 *
 * <p>The manager can browse directories, open files with other apps, share one or more entries
 * through the Android share sheet (folders are zipped first), create, rename and delete entries
 * and inspect their size and timestamps. Files that Termux itself needs ({@code $PREFIX},
 * {@code ~/.opencode}, the volume roots) cannot be deleted, and symlinks are never followed while
 * deleting or zipping.</p>
 */
final class FileManagerDialog {

    /** Receives a directory that the user wants to open as an OpenCode project. */
    interface Listener {
        void onOpenProject(File directory);
    }

    /** Receives a validated name from a rename/create dialog. */
    private interface NameCallback {
        void onName(String name);
    }

    private static final String PREFS_NAME = "opencode_cfg";
    private static final String PREF_SHOW_HIDDEN = "file_manager_show_hidden";
    private static final String PREF_SORT = "file_manager_sort";

    private static final String FILE_PROVIDER_SUFFIX = ".fileprovider";
    private static final String SHARE_CACHE_DIR = "shared";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private static final int SORT_NAME = 0;
    private static final int SORT_SIZE = 1;
    private static final int SORT_MODIFIED = 2;

    private static final int DETAILS_CALCULATING = -2;
    private static final int DETAILS_UNKNOWN = -1;
    private static final int MEASURE_LIMIT = 200_000;

    /** The manager that is currently shown, so a second request replaces it instead of stacking. */
    private static WeakReference<FileManagerDialog> activeDialog;

    /** Home entries that the app or Termux manage themselves and that must never be deleted. */
    private static final Set<String> PROTECTED_HOME_NAMES = new HashSet<>(
        Arrays.asList(".opencode", ".termux", "storage", "tmp", "usr"));

    private static final String[] PROTECTED_PATHS = {
        "/",
        "/data",
        "/data/data",
        TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH,
        TermuxConstants.TERMUX_FILES_DIR_PATH,
        TermuxConstants.TERMUX_HOME_DIR_PATH,
        TermuxConstants.TERMUX_PREFIX_DIR_PATH
    };

    private final Activity activity;
    private final Listener listener;
    private final File home;
    private final File startedAt;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AlertDialog dialog;
    private ListView listView;
    private TextView pathView;
    private TextView summaryView;
    private TextView emptyView;
    private ProgressBar progressBar;
    private ImageButton upButton;
    private ImageButton homeButton;
    private ImageButton refreshButton;
    private ImageButton searchButton;
    private ImageButton moreButton;
    private View searchRow;
    private EditText searchInput;
    private View selectionBar;
    private TextView selectionCount;
    private ImageButton selectionShare;
    private ImageButton selectionDelete;
    private ImageButton selectionAll;
    private ImageButton selectionMore;
    private ImageButton selectionClose;

    private final List<Entry> allEntries = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> selection = new LinkedHashSet<>();
    private final List<File> clipboard = new ArrayList<>();
    private EntryAdapter adapter;
    private File currentDirectory;
    private String query = "";
    private boolean loading;
    private boolean busy;
    private boolean selectionMode;
    private boolean showHidden;
    private int sortMode;
    private int loadGeneration;
    private String temporaryStatus;
    private boolean clipboardMove;

    private FileManagerDialog(Activity activity, File startDirectory, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        this.startedAt = startDirectory != null ? startDirectory.getAbsoluteFile() : home;
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.showHidden = preferences.getBoolean(PREF_SHOW_HIDDEN, false);
        this.sortMode = preferences.getInt(PREF_SORT, SORT_NAME);
    }

    /** Show the manager at the Termux home directory. Must be called on the UI thread. */
    static void show(Activity activity, Listener listener) {
        show(activity, null, listener);
    }

    /** Show the manager at {@code startDirectory}, or at home when it is not a directory. */
    static void show(Activity activity, File startDirectory, Listener listener) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (activeDialog != null) {
            FileManagerDialog previous = activeDialog.get();
            if (previous != null) previous.dismiss();
        }
        FileManagerDialog manager = new FileManagerDialog(activity, startDirectory, listener);
        activeDialog = new WeakReference<>(manager);
        manager.show();
    }

    /** Close the dialog if it has not been dismissed already. */
    private void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void show() {
        View view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_file_manager, null, false);

        listView = view.findViewById(R.id.managed_file_list);
        pathView = view.findViewById(R.id.managed_file_path);
        summaryView = view.findViewById(R.id.managed_file_summary);
        emptyView = view.findViewById(R.id.managed_file_empty);
        progressBar = view.findViewById(R.id.managed_file_progress);
        upButton = view.findViewById(R.id.managed_file_up);
        homeButton = view.findViewById(R.id.managed_file_home);
        refreshButton = view.findViewById(R.id.managed_file_refresh);
        searchButton = view.findViewById(R.id.managed_file_search);
        moreButton = view.findViewById(R.id.managed_file_more);
        searchRow = view.findViewById(R.id.managed_file_search_row);
        searchInput = view.findViewById(R.id.managed_file_search_input);
        selectionBar = view.findViewById(R.id.managed_file_selection_bar);
        selectionCount = view.findViewById(R.id.managed_file_selection_count);
        selectionShare = view.findViewById(R.id.managed_file_selection_share);
        selectionDelete = view.findViewById(R.id.managed_file_selection_delete);
        selectionAll = view.findViewById(R.id.managed_file_selection_all);
        selectionMore = view.findViewById(R.id.managed_file_selection_more);
        selectionClose = view.findViewById(R.id.managed_file_selection_close);

        adapter = new EntryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, item, position, id) -> {
            if (busy || loading) return;
            if (position < 0 || position >= entries.size()) return;
            Entry entry = entries.get(position);
            if (selectionMode) {
                toggleSelection(entry);
            } else if (entry.file.isDirectory()) {
                navigateTo(entry.file);
            } else {
                openFile(entry);
            }
        });
        listView.setOnItemLongClickListener((parent, item, position, id) -> {
            if (busy || loading) return false;
            if (position < 0 || position >= entries.size()) return false;
            Entry entry = entries.get(position);
            if (selectionMode) {
                toggleSelection(entry);
            } else {
                selectionMode = true;
                selection.add(entry.file.getAbsolutePath());
                updateSelectionUi();
                adapter.notifyDataSetChanged();
            }
            return true;
        });

        upButton.setOnClickListener(v -> {
            File parent = currentDirectory == null ? null : currentDirectory.getParentFile();
            if (parent != null) navigateTo(parent);
        });
        homeButton.setOnClickListener(v -> navigateTo(home));
        refreshButton.setOnClickListener(v -> reload());
        pathView.setOnClickListener(v -> copyPath(currentDirectory));
        searchButton.setOnClickListener(v -> toggleSearch());
        moreButton.setOnClickListener(v -> showDirectoryActions());

        View searchClear = view.findViewById(R.id.managed_file_search_clear);
        if (searchClear != null) searchClear.setOnClickListener(v -> closeSearch());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString();
                rebuildVisibleEntries();
            }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });

        selectionShare.setOnClickListener(v -> shareSelectedEntries());
        selectionDelete.setOnClickListener(v -> confirmDeleteSelection());
        selectionAll.setOnClickListener(v -> selectAllVisible());
        selectionMore.setOnClickListener(v -> showSelectionActions());
        selectionClose.setOnClickListener(v -> exitSelection());

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle(R.string.opencode_files_dialog_title);
        builder.setView(view);
        builder.setNegativeButton(R.string.opencode_files_close, null);
        builder.setOnDismissListener(d -> {
            executor.shutdownNow();
            FileManagerDialog current = activeDialog == null ? null : activeDialog.get();
            if (current == this) activeDialog = null;
        });

        dialog = builder.create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK || event.getAction() != KeyEvent.ACTION_UP) {
                return false;
            }
            return handleBack();
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        }

        File start = startedAt;
        if (start == null || !start.isDirectory()) start = home;
        navigateTo(start);
    }

    /** Navigate up one directory instead of closing the dialog while it is possible. */
    private boolean handleBack() {
        if (selectionMode) {
            exitSelection();
            return true;
        }
        if (searchRow != null && searchRow.getVisibility() == View.VISIBLE) {
            closeSearch();
            return true;
        }
        if (currentDirectory == null) return false;
        File parent = currentDirectory.getParentFile();
        if (parent == null) return false;
        navigateTo(parent);
        return true;
    }

    /* ==========================================================================================
     * Directory loading
     * ========================================================================================== */

    private void navigateTo(File directory) {
        if (directory == null) return;
        File target = directory.getAbsoluteFile();
        if (!target.isDirectory()) {
            Toast.makeText(activity, R.string.opencode_files_not_directory, Toast.LENGTH_SHORT).show();
            return;
        }
        currentDirectory = target;
        exitSelection();
        closeSearch();
        updatePath();
        reload();
    }

    private void updatePath() {
        if (pathView == null || currentDirectory == null) return;
        String homePath = home.getAbsolutePath();
        String path = currentDirectory.getAbsolutePath();
        String display;
        if (path.equals(homePath)) {
            display = "~";
        } else if (path.startsWith(homePath + "/")) {
            display = "~" + path.substring(homePath.length());
        } else {
            display = path;
        }
        pathView.setText(display);
        pathView.setContentDescription(activity.getString(
            R.string.opencode_files_path_description, path));
        if (upButton != null) upButton.setEnabled(currentDirectory.getParentFile() != null);
        if (homeButton != null) homeButton.setEnabled(!path.equals(homePath));
    }

    private void reload() {
        if (currentDirectory == null) return;
        final File directory = currentDirectory;
        final int generation = ++loadGeneration;
        loading = true;
        setProgressVisible(true);
        temporaryStatus = null;
        updateSummary();
        updateEmptyView();
        setControlsEnabled(false);

        executor.execute(() -> {
            List<Entry> scanned;
            try {
                scanned = scan(directory);
            } catch (Throwable t) {
                scanned = null;
            }
            final List<Entry> loaded = scanned;
            final long free = availableBytes(directory);
            activity.runOnUiThread(() -> {
                if (!isAlive() || generation != loadGeneration) return;
                loading = false;
                setProgressVisible(false);
                setControlsEnabled(true);

                allEntries.clear();
                if (loaded == null) {
                    entries.clear();
                    adapter.notifyDataSetChanged();
                    if (summaryView != null) summaryView.setText(R.string.opencode_files_unreadable);
                    if (emptyView != null) {
                        emptyView.setText(R.string.opencode_files_unreadable);
                        emptyView.setVisibility(View.VISIBLE);
                    }
                    return;
                }
                allEntries.addAll(loaded);
                rebuildVisibleEntries();
                updateSummary(free);
            });
        });
    }

    /**
     * @return the entries of {@code directory} sorted by the current settings, or {@code null}
     *     when the directory cannot be listed.
     */
    private List<Entry> scan(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return null;

        final boolean hidden = showHidden;
        final int sort = sortMode;
        List<Entry> result = new ArrayList<>();
        for (File child : children) {
            String name = child.getName();
            if (!hidden && name.startsWith(".")) continue;

            Entry entry = new Entry(child);
            entry.symlink = isSymbolicLink(child);
            entry.directory = child.isDirectory();
            entry.modified = child.lastModified();
            entry.size = entry.directory ? DETAILS_UNKNOWN : Math.max(0, child.length());
            entry.git = entry.directory && new File(child, ".git").exists();
            result.add(entry);
        }

        Collections.sort(result, (left, right) -> {
            if (left.directory != right.directory) return left.directory ? -1 : 1;
            int comparison;
            switch (sort) {
                case SORT_SIZE:
                    comparison = Long.compare(right.size, left.size);
                    break;
                case SORT_MODIFIED:
                    comparison = Long.compare(right.modified, left.modified);
                    break;
                default:
                    comparison = 0;
                    break;
            }
            if (comparison == 0) {
                comparison = left.file.getName().compareToIgnoreCase(right.file.getName());
            }
            return comparison;
        });
        return result;
    }

    private void rebuildVisibleEntries() {
        if (adapter == null) return;
        entries.clear();
        String filter = query.trim().toLowerCase(Locale.ROOT);
        for (Entry entry : allEntries) {
            if (filter.isEmpty()
                || entry.file.getName().toLowerCase(Locale.ROOT).contains(filter)) {
                entries.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyView();
        updateSummary();
        updateSelectionUi();
    }

    private void updateEmptyView() {
        if (emptyView == null) return;
        if (loading) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        boolean empty = entries.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyView.setText(allEntries.isEmpty() && query.trim().isEmpty()
                ? R.string.opencode_files_empty
                : R.string.opencode_files_no_match);
        }
    }

    private void updateSummary() {
        if (currentDirectory == null) return;
        updateSummary(availableBytes(currentDirectory));
    }

    private void updateSummary(long freeBytes) {
        if (summaryView == null) return;
        if (temporaryStatus != null) {
            summaryView.setText(temporaryStatus);
        } else if (selectionMode) {
            summaryView.setText(activity.getString(
                R.string.opencode_files_summary_selected, selection.size()));
        } else if (loading) {
            summaryView.setText(R.string.opencode_files_scanning);
        } else if (freeBytes >= 0) {
            summaryView.setText(activity.getString(R.string.opencode_files_summary,
                entries.size(), OpenCodeInstallDialog.formatSize(freeBytes)));
        } else {
            summaryView.setText(activity.getString(
                R.string.opencode_files_summary_no_free, entries.size()));
        }
    }

    /** Available space of the partition that holds {@code directory}. */
    private static long availableBytes(File directory) {
        try {
            return new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void setProgressVisible(boolean visible) {
        if (progressBar != null) {
            progressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setControlsEnabled(boolean enabled) {
        if (refreshButton != null) refreshButton.setEnabled(enabled && !busy);
        if (searchButton != null) searchButton.setEnabled(enabled && !busy);
        if (moreButton != null) moreButton.setEnabled(enabled && !busy);
        if (upButton != null) {
            upButton.setEnabled(enabled && currentDirectory != null
                && currentDirectory.getParentFile() != null);
        }
        if (homeButton != null) {
            homeButton.setEnabled(enabled && currentDirectory != null
                && !samePath(currentDirectory, home));
        }
    }

    /* ==========================================================================================
     * Selection
     * ========================================================================================== */

    private void toggleSelection(Entry entry) {
        String path = entry.file.getAbsolutePath();
        if (!selection.remove(path)) selection.add(path);
        if (selection.isEmpty()) {
            selectionMode = false;
        } else {
            selectionMode = true;
        }
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void selectAllVisible() {
        selectionMode = true;
        for (Entry entry : entries) selection.add(entry.file.getAbsolutePath());
        if (selection.isEmpty()) selectionMode = false;
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void exitSelection() {
        selection.clear();
        selectionMode = false;
        updateSelectionUi();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateSelectionUi() {
        boolean active = selectionMode && !selection.isEmpty();
        if (selectionBar != null) {
            selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (active && selectionCount != null) {
            selectionCount.setText(activity.getString(
                R.string.opencode_files_summary_selected, selection.size()));
        }
        boolean available = !active && !busy && !loading;
        if (moreButton != null) moreButton.setEnabled(available);
        if (searchButton != null) searchButton.setEnabled(available);
        if (refreshButton != null) refreshButton.setEnabled(available);
        if (selectionMore != null) selectionMore.setEnabled(!busy);
        if (upButton != null) {
            upButton.setEnabled(available && currentDirectory != null
                && currentDirectory.getParentFile() != null);
        }
        if (homeButton != null) {
            homeButton.setEnabled(available && currentDirectory != null
                && !samePath(currentDirectory, home));
        }
        updateSummary();
    }

    private List<Entry> selectedEntries() {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : allEntries) {
            if (selection.contains(entry.file.getAbsolutePath())) result.add(entry);
        }
        return result;
    }

    /* ==========================================================================================
     * Entry actions
     * ========================================================================================== */

    private void showEntryActions(final Entry entry) {
        final List<Runnable> actions = new ArrayList<>();
        if (entry.file.isDirectory()) {
            actions.add(() -> navigateTo(entry.file));
            actions.add(() -> openProject(entry));
        } else {
            actions.add(() -> openFile(entry));
        }
        actions.add(() -> shareEntries(Collections.singletonList(entry)));
        actions.add(() -> copyPath(entry.file));
        actions.add(() -> copyToClipboard(Collections.singletonList(entry), false));
        actions.add(() -> copyToClipboard(Collections.singletonList(entry), true));
        actions.add(() -> promptRename(entry));
        actions.add(() -> showDetails(entry));
        actions.add(() -> confirmDelete(entry));

        List<String> labels = new ArrayList<>();
        if (entry.file.isDirectory()) {
            labels.add(activity.getString(R.string.opencode_files_open_folder));
            labels.add(activity.getString(R.string.opencode_files_open_project));
        } else {
            labels.add(activity.getString(R.string.opencode_files_open_file));
        }
        labels.add(activity.getString(R.string.opencode_files_share));
        labels.add(activity.getString(R.string.opencode_files_copy_path));
        labels.add(activity.getString(R.string.opencode_files_copy));
        labels.add(activity.getString(R.string.opencode_files_move));
        labels.add(activity.getString(R.string.opencode_files_rename));
        labels.add(activity.getString(R.string.opencode_files_details));
        labels.add(activity.getString(R.string.opencode_files_delete));

        new MaterialAlertDialogBuilder(activity)
            .setTitle(entry.file.getName())
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which >= 0 && which < actions.size()) actions.get(which).run();
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void openProject(Entry entry) {
        if (!entry.file.isDirectory()) return;
        if (dialog != null) dialog.dismiss();
        listener.onOpenProject(entry.file);
    }

    private void openFile(Entry entry) {
        try {
            Uri uri = uriFor(entry.file);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType(entry.file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.opencode_files_open_no_app,
                Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(activity, R.string.opencode_files_open_failed,
                Toast.LENGTH_LONG).show();
        }
    }

    private void copyPath(File file) {
        if (file == null) return;
        try {
            ClipboardManager manager =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText(
                    activity.getString(R.string.opencode_files_dialog_title),
                    file.getAbsolutePath()));
                Toast.makeText(activity, R.string.opencode_files_copied,
                    Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
            // The clipboard is only a convenience.
        }
    }

    private void showSelectionActions() {
        final List<Entry> selected = selectedEntries();
        if (selected.isEmpty()) return;

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(activity.getString(R.string.opencode_files_copy));
        actions.add(() -> copyToClipboard(selected, false));
        labels.add(activity.getString(R.string.opencode_files_move));
        actions.add(() -> copyToClipboard(selected, true));
        labels.add(activity.getString(R.string.opencode_files_select_all));
        actions.add(this::selectAllVisible);
        labels.add(activity.getString(R.string.opencode_files_deselect));
        actions.add(this::exitSelection);

        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_selection_actions)
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which >= 0 && which < actions.size()) actions.get(which).run();
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    /** Remember entries for a later paste in another directory. */
    private void copyToClipboard(List<Entry> selected, boolean move) {
        if (selected.isEmpty()) return;
        clipboard.clear();
        for (Entry entry : selected) clipboard.add(entry.file);
        clipboardMove = move;
        exitSelection();
        toast(activity.getString(move
            ? R.string.opencode_files_moved_to_clipboard
            : R.string.opencode_files_copied_to_clipboard, clipboard.size()));
    }

    /** Copy or move the entries that were remembered with {@link #copyToClipboard}. */
    private void pasteClipboard() {
        if (clipboard.isEmpty()) return;
        if (busy || loading) {
            toast(R.string.opencode_files_busy);
            return;
        }

        final File destination = currentDirectory;
        final List<File> sources = new ArrayList<>(clipboard);
        final boolean move = clipboardMove;
        busy = true;
        temporaryStatus = activity.getString(R.string.opencode_files_pasting, sources.size());
        setProgressVisible(true);
        setControlsEnabled(false);
        updateSummary();

        executor.execute(() -> {
            int pasted = 0;
            int failed = 0;
            List<File> failedSources = new ArrayList<>();
            for (File source : sources) {
                boolean success = false;
                try {
                    if (source == null) throw new IOException("Missing source");
                    if (move && samePath(source.getParentFile(), destination)) {
                        success = true;
                    } else {
                        if (isSameOrInside(destination, source)) {
                            throw new IOException("Cannot paste a directory into itself");
                        }
                        File target = uniqueTarget(destination, source.getName());
                        if (move) {
                            if (!isSafeToModify(source)) {
                                throw new IOException("Protected source");
                            }
                            if (source.renameTo(target)) {
                                success = true;
                            } else {
                                copyRecursively(source, target);
                                success = deleteRecursively(source);
                            }
                        } else {
                            copyRecursively(source, target);
                            success = true;
                        }
                    }
                } catch (Throwable ignored) {
                    success = false;
                }
                if (success) pasted++;
                else {
                    failed++;
                    failedSources.add(source);
                }
            }

            final int pastedCount = pasted;
            final int failedCount = failed;
            final List<File> stillQueued = failedSources;
            activity.runOnUiThread(() -> {
                busy = false;
                temporaryStatus = null;
                if (!isAlive()) return;
                if (move) {
                    clipboard.clear();
                    clipboard.addAll(stillQueued);
                    clipboardMove = !clipboard.isEmpty();
                }
                setProgressVisible(false);
                setControlsEnabled(true);
                if (pastedCount == 0) {
                    toast(R.string.opencode_files_paste_failed);
                } else if (failedCount == 0) {
                    toast(activity.getString(R.string.opencode_files_pasted, pastedCount));
                } else {
                    toast(activity.getString(R.string.opencode_files_pasted_partial,
                        pastedCount, failedCount));
                }
                reload();
            });
        });
    }

    private static void copyRecursively(File source, File target) throws IOException {
        if (isSymbolicLink(source)) {
            throw new IOException("Symlinks are not copied");
        }
        if (source.isDirectory()) {
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("Cannot create " + target.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (isSymbolicLink(child)) continue;
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
            return;
        }
        if (!source.isFile()) throw new IOException("Not a file");

        try (BufferedInputStream input =
                 new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream output =
                 new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static File uniqueTarget(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists() && !isSymbolicLink(file)) return file;

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            File candidate = new File(directory, base + " (" + i + ")" + extension);
            if (!candidate.exists() && !isSymbolicLink(candidate)) return candidate;
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    private static boolean isSameOrInside(File candidate, File ancestor) {
        if (candidate == null || ancestor == null) return false;
        String candidatePath = canonical(candidate).getAbsolutePath();
        String ancestorPath = canonical(ancestor).getAbsolutePath();
        return candidatePath.equals(ancestorPath)
            || candidatePath.startsWith(ancestorPath + File.separator);
    }

    private void showDetails(final Entry entry) {
        long initialSize = entry.file.isDirectory() ? DETAILS_CALCULATING : entry.size;
        final AlertDialog details = new MaterialAlertDialogBuilder(activity)
            .setTitle(entry.file.getName())
            .setMessage(buildDetails(entry, initialSize))
            .setPositiveButton(R.string.opencode_files_close, null)
            .show();
        if (!entry.file.isDirectory()) return;

        executor.execute(() -> {
            final long size = measureSize(entry.file);
            activity.runOnUiThread(() -> {
                if (!isAlive() || !details.isShowing()) return;
                details.setMessage(buildDetails(entry, size));
            });
        });
    }

    private String buildDetails(Entry entry, long size) {
        String kind = describeKind(entry);
        String sizeText;
        if (size == DETAILS_CALCULATING) {
            sizeText = activity.getString(R.string.opencode_files_details_calculating);
        } else if (size < 0) {
            sizeText = activity.getString(R.string.opencode_files_size_unknown);
        } else {
            sizeText = OpenCodeInstallDialog.formatSize(size);
        }
        String date = entry.modified > 0
            ? new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(new Date(entry.modified))
            : activity.getString(R.string.opencode_files_time_unknown);
        String type = entry.file.isDirectory() ? activity.getString(R.string.opencode_files_kind_dir)
            : mimeType(entry.file);
        return activity.getString(R.string.opencode_files_details_message,
            entry.file.getAbsolutePath(), kind, type, sizeText, date);
    }

    /** Recursively sum the size of a tree, without following symlinks. */
    private static long measureSize(File root) {
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(root);
        long total = 0;
        int visited = 0;
        while (!stack.isEmpty()) {
            File file = stack.pop();
            if (isSymbolicLink(file)) continue;
            if (file.isFile()) {
                total += Math.max(0, file.length());
            } else if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) stack.push(child);
                }
            }
            if (++visited > MEASURE_LIMIT) return DETAILS_UNKNOWN;
        }
        return total;
    }

    /* ==========================================================================================
     * Create, rename and delete
     * ========================================================================================== */

    private void showDirectoryActions() {
        final List<Runnable> actions = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        if (!clipboard.isEmpty()) {
            labels.add(activity.getString(clipboardMove
                ? R.string.opencode_files_paste_move : R.string.opencode_files_paste,
                clipboard.size()));
            actions.add(this::pasteClipboard);
        }

        if (!samePath(currentDirectory, home)) {
            labels.add(activity.getString(R.string.opencode_files_go_home));
            actions.add(() -> navigateTo(home));
        }
        File primary = StorageAccess.primaryRoot();
        if (primary != null && !samePath(currentDirectory, primary)) {
            labels.add(activity.getString(R.string.opencode_files_go_internal));
            actions.add(() -> navigateTo(primary));
        }
        for (File root : StorageAccess.volumeRoots(activity)) {
            if (StorageAccess.isPrimaryRoot(root) || samePath(currentDirectory, root)) continue;
            labels.add(activity.getString(R.string.opencode_files_go_sd, root.getName()));
            actions.add(() -> navigateTo(root));
        }

        labels.add(activity.getString(R.string.opencode_files_new_folder));
        actions.add(this::createFolder);
        labels.add(activity.getString(R.string.opencode_files_new_file));
        actions.add(this::createFile);
        labels.add(activity.getString(searchRow != null && searchRow.getVisibility() == View.VISIBLE
            ? R.string.opencode_files_search_close : R.string.opencode_files_search));
        actions.add(this::toggleSearch);
        labels.add(activity.getString(showHidden
            ? R.string.opencode_files_hide_hidden : R.string.opencode_files_show_hidden));
        actions.add(this::toggleHidden);
        labels.add(activity.getString(R.string.opencode_files_sort));
        actions.add(this::showSortDialog);
        if (!entries.isEmpty()) {
            labels.add(activity.getString(R.string.opencode_files_select_all));
            actions.add(this::selectAllVisible);
        }
        labels.add(activity.getString(R.string.opencode_files_details));
        actions.add(() -> showDirectoryDetails());

        new MaterialAlertDialogBuilder(activity)
            .setTitle(String.valueOf(currentDirectory == null ? "" : pathView.getText()))
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which >= 0 && which < actions.size()) actions.get(which).run();
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void createFolder() {
        promptName(R.string.opencode_files_new_folder, null, R.string.opencode_files_name_hint,
            name -> {
                File target = new File(currentDirectory, name);
                if (!isSafeToModify(target)) {
                    toast(R.string.opencode_files_modify_unsafe);
                    return;
                }
                if (target.exists() || isSymbolicLink(target)) {
                    toast(R.string.opencode_files_name_exists);
                    return;
                }
                if (target.mkdirs()) {
                    toast(activity.getString(R.string.opencode_files_created, name));
                    reload();
                } else {
                    toast(R.string.opencode_files_create_failed);
                }
            });
    }

    private void createFile() {
        promptName(R.string.opencode_files_new_file, null, R.string.opencode_files_name_hint,
            name -> {
                File target = new File(currentDirectory, name);
                if (!isSafeToModify(target)) {
                    toast(R.string.opencode_files_modify_unsafe);
                    return;
                }
                if (target.exists() || isSymbolicLink(target)) {
                    toast(R.string.opencode_files_name_exists);
                    return;
                }
                try {
                    if (target.createNewFile()) {
                        toast(activity.getString(R.string.opencode_files_created, name));
                        reload();
                    } else {
                        toast(R.string.opencode_files_create_failed);
                    }
                } catch (Throwable t) {
                    toast(R.string.opencode_files_create_failed);
                }
            });
    }

    private void promptRename(final Entry entry) {
        if (!isSafeToModify(entry.file)) {
            toast(R.string.opencode_files_modify_unsafe);
            return;
        }
        promptName(R.string.opencode_files_rename, entry.file.getName(),
            R.string.opencode_files_name_hint, name -> {
                if (name.equals(entry.file.getName())) return;
                File target = new File(entry.file.getParentFile(), name);
                if (!isSafeToModify(target)) {
                    toast(R.string.opencode_files_modify_unsafe);
                    return;
                }
                if (target.exists() || isSymbolicLink(target)) {
                    toast(R.string.opencode_files_name_exists);
                    return;
                }
                if (entry.file.renameTo(target)) {
                    toast(activity.getString(R.string.opencode_files_renamed, name));
                    reload();
                } else {
                    toast(R.string.opencode_files_rename_failed);
                }
            });
    }

    private void promptName(int titleRes, String initial, int hintRes, NameCallback callback) {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(hintRes);
        if (initial != null) {
            input.setText(initial);
            input.setSelection(initial.length());
        }

        FrameLayout container = new FrameLayout(activity);
        int padding = dp(20);
        container.setPadding(padding, dp(6), padding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog nameDialog = new MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .create();
        nameDialog.setOnShowListener(d -> nameDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String name = input.getText() == null ? "" : input.getText().toString().trim();
                if (!isValidName(name)) {
                    toast(R.string.opencode_files_name_invalid);
                    return;
                }
                nameDialog.dismiss();
                callback.onName(name);
            }));
        nameDialog.show();
        input.requestFocus();
        showKeyboard(input);
    }

    private static boolean isValidName(String name) {
        return !name.isEmpty()
            && !name.equals(".")
            && !name.equals("..")
            && name.indexOf('/') < 0
            && name.indexOf('\0') < 0
            && name.length() <= 255;
    }

    private void confirmDelete(final Entry entry) {
        String size = entry.file.isDirectory()
            ? activity.getString(R.string.opencode_files_size_unknown)
            : OpenCodeInstallDialog.formatSize(entry.size);
        String message = entry.file.isDirectory()
            ? activity.getString(R.string.opencode_files_delete_message_dir, size)
            : activity.getString(R.string.opencode_files_delete_message_file, size);

        new MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.opencode_files_delete_title,
                entry.file.getName()))
            .setMessage(message)
            .setPositiveButton(R.string.opencode_files_delete, (dialog, which) ->
                deleteEntries(Collections.singletonList(entry)))
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void confirmDeleteSelection() {
        final List<Entry> selected = selectedEntries();
        if (selected.isEmpty()) return;
        if (selected.size() == 1) {
            confirmDelete(selected.get(0));
            return;
        }
        new MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.opencode_files_delete_title,
                activity.getString(R.string.opencode_files_selected_count, selected.size())))
            .setMessage(activity.getString(R.string.opencode_files_delete_message_many,
                selected.size()))
            .setPositiveButton(R.string.opencode_files_delete, (dialog, which) ->
                deleteEntries(selected))
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void deleteEntries(final List<Entry> entriesToDelete) {
        if (busy || loading) {
            toast(R.string.opencode_files_busy);
            return;
        }
        busy = true;
        exitSelection();
        temporaryStatus = activity.getString(R.string.opencode_files_deleting_count,
            entriesToDelete.size());
        setProgressVisible(true);
        setControlsEnabled(false);
        updateSummary();

        executor.execute(() -> {
            int deleted = 0;
            int failed = 0;
            int unsafe = 0;
            for (Entry entry : entriesToDelete) {
                boolean ok = false;
                boolean safe = false;
                try {
                    safe = isSafeToModify(entry.file);
                    if (safe) ok = deleteRecursively(entry.file);
                } catch (Throwable ignored) {
                    ok = false;
                }
                if (ok) deleted++;
                else {
                    failed++;
                    if (!safe) unsafe++;
                }
            }
            final int deletedCount = deleted;
            final int failedCount = failed;
            final int unsafeCount = unsafe;
            activity.runOnUiThread(() -> {
                busy = false;
                temporaryStatus = null;
                if (!isAlive()) return;
                setProgressVisible(false);
                setControlsEnabled(true);
                if (failedCount == 0) {
                    toast(activity.getString(R.string.opencode_files_deleted_count, deletedCount));
                } else if (deletedCount == 0 && unsafeCount == failedCount) {
                    toast(R.string.opencode_files_delete_unsafe);
                } else if (deletedCount == 0) {
                    toast(R.string.opencode_files_delete_failed);
                } else {
                    toast(activity.getString(R.string.opencode_files_deleted_partial,
                        deletedCount, failedCount));
                }
                reload();
            });
        });
    }

    /**
     * Only allow modifications inside the Termux home directory, the Termux files directory
     * (excluding the protected prefix and app data) or a mounted storage volume. Critical paths
     * and ancestors of the directory that is currently open are never modified. Symlinks are
     * modified as links; their target is never touched.
     */
    private boolean isSafeToModify(File file) {
        if (file == null) return false;
        String name = file.getName();
        if (name.isEmpty()) return false;
        File parent = file.getParentFile();
        if (parent == null) return false;

        File canonicalParent = canonical(parent);
        // For a symlink, use the location of the link itself instead of its target. Deleting or
        // renaming the link must not be blocked just because the target lives outside the
        // allowed directories.
        String path = isSymbolicLink(file)
            ? new File(canonicalParent, name).getAbsolutePath()
            : canonical(file).getAbsolutePath();

        for (String protectedPath : PROTECTED_PATHS) {
            if (path.equals(protectedPath)) return false;
        }

        File canonicalHome = canonical(home);
        String homePath = canonicalHome.getAbsolutePath();
        if (canonicalParent.getAbsolutePath().equals(homePath)
            && PROTECTED_HOME_NAMES.contains(name)) {
            return false;
        }
        if (path.equals(homePath) || path.startsWith(homePath + "/.opencode")) {
            return false;
        }

        String prefixPath = canonical(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH))
            .getAbsolutePath();
        if (path.equals(prefixPath) || path.startsWith(prefixPath + "/")) {
            return false;
        }

        boolean insideAllowedRoot = path.equals(homePath) || path.startsWith(homePath + "/");
        if (!insideAllowedRoot) {
            String filesPath = canonical(new File(TermuxConstants.TERMUX_FILES_DIR_PATH))
                .getAbsolutePath();
            insideAllowedRoot = path.equals(filesPath) || path.startsWith(filesPath + "/");
        }
        if (!insideAllowedRoot) {
            for (File root : StorageAccess.volumeRoots(activity)) {
                if (root == null) continue;
                String rootPath = canonical(root).getAbsolutePath();
                if (path.equals(rootPath)) return false;
                if (path.startsWith(rootPath + "/")) {
                    insideAllowedRoot = true;
                    break;
                }
            }
        }
        if (!insideAllowedRoot) return false;

        File current = currentDirectory;
        while (current != null) {
            if (path.equals(canonical(current).getAbsolutePath())) return false;
            current = current.getParentFile();
        }
        return true;
    }

    private static boolean deleteRecursively(File file) {
        if (isSymbolicLink(file)) return file.delete();
        if (file.isDirectory()) {
            // Git object files can be read-only, and a read-only directory would block the
            // deletion of its children. Make the directory writable before descending into it.
            file.setWritable(true);
            file.setReadable(true);
            file.setExecutable(true);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        } else {
            file.setWritable(true);
        }
        return file.delete();
    }

    /* ==========================================================================================
     * Sharing and opening files
     * ========================================================================================== */

    private void shareSelectedEntries() {
        shareEntries(selectedEntries());
    }

    /**
     * Prepare the selected entries in the background. Folders are zipped into the app cache
     * because the share sheet can only send files.
     */
    private void shareEntries(final List<Entry> selected) {
        if (selected.isEmpty()) return;
        if (busy || loading) {
            toast(R.string.opencode_files_busy);
            return;
        }
        busy = true;
        temporaryStatus = activity.getString(R.string.opencode_files_share_preparing);
        setProgressVisible(true);
        setControlsEnabled(false);
        updateSummary();

        final List<Entry> copy = new ArrayList<>(selected);
        executor.execute(() -> {
            final List<Uri> uris = new ArrayList<>();
            String mime = null;
            try {
                cleanShareCache();
            } catch (Throwable ignored) {
                // The cache is cleaned best effort.
            }
            for (Entry entry : copy) {
                try {
                    File file = entry.file;
                    if (file.isDirectory()) {
                        if (isSymbolicLink(file)) {
                            throw new IOException("Symlink directories are not zipped");
                        }
                        File archive = zipDirectory(file);
                        uris.add(uriFor(archive));
                        mime = mergeMime(mime, "application/zip");
                    } else {
                        uris.add(uriFor(file));
                        mime = mergeMime(mime, mimeType(file));
                    }
                } catch (Throwable ignored) {
                    // Skip this entry, the other selected entries can still be shared.
                }
            }
            final String finalMime = mime == null ? "*/*" : mime;
            activity.runOnUiThread(() -> {
                busy = false;
                temporaryStatus = null;
                if (!isAlive()) return;
                setProgressVisible(false);
                setControlsEnabled(true);
                updateSummary();
                if (uris.isEmpty()) {
                    toast(R.string.opencode_files_share_failed);
                    return;
                }
                launchShare(uris, finalMime);
            });
        });
    }

    private static String mergeMime(String current, String next) {
        if (current == null) return next;
        return current.equals(next) ? current : "*/*";
    }

    private void launchShare(List<Uri> uris, String mime) {
        try {
            Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                    new ArrayList<>(uris));
            }
            intent.setType(mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            ClipData clip = null;
            for (Uri uri : uris) {
                if (clip == null) {
                    clip = ClipData.newUri(activity.getContentResolver(), "files", uri);
                } else {
                    clip.addItem(new ClipData.Item(uri));
                }
            }
            if (clip != null) intent.setClipData(clip);

            activity.startActivity(Intent.createChooser(intent,
                activity.getString(R.string.opencode_files_share)));
        } catch (ActivityNotFoundException e) {
            toast(R.string.opencode_files_share_no_app);
        } catch (Throwable t) {
            toast(R.string.opencode_files_share_failed);
        }
    }

    private Uri uriFor(File file) {
        String authority = BuildConfig.APPLICATION_ID + FILE_PROVIDER_SUFFIX;
        return FileProvider.getUriForFile(activity, authority, file);
    }

    private static String mimeType(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    /** Zip a folder into {@code cache/shared} so that it can be sent through the share sheet. */
    private File zipDirectory(File directory) throws IOException {
        File cache = new File(activity.getCacheDir(), SHARE_CACHE_DIR);
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IOException("Cannot create the share cache");
        }
        File archive = new File(cache, safeFileName(directory.getName()) + "-"
            + System.currentTimeMillis() + ".zip");
        boolean success = false;
        try (ZipOutputStream output = new ZipOutputStream(
            new BufferedOutputStream(new FileOutputStream(archive)))) {
            addToZip(directory, safeFileName(directory.getName()), output);
            success = true;
        } finally {
            if (!success) archive.delete();
        }
        return archive;
    }

    private static void addToZip(File file, String entryName, ZipOutputStream output)
        throws IOException {
        if (isSymbolicLink(file)) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) {
                addToZip(child, entryName + "/" + child.getName(), output);
            }
            return;
        }
        if (!file.isFile()) return;

        ZipEntry entry = new ZipEntry(entryName.replace('\\', '/'));
        entry.setTime(file.lastModified());
        output.putNextEntry(entry);
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        output.closeEntry();
    }

    private static String safeFileName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return safe.isEmpty() ? "files" : safe;
    }

    private void cleanShareCache() {
        File cache = new File(activity.getCacheDir(), SHARE_CACHE_DIR);
        File[] files = cache.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - DAY_MS;
        for (File file : files) {
            if (file.lastModified() < cutoff) file.delete();
        }
    }

    /* ==========================================================================================
     * Sorting, hidden files and search
     * ========================================================================================== */

    private void toggleHidden() {
        showHidden = !showHidden;
        preferences.edit().putBoolean(PREF_SHOW_HIDDEN, showHidden).apply();
        reload();
    }

    private void showSortDialog() {
        List<String> labels = Arrays.asList(
            activity.getString(R.string.opencode_files_sort_name),
            activity.getString(R.string.opencode_files_sort_size),
            activity.getString(R.string.opencode_files_sort_modified));
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_sort)
            .setSingleChoiceItems(labels.toArray(new CharSequence[0]), sortMode,
                (dialog, which) -> {
                    sortMode = which;
                    preferences.edit().putInt(PREF_SORT, sortMode).apply();
                    dialog.dismiss();
                    reload();
                })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void toggleSearch() {
        if (searchRow == null) return;
        if (searchRow.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        searchRow.setVisibility(View.VISIBLE);
        searchInput.requestFocus();
        showKeyboard(searchInput);
        if (searchButton != null) {
            searchButton.setImageResource(R.drawable.ic_close);
        }
    }

    private void closeSearch() {
        if (searchRow == null || searchRow.getVisibility() != View.VISIBLE) return;
        searchRow.setVisibility(View.GONE);
        if (searchInput != null) searchInput.setText("");
        query = "";
        if (searchButton != null) searchButton.setImageResource(R.drawable.ic_search);
        hideKeyboard();
        rebuildVisibleEntries();
    }

    private void showKeyboard(View view) {
        try {
            InputMethodManager manager = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
            // The keyboard is optional.
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null && searchInput != null) {
                manager.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
            // The keyboard is optional.
        }
    }

    private void showDirectoryDetails() {
        if (currentDirectory == null) return;
        Entry entry = new Entry(currentDirectory);
        entry.directory = true;
        entry.modified = currentDirectory.lastModified();
        showDetails(entry);
    }

    /* ==========================================================================================
     * Small helpers
     * ========================================================================================== */

    private void toast(int resId) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean isAlive() {
        return dialog != null && dialog.isShowing()
            && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static File canonical(File file) {
        if (file == null) return new File("/");
        try {
            return file.getCanonicalFile();
        } catch (Exception e) {
            return file.getAbsoluteFile();
        }
    }

    private static boolean samePath(File left, File right) {
        if (left == null || right == null) return false;
        return canonical(left).getAbsolutePath().equals(canonical(right).getAbsolutePath());
    }

    private static boolean isSymbolicLink(File file) {
        if (file == null) return false;
        try {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            return OsConstants.S_ISLNK(stat.st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    private String describeKind(Entry entry) {
        if (entry.symlink) return activity.getString(R.string.opencode_files_kind_link);
        if (entry.git) return activity.getString(R.string.opencode_files_kind_repo);
        if (entry.file.isDirectory()) return activity.getString(R.string.opencode_files_kind_dir);
        return activity.getString(R.string.opencode_files_kind_file);
    }

    private String describe(Entry entry) {
        String kind = describeKind(entry);
        String size;
        if (entry.file.isDirectory()) {
            size = activity.getString(R.string.opencode_files_size_folder);
        } else {
            size = OpenCodeInstallDialog.formatSize(entry.size);
        }
        return activity.getString(R.string.opencode_files_meta, kind, size,
            relativeTime(entry.modified));
    }

    private String relativeTime(long timestamp) {
        if (timestamp <= 0) return activity.getString(R.string.opencode_files_time_unknown);
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 60_000L) return activity.getString(R.string.opencode_files_time_now);
        if (diff < 60L * 60_000L) {
            return activity.getString(R.string.opencode_files_time_minutes, diff / 60_000L);
        }
        if (diff < 24L * 60L * 60_000L) {
            return activity.getString(R.string.opencode_files_time_hours, diff / (60L * 60_000L));
        }
        if (diff < 7L * 24L * 60L * 60_000L) {
            return activity.getString(R.string.opencode_files_time_days,
                diff / (24L * 60L * 60_000L));
        }
        return new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            .format(new Date(timestamp));
    }

    private static final class Entry {
        final File file;
        long size = DETAILS_UNKNOWN;
        long modified;
        boolean directory;
        boolean symlink;
        boolean git;

        Entry(File file) {
            this.file = file;
        }
    }

    private final class EntryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Entry getItem(int position) {
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
                view = LayoutInflater.from(activity)
                    .inflate(R.layout.item_managed_file, parent, false);
            }
            final Entry entry = getItem(position);
            TextView name = view.findViewById(R.id.managed_file_name);
            TextView meta = view.findViewById(R.id.managed_file_meta);
            ImageView icon = view.findViewById(R.id.managed_file_icon);
            CheckBox check = view.findViewById(R.id.managed_file_check);
            ImageButton more = view.findViewById(R.id.managed_file_more);

            if (name != null) name.setText(entry.file.getName());
            if (meta != null) meta.setText(describe(entry));
            if (icon != null) {
                int iconRes;
                if (entry.symlink) {
                    iconRes = R.drawable.ic_file;
                } else if (entry.git) {
                    iconRes = R.drawable.ic_github_branch;
                } else if (entry.file.isDirectory()) {
                    iconRes = R.drawable.ic_folder;
                } else {
                    iconRes = R.drawable.ic_file;
                }
                icon.setImageResource(iconRes);
            }
            if (check != null) {
                check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
                check.setChecked(selection.contains(entry.file.getAbsolutePath()));
            }
            if (more != null) {
                more.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
                more.setContentDescription(activity.getString(
                    R.string.opencode_files_more_for, entry.file.getName()));
                more.setOnClickListener(v -> showEntryActions(entry));
            }
            return view;
        }
    }
}
