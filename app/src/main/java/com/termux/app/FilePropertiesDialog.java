package com.termux.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Detailed file properties: basic information, recursive contents, POSIX permissions, symbolic
 * link target, SELinux context, checksums, APK metadata and image dimensions.
 */
final class FilePropertiesDialog {

    interface Listener {
        void onChanged(File file);
    }

    private static final class Row {
        final String label;
        final TextView value;

        Row(String label, TextView value) {
            this.label = label;
            this.value = value;
        }
    }

    private final Activity activity;
    private final File file;
    private final boolean canModify;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Row> rows = new ArrayList<>();

    private AlertDialog dialog;
    private LinearLayout container;
    private TextView status;
    private ProgressBar progress;
    private TextView modeValue;
    private TextView contentsValue;
    private TextView md5Value;
    private TextView sha1Value;
    private TextView sha256Value;
    private int mode;

    private FilePropertiesDialog(Activity activity, File file, boolean canModify,
                                 Listener listener) {
        this.activity = activity;
        this.file = file;
        this.canModify = canModify;
        this.listener = listener;
    }

    static void show(Activity activity, File file, boolean canModify, Listener listener) {
        if (activity == null || file == null || activity.isFinishing()
            || activity.isDestroyed()) {
            return;
        }
        new FilePropertiesDialog(activity, file, canModify, listener).show();
    }

    private void show() {
        View view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_file_properties, null, false);
        container = view.findViewById(R.id.file_properties_container);
        status = view.findViewById(R.id.file_properties_status);
        progress = view.findViewById(R.id.file_properties_progress);

        buildBasicRows();

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
            .setTitle(file.getName())
            .setView(view)
            .setNegativeButton(R.string.opencode_files_close, null)
            .setPositiveButton(R.string.opencode_files_properties_copy, null);
        if (canModify && !FileManagerUtils.isSymbolicLink(file)) {
            builder.setNeutralButton(R.string.opencode_files_permissions, null);
        }
        dialog = builder.create();
        dialog.setOnShowListener(d -> {
            android.widget.Button copy = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (copy != null) copy.setOnClickListener(v -> copyDetails());
            android.widget.Button permissions = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (permissions != null) permissions.setOnClickListener(v -> promptPermissions());
        });
        dialog.setOnDismissListener(d -> executor.shutdownNow());
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        }
        loadDetails();
    }

    private void buildBasicRows() {
        boolean symlink = FileManagerUtils.isSymbolicLink(file);
        mode = FileManagerUtils.modeOf(file);

        addRow(R.string.opencode_files_properties_name, file.getName());
        addRow(R.string.opencode_files_properties_path, file.getAbsolutePath());
        File parent = file.getParentFile();
        if (parent != null) {
            addRow(R.string.opencode_files_properties_parent, parent.getAbsolutePath());
        }
        addRow(R.string.opencode_files_properties_type, describeType(symlink));

        if (symlink) {
            String target = FileManagerUtils.symlinkTarget(file);
            addRow(R.string.opencode_files_properties_link_target,
                target == null ? "?" : target);
            addRow(R.string.opencode_files_properties_link_broken,
                file.exists() ? activity.getString(R.string.opencode_files_properties_no)
                    : activity.getString(R.string.opencode_files_properties_yes));
        }

        if (file.isDirectory() && !symlink) {
            contentsValue = addRow(R.string.opencode_files_properties_contents,
                activity.getString(R.string.opencode_files_properties_calculating));
        } else {
            addRow(R.string.opencode_files_properties_size,
                OpenCodeInstallDialog.formatSize(Math.max(0, file.length())));
        }

        String modified = FileManagerUtils.formatDate(file.lastModified());
        addRow(R.string.opencode_files_properties_modified,
            modified == null ? activity.getString(R.string.opencode_files_time_unknown)
                : modified);

        String owner = FileManagerUtils.ownerGroup(file);
        if (owner != null) {
            addRow(R.string.opencode_files_properties_owner, owner);
        }
        modeValue = addRow(R.string.opencode_files_properties_mode, describeMode());
        String selinux = FileManagerUtils.selinuxContext(file);
        if (selinux != null && !selinux.isEmpty()) {
            addRow(R.string.opencode_files_properties_selinux, selinux);
        }

        if (file.isFile()) {
            md5Value = addRow("MD5",
                activity.getString(R.string.opencode_files_properties_calculating));
            sha1Value = addRow("SHA-1",
                activity.getString(R.string.opencode_files_properties_calculating));
            sha256Value = addRow("SHA-256",
                activity.getString(R.string.opencode_files_properties_calculating));
        }

        if (FileManagerUtils.isZipArchive(file)) {
            addRow(R.string.opencode_files_properties_archive,
                activity.getString(R.string.opencode_files_properties_yes));
        }
        loadApkInfo();
        loadImageInfo();
    }

    private String describeType(boolean symlink) {
        if (symlink) return activity.getString(R.string.opencode_files_kind_link);
        if (file.isDirectory()) return activity.getString(R.string.opencode_files_kind_dir);
        String mime = FileManagerUtils.mimeType(file);
        return activity.getString(R.string.opencode_files_properties_type_value,
            activity.getString(R.string.opencode_files_kind_file), mime);
    }

    private String describeMode() {
        String symbolic = FileManagerUtils.modeString(file);
        if (symbolic == null) return activity.getString(R.string.opencode_files_size_unknown);
        return symbolic + "  (" + FileManagerUtils.octal(mode) + ")";
    }

    private TextView addRow(int labelRes, String value) {
        return addRow(activity.getString(labelRes), value);
    }

    private TextView addRow(String label, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(4);
        row.setPadding(0, padding, 0, padding);

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextSize(11);
        labelView.setTextColor(MaterialColors.getColor(activity, R.attr.opencodeColorMuted,
            ContextCompat.getColor(activity, R.color.github_muted)));

        TextView valueView = new TextView(activity);
        valueView.setText(value == null ? "" : value);
        valueView.setTextSize(14);
        valueView.setTextIsSelectable(true);
        valueView.setLineSpacing(dp(2), 1f);

        row.addView(labelView);
        row.addView(valueView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rows.add(new Row(label, valueView));
        return valueView;
    }

    private void loadDetails() {
        if (file.isDirectory() && !FileManagerUtils.isSymbolicLink(file)) {
            setBusy(true, activity.getString(R.string.opencode_files_properties_calculating));
            executor.execute(() -> {
                FileManagerUtils.SizeInfo info =
                    FileManagerUtils.measure(file, FileManagerUtils.MEASURE_LIMIT);
                activity.runOnUiThread(() -> {
                    if (!isAlive()) return;
                    setBusy(false, null);
                    if (contentsValue != null) {
                        contentsValue.setText(describeContents(info));
                    }
                });
            });
        }

        if (file.isFile()) {
            executor.execute(() -> {
                FileManagerUtils.Checksums checksums = null;
                try {
                    checksums = FileManagerUtils.checksums(file);
                } catch (Throwable ignored) {
                    // The rows keep a readable error below.
                }
                final FileManagerUtils.Checksums result = checksums;
                activity.runOnUiThread(() -> {
                    if (!isAlive()) return;
                    if (result == null) {
                        setChecksum(md5Value, null);
                        setChecksum(sha1Value, null);
                        setChecksum(sha256Value, null);
                    } else {
                        setChecksum(md5Value, result.md5);
                        setChecksum(sha1Value, result.sha1);
                        setChecksum(sha256Value, result.sha256);
                    }
                });
            });
        }
    }

    private String describeContents(FileManagerUtils.SizeInfo info) {
        String text = activity.getString(R.string.opencode_files_properties_contents_value,
            info.files, info.directories, OpenCodeInstallDialog.formatSize(info.size));
        if (info.truncated) {
            text += " " + activity.getString(R.string.opencode_files_properties_partial);
        }
        return text;
    }

    private void setChecksum(TextView view, String value) {
        if (view == null) return;
        view.setText(value == null
            ? activity.getString(R.string.opencode_files_properties_unavailable) : value);
    }

    private void loadApkInfo() {
        if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) return;
        try {
            PackageManager manager = activity.getPackageManager();
            PackageInfo info = manager.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (info == null) return;
            addRow(R.string.opencode_files_properties_package, info.packageName);
            String version = info.versionName == null ? "?" : info.versionName;
            addRow(R.string.opencode_files_properties_version,
                version + " (" + info.versionCode + ")");
            if (info.applicationInfo != null) {
                try {
                    CharSequence label = manager.getApplicationLabel(info.applicationInfo);
                    if (label != null && label.length() > 0) {
                        addRow(R.string.opencode_files_properties_app_label, label.toString());
                    }
                } catch (Throwable ignored) {
                    // The label is optional.
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addRow(R.string.opencode_files_properties_sdk,
                        info.applicationInfo.minSdkVersion + " / "
                            + info.applicationInfo.targetSdkVersion);
                }
            }
        } catch (Throwable ignored) {
            // A broken or unsupported APK simply gets no extra rows.
        }
    }

    private void loadImageInfo() {
        if (!file.isFile()) return;
        String mime = FileManagerUtils.mimeType(file);
        if (!mime.startsWith("image/")) return;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth > 0 && options.outHeight > 0) {
                addRow(R.string.opencode_files_properties_image_size,
                    options.outWidth + " × " + options.outHeight);
            }
        } catch (Throwable ignored) {
            // Image dimensions are optional.
        }
    }

    private void promptPermissions() {
        if (!canModify) return;
        if (FileManagerUtils.isSymbolicLink(file)) {
            toast(R.string.opencode_files_properties_link_permissions);
            return;
        }
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("755");
        input.setText(FileManagerUtils.octal(mode));
        input.setSelection(input.getText().length());

        FrameLayout wrapper = new FrameLayout(activity);
        int padding = dp(20);
        wrapper.setPadding(padding, dp(6), padding, 0);
        wrapper.addView(input, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog permissions = new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_permissions)
            .setMessage(activity.getString(R.string.opencode_files_permissions_message,
                FileManagerUtils.octal(mode)))
            .setView(wrapper)
            .setPositiveButton(R.string.opencode_files_apply, null)
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .create();
        permissions.setOnShowListener(d -> permissions.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String text = input.getText() == null ? "" : input.getText().toString();
                int requested = FileManagerUtils.parseOctalMode(text);
                if (requested == FileManagerUtils.UNKNOWN) {
                    toast(R.string.opencode_files_permissions_invalid);
                    return;
                }
                permissions.dismiss();
                applyMode(requested);
            }));
        permissions.show();
    }

    private void applyMode(final int requested) {
        setBusy(true, activity.getString(R.string.opencode_files_permissions_applying));
        executor.execute(() -> {
            String error = null;
            try {
                FileManagerUtils.setMode(file, requested);
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (!isAlive()) return;
                setBusy(false, null);
                if (failure != null) {
                    toast(activity.getString(R.string.opencode_files_permissions_failed,
                        failure == null ? "" : failure));
                    return;
                }
                mode = requested;
                if (modeValue != null) modeValue.setText(describeMode());
                toast(R.string.opencode_files_permissions_applied);
                if (listener != null) listener.onChanged(file);
            });
        });
    }

    private void copyDetails() {
        StringBuilder builder = new StringBuilder();
        for (Row row : rows) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(row.label).append(": ").append(row.value.getText());
        }
        try {
            ClipboardManager manager =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText(
                    activity.getString(R.string.opencode_files_properties_title), builder));
                toast(R.string.opencode_files_properties_copied);
            }
        } catch (Throwable ignored) {
            // The clipboard is only a convenience.
        }
    }

    private void setBusy(boolean busy, String message) {
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (status != null) {
            status.setText(message == null
                ? activity.getString(R.string.opencode_files_properties_title) : message);
        }
    }

    private boolean isAlive() {
        return dialog != null && dialog.isShowing()
            && !activity.isFinishing() && !activity.isDestroyed();
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(int resId) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
