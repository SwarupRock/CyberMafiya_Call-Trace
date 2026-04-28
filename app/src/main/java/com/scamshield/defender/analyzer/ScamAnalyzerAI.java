package com.scamshield.defender.analyzer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScamAnalyzerAI {

    private static final String TAG = "ScamAnalyzerAI";
    private static final float LOCAL_ALERT_THRESHOLD = 0.40f;
    private static final float SCAM_CONFIRMED_THRESHOLD = 0.70f;
    private static final float CLOUD_TRIGGER_THRESHOLD = 0.30f;
    private static final String PREFS_NAME = "scam_shield_prefs";
    private static final String PREF_GEMINI_API_KEY = "gemini_api_key";
    private static final String GEMINI_MODEL = "gemini-2.0-flash";
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                    GEMINI_MODEL + ":generateContent?key=";

    private static final Map<String, String[]> SCAM_KEYWORDS = new HashMap<>();

    static {
        SCAM_KEYWORDS.put("banking", new String[]{
                "otp", "cvv", "card number", "bank account", "upi pin",
                "credit card", "debit card", "account number", "ifsc",
                "transfer money", "net banking", "mobile banking", "upi",
                "paytm", "google pay", "phonepe", "share otp", "tell me otp",
                "give me otp", "send otp", "read otp", "otp number",
                "bank details", "account details", "card details",
                "transaction failed", "refund", "cashback credited",
                "calling from sbi", "calling from hdfc", "calling from icici",
                "calling from axis", "calling from kotak", "calling from bank",
                "your account has been", "your card has been", "your loan",
                "emi due", "overdue payment", "pending amount",
                "insurance claim", "policy expired", "premium due"
        });
        SCAM_KEYWORDS.put("kyc", new String[]{
                "kyc", "verification", "verify your", "account blocked",
                "account suspended", "update kyc", "pan card", "aadhaar",
                "aadhar", "pan number", "kyc expired", "complete kyc",
                "link aadhaar", "link aadhar", "verify identity", "identity proof"
        });
        SCAM_KEYWORDS.put("urgency", new String[]{
                "immediately", "right now", "urgent", "last chance",
                "within 24 hours", "your account will be", "act now",
                "do it now", "deadline", "final warning", "before midnight",
                "expiring today", "closing soon", "limited time",
                "do it fast", "hurry up", "time is running out"
        });
        SCAM_KEYWORDS.put("authority", new String[]{
                "police", "cbi", "income tax", "government", "rbi",
                "reserve bank", "court order", "legal action", "arrest warrant",
                "digital arrest", "cyber crime", "customs", "narcotics", "fir filed",
                "enforcement directorate", "sebi", "trai", "department of telecom"
        });
        SCAM_KEYWORDS.put("lottery", new String[]{
                "lottery", "prize", "won", "winner", "congratulations",
                "lucky draw", "claim your", "reward", "crore", "lakh won",
                "gift card", "free gift", "selected for", "chosen for"
        });
        SCAM_KEYWORDS.put("tech_support", new String[]{
                "anydesk", "teamviewer", "remote access", "install app",
                "download app", "screen share", "quick support", "give access",
                "share screen", "control your phone", "access your device"
        });
        SCAM_KEYWORDS.put("threats", new String[]{
                "disconnect", "electricity", "gas connection", "sim card",
                "deactivate", "parcel", "courier", "drugs found",
                "money laundering", "your number is involved", "complaint registered",
                "case filed", "warrant issued", "jail", "prison"
        });
        SCAM_KEYWORDS.put("call_bomber", new String[]{
                "pick up the phone", "answer my call", "why not answering",
                "i will keep calling", "harassment", "threatening"
        });
    }

    private final Context appContext;
    private final ExecutorService cloudExecutor = Executors.newSingleThreadExecutor();
    private final Gson gson = new Gson();
    private final List<String> detectedKeywords = new ArrayList<>();

    private float currentScore = 0f;
    private String threatCategory = "none";
    private int analysisCount = 0;
    private boolean cloudAnalysisDone = false;
    private AnalysisResult latestResult;
    private ScamDetectionListener scamListener;

    public ScamAnalyzerAI(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public interface ScamDetectionListener {
        void onAnalysisUpdate(AnalysisResult result);
        void onScamAlert(AnalysisResult result);
        void onScamConfirmed(AnalysisResult result);
    }

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
            if (confidence >= 0.70f) return "CRITICAL";
            if (confidence >= 0.50f) return "HIGH";
            if (confidence >= 0.30f) return "MEDIUM";
            if (confidence >= 0.10f) return "LOW";
            return "SAFE";
        }
    }

    private static class CloudResponse {
        @SerializedName("is_scam") boolean isScam;
        @SerializedName("confidence") float confidence;
        @SerializedName("threat_type") String threatType;
        @SerializedName("reasoning") String reasoning;
        @SerializedName("keywords_found") List<String> keywordsFound;
    }

    public void setScamDetectionListener(ScamDetectionListener listener) {
        this.scamListener = listener;
    }

    public void analyzeChunk(String textChunk, String fullTranscript) {
        analysisCount++;
        Log.d(TAG, "Analyzing chunk #" + analysisCount + ": " + textChunk);

        float keywordScore = runKeywordAnalysis(textChunk);
        float patternScore = runPatternAnalysis(fullTranscript);
        float combinedLocal = (keywordScore * 0.6f) + (patternScore * 0.4f);
        currentScore = Math.max(currentScore, combinedLocal);

        AnalysisResult result = new AnalysisResult();
        result.confidence = currentScore;
        result.isScam = currentScore >= LOCAL_ALERT_THRESHOLD;
        result.threatType = threatCategory;
        result.keywordsFound = new ArrayList<>(detectedKeywords);
        result.fromCloud = false;
        result.reasoning = detectedKeywords.isEmpty()
                ? "No scam indicators detected"
                : "Detected " + detectedKeywords.size() + " scam indicators in " + threatCategory;

        latestResult = result;
        notifyListeners(result);

        if (currentScore >= CLOUD_TRIGGER_THRESHOLD && !cloudAnalysisDone
                && fullTranscript.length() > 20) {
            triggerCloudAnalysis(fullTranscript);
        }
    }

    public void reset() {
        currentScore = 0f;
        detectedKeywords.clear();
        threatCategory = "none";
        analysisCount = 0;
        cloudAnalysisDone = false;
        latestResult = null;
    }

    public AnalysisResult getLatestResult() {
        return latestResult;
    }

    public float getCurrentScore() {
        return currentScore;
    }

    private void notifyListeners(AnalysisResult result) {
        if (scamListener == null) return;

        scamListener.onAnalysisUpdate(result);
        if (currentScore >= SCAM_CONFIRMED_THRESHOLD) {
            scamListener.onScamConfirmed(result);
        } else if (currentScore >= LOCAL_ALERT_THRESHOLD) {
            scamListener.onScamAlert(result);
        }
    }

    private float runKeywordAnalysis(String text) {
        String lower = text.toLowerCase();
        float score = 0f;
        float maxCategoryScore = 0f;

        for (Map.Entry<String, String[]> entry : SCAM_KEYWORDS.entrySet()) {
            String category = entry.getKey();
            float categoryScore = 0f;

            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword) && !detectedKeywords.contains(keyword)) {
                    detectedKeywords.add(keyword);
                    categoryScore += getKeywordWeight(category);
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

    private float getKeywordWeight(String category) {
        switch (category) {
            case "banking":
                return 0.20f;
            case "authority":
            case "tech_support":
                return 0.18f;
            case "threats":
            case "kyc":
            case "lottery":
                return 0.15f;
            case "urgency":
                return 0.10f;
            default:
                return 0.10f;
        }
    }

    private float runPatternAnalysis(String fullTranscript) {
        String lower = fullTranscript.toLowerCase();
        float score = 0f;

        int urgencyCount = 0;
        String[] urgencyPhrases = {"immediately", "right now", "urgent", "hurry",
                "now or", "last chance", "time is running"};
        for (String phrase : urgencyPhrases) {
            if (lower.contains(phrase)) urgencyCount++;
        }
        if (urgencyCount >= 2) score += 0.25f;

        String[] credentialRequests = {
                "what is your", "tell me your", "share your",
                "give me your", "confirm your", "verify your"
        };
        for (String pattern : credentialRequests) {
            if (lower.contains(pattern)) {
                score += 0.15f;
                break;
            }
        }

        boolean hasFinancial = lower.contains("money") || lower.contains("transfer")
                || lower.contains("payment") || lower.contains("rupees")
                || lower.contains("amount");
        boolean hasPressure = lower.contains("now") || lower.contains("immediate")
                || lower.contains("urgent");
        if (hasFinancial && hasPressure) score += 0.20f;

        String[] impersonation = {"i am calling from", "this is from",
                "we are from", "calling from bank"};
        for (String pattern : impersonation) {
            if (lower.contains(pattern)) {
                score += 0.10f;
                break;
            }
        }

        int words = fullTranscript.trim().isEmpty()
                ? 0
                : fullTranscript.trim().split("\\s+").length;
        if (words > 0 && detectedKeywords.size() > 0) {
            float density = (float) detectedKeywords.size() / words;
            if (density > 0.1f) score += 0.15f;
        }

        return Math.min(score, 1.0f);
    }

    private void triggerCloudAnalysis(String transcript) {
        cloudAnalysisDone = true;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String apiKey = prefs.getString(PREF_GEMINI_API_KEY, "");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Log.w(TAG, "Gemini API key missing. Local analysis remains active.");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                CloudResponse cloud = callGemini(apiKey.trim(), transcript);
                applyCloudResult(cloud);
            } catch (Exception e) {
                Log.w(TAG, "Gemini analysis failed. Local analysis remains active: " + e.getMessage());
            }
        });
    }

    private CloudResponse callGemini(String apiKey, String transcript) throws Exception {
        URL url = new URL(GEMINI_ENDPOINT + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String prompt = "You are Call Trace analyzing an English phone-call transcript in real time. " +
                "Return only JSON with keys: is_scam boolean, confidence number 0-1, " +
                "threat_type string, reasoning string, keywords_found string array. " +
                "Flag OTP, bank or KYC impersonation, urgent account freeze, remote access, legal threats, " +
                "refund traps, and credential requests. Transcript: " + transcript;

        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", prompt);

        JsonArray parts = new JsonArray();
        parts.add(textPart);

        JsonObject content = new JsonObject();
        content.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.1);
        generationConfig.addProperty("responseMimeType", "application/json");

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Gemini HTTP " + code + ": " + response);
        }

        String modelText = extractGeminiText(response.toString());
        return gson.fromJson(modelText, CloudResponse.class);
    }

    private String extractGeminiText(String responseJson) {
        JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.size() == 0) {
            throw new IllegalStateException("Gemini returned no candidates");
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        JsonArray parts = content.getAsJsonArray("parts");
        if (parts == null || parts.size() == 0) {
            throw new IllegalStateException("Gemini returned no text parts");
        }
        return parts.get(0).getAsJsonObject().get("text").getAsString();
    }

    private void applyCloudResult(CloudResponse cloud) {
        if (cloud == null) return;

        if (cloud.confidence > currentScore) {
            currentScore = cloud.confidence;
        }

        AnalysisResult result = new AnalysisResult();
        result.confidence = currentScore;
        result.isScam = cloud.isScam;
        result.threatType = cloud.threatType != null ? cloud.threatType : threatCategory;
        result.reasoning = cloud.reasoning != null ? cloud.reasoning : "Gemini AI analysis";
        result.keywordsFound = cloud.keywordsFound != null && !cloud.keywordsFound.isEmpty()
                ? new ArrayList<>(cloud.keywordsFound)
                : new ArrayList<>(detectedKeywords);
        result.fromCloud = true;

        latestResult = result;
        notifyListeners(result);

        Log.i(TAG, "Gemini verdict: " + (cloud.isScam ? "SCAM" : "SAFE")
                + " (" + (cloud.confidence * 100) + "%)");
    }
}
