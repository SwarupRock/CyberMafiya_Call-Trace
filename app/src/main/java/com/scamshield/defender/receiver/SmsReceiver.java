package com.scamshield.defender.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.scamshield.defender.ui.MainActivity;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";
    private static final String PREFS_NAME = "scam_shield_prefs";
    private static final String PREF_QUARANTINE = "quarantine_messages";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        boolean isEnabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("defender_enabled", false);
        if (!isEnabled) {
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            return;
        }

        String sender = messages[0].getOriginatingAddress();
        StringBuilder body = new StringBuilder();
        for (SmsMessage msg : messages) {
            if (msg != null && msg.getMessageBody() != null) {
                body.append(msg.getMessageBody());
            }
        }

        String text = body.toString();
        if (text.trim().isEmpty()) {
            return;
        }

        int score = scoreMessage(text);
        boolean hasOtp = text.toLowerCase().matches(".*\\b(otp|code|pin|verification)\\b.*")
                || text.matches(".*\\b\\d{4,8}\\b.*");
        boolean quarantine = score >= 2 || (hasOtp && score >= 1);

        if (quarantine) {
            saveQuarantine(context, sender, text, score, hasOtp);
            vibrateTwice(context);
            Intent update = new Intent(MainActivity.ACTION_SMS_QUARANTINE);
            update.putExtra("sender", sender);
            update.putExtra("text", text);
            update.putExtra("score", score);
            update.putExtra("has_otp", hasOtp);
            LocalBroadcastManager.getInstance(context).sendBroadcast(update);
            Log.w(TAG, "Quarantined suspicious SMS from " + sender);
        }
    }

    private int scoreMessage(String text) {
        String lower = text.toLowerCase();
        int score = 0;
        String[] scamSignals = {
                "otp", "kyc", "account blocked", "account suspended", "verify",
                "urgent", "click", "link", "refund", "cashback", "prize",
                "won", "bank", "upi", "pin", "card", "arrest", "legal",
                "deactivate", "download", "remote access"
        };
        for (String signal : scamSignals) {
            if (lower.contains(signal)) {
                score++;
            }
        }
        if (lower.contains("do not share") && (lower.contains("bank") || lower.contains("upi"))) {
            score++;
        }
        if (lower.matches(".*https?://.*") || lower.matches(".*\\b[a-z0-9.-]+\\.(com|in|net|org)\\b.*")) {
            score += 2;
        }
        return score;
    }

    private void saveQuarantine(Context context, String sender, String text, int score, boolean hasOtp) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existing = prefs.getString(PREF_QUARANTINE, "");
        String safeSender = sender == null ? "UNKNOWN" : sender.replace("\n", " ");
        String safeText = text.replace("\n", " ").replace("|", "/");
        String row = System.currentTimeMillis() + "|" + safeSender + "|" + score + "|" + hasOtp + "|" + safeText;
        String combined = row + "\n" + existing;
        String[] lines = combined.split("\n");
        StringBuilder trimmed = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 10); i++) {
            if (!lines[i].trim().isEmpty()) {
                trimmed.append(lines[i]).append("\n");
            }
        }
        prefs.edit().putString(PREF_QUARANTINE, trimmed.toString()).apply();
    }

    private void vibrateTwice(Context context) {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm == null ? null : vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = {0, 180, 120, 180};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int[] amplitudes = {0, 255, 0, 255};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }
}
