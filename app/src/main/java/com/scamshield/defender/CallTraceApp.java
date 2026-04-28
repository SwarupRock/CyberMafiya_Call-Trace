package com.scamshield.defender;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * Application class for Call Trace — global initialization.
 */
public class CallTraceApp extends Application {

    public static final String CHANNEL_ID_ALERTS = "scam_shield_alerts";

    @Override
    public void onCreate() {
        super.onCreate();
        createAlertNotificationChannel();
    }

    /**
     * Creates a high-priority notification channel for scam alerts.
     * This is separate from the service's low-priority monitoring channel.
     */
    private void createAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERTS,
                    "Scam Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription("Critical alerts when a scam call is detected");
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{0, 300, 200, 300});

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(alertChannel);
            }
        }
    }
}
