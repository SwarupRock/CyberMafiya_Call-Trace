package com.scamshield.defender.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.scamshield.defender.service.CallDefenderService;

public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "CallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        boolean isEnabled = context.getSharedPreferences("scam_shield_prefs", Context.MODE_PRIVATE)
                .getBoolean("defender_enabled", false);
        if (!isEnabled) {
            Log.d(TAG, "Call Trace disabled. Ignoring phone state broadcast.");
            return;
        }

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

        Intent serviceIntent = new Intent(context, CallDefenderService.class);
        serviceIntent.putExtra("phone_state", state);
        if (incomingNumber != null && !incomingNumber.isEmpty()) {
            serviceIntent.putExtra("incoming_number", incomingNumber);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        Log.i(TAG, "Forwarded phone state to service: " + state);
    }
}
