package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import androidx.core.content.ContextCompat;

import com.termux.shared.android.PermissionUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for the shared storage access that OpenCode needs to open projects outside of the
 * Termux home directory.
 *
 * <p>Termux normally exposes the primary shared storage as {@code ~/storage/shared}. On
 * Android 10 and lower the legacy storage permission covers all shared volumes, including an
 * SD card. On Android 11 and higher the root of an SD card can only be read with the
 * {@code MANAGE_EXTERNAL_STORAGE} ("All files access") special permission, while the legacy
 * {@code READ/WRITE_EXTERNAL_STORAGE} permissions still cover the primary storage because this
 * app targets SDK 28.</p>
 */
final class StorageAccess {

    private StorageAccess() {
    }

    /** Whether the app can read the primary shared storage. */
    static boolean hasLegacyStoragePermission(Context context) {
        return ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether the app has the "All files access" special permission (Android 11+). */
    static boolean hasAllFilesAccess(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && Environment.isExternalStorageManager();
    }

    /** Whether any shared storage permission is available. */
    static boolean hasStoragePermission(Context context) {
        return hasAllFilesAccess(context) || hasLegacyStoragePermission(context);
    }

    /**
     * Whether the root of a secondary volume (an SD card) is accessible.
     *
     * <p>On Android 10 and lower the legacy storage permission also covers the secondary
     * volumes. On Android 11 and higher only the "All files access" permission does.</p>
     */
    static boolean hasSecondaryVolumeAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return hasAllFilesAccess(context);
        }
        return hasLegacyStoragePermission(context);
    }

    /**
     * Open the system screen that grants the broadest storage access available on this device.
     *
     * @return {@code true} if a permission request was started.
     */
    static boolean requestStoragePermission(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return PermissionUtils.requestManageStorageExternalPermission(activity, requestCode) == null;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PermissionUtils.requestLegacyStorageExternalPermission(activity, requestCode);
        }
        return false;
    }

    /**
     * Request only the legacy runtime permissions (used as a fallback on Android 11+).
     *
     * @return {@code true} if a permission request was started.
     */
    static boolean requestLegacyStoragePermission(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PermissionUtils.requestLegacyStorageExternalPermission(activity, requestCode);
        }
        return false;
    }

    /** The primary shared storage root, normally {@code /storage/emulated/0}. */
    static File primaryRoot() {
        return Environment.getExternalStorageDirectory();
    }

    /**
     * Whether the given volume root is the primary shared storage.
     *
     * <p>Older Android versions may report the primary root as {@code /storage/self/primary}
     * instead of {@code /storage/emulated/0}, so the canonical paths are compared when possible.</p>
     */
    static boolean isPrimaryRoot(File root) {
        if (root == null) return false;
        File primary = primaryRoot();
        if (root.getAbsolutePath().equals(primary.getAbsolutePath())) return true;
        try {
            return root.getCanonicalPath().equals(primary.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Root directories of the mounted storage volumes, primary storage first.
     *
     * <p>Android gives every app an app-specific directory on each mounted volume
     * ({@code /storage/XXXX-XXXX/Android/data/<package>/files}). The volume root is derived from
     * that path so this also works below API 30, where {@code StorageVolume#getDirectory()}
     * does not exist yet.</p>
     */
    static List<File> volumeRoots(Context context) {
        List<File> roots = new ArrayList<>();
        File[] dirs = context.getExternalFilesDirs(null);
        if (dirs == null) return roots;

        for (File dir : dirs) {
            if (dir == null) continue;
            File root = volumeRootOf(dir);
            if (root == null) continue;
            boolean duplicate = false;
            for (File existing : roots) {
                if (existing.getAbsolutePath().equals(root.getAbsolutePath())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) roots.add(root);
        }
        return roots;
    }

    /** Strip {@code /Android/data/<package>/files} from an app-specific external directory. */
    static File volumeRootOf(File appDir) {
        String path = appDir.getAbsolutePath();
        int index = path.indexOf("/Android/data/");
        if (index <= 0) return null;
        return new File(path.substring(0, index));
    }
}
