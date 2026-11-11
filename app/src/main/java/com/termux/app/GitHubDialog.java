package com.termux.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The GitHub screen of the file manager: install {@code git} and {@code gh}, sign in with the
 * GitHub device flow, browse the repositories of the signed-in account and clone one into the
 * folder the file manager is showing.
 *
 * <p>It is a full screen screen with the same header as the file manager, so GitHub lives inside
 * the file manager instead of on its own. Cloning targets the directory that was open when the
 * screen was launched, and the file manager is told to reload afterwards.</p>
 */
final class GitHubDialog {

    /** Receives the actions that the screen cannot perform on its own. */
    interface Listener {
        /**
         * Called after the GitHub account changed (sign in or sign out) so the app can restart
         * the native OpenCode service with the new credentials.
         */
        void onAccountChanged();

        /** Called after a repository was cloned into {@code directory}. */
        void onCloned(File directory);
    }

    private static final String CLIENT_ID = "178c6fc778ccc68e1d6a";
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String DEVICE_VERIFY_URL = "https://github.com/login/device";
    /**
     * Ask for the full set of scopes that the GitHub CLI and Copilot can use. GitHub rejects the
     * whole request with {@code invalid_scope} when a single unknown scope is sent, so only
     * documented scopes are listed here.
     */
    private static final String SCOPES =
        "repo workflow write:packages read:packages delete:packages security_events "
            + "admin:repo_hook write:repo_hook read:repo_hook "
            + "admin:org write:org read:org "
            + "admin:public_key write:public_key read:public_key "
            + "admin:gpg_key write:gpg_key read:gpg_key "
            + "admin:ssh_signing_key write:ssh_signing_key read:ssh_signing_key "
            + "gist notifications user read:user user:email user:follow "
            + "project read:project delete_repo "
            + "write:discussion read:discussion codespace copilot";

    private static final int MENU_CLONE = 1;
    private static final int MENU_COPY_URL = 2;
    private static final int MENU_OPEN = 3;

    private static final int MENU_LOGOUT = 11;
    private static final int MENU_MANUAL_CLONE = 12;
    private static final int MENU_RELOAD = 13;

    private final Activity activity;
    private final File targetDirectory;
    private final Listener listener;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private Dialog dialog;
    private TextView subtitleView;
    private TextView statusView;
    private TextView chipView;
    private TextView userView;
    private TextView codeView;
    private TextView linkView;
    private TextView hintView;
    private TextView installStatusView;
    private TextView repoStatusView;
    private TextView emptyView;
    private ProgressBar progressView;
    private ImageButton closeButton;
    private ImageButton refreshButton;
    private ImageButton moreButton;
    private ImageButton repoRefreshButton;
    private Button copyButton;
    private Button installButton;
    private Button loginButton;
    private Button logoutButton;
    private View installCard;
    private View repoSection;
    private LinearLayout repoList;
    private FloatingActionButton fab;

    private volatile String deviceCodeValue;
    private volatile boolean installing;
    private volatile boolean loggingIn;
    private volatile boolean loggedIn;
    private volatile String account;
    private int stateGeneration;

    private GitHubDialog(Activity activity, File targetDirectory, Listener listener) {
        this.activity = activity;
        this.targetDirectory = targetDirectory;
        this.listener = listener;
    }

    /** Show the GitHub screen on top of the file manager. Must run on the UI thread. */
    static void show(Activity activity, File targetDirectory, Listener listener) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        GitHubDialog screen = new GitHubDialog(activity, targetDirectory, listener);
        screen.show();
    }

    private void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void show() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_github, null, false);
        subtitleView = view.findViewById(R.id.opencode_github_subtitle);
        statusView = view.findViewById(R.id.opencode_github_status);
        chipView = view.findViewById(R.id.opencode_github_chip);
        userView = view.findViewById(R.id.opencode_github_user);
        codeView = view.findViewById(R.id.opencode_github_code);
        linkView = view.findViewById(R.id.opencode_github_link);
        hintView = view.findViewById(R.id.opencode_github_hint);
        installStatusView = view.findViewById(R.id.opencode_github_install_status);
        repoStatusView = view.findViewById(R.id.opencode_github_repo_status);
        emptyView = view.findViewById(R.id.opencode_github_empty);
        progressView = view.findViewById(R.id.opencode_github_progress);
        closeButton = view.findViewById(R.id.opencode_github_close);
        refreshButton = view.findViewById(R.id.opencode_github_refresh);
        moreButton = view.findViewById(R.id.opencode_github_more);
        repoRefreshButton = view.findViewById(R.id.opencode_github_repo_refresh);
        copyButton = view.findViewById(R.id.opencode_github_copy_button);
        installButton = view.findViewById(R.id.opencode_github_install_button);
        loginButton = view.findViewById(R.id.opencode_github_login_button);
        logoutButton = view.findViewById(R.id.opencode_github_logout_button);
        installCard = view.findViewById(R.id.opencode_github_install_card);
        repoSection = view.findViewById(R.id.opencode_github_repo_section);
        repoList = view.findViewById(R.id.opencode_github_repo_list);
        fab = view.findViewById(R.id.opencode_github_fab);

        closeButton.setOnClickListener(v -> dismiss());
        refreshButton.setOnClickListener(v -> loadState());
        moreButton.setOnClickListener(v -> showMenu());
        installButton.setOnClickListener(v -> install());
        loginButton.setOnClickListener(v -> login());
        logoutButton.setOnClickListener(v -> logout());
        copyButton.setOnClickListener(v -> copyCode());
        if (repoRefreshButton != null) repoRefreshButton.setOnClickListener(v -> loadRepos());
        if (fab != null) fab.setOnClickListener(v -> promptManualClone());

        dialog = new Dialog(activity, R.style.Theme_Opencode_FullScreenDialog);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            executor.shutdownNow();
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK || event.getAction() != KeyEvent.ACTION_UP) {
                return false;
            }
            dismiss();
            return true;
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        }

        loadState();
    }

    /* ==========================================================================================
     * State
     * ========================================================================================== */

    private void loadState() {
        final int generation = ++stateGeneration;
        setBusy(true);
        if (repoStatusView != null && repoList != null && repoList.getChildCount() == 0) {
            repoStatusView.setText(R.string.opencode_github_checking);
        }
        executor.execute(() -> {
            boolean gitOk = isGitInstalled();
            boolean ghOk = isGhInstalled();
            boolean signedIn = ghOk && isGhLoggedIn();
            String who = null;
            if (signedIn) {
                GhResult whoResult = runGitHubResult(new String[]{"api", "user", "-q", ".login"});
                if (whoResult.isSuccess()) {
                    String candidate = whoResult.output.trim();
                    if (isGitHubUsername(candidate)) who = candidate;
                }
                if (who == null) signedIn = false;
            }
            final boolean gitOkF = gitOk;
            final boolean ghOkF = ghOk;
            final boolean signedInF = signedIn && who != null && !who.isEmpty();
            final String whoF = who;
            activity.runOnUiThread(() -> {
                if (!isAlive() || generation != stateGeneration) return;
                setBusy(false);
                loggedIn = signedInF;
                account = whoF;
                if (subtitleView != null) {
                    subtitleView.setText(signedInF
                        ? activity.getString(R.string.opencode_settings_github_connected, whoF)
                        : activity.getString(R.string.opencode_github_not_logged_in));
                }
                if (!gitOkF || !ghOkF) {
                    showInstallState(gitOkF, ghOkF);
                    return;
                }
                if (installCard != null) installCard.setVisibility(View.GONE);
                if (!signedInF) {
                    if (statusView != null) {
                        statusView.setText(R.string.opencode_github_ready);
                    }
                    setChip(R.string.opencode_github_chip_ready, false);
                    showLoggedOut();
                    return;
                }
                if (statusView != null) statusView.setText(R.string.opencode_github_connected_status);
                setChip(R.string.opencode_github_chip_connected, true);
                showLoggedIn();
                loadRepos();
            });
        });
    }

    private void showInstallState(boolean gitOk, boolean ghOk) {
        if (statusView != null) statusView.setText(R.string.opencode_github_missing_tools);
        setChip(R.string.opencode_github_chip_missing, false);
        if (installCard != null) installCard.setVisibility(View.VISIBLE);
        if (installStatusView != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("git: ").append(gitOk ? "đã cài" : "chưa cài").append("  ·  ");
            sb.append("gh: ").append(ghOk ? "đã cài" : "chưa cài");
            installStatusView.setText(sb.toString());
            installStatusView.setVisibility(View.VISIBLE);
        }
        if (loginButton != null) loginButton.setVisibility(View.GONE);
        if (logoutButton != null) logoutButton.setVisibility(View.GONE);
        if (userView != null) userView.setVisibility(View.GONE);
        if (repoSection != null) repoSection.setVisibility(View.GONE);
        if (emptyView != null) emptyView.setVisibility(View.GONE);
        if (fab != null) fab.setVisibility(View.GONE);
    }

    private void showLoggedOut() {
        if (installCard != null) installCard.setVisibility(View.GONE);
        if (loginButton != null) {
            loginButton.setVisibility(View.VISIBLE);
            loginButton.setEnabled(true);
            loginButton.setText(R.string.opencode_settings_github_connect);
        }
        if (logoutButton != null) logoutButton.setVisibility(View.GONE);
        if (userView != null) userView.setVisibility(View.GONE);
        if (repoSection != null) repoSection.setVisibility(View.GONE);
        if (emptyView != null) {
            emptyView.setText(R.string.opencode_github_sign_in_hint);
            emptyView.setVisibility(View.VISIBLE);
        }
        if (fab != null) fab.setVisibility(View.GONE);
    }

    private void showLoggedIn() {
        if (installCard != null) installCard.setVisibility(View.GONE);
        if (loginButton != null) loginButton.setVisibility(View.GONE);
        if (logoutButton != null) logoutButton.setVisibility(View.VISIBLE);
        if (userView != null && account != null) {
            userView.setText(activity.getString(R.string.opencode_github_account, account));
            userView.setVisibility(View.VISIBLE);
        }
        if (repoSection != null) repoSection.setVisibility(View.VISIBLE);
        if (emptyView != null) emptyView.setVisibility(View.GONE);
        if (fab != null) fab.setVisibility(View.VISIBLE);
    }

    private void setChip(int textRes, boolean connected) {
        if (chipView == null) return;
        chipView.setText(textRes);
        int background = MaterialColors.getColor(activity,
            connected ? R.attr.opencodeColorChipOk : R.attr.opencodeColorChipOff,
            ContextCompat.getColor(activity,
                connected ? R.color.github_chip_ok_bg : R.color.github_chip_off_bg));
        chipView.setBackgroundTintList(ColorStateList.valueOf(background));
        chipView.setTextColor(MaterialColors.getColor(activity,
            connected ? R.attr.opencodeColorAccent : R.attr.opencodeColorMuted,
            ContextCompat.getColor(activity,
                connected ? R.color.github_accent : R.color.github_muted)));
    }

    private void setBusy(boolean busy) {
        if (progressView != null) progressView.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (refreshButton != null) refreshButton.setEnabled(!busy);
    }

    /* ==========================================================================================
     * Install, login and logout
     * ========================================================================================== */

    private void install() {
        if (installing) return;
        installing = true;
        setBusy(true);
        if (installButton != null) {
            installButton.setEnabled(false);
            installButton.setText(R.string.opencode_github_installing);
        }
        if (installStatusView != null) {
            installStatusView.setVisibility(View.VISIBLE);
            installStatusView.setText("pkg install -y git gh");
        }
        if (statusView != null) statusView.setText(R.string.opencode_github_installing_status);
        setChip(R.string.opencode_github_chip_installing, false);
        executor.execute(() -> {
            final StringBuilder liveLog = new StringBuilder();
            final long[] lastUiUpdate = new long[]{0L};
            String out = runShellStreaming("pkg install -y git gh", 900000L, line -> {
                synchronized (liveLog) {
                    liveLog.append(line).append('\n');
                }
                long now = SystemClock.uptimeMillis();
                if (now - lastUiUpdate[0] < 200L) return;
                lastUiUpdate[0] = now;
                final String tail = tailOf(liveLog.toString(), 500);
                activity.runOnUiThread(() -> {
                    if (installStatusView != null) {
                        installStatusView.setVisibility(View.VISIBLE);
                        installStatusView.setText("pkg install -y git gh\n" + tail);
                    }
                });
            });
            installing = false;
            final String shown = out == null ? "" : out.trim();
            activity.runOnUiThread(() -> {
                if (installButton != null) {
                    installButton.setEnabled(true);
                    installButton.setText(R.string.opencode_github_install_again);
                }
                if (installStatusView != null && !shown.isEmpty()) {
                    String s = shown.length() > 400 ? shown.substring(0, 400) + "..." : shown;
                    installStatusView.setText("pkg install -y git gh\n" + s);
                }
                if (!isAlive()) return;
                loadState();
            });
        });
    }

    private void login() {
        if (loggingIn) return;
        loggingIn = true;
        setBusy(true);
        if (loginButton != null) {
            loginButton.setEnabled(false);
            loginButton.setText(R.string.opencode_github_fetching_code);
        }
        deviceCodeValue = null;
        if (codeView != null) { codeView.setVisibility(View.GONE); codeView.setText(""); }
        if (copyButton != null) copyButton.setVisibility(View.GONE);
        if (linkView != null) { linkView.setVisibility(View.GONE); linkView.setText(""); }
        if (hintView != null) { hintView.setVisibility(View.GONE); hintView.setText(""); }
        if (statusView != null) statusView.setText(R.string.opencode_github_fetching_code);
        setChip(R.string.opencode_github_chip_signing_in, false);
        executor.execute(() -> {
            try {
                if (!isGitInstalled() || !isGhInstalled()) {
                    activity.runOnUiThread(() -> {
                        if (statusView != null) statusView.setText(R.string.opencode_github_installing_before_login);
                        setChip(R.string.opencode_github_chip_installing, false);
                    });
                    runShellStreaming("pkg install -y git gh", 900000L, null);
                    if (!isGitInstalled() || !isGhInstalled()) {
                        throw new IllegalStateException(
                            "Không tải được git/gh. Bấm Tải git & gh rồi thử lại.");
                    }
                }

                final DeviceCode device = requestDeviceCode();
                deviceCodeValue = device.userCode;
                activity.runOnUiThread(() -> {
                    if (codeView != null) {
                        codeView.setText(device.userCode);
                        codeView.setVisibility(View.VISIBLE);
                    }
                    if (copyButton != null) copyButton.setVisibility(View.VISIBLE);
                    if (statusView != null) {
                        statusView.setText(activity.getString(
                            R.string.opencode_github_enter_code, device.userCode));
                    }
                    if (loginButton != null) loginButton.setText(R.string.opencode_github_waiting_auth);
                    if (linkView != null) {
                        linkView.setText(device.verificationUri);
                        linkView.setVisibility(View.VISIBLE);
                    }
                    if (hintView != null) {
                        long minutes = Math.max(1L, device.expiresIn / 60L);
                        hintView.setText(activity.getString(
                            R.string.opencode_github_code_hint, minutes));
                        hintView.setVisibility(View.VISIBLE);
                    }
                    setChip(R.string.opencode_github_chip_waiting, false);
                    // Put the code on the clipboard before leaving for the browser, so the user
                    // only has to paste it into the GitHub device page.
                    copyText(device.userCode);
                    openExternal(device.verificationUri);
                });

                final String token = pollAccessToken(device);
                GhResult authResult = runGitHubWithInput(
                    new String[]{"auth", "login", "--with-token", "-h", "github.com"}, token);
                if (!authResult.isSuccess()) {
                    String detail = authResult.output.trim();
                    if (detail.length() > 200) detail = detail.substring(0, 200) + "...";
                    throw new IllegalStateException("Không lưu được token vào gh"
                        + (detail.isEmpty() ? "." : ": " + detail));
                }
                runGitHub(new String[]{"auth", "setup-git"});
                final boolean ok = isGhLoggedIn();
                loggingIn = false;
                activity.runOnUiThread(() -> {
                    setBusy(false);
                    if (loginButton != null) {
                        loginButton.setEnabled(true);
                        loginButton.setText(R.string.opencode_settings_github_connect);
                    }
                    if (!isAlive()) return;
                    if (ok) {
                        toast(R.string.opencode_github_login_success);
                        notifyAccountChanged();
                        loadState();
                    } else {
                        if (statusView != null) {
                            statusView.setText(R.string.opencode_github_login_not_recorded);
                        }
                        setChip(R.string.opencode_github_chip_not_logged_in, false);
                    }
                });
            } catch (Exception ex) {
                loggingIn = false;
                final String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                activity.runOnUiThread(() -> {
                    setBusy(false);
                    if (loginButton != null) {
                        loginButton.setEnabled(true);
                        loginButton.setText(R.string.opencode_github_login_again);
                    }
                    if (!isAlive()) return;
                    if (statusView != null) {
                        statusView.setText(activity.getString(
                            R.string.opencode_github_login_failed, message));
                    }
                    setChip(R.string.opencode_github_chip_login_error, false);
                    if (hintView != null) {
                        hintView.setText(R.string.opencode_github_login_retry_hint);
                        hintView.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void logout() {
        setBusy(true);
        executor.execute(() -> {
            runGitHub(new String[]{"auth", "logout", "-h", "github.com"});
            activity.runOnUiThread(() -> {
                setBusy(false);
                if (!isAlive()) return;
                toast(R.string.opencode_github_logout_done);
                notifyAccountChanged();
                loadState();
            });
        });
    }

    private void notifyAccountChanged() {
        if (listener != null) listener.onAccountChanged();
    }

    /* ==========================================================================================
     * Repositories
     * ========================================================================================== */

    private void loadRepos() {
        if (repoList == null) return;
        if (repoStatusView != null) {
            repoStatusView.setVisibility(View.VISIBLE);
            repoStatusView.setText(R.string.opencode_github_repos_loading);
        }
        if (repoRefreshButton != null) repoRefreshButton.setEnabled(false);
        executor.execute(() -> {
            GhResult result = runGitHubResult(new String[]{
                "repo", "list", "--limit", "100",
                "--json", "nameWithOwner,description,isPrivate,url"
            });
            JSONArray repos = null;
            String error = null;
            if (result.isSuccess()) {
                try {
                    String text = result.output.trim();
                    int start = text.indexOf('[');
                    int end = text.lastIndexOf(']');
                    if (start >= 0 && end > start) text = text.substring(start, end + 1);
                    repos = new JSONArray(text);
                } catch (Exception ex) {
                    error = "Không đọc được danh sách repository.";
                }
            } else {
                String detail = result.output.trim();
                if (detail.length() > 200) detail = detail.substring(0, 200) + "...";
                error = detail.isEmpty() ? "Không tải được danh sách repository." : detail;
            }
            final JSONArray reposF = repos;
            final String errorF = error;
            activity.runOnUiThread(() -> {
                if (!isAlive()) return;
                if (repoRefreshButton != null) repoRefreshButton.setEnabled(true);
                if (reposF == null) {
                    if (repoStatusView != null) {
                        repoStatusView.setVisibility(View.VISIBLE);
                        repoStatusView.setText(activity.getString(
                            R.string.opencode_github_repos_error, errorF));
                    }
                    return;
                }
                populateRepos(reposF);
            });
        });
    }

    private void populateRepos(JSONArray repos) {
        if (repoList == null) return;
        repoList.removeAllViews();
        if (repos.length() == 0) {
            if (repoStatusView != null) {
                repoStatusView.setVisibility(View.VISIBLE);
                repoStatusView.setText(R.string.opencode_github_repos_empty);
            }
            return;
        }
        if (repoStatusView != null) {
            repoStatusView.setVisibility(View.VISIBLE);
            repoStatusView.setText(activity.getString(
                R.string.opencode_github_repos_count, repos.length()));
        }
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = 0; i < repos.length(); i++) {
            JSONObject repo = repos.optJSONObject(i);
            if (repo == null) continue;
            final String name = repo.optString("nameWithOwner", "");
            if (name.isEmpty()) continue;
            String description = repo.optString("description", "");
            boolean isPrivate = repo.optBoolean("isPrivate", false);
            final String cloneUrl = "https://github.com/" + name + ".git";

            View item = inflater.inflate(R.layout.item_github_repo, repoList, false);
            TextView nameView = item.findViewById(R.id.github_repo_name);
            TextView visibilityView = item.findViewById(R.id.github_repo_visibility);
            TextView descriptionView = item.findViewById(R.id.github_repo_description);
            ImageButton moreView = item.findViewById(R.id.github_repo_more);

            if (nameView != null) nameView.setText(name);
            if (visibilityView != null) {
                visibilityView.setText(isPrivate
                    ? R.string.opencode_github_private : R.string.opencode_github_public);
                visibilityView.setTextColor(MaterialColors.getColor(activity,
                    isPrivate ? R.attr.opencodeColorAccent : R.attr.opencodeColorMuted,
                    ContextCompat.getColor(activity,
                        isPrivate ? R.color.github_accent : R.color.github_muted)));
            }
            if (descriptionView != null) {
                if (description == null || description.trim().isEmpty()
                        || "null".equals(description)) {
                    descriptionView.setVisibility(View.GONE);
                } else {
                    descriptionView.setText(description);
                    descriptionView.setVisibility(View.VISIBLE);
                }
            }
            item.setOnClickListener(v -> clone(cloneUrl, name));
            if (moreView != null) {
                moreView.setOnClickListener(v -> showRepoMenu(v, name, cloneUrl));
            }
            repoList.addView(item);
        }
    }

    private void showRepoMenu(View anchor, final String name, final String cloneUrl) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, MENU_CLONE, 0, R.string.opencode_github_clone);
        menu.getMenu().add(0, MENU_COPY_URL, 1, R.string.opencode_github_copy_url);
        menu.getMenu().add(0, MENU_OPEN, 2, R.string.opencode_github_open_in_browser);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_CLONE:
                    clone(cloneUrl, name);
                    return true;
                case MENU_COPY_URL:
                    copyText("https://github.com/" + name);
                    return true;
                case MENU_OPEN:
                    openExternal("https://github.com/" + name);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void promptManualClone() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.opencode_settings_github_url);
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_github_clone_manual)
            .setMessage(activity.getString(R.string.opencode_github_clone_into,
                cloneDirectory().getAbsolutePath()))
            .setView(input)
            .setPositiveButton(R.string.opencode_github_clone, (d, which) -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                if (value.isEmpty()) return;
                clone(normalizeCloneUrl(value), null);
            })
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show();
    }

    private void clone(final String rawUrl, final String repoName) {
        final String url = normalizeCloneUrl(rawUrl);
        if (url.isEmpty()) return;
        final File directory = cloneDirectory();
        final String label = repoName == null || repoName.isEmpty() ? url : repoName;
        if (repoStatusView != null) {
            repoStatusView.setVisibility(View.VISIBLE);
            repoStatusView.setText(activity.getString(R.string.opencode_github_cloning, label));
        }
        toast(activity.getString(R.string.opencode_github_cloning, label));
        executor.execute(() -> {
            final String cmd = "cd " + shellQuote(directory.getAbsolutePath())
                + " && GIT_TERMINAL_PROMPT=0 git clone --progress -- " + shellQuote(url);
            final StringBuilder liveLog = new StringBuilder();
            final long[] lastUiUpdate = new long[]{0L};
            String out = runShellStreaming(cmd, 900000L, line -> {
                synchronized (liveLog) {
                    liveLog.append(line).append('\n');
                }
                long now = SystemClock.uptimeMillis();
                if (now - lastUiUpdate[0] < 200L) return;
                lastUiUpdate[0] = now;
                final String tail = tailOf(liveLog.toString(), 500);
                activity.runOnUiThread(() -> {
                    if (repoStatusView != null) {
                        repoStatusView.setVisibility(View.VISIBLE);
                        repoStatusView.setText("git clone\n" + tail);
                    }
                });
            });
            final String trimmed = out == null ? "" : out.trim();
            final File cloned = new File(directory, repoNameFromUrl(url));
            final boolean ok = cloned.isDirectory()
                && !trimmed.toLowerCase().contains("fatal:");
            activity.runOnUiThread(() -> {
                if (repoStatusView != null && ok) {
                    repoStatusView.setText(activity.getString(
                        R.string.opencode_github_repos_count, repoCount()));
                }
                toast(ok ? R.string.opencode_github_clone_done
                    : R.string.opencode_github_clone_failed);
                if (!isAlive()) return;
                if (ok && listener != null) {
                    listener.onCloned(cloned.isDirectory() ? cloned : directory);
                }
            });
        });
    }

    private int repoCount() {
        return repoList == null ? 0 : repoList.getChildCount();
    }

    /** The folder a clone goes into: the one the file manager showed, or HOME as a fallback. */
    private File cloneDirectory() {
        if (targetDirectory != null && targetDirectory.isDirectory()
                && targetDirectory.canWrite()) {
            return targetDirectory;
        }
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    private String normalizeCloneUrl(String repo) {
        String url = repo == null ? "" : repo.trim();
        if (url.isEmpty()) return url;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("github.com/") || url.startsWith("www.github.com/")) {
            url = "https://" + url;
        } else if (!url.contains("://") && !url.startsWith("git@") && url.indexOf("/") > 0) {
            url = "https://github.com/" + url;
        }
        if (url.startsWith("https://github.com/") && !url.endsWith(".git")) {
            url += ".git";
        }
        return url;
    }

    private static String repoNameFromUrl(String url) {
        String value = url == null ? "" : url.trim();
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        return name;
    }

    /* ==========================================================================================
     * Menu, copy and links
     * ========================================================================================== */

    private void showMenu() {
        if (moreButton == null) return;
        PopupMenu menu = new PopupMenu(activity, moreButton);
        menu.getMenu().add(0, MENU_RELOAD, 0, R.string.opencode_files_refresh);
        menu.getMenu().add(0, MENU_MANUAL_CLONE, 1, R.string.opencode_github_clone_manual);
        if (loggedIn) {
            menu.getMenu().add(0, MENU_LOGOUT, 2, R.string.opencode_settings_github_disconnect);
        }
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_RELOAD:
                    loadState();
                    return true;
                case MENU_MANUAL_CLONE:
                    promptManualClone();
                    return true;
                case MENU_LOGOUT:
                    logout();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void copyCode() {
        String code = deviceCodeValue;
        if (code == null || code.isEmpty()) return;
        copyText(code);
    }

    private void copyText(String text) {
        ClipboardManager clipboard =
            (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("GitHub", text));
        }
        toast(R.string.opencode_settings_github_copy_done);
    }

    private void openExternal(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
        } catch (Exception ignored) {
        }
    }

    /* ==========================================================================================
     * GitHub device flow
     * ========================================================================================== */

    private static final class DeviceCode {
        final String deviceCode;
        final String userCode;
        final String verificationUri;
        final int interval;
        final long expiresIn;

        DeviceCode(String deviceCode, String userCode, String verificationUri,
                   int interval, long expiresIn) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.interval = interval;
            this.expiresIn = expiresIn;
        }
    }

    private DeviceCode requestDeviceCode() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("client_id", CLIENT_ID);
        params.put("scope", SCOPES);
        String body = httpPostForm(DEVICE_CODE_URL, params);
        JSONObject json = new JSONObject(body);
        String deviceCode = json.optString("device_code", "");
        String userCode = json.optString("user_code", "");
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            String error = json.optString("error_description", json.optString("error", body));
            throw new IllegalStateException("GitHub không trả về mã đăng nhập: " + error);
        }
        String uri = json.optString("verification_uri", DEVICE_VERIFY_URL);
        int interval = Math.max(5, json.optInt("interval", 5));
        long expiresIn = Math.max(60L, json.optLong("expires_in", 900L));
        return new DeviceCode(deviceCode, userCode, uri, interval, expiresIn);
    }

    private String pollAccessToken(DeviceCode device) throws Exception {
        long deadline = SystemClock.uptimeMillis() + device.expiresIn * 1000L;
        int interval = Math.max(5, device.interval);
        while (SystemClock.uptimeMillis() < deadline) {
            Map<String, String> params = new HashMap<>();
            params.put("client_id", CLIENT_ID);
            params.put("device_code", device.deviceCode);
            params.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            String body = httpPostForm(ACCESS_TOKEN_URL, params);
            JSONObject json = new JSONObject(body);
            String token = json.optString("access_token", "");
            if (!token.isEmpty()) return token;

            String error = json.optString("error", "");
            if ("authorization_pending".equals(error)) {
                Thread.sleep(interval * 1000L);
                continue;
            }
            if ("slow_down".equals(error)) {
                interval += 5;
                Thread.sleep(interval * 1000L);
                continue;
            }
            if ("expired_token".equals(error)) {
                throw new IllegalStateException("Mã đăng nhập đã hết hạn. Bấm Đăng nhập lại để lấy mã mới.");
            }
            if ("access_denied".equals(error)) {
                throw new IllegalStateException("Bạn đã từ chối cấp quyền cho ứng dụng.");
            }
            String description = json.optString("error_description", error);
            throw new IllegalStateException(description == null || description.isEmpty()
                ? "Không lấy được access token." : description);
        }
        throw new IllegalStateException("Hết thời gian chờ xác thực. Bấm Đăng nhập lại để lấy mã mới.");
    }

    private String httpPostForm(String url, Map<String, String> params) throws Exception {
        java.net.HttpURLConnection conn =
            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "Termux-OpenCode");

            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (body.length() > 0) body.append('&');
                body.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"));
                body.append('=');
                body.append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            try (java.io.OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new IllegalStateException("GitHub trả về HTTP " + code);
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
            if (code >= 400 && response.length() == 0) {
                throw new IllegalStateException("GitHub trả về HTTP " + code);
            }
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }

    /* ==========================================================================================
     * GitHub CLI and shell helpers
     * ========================================================================================== */

    private boolean isGitInstalled() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "git").canExecute();
    }

    private boolean isGhInstalled() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "gh").canExecute();
    }

    private boolean isGhLoggedIn() {
        GhResult r = runGitHubResult(new String[]{"auth", "status", "-h", "github.com"});
        if (!r.isSuccess()) return false;
        String lower = r.output.toLowerCase();
        // Logged out output is "You are not logged into any GitHub hosts", which contains the
        // substring "logged in", so check for the exact success wording instead.
        if (lower.contains("not logged")) return false;
        return lower.contains("logged in to github.com");
    }

    private static boolean isGitHubUsername(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || v.indexOf('\n') >= 0 || v.indexOf(' ') >= 0) return false;
        return v.matches("[A-Za-z0-9][A-Za-z0-9-]*");
    }

    private static final class GhResult {
        final int exitCode;
        final String output;

        GhResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private String runGitHub(String[] args) {
        return runGitHubResult(args).output;
    }

    private GhResult runGitHubResult(String[] args) {
        try {
            ProcessBuilder builder = new ProcessBuilder(buildGh(args));
            applyTermuxEnv(builder);
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);
            final Process process = builder.start();
            final StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "opencode-gh-runner-reader");
            reader.setDaemon(true);
            reader.start();
            int exitCode = waitFor(process, 180000L);
            reader.join(1000L);
            synchronized (output) {
                return new GhResult(exitCode, output.toString());
            }
        } catch (Exception ex) {
            return new GhResult(-1, ex.toString());
        }
    }

    /** Feed a token to gh so it is stored in gh's own credential store. */
    private GhResult runGitHubWithInput(String[] args, String input) {
        try {
            ProcessBuilder builder = new ProcessBuilder(buildGh(args));
            applyTermuxEnv(builder);
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);
            // gh refuses to persist a token while GH_TOKEN/GITHUB_TOKEN is set.
            builder.environment().remove("GH_TOKEN");
            builder.environment().remove("GITHUB_TOKEN");
            final Process process = builder.start();
            final StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "opencode-gh-input-reader");
            reader.setDaemon(true);
            reader.start();
            try (java.io.BufferedWriter stdin = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(process.getOutputStream()))) {
                stdin.write(input == null ? "" : input);
                if (input == null || !input.endsWith("\n")) stdin.write("\n");
                stdin.flush();
            } catch (Exception ignored) {
            }
            int exitCode = waitFor(process, 120000L);
            reader.join(1000L);
            synchronized (output) {
                return new GhResult(exitCode, output.toString());
            }
        } catch (Exception ex) {
            return new GhResult(-1, ex.toString());
        }
    }

    private interface StreamListener {
        void onLine(String line);
    }

    private String runShellStreaming(String cmd, long timeoutMs, final StreamListener listener) {
        try {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", cmd);
            applyTermuxEnv(builder);
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);
            final Process process = builder.start();
            final StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                        if (listener != null) {
                            try {
                                listener.onLine(line);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "opencode-gh-stream-reader");
            reader.setDaemon(true);
            reader.start();
            waitFor(process, timeoutMs);
            reader.join(1000L);
            synchronized (output) {
                return output.toString();
            }
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    private static int waitFor(Process process, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException e) {
                if (SystemClock.uptimeMillis() > deadline) {
                    process.destroy();
                    try {
                        process.waitFor();
                    } catch (Exception ignored) {
                    }
                    return -1;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroy();
                    return -1;
                }
            }
        }
    }

    private void applyTermuxEnv(ProcessBuilder builder) {
        Map<String, String> processEnv = builder.environment();
        processEnv.clear();
        try {
            processEnv.putAll(new TermuxShellEnvironment().getEnvironment(activity, false));
        } catch (Exception ignored) {
            processEnv.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            processEnv.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            String existingPath = processEnv.get("PATH");
            String path = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                + (existingPath == null || existingPath.isEmpty() ? "" : ":" + existingPath);
            processEnv.put("PATH", path);
            processEnv.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        }
        processEnv.put("TERM", "xterm-256color");
        processEnv.put("LANG", "en_US.UTF-8");
        processEnv.put("COLORTERM", "truecolor");
        processEnv.remove("LD_PRELOAD");
        processEnv.remove("LD_LIBRARY_PATH");
    }

    private String[] buildGh(String[] args) {
        String gh = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/gh";
        String head = new File(gh).exists() ? gh : "gh";
        String[] command = new String[1 + (args == null ? 0 : args.length)];
        command[0] = head;
        if (args != null) System.arraycopy(args, 0, command, 1, args.length);
        return command;
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String tailOf(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return "..." + text.substring(text.length() - maxChars);
    }

    /* ==========================================================================================
     * Helpers
     * ========================================================================================== */

    private boolean isAlive() {
        return dialog != null && dialog.isShowing()
            && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void toast(int resId) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
