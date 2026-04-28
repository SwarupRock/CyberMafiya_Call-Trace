package com.scamshield.defender.network;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.scamshield.defender.model.ScamNumber;
import com.scamshield.defender.model.UserProfile;

import java.util.HashMap;
import java.util.Map;

public class FirebaseClient {
    private static volatile FirebaseClient instance;

    private final FirebaseAuth auth;
    private final FirebaseFirestore db;

    private FirebaseClient() {
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
    }

    public static FirebaseClient getInstance() {
        if (instance == null) {
            synchronized (FirebaseClient.class) {
                if (instance == null) {
                    instance = new FirebaseClient();
                }
            }
        }
        return instance;
    }

    public FirebaseAuth getAuth() {
        return auth;
    }

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

    public void saveUserProfile(UserProfile profile, DataCallback<Void> callback) {
        String uid = getUserId();
        if (uid == null) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);
        data.put("phoneNumber", profile.getPhoneNumber());
        data.put("email", profile.getEmail());
        data.put("country", profile.getCountry());
        data.put("createdAt", profile.getCreatedAt() > 0 ? profile.getCreatedAt() : System.currentTimeMillis());

        db.collection("users").document(uid)
                .set(data)
                .addOnSuccessListener(unused -> {
                    if (callback != null) callback.onSuccess(null);
                })
                .addOnFailureListener(error -> {
                    if (callback != null) callback.onError(error.getMessage());
                });
    }

    public void getUserProfile(DataCallback<UserProfile> callback) {
        String uid = getUserId();
        if (uid == null) {
            if (callback != null) callback.onError("Not authenticated");
            return;
        }

        db.collection("users").document(uid)
                .get()
                .addOnSuccessListener(document -> {
                    if (callback == null) return;
                    if (!document.exists()) {
                        callback.onSuccess(null);
                        return;
                    }

                    UserProfile profile = new UserProfile();
                    profile.setUid(uid);
                    profile.setPhoneNumber(document.getString("phoneNumber"));
                    profile.setEmail(document.getString("email"));
                    profile.setCountry(document.getString("country"));
                    Long createdAt = document.getLong("createdAt");
                    profile.setCreatedAt(createdAt != null ? createdAt : 0L);
                    callback.onSuccess(profile);
                })
                .addOnFailureListener(error -> {
                    if (callback != null) callback.onError(error.getMessage());
                });
    }

    public void checkScamDatabase(String phoneNumber, DataCallback<ScamNumber> callback) {
        db.collection("scam_numbers").document(phoneNumber)
                .get()
                .addOnSuccessListener(document -> {
                    if (callback == null) return;
                    if (!document.exists()) {
                        callback.onSuccess(null);
                        return;
                    }

                    ScamNumber scamNumber = new ScamNumber();
                    scamNumber.phone_number = phoneNumber;
                    Long reports = document.getLong("totalReports");
                    Double score = document.getDouble("avgScamScore");
                    Boolean verified = document.getBoolean("isVerifiedScam");
                    scamNumber.total_reports = reports != null ? reports.intValue() : 0;
                    scamNumber.avg_scam_score = score != null ? score.floatValue() : 0f;
                    scamNumber.is_verified_scam = verified != null && verified;
                    callback.onSuccess(scamNumber);
                })
                .addOnFailureListener(error -> {
                    if (callback != null) callback.onError(error.getMessage());
                });
    }

    public static class CloudAnalysisResult {
        public boolean is_scam;
        public float scam_score;
        public String scam_type;
        public String explanation;
    }

    public interface DataCallback<T> {
        void onSuccess(T data);
        void onError(String error);
    }
}
