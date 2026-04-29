package com.scamshield.defender.child;

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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ChildSafetyAnalyzer {
    private static final String TAG = "ChildSafetyAnalyzer";
    private static final String PREFS_NAME = "scam_shield_prefs";
    private static final String PREF_NVIDIA_LLM_API_KEY = "nvidia_llm_api_key";
    private static final String NVIDIA_MODEL = "meta/llama-3.1-8b-instruct";
    private static final String NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions";

    public static final List<String> CHILD_SPEECH_SIGNALS = Arrays.asList(
            "i don't know", "i dont know", "my mommy", "my mom", "my mummy",
            "my daddy", "my dad", "i am years old", "i'm years old",
            "i am 5", "i am 6", "i am 7", "i am 8", "i am 9", "i am 10",
            "i am 11", "i am 12", "i am 13", "i am 14", "i am 15",
            "i'm 5", "i'm 6", "i'm 7", "i'm 8", "i'm 9", "i'm 10",
            "i'm 11", "i'm 12", "i'm 13", "i'm 14", "i'm 15",
            "i go to school", "my school", "my teacher", "homework",
            "can i ask my mom", "can i ask my dad", "i am alone",
            "i'm alone", "mom is not home", "dad is not home", "what does that mean",
            "i don't understand", "i dont understand"
    );

    public static final List<String> EXTRACTION_PATTERNS = Arrays.asList(
            "what's your address", "what is your address", "where do you live",
            "don't tell your parents", "dont tell your parents", "this is a secret",
            "you won", "claim your prize", "your full name", "mother's name",
            "mothers name", "father's name", "fathers name", "date of birth",
            "school name", "what grade", "home alone", "are you alone",
            "credit card", "bank account", "password", "otp", "pin",
            "send me a photo", "send photo", "location", "share your location",
            "parent's name", "parents name", "what is your name", "how old are you"
    );

    private static final Gson gson = new Gson();

    private ChildSafetyAnalyzer() {
    }

    public static ChildRiskResult analyzeForChildRisk(Context context, String transcript) {
        ChildRiskResult local = analyzeLocal(transcript);
        if (wordCount(transcript) < 30) return local;

        ChildRiskResult cloud = analyzeWithNvidia(context, transcript);
        if (cloud == null) return local;
        return merge(local, cloud);
    }

    public static ChildRiskResult analyzeForChildRisk(String transcript) {
        return analyzeLocal(transcript);
    }

    private static ChildRiskResult analyzeLocal(String transcript) {
        ChildRiskResult result = new ChildRiskResult();
        String lower = transcript == null ? "" : transcript.toLowerCase(Locale.US);

        for (String signal : CHILD_SPEECH_SIGNALS) {
            if (lower.contains(signal) && !result.matchedChildSignals.contains(signal)) {
                result.matchedChildSignals.add(signal);
            }
        }
        for (String pattern : EXTRACTION_PATTERNS) {
            if (lower.contains(pattern) && !result.matchedExtractionPatterns.contains(pattern)) {
                result.matchedExtractionPatterns.add(pattern);
            }
        }

        result.isChildDetected = !result.matchedChildSignals.isEmpty();
        result.isExtractionAttempt = !result.matchedExtractionPatterns.isEmpty();
        float childScore = Math.min(0.5f, result.matchedChildSignals.size() * 0.15f);
        float extractionScore = Math.min(0.5f, result.matchedExtractionPatterns.size() * 0.15f);
        result.riskScore = childScore + extractionScore;
        result.reason = result.shouldAlert()
                ? "Local child safety patterns detected child speech and information extraction."
                : "No combined child safety risk detected locally.";
        return result;
    }

    private static ChildRiskResult merge(ChildRiskResult local, ChildRiskResult cloud) {
        ChildRiskResult merged = new ChildRiskResult();
        merged.isChildDetected = local.isChildDetected || cloud.isChildDetected;
        merged.isExtractionAttempt = local.isExtractionAttempt || cloud.isExtractionAttempt;
        merged.riskScore = Math.max(local.riskScore, cloud.riskScore);
        merged.nvidiaConfidenceScore = cloud.nvidiaConfidenceScore;
        merged.reason = cloud.reason != null && !cloud.reason.isEmpty() ? cloud.reason : local.reason;
        merged.matchedChildSignals = union(local.matchedChildSignals, cloud.matchedChildSignals);
        merged.matchedExtractionPatterns = union(local.matchedExtractionPatterns, cloud.matchedExtractionPatterns);
        return merged;
    }

    private static List<String> union(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a != null) for (String item : a) if (!out.contains(item)) out.add(item);
        if (b != null) for (String item : b) if (!out.contains(item)) out.add(item);
        return out;
    }

    private static int wordCount(String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) return 0;
        return transcript.trim().split("\\s+").length;
    }

    private static ChildRiskResult analyzeWithNvidia(Context context, String transcript) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String apiKey = prefs.getString(PREF_NVIDIA_LLM_API_KEY, "");
        if (apiKey == null || apiKey.trim().isEmpty()) return null;

        try {
            URL url = new URL(NVIDIA_ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            conn.setDoOutput(true);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", systemPrompt());
            messages.add(system);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "Transcript so far: " + transcript);
            messages.add(user);

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
            String response = readResponse(conn, code);
            conn.disconnect();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "NVIDIA child safety HTTP " + code + ": " + response);
                return null;
            }
            String modelText = extractContent(response);
            CloudChildSafetyResponse cloud = gson.fromJson(stripJsonFence(modelText),
                    CloudChildSafetyResponse.class);
            return fromCloud(cloud);
        } catch (Exception e) {
            Log.w(TAG, "NVIDIA child safety analysis failed", e);
            return null;
        }
    }

    private static ChildRiskResult fromCloud(CloudChildSafetyResponse cloud) {
        if (cloud == null) return null;
        ChildRiskResult result = new ChildRiskResult();
        result.isChildDetected = cloud.isChild;
        result.isExtractionAttempt = cloud.isExtractionAttempt;
        result.nvidiaConfidenceScore = cloud.confidence;
        result.riskScore = cloud.confidence;
        result.reason = cloud.reason;
        if (cloud.childSignals != null) result.matchedChildSignals.addAll(cloud.childSignals);
        if (cloud.extractionSignals != null) result.matchedExtractionPatterns.addAll(cloud.extractionSignals);
        return result;
    }

    private static String systemPrompt() {
        return "You are a child safety detection AI embedded in a phone scam protection app. "
                + "Your job is to analyze live call transcripts and determine two things: "
                + "(1) Is the person answering this call likely a child under 16 years old? "
                + "Signals: mentions of parents/mommy/daddy, school, young age, naive trust, "
                + "simple vocabulary, confusion, asking to speak to parents, childlike phrasing. "
                + "(2) Is the caller attempting to extract sensitive personal information from "
                + "this child through manipulation, luring, or deception? Signals: asking for home "
                + "address, school name, parent names, financial info, secrecy requests, false "
                + "prizes/gifts, urgency, authority impersonation. Respond ONLY with valid JSON: "
                + "{\"isChild\":true,\"isExtractionAttempt\":true,\"confidence\":0.0,"
                + "\"childSignals\":[],\"extractionSignals\":[],\"reason\":\"one sentence\"}. "
                + "Threshold for isChild: if there is reasonable suspicion, set true. "
                + "Err on the side of caution.";
    }

    private static String readResponse(HttpURLConnection conn, int code) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        return response.toString();
    }

    private static String extractContent(String responseJson) {
        JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        return message.get("content").getAsString();
    }

    private static String stripJsonFence(String text) {
        if (text == null) return "";
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?", "").trim();
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
    }

    private static class CloudChildSafetyResponse {
        @SerializedName("isChild") boolean isChild;
        @SerializedName("isExtractionAttempt") boolean isExtractionAttempt;
        @SerializedName("confidence") float confidence;
        @SerializedName("childSignals") List<String> childSignals;
        @SerializedName("extractionSignals") List<String> extractionSignals;
        @SerializedName("reason") String reason;
    }
}
