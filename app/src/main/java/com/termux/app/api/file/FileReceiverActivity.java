package com.termux.app.api.file;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.OpenCodeWebActivity;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Receives content from the Android share sheet and copies it into {@code ~/Downloads}.
 *
 * <p>Files shared from other apps are read through the temporary read permission that the
 * system grants with the {@code ACTION_SEND} intent. Text shares are written to a small
 * {@code .txt} file. When the copy is finished the main activity opens the file manager at the
 * Downloads folder so that the user can open or share the received files immediately.</p>
 */
public class FileReceiverActivity extends AppCompatActivity {

    private static final String DOWNLOADS_DIRECTORY = "Downloads";
    private static final int BUFFER_SIZE = 8192;

    private ProgressDialog progress;
    private boolean receiving;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A configuration change recreates the activity while the copy thread is still running.
        // The original instance finishes the job, so a second copy must not start.
        if (savedInstanceState != null) {
            finish();
            return;
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) {
            finish();
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            finish();
            return;
        }

        final List<Uri> streams = collectStreams(intent);
        final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        final boolean hasText = text != null && !text.trim().isEmpty();
        if (streams.isEmpty() && !hasText) {
            Toast.makeText(this, R.string.opencode_share_receive_empty, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (receiving) return;
        receiving = true;

        progress = ProgressDialog.show(this,
            getString(R.string.opencode_share_receiving_title),
            getString(R.string.opencode_share_receiving_message), true, false);

        Thread worker = new Thread(() -> receive(streams, hasText ? text : null));
        worker.setDaemon(true);
        worker.start();
    }

    private void receive(List<Uri> streams, @Nullable String text) {
        File downloads = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, DOWNLOADS_DIRECTORY);
        int received = 0;

        if (downloads.isDirectory() || downloads.mkdirs()) {
            ContentResolver resolver = getContentResolver();
            int index = 0;
            for (Uri uri : streams) {
                index++;
                String name = queryDisplayName(resolver, uri);
                if (name == null || name.trim().isEmpty()) {
                    name = "shared-" + index;
                }
                File target = uniqueFile(downloads, safeFileName(name));
                try (InputStream input = resolver.openInputStream(uri);
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    if (input == null) throw new IllegalStateException("No stream");
                    copy(new BufferedInputStream(input), output);
                    received++;
                } catch (Throwable t) {
                    target.delete();
                }
            }

            if (received == 0 && text != null) {
                File target = uniqueFile(downloads, textFileName());
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                    received++;
                } catch (Throwable t) {
                    target.delete();
                }
            }
        }

        final int total = received;
        runOnUiThread(() -> finishReceiving(total, downloads));
    }

    private void finishReceiving(int received, File directory) {
        if (progress != null && progress.isShowing()) {
            try {
                progress.dismiss();
            } catch (Throwable ignored) {
                // The activity may already be finishing.
            }
        }
        progress = null;

        if (received <= 0) {
            Toast.makeText(this, R.string.opencode_share_receive_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Toast.makeText(this, getString(R.string.opencode_share_received, received),
            Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(this, OpenCodeWebActivity.class);
        intent.putExtra(OpenCodeWebActivity.EXTRA_OPEN_DIRECTORY, directory.getAbsolutePath());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Throwable ignored) {
            // The main activity is normally already in the task.
        }
        finish();
    }

    private static List<Uri> collectStreams(Intent intent) {
        Set<Uri> result = new LinkedHashSet<>();

        Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream instanceof Uri) {
            result.add((Uri) stream);
        } else if (stream instanceof List) {
            for (Object item : (List<?>) stream) {
                if (item instanceof Uri) result.add((Uri) item);
            }
        } else if (stream instanceof Uri[]) {
            for (Uri uri : (Uri[]) stream) result.add(uri);
        }

        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) result.add(uri);
            }
        }
        return new ArrayList<>(result);
    }

    @Nullable
    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri,
            new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Throwable ignored) {
            // Fall back to the path segment below.
        }
        return uri.getLastPathSegment();
    }

    private static String safeFileName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) safe = "shared";
        if (safe.startsWith(".")) safe = "shared" + safe;
        if (safe.length() > 120) {
            String extension = "";
            int dot = safe.lastIndexOf('.');
            if (dot > 0) extension = safe.substring(dot);
            safe = safe.substring(0, Math.max(1, 120 - extension.length())) + extension;
        }
        return safe;
    }

    private static File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists()) return file;

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            File candidate = new File(directory, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    private static String textFileName() {
        return "shared-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(new Date()) + ".txt";
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    /**
     * Whether text shared with the app is a single URL. Shared text is not stored when the same
     * share also contains files; this helper exposes the URL classification used by tests.
     */
    public static boolean isSharedTextAnUrl(String sharedText) {
        if (sharedText == null) return false;
        String text = sharedText.trim();
        if (text.isEmpty()) return false;
        try {
            String scheme = Uri.parse(text).getScheme();
            if (scheme == null) return false;
            switch (scheme.toLowerCase(Locale.ROOT)) {
                case "http":
                case "https":
                case "ftp":
                case "ftps":
                case "magnet":
                case "mailto":
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
