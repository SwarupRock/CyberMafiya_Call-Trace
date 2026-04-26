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
import java.util.Locale;

/**
 * ═══════════════════════════════════════════════════════════════════
 * SpeechToTextEngine — Real-Time Speech Recognition (No Recording)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Uses Android's built-in SpeechRecognizer for continuous
 * speech-to-text during active calls. Does NOT record or save audio.
 *
 * Flow:
 *   1. Start listening when call goes off-hook
 *   2. Stream partial results to UI (live transcript)
 *   3. Stream final results to ScamAnalyzerAI
 *   4. Automatically restart recognition for continuous listening
 *   5. Stop when call ends
 *
 * IMPORTANT: Phone must be on speaker for mic to capture caller voice
 */
public class SpeechToTextEngine {

    private static final String TAG = "SpeechToText";
    private static final int RESTART_DELAY_MS = 300;

    private final Context context;
    private SpeechRecognizer recognizer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean isListening = false;
    private volatile boolean shouldContinue = false;

    private TranscriptListener listener;

    // Full transcript accumulated during the call
    private final StringBuilder fullTranscript = new StringBuilder();

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

        mainHandler.post(this::initAndListen);
        Log.i(TAG, "🎙️ Speech-to-text engine STARTED");
    }

    /**
     * Stop speech recognition.
     */
    public void stop() {
        Log.i(TAG, "🛑 Speech-to-text engine STOPPED");
        shouldContinue = false;
        isListening = false;

        mainHandler.post(() -> {
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
        });
    }

    public boolean isListening() {
        return isListening;
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
            // Create new recognizer instance
            if (recognizer != null) {
                try {
                    recognizer.destroy();
                } catch (Exception ignored) {}
            }

            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new InternalListener());

            // Configure recognition intent
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN"); // Indian English
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            // Keep listening longer during pauses
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000);

            recognizer.startListening(intent);
            isListening = true;
            Log.d(TAG, "Recognition started");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recognition", e);
            isListening = false;
            // Retry after delay
            if (shouldContinue) {
                mainHandler.postDelayed(this::initAndListen, 1000);
            }
        }
    }

    /**
     * Restart recognition after a brief delay (continuous listening loop).
     */
    private void restartListening() {
        if (shouldContinue) {
            mainHandler.postDelayed(this::initAndListen, RESTART_DELAY_MS);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RECOGNITION LISTENER
    // ═══════════════════════════════════════════════════════════════

    private class InternalListener implements RecognitionListener {

        @Override
        public void onReadyForSpeech(Bundle params) {
            Log.d(TAG, "Ready for speech");
        }

        @Override
        public void onBeginningOfSpeech() {
            Log.d(TAG, "Speech started");
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            // Could use for audio level visualization
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
            String errorMsg;

            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                    errorMsg = "No speech detected";
                    break;
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    errorMsg = "Speech timeout";
                    break;
                case SpeechRecognizer.ERROR_AUDIO:
                    errorMsg = "Audio error";
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    errorMsg = "Network error — using offline mode";
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    errorMsg = "Microphone permission required";
                    if (listener != null) listener.onError(errorMsg);
                    return; // Don't restart
                default:
                    errorMsg = "Recognition error: " + error;
                    break;
            }

            Log.w(TAG, errorMsg);
            // Restart for continuous listening
            restartListening();
        }

        @Override
        public void onResults(Bundle results) {
            isListening = false;
            ArrayList<String> matches = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);

            if (matches != null && !matches.isEmpty()) {
                String text = matches.get(0);
                if (!text.isEmpty()) {
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
                if (!partial.isEmpty() && listener != null) {
                    listener.onPartialResult(partial);
                }
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
            // Not used
        }
    }
}
