package com.termux.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.BuildConfig;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;

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
 * <p>The manager can browse directories, open text and code files in a built-in editor
 * modelled after Acode, open other files with other apps, share one or more entries
 * through the Android share sheet (folders are zipped first), create, rename and delete
 * entries and inspect their size and timestamps. The same screen grants shared-storage
 * access and opens the GitHub login and clone screen. Files that Termux itself needs
 * ({@code $PREFIX}, {@code ~/.opencode}, the volume roots) cannot be deleted, and symlinks
 * are never followed while deleting or zipping.</p>
 */
final class FileManagerDialog {

    /** Receives the actions that the file manager cannot perform on its own. */
    interface Listener {
        /** Receives a directory that the user wants to open as an OpenCode project. */
        void onOpenProject(File directory);

        /** Ask the activity to grant or refresh shared-storage access. */
        default void onManageStorage() {
        }
    }

    /** Receives a validated name from a rename/create dialog. */
    private interface NameCallback {
        void onName(String name);
    }

    private static final String PREFS_NAME = "opencode_cfg";
    private static final String PREF_SHOW_HIDDEN = "file_manager_show_hidden";
    private static final String PREF_SORT = "file_manager_sort";
    private static final String PREF_SORT_ASCENDING = "file_manager_sort_ascending";
    private static final String PREF_DIRS_FIRST = "file_manager_dirs_first";
    private static final String PREF_RECURSIVE_SEARCH = "file_manager_recursive_search";
    private static final String PREF_BOOKMARKS = "file_manager_bookmarks";
    private static final String PREF_GRID_MODE = "file_manager_grid_mode";

    private static final String FILE_PROVIDER_SUFFIX = ".fileprovider";
    private static final String SHARE_CACHE_DIR = "shared";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private static final int SORT_NAME = 0;
    private static final int SORT_SIZE = 1;
    private static final int SORT_MODIFIED = 2;
    private static final int SORT_TYPE = 3;

    private static final int DETAILS_UNKNOWN = -1;
    private static final int SEARCH_LIMIT = 5_000;
    private static final int SEARCH_MAX_DEPTH = 24;

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

    private Dialog dialog;
    private ListView listView;
    private GridView gridView;
    private TextView pathView;
    private TextView titleView;
    private TextView summaryView;
    private TextView emptyView;
    private ProgressBar progressBar;
    private ImageButton upButton;
    private ImageButton homeButton;
    private ImageButton refreshButton;
    private ImageButton locationsButton;
    private ImageButton moreButton;
    private ImageButton closeButton;
    private ImageButton viewModeButton;
    private View drawer;
    private View drawerScrim;
    private LinearLayout drawerList;
    private View fab;
    private HorizontalScrollView breadcrumbScroll;
    private LinearLayout breadcrumb;
    private View selectionBar;
    private TextView selectionCount;
    private ImageButton selectionShare;
    private ImageButton selectionDelete;
    private ImageButton selectionAll;
    private ImageButton selectionMore;
    private ImageButton selectionClose;

    private final List<Entry> allEntries = new ArrayList<>();
    private final List<Entry> searchResults = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> selection = new LinkedHashSet<>();
    private final List<File> clipboard = new ArrayList<>();
    private final List<String> bookmarks = new ArrayList<>();
    private EntryAdapter adapter;
    private volatile File currentDirectory;
    private String query = "";
    private String searchQuery = "";
    private boolean loading;
    private boolean searching;
    private boolean busy;
    private boolean selectionMode;
    private boolean showHidden;
    private boolean recursiveSearch;
    private boolean gridMode;
    private boolean sortAscending;
    private boolean directoriesFirst;
    private int sortMode;
    private int loadGeneration;
    private volatile int searchGeneration;
    private String temporaryStatus;
    private boolean clipboardMove;

    private FileManagerDialog(Activity activity, File startDirectory, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        this.startedAt = startDirectory != null ? startDirectory.getAbsoluteFile() : home;
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.showHidden = preferences.getBoolean(PREF_SHOW_HIDDEN, false);
        this.recursiveSearch = preferences.getBoolean(PREF_RECURSIVE_SEARCH, true);
        this.gridMode = preferences.getBoolean(PREF_GRID_MODE, false);
        this.sortMode = preferences.getInt(PREF_SORT, SORT_NAME);
        this.sortAscending = preferences.getBoolean(PREF_SORT_ASCENDING, true);
        this.directoriesFirst = preferences.getBoolean(PREF_DIRS_FIRST, true);
        loadBookmarks();
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

    /** Reload the active manager, for example after storage access was granted. */
    static void refreshActive() {
        FileManagerDialog current = activeDialog == null ? null : activeDialog.get();
        if (current != null && current.isAlive()) current.reload();
    }

    private void show() {
        View view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_file_manager, null, false);

        listView = view.findViewById(R.id.managed_file_list);
        gridView = view.findViewById(R.id.managed_file_grid);
        pathView = view.findViewById(R.id.managed_file_path);
        titleView = view.findViewById(R.id.managed_file_title);
        summaryView = view.findViewById(R.id.managed_file_summary);
        emptyView = view.findViewById(R.id.managed_file_empty);
        progressBar = view.findViewById(R.id.managed_file_progress);
        upButton = view.findViewById(R.id.managed_file_up);
        homeButton = view.findViewById(R.id.managed_file_home);
        refreshButton = view.findViewById(R.id.managed_file_refresh);
        locationsButton = view.findViewById(R.id.managed_file_locations);
        moreButton = view.findViewById(R.id.managed_file_more);
        closeButton = view.findViewById(R.id.managed_file_close);
        viewModeButton = view.findViewById(R.id.managed_file_view_mode);
        drawer = view.findViewById(R.id.managed_file_drawer);
        drawerScrim = view.findViewById(R.id.managed_file_drawer_scrim);
        drawerList = view.findViewById(R.id.managed_file_drawer_list);
        fab = view.findViewById(R.id.managed_file_fab);
        breadcrumbScroll = view.findViewById(R.id.managed_file_breadcrumb_scroll);
        breadcrumb = view.findViewById(R.id.managed_file_breadcrumb);
        selectionBar = view.findViewById(R.id.managed_file_selection_bar);
        selectionCount = view.findViewById(R.id.managed_file_selection_count);
        selectionShare = view.findViewById(R.id.managed_file_selection_share);
        selectionDelete = view.findViewById(R.id.managed_file_selection_delete);
        selectionAll = view.findViewById(R.id.managed_file_selection_all);
        selectionMore = view.findViewById(R.id.managed_file_selection_more);
        selectionClose = view.findViewById(R.id.managed_file_selection_close);

        adapter = new EntryAdapter();
        listView.setAdapter(adapter);
        gridView.setAdapter(adapter);
        updateViewMode();

        AdapterView.OnItemClickListener itemClick = (parent, item, position, id) -> {
            if (busy || loading) return;
            if (position < 0 || position >= entries.size()) return;
            Entry entry = entries.get(position);
            if (selectionMode) {
                toggleSelection(entry);
            } else if (entry.file.isDirectory()) {
                navigateTo(entry.file);
            } else {
                openEntry(entry);
            }
        };
        AdapterView.OnItemLongClickListener itemLongClick = (parent, item, position, id) -> {
            if (busy || loading) return false;
            if (position < 0 || position >= entries.size()) return false;
            Entry entry = entries.get(position);
            if (selectionMode) {
                toggleSelection(entry);
            } else {
                // Material Files behaviour: a long press starts multi-select instead of
                // forcing the user through an action menu before anything can happen.
                beginSelection(entry);
            }
            return true;
        };
        listView.setOnItemClickListener(itemClick);
        listView.setOnItemLongClickListener(itemLongClick);
        gridView.setOnItemClickListener(itemClick);
        gridView.setOnItemLongClickListener(itemLongClick);

        upButton.setOnClickListener(v -> {
            File parent = currentDirectory == null ? null : currentDirectory.getParentFile();
            if (parent != null) navigateTo(parent);
        });
        homeButton.setOnClickListener(v -> navigateTo(home));
        refreshButton.setOnClickListener(v -> reload());
        pathView.setOnClickListener(v -> showPathActions());
        pathView.setOnLongClickListener(v -> {
            copyPath(currentDirectory);
            return true;
        });
        if (locationsButton != null) locationsButton.setOnClickListener(v -> openDrawer());
        moreButton.setOnClickListener(v -> showDirectoryActions());
        if (closeButton != null) closeButton.setOnClickListener(v -> dismiss());
        if (viewModeButton != null) viewModeButton.setOnClickListener(v -> toggleViewMode());
        if (drawerScrim != null) drawerScrim.setOnClickListener(v -> closeDrawer());
        if (fab != null) fab.setOnClickListener(v -> showCreateMenu());

        selectionShare.setOnClickListener(v -> shareSelectedEntries());
        selectionDelete.setOnClickListener(v -> confirmDeleteSelection());
        selectionAll.setOnClickListener(v -> selectAllVisible());
        selectionMore.setOnClickListener(v -> showSelectionActions());
        selectionClose.setOnClickListener(v -> exitSelection());

        dialog = new Dialog(activity, R.style.Theme_Opencode_FullScreenDialog);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            loadGeneration++;
            searchGeneration++;
            executor.shutdownNow();
            FileManagerDialog current = activeDialog == null ? null : activeDialog.get();
            if (current == this) activeDialog = null;
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
        }

        File start = startedAt;
        if (start == null || !start.isDirectory()) start = home;
        navigateTo(start);
    }

    /** Navigate up one directory instead of closing the dialog while it is possible. */
    private boolean handleBack() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return true;
        }
        if (selectionMode) {
            exitSelection();
            return true;
        }
        if (currentDirectory == null) return false;
        File parent = currentDirectory.getParentFile();
        if (parent == null) return false;
        navigateTo(parent);
        return true;
    }

    /* ==========================================================================================
     * View mode, navigation drawer and create menu
     * ========================================================================================== */

    private void toggleViewMode() {
        gridMode = !gridMode;
        preferences.edit().putBoolean(PREF_GRID_MODE, gridMode).apply();
        updateViewMode();
    }

    private void updateViewMode() {
        if (listView != null) {
            listView.setVisibility(gridMode ? View.GONE : View.VISIBLE);
        }
        if (gridView != null) {
            gridView.setVisibility(gridMode ? View.VISIBLE : View.GONE);
        }
        if (viewModeButton != null) {
            viewModeButton.setImageResource(gridMode ? R.drawable.ic_list : R.drawable.ic_grid);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void openDrawer() {
        if (drawer == null) return;
        buildDrawer();
        drawer.animate().cancel();
        if (drawerScrim != null) drawerScrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-dp(320));
        drawer.animate().translationX(0f).setDuration(180).start();
    }

    private void closeDrawer() {
        if (drawer == null || drawer.getVisibility() != View.VISIBLE) return;
        drawer.animate().cancel();
        if (drawerScrim != null) drawerScrim.setVisibility(View.GONE);
        drawer.animate().translationX(-Math.max(drawer.getWidth(), dp(300))).setDuration(160)
            .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    /** Rebuild the drawer so it always mirrors the volumes that are currently reachable. */
    private void buildDrawer() {
        if (drawerList == null) return;
        drawerList.removeAllViews();

        addDrawerItem(activity.getString(R.string.opencode_files_location_home),
            home.getAbsolutePath(), R.drawable.ic_home, () -> navigateTo(home));
        File filesDir = TermuxConstants.TERMUX_FILES_DIR;
        if (filesDir != null && filesDir.isDirectory()) {
            addDrawerItem(activity.getString(R.string.opencode_files_location_files),
                filesDir.getAbsolutePath(), R.drawable.ic_folder, () -> navigateTo(filesDir));
        }
        File primary = StorageAccess.primaryRoot();
        if (primary != null) {
            addDrawerItem(activity.getString(R.string.opencode_files_location_internal),
                primary.getAbsolutePath(), R.drawable.ic_storage, () -> navigateTo(primary));
        }
        for (File root : StorageAccess.volumeRoots(activity)) {
            if (StorageAccess.isPrimaryRoot(root)) continue;
            addDrawerItem(activity.getString(R.string.opencode_files_location_sd, root.getName()),
                root.getAbsolutePath(), R.drawable.ic_storage, () -> navigateTo(root));
        }
        File dataDir = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR;
        if (dataDir.isDirectory()) {
            addDrawerItem(activity.getString(R.string.opencode_files_location_app_data),
                dataDir.getAbsolutePath(), R.drawable.ic_folder, () -> navigateTo(dataDir));
        }
        addDrawerItem(activity.getString(R.string.opencode_files_location_root), "/",
            R.drawable.ic_storage, () -> navigateTo(new File("/")));

        if (!bookmarks.isEmpty()) {
            addDrawerItem(activity.getString(R.string.opencode_files_bookmarks),
                activity.getString(R.string.opencode_files_bookmarks_count, bookmarks.size()),
                R.drawable.ic_star, this::showBookmarks);
        }

        if (!StorageAccess.hasStoragePermission(activity)) {
            addDrawerItem(activity.getString(R.string.opencode_files_storage_grant), null,
                R.drawable.ic_storage, () -> listener.onManageStorage());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !StorageAccess.hasAllFilesAccess(activity)) {
            addDrawerItem(activity.getString(R.string.opencode_files_storage_grant_sd), null,
                R.drawable.ic_storage, () -> listener.onManageStorage());
        }
    }

    private void addDrawerItem(String title, String subtitle, int iconRes, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));

        android.util.TypedValue ripple = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }

        ImageView icon = new ImageView(activity);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        icon.setLayoutParams(iconParams);
        icon.setImageResource(iconRes);
        icon.setColorFilter(activity.getResources().getColor(R.color.github_muted));
        row.addView(icon);

        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        columnParams.setMarginStart(dp(18));
        textColumn.setLayoutParams(columnParams);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(15f);
        textColumn.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = new TextView(activity);
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(11f);
            subtitleView.setTextColor(activity.getResources().getColor(R.color.github_muted));
            subtitleView.setMaxLines(1);
            subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            textColumn.addView(subtitleView);
        }

        row.addView(textColumn);
        row.setOnClickListener(v -> {
            closeDrawer();
            action.run();
        });
        drawerList.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showCreateMenu() {
        if (fab == null) return;
        PopupMenu menu = new PopupMenu(activity, fab);
        menu.getMenu().add(0, 1, 0, R.string.opencode_files_new_folder);
        menu.getMenu().add(0, 2, 1, R.string.opencode_files_new_file);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    createFolder();
                    return true;
                case 2:
                    createFile();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
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
        searchGeneration++;
        searching = false;
        searchQuery = "";
        searchResults.clear();
        exitSelection();
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
        if (titleView != null) {
            String name = currentDirectory.getName();
            titleView.setText(name == null || name.isEmpty()
                ? activity.getString(R.string.opencode_files_dialog_title) : name);
        }
        if (upButton != null) upButton.setEnabled(currentDirectory.getParentFile() != null);
        if (homeButton != null) homeButton.setEnabled(!path.equals(homePath));
        updateBreadcrumbs(path, homePath);
    }

    /** Rebuild the clickable path segments below the toolbar. */
    private void updateBreadcrumbs(String path, String homePath) {
        if (breadcrumb == null) return;
        breadcrumb.removeAllViews();

        List<String> labels = new ArrayList<>();
        List<File> targets = new ArrayList<>();
        if (path.equals(homePath) || path.startsWith(homePath + "/")) {
            labels.add("~");
            targets.add(home);
            String rest = path.substring(homePath.length());
            File cursor = home;
            for (String part : rest.split("/")) {
                if (part.isEmpty()) continue;
                cursor = new File(cursor, part);
                labels.add(part);
                targets.add(cursor);
            }
        } else {
            labels.add("/");
            targets.add(new File("/"));
            File cursor = new File("/");
            for (String part : path.split("/")) {
                if (part.isEmpty()) continue;
                cursor = new File(cursor, part);
                labels.add(part);
                targets.add(cursor);
            }
        }

        int accent = ContextCompat.getColor(activity, R.color.github_accent);
        int muted = ContextCompat.getColor(activity, R.color.github_muted);
        for (int i = 0; i < labels.size(); i++) {
            final File target = targets.get(i);
            TextView segment = new TextView(activity);
            segment.setText(labels.get(i));
            segment.setTextSize(12);
            segment.setSingleLine(true);
            segment.setPadding(dp(6), dp(3), dp(6), dp(3));
            segment.setTextColor(i == labels.size() - 1 ? accent : muted);
            segment.setBackgroundResource(android.R.drawable.list_selector_background);
            segment.setOnClickListener(v -> navigateTo(target));
            breadcrumb.addView(segment);
            if (i < labels.size() - 1) {
                TextView separator = new TextView(activity);
                separator.setText("›");
                separator.setTextSize(12);
                separator.setTextColor(muted);
                separator.setPadding(dp(1), dp(3), dp(1), dp(3));
                breadcrumb.addView(separator);
            }
        }
        if (breadcrumbScroll != null) {
            breadcrumbScroll.post(() -> breadcrumbScroll.fullScroll(View.FOCUS_RIGHT));
        }
    }

    private void reload() {
        if (currentDirectory == null) return;
        final File directory = currentDirectory;
        final int generation = ++loadGeneration;
        searchGeneration++;
        searching = false;
        searchQuery = "";
        searchResults.clear();
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
        final boolean ascending = sortAscending;
        final boolean dirsFirst = directoriesFirst;
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
            if (dirsFirst && left.directory != right.directory) {
                return left.directory ? -1 : 1;
            }
            int comparison;
            switch (sort) {
                case SORT_SIZE:
                    comparison = Long.compare(left.size, right.size);
                    break;
                case SORT_MODIFIED:
                    comparison = Long.compare(left.modified, right.modified);
                    break;
                case SORT_TYPE:
                    comparison = typeKey(left).compareToIgnoreCase(typeKey(right));
                    break;
                default:
                    comparison = left.file.getName().compareToIgnoreCase(right.file.getName());
                    break;
            }
            if (comparison == 0) {
                comparison = left.file.getName().compareToIgnoreCase(right.file.getName());
            }
            return ascending ? comparison : -comparison;
        });
        return result;
    }

    private static String typeKey(Entry entry) {
        String name = entry.file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private void rebuildVisibleEntries() {
        if (adapter == null) return;
        String filter = query.trim().toLowerCase(Locale.ROOT);
        if (filter.isEmpty()) {
            searchGeneration++;
            searching = false;
            searchQuery = "";
            searchResults.clear();
            entries.clear();
            entries.addAll(allEntries);
            adapter.notifyDataSetChanged();
            setProgressVisible(false);
            updateEmptyView();
            updateSummary();
            updateSelectionUi();
            return;
        }

        if (recursiveSearch) {
            if (!searching || !filter.equals(searchQuery)) {
                startRecursiveSearch(filter);
            }
            return;
        }

        searchGeneration++;
        searching = false;
        searchQuery = "";
        searchResults.clear();
        entries.clear();
        for (Entry entry : allEntries) {
            if (entry.file.getName().toLowerCase(Locale.ROOT).contains(filter)) {
                entries.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
        setProgressVisible(false);
        updateEmptyView();
        updateSummary();
        updateSelectionUi();
    }

    /** Search recursively below the current directory, without following symbolic links. */
    private void startRecursiveSearch(final String filter) {
        final File root = currentDirectory;
        if (root == null) return;
        final int generation = ++searchGeneration;
        searchQuery = filter;
        searching = true;
        entries.clear();
        adapter.notifyDataSetChanged();
        updateEmptyView();
        updateSummary();
        setProgressVisible(true);

        executor.execute(() -> {
            List<Entry> found = new ArrayList<>();
            ArrayDeque<FileDepth> stack = new ArrayDeque<>();
            stack.push(new FileDepth(root, 0));
            int visited = 0;
            while (!stack.isEmpty() && found.size() < SEARCH_LIMIT) {
                if (generation != searchGeneration || Thread.currentThread().isInterrupted()) {
                    return;
                }
                FileDepth current = stack.pop();
                File[] children = current.file.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    if (generation != searchGeneration
                        || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    if (++visited > SEARCH_LIMIT * 8) {
                        stack.clear();
                        break;
                    }
                    String name = child.getName();
                    if (!showHidden && name.startsWith(".")) continue;
                    if (isSymbolicLink(child)) continue;
                    Entry entry = new Entry(child);
                    entry.directory = child.isDirectory();
                    entry.modified = child.lastModified();
                    entry.size = entry.directory ? DETAILS_UNKNOWN
                        : Math.max(0, child.length());
                    entry.relativePath = relativePath(root, child);
                    if (name.toLowerCase(Locale.ROOT).contains(filter)) {
                        found.add(entry);
                        if (found.size() >= SEARCH_LIMIT) break;
                    }
                    if (entry.directory && current.depth < SEARCH_MAX_DEPTH) {
                        stack.push(new FileDepth(child, current.depth + 1));
                    }
                }
            }
            Collections.sort(found, (left, right) -> {
                if (left.directory != right.directory) return left.directory ? -1 : 1;
                return left.relativePath.compareToIgnoreCase(right.relativePath);
            });
            final List<Entry> results = found;
            activity.runOnUiThread(() -> {
                if (!isAlive() || generation != searchGeneration) return;
                searching = false;
                searchResults.clear();
                searchResults.addAll(results);
                entries.clear();
                entries.addAll(searchResults);
                adapter.notifyDataSetChanged();
                setProgressVisible(false);
                updateEmptyView();
                updateSummary();
                updateSelectionUi();
            });
        });
    }

    private static String relativePath(File root, File child) {
        String rootPath = root.getAbsolutePath();
        String childPath = child.getAbsolutePath();
        if (childPath.startsWith(rootPath + File.separator)) {
            return childPath.substring(rootPath.length() + 1);
        }
        return childPath;
    }

    private static final class FileDepth {
        final File file;
        final int depth;

        FileDepth(File file, int depth) {
            this.file = file;
            this.depth = depth;
        }
    }

    private void updateEmptyView() {
        if (emptyView == null) return;
        if (loading || searching) {
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
        } else if (searching) {
            summaryView.setText(R.string.opencode_files_searching);
        } else if (loading) {
            summaryView.setText(R.string.opencode_files_scanning);
        } else if (recursiveSearch && !query.trim().isEmpty()) {
            if (freeBytes >= 0) {
                summaryView.setText(activity.getString(
                    R.string.opencode_files_search_results_free,
                    entries.size(), OpenCodeInstallDialog.formatSize(freeBytes)));
            } else {
                summaryView.setText(activity.getString(
                    R.string.opencode_files_search_results, entries.size()));
            }
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
        if (locationsButton != null) locationsButton.setEnabled(enabled && !busy);
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

    /** Enter multi-select mode with {@code entry} selected, used by long press and the menus. */
    private void beginSelection(Entry entry) {
        selectionMode = true;
        selection.add(entry.file.getAbsolutePath());
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

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
        if (locationsButton != null) locationsButton.setEnabled(available);
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
        Set<String> seen = new HashSet<>();
        for (Entry entry : allEntries) {
            String path = entry.file.getAbsolutePath();
            if (selection.contains(path) && seen.add(path)) result.add(entry);
        }
        for (Entry entry : searchResults) {
            String path = entry.file.getAbsolutePath();
            if (selection.contains(path) && seen.add(path)) result.add(entry);
        }
        return result;
    }

    /* ==========================================================================================
     * Entry actions
     * ========================================================================================== */

    private void showEntryActions(final Entry entry) {
        final List<Runnable> actions = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        if (entry.file.isDirectory()) {
            labels.add(activity.getString(R.string.opencode_files_open_folder));
            actions.add(() -> navigateTo(entry.file));
            labels.add(activity.getString(R.string.opencode_files_open_project));
            actions.add(() -> openProject(entry));
        } else {
            // Like Acode: text and code files open in the built-in viewer, everything else
            // is handed to another app.
            if (FileManagerUtils.isTextFile(entry.file)) {
                labels.add(activity.getString(R.string.opencode_files_open_in_app));
                actions.add(() -> viewCode(entry, false));
                if (!entry.symlink) {
                    labels.add(activity.getString(R.string.opencode_files_edit_text));
                    actions.add(() -> viewCode(entry, true));
                }
            }
            labels.add(activity.getString(R.string.opencode_files_open_file));
            actions.add(() -> openFile(entry, false));
            labels.add(activity.getString(R.string.opencode_files_open_with));
            actions.add(() -> openFile(entry, true));
        }

        if (entry.relativePath != null) {
            labels.add(activity.getString(R.string.opencode_files_open_containing_folder));
            actions.add(() -> openContainingFolder(entry));
        }

        labels.add(activity.getString(R.string.opencode_files_select_multiple));
        actions.add(() -> beginSelection(entry));
        labels.add(activity.getString(R.string.opencode_files_share));
        actions.add(() -> shareEntries(Collections.singletonList(entry)));
        labels.add(activity.getString(R.string.opencode_files_copy_path));
        actions.add(() -> copyPath(entry.file));
        labels.add(activity.getString(R.string.opencode_files_copy));
        actions.add(() -> copyToClipboard(Collections.singletonList(entry), false));
        labels.add(activity.getString(R.string.opencode_files_move));
        actions.add(() -> copyToClipboard(Collections.singletonList(entry), true));
        labels.add(activity.getString(R.string.opencode_files_rename));
        actions.add(() -> promptRename(entry));
        if (!entry.symlink) {
            labels.add(activity.getString(R.string.opencode_files_compress));
            actions.add(() -> compressEntries(Collections.singletonList(entry)));
        }
        if (!entry.symlink && FileManagerUtils.isZipArchive(entry.file)) {
            labels.add(activity.getString(R.string.opencode_files_extract));
            actions.add(() -> extractArchive(entry));
        }
        labels.add(activity.getString(R.string.opencode_files_properties));
        actions.add(() -> showDetails(entry));
        labels.add(activity.getString(R.string.opencode_files_delete));
        actions.add(() -> confirmDelete(entry));

        new MaterialAlertDialogBuilder(activity)
            .setTitle(entry.file.getName())
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which >= 0 && which < actions.size()) actions.get(which).run();
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void openContainingFolder(Entry entry) {
        File parent = entry.file.getParentFile();
        if (parent != null && parent.isDirectory()) {
            navigateTo(parent);
        } else {
            toast(R.string.opencode_files_reveal_failed);
        }
    }

    /** Open the tapped file: text and code files go to the built-in viewer, others to an app. */
    private void openEntry(Entry entry) {
        if (FileManagerUtils.isTextFile(entry.file)) {
            viewCode(entry, false);
        } else {
            openFile(entry, false);
        }
    }

    /**
     * Show the file in the built-in code viewer. A symbolic link is shown read-only, and
     * the same safety rules as before decide whether editing is allowed.
     */
    private void viewCode(Entry entry, boolean startEditing) {
        boolean writable = !entry.symlink && isSafeToModify(entry.file) && entry.file.canWrite();
        CodeViewerDialog.show(activity, entry.file, writable, startEditing, file -> reload());
    }

    private void openProject(Entry entry) {
        if (!entry.file.isDirectory()) return;
        if (dialog != null) dialog.dismiss();
        listener.onOpenProject(entry.file);
    }

    private void openFile(Entry entry, boolean chooser) {
        try {
            Uri uri = uriFor(entry.file);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType(entry.file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (chooser) {
                activity.startActivity(Intent.createChooser(intent,
                    activity.getString(R.string.opencode_files_open_with)));
            } else {
                activity.startActivity(intent);
            }
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
        labels.add(activity.getString(R.string.opencode_files_selection_compress));
        actions.add(() -> compressEntries(selected));
        if (selected.size() == 1) {
            labels.add(activity.getString(R.string.opencode_files_selection_properties));
            actions.add(() -> showDetails(selected.get(0)));
        }
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
                        if (!isSafeToModify(target)) {
                            throw new IOException("Protected destination");
                        }
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
        boolean canModify = isSafeToModify(entry.file);
        FilePropertiesDialog.show(activity, entry.file, canModify, file -> reload());
    }

    /* ==========================================================================================
     * ZIP compression and extraction
     * ========================================================================================== */

    /** Compress the given entries into a ZIP archive next to them. */
    private void compressEntries(final List<Entry> selected) {
        if (busy || loading) {
            toast(R.string.opencode_files_busy);
            return;
        }
        if (selected == null || selected.isEmpty()) return;
        final List<File> sources = new ArrayList<>();
        for (Entry entry : selected) {
            if (entry == null || entry.file == null) continue;
            // A symlink is never compressed: addToZip would silently skip it and leave an
            // empty archive behind.
            if (FileManagerUtils.isSymbolicLink(entry.file)) continue;
            sources.add(entry.file);
        }
        if (sources.isEmpty()) {
            toast(R.string.opencode_files_compress_failed);
            return;
        }

        String base = sources.size() == 1
            ? safeFileName(sources.get(0).getName())
            : safeFileName(currentDirectory == null ? "files" : currentDirectory.getName());
        if (!base.toLowerCase(Locale.ROOT).endsWith(".zip")) base += ".zip";

        final File destination = FileManagerUtils.uniqueTarget(
            currentDirectory == null ? new File("/") : currentDirectory, base);
        if (currentDirectory == null || !isSafeToModify(destination)) {
            toast(R.string.opencode_files_modify_unsafe);
            return;
        }

        busy = true;
        temporaryStatus = activity.getString(R.string.opencode_files_compressing);
        setProgressVisible(true);
        setControlsEnabled(false);
        updateSummary();

        executor.execute(() -> {
            String error = null;
            try {
                FileManagerUtils.createZip(sources, destination, name -> {
                    if (Thread.currentThread().isInterrupted()) return false;
                    return true;
                });
            } catch (Throwable t) {
                error = t.getMessage();
                destination.delete();
            }
            final String failure = error;
            activity.runOnUiThread(() -> {
                busy = false;
                temporaryStatus = null;
                if (!isAlive()) return;
                setProgressVisible(false);
                setControlsEnabled(true);
                if (failure != null) {
                    toast(R.string.opencode_files_compress_failed);
                } else {
                    toast(activity.getString(R.string.opencode_files_compressed,
                        destination.getName()));
                }
                reload();
            });
        });
    }

    /** Extract a ZIP archive into a new folder next to it. */
    private void extractArchive(final Entry entry) {
        if (busy || loading) {
            toast(R.string.opencode_files_busy);
            return;
        }
        if (entry == null || !FileManagerUtils.isZipArchive(entry.file)) return;

        String name = entry.file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        else name = name + "-extracted";
        name = safeFileName(name);
        final File parent = entry.file.getParentFile();
        if (parent == null || !isSafeToModify(entry.file)) {
            toast(R.string.opencode_files_modify_unsafe);
            return;
        }
        final File destination = FileManagerUtils.uniqueTarget(parent, name);

        busy = true;
        temporaryStatus = activity.getString(R.string.opencode_files_extracting);
        setProgressVisible(true);
        setControlsEnabled(false);
        updateSummary();

        executor.execute(() -> {
            int extracted = 0;
            String error = null;
            try {
                extracted = FileManagerUtils.extractZip(entry.file, destination, path -> {
                    if (Thread.currentThread().isInterrupted()) return false;
                    return true;
                });
            } catch (Throwable t) {
                error = t.getMessage();
                FileManagerUtils.deleteRecursively(destination);
            }
            final int count = extracted;
            final String failure = error;
            activity.runOnUiThread(() -> {
                busy = false;
                temporaryStatus = null;
                if (!isAlive()) return;
                setProgressVisible(false);
                setControlsEnabled(true);
                if (failure != null) {
                    toast(R.string.opencode_files_extract_failed);
                } else {
                    toast(activity.getString(R.string.opencode_files_extracted,
                        count, destination.getName()));
                }
                reload();
            });
        });
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

        if (!StorageAccess.hasStoragePermission(activity)) {
            labels.add(activity.getString(R.string.opencode_files_storage_grant));
            actions.add(() -> listener.onManageStorage());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !StorageAccess.hasAllFilesAccess(activity)) {
            labels.add(activity.getString(R.string.opencode_files_storage_grant_sd));
            actions.add(() -> listener.onManageStorage());
        }

        labels.add(activity.getString(R.string.opencode_files_locations));
        actions.add(this::showLocations);
        labels.add(activity.getString(R.string.opencode_files_go_to_path));
        actions.add(this::promptGoToPath);
        if (currentDirectory != null) {
            boolean bookmarked = isBookmarked(currentDirectory);
            labels.add(activity.getString(bookmarked
                ? R.string.opencode_files_bookmark_remove
                : R.string.opencode_files_bookmark_add));
            actions.add(() -> toggleBookmark(currentDirectory));
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
        if (currentDirectory != null) {
            labels.add(activity.getString(R.string.opencode_files_compress_current));
            actions.add(() -> compressEntries(Collections.singletonList(
                new Entry(currentDirectory))));
        }
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

    /* ==========================================================================================
     * Locations, bookmarks and go-to-path
     * ========================================================================================== */

    private void showLocations() {
        final List<Runnable> actions = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        labels.add(activity.getString(R.string.opencode_files_location_home));
        actions.add(() -> navigateTo(home));
        File filesDir = TermuxConstants.TERMUX_FILES_DIR;
        if (filesDir != null && filesDir.isDirectory()) {
            labels.add(activity.getString(R.string.opencode_files_location_files));
            actions.add(() -> navigateTo(filesDir));
        }
        File primary = StorageAccess.primaryRoot();
        if (primary != null) {
            labels.add(activity.getString(R.string.opencode_files_location_internal));
            actions.add(() -> navigateTo(primary));
        }
        for (File root : StorageAccess.volumeRoots(activity)) {
            if (StorageAccess.isPrimaryRoot(root)) continue;
            labels.add(activity.getString(R.string.opencode_files_location_sd, root.getName()));
            actions.add(() -> navigateTo(root));
        }
        File dataDir = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR;
        if (dataDir.isDirectory()) {
            labels.add(activity.getString(R.string.opencode_files_location_app_data));
            actions.add(() -> navigateTo(dataDir));
        }
        labels.add(activity.getString(R.string.opencode_files_location_root));
        actions.add(() -> navigateTo(new File("/")));

        if (!StorageAccess.hasStoragePermission(activity)) {
            labels.add(activity.getString(R.string.opencode_files_storage_grant));
            actions.add(() -> listener.onManageStorage());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !StorageAccess.hasAllFilesAccess(activity)) {
            labels.add(activity.getString(R.string.opencode_files_storage_grant_sd));
            actions.add(() -> listener.onManageStorage());
        }

        if (!bookmarks.isEmpty()) {
            labels.add(activity.getString(R.string.opencode_files_bookmarks));
            actions.add(this::showBookmarks);
        }

        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_locations_title)
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which >= 0 && which < actions.size()) actions.get(which).run();
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void showBookmarks() {
        final List<Runnable> actions = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String path : bookmarks) {
            final File directory = new File(path);
            labels.add(directory.getName().isEmpty() ? path : directory.getName());
            actions.add(() -> {
                if (directory.isDirectory()) navigateTo(directory);
                else toast(R.string.opencode_files_go_to_path_invalid);
            });
        }
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_bookmarks)
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which >= 0 && which < actions.size()) actions.get(which).run();
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void loadBookmarks() {
        bookmarks.clear();
        String raw = preferences.getString(PREF_BOOKMARKS, null);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String path = array.optString(i, null);
                if (path != null && !path.isEmpty() && !bookmarks.contains(path)) {
                    bookmarks.add(path);
                }
            }
        } catch (Throwable ignored) {
            // A corrupted bookmark list is simply ignored.
        }
    }

    private void saveBookmarks() {
        JSONArray array = new JSONArray();
        for (String path : bookmarks) array.put(path);
        preferences.edit().putString(PREF_BOOKMARKS, array.toString()).apply();
    }

    private boolean isBookmarked(File directory) {
        if (directory == null) return false;
        String path = directory.getAbsolutePath();
        for (String bookmark : bookmarks) {
            if (bookmark.equals(path)) return true;
        }
        return false;
    }

    private void toggleBookmark(File directory) {
        if (directory == null) return;
        String path = directory.getAbsolutePath();
        if (bookmarks.remove(path)) {
            toast(R.string.opencode_files_bookmark_removed);
        } else {
            bookmarks.add(path);
            toast(R.string.opencode_files_bookmark_added);
        }
        saveBookmarks();
    }

    private void promptGoToPath() {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.opencode_files_go_to_path_hint);
        input.setText(currentDirectory == null ? "" : currentDirectory.getAbsolutePath());
        input.setSelection(input.getText().length());

        FrameLayout wrapper = new FrameLayout(activity);
        int padding = dp(20);
        wrapper.setPadding(padding, dp(6), padding, 0);
        wrapper.addView(input, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_go_to_path)
            .setView(wrapper)
            .setPositiveButton(R.string.opencode_files_manage, (dialog, which) -> {
                String text = input.getText() == null ? "" : input.getText().toString().trim();
                if (text.isEmpty()) return;
                if (text.equals("~")) text = home.getAbsolutePath();
                else if (text.startsWith("~/")) {
                    text = home.getAbsolutePath() + text.substring(1);
                }
                File target = new File(text);
                if (!target.isAbsolute()) target = new File(currentDirectory, text);
                if (target.isDirectory()) {
                    navigateTo(target);
                } else {
                    toast(R.string.opencode_files_go_to_path_invalid);
                }
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void showPathActions() {
        if (currentDirectory == null) return;
        String path = currentDirectory.getAbsolutePath();
        final List<Runnable> actions = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        labels.add(activity.getString(R.string.opencode_files_path_copy));
        actions.add(() -> copyPath(currentDirectory));
        labels.add(activity.getString(R.string.opencode_files_go_to_path));
        actions.add(this::promptGoToPath);
        labels.add(activity.getString(R.string.opencode_files_locations));
        actions.add(this::showLocations);
        labels.add(activity.getString(isBookmarked(currentDirectory)
            ? R.string.opencode_files_bookmark_remove
            : R.string.opencode_files_bookmark_add));
        actions.add(() -> toggleBookmark(currentDirectory));

        new MaterialAlertDialogBuilder(activity)
            .setTitle(path)
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
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, dp(6), padding, 0);

        final String[] fields = {
            activity.getString(R.string.opencode_files_sort_name),
            activity.getString(R.string.opencode_files_sort_size),
            activity.getString(R.string.opencode_files_sort_modified),
            activity.getString(R.string.opencode_files_sort_type)
        };

        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.VERTICAL);
        final RadioButton[] buttons = new RadioButton[fields.length];
        for (int i = 0; i < fields.length; i++) {
            RadioButton button = new RadioButton(activity);
            button.setId(View.generateViewId());
            button.setText(fields[i]);
            group.addView(button);
            buttons[i] = button;
        }
        int checked = sortMode >= 0 && sortMode < buttons.length ? sortMode : 0;
        buttons[checked].setChecked(true);
        content.addView(group);

        final CheckBox ascending = new CheckBox(activity);
        ascending.setText(R.string.opencode_files_sort_ascending);
        ascending.setChecked(sortAscending);
        content.addView(ascending);

        final CheckBox dirsFirst = new CheckBox(activity);
        dirsFirst.setText(R.string.opencode_files_sort_dirs_first);
        dirsFirst.setChecked(directoriesFirst);
        content.addView(dirsFirst);

        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_sort)
            .setView(content)
            .setPositiveButton(R.string.opencode_files_sort_apply, (dialog, which) -> {
                int selected = 0;
                for (int i = 0; i < buttons.length; i++) {
                    if (buttons[i].isChecked()) {
                        selected = i;
                        break;
                    }
                }
                sortMode = selected;
                sortAscending = ascending.isChecked();
                directoriesFirst = dirsFirst.isChecked();
                preferences.edit()
                    .putInt(PREF_SORT, sortMode)
                    .putBoolean(PREF_SORT_ASCENDING, sortAscending)
                    .putBoolean(PREF_DIRS_FIRST, directoriesFirst)
                    .apply();
                if (recursiveSearch && !query.trim().isEmpty()) {
                    searchQuery = "";
                    rebuildVisibleEntries();
                } else {
                    reload();
                }
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
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
        String kind = entry.relativePath != null ? entry.relativePath : describeKind(entry);
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
        String relativePath;
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
            boolean grid = parent instanceof GridView;
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(activity).inflate(
                    grid ? R.layout.item_managed_file_grid : R.layout.item_managed_file,
                    parent, false);
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
                } else if (FileManagerUtils.isZipArchive(entry.file)) {
                    iconRes = R.drawable.ic_archive;
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
