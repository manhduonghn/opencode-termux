package com.termux.app;

import android.text.TextUtils;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A snapshot of the state of the OpenCode installation.
 *
 * <p>The state is written to the disk after every change, so that the app shows the real progress
 * again after it was closed, after the activity was destroyed or after Android killed the
 * process. The screen of the app is never blank while something is being downloaded.</p>
 *
 * <p>The installation is split into stages. Every stage has a weight, the sum of all weights is
 * 100, so that the overall percentage can be computed from the percentage of the current
 * stage.</p>
 */
public final class OpenCodeInstallState {

    /* ==========================================================================================
     * Stages
     * ========================================================================================== */

    /** Nothing is being installed. */
    public static final String STAGE_IDLE = "idle";
    /** Preparing the environment (second stage of the Termux bootstrap, resolver, …). */
    public static final String STAGE_PREPARE = "prepare";
    /** Refreshing the package lists of apt. */
    public static final String STAGE_APT_UPDATE = "apt_update";
    /** Installing the helper packages (glibc-runner, tar, …). */
    public static final String STAGE_APT_INSTALL = "apt_install";
    /** Downloading the OpenCode package. */
    public static final String STAGE_DOWNLOAD = "download";
    /** Extracting the OpenCode package. */
    public static final String STAGE_EXTRACT = "extract";
    /** Patching the ELF binary. */
    public static final String STAGE_PATCH = "patch";
    /** Verifying the patched binary. */
    public static final String STAGE_VERIFY = "verify";
    /** The installation finished successfully. */
    public static final String STAGE_DONE = "done";
    /** The installation failed. */
    public static final String STAGE_FAILED = "failed";

    /** The weight of every stage, in percent. The sum of all of them is 100. */
    private static final Map<String, Integer> STAGE_WEIGHTS = new LinkedHashMap<>();

    static {
        STAGE_WEIGHTS.put(STAGE_PREPARE, 3);
        STAGE_WEIGHTS.put(STAGE_APT_UPDATE, 7);
        STAGE_WEIGHTS.put(STAGE_APT_INSTALL, 25);
        STAGE_WEIGHTS.put(STAGE_DOWNLOAD, 45);
        STAGE_WEIGHTS.put(STAGE_EXTRACT, 8);
        STAGE_WEIGHTS.put(STAGE_PATCH, 7);
        STAGE_WEIGHTS.put(STAGE_VERIFY, 5);
    }

    /** Number of steps kept in the state, the oldest ones are dropped. */
    private static final int MAX_STEPS = 60;

    /* ==========================================================================================
     * Paths
     * ========================================================================================== */

    /** File the state is written to, it survives a restart of the app. */
    public static final String STATE_FILE_PATH = OpenCodeBootstrap.OC_HOME + "/install-state.json";

    /* ==========================================================================================
     * Fields
     * ========================================================================================== */

    /** The stage the installation is currently in. */
    public String stage = STAGE_IDLE;
    /** The percentage of the current stage, between 0 and 100. */
    public int stagePercent = 0;
    /** The percentage of the whole installation, between 0 and 100. */
    public int overallPercent = 0;
    /** The main status line, eg. "Downloading OpenCode 1.2.3…". */
    public String message = "";
    /** The secondary line, eg. "12.3 MB / 98.7 MB - 1.4 MB/s - 1m 20s left". */
    public String detail = "";
    /** The error message when the installation failed. */
    public String error = "";
    /** Number of bytes that were downloaded so far. */
    public long bytesDone = 0;
    /** Number of bytes that have to be downloaded in total, 0 when unknown. */
    public long bytesTotal = 0;
    /** True while the installation is running. */
    public boolean running = false;
    /** True when the installation finished successfully. */
    public boolean done = false;
    /** True when the installation failed. */
    public boolean failed = false;
    /** Timestamp of the last update. */
    public long updatedAt = 0;
    /** The last steps of the installation, the oldest ones are dropped. */
    public final List<String> steps = new ArrayList<>();

    /* ==========================================================================================
     * Methods
     * ========================================================================================== */

    /** @return the percentage of the whole installation, between 0 and 100. */
    public int getOverallPercent() {
        return clampPercent(overallPercent);
    }

    /** @return the percentage of the current stage, between 0 and 100. */
    public int getStagePercent() {
        return clampPercent(stagePercent);
    }

    /**
     * Recompute {@link #overallPercent} from the current stage and its percentage. Must be called
     * whenever the stage or the percentage of the stage changes.
     */
    public void recomputeOverallPercent() {
        if (STAGE_DONE.equals(stage)) {
            overallPercent = 100;
            return;
        }

        int total = 0;
        boolean found = false;
        for (Map.Entry<String, Integer> entry : STAGE_WEIGHTS.entrySet()) {
            if (entry.getKey().equals(stage)) {
                total += entry.getValue() * clampPercent(stagePercent) / 100;
                found = true;
                break;
            }
            total += entry.getValue();
        }

        // An unknown stage (idle, failed, …) keeps the last known percentage.
        overallPercent = found ? clampPercent(total) : clampPercent(overallPercent);
    }

    /** Add a step to the list of the steps, the oldest ones are dropped. */
    public void addStep(String step) {
        if (step == null) return;
        synchronized (steps) {
            steps.add(step);
            while (steps.size() > MAX_STEPS)
                steps.remove(0);
        }
    }

    /** @return the last steps of the installation, the newest ones last. */
    public List<String> getSteps() {
        synchronized (steps) {
            return new ArrayList<>(steps);
        }
    }

    private static int clampPercent(int value) {
        if (value < 0) return 0;
        if (value > 100) return 100;
        return value;
    }

    /* ==========================================================================================
     * Serialization
     * ========================================================================================== */

    /** @return the state as a JSON object. */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("stage", stage);
            json.put("stagePercent", stagePercent);
            json.put("overallPercent", overallPercent);
            json.put("message", message);
            json.put("detail", detail);
            json.put("error", error);
            json.put("bytesDone", bytesDone);
            json.put("bytesTotal", bytesTotal);
            json.put("running", running);
            json.put("done", done);
            json.put("failed", failed);
            json.put("updatedAt", updatedAt);
            JSONArray array = new JSONArray();
            for (String step : getSteps())
                array.put(step);
            json.put("steps", array);
        } catch (JSONException e) {
            // A JSONObject never throws for these keys.
        }
        return json;
    }

    /** @return the state read from a JSON object, never null. */
    public static OpenCodeInstallState fromJson(JSONObject json) {
        OpenCodeInstallState state = new OpenCodeInstallState();
        if (json == null) return state;

        state.stage = json.optString("stage", STAGE_IDLE);
        state.stagePercent = json.optInt("stagePercent", 0);
        state.overallPercent = json.optInt("overallPercent", 0);
        state.message = json.optString("message", "");
        state.detail = json.optString("detail", "");
        state.error = json.optString("error", "");
        state.bytesDone = json.optLong("bytesDone", 0);
        state.bytesTotal = json.optLong("bytesTotal", 0);
        state.running = json.optBoolean("running", false);
        state.done = json.optBoolean("done", false);
        state.failed = json.optBoolean("failed", false);
        state.updatedAt = json.optLong("updatedAt", 0);

        JSONArray array = json.optJSONArray("steps");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String step = array.optString(i, null);
                if (step != null)
                    state.steps.add(step);
            }
        }

        return state;
    }

    /* ==========================================================================================
     * Persistence
     * ========================================================================================== */

    /**
     * Write the state to the disk. The file is written to a temporary file first and then
     * renamed, so that a crash never leaves a half written file behind.
     */
    public static void save(OpenCodeInstallState state) {
        if (state == null) return;

        File file = new File(STATE_FILE_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            return;

        File temp = new File(file.getAbsolutePath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(state.toJson().toString().getBytes("UTF-8"));
            out.flush();
        } catch (IOException e) {
            Logger.logStackTraceWithMessage("OpenCodeInstallState", "Could not write the install state", e);
            return;
        }

        if (!temp.renameTo(file)) {
            // The rename can fail when the target exists on some file systems.
            if (!file.delete() || !temp.renameTo(file)) {
                Logger.logWarn("OpenCodeInstallState", "Could not replace " + file);
                return;
            }
        }
    }

    /** @return the state that was saved on the disk, never null. */
    public static OpenCodeInstallState load() {
        File file = new File(STATE_FILE_PATH);
        if (!file.isFile()) return new OpenCodeInstallState();

        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            StringBuilder sb = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) != -1)
                sb.append(new String(buffer, 0, read, "UTF-8"));
            return fromJson(new JSONObject(sb.toString()));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage("OpenCodeInstallState", "Could not read the install state", e);
            return new OpenCodeInstallState();
        }
    }

    /** Remove the saved state, used when the runtime is complete. */
    public static void clear() {
        try {
            File file = new File(STATE_FILE_PATH);
            if (file.exists())
                file.delete();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage("OpenCodeInstallState", "Could not delete the install state", e);
        }
    }

    /** @return true if the given message is empty or only contains spaces. */
    public static boolean isEmpty(String text) {
        return TextUtils.isEmpty(text) || text.trim().isEmpty();
    }
}
