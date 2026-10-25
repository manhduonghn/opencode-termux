package com.termux.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) The branded startup dialog is shown with the "Installing..." message and an indeterminate
 *     progress bar. It is kept visible while glibc and OpenCode are installed as well, and the
 *     caller dismisses it when the whole setup is finished.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** The branded startup dialog, shown until the whole setup is finished. */
    private static volatile Dialog sProgressDialog;
    /** The activity that owns {@link #sProgressDialog}. */
    private static volatile Activity sProgressActivity;
    /** True from the start of the first setup until the terminal is ready. */
    private static volatile boolean sStartupFlowActive = false;
    /** The last message shown in the startup progress dialog. */
    private static volatile String sProgressMessage;
    /** The last detail line (speed, ETA, …) shown in the startup progress dialog. */
    private static volatile String sProgressDetail;
    /** The overall percentage, or a negative value while the progress is indeterminate. */
    private static volatile int sProgressPercent = -1;
    /** Messages shown in the console box of the dialog, newest last. */
    private static final ArrayList<String> sProgressLog = new ArrayList<>();
    /** Number of lines kept in the console box. */
    private static final int MAX_PROGRESS_LOG_LINES = 6;

    /** Views of {@link #sProgressDialog}, null while the dialog is not shown. */
    private static volatile TextView sProgressMessageView;
    private static volatile TextView sProgressDetailView;
    private static volatile TextView sProgressPercentView;
    private static volatile TextView sProgressLogView;
    private static volatile ProgressBar sProgressBarView;

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        // Show the branded startup dialog and keep it visible while the rest of the setup
        // (glibc and OpenCode) runs, so the first start uses a single screen.
        showStartupProgress(activity, activity.getString(R.string.bootstrap_installer_body));

        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            dismissStartupProgress();
            try {
                new MaterialAlertDialogBuilder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    /**
     * Show the branded startup dialog, or update its message when it is already shown.
     * The dialog is not cancelled when the bootstrap packages alone have been extracted: it is
     * kept visible while glibc and OpenCode are installed and is dismissed by
     * {@link #dismissStartupProgress()} when the whole setup is finished.
     *
     * <p>It is safe to call this again when the activity was recreated (for example after the
     * user left the app and came back): the dialog of the old activity is replaced by a new one
     * that belongs to the current activity.</p>
     */
    static void showStartupProgress(final Activity activity, final String message) {
        showStartupProgress(activity, message, -1, null);
    }

    /**
     * Show or update the startup dialog with a determinate percentage when the OpenCode
     * installer reports one. A negative percentage makes the progress bar indeterminate, which
     * is what the bootstrap extraction uses because it has no byte level progress.
     */
    static void showStartupProgress(final Activity activity, final String message,
                                    final int percent, final String detail) {
        if (activity == null) return;

        sStartupFlowActive = true;
        if (message != null) {
            sProgressMessage = message;
            addProgressLogLine(message);
        }
        sProgressDetail = detail;
        sProgressPercent = percent;

        Dialog current = sProgressDialog;
        if (current != null && sProgressActivity != activity) {
            // The activity that owned the dialog is gone, replace the stale dialog.
            sProgressDialog = null;
            clearProgressViews();
            try {
                current.dismiss();
            } catch (RuntimeException e) {
                // Activity already dismissed - ignore.
            }
            current = null;
        }

        if (current != null && current.isShowing()) {
            updateProgressViews();
            return;
        }

        sProgressActivity = activity;
        try {
            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            View view = LayoutInflater.from(activity).inflate(R.layout.dialog_startup_progress, null, false);
            sProgressMessageView = view.findViewById(R.id.startup_progress_message);
            sProgressDetailView = view.findViewById(R.id.startup_progress_detail);
            sProgressPercentView = view.findViewById(R.id.startup_progress_percent);
            sProgressLogView = view.findViewById(R.id.startup_progress_log);
            sProgressBarView = view.findViewById(R.id.startup_progress_bar);

            dialog.setContentView(view);
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams params = window.getAttributes();
                params.dimAmount = 0.45f;
                window.setAttributes(params);
            }

            dialog.show();

            if (window != null) {
                DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                int width = (int) (metrics.widthPixels * 0.90f);
                int maxWidth = (int) (400 * metrics.density);
                if (width > maxWidth) width = maxWidth;
                window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            }

            sProgressDialog = dialog;
            updateProgressViews();
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not show the startup progress dialog", e);
            sProgressDialog = null;
        }
    }

    /** @return true while a startup setup is running (even if the dialog is temporarily hidden). */
    static boolean isStartupFlowActive() {
        return sStartupFlowActive;
    }

    /** @return true while the startup progress dialog of the given activity is visible. */
    static boolean isStartupProgressShowing(final Activity activity) {
        final Dialog dialog = sProgressDialog;
        return dialog != null && dialog.isShowing() && sProgressActivity == activity;
    }

    /** Update the message of the startup progress dialog, keeping the current percentage. */
    static void setStartupProgressMessage(final Activity activity, final String message) {
        if (message != null) sProgressMessage = message;
        final Dialog dialog = sProgressDialog;
        if (dialog == null || sProgressActivity != activity || !dialog.isShowing() || message == null) return;
        addProgressLogLine(message);
        updateProgressViews();
    }

    /** Update the message, percentage and detail line of the startup progress dialog. */
    static void setStartupProgress(final Activity activity, final String message,
                                   final int percent, final String detail) {
        if (activity == null) return;
        if (message != null) sProgressMessage = message;
        sProgressPercent = percent;
        sProgressDetail = detail;
        final Dialog dialog = sProgressDialog;
        if (dialog == null || sProgressActivity != activity || !dialog.isShowing()) return;
        addProgressLogLine(message);
        updateProgressViews();
    }

    private static void updateProgressViews() {
        if (sProgressMessageView != null) {
            sProgressMessageView.setText(sProgressMessage != null ? sProgressMessage : "");
        }
        if (sProgressDetailView != null) {
            boolean hasDetail = sProgressDetail != null && !sProgressDetail.trim().isEmpty();
            sProgressDetailView.setText(hasDetail ? sProgressDetail : "");
            sProgressDetailView.setVisibility(hasDetail ? View.VISIBLE : View.GONE);
        }
        if (sProgressBarView != null) {
            if (sProgressPercent >= 0) {
                sProgressBarView.setIndeterminate(false);
                sProgressBarView.setProgress(sProgressPercent);
            } else {
                sProgressBarView.setIndeterminate(true);
            }
        }
        if (sProgressPercentView != null) {
            sProgressPercentView.setText(sProgressPercent >= 0 ? (sProgressPercent + "%") : "…");
        }
        if (sProgressLogView != null) {
            sProgressLogView.setText(buildProgressLogText());
        }
    }

    /** Keep the newest lines of the setup, so the dialog shows what is being downloaded. */
    private static void addProgressLogLine(String line) {
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;
        synchronized (sProgressLog) {
            if (!sProgressLog.isEmpty() && sProgressLog.get(sProgressLog.size() - 1).equals(trimmed))
                return;
            sProgressLog.add(trimmed);
            while (sProgressLog.size() > MAX_PROGRESS_LOG_LINES)
                sProgressLog.remove(0);
        }
    }

    private static String buildProgressLogText() {
        synchronized (sProgressLog) {
            if (sProgressLog.isEmpty()) return "…";
            StringBuilder sb = new StringBuilder();
            for (String line : sProgressLog) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("› ").append(line);
            }
            return sb.toString();
        }
    }

    private static void clearProgressViews() {
        sProgressMessageView = null;
        sProgressDetailView = null;
        sProgressPercentView = null;
        sProgressLogView = null;
        sProgressBarView = null;
    }

    /**
     * Hide the dialog when its activity goes away. The setup keeps running and the dialog is
     * shown again by the next activity, so leaving the app and coming back right in the middle
     * of the installation does not lose the progress screen.
     */
    static void hideStartupProgress(final Activity activity) {
        if (activity == null || sProgressActivity != activity) return;
        final Dialog dialog = sProgressDialog;
        sProgressDialog = null;
        sProgressActivity = null;
        clearProgressViews();
        if (dialog == null) return;
        try {
            dialog.dismiss();
        } catch (RuntimeException e) {
            // Activity already dismissed - ignore.
        }
    }

    /**
     * Dismiss the startup progress dialog. It is called when the whole setup is
     * finished (or failed), not when the bootstrap packages alone have been extracted.
     */
    static void dismissStartupProgress() {
        sStartupFlowActive = false;
        sProgressMessage = null;
        sProgressDetail = null;
        sProgressPercent = -1;
        sProgressActivity = null;
        final Dialog dialog = sProgressDialog;
        sProgressDialog = null;
        clearProgressViews();
        synchronized (sProgressLog) {
            sProgressLog.clear();
        }
        if (dialog == null) return;
        try {
            dialog.dismiss();
        } catch (RuntimeException e) {
            // Activity already dismissed - ignore.
        }
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        setupStorageSymlinks(context, null);
    }

    /**
     * Create the {@code ~/storage} symlinks.
     *
     * @param onComplete Optional runnable that is executed on the worker thread after the
     *                   symlinks were created or the setup failed. The caller is responsible for
     *                   switching to the UI thread if needed.
     */
    static void setupStorageSymlinks(final Context context, final Runnable onComplete) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Android only exposes app-specific directories on secondary volumes. With
                    // the storage permission that covers secondary volumes, the root of an SD
                    // card can be used directly, so expose it as ~/storage/sdcard1, ...
                    if (StorageAccess.hasSecondaryVolumeAccess(context)) {
                        int secondaryIndex = 0;
                        for (File volumeRoot : StorageAccess.volumeRoots(context)) {
                            if (StorageAccess.isPrimaryRoot(volumeRoot)) continue;
                            secondaryIndex++;
                            String symlinkName = "sdcard" + secondaryIndex;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlink at ~/storage/" + symlinkName
                                + " for volume root \"" + volumeRoot.getAbsolutePath() + "\".");
                            Os.symlink(volumeRoot.getAbsolutePath(),
                                new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                } finally {
                    if (onComplete != null) {
                        try {
                            onComplete.run();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
