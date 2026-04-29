package com.scamshield.defender.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
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
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.scamshield.defender.R;
import com.scamshield.defender.analyzer.LiveMicTranscriber;
import com.scamshield.defender.analyzer.ScamAnalyzerAI;
import com.scamshield.defender.analyzer.SpeechToTextEngine;
import com.scamshield.defender.child.ChildRiskResult;
import com.scamshield.defender.child.ChildSafetyAlertOverlay;
import com.scamshield.defender.child.ChildSafetyAnalyzer;
import com.scamshield.defender.child.EmergencyContact;
import com.scamshield.defender.child.EmergencyContactManager;
import com.scamshield.defender.model.CallLog;
import com.scamshield.defender.network.FirebaseClient;
import com.scamshield.defender.network.FirestoreHelper;
import com.scamshield.defender.ui.MainActivity;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final long PRIVACY_LIMITED_WARNING_DELAY_MS = 25_000L;  // Extended: give retry logic more time
    private static final long STABLE_PARTIAL_COMMIT_DELAY_MS = 2_500L;
    private static final long DEMO_SCAM_ALERT_DELAY_MS = 1_500L;
    private static final String DEMO_SCAM_PHRASE = "otp bank account verification";
    private static final long CHILD_SAFETY_HARD_ALERT_DELAY_MS = 3_000L;
    private static final String CHILD_SAFETY_AUTO_TRANSCRIPT =
            "Child Safety Mode: unknown caller traced and disconnected after 3 seconds.";
    private static final long CHILD_SAFETY_ANALYSIS_INTERVAL_MS = 10_000L;

    // ── State ────────────────────────────────────────────────────
    private TelephonyManager telephonyManager;
    private AudioManager audioManager;
    private PhoneStateListener phoneStateListener;
    private Object telephonyCallbackObj;

    private SpeechToTextEngine speechEngine;
    private LiveMicTranscriber liveMicTranscriber;
    private ScamAnalyzerAI scamAnalyzer;
    private Handler mainHandler;
    private final ExecutorService childSafetyExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean isScamDetected = false;
    private volatile boolean alertTriggered = false;
    private volatile boolean livePeakAlertTriggered = false;
    private volatile float currentScamScore = 0f;
    private String currentIncomingNumber = null;
    private String currentThreatType = "none";
    private long callStartTime = 0;
    private int lastCallState = TelephonyManager.CALL_STATE_IDLE;
    private boolean isContactNumber = false; // true = caller is in phonebook, skip scam analysis
    private boolean speechStartedForCall = false;
    private boolean audioRouteChangedForCall = false;
    private boolean previousSpeakerphoneOn = false;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private boolean privacyLimitedWarningTriggered = false;
    private boolean demoScamAlertTriggered = false;
    private boolean childSafetyAlertTriggered = false;
    private long lastChildSafetyAnalysisAt = 0L;
    private final Runnable hardChildSafetyAlertRunnable = this::triggerHardChildSafetyAlert;

    // Full transcript accumulated during the call
    private final StringBuilder callTranscript = new StringBuilder();
    private String lastPartialTranscript = "";
    private final Runnable commitStablePartialRunnable = this::commitStablePartialTranscript;

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
            handleCallStateChange(state, false);
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
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Initialize AI components
        speechEngine = new SpeechToTextEngine(this);
        scamAnalyzer = new ScamAnalyzerAI(this);
        liveMicTranscriber = new LiveMicTranscriber(this, scamAnalyzer);

        // Wire speech-to-text → AI analyzer
        setupSpeechToAIPipeline();
        setupLiveMicPipeline();

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
        if (intent != null && intent.getBooleanExtra("request_status", false)) {
            broadcastCurrentCallStatus();
        }
        if (intent != null && intent.hasExtra("incoming_number")) {
            currentIncomingNumber = intent.getStringExtra("incoming_number");
            Log.d(TAG, "Incoming number: " + maskNumber(currentIncomingNumber));
        }
        if (intent != null && intent.hasExtra("phone_state")) {
            int mappedState = mapPhoneState(intent.getStringExtra("phone_state"));
            if (mappedState != -1) {
                handleCallStateChange(mappedState, true);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service destroyed");
        speechEngine.stop();
        liveMicTranscriber.stop();
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
                handlePartialTranscript(partialText);
            }

            @Override
            public void onFinalResult(String finalText, String fullTranscript) {
                handleFinalTranscript(finalText, fullTranscript);
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "STT error: " + error);
                broadcastTranscriptStatus(error);
            }
        });
    }

    private void setupLiveMicPipeline() {
        liveMicTranscriber.setListener(new LiveMicTranscriber.Listener() {
            @Override
            public void onTranscript(String text) {
                mainHandler.post(() ->
                        handleFinalTranscript(text, callTranscript.toString() + " " + text));
            }

            @Override
            public void onStatus(String status) {
                broadcastTranscriptStatus(status);
            }

            @Override
            public void onError(String error) {
                broadcastTranscriptStatus(error);
            }

            @Override
            public void onDeadAudioInput() {
                mainHandler.post(() -> {
                    liveMicTranscriber.stop();
                    Log.i(TAG, "Ignoring cloud ASR dead-audio callback; live calls use Android speech only.");
                });
            }
        });
    }

    // Dedup overlapping transcripts from dual engines (SpeechRecognizer + LiveMicTranscriber)
    private String lastTranscriptChunk = "";
    private long lastTranscriptTime = 0L;
    private String lastAnalyzedPartial = "";
    private long lastPartialAnalysisTime = 0L;
    private static final long DEDUP_WINDOW_MS = 2000L;
    private static final long PARTIAL_ANALYSIS_WINDOW_MS = 1200L;

    private void handlePartialTranscript(String partialText) {
        if (partialText == null || partialText.trim().isEmpty()) return;
        String text = partialText.trim();
        lastPartialTranscript = text;

        broadcastTranscript(text, true);
        mainHandler.removeCallbacks(commitStablePartialRunnable);
        mainHandler.postDelayed(commitStablePartialRunnable, STABLE_PARTIAL_COMMIT_DELAY_MS);

        long now = System.currentTimeMillis();
        if (text.length() < 3) return;
        if (text.equalsIgnoreCase(lastAnalyzedPartial)
                && (now - lastPartialAnalysisTime) < PARTIAL_ANALYSIS_WINDOW_MS) {
            return;
        }

        lastAnalyzedPartial = text;
        lastPartialAnalysisTime = now;
        String snapshot = buildTranscriptSnapshot(text);
        scamAnalyzer.analyzeChunk(text, snapshot);
        maybeRunChildSafetyAnalysis(snapshot);
    }

    private void handleFinalTranscript(String finalText, String fullTranscript) {
        if (finalText == null || finalText.trim().isEmpty()) return;
        String text = finalText.trim();
        mainHandler.removeCallbacks(commitStablePartialRunnable);
        lastPartialTranscript = "";

        // Simple dedup: skip if identical text arrived within 2s window
        long now = System.currentTimeMillis();
        if (text.equalsIgnoreCase(lastTranscriptChunk)
                && (now - lastTranscriptTime) < DEDUP_WINDOW_MS) {
            Log.d(TAG, "Skipping duplicate transcript: " + text);
            return;
        }
        lastTranscriptChunk = text;
        lastTranscriptTime = now;

        if (callTranscript.length() > 0) callTranscript.append(" ");
        callTranscript.append(text);

        broadcastTranscript(text, false);
        String snapshot = fullTranscript != null ? fullTranscript : callTranscript.toString();
        scamAnalyzer.analyzeChunk(text, snapshot);
        maybeRunChildSafetyAnalysis(snapshot);
    }

    private void commitStablePartialTranscript() {
        if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) return;
        String stablePartial = lastPartialTranscript != null ? lastPartialTranscript.trim() : "";
        if (stablePartial.length() < 5) return;
        handleFinalTranscript(stablePartial, buildTranscriptSnapshot(stablePartial));
    }

    private String buildTranscriptSnapshot(String partialText) {
        StringBuilder snapshot = new StringBuilder(callTranscript.toString().trim());
        if (partialText != null && !partialText.trim().isEmpty()) {
            String partial = partialText.trim();
            if (snapshot.length() == 0
                    || !snapshot.toString().toLowerCase().endsWith(partial.toLowerCase())) {
                if (snapshot.length() > 0) snapshot.append(" ");
                snapshot.append(partial);
            }
        }
        return snapshot.toString().trim();
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
                Log.w(TAG, "SCAM WATCH - Confidence: " + (result.confidence * 100) + "%");
                updateNotification("SCAM WARNING - Cut the call if this sounds suspicious");
                if (!alertTriggered) {
                    alertTriggered = true;
                    triggerScamAlert();
                }
            }

            @Override
            public void onScamConfirmed(ScamAnalyzerAI.AnalysisResult result) {
                // High confidence — mark as scam
                if (!isScamDetected) {
                    isScamDetected = true;
                    Log.w(TAG, "🚫 SCAM CONFIRMED — " + (result.confidence * 100) + "%");
                    if (!alertTriggered) {
                        alertTriggered = true;
                        triggerScamAlert();
                    }
                    updateNotification("SCAM CONFIRMED - Cut the call!");
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
                    handleCallStateChange(state, false);
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
        handleCallStateChange(state, false);
    }

    private void handleCallStateChange(int state, boolean forceFromBroadcast) {
        if (state == lastCallState && !forceFromBroadcast) {
            return;
        }
        lastCallState = state;

        switch (state) {

            case TelephonyManager.CALL_STATE_RINGING:
                Log.i(TAG, "RINGING - " + maskNumber(currentIncomingNumber));
                isScamDetected = false;
                alertTriggered = false;
                livePeakAlertTriggered = false;
                currentScamScore = 0f;
                currentThreatType = "none";
                callTranscript.setLength(0);
                scamAnalyzer.reset();
                speechStartedForCall = false;
                privacyLimitedWarningTriggered = false;
                demoScamAlertTriggered = false;
                childSafetyAlertTriggered = false;
                lastChildSafetyAnalysisAt = 0L;
                mainHandler.removeCallbacks(hardChildSafetyAlertRunnable);
                lastTranscriptChunk = "";
                lastTranscriptTime = 0L;
                lastPartialTranscript = "";
                lastAnalyzedPartial = "";
                lastPartialAnalysisTime = 0L;

                isContactNumber = (currentIncomingNumber != null)
                        && isNumberInContacts(currentIncomingNumber);

                if (isContactNumber) {
                    updateNotification("Contact call - waiting for answer");
                } else {
                    updateNotification("Incoming call - preparing Call Trace");
                }

                broadcastCallState("RINGING", currentIncomingNumber);
                scheduleOffhookFallbackCheck();

                if (!isContactNumber && currentIncomingNumber != null) {
                    checkScamDatabase(currentIncomingNumber);
                    if (isCallBomber(currentIncomingNumber)) {
                        Log.w(TAG, "CALL BOMBER DETECTED: " + maskNumber(currentIncomingNumber));
                        raiseNumberIntelligenceAlert(
                                "call_bomber",
                                0.95f,
                                "CALL BOMBER - " + maskNumber(currentIncomingNumber));
                    }
                }
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                Log.i(TAG, "OFF-HOOK - Starting live transcription and scam analysis");
                if (callStartTime == 0) {
                    callStartTime = System.currentTimeMillis();
                }

                if (isContactNumber) {
                    updateNotification("Contact call active - Call Trace ignored");
                    broadcastCallState("ACTIVE", currentIncomingNumber);
                    broadcastTranscriptStatus("Saved contact detected. Child Safety and scam demo alerts skipped.");
                    break;
                }

                if (isScamDetected) {
                    updateNotification("LIVE WARNING - " + currentThreatType);
                    triggerLivePeakAlertOnce();
                } else {
                    updateNotification("Unknown caller active - Call Trace armed");
                }

                broadcastCallState("ACTIVE", currentIncomingNumber);
                if (isChildSafetyModeEnabled()) {
                    scheduleHardChildSafetyAlert();
                } else {
                    triggerDemoScamAlertForAnsweredCall();
                }
                break;
            case TelephonyManager.CALL_STATE_IDLE:
                Log.i(TAG, "📴 IDLE — Call ended");

                // Stop speech engine
                speechEngine.stop();
                liveMicTranscriber.stop();

                int callDuration = callStartTime > 0
                        ? (int) ((System.currentTimeMillis() - callStartTime) / 1000)
                        : 0;
                String transcript = buildTranscriptSnapshot(lastPartialTranscript);

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
                speechStartedForCall = false;
                privacyLimitedWarningTriggered = false;
                demoScamAlertTriggered = false;
                childSafetyAlertTriggered = false;
                lastChildSafetyAnalysisAt = 0L;
                mainHandler.removeCallbacks(hardChildSafetyAlertRunnable);
                restoreAudioRouteAfterCall();
                callTranscript.setLength(0);
                lastPartialTranscript = "";
                lastAnalyzedPartial = "";
                lastPartialAnalysisTime = 0L;
                break;
        }
    }

    private void triggerDemoScamAlertForAnsweredCall() {
        if (demoScamAlertTriggered) return;
        demoScamAlertTriggered = true;

        mainHandler.postDelayed(() -> {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) return;
            if (alertTriggered) return;

            Log.i(TAG, "Demo scam alert triggered for answered call");
            isScamDetected = true;
            currentScamScore = 1.0f;
            currentThreatType = "demo_otp_bank_scam";
            alertTriggered = true;

            if (callTranscript.length() > 0) callTranscript.append(" ");
            callTranscript.append(DEMO_SCAM_PHRASE);

            ScamAnalyzerAI.AnalysisResult result = new ScamAnalyzerAI.AnalysisResult();
            result.confidence = 1.0f;
            result.isScam = true;
            result.threatType = currentThreatType;
            result.reasoning = "Pre-coded demo scam alert for answered call.";
            result.keywordsFound.add("otp");
            result.keywordsFound.add("bank");
            result.fromCloud = false;

            broadcastTranscript("DEMO: OTP bank scam detected", false);
            broadcastThreatUpdate(result);
            updateNotification("DEMO SCAM DETECTED - Cut the call!");
            triggerScamAlert();
        }, DEMO_SCAM_ALERT_DELAY_MS);
    }

    private boolean isChildSafetyModeEnabled() {
        return getSharedPreferences("scam_shield_prefs", MODE_PRIVATE)
                .getBoolean("child_safety_mode", false);
    }

    private void scheduleHardChildSafetyAlert() {
        if (childSafetyAlertTriggered) return;
        updateNotification("Child Safety armed - call will disconnect");
        broadcastTranscriptStatus("Child Safety Mode active: threat flow starts in 3 seconds.");
        mainHandler.removeCallbacks(hardChildSafetyAlertRunnable);
        mainHandler.postDelayed(hardChildSafetyAlertRunnable, CHILD_SAFETY_HARD_ALERT_DELAY_MS);
    }

    private void triggerHardChildSafetyAlert() {
        if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) return;
        if (!isChildSafetyModeEnabled() || childSafetyAlertTriggered) return;
        if (isContactNumber) return;

        ChildRiskResult result = new ChildRiskResult();
        result.isChildDetected = true;
        result.isExtractionAttempt = true;
        result.riskScore = 1.0f;
        result.nvidiaConfidenceScore = 1.0f;
        result.reason = "Child Safety Mode traced an unknown caller after the call was answered.";
        result.matchedChildSignals.add("child_safety_mode_enabled");
        result.matchedExtractionPatterns.add("unknown_answered_call_traced");
        triggerChildSafetyAlert(currentIncomingNumber, result, buildTranscriptSnapshot(CHILD_SAFETY_AUTO_TRANSCRIPT), true);
    }

    private void schedulePrivacyLimitedWarning() {
        mainHandler.postDelayed(() -> {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) return;
            if (privacyLimitedWarningTriggered || alertTriggered) return;
            if (callTranscript.length() > 0) return;

            privacyLimitedWarningTriggered = true;
            currentThreatType = "audio_unavailable_unknown_call";
            updateNotification("Call Trace cannot hear this call - stay cautious");
            broadcastTranscriptStatus("Privacy-limited mode: Android is not exposing caller speech yet. No scam verdict will be raised until caller words are transcribed.");
        }, PRIVACY_LIMITED_WARNING_DELAY_MS);
    }

    private void maybeRunChildSafetyAnalysis(String transcriptSnapshot) {
        if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) return;
        if (childSafetyAlertTriggered) return;
        if (isContactNumber) return;
        if (!getSharedPreferences("scam_shield_prefs", MODE_PRIVATE)
                .getBoolean("child_safety_mode", false)) return;
        if (transcriptSnapshot == null || transcriptSnapshot.trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastChildSafetyAnalysisAt < CHILD_SAFETY_ANALYSIS_INTERVAL_MS) return;
        lastChildSafetyAnalysisAt = now;

        String snapshot = transcriptSnapshot.trim();
        childSafetyExecutor.execute(() -> {
            ChildRiskResult result = ChildSafetyAnalyzer.analyzeForChildRisk(this, snapshot);
            if (result != null && result.shouldAlert()) {
                mainHandler.post(() -> triggerChildSafetyAlert(currentIncomingNumber, result, snapshot, false));
            }
        });
    }

    private void triggerChildSafetyAlert(String callerNumber, ChildRiskResult result,
                                         String transcriptSnapshot, boolean forceDisconnect) {
        if (childSafetyAlertTriggered) return;
        childSafetyAlertTriggered = true;
        currentThreatType = "child_safety";
        currentScamScore = Math.max(currentScamScore, result != null ? result.riskScore : 1.0f);
        isScamDetected = true;
        alertTriggered = true;

        triggerMaxChildSafetyVibration();

        String uid = getCurrentUid();
        String ownerName = getOwnerName();
        boolean autoDisconnect = getSharedPreferences("scam_shield_prefs", MODE_PRIVATE)
                .getBoolean("child_safety_auto_disconnect", false);
        boolean shouldDisconnect = forceDisconnect || autoDisconnect;
        ChildSafetyAlertOverlay.show(this, 0, shouldDisconnect);

        boolean disconnected = false;
        if (shouldDisconnect) {
            disconnected = ChildSafetyAlertOverlay.endCall(this);
        }

        updateNotification("Child Safety Alert Triggered");
        broadcastTranscriptStatus("CHILD SAFETY ALERT: unknown caller has been traced.");

        final boolean finalDisconnected = disconnected;
        EmergencyContactManager.getEmergencyContactsFromFirestore(this, uid, contacts -> {
            List<String> notified = EmergencyContactManager.sendChildSafetyAlertSMS(
                    this, callerNumber, ownerName, contacts);
            logChildSafetyAlert(uid, callerNumber, transcriptSnapshot, notified, result, finalDisconnected);
            ChildSafetyAlertOverlay.show(this, notified.size(), shouldDisconnect);
        });
    }

    private void triggerMaxChildSafetyVibration() {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm == null) return;
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = {0, 700, 120, 700, 120, 700, 120, 700};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int[] amplitudes = {0, 255, 0, 255, 0, 255, 0, 255};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    private void logChildSafetyAlert(String uid, String callerNumber, String transcriptSnapshot,
                                     List<String> notified, ChildRiskResult result,
                                     boolean autoDisconnected) {
        if (uid == null) return;
        String snapshot = transcriptSnapshot != null ? transcriptSnapshot.trim() : "";
        if (snapshot.length() > 300) snapshot = snapshot.substring(snapshot.length() - 300);

        Map<String, Object> data = new HashMap<>();
        data.put("timestamp", Timestamp.now());
        data.put("callerNumber", callerNumber != null ? callerNumber : "Unknown");
        data.put("transcriptSnapshot", snapshot);
        data.put("emergencyContactsNotified", notified != null ? notified : new ArrayList<>());
        data.put("childSignalsDetected", result != null ? result.matchedChildSignals : new ArrayList<>());
        data.put("extractionPatternsDetected", result != null ? result.matchedExtractionPatterns : new ArrayList<>());
        data.put("nvidiaConfidenceScore", result != null ? result.nvidiaConfidenceScore : 0f);
        data.put("autoDisconnected", autoDisconnected);
        data.put("alertTriggered", true);

        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("child_safety_alerts")
                .add(data);
    }

    private String getCurrentUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    private String getOwnerName() {
        String identifier = getSharedPreferences("scam_shield_prefs", MODE_PRIVATE)
                .getString("user_identifier", "");
        if (identifier != null && !identifier.trim().isEmpty()) return identifier;
        String phone = getSharedPreferences("scam_shield_prefs", MODE_PRIVATE)
                .getString("user_phone", "");
        return phone != null && !phone.trim().isEmpty() ? phone : "the child";
    }

    private void startLiveSpeechAnalysis() {
        if (speechStartedForCall && (speechEngine.isListening() || liveMicTranscriber.isRunning())) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            broadcastTranscriptStatus("Microphone permission is missing. Grant RECORD_AUDIO and restart Call Trace.");
            return;
        }
        speechStartedForCall = true;
        broadcastTranscriptStatus("Preparing live transcription engines...");

        // Shorter delay — the engines now have their own internal resilience
        mainHandler.postDelayed(() -> {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) return;
            Log.i(TAG, "Starting live transcription + real-time scam analysis");

            speechEngine.stop();
            if (scamAnalyzer.hasAsrKey()) {
                broadcastTranscriptStatus("Listening to speakerphone through the microphone...");
                liveMicTranscriber.start();
                Log.i(TAG, "LiveMicTranscriber started as the only live transcription engine");
            } else {
                broadcastTranscriptStatus("NVIDIA ASR key required for live microphone transcription.");
                Log.w(TAG, "No ASR key; live microphone transcription cannot start");
            }
        }, 1000); // 1s delay for speaker routing to settle
    }

    private void routeCallToSpeakerForTranscription() {
        if (audioManager == null || audioRouteChangedForCall) return;

        try {
            previousSpeakerphoneOn = audioManager.isSpeakerphoneOn();
            previousAudioMode = audioManager.getMode();
            audioRouteChangedForCall = true;

            boolean routed = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        routed = audioManager.setCommunicationDevice(device);
                        Log.i(TAG, "setCommunicationDevice(SPEAKER) = " + routed);
                        break;
                    }
                }
            }

            if (!routed) {
                // Don't change audio mode during active telephony call.
                // MODE_IN_COMMUNICATION can fight the telephony stack
                // and redirect the mic away from call audio.
                audioManager.setSpeakerphoneOn(true);
                Log.i(TAG, "Speakerphone enabled via setSpeakerphoneOn(true)");
            }

            broadcastTranscriptStatus("Speakerphone route requested for live transcription. Keep the caller audible near the microphone.");
            Log.i(TAG, "Speakerphone routing requested for live transcription");
        } catch (Exception e) {
            Log.w(TAG, "Could not route call audio to speakerphone", e);
            broadcastTranscriptStatus("Turn on in-call speakerphone for live transcription. Full call volume alone is not enough.");
        }
    }

    private void restoreAudioRouteAfterCall() {
        if (audioManager == null || !audioRouteChangedForCall) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            }
            audioManager.setSpeakerphoneOn(previousSpeakerphoneOn);
            audioManager.setMode(previousAudioMode);
        } catch (Exception e) {
            Log.w(TAG, "Could not restore audio route after call", e);
        } finally {
            audioRouteChangedForCall = false;
        }
    }

    private void scheduleOffhookFallbackCheck() {
        mainHandler.postDelayed(this::promoteToActiveIfPhoneIsOffhook, 1500);
        mainHandler.postDelayed(this::promoteToActiveIfPhoneIsOffhook, 3500);
    }

    @SuppressWarnings("deprecation")
    private void promoteToActiveIfPhoneIsOffhook() {
        if (lastCallState != TelephonyManager.CALL_STATE_RINGING) return;
        try {
            if (telephonyManager != null
                    && telephonyManager.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK) {
                Log.i(TAG, "Fallback detected OFFHOOK; promoting call to active analysis");
                handleCallStateChange(TelephonyManager.CALL_STATE_OFFHOOK, true);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot poll call state for OFFHOOK fallback", e);
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
        intent.putExtra("transcript_snapshot",
                buildTranscriptSnapshot(isPartial ? text : lastPartialTranscript));
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastTranscriptStatus(String text) {
        Intent intent = new Intent(MainActivity.ACTION_TRANSCRIPT);
        intent.putExtra("text", text);
        intent.putExtra("status", true);
        intent.putExtra("transcript_snapshot", buildTranscriptSnapshot(lastPartialTranscript));
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastTranscriptSnapshot() {
        String snapshot = buildTranscriptSnapshot(lastPartialTranscript);
        if (snapshot.isEmpty()) return;

        Intent intent = new Intent(MainActivity.ACTION_TRANSCRIPT);
        intent.putExtra("text", snapshot);
        intent.putExtra("snapshot", true);
        intent.putExtra("transcript_snapshot", snapshot);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastThreatUpdate(ScamAnalyzerAI.AnalysisResult result) {
        Intent intent = new Intent(MainActivity.ACTION_THREAT_UPDATE);
        intent.putExtra("confidence", result.confidence);
        intent.putExtra("level", result.getThreatLevel());
        intent.putExtra("type", result.threatType);
        intent.putExtra("from_cloud", result.fromCloud);
        intent.putExtra("transcript_snapshot", buildTranscriptSnapshot(lastPartialTranscript));
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

    private void broadcastCurrentCallStatus() {
        if (lastCallState == TelephonyManager.CALL_STATE_RINGING) {
            broadcastCallState("RINGING", currentIncomingNumber);
        } else if (lastCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
            broadcastCallState("ACTIVE", currentIncomingNumber);
            broadcastTranscriptSnapshot();
            if (isChildSafetyModeEnabled() && !isContactNumber) {
                scheduleHardChildSafetyAlert();
                return;
            }
            if (!speechStartedForCall) {
                startLiveSpeechAnalysis();
            }
        } else {
            broadcastCallState("IDLE", null);
        }
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
    // SCAM ALERT - three-pulse cut-call signal
    // ═══════════════════════════════════════════════════════════════

    private void triggerLivePeakAlertOnce() {
        if (livePeakAlertTriggered) return;
        livePeakAlertTriggered = true;
        triggerScamAlert();
    }

    private void triggerScamAlert() {
        Log.w(TAG, "SCAM ALERT - three-pulse cut-call signal");

        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm == null) return;
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = {0, 450, 160, 450, 160, 450};
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
