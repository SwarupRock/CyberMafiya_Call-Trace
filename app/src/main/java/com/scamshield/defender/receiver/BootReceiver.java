package com.scamshield.defender.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.scamshield.defender.service.CallDefenderService;

/**
 * ═══════════════════════════════════════════════════════════════════
 * BootReceiver — Restart Service on Device Reboot
 * ═══════════════════════════════════════════════════════════════════
 *
 * Ensures the CallDefenderService restarts after the device reboots,
 * so the user stays protected at all times.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Device boot detected — Restarting Call Trace service");

            // Check if the user had the service enabled
            boolean isEnabled = context.getSharedPreferences("scam_shield_prefs", Context.MODE_PRIVATE)
                    .getBoolean("defender_enabled", false);

            if (isEnabled) {
                Intent serviceIntent = new Intent(context, CallDefenderService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.i(TAG, "Service restarted successfully after boot");
            } else {
                Log.d(TAG, "Service was disabled — skipping restart");
            }
        }
    }
}
