package com.scamshield.defender.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.BlockedNumberContract;
import android.provider.ContactsContract;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.scamshield.defender.R;
import com.scamshield.defender.analyzer.ScamAnalyzerAI;
import com.scamshield.defender.analyzer.SpeechToTextEngine;
import com.scamshield.defender.model.CallLog;
import com.scamshield.defender.network.FirebaseClient;
import com.scamshield.defender.network.FirestoreHelper;
import com.scamshield.defender.ui.MainActivity;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * CallDefenderService — Foreground Service with Real-Time AI
 * ═══════════════════════════════════════════════════════════════════
 *
 * Core engine of Call Trace. Runs as a foreground service to:
 *   1. Listen for phone state changes (ringing → off-hook → idle)
 *   2. Stream speech-to-text during active calls (NO recording/saving)
 *   3. Feed transcript to multi-layer AI analyzer in real-time
 *   4. Broadcast live updates to the dashboard UI
 *   5. Trigger double-vibration alert when scam detected
 *   6. Auto-block the scammer's number when call ends
 *   7. Save call transcript + analysis to Firestore
 *   8. Detect call bombers (3+ rapid calls from same number)
 */
@SuppressWarnings("deprecation")
public class CallDefenderService extends Service {

    private static final String TAG = "CallDefenderService";

    // ── Notification ─────────────────────────────────────────────
    private static final String CHANNEL_ID = "scam_shield_channel";
    private static final String CHANNEL_NAME = "Call Trace";
    private static final int NOTIFICATION_ID = 1001;

    // ── Call Bomber Detection ────────────────────────────────────
    private static final int BOMBER_CALL_THRESHOLD = 3;       // 3+ calls = bomber
    private static final long BOMBER_WINDOW_MS = 5 * 60 * 1000; // within 5 minutes

    // ── State ────────────────────────────────────────────────────
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private Object telephonyCallbackObj;

    private SpeechToTextEngine speechEngine;
    private ScamAnalyzerAI scamAnalyzer;
    private Handler mainHandler;

    private volatile boolean isScamDetected = false;
    private volatile boolean alertTriggered = false;
    private volatile boolean livePeakAlertTriggered = false;
    private volatile float currentScamScore = 0f;
    private String currentIncomingNumber = null;
    private String currentThreatType = "none";
    private long callStartTime = 0;
    private int lastCallState = TelephonyManager.CALL_STATE_IDLE;
    private boolean isContactNumber = false; // true = caller is in phonebook, skip scam analysis

    // Full transcript accumulated during the call
    private final StringBuilder callTranscript = new StringBuilder();

    // Call bomber tracking: number → list of timestamps
    private final Map<String, LinkedList<Long>> callBomberTracker = new HashMap<>();

    // ═══════════════════════════════════════════════════════════════
    // Android 12+ TelephonyCallback
    // ═══════════════════════════════════════════════════════════════

    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private class CallStateCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            handleCallStateChange(state);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SERVICE LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "╔══════════════════════════════════════╗");
        Log.i(TAG, "║  CALL TRACE — INITIALIZED           ║");
        Log.i(TAG, "╚══════════════════════════════════════╝");

        mainHandler = new Handler(Looper.getMainLooper());
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        // Initialize AI components
        speechEngine = new SpeechToTextEngine(this);
        scamAnalyzer = new ScamAnalyzerAI(this);

        // Wire speech-to-text → AI analyzer
        setupSpeechToAIPipeline();

        // Wire AI analyzer → alerts + dashboard
        setupAIAlertPipeline();

        createNotificationChannel();

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Monitoring — Call Trace active"));
        } catch (SecurityException e) {
            Log.e(TAG, "Could not start foreground service", e);
        }

        registerPhoneStateListener();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("incoming_number")) {
            currentIncomingNumber = intent.getStringExtra("incoming_number");
            Log.d(TAG, "Incoming number: " + maskNumber(currentIncomingNumber));
        }
        if (intent != null && intent.hasExtra("phone_state")) {
            int mappedState = mapPhoneState(intent.getStringExtra("phone_state"));
            if (mappedState != -1) {
                handleCallStateChange(mappedState);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service destroyed");
        speechEngine.stop();
        unregisterPhoneStateListener();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // PIPELINE SETUP — STT → AI → Dashboard
    // ═══════════════════════════════════════════════════════════════

    /**
     * Wire: SpeechToTextEngine → ScamAnalyzerAI → Dashboard
     * When STT produces text, feed it to the AI analyzer.
     * Also broadcast transcript to the dashboard UI.
     */
    private void setupSpeechToAIPipeline() {
        speechEngine.setTranscriptListener(new SpeechToTextEngine.TranscriptListener() {
            @Override
            public void onPartialResult(String partialText) {
                // Broadcast partial transcript to dashboard
                broadcastTranscript(partialText, true);
            }

            @Override
            public void onFinalResult(String finalText, String fullTranscript) {
                // Accumulate transcript for saving
                if (callTranscript.length() > 0) callTranscript.append(" ");
                callTranscript.append(finalText);

                // Broadcast final transcript to dashboard
                broadcastTranscript(finalText, false);

                // Feed to AI analyzer
                scamAnalyzer.analyzeChunk(finalText, fullTranscript);
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "STT error: " + error);
            }
        });
    }

    /**
     * Wire: ScamAnalyzerAI → Vibration alerts + Dashboard updates
     */
    private void setupAIAlertPipeline() {
        scamAnalyzer.setScamDetectionListener(new ScamAnalyzerAI.ScamDetectionListener() {
            @Override
            public void onAnalysisUpdate(ScamAnalyzerAI.AnalysisResult result) {
                currentScamScore = result.confidence;
                currentThreatType = result.threatType;
                broadcastThreatUpdate(result);
            }

            @Override
            public void onScamAlert(ScamAnalyzerAI.AnalysisResult result) {
                // First alert — warn user with vibration
                if (!alertTriggered) {
                    alertTriggered = true;
                    Log.w(TAG, "⚠️ SCAM ALERT — Confidence: " + (result.confidence * 100) + "%");
                    triggerScamAlert();
                    updateNotification("⚠️ SCAM ALERT — " + result.threatType);
                }
            }

            @Override
            public void onScamConfirmed(ScamAnalyzerAI.AnalysisResult result) {
                // High confidence — mark as scam
                if (!isScamDetected) {
                    isScamDetected = true;
                    Log.w(TAG, "🚫 SCAM CONFIRMED — " + (result.confidence * 100) + "%");
                    triggerScamAlert(); // Second vibration for confirmed
                    updateNotification("🚫 SCAM CONFIRMED — End this call!");
                }
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // PHONE STATE LISTENER
    // ═══════════════════════════════════════════════════════════════

    private void registerPhoneStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            CallStateCallback callback = new CallStateCallback();
            telephonyCallbackObj = callback;
            try {
                telephonyManager.registerTelephonyCallback(getMainExecutor(), callback);
                Log.d(TAG, "TelephonyCallback registered (API 31+)");
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied for TelephonyCallback", e);
            }
        } else {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    if (phoneNumber != null && !phoneNumber.isEmpty()) {
                        currentIncomingNumber = phoneNumber;
                    }
                    handleCallStateChange(state);
                }
            };
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            Log.d(TAG, "PhoneStateListener registered (legacy)");
        }
    }

    private void unregisterPhoneStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallbackObj != null) {
            telephonyManager.unregisterTelephonyCallback((TelephonyCallback) telephonyCallbackObj);
        } else if (phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CALL STATE HANDLER — The Core Logic
    // ═══════════════════════════════════════════════════════════════

    private void handleCallStateChange(int state) {
        if (state == lastCallState) {
            return;
        }
        lastCallState = state;

        switch (state) {

            case TelephonyManager.CALL_STATE_RINGING:
                Log.i(TAG, "📞 RINGING — " + maskNumber(currentIncomingNumber));
                isScamDetected = false;
                alertTriggered = false;
                livePeakAlertTriggered = false;
                currentScamScore = 0f;
                currentThreatType = "none";
                callTranscript.setLength(0);
                scamAnalyzer.reset();

                // Check if caller is in contacts — if yes, skip all scam analysis
                isContactNumber = (currentIncomingNumber != null)
                        && isNumberInContacts(currentIncomingNumber);

                if (isContactNumber) {
                    Log.i(TAG, "✅ CONTACT — " + maskNumber(currentIncomingNumber) + " is in phonebook, skipping scam analysis");
                    updateNotification("✅ Contact call — " + maskNumber(currentIncomingNumber));
                } else {
                    updateNotification("⚡ Incoming call — Preparing Call Trace...");
                }

                broadcastCallState("RINGING", currentIncomingNumber);

                // Only run scam checks for UNKNOWN numbers
                if (!isContactNumber && currentIncomingNumber != null) {
                    checkScamDatabase(currentIncomingNumber);
                    // Check for call bomber
                    if (isCallBomber(currentIncomingNumber)) {
                        Log.w(TAG, "🚨 CALL BOMBER DETECTED: " + maskNumber(currentIncomingNumber));
                        raiseNumberIntelligenceAlert(
                                "call_bomber",
                                0.95f,
                                "🚨 CALL BOMBER — " + maskNumber(currentIncomingNumber));
                    }
                }
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                Log.i(TAG, "🎙️ OFF-HOOK — Starting real-time analysis");
                callStartTime = System.currentTimeMillis();

                if (isContactNumber) {
                    // Contact call — don't analyze, just monitor
                    updateNotification("✅ In call with contact");
                    broadcastCallState("ACTIVE", currentIncomingNumber);
                    Log.i(TAG, "Skipping STT + AI for contact call");
                } else {
                    // Unknown number — full AI analysis
                    if (isScamDetected) {
                        updateNotification("🚨 LIVE WARNING — " + currentThreatType);
                        triggerLivePeakAlertOnce();
                    } else {
                        updateNotification("🔴 LIVE — AI analyzing call audio...");
                    }
                    broadcastCallState("ACTIVE", currentIncomingNumber);
                    mainHandler.post(() -> speechEngine.start());
                }
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                Log.i(TAG, "📴 IDLE — Call ended");

                // Stop speech engine
                speechEngine.stop();

                int callDuration = callStartTime > 0
                        ? (int) ((System.currentTimeMillis() - callStartTime) / 1000)
                        : 0;
                String transcript = callTranscript.toString().trim();

                if (isScamDetected && currentIncomingNumber != null) {
                    Log.w(TAG, "🚫 SCAM — Blocking: " + maskNumber(currentIncomingNumber));
                    blockNumber(currentIncomingNumber);
                    updateNotification("🛡️ Scam blocked: " + maskNumber(currentIncomingNumber));
                    broadcastCallState("SCAM_BLOCKED", currentIncomingNumber);
                    saveCallToFirestore(currentIncomingNumber, callDuration, currentScamScore,
                            true, true, transcript, currentThreatType);
                } else if (currentIncomingNumber != null && callDuration > 0) {
                    updateNotification("✅ Call clean — Monitoring");
                    saveCallToFirestore(currentIncomingNumber, callDuration, currentScamScore,
                            false, false, transcript, currentThreatType);
                } else {
                    updateNotification("✅ Monitoring — Call Trace active");
                }

                broadcastCallState("IDLE", null);

                // Reset
                isScamDetected = false;
                alertTriggered = false;
                livePeakAlertTriggered = false;
                currentScamScore = 0f;
                currentThreatType = "none";
                currentIncomingNumber = null;
                callStartTime = 0;
                isContactNumber = false;
                callTranscript.setLength(0);
                break;
        }
    }

    private int mapPhoneState(String state) {
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            return TelephonyManager.CALL_STATE_RINGING;
        }
        if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            return TelephonyManager.CALL_STATE_OFFHOOK;
        }
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            return TelephonyManager.CALL_STATE_IDLE;
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTACTS WHITELIST — Skip scam analysis for known contacts
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if a phone number exists in the user's contacts.
     * If the number is a saved contact, we trust it and skip all
     * scam analysis (no STT, no AI, no alerts, no blocking).
     */
    private boolean isNumberInContacts(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return false;

        try {
            Uri lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber));

            String[] projection = {ContactsContract.PhoneLookup.DISPLAY_NAME};

            Cursor cursor = getContentResolver().query(lookupUri, projection,
                    null, null, null);

            if (cursor != null) {
                boolean found = cursor.getCount() > 0;
                if (found) {
                    cursor.moveToFirst();
                    String name = cursor.getString(
                            cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME));
                    Log.i(TAG, "📒 Contact found: " + name + " — whitelisted");
                }
                cursor.close();
                return found;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read contacts — treating as unknown", e);
        } catch (Exception e) {
            Log.w(TAG, "Contact lookup error", e);
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // CALL BOMBER DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Track incoming calls per number. If 3+ calls from the same
     * number within 5 minutes, it's a call bomber → auto-block.
     */
    private boolean isCallBomber(String phoneNumber) {
        if (phoneNumber == null) return false;

        long now = System.currentTimeMillis();
        LinkedList<Long> timestamps = callBomberTracker.get(phoneNumber);
        if (timestamps == null) {
            timestamps = new LinkedList<>();
            callBomberTracker.put(phoneNumber, timestamps);
        }

        timestamps.add(now);

        // Remove timestamps outside the window
        while (!timestamps.isEmpty() && (now - timestamps.getFirst()) > BOMBER_WINDOW_MS) {
            timestamps.removeFirst();
        }

        boolean isBomber = timestamps.size() >= BOMBER_CALL_THRESHOLD;
        if (isBomber) {
            Log.w(TAG, "📞 Call bomber: " + timestamps.size() + " calls in 5 min from "
                    + maskNumber(phoneNumber));
        }
        return isBomber;
    }

    // ═══════════════════════════════════════════════════════════════
    // BROADCASTS → Dashboard UI
    // ═══════════════════════════════════════════════════════════════

    private void broadcastCallState(String state, String number) {
        Intent intent = new Intent(MainActivity.ACTION_CALL_STATE);
        intent.putExtra("state", state);
        if (number != null) intent.putExtra("number", number);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastTranscript(String text, boolean isPartial) {
        Intent intent = new Intent(MainActivity.ACTION_TRANSCRIPT);
        intent.putExtra("text", text);
        intent.putExtra("partial", isPartial);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastThreatUpdate(ScamAnalyzerAI.AnalysisResult result) {
        Intent intent = new Intent(MainActivity.ACTION_THREAT_UPDATE);
        intent.putExtra("confidence", result.confidence);
        intent.putExtra("level", result.getThreatLevel());
        intent.putExtra("type", result.threatType);
        intent.putExtra("from_cloud", result.fromCloud);
        if (result.keywordsFound != null && !result.keywordsFound.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(result.keywordsFound.size(), 5); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(result.keywordsFound.get(i)).append("\"");
            }
            intent.putExtra("keywords", sb.toString());
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ═══════════════════════════════════════════════════════════════
    // FIRESTORE — Save Call Data + Transcript
    // ═══════════════════════════════════════════════════════════════

    private void saveCallToFirestore(String phoneNumber, int durationSec, float scamScore,
                                      boolean isScam, boolean blocked, String transcript,
                                      String threatType) {
        CallLog callLog = new CallLog(phoneNumber, durationSec, scamScore, isScam, 0f);
        callLog.transcript = transcript;
        callLog.threatType = threatType;
        callLog.blocked = blocked;
        callLog.timestamp = System.currentTimeMillis();

        // Save to Firestore
        FirestoreHelper.getInstance().saveCallLog(callLog, new FirestoreHelper.ResultCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "☁️ Call log + transcript saved to Firestore");
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "Firestore save failed: " + error);
            }
        });

        // Also save blocked number separately
        if (blocked) {
            FirestoreHelper.getInstance().saveBlockedNumber(phoneNumber, "auto_detected", scamScore,
                    new FirestoreHelper.ResultCallback() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "☁️ Blocked number saved to Firestore");
                        }

                        @Override
                        public void onError(String error) {
                            Log.w(TAG, "Blocked number save failed: " + error);
                        }
                    });
        }
    }

    private void checkScamDatabase(String phoneNumber) {
        FirebaseClient.getInstance().checkScamDatabase(phoneNumber,
                new FirebaseClient.DataCallback<com.scamshield.defender.model.ScamNumber>() {
                    @Override
                    public void onSuccess(com.scamshield.defender.model.ScamNumber scam) {
                        if (scam != null && scam.total_reports >= 2) {
                            Log.w(TAG, "⚠️ KNOWN SCAM — " + scam.total_reports + " reports!");
                            raiseNumberIntelligenceAlert(
                                    "known_scam_number",
                                    getDatabaseThreatScore(scam),
                                    "⚠️ WARNING: Known scam (" + scam.total_reports + " reports)");
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Scam DB check failed: " + error);
                    }
                });
    }

    private float getDatabaseThreatScore(com.scamshield.defender.model.ScamNumber scam) {
        if (scam.is_verified_scam) return 1.0f;
        float reportScore = Math.min(0.95f, 0.55f + (scam.total_reports * 0.05f));
        return Math.max(reportScore, scam.avg_scam_score);
    }

    private void raiseNumberIntelligenceAlert(String threatType, float scamScore, String notificationText) {
        isScamDetected = true;
        currentThreatType = threatType;
        currentScamScore = Math.max(currentScamScore, scamScore);
        updateNotification(notificationText);

        if (!alertTriggered) {
            alertTriggered = true;
            triggerScamAlert();
        }

        if (lastCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
            triggerLivePeakAlertOnce();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SCAM ALERT — Double Vibration
    // ═══════════════════════════════════════════════════════════════

    private void triggerLivePeakAlertOnce() {
        if (livePeakAlertTriggered) return;
        livePeakAlertTriggered = true;
        triggerScamAlert();
    }

    private void triggerScamAlert() {
        Log.w(TAG, "🔔 SCAM ALERT — peak vibration!");

        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm == null) return;
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = {0, 450, 120, 450, 120, 700};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int[] amplitudes = {0, 255, 0, 255, 0, 255};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // NUMBER BLOCKING
    // ═══════════════════════════════════════════════════════════════

    private void blockNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return;

        try {
            ContentValues values = new ContentValues();
            values.put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, phoneNumber);
            getContentResolver().insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values);
            Log.i(TAG, "✅ Number blocked: " + maskNumber(phoneNumber));
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot block directly — using local blocklist");
            blockNumberLocally(phoneNumber);
        } catch (Exception e) {
            Log.e(TAG, "Error blocking number", e);
            blockNumberLocally(phoneNumber);
        }
    }

    private void blockNumberLocally(String phoneNumber) {
        getSharedPreferences("blocked_numbers", MODE_PRIVATE).edit()
                .putBoolean(phoneNumber, true)
                .putLong(phoneNumber + "_timestamp", System.currentTimeMillis())
                .apply();
        Log.i(TAG, "Number added to local blocklist: " + maskNumber(phoneNumber));
    }

    // ═══════════════════════════════════════════════════════════════
    // NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Call Trace call monitoring");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Trace")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════

    private String maskNumber(String number) {
        if (number == null) return "UNKNOWN";
        if (number.length() <= 4) return "****";
        return number.substring(0, number.length() - 4)
                .replaceAll("\\d", "*")
                + number.substring(number.length() - 4);
    }
}
