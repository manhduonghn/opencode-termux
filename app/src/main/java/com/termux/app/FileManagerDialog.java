package com.termux.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.StatFs;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the files and folders that were downloaded or cloned into the Termux home directory and
 * lets the user open them as a project, copy their path or delete them.
 *
 * <p>Only top level entries are shown. Hidden entries and the files that the app itself manages
 * ({@code storage}, {@code tmp}, {@code usr}) are skipped so that the app cannot break its own
 * environment. Symlinks are skipped as well: deleting a link is harmless, but it would be
 * confusing to show an external folder as if it were stored in Termux.</p>
 */
final class FileManagerDialog {

    /** Receives a directory that the user wants to open as an OpenCode project. */
    interface Listener {
        void onOpenProject(File directory);
    }

    /** Top level home entries that are never listed or deleted. */
    private static final Set<String> PROTECTED_NAMES = new HashSet<>(
        Arrays.asList("storage", "tmp", "usr"));

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60L * MINUTE_MS;
    private static final long DAY_MS = 24L * HOUR_MS;

    private static final int ACTION_OPEN = 1;
    private static final int ACTION_COPY = 2;
    private static final int ACTION_DELETE = 3;

    private final Activity activity;
    private final Listener listener;
    private final File home;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AlertDialog dialog;
    private ListView listView;
    private TextView summaryView;
    private TextView emptyView;
    private ProgressBar progressBar;
    private ImageButton refreshButton;

    private final List<Entry> entries = new ArrayList<>();
    private EntryAdapter adapter;
    private boolean loading;
    private boolean deleting;

    private FileManagerDialog(Activity activity, File home, Listener listener) {
        this.activity = activity;
        this.home = home;
        this.listener = listener;
    }

    /** Show the manager. Must be called on the UI thread. */
    static void show(Activity activity, Listener listener) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        new FileManagerDialog(activity, home, listener).show();
    }

    private void show() {
        View view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_file_manager, null, false);

        listView = view.findViewById(R.id.managed_file_list);
        summaryView = view.findViewById(R.id.managed_file_summary);
        emptyView = view.findViewById(R.id.managed_file_empty);
        progressBar = view.findViewById(R.id.managed_file_progress);
        refreshButton = view.findViewById(R.id.managed_file_refresh);

        adapter = new EntryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, item, position, id) -> {
            if (position >= 0 && position < entries.size()) showActions(entries.get(position));
        });

        if (refreshButton != null) {
            refreshButton.setOnClickListener(v -> reload());
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle(R.string.opencode_files_dialog_title);
        builder.setView(view);
        builder.setNegativeButton(R.string.close, null);
        builder.setOnDismissListener(d -> executor.shutdownNow());

        dialog = builder.create();
        dialog.show();
        reload();
    }

    /** Read the home directory in the background, then fill the list. */
    private void reload() {
        if (loading || deleting) return;
        loading = true;
        setRefreshEnabled(false);
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (emptyView != null) emptyView.setVisibility(View.GONE);
        if (summaryView != null) summaryView.setText(R.string.opencode_files_scanning);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                List<Entry> loaded;
                try {
                    loaded = scan();
                } catch (Throwable t) {
                    loaded = null;
                }
                final List<Entry> scanned = loaded;
                final long free = availableBytes();
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAlive()) return;
                        loading = false;
                        setRefreshEnabled(true);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);

                        entries.clear();
                        if (scanned == null) {
                            adapter.notifyDataSetChanged();
                            if (summaryView != null) summaryView.setText(R.string.opencode_files_unreadable);
                            if (emptyView != null) {
                                emptyView.setText(R.string.opencode_files_unreadable);
                                emptyView.setVisibility(View.VISIBLE);
                            }
                            return;
                        }

                        entries.addAll(scanned);
                        adapter.notifyDataSetChanged();
                        updateSummary(free);
                        if (emptyView != null) {
                            emptyView.setText(R.string.opencode_files_empty);
                            emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                        }
                    }
                });
            }
        });
    }

    /** @return the entries sorted by kind and name, or {@code null} when home is unreadable. */
    private List<Entry> scan() {
        File[] children = home.listFiles();
        if (children == null) return null;

        List<Entry> result = new ArrayList<>();
        for (File child : children) {
            String name = child.getName();
            // Hidden files (including ~/.opencode) are managed by the app or by OpenCode itself.
            if (name.startsWith(".")) continue;
            if (PROTECTED_NAMES.contains(name)) continue;
            if (isSymbolicLink(child)) continue;
            Entry entry = new Entry(child);
            result.add(entry);
        }

        Collections.sort(result, (left, right) -> {
            boolean leftDir = left.file.isDirectory();
            boolean rightDir = right.file.isDirectory();
            if (leftDir != rightDir) return leftDir ? -1 : 1;
            return left.file.getName().compareToIgnoreCase(right.file.getName());
        });

        for (Entry entry : result) {
            entry.git = entry.file.isDirectory() && new File(entry.file, ".git").exists();
            entry.size = sizeOf(entry.file);
        }
        return result;
    }

    /** Recursively sum the size of an entry. Symlinks are not followed. */
    private static long sizeOf(File file) {
        if (isSymbolicLink(file)) return 0;
        if (file.isFile()) return Math.max(0, file.length());
        if (!file.isDirectory()) return 0;

        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) total += sizeOf(child);
        }
        return total;
    }

    private void updateSummary(long freeBytes) {
        if (summaryView == null) return;
        long total = 0;
        for (Entry entry : entries) {
            if (entry.size > 0) total += entry.size;
        }
        if (freeBytes >= 0) {
            summaryView.setText(activity.getString(R.string.opencode_files_summary,
                entries.size(), OpenCodeInstallDialog.formatSize(total),
                OpenCodeInstallDialog.formatSize(freeBytes)));
        } else {
            summaryView.setText(activity.getString(R.string.opencode_files_summary_no_free,
                entries.size(), OpenCodeInstallDialog.formatSize(total)));
        }
    }

    /** Available space of the partition that holds the Termux home directory. */
    private long availableBytes() {
        try {
            return new StatFs(home.getAbsolutePath()).getAvailableBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void showActions(final Entry entry) {
        final boolean isDirectory = entry.file.isDirectory();
        final List<String> labels = new ArrayList<>();
        final List<Integer> actions = new ArrayList<>();
        if (isDirectory) {
            labels.add(activity.getString(R.string.opencode_files_open));
            actions.add(ACTION_OPEN);
        }
        labels.add(activity.getString(R.string.opencode_files_copy_path));
        actions.add(ACTION_COPY);
        labels.add(activity.getString(R.string.opencode_files_delete));
        actions.add(ACTION_DELETE);

        new MaterialAlertDialogBuilder(activity)
            .setTitle(entry.file.getName())
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which < 0 || which >= actions.size()) return;
                switch (actions.get(which)) {
                    case ACTION_OPEN:
                        openProject(entry);
                        break;
                    case ACTION_COPY:
                        copyPath(entry.file);
                        break;
                    case ACTION_DELETE:
                        confirmDelete(entry);
                        break;
                }
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void openProject(Entry entry) {
        if (!entry.file.isDirectory()) return;
        if (dialog != null) dialog.dismiss();
        listener.onOpenProject(entry.file);
    }

    private void copyPath(File file) {
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
            // The clipboard is a convenience, deletion and opening still work.
        }
    }

    private void confirmDelete(final Entry entry) {
        String size = entry.size >= 0
            ? OpenCodeInstallDialog.formatSize(entry.size)
            : activity.getString(R.string.opencode_files_size_unknown);
        String message = entry.file.isDirectory()
            ? activity.getString(R.string.opencode_files_delete_message_dir, size)
            : activity.getString(R.string.opencode_files_delete_message_file, size);

        new MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.opencode_files_delete_title,
                entry.file.getName()))
            .setMessage(message)
            .setPositiveButton(R.string.opencode_files_delete, (dialog, which) -> delete(entry))
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    /** Delete the entry in the background and remove it from the list when it succeeded. */
    private void delete(final Entry entry) {
        if (!isSafeToDelete(entry.file)) {
            Toast.makeText(activity, R.string.opencode_files_delete_unsafe,
                Toast.LENGTH_LONG).show();
            return;
        }
        if (deleting || loading) {
            Toast.makeText(activity, R.string.opencode_files_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        deleting = true;
        setRefreshEnabled(false);
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (summaryView != null) {
            summaryView.setText(activity.getString(R.string.opencode_files_deleting,
                entry.file.getName()));
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean success;
                try {
                    success = deleteRecursively(entry.file);
                } catch (Throwable t) {
                    success = false;
                }
                final boolean ok = success;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        deleting = false;
                        if (!isAlive()) return;
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        setRefreshEnabled(true);
                        if (ok) {
                            entries.remove(entry);
                            adapter.notifyDataSetChanged();
                            updateSummary(availableBytes());
                            Toast.makeText(activity,
                                activity.getString(R.string.opencode_files_deleted,
                                    entry.file.getName()),
                                Toast.LENGTH_SHORT).show();
                            if (emptyView != null && entries.isEmpty()) {
                                emptyView.setText(R.string.opencode_files_empty);
                                emptyView.setVisibility(View.VISIBLE);
                            }
                        } else {
                            updateSummary(availableBytes());
                            Toast.makeText(activity,
                                activity.getString(R.string.opencode_files_delete_failed,
                                    entry.file.getName()),
                                Toast.LENGTH_LONG).show();
                            // Part of the tree may have been removed already. Re-read the
                            // directory so the list and the sizes match the disk again.
                            reload();
                        }
                    }
                });
            }
        });
    }

    /**
     * Never delete outside the home directory, never delete a protected entry and never follow a
     * symlink. The manager only lists safe entries, but the check keeps a stale list harmless.
     */
    private boolean isSafeToDelete(File file) {
        if (file == null) return false;
        File parent = file.getParentFile();
        if (parent == null) return false;
        if (!parent.getAbsolutePath().equals(home.getAbsolutePath())) return false;
        if (file.getName().startsWith(".")) return false;
        if (PROTECTED_NAMES.contains(file.getName())) return false;
        return !isSymbolicLink(file);
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
        }
        return file.delete();
    }

    private static boolean isSymbolicLink(File file) {
        try {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            return OsConstants.S_ISLNK(stat.st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    private void setRefreshEnabled(boolean enabled) {
        if (refreshButton != null) refreshButton.setEnabled(enabled);
    }

    private boolean isAlive() {
        return dialog != null && dialog.isShowing()
            && !activity.isFinishing() && !activity.isDestroyed();
    }

    private String describe(Entry entry) {
        String kind;
        if (entry.git) {
            kind = activity.getString(R.string.opencode_files_kind_repo);
        } else if (entry.file.isDirectory()) {
            kind = activity.getString(R.string.opencode_files_kind_dir);
        } else {
            kind = activity.getString(R.string.opencode_files_kind_file);
        }
        String size = entry.size >= 0
            ? OpenCodeInstallDialog.formatSize(entry.size)
            : activity.getString(R.string.opencode_files_size_unknown);
        return activity.getString(R.string.opencode_files_meta, kind, size,
            relativeTime(entry.file.lastModified()));
    }

    private String relativeTime(long timestamp) {
        if (timestamp <= 0) return activity.getString(R.string.opencode_files_time_unknown);
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < MINUTE_MS) return activity.getString(R.string.opencode_files_time_now);
        if (diff < HOUR_MS) {
            return activity.getString(R.string.opencode_files_time_minutes, diff / MINUTE_MS);
        }
        if (diff < DAY_MS) {
            return activity.getString(R.string.opencode_files_time_hours, diff / HOUR_MS);
        }
        if (diff < 7 * DAY_MS) {
            return activity.getString(R.string.opencode_files_time_days, diff / DAY_MS);
        }
        return new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            .format(new Date(timestamp));
    }

    private static final class Entry {
        final File file;
        long size = -1;
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
            ImageButton delete = view.findViewById(R.id.managed_file_delete);

            if (name != null) name.setText(entry.file.getName());
            if (meta != null) meta.setText(describe(entry));
            if (icon != null) {
                icon.setImageResource(entry.git
                    ? R.drawable.ic_github_branch
                    : entry.file.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file);
            }
            if (delete != null) {
                delete.setContentDescription(activity.getString(
                    R.string.opencode_files_delete_title, entry.file.getName()));
                delete.setOnClickListener(v -> confirmDelete(entry));
            }
            return view;
        }
    }
}
