package com.scamshield.defender.child;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.telephony.SmsManager;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class EmergencyContactManager {
    private static final String TAG = "EmergencyContactManager";
    private static final String PREFS_NAME = "scam_shield_prefs";
    private static final String CONTACTS_JSON = "emergency_contacts_json";
    private static final String CONTACTS_JSON_ENCRYPTED = "emergency_contacts_json_encrypted";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String CONTACT_CACHE_KEY_ALIAS = "call_trace_child_safety_contacts";
    private static final int GCM_TAG_BITS = 128;
    private static final Gson gson = new Gson();

    private EmergencyContactManager() {
    }

    public static String currentUid() {
        return FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid()
                : null;
    }

    public static List<EmergencyContact> getEmergencyContacts(Context context, String uid) {
        List<EmergencyContact> cached = getCachedContacts(context);
        if (uid != null) refreshContactsFromFirestore(context, uid);
        return cached;
    }

    public static void getEmergencyContactsFromFirestore(Context context, String uid,
                                                         ContactsCallback callback) {
        if (uid == null) {
            if (callback != null) callback.onContacts(getCachedContacts(context));
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("emergency_contacts")
                .get()
                .addOnSuccessListener(snapshot -> {
                    List<EmergencyContact> contacts = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        contacts.add(contactFromDocument(doc));
                    }
                    saveCache(context, contacts);
                    if (callback != null) callback.onContacts(contacts);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Could not load emergency contacts from Firestore", e);
                    if (callback != null) callback.onContacts(getCachedContacts(context));
                });
    }

    public static List<EmergencyContact> getCachedContacts(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = decryptContactsJson(prefs.getString(CONTACTS_JSON_ENCRYPTED, null));
        if (json == null || json.trim().isEmpty()) {
            json = prefs.getString(CONTACTS_JSON, "[]");
        }
        Type type = new TypeToken<List<EmergencyContact>>() {}.getType();
        List<EmergencyContact> contacts = gson.fromJson(json, type);
        return contacts != null ? contacts : new ArrayList<>();
    }

    public static void addEmergencyContact(Context context, String uid, String name, String phone) {
        String id = UUID.randomUUID().toString();
        EmergencyContact contact = new EmergencyContact(id, name, phone, System.currentTimeMillis());
        List<EmergencyContact> contacts = getCachedContacts(context);
        contacts.add(contact);
        saveCache(context, contacts);

        if (uid != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", name);
            data.put("phoneNumber", phone);
            data.put("addedAt", Timestamp.now());
            FirebaseFirestore.getInstance()
                    .collection("users").document(uid)
                    .collection("emergency_contacts").document(id)
                    .set(data);
        }
    }

    public static void removeEmergencyContact(Context context, String uid, String contactId) {
        List<EmergencyContact> contacts = getCachedContacts(context);
        contacts.removeIf(contact -> contactId != null && contactId.equals(contact.id));
        saveCache(context, contacts);
        if (uid != null && contactId != null) {
            FirebaseFirestore.getInstance()
                    .collection("users").document(uid)
                    .collection("emergency_contacts").document(contactId)
                    .delete();
        }
    }

    public static List<String> sendChildSafetyAlertSMS(Context context, String callerNumber,
                                                       String ownerName,
                                                       List<EmergencyContact> contacts) {
        List<String> notified = new ArrayList<>();
        if (contacts == null || contacts.isEmpty()) return notified;

        String time = new SimpleDateFormat("HH:mm, dd MMM yyyy", Locale.getDefault())
                .format(new Date());
        String message = "CALL TRACE ALERT: The call has been traced on "
                + safe(ownerName, "this device") + "'s phone. Child Safety Mode disconnected "
                + "an unknown caller. Call received from: "
                + safe(callerNumber, "Unknown") + ". Time: " + time
                + ". Please check immediately.";

        boolean canSendDirect = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
        for (EmergencyContact contact : contacts) {
            if (contact.phoneNumber == null || contact.phoneNumber.trim().isEmpty()) continue;
            try {
                if (canSendDirect) {
                    SmsManager sms = SmsManager.getDefault();
                    ArrayList<String> parts = sms.divideMessage(message);
                    sms.sendMultipartTextMessage(contact.phoneNumber, null, parts,
                            new ArrayList<PendingIntent>(), new ArrayList<PendingIntent>());
                    Log.i(TAG, "Child safety SMS sent to " + contact.maskedPhone());
                } else {
                    composeSms(context, contact.phoneNumber, message);
                    Log.w(TAG, "SMS permission missing; opened SMS composer for " + contact.maskedPhone());
                }
                notified.add(contact.maskedPhone());
            } catch (Exception e) {
                Log.w(TAG, "SMS alert failed for " + contact.maskedPhone(), e);
                composeSms(context, contact.phoneNumber, message);
            }
        }
        return notified;
    }

    private static void composeSms(Context context, String phone, String message) {
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)));
            intent.putExtra("sms_body", message);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not open SMS composer", e);
        }
    }

    private static void refreshContactsFromFirestore(Context context, String uid) {
        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("emergency_contacts")
                .get()
                .addOnSuccessListener(snapshot -> {
                    List<EmergencyContact> contacts = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        contacts.add(contactFromDocument(doc));
                    }
                    saveCache(context, contacts);
                })
                .addOnFailureListener(e -> Log.w(TAG, "Could not refresh emergency contacts", e));
    }

    private static EmergencyContact contactFromDocument(DocumentSnapshot doc) {
        EmergencyContact contact = new EmergencyContact();
        contact.id = doc.getId();
        contact.name = doc.getString("name");
        contact.phoneNumber = doc.getString("phoneNumber");
        Timestamp addedAt = doc.getTimestamp("addedAt");
        contact.addedAt = addedAt != null ? addedAt.toDate().getTime() : 0L;
        return contact;
    }

    private static void saveCache(Context context, List<EmergencyContact> contacts) {
        String json = gson.toJson(contacts != null ? contacts : new ArrayList<>());
        SharedPreferences.Editor editor = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit();
        String encrypted = encryptContactsJson(json);
        if (encrypted != null) {
            editor.putString(CONTACTS_JSON_ENCRYPTED, encrypted);
            editor.remove(CONTACTS_JSON);
        } else {
            editor.putString(CONTACTS_JSON, json);
        }
        editor.apply();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public interface ContactsCallback {
        void onContacts(List<EmergencyContact> contacts);
    }

    private static String encryptContactsJson(String json) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateContactCacheKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":"
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "Could not encrypt emergency contact cache", e);
            return null;
        }
    }

    private static String decryptContactsJson(String stored) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || stored == null
                || !stored.contains(":")) {
            return null;
        }
        try {
            String[] parts = stored.split(":", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateContactCacheKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Could not decrypt emergency contact cache", e);
            return null;
        }
    }

    private static SecretKey getOrCreateContactCacheKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(CONTACT_CACHE_KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(
                CONTACT_CACHE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
