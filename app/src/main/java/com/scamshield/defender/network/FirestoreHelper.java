package com.scamshield.defender.network;

import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.scamshield.defender.model.CallLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Save call log failed", e);
                    if (callback != null) callback.onError(e.getMessage());
                });
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
                        Map<String, Object> data = doc.getData();
                        if (data != null) {
                            logs.add(CallLog.fromMap(doc.getId(), data));
                        }
                    }
                    Log.i(TAG, "📋 Fetched " + logs.size() + " call logs");
                    callback.onSuccess(logs);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Fetch call history failed", e);
                    callback.onError(e.getMessage());
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

    public interface ResultCallback {
        void onSuccess();
        void onError(String error);
    }

    public interface DataCallback<T> {
        void onSuccess(T data);
        void onError(String error);
    }
}
