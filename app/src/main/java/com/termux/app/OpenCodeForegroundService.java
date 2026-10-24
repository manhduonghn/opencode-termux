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

        startForeground(NOTIFICATION_ID, buildNotification());
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
        new Thread("opencode-fg-ensure") {
            @Override
            public void run() {
                try {
                    if (!OpenCodeService.isRunning()) {
                        OpenCodeService.startAndPair(app, 60000L, 60000L);
                    }
                } catch (Throwable t) {
                    // Best effort
                }
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
                        try {
                            stopForeground(STOP_FOREGROUND_REMOVE);
                        } catch (Throwable ignored) {}
                        stopSelf();
                    });
                } catch (Throwable t) {
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } catch (Throwable ignored) {}
                    stopSelf();
                }
            }
        }.start();
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
