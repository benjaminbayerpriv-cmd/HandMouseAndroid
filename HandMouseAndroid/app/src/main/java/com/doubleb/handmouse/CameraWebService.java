package com.doubleb.handmouse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class CameraWebService extends Service {
    public static final String ACTION_START = "com.doubleb.handmouse.START";
    public static final String ACTION_STOP = "com.doubleb.handmouse.STOP";
    private static final String CHANNEL_ID = "hand_mouse_camera";
    private static final int NOTIFICATION_ID = 88;

    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            running = false;
            HandMouseAccessibilityService svc = HandMouseAccessibilityService.getInstance();
            if (svc != null) svc.stopWebEngine();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Hand Mouse Kamera", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Hand Mouse aktiv")
                .setContentText("Kamera-Handtracking läuft")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, n);
        running = true;

        HandMouseAccessibilityService svc = HandMouseAccessibilityService.getInstance();
        if (svc != null) svc.startWebEngine();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
