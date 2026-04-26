package com.scamshield.defender.analyzer;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.scamshield.defender.network.FirebaseClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * ScamAnalyzerAI — Multi-Layer Real-Time Scam Detection
 * ═══════════════════════════════════════════════════════════════════
 *
 * Layer 1: Keyword Detection (instant, on-device)
 *   - 80+ scam keywords in 7 categories
 *   - Weighted scoring per category
 *
 * Layer 2: Pattern Analysis (on-device)
 *   - Repeated urgency phrases
 *   - Keyword density over time
 *   - Cumulative evidence scoring
 *
 * Layer 3: Gemini AI (cloud, via Supabase Edge Function)
 *   - Triggered when local score exceeds threshold
 *   - Deep contextual analysis of full transcript
 */
public class ScamAnalyzerAI {

    private static final String TAG = "ScamAnalyzerAI";

    // Thresholds
    private static final float LOCAL_ALERT_THRESHOLD = 0.40f;   // Trigger vibration warning
    private static final float SCAM_CONFIRMED_THRESHOLD = 0.70f; // Auto-block
    private static final float CLOUD_TRIGGER_THRESHOLD = 0.30f;  // Send to Gemini

    // ═══════════════════════════════════════════════════════════════
    // KEYWORD DATABASE (India-focused)
    // ═══════════════════════════════════════════════════════════════

    private static final Map<String, String[]> SCAM_KEYWORDS = new HashMap<>();
    static {
        SCAM_KEYWORDS.put("banking", new String[]{
                "otp", "cvv", "card number", "bank account", "upi pin",
                "credit card", "debit card", "account number", "ifsc",
                "neft", "rtgs", "transfer money", "net banking", "mobile banking",
                "upi", "paytm", "google pay", "phonepe", "bhim",
                "share otp", "tell me otp", "share your otp", "give me otp",
                "bank details", "account details", "card details",
                "transaction failed", "refund", "cashback credited"
        });

        SCAM_KEYWORDS.put("kyc", new String[]{
                "kyc", "verification", "verify your", "account blocked",
                "account suspended", "update kyc", "pan card", "aadhaar",
                "aadhar", "pan number", "link aadhaar", "kyc expired",
                "kyc update", "complete kyc", "kyc pending"
        });

        SCAM_KEYWORDS.put("urgency", new String[]{
                "immediately", "right now", "urgent", "last chance",
                "within 24 hours", "your account will be", "act now",
                "don't delay", "time is running out", "hurry up",
                "deadline", "expire", "expiring", "final warning",
                "do it now", "within 2 hours", "before midnight"
        });

        SCAM_KEYWORDS.put("authority", new String[]{
                "police", "cbi", "income tax", "government", "rbi",
                "reserve bank", "court order", "legal action",
                "arrest warrant", "digital arrest", "cyber crime",
                "enforcement", "customs", "narcotics",
                "tax department", "it department", "fir filed"
        });

        SCAM_KEYWORDS.put("lottery", new String[]{
                "lottery", "prize", "won", "winner", "congratulations",
                "lucky draw", "claim your", "reward", "bumper prize",
                "crore", "lakh won", "you have been selected"
        });

        SCAM_KEYWORDS.put("tech_support", new String[]{
                "anydesk", "teamviewer", "remote access", "install app",
                "download app", "screen share", "quick support",
                "give access", "control your phone"
        });

        SCAM_KEYWORDS.put("threats", new String[]{
                "disconnect", "electricity", "gas connection", "sim card",
                "deactivate", "parcel", "courier", "drugs found",
                "money laundering", "hawala", "terrorism",
                "your number is involved", "complaint registered"
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════

    private final List<String> detectedKeywords = new ArrayList<>();
    private float currentScore = 0f;
    private String threatCategory = "none";
    private int analysisCount = 0;
    private boolean cloudAnalysisDone = false;
    private AnalysisResult latestResult;
    private ScamDetectionListener scamListener;

    // ═══════════════════════════════════════════════════════════════
    // CALLBACK
    // ═══════════════════════════════════════════════════════════════

    public interface ScamDetectionListener {
        /** Called after each analysis with current threat level */
        void onAnalysisUpdate(AnalysisResult result);

        /** Called when confidence crosses the ALERT threshold */
        void onScamAlert(AnalysisResult result);

        /** Called when confidence crosses the CONFIRMED threshold */
        void onScamConfirmed(AnalysisResult result);
    }

    // ═══════════════════════════════════════════════════════════════
    // RESULT MODEL
    // ═══════════════════════════════════════════════════════════════

    public static class AnalysisResult {
        public float confidence;
        public boolean isScam;
        public String threatType;
        public String reasoning;
        public List<String> keywordsFound;
        public boolean fromCloud;

        public AnalysisResult() {
            keywordsFound = new ArrayList<>();
            threatType = "none";
            reasoning = "";
        }

        public String getThreatLevel() {
            if (confidence >= 0.70) return "CRITICAL";
            if (confidence >= 0.50) return "HIGH";
            if (confidence >= 0.30) return "MEDIUM";
            if (confidence >= 0.10) return "LOW";
            return "SAFE";
        }
    }

    /** Cloud response model (matches Edge Function output) */
    private static class CloudResponse {
        @SerializedName("is_scam") boolean isScam;
        @SerializedName("confidence") float confidence;
        @SerializedName("threat_type") String threatType;
        @SerializedName("reasoning") String reasoning;
        @SerializedName("keywords_found") List<String> keywordsFound;
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    public void setScamDetectionListener(ScamDetectionListener listener) {
        this.scamListener = listener;
    }

    /**
     * Analyze a new chunk of transcribed text.
     * Called every time SpeechToTextEngine produces a final result.
     */
    public void analyzeChunk(String textChunk, String fullTranscript) {
        analysisCount++;
        Log.d(TAG, "🔍 Analyzing chunk #" + analysisCount + ": \"" + textChunk + "\"");

        // ── Layer 1: Keyword Detection ────────────────────────────
        float keywordScore = runKeywordAnalysis(textChunk);

        // ── Layer 2: Pattern Analysis (on full transcript) ────────
        float patternScore = runPatternAnalysis(fullTranscript);

        // ── Combine scores ────────────────────────────────────────
        // Weight: 60% keywords, 40% patterns
        float combinedLocal = (keywordScore * 0.6f) + (patternScore * 0.4f);
        // Scores accumulate over time (evidence builds up)
        currentScore = Math.max(currentScore, combinedLocal);

        // ── Build result ──────────────────────────────────────────
        AnalysisResult result = new AnalysisResult();
        result.confidence = currentScore;
        result.isScam = currentScore >= LOCAL_ALERT_THRESHOLD;
        result.threatType = threatCategory;
        result.keywordsFound = new ArrayList<>(detectedKeywords);
        result.fromCloud = false;

        if (detectedKeywords.isEmpty()) {
            result.reasoning = "No scam indicators detected";
        } else {
            result.reasoning = "Detected " + detectedKeywords.size() +
                    " scam indicators in '" + threatCategory + "' category";
        }

        latestResult = result;

        // ── Notify listener ───────────────────────────────────────
        if (scamListener != null) {
            scamListener.onAnalysisUpdate(result);

            if (currentScore >= SCAM_CONFIRMED_THRESHOLD) {
                scamListener.onScamConfirmed(result);
            } else if (currentScore >= LOCAL_ALERT_THRESHOLD) {
                scamListener.onScamAlert(result);
            }
        }

        // ── Layer 3: Cloud AI (if suspicious but not certain) ─────
        if (currentScore >= CLOUD_TRIGGER_THRESHOLD && !cloudAnalysisDone
                && fullTranscript.length() > 20) {
            triggerCloudAnalysis(fullTranscript);
        }

        Log.i(TAG, String.format("📊 Score: %.0f%% | Keywords: %d | Category: %s",
                currentScore * 100, detectedKeywords.size(), threatCategory));
    }

    /** Reset analysis state (new call) */
    public void reset() {
        currentScore = 0f;
        detectedKeywords.clear();
        threatCategory = "none";
        analysisCount = 0;
        cloudAnalysisDone = false;
        latestResult = null;
        Log.i(TAG, "Analysis state RESET");
    }

    public AnalysisResult getLatestResult() {
        return latestResult;
    }

    public float getCurrentScore() {
        return currentScore;
    }

    // ═══════════════════════════════════════════════════════════════
    // LAYER 1: KEYWORD ANALYSIS
    // ═══════════════════════════════════════════════════════════════

    private float runKeywordAnalysis(String text) {
        String lower = text.toLowerCase();
        float score = 0f;
        float maxCategoryScore = 0f;

        for (Map.Entry<String, String[]> entry : SCAM_KEYWORDS.entrySet()) {
            String category = entry.getKey();
            String[] keywords = entry.getValue();
            float categoryScore = 0f;

            for (String keyword : keywords) {
                if (lower.contains(keyword) && !detectedKeywords.contains(keyword)) {
                    detectedKeywords.add(keyword);

                    // Weight by category danger level
                    float weight;
                    switch (category) {
                        case "banking":    weight = 0.20f; break;
                        case "authority":  weight = 0.18f; break;
                        case "threats":    weight = 0.15f; break;
                        case "kyc":        weight = 0.15f; break;
                        case "urgency":    weight = 0.10f; break;
                        case "tech_support": weight = 0.18f; break;
                        case "lottery":    weight = 0.15f; break;
                        default:           weight = 0.10f; break;
                    }
                    categoryScore += weight;
                }
            }

            score += categoryScore;
            if (categoryScore > maxCategoryScore) {
                maxCategoryScore = categoryScore;
                threatCategory = category;
            }
        }

        return Math.min(score, 1.0f);
    }

    // ═══════════════════════════════════════════════════════════════
    // LAYER 2: PATTERN ANALYSIS
    // ═══════════════════════════════════════════════════════════════

    private float runPatternAnalysis(String fullTranscript) {
        String lower = fullTranscript.toLowerCase();
        float score = 0f;

        // ── Check for multiple urgency phrases ────────────────────
        int urgencyCount = 0;
        String[] urgencyPhrases = {"immediately", "right now", "urgent", "hurry",
                "now or", "last chance", "time is running"};
        for (String phrase : urgencyPhrases) {
            if (lower.contains(phrase)) urgencyCount++;
        }
        if (urgencyCount >= 2) {
            score += 0.25f; // Heavy urgency = strong scam signal
        }

        // ── Check for question patterns ───────────────────────────
        String[] interrogationPatterns = {
                "what is your", "tell me your", "share your",
                "give me your", "confirm your", "verify your"
        };
        for (String pattern : interrogationPatterns) {
            if (lower.contains(pattern)) {
                score += 0.15f;
                break;
            }
        }

        // ── Check for financial pressure ──────────────────────────
        boolean hasFinancial = lower.contains("money") || lower.contains("transfer")
                || lower.contains("payment") || lower.contains("rupees")
                || lower.contains("amount");
        boolean hasPressure = lower.contains("now") || lower.contains("immediate")
                || lower.contains("urgent");
        if (hasFinancial && hasPressure) {
            score += 0.20f;
        }

        // ── Check for impersonation patterns ──────────────────────
        String[] impersonation = {"i am calling from", "this is from",
                "we are from", "calling from bank"};
        for (String pattern : impersonation) {
            if (lower.contains(pattern)) {
                score += 0.10f;
                break;
            }
        }

        // ── Keyword density bonus ─────────────────────────────────
        // If many scam keywords in short text = suspicious
        int words = fullTranscript.split("\\s+").length;
        if (words > 0 && detectedKeywords.size() > 0) {
            float density = (float) detectedKeywords.size() / words;
            if (density > 0.1f) {
                score += 0.15f; // High scam keyword density
            }
        }

        return Math.min(score, 1.0f);
    }

    // ═══════════════════════════════════════════════════════════════
    // LAYER 3: CLOUD AI ANALYSIS (Gemini via Supabase Edge Function)
    // ═══════════════════════════════════════════════════════════════

    private void triggerCloudAnalysis(String transcript) {
        cloudAnalysisDone = true;
        Log.i(TAG, "☁️ Sending to Gemini AI for deep analysis...");

        FirebaseClient.getInstance().analyzeCallWithAI(transcript,
                new FirebaseClient.DataCallback<FirebaseClient.CloudAnalysisResult>() {
                    @Override
                    public void onSuccess(FirebaseClient.CloudAnalysisResult cloud) {
                        try {
                            // Cloud result overrides local if higher confidence
                            if (cloud.scam_score > currentScore) {
                                currentScore = cloud.scam_score;
                            }

                            AnalysisResult result = new AnalysisResult();
                            result.confidence = currentScore;
                            result.isScam = cloud.is_scam;
                            result.threatType = cloud.scam_type != null ?
                                    cloud.scam_type : threatCategory;
                            result.reasoning = cloud.explanation != null ?
                                    cloud.explanation : "Cloud AI analysis";
                            
                            List<String> cloudKeywords = new ArrayList<>();
                            if (cloud.keywords != null) {
                                for (String kw : cloud.keywords) {
                                    cloudKeywords.add(kw);
                                }
                            }
                            result.keywordsFound = !cloudKeywords.isEmpty() ?
                                    cloudKeywords : new ArrayList<>(detectedKeywords);
                            result.fromCloud = true;

                            latestResult = result;

                            if (scamListener != null) {
                                scamListener.onAnalysisUpdate(result);

                                if (currentScore >= SCAM_CONFIRMED_THRESHOLD) {
                                    scamListener.onScamConfirmed(result);
                                } else if (currentScore >= LOCAL_ALERT_THRESHOLD) {
                                    scamListener.onScamAlert(result);
                                }
                            }

                            Log.i(TAG, "☁️ Gemini verdict: " + (cloud.is_scam ? "SCAM" : "SAFE")
                                    + " (" + (cloud.scam_score * 100) + "%)");

                        } catch (Exception e) {
                            Log.e(TAG, "Failed to parse cloud response", e);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Cloud analysis failed (local analysis still active): " + error);
                        // Local analysis continues to work
                    }
                });
    }
}
