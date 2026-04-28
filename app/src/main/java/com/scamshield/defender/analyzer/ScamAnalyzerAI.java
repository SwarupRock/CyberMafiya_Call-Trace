package com.scamshield.defender.analyzer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
    private static final String PREF_NVIDIA_LLM_API_KEY = "nvidia_llm_api_key";
    private static final String PREF_NVIDIA_ASR_API_KEY = "nvidia_asr_api_key";
    private static final String NVIDIA_MODEL = "meta/llama-3.1-8b-instruct";
    private static final String NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String NVIDIA_ASR_MODEL = "nvidia/parakeet-ctc-1_1b-asr";
    private static final String NVIDIA_ASR_ENDPOINT = "https://integrate.api.nvidia.com/v1/audio/transcriptions";

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
            if (confidence >= 0.70f) return "HIGH RISK";
            if (confidence >= 0.40f) return "MEDIUM RISK";
            if (confidence >= 0.10f) return "LOW RISK";
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
        String apiKey = prefs.getString(PREF_NVIDIA_LLM_API_KEY, "");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Log.w(TAG, "NVIDIA LLM API key missing. Local analysis remains active.");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                CloudResponse cloud = callNvidia(apiKey.trim(), transcript);
                applyCloudResult(cloud);
            } catch (Exception e) {
                Log.w(TAG, "NVIDIA analysis failed. Local analysis remains active: " + e.getMessage());
            }
        });
    }

    private CloudResponse callNvidia(String apiKey, String transcript) throws Exception {
        URL url = new URL(NVIDIA_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);

        String prompt = "You are Call Trace analyzing an English phone-call transcript in real time. " +
                "Return only JSON with keys: is_scam boolean, confidence number 0-1, " +
                "threat_type string, reasoning string, keywords_found string array. " +
                "Flag OTP, bank or KYC impersonation, urgent account freeze, remote access, legal threats, " +
                "refund traps, and credential requests. Transcript: " + transcript;

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "Return strict JSON only. No markdown.");
        messages.add(systemMessage);
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);

        JsonObject body = new JsonObject();
        body.addProperty("model", NVIDIA_MODEL);
        body.add("messages", messages);
        body.addProperty("temperature", 0.1);
        body.addProperty("max_tokens", 512);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        String response = readHttpResponse(conn, code);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("NVIDIA HTTP " + code + ": " + response);
        }

        String modelText = extractChatCompletionText(response);
        return gson.fromJson(stripJsonFence(modelText), CloudResponse.class);
    }

    private String readHttpResponse(HttpURLConnection conn, int code) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        return response.toString();
    }

    private String extractChatCompletionText(String responseJson) {
        JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new IllegalStateException("NVIDIA returned no choices");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new IllegalStateException("NVIDIA returned no message content");
        }
        return message.get("content").getAsString();
    }

    private String stripJsonFence(String text) {
        if (text == null) return "";
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?", "").trim();
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        }
        return cleaned;
    }

    private void applyCloudResult(CloudResponse cloud) {
        if (cloud == null) return;

        AnalysisResult result = buildCloudAnalysisResult(cloud, currentScore, threatCategory);
        currentScore = result.confidence;
        threatCategory = result.threatType;

        latestResult = result;
        notifyListeners(result);

        Log.i(TAG, "Cloud verdict: " + (cloud.isScam ? "SCAM" : "SAFE")
                + " (" + (cloud.confidence * 100) + "%)");
    }

    private AnalysisResult buildCloudAnalysisResult(CloudResponse cloud, float fallbackScore, String fallbackThreat) {
        AnalysisResult result = new AnalysisResult();
        result.confidence = Math.max(cloud.confidence, fallbackScore);
        result.isScam = cloud.isScam || result.confidence >= LOCAL_ALERT_THRESHOLD;
        result.threatType = cloud.threatType != null ? cloud.threatType : fallbackThreat;
        result.reasoning = cloud.reasoning != null ? cloud.reasoning : "NVIDIA AI analysis";
        result.keywordsFound = cloud.keywordsFound != null && !cloud.keywordsFound.isEmpty()
                ? new ArrayList<>(cloud.keywordsFound)
                : new ArrayList<>(detectedKeywords);
        result.fromCloud = true;
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // AUDIO FILE ANALYSIS (User Upload)
    // ═══════════════════════════════════════════════════════════════

    public interface AudioAnalysisCallback {
        void onTranscriptReady(String transcript);
        void onAnalysisComplete(AnalysisResult result, String transcript);
        void onError(String error);
    }

    private static class AudioFileResponse {
        @SerializedName("transcript") String transcript;
        @SerializedName("is_scam") boolean isScam;
        @SerializedName("confidence") float confidence;
        @SerializedName("threat_type") String threatType;
        @SerializedName("risk_level") String riskLevel;
        @SerializedName("reasoning") String reasoning;
        @SerializedName("keywords_found") List<String> keywordsFound;
    }

    public void analyzeAudioFile(byte[] audioData, String mimeType, AudioAnalysisCallback callback) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String asrKey = prefs.getString(PREF_NVIDIA_ASR_API_KEY, "");
        String llmKey = prefs.getString(PREF_NVIDIA_LLM_API_KEY, "");
        if (asrKey == null || asrKey.trim().isEmpty()) {
            callback.onError("NVIDIA ASR/Riva API key is required for audio transcription.");
            return;
        }
        if (llmKey == null || llmKey.trim().isEmpty()) {
            callback.onError("NVIDIA LLM API key is required for scam analysis.");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                String transcript = transcribeAudioWithNvidia(asrKey.trim(), audioData, mimeType);
                if (transcript == null || transcript.trim().isEmpty()) {
                    throw new IllegalStateException("NVIDIA ASR returned an empty transcript.");
                }

                String cleanedTranscript = transcript.trim();
                callback.onTranscriptReady(cleanedTranscript);

                CloudResponse cloud = callNvidia(llmKey.trim(), cleanedTranscript);
                AnalysisResult result = buildCloudAnalysisResult(cloud, 0f, "none");
                callback.onAnalysisComplete(result, cleanedTranscript);
            } catch (Exception e) {
                Log.e(TAG, "NVIDIA audio analysis failed", e);
                callback.onError("NVIDIA audio analysis failed: " + e.getMessage());
            }
        });
    }

    private String transcribeAudioWithNvidia(String apiKey, byte[] audioData, String mimeType) throws Exception {
        URL url = new URL(NVIDIA_ASR_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        String boundary = "CallTraceBoundary" + System.currentTimeMillis();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            writeMultipartField(os, boundary, "model", NVIDIA_ASR_MODEL);
            writeMultipartField(os, boundary, "response_format", "json");
            writeMultipartFile(os, boundary, "file", "call-trace-audio", mimeType, audioData);
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        String response = readHttpResponse(conn, code);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("NVIDIA ASR HTTP " + code + ": " + response);
        }

        return extractTranscriptText(response);
    }

    private void writeMultipartField(OutputStream os, String boundary, String name, String value) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(value.getBytes(StandardCharsets.UTF_8));
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeMultipartFile(OutputStream os, String boundary, String name, String filename,
                                    String mimeType, byte[] data) throws Exception {
        String contentType = mimeType == null || mimeType.trim().isEmpty() ? "audio/mpeg" : mimeType;
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String extractTranscriptText(String responseJson) {
        JsonElement parsed = JsonParser.parseString(responseJson);
        if (!parsed.isJsonObject()) {
            return responseJson;
        }

        JsonObject root = parsed.getAsJsonObject();
        if (root.has("text") && !root.get("text").isJsonNull()) {
            return root.get("text").getAsString();
        }
        if (root.has("transcript") && !root.get("transcript").isJsonNull()) {
            return root.get("transcript").getAsString();
        }
        if (root.has("results") && root.get("results").isJsonArray()) {
            return joinTranscriptArray(root.getAsJsonArray("results"));
        }
        if (root.has("segments") && root.get("segments").isJsonArray()) {
            return joinTranscriptArray(root.getAsJsonArray("segments"));
        }
        throw new IllegalStateException("NVIDIA ASR response did not include transcript text.");
    }

    private String joinTranscriptArray(JsonArray array) {
        StringBuilder transcript = new StringBuilder();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String part = null;
            if (object.has("text") && !object.get("text").isJsonNull()) {
                part = object.get("text").getAsString();
            } else if (object.has("transcript") && !object.get("transcript").isJsonNull()) {
                part = object.get("transcript").getAsString();
            }
            if (part != null && !part.trim().isEmpty()) {
                if (transcript.length() > 0) transcript.append(' ');
                transcript.append(part.trim());
            }
        }
        return transcript.toString();
    }
}
