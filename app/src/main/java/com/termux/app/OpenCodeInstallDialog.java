package com.termux.app;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.shared.logger.Logger;

import java.util.List;
import java.util.Locale;

/**
 * The dialog that shows the progress of the OpenCode installation.
 *
 * <p>The dialog is bound to the activity: when the activity goes away the dialog is dismissed,
 * but the installation keeps running in the background. When the user comes back to the app the
 * dialog is created again and shows the state that was saved on the disk, so the screen is never
 * empty while something is being downloaded.</p>
 */
public final class OpenCodeInstallDialog {

    private static final String LOG_TAG = "OpenCodeInstallDialog";

    /** Number of steps shown in the log box of the dialog. */
    private static final int MAX_VISIBLE_STEPS = 8;

    private Activity activity;
    private AlertDialog dialog;

    private TextView titleView;
    private TextView messageView;
    private ProgressBar progressView;
    private TextView percentView;
    private TextView bytesView;
    private TextView detailView;
    private TextView errorView;
    private TextView stepsView;

    /** Set when the user asked to hide the dialog, the installation keeps running. */
    private boolean hiddenByUser = false;

    /** Called when the user asks to retry a failed installation. */
    private Runnable onRetry = null;

    /* ==========================================================================================
     * Lifecycle
     * ========================================================================================== */

    /**
     * Show the dialog for the given activity. Does nothing when the dialog is already shown.
     *
     * @param activity the activity the dialog belongs to
     * @param onRetry  called when the user asks to retry a failed installation
     */
    public void attach(final Activity activity, final Runnable onRetry) {
        if (activity == null) return;
        if (dialog != null && dialog.isShowing() && this.activity == activity) return;

        this.activity = activity;
        this.onRetry = onRetry;
        this.hiddenByUser = false;

        try {
            View view = LayoutInflater.from(activity).inflate(R.layout.dialog_opencode_install, null, false);

            titleView = view.findViewById(R.id.opencode_install_title);
            messageView = view.findViewById(R.id.opencode_install_message);
            progressView = view.findViewById(R.id.opencode_install_progress);
            percentView = view.findViewById(R.id.opencode_install_percent);
            bytesView = view.findViewById(R.id.opencode_install_bytes);
            detailView = view.findViewById(R.id.opencode_install_detail);
            errorView = view.findViewById(R.id.opencode_install_error);
            stepsView = view.findViewById(R.id.opencode_install_steps);

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
            builder.setView(view);
            builder.setCancelable(false);
            builder.setPositiveButton(R.string.opencode_install_hide, (d, which) -> {
                hiddenByUser = true;
            });
            builder.setNegativeButton(R.string.opencode_install_retry, (d, which) -> {
                if (onRetry != null) onRetry.run();
            });

            dialog = builder.create();
            dialog.setOnDismissListener(d -> {
                // Do not touch the views of a dismissed dialog.
                dialog = null;
                titleView = null;
                messageView = null;
                progressView = null;
                percentView = null;
                bytesView = null;
                detailView = null;
                errorView = null;
                stepsView = null;
            });
            dialog.show();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not show the OpenCode install dialog", e);
            dialog = null;
        }
    }

    /** Dismiss the dialog, the installation keeps running in the background. */
    public void detach() {
        final AlertDialog current = dialog;
        dialog = null;
        titleView = null;
        messageView = null;
        progressView = null;
        percentView = null;
        bytesView = null;
        detailView = null;
        errorView = null;
        stepsView = null;

        if (current == null) return;

        try {
            current.setOnDismissListener(null);
            if (current.isShowing())
                current.dismiss();
        } catch (Exception e) {
            // The activity is gone, ignore.
        }
    }

    /** @return true when the dialog is currently shown. */
    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    /** @return true when the user asked to hide the dialog. */
    public boolean isHiddenByUser() {
        return hiddenByUser;
    }

    /* ==========================================================================================
     * Update
     * ========================================================================================== */

    /** Show the given state in the dialog. Must be called on the UI thread. */
    public void update(final OpenCodeInstallState state) {
        if (state == null || dialog == null || progressView == null) return;

        try {
            int percent = state.getOverallPercent();
            progressView.setIndeterminate(false);
            progressView.setProgress(percent);
            percentView.setText(percent + "%");

            messageView.setText(state.message == null ? "" : state.message);

            if (state.failed) {
                titleView.setText(R.string.opencode_install_failed_title);
                errorView.setVisibility(View.VISIBLE);
                errorView.setText(state.error == null ? "" : state.error);
            } else if (state.done) {
                titleView.setText(R.string.opencode_install_done_title);
                errorView.setVisibility(View.GONE);
            } else {
                titleView.setText(R.string.opencode_installing);
                errorView.setVisibility(View.GONE);
            }

            String bytes = formatBytes(state.bytesDone, state.bytesTotal);
            bytesView.setText(bytes);
            bytesView.setVisibility(bytes.isEmpty() ? View.GONE : View.VISIBLE);

            String detail = state.detail == null ? "" : state.detail;
            detailView.setText(detail);
            detailView.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);

            stepsView.setText(buildStepsText(state.getSteps()));

            // The retry button is only useful when the installation failed.
            Button retryButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            if (retryButton != null)
                retryButton.setVisibility(state.failed ? View.VISIBLE : View.GONE);

            Button hideButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (hideButton != null)
                hideButton.setText(state.failed ? R.string.opencode_install_close : R.string.opencode_install_hide);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not update the OpenCode install dialog", e);
        }
    }

    /* ==========================================================================================
     * Helpers
     * ========================================================================================== */

    /** @return the text of the log box, the last steps of the installation. */
    private static String buildStepsText(List<String> steps) {
        if (steps == null || steps.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, steps.size() - MAX_VISIBLE_STEPS);
        for (int i = from; i < steps.size(); i++) {
            String step = steps.get(i);
            if (step == null) continue;
            sb.append("› ").append(step).append('\n');
        }
        return sb.toString().trim();
    }

    /** @return a human readable size, eg. "12.3 MB / 98.7 MB". */
    public static String formatBytes(long done, long total) {
        if (total <= 0) {
            if (done <= 0) return "";
            return formatSize(done);
        }
        return formatSize(done) + " / " + formatSize(total);
    }

    /** @return a human readable size, eg. "12.3 MB". */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f kB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
