package com.scamshield.defender.network;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.scamshield.defender.model.BlockedNumber;
import com.scamshield.defender.model.CallLog;
import com.scamshield.defender.model.ScamNumber;
import com.scamshield.defender.model.UserProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * FirebaseClient — Full Firebase Client (Auth + Firestore)
 * ═══════════════════════════════════════════════════════════════════
 */
public class FirebaseClient {

    private static final String TAG = "FirebaseClient";

    private static FirebaseClient instance;
    private final FirebaseFirestore db;
    private final FirebaseAuth auth;

    private FirebaseClient() {
        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
    }

    public static synchronized FirebaseClient getInstance() {
        if (instance == null) {
            instance = new FirebaseClient();
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════════
    // AUTHENTICATION
    // ═══════════════════════════════════════════════════════════════

    public boolean isAuthenticated() {
        return auth.getCurrentUser() != null;
    }

    public String getUserId() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    public void signOut() {
        auth.signOut();
    }

    public FirebaseAuth getAuth() {
        return auth;
    }

    // ═══════════════════════════════════════════════════════════════
    // USER PROFILE
    // ═══════════════════════════════════════════════════════════════

    public void saveUserProfile(UserProfile profile, DataCallback<Void> callback) {
        if (!isAuthenticated()) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        db.collection("users").document(getUserId())
                .set(profile)
                .addOnSuccessListener(aVoid -> {
                    Log.i(TAG, "✅ User profile saved");
                    if (callback != null) callback.onSuccess(null);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Save profile error", e);
                    if (callback != null) callback.onError(e.getMessage());
                });
    }

    public void getUserProfile(DataCallback<UserProfile> callback) {
        if (!isAuthenticated()) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        db.collection("users").document(getUserId())
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        DocumentSnapshot document = task.getResult();
                        if (document != null && document.exists()) {
                            UserProfile profile = document.toObject(UserProfile.class);
                            if (callback != null) callback.onSuccess(profile);
                        } else {
                            if (callback != null) callback.onSuccess(null);
                        }
                    } else {
                        Log.e(TAG, "Get profile error", task.getException());
                        if (callback != null) callback.onError(task.getException() != null ? task.getException().getMessage() : "Unknown error");
                    }
                });
    }

    // ═══════════════════════════════════════════════════════════════
    // CALL LOGS
    // ═══════════════════════════════════════════════════════════════

    public void insertCallLog(CallLog callLog, DataCallback<Void> callback) {
        if (!isAuthenticated()) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }
        
        callLog.userId = getUserId();
        
        db.collection("call_logs")
                .add(callLog)
                .addOnSuccessListener(documentReference -> {
                    Log.i(TAG, "✅ Call log inserted");
                    if (callback != null) callback.onSuccess(null);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Insert call log error", e);
                    if (callback != null) callback.onError(e.getMessage());
                });
    }

    public void fetchCallHistory(int limit, DataCallback<List<CallLog>> callback) {
        if (!isAuthenticated()) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        db.collection("call_logs")
                .whereEqualTo("user_id", getUserId())
                .orderBy("analyzed_at", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        List<CallLog> logs = new ArrayList<>();
                        for (DocumentSnapshot document : task.getResult()) {
                            logs.add(document.toObject(CallLog.class));
                        }
                        if (callback != null) callback.onSuccess(logs);
                    } else {
                        Log.w(TAG, "Error getting call logs.", task.getException());
                        if (callback != null) callback.onError(task.getException() != null ? task.getException().getMessage() : "Unknown error");
                    }
                });
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCKED NUMBERS
    // ═══════════════════════════════════════════════════════════════

    public void insertBlockedNumber(BlockedNumber blockedNumber, DataCallback<Void> callback) {
        if (!isAuthenticated()) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        blockedNumber.blockedBy = getUserId();

        db.collection("blocked_numbers")
                .add(blockedNumber)
                .addOnSuccessListener(documentReference -> {
                    Log.i(TAG, "✅ Blocked number synced");
                    reportToScamDatabase(blockedNumber.phone_number, blockedNumber.scam_score);
                    if (callback != null) callback.onSuccess(null);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Insert blocked number error", e);
                    if (callback != null) callback.onError(e.getMessage());
                });
    }

    public void fetchBlockedNumbers(DataCallback<List<BlockedNumber>> callback) {
        if (!isAuthenticated()) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        db.collection("blocked_numbers")
                .whereEqualTo("blocked_by", getUserId())
                .orderBy("created_at", Query.Direction.DESCENDING)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        List<BlockedNumber> logs = new ArrayList<>();
                        for (DocumentSnapshot document : task.getResult()) {
                            logs.add(document.toObject(BlockedNumber.class));
                        }
                        if (callback != null) callback.onSuccess(logs);
                    } else {
                        Log.w(TAG, "Error getting blocked numbers.", task.getException());
                        if (callback != null) callback.onError(task.getException() != null ? task.getException().getMessage() : "Unknown error");
                    }
                });
    }

    // ═══════════════════════════════════════════════════════════════
    // SCAM DATABASE — Community crowd-sourced intelligence
    // ═══════════════════════════════════════════════════════════════

    public void checkScamDatabase(String phoneNumber, DataCallback<ScamNumber> callback) {
        db.collection("scam_numbers").document(phoneNumber)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        DocumentSnapshot document = task.getResult();
                        if (document != null && document.exists()) {
                            ScamNumber scamNumber = document.toObject(ScamNumber.class);
                            if(scamNumber != null) {
                                scamNumber.phone_number = document.getId();
                                if (callback != null) callback.onSuccess(scamNumber);
                                return;
                            }
                        }
                        if (callback != null) callback.onSuccess(null);
                    } else {
                        Log.w(TAG, "Error checking scam database.", task.getException());
                        if (callback != null) callback.onError(task.getException() != null ? task.getException().getMessage() : "Unknown error");
                    }
                });
    }

    private void reportToScamDatabase(String phoneNumber, float scamScore) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("total_reports", FieldValue.increment(1));
        updates.put("last_reported", FieldValue.serverTimestamp());
        
        // Simple moving average approx or just taking the max score.
        // For simplicity we'll just set it if it's the first time. 
        // In a real app, a Cloud Function should recalculate the avg.
        updates.put("avg_scam_score", scamScore);

        db.collection("scam_numbers").document(phoneNumber)
                .set(updates, SetOptions.merge())
                .addOnSuccessListener(aVoid -> Log.i(TAG, "Reported to community scam database"))
                .addOnFailureListener(e -> Log.e(TAG, "Report to scam DB error", e));
    }

    public void getTopScamNumbers(int limit, DataCallback<List<ScamNumber>> callback) {
        db.collection("scam_numbers")
                .orderBy("total_reports", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        List<ScamNumber> numbers = new ArrayList<>();
                        for (DocumentSnapshot document : task.getResult()) {
                            ScamNumber num = document.toObject(ScamNumber.class);
                            if(num != null) {
                                num.phone_number = document.getId();
                                numbers.add(num);
                            }
                        }
                        if (callback != null) callback.onSuccess(numbers);
                    } else {
                        Log.w(TAG, "Error getting top scam numbers.", task.getException());
                        if (callback != null) callback.onError(task.getException() != null ? task.getException().getMessage() : "Unknown error");
                    }
                });
    }

    // ═══════════════════════════════════════════════════════════════
    // AI ANALYSIS (Gemini via Firebase Functions)
    // ═══════════════════════════════════════════════════════════════

    public static class CloudAnalysisResult {
        public boolean is_scam;
        public float scam_score;
        public String scam_type;
        public String explanation;
        public List<String> keywords;
    }

    public void analyzeCallWithAI(String transcript, DataCallback<CloudAnalysisResult> callback) {
        if (!isAuthenticated()) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }
        
        // TODO: Implement Firebase Callable Cloud Function for Gemini AI
        // For now, return a dummy response or error to prevent crashes during migration
        Log.w(TAG, "analyzeCallWithAI: Firebase Cloud Function not yet implemented");
        if (callback != null) {
            callback.onError("AI Analysis via Firebase Functions is not yet implemented.");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CALLBACKS
    // ═══════════════════════════════════════════════════════════════

    public interface DataCallback<T> {
        void onSuccess(T data);
        void onError(String error);
    }
}
