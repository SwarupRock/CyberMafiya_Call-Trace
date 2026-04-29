package com.scamshield.defender.analyzer;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * ═══════════════════════════════════════════════════════════════════
 * SpeechToTextEngine — Real-Time Speech Recognition (No Recording)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Uses Android's built-in SpeechRecognizer for continuous
 * speech-to-text during active calls. Does NOT record or save audio.
 *
 * v2 — Aggressive persistence during telephony calls:
 *   - Uses on-device recognizer (Android 13+) to bypass cloud issues
 *   - Extends all silence thresholds so recognizer doesn't auto-stop
 *   - Watchdog timer restarts recognizer if it silently dies
 *   - Never gives up while shouldContinue is true
 *
 * IMPORTANT: Phone must be on speaker for mic to capture caller voice
 */
public class SpeechToTextEngine {

    private static final String TAG = "SpeechToText";
    private static final int RESTART_DELAY_MS = 500;
    private static final int MAX_RESTART_DELAY_MS = 3000;
    private static final long USER_VISIBLE_ERROR_INTERVAL_MS = 8000L;
    private static final long WATCHDOG_INTERVAL_MS = 12000L;

    private final Context context;
    private SpeechRecognizer recognizer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean isListening = false;
    private volatile boolean shouldContinue = false;
    private long lastUserVisibleErrorAt = 0L;
    private float peakRmsSinceStart = Float.NEGATIVE_INFINITY;
    private int consecutiveErrors = 0;
    private long lastResultTime = 0L;
    private long startTime = 0L;
    private boolean gotAnyResult = false;
    private static final String SPEECH_LANGUAGE = "en-US";

    private TranscriptListener listener;

    // Full transcript accumulated during the call
    private final StringBuilder fullTranscript = new StringBuilder();

    // Watchdog runnable
    private final Runnable watchdogRunnable = this::watchdog;

    // ═══════════════════════════════════════════════════════════════
    // CALLBACK INTERFACE
    // ═══════════════════════════════════════════════════════════════

    public interface TranscriptListener {
        /** Called with partial (in-progress) text */
        void onPartialResult(String partialText);

        /** Called with final recognized text chunk */
        void onFinalResult(String finalText, String fullTranscript);

        /** Called when an error occurs */
        void onError(String error);
    }

    // ═══════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ═══════════════════════════════════════════════════════════════

    public SpeechToTextEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setTranscriptListener(TranscriptListener listener) {
        this.listener = listener;
    }

    // ═══════════════════════════════════════════════════════════════
    // START / STOP
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start continuous speech recognition.
     * Must be called from the main thread.
     */
    public void start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device");
            if (listener != null) listener.onError("Speech recognition not available");
            return;
        }

        shouldContinue = true;
        fullTranscript.setLength(0);
        consecutiveErrors = 0;
        gotAnyResult = false;
        startTime = System.currentTimeMillis();

        mainHandler.post(this::initAndListen);
        startWatchdog();
        Log.i(TAG, "Speech-to-text engine STARTED (English only)");
    }

    /**
     * Stop speech recognition.
     */
    public void stop() {
        Log.i(TAG, "🛑 Speech-to-text engine STOPPED");
        shouldContinue = false;
        isListening = false;
        stopWatchdog();

        mainHandler.post(() -> {
            destroyRecognizer();
        });
    }

    public boolean isListening() {
        return isListening || shouldContinue;
    }

    public String getFullTranscript() {
        return fullTranscript.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL — Recognition Loop
    // ═══════════════════════════════════════════════════════════════

    private void initAndListen() {
        if (!shouldContinue) return;

        try {
            destroyRecognizer();

            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            Log.i(TAG, "Using Android speech recognizer with English (US)");

            recognizer.setRecognitionListener(new InternalListener());
            peakRmsSinceStart = Float.NEGATIVE_INFINITY;

            // Configure recognition intent for MAXIMUM persistence
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, SPEECH_LANGUAGE);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, SPEECH_LANGUAGE);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());

            // ── KEY: Extended silence thresholds ──────────────────
            // During calls, there can be long pauses. Don't let the
            // recognizer stop just because there's silence.
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 7000);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000);

            // Use the online/system recognizer, not missing on-device packs.
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);

            // Do not force android.speech.extra.AUDIO_SOURCE here. It is not
            // a public SpeechRecognizer contract and can make OEM recognizers
            // bind to blocked in-call audio routes. Let Android choose its
            // normal speech microphone path.

            recognizer.startListening(intent);
            isListening = true;
            Log.d(TAG, "Recognition started (attempt " + (consecutiveErrors + 1)
                    + ", language=" + SPEECH_LANGUAGE + ")");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recognition", e);
            isListening = false;
            // Retry after delay
            if (shouldContinue) {
                int delay = Math.min(1000 * (consecutiveErrors + 1), MAX_RESTART_DELAY_MS);
                mainHandler.postDelayed(this::initAndListen, delay);
            }
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.stopListening();
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping recognizer", e);
            }
            recognizer = null;
        }
    }

    /**
     * Restart recognition after a brief delay (continuous listening loop).
     */
    private void restartListening() {
        if (shouldContinue) {
            int delay = consecutiveErrors > 5
                    ? MAX_RESTART_DELAY_MS
                    : Math.min(RESTART_DELAY_MS * Math.max(1, consecutiveErrors), MAX_RESTART_DELAY_MS);
            mainHandler.postDelayed(this::initAndListen, delay);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WATCHDOG — restart if recognizer silently dies
    // ═══════════════════════════════════════════════════════════════

    private void startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable);
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    private void stopWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable);
    }

    private void watchdog() {
        if (!shouldContinue) return;

        long elapsed = System.currentTimeMillis() - startTime;
        long sinceLastResult = lastResultTime > 0
                ? System.currentTimeMillis() - lastResultTime
                : elapsed;

        Log.d(TAG, "Watchdog check: isListening=" + isListening
                + " elapsed=" + (elapsed / 1000) + "s"
                + " sinceResult=" + (sinceLastResult / 1000) + "s"
                + " errors=" + consecutiveErrors
                + " gotResult=" + gotAnyResult);

        // If we haven't heard anything in a while, force restart
        if (!isListening || sinceLastResult > WATCHDOG_INTERVAL_MS * 2) {
            Log.w(TAG, "Watchdog: recognizer appears dead, force restarting");
            notifyUserVisibleError("Restarting speech recognition...");
            destroyRecognizer();
            isListening = false;
            mainHandler.postDelayed(this::initAndListen, 500);
        }

        // Schedule next check
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    // ═══════════════════════════════════════════════════════════════
    // RECOGNITION LISTENER
    // ═══════════════════════════════════════════════════════════════

    private class InternalListener implements RecognitionListener {

        @Override
        public void onReadyForSpeech(Bundle params) {
            Log.d(TAG, "✅ Ready for speech — recognizer is listening");
            consecutiveErrors = 0; // Reset on successful start
        }

        @Override
        public void onBeginningOfSpeech() {
            Log.d(TAG, "🗣️ Speech started — hearing audio");
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            peakRmsSinceStart = Math.max(peakRmsSinceStart, rmsdB);
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
            // Raw audio buffer — we don't record, just analyze
        }

        @Override
        public void onEndOfSpeech() {
            Log.d(TAG, "Speech ended");
            isListening = false;
        }

        @Override
        public void onError(int error) {
            isListening = false;
            consecutiveErrors++;
            String errorMsg;

            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                    // This is THE most common "error" during calls — not really an error,
                    // just means no speech was detected in this listening window.
                    if (peakRmsSinceStart < 1.0f) {
                        errorMsg = "Listening... Mic hears silence. Ensure in-call speakerphone is ON (not just volume up).";
                    } else {
                        errorMsg = "Hearing audio (level " + Math.round(peakRmsSinceStart)
                                + ") but no clear words yet. Keep caller audible.";
                    }
                    // Don't count this as a real error
                    consecutiveErrors = Math.max(0, consecutiveErrors - 1);
                    Log.d(TAG, errorMsg);
                    restartListening();
                    return;
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    errorMsg = "Listening for speech... (ensure speakerphone is ON)";
                    consecutiveErrors = Math.max(0, consecutiveErrors - 1);
                    Log.d(TAG, errorMsg);
                    restartListening();
                    return;
                case SpeechRecognizer.ERROR_AUDIO:
                    errorMsg = "Android could not open the speech microphone. Retrying...";
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    errorMsg = "Network issue. English speech recognition needs internet or Google Speech Services.";
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    errorMsg = "Microphone permission required";
                    if (listener != null) listener.onError(errorMsg);
                    return; // Don't restart
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    errorMsg = "Recognizer busy, retrying...";
                    break;
                case SpeechRecognizer.ERROR_CLIENT:
                    errorMsg = "Recognition client error, restarting...";
                    break;
                case SpeechRecognizer.ERROR_SERVER:
                    errorMsg = "English speech recognition server error, retrying...";
                    break;
                case 10: // ERROR_TOO_MANY_REQUESTS
                    errorMsg = "Android speech service is throttling. Waiting before retry...";
                    consecutiveErrors += 2; // Extra backoff
                    break;
                case 11: // ERROR_LANGUAGE_NOT_SUPPORTED
                case 12: // ERROR_LANGUAGE_UNAVAILABLE
                    errorMsg = "English speech recognition is unavailable. Install or update Google Speech Services and English (US).";
                    break;
                default:
                    errorMsg = "Recognition error (" + error + "), restarting...";
                    break;
            }

            Log.w(TAG, "Error " + error + ": " + errorMsg
                    + " (consecutive=" + consecutiveErrors + ")");
            notifyUserVisibleError(errorMsg);
            // ALWAYS restart for continuous listening — never give up
            restartListening();
        }

        @Override
        public void onResults(Bundle results) {
            isListening = false;
            consecutiveErrors = 0;
            ArrayList<String> matches = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);

            if (matches != null && !matches.isEmpty()) {
                String text = matches.get(0);
                if (!text.isEmpty()) {
                    gotAnyResult = true;
                    lastResultTime = System.currentTimeMillis();

                    // Append to full transcript
                    if (fullTranscript.length() > 0) {
                        fullTranscript.append(" ");
                    }
                    fullTranscript.append(text);

                    Log.i(TAG, "📝 Final: " + text);

                    if (listener != null) {
                        listener.onFinalResult(text, fullTranscript.toString());
                    }
                }
            }

            // Continue listening for more speech
            restartListening();
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> matches = partialResults.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);

            if (matches != null && !matches.isEmpty()) {
                String partial = matches.get(0);
                if (!partial.isEmpty()) {
                    lastResultTime = System.currentTimeMillis();
                    if (listener != null) {
                        listener.onPartialResult(partial);
                    }
                }
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
            // Not used
        }
    }

    private void notifyUserVisibleError(String errorMsg) {
        if (listener == null || errorMsg == null || errorMsg.trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastUserVisibleErrorAt < USER_VISIBLE_ERROR_INTERVAL_MS) return;

        lastUserVisibleErrorAt = now;
        listener.onError(errorMsg);
    }
}
