package com.scamshield.defender.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.scamshield.defender.service.CallDefenderService;

/**
 * ═══════════════════════════════════════════════════════════════════
 * CallReceiver — Phone State Broadcast Receiver
 * ═══════════════════════════════════════════════════════════════════
 *
 * Receives PHONE_STATE broadcasts from the system when a call comes in.
 * Extracts the incoming number and passes it to the CallDefenderService.
 *
 * Registered in AndroidManifest.xml with:
 *   <action android:name="android.intent.action.PHONE_STATE" />
 */
public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "CallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

            Log.d(TAG, "Phone state changed: " + state +
                    " | Number: " + (incomingNumber != null ? "present" : "null"));

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state) && incomingNumber != null) {
                // Forward the incoming number to our foreground service
                Intent serviceIntent = new Intent(context, CallDefenderService.class);
                serviceIntent.putExtra("incoming_number", incomingNumber);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }

                Log.i(TAG, "Incoming call detected — Service notified with number");
            }
        }
    }
}
