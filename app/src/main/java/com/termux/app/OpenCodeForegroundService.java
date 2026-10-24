package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;

public class OpenCodeForegroundService extends Service {

    public static final String ACTION_STOP = "com.termux.opencode.ACTION_STOP";
    public static final String ACTION_START = "com.termux.opencode.ACTION_START";
    /** Sent after the daemon was stopped from the notification action. */
    public static final String ACTION_STOPPED = "com.termux.opencode.SERVICE_STOPPED";

    private static final String CHANNEL_ID = "opencode_service_channel";
    private static final String CHANNEL_NAME_RES = "opencode_service_notification_channel_name";
    private static final String CHANNEL_DESC_RES = "opencode_service_notification_channel_desc";

    public static final int NOTIFICATION_ID = 0x4F43; // "OC"

    private static PowerManager.WakeLock sWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopOpenCode();
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            // Android 12+ refuses to promote a service that was restarted while the app is in the
            // background. Stop instead of crashing, the activity will start it again.
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureOpenCodeRunning();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep service alive when task is removed - START_STICKY will handle if killed
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

            CharSequence name = getString(R.string.opencode_service_notification_channel_name);
            String desc = getString(R.string.opencode_service_notification_channel_desc);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(desc);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = OpenCodeWebActivity.newInstance(this);
        PendingIntent openPending = PendingIntent.getActivity(this, 100, openIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, OpenCodeForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 101, stopIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(getString(R.string.opencode_service_notification_title))
            .setContentText(getString(R.string.opencode_service_notification_text))
            .setContentIntent(openPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.opencode_service_notification_action_open), openPending)
            .addAction(0, getString(R.string.opencode_service_notification_action_stop), stopPending);

        return builder.build();
    }

    private void ensureOpenCodeRunning() {
        final Context app = getApplicationContext();
        if (!OpenCodeBootstrap.isRuntimeComplete()) return;
        new Thread("opencode-fg-ensure") {
            @Override
            public void run() {
                // The call is serialized with the pair/stop calls of the web activity by the lock
                // inside OpenCodeService, so it can never kill a daemon that is just starting.
                OpenCodeService.ensureStarted(app, 60000L);
            }
        }.start();
    }

    private void stopOpenCode() {
        final Context app = getApplicationContext();
        new Thread("opencode-fg-stop") {
            @Override
            public void run() {
                try {
                    OpenCodeService.stopAsync(app, false, () -> {
                        notifyStopped();
                        try {
                            stopForeground(STOP_FOREGROUND_REMOVE);
                        } catch (Throwable ignored) {}
                        stopSelf();
                    });
                } catch (Throwable t) {
                    notifyStopped();
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } catch (Throwable ignored) {}
                    stopSelf();
                }
            }
        }.start();
    }

    /**
     * Tell a visible web activity that the current page belongs to a daemon that is gone, so it can
     * pair again right away instead of waiting for the next {@code onResume()}.
     */
    private void notifyStopped() {
        Intent intent = new Intent(ACTION_STOPPED).setPackage(getPackageName());
        try {
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private void acquireWakeLock() {
        try {
            if (sWakeLock != null && sWakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:foreground");
            sWakeLock.setReferenceCounted(false);
            sWakeLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (sWakeLock != null && sWakeLock.isHeld()) {
                sWakeLock.release();
            }
            sWakeLock = null;
        } catch (Throwable ignored) {}
    }

    public static void start(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, OpenCodeForegroundService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Throwable ignored) {
            try {
                app.startService(intent);
            } catch (Throwable ignored2) {}
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, OpenCodeForegroundService.class).setAction(ACTION_STOP);
        try {
            app.startService(intent);
        } catch (Throwable ignored) {}
    }
}
