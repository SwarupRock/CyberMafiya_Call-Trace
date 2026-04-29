package com.scamshield.defender.network;

import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.scamshield.defender.model.CallLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * FirestoreHelper — Direct Firestore CRUD for Call History
 * ═══════════════════════════════════════════════════════════════════
 *
 * Simple, direct Firestore wrapper for storing and retrieving:
 *   - Call history with transcripts
 *   - Blocked numbers
 *   - Scam reports
 *
 * Firestore Structure:
 *   users/{uid}/call_history/{docId}
 *   users/{uid}/blocked_numbers/{docId}
 */
public class FirestoreHelper {

    private static final String TAG = "FirestoreHelper";
    private static final String COLLECTION_USERS = "users";
    private static final String COLLECTION_CALL_HISTORY = "call_history";
    private static final String COLLECTION_BLOCKED = "blocked_numbers";
    private static final String COLLECTION_CSV_BACKUPS = "csv_backups";
    private static final String COLLECTION_PRIVATE_SETTINGS = "private_settings";
    private static final String DOCUMENT_NVIDIA_KEYS = "nvidia_keys";

    private static volatile FirestoreHelper instance;
    private final FirebaseFirestore db;

    private FirestoreHelper() {
        db = FirebaseFirestore.getInstance();
    }

    public static FirestoreHelper getInstance() {
        if (instance == null) {
            synchronized (FirestoreHelper.class) {
                if (instance == null) {
                    instance = new FirestoreHelper();
                }
            }
        }
        return instance;
    }

    private String getUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    // ═══════════════════════════════════════════════════════════════
    // CALL HISTORY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Save a call log entry with transcript to Firestore.
     */
    public void saveCallLog(CallLog callLog, ResultCallback callback) {
        String uid = getUid();
        if (uid == null) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_CALL_HISTORY)
                .add(callLog.toMap())
                .addOnSuccessListener(docRef -> {
                    Log.i(TAG, "✅ Call log saved: " + docRef.getId());
                    saveCsvBackup(docRef.getId(), callLog.toMap());
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Save call log failed", e);
                    if (callback != null) callback.onError(e.getMessage());
                });
    }

    /**
     * Save a call log entry from a Map (used by audio file upload analysis).
     */
    public void saveCallLog(Map<String, Object> data) {
        String uid = getUid();
        if (uid == null) {
            Log.e(TAG, "Not authenticated, cannot save call log");
            return;
        }

        db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_CALL_HISTORY)
                .add(data)
                .addOnSuccessListener(docRef ->
                        Log.i(TAG, "✅ Call log (map) saved: " + docRef.getId()))
                .addOnFailureListener(e ->
                        Log.e(TAG, "❌ Save call log (map) failed", e));
    }

    /**
     * Fetch all call history ordered by timestamp descending.
     */
    public void getCallHistory(int limit, DataCallback<List<CallLog>> callback) {
        String uid = getUid();
        if (uid == null) {
            callback.onError("Not authenticated");
            return;
        }

        db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_CALL_HISTORY)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    List<CallLog> logs = new ArrayList<>();
                    for (DocumentSnapshot doc : querySnapshot) {
                        try {
                            Map<String, Object> data = doc.getData();
                            if (data != null) {
                                logs.add(CallLog.fromMap(doc.getId(), data));
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Skipping malformed call history doc: " + doc.getId(), e);
                        }
                    }
                    Log.i(TAG, "📋 Fetched " + logs.size() + " call logs");
                    if (callback != null) callback.onSuccess(logs);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Fetch call history failed", e);
                    if (callback != null) callback.onError(e.getMessage());
                });
    }

    /**
     * Fetch only scam calls.
     */
    public void getScamHistory(int limit, DataCallback<List<CallLog>> callback) {
        String uid = getUid();
        if (uid == null) {
            callback.onError("Not authenticated");
            return;
        }

        db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_CALL_HISTORY)
                .whereEqualTo("isScam", true)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    List<CallLog> logs = new ArrayList<>();
                    for (DocumentSnapshot doc : querySnapshot) {
                        Map<String, Object> data = doc.getData();
                        if (data != null) {
                            logs.add(CallLog.fromMap(doc.getId(), data));
                        }
                    }
                    callback.onSuccess(logs);
                })
                .addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCKED NUMBERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Save a blocked number to Firestore.
     */
    public void saveBlockedNumber(String phoneNumber, String reason, float scamScore,
                                   ResultCallback callback) {
        String uid = getUid();
        if (uid == null) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("phoneNumber", phoneNumber);
        data.put("reason", reason);
        data.put("scamScore", scamScore);
        data.put("timestamp", System.currentTimeMillis());

        db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_BLOCKED)
                .add(data)
                .addOnSuccessListener(docRef -> {
                    Log.i(TAG, "✅ Blocked number saved: " + phoneNumber);
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Save blocked number failed", e);
                    if (callback != null) callback.onError(e.getMessage());
                });
    }

    /**
     * Fetch all blocked numbers.
     */
    public void getBlockedNumbers(DataCallback<List<Map<String, Object>>> callback) {
        String uid = getUid();
        if (uid == null) {
            callback.onError("Not authenticated");
            return;
        }

        db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_BLOCKED)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    List<Map<String, Object>> numbers = new ArrayList<>();
                    for (DocumentSnapshot doc : querySnapshot) {
                        Map<String, Object> data = doc.getData();
                        if (data != null) {
                            data.put("id", doc.getId());
                            numbers.add(data);
                        }
                    }
                    callback.onSuccess(numbers);
                })
                .addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    // ═══════════════════════════════════════════════════════════════
    // CALLBACKS
    // ═══════════════════════════════════════════════════════════════

    public void saveNvidiaApiKeys(String llmKey, String asrKey, ResultCallback callback) {
        String uid = getUid();
        if (uid == null) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("nvidiaLlmApiKey", llmKey != null ? llmKey : "");
        data.put("nvidiaAsrApiKey", asrKey != null ? asrKey : "");
        data.put("updatedAt", System.currentTimeMillis());

        db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_PRIVATE_SETTINGS)
                .document(DOCUMENT_NVIDIA_KEYS)
                .set(data, SetOptions.merge())
                .addOnSuccessListener(unused -> {
                    Log.i(TAG, "NVIDIA API keys synced to Firestore");
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "NVIDIA API key sync failed", e);
                    if (callback != null) callback.onError(e.getMessage());
                });
    }

    public void getNvidiaApiKeys(DataCallback<Map<String, String>> callback) {
        String uid = getUid();
        if (uid == null) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_PRIVATE_SETTINGS)
                .document(DOCUMENT_NVIDIA_KEYS)
                .get()
                .addOnSuccessListener(document -> {
                    Map<String, String> keys = new HashMap<>();
                    if (document.exists()) {
                        keys.put("llm", document.getString("nvidiaLlmApiKey"));
                        keys.put("asr", document.getString("nvidiaAsrApiKey"));
                    }
                    if (callback != null) callback.onSuccess(keys);
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
    }

    public void saveCsvBackup(String callLogId, Map<String, Object> callData) {
        String uid = getUid();
        if (uid == null || callLogId == null || callData == null) return;

        String fileName = "call_trace_" + callLogId + ".csv";
        Map<String, Object> backup = new HashMap<>();
        backup.put("callLogId", callLogId);
        backup.put("fileName", fileName);
        backup.put("mimeType", "text/csv");
        backup.put("csv", buildCsv(callData));
        backup.put("createdAt", System.currentTimeMillis());

        db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_CSV_BACKUPS)
                .document(callLogId)
                .set(backup)
                .addOnSuccessListener(unused -> Log.i(TAG, "CSV backup saved: " + fileName))
                .addOnFailureListener(e -> Log.w(TAG, "CSV backup failed: " + e.getMessage()));
    }

    private String buildCsv(Map<String, Object> data) {
        float score = number(data.get("scamScore"));
        return "Phone Number,Date,Duration (s),Scam Score,Is Scam,Threat Type,Risk Level,Blocked,Transcript\n"
                + csv(data.get("phoneNumber")) + ","
                + csv(data.get("timestamp")) + ","
                + csv(data.get("callDurationSeconds")) + ","
                + csv(String.format(Locale.US, "%.2f", score)) + ","
                + csv(Boolean.TRUE.equals(data.get("isScam")) ? "YES" : "NO") + ","
                + csv(data.get("threatType")) + ","
                + csv(riskLabel(score)) + ","
                + csv(Boolean.TRUE.equals(data.get("blocked")) ? "YES" : "NO") + ","
                + csv(data.get("transcript")) + "\n";
    }

    private String csv(Object value) {
        String text = value != null ? value.toString() : "";
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private float number(Object value) {
        return value instanceof Number ? ((Number) value).floatValue() : 0f;
    }

    private String riskLabel(float score) {
        if (score >= 0.70f) return "HIGH RISK";
        if (score >= 0.40f) return "MEDIUM RISK";
        if (score >= 0.10f) return "LOW RISK";
        return "SAFE";
    }

    public interface ResultCallback {
        void onSuccess();
        void onError(String error);
    }

    public interface DataCallback<T> {
        void onSuccess(T data);
        void onError(String error);
    }
}
