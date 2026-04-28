package com.scamshield.defender.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import com.google.firebase.auth.FirebaseAuth;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.scamshield.defender.R;
import com.scamshield.defender.analyzer.ScamAnalyzerAI;
import com.scamshield.defender.network.FirestoreHelper;
import com.scamshield.defender.service.CallDefenderService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * MainActivity — Live Dashboard (stays open during calls)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Receives real-time broadcasts from CallDefenderService:
 *   - Call state changes (ringing → active → idle)
 *   - Live transcript updates
 *   - AI threat analysis results
 *
 * Updates the UI in real-time to show the user what's happening.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Dashboard";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String PREFS_NAME = "scam_shield_prefs";
    private static final String PREF_NVIDIA_LLM_API_KEY = "nvidia_llm_api_key";
    private static final String PREF_NVIDIA_ASR_API_KEY = "nvidia_asr_api_key";
    private static final String PREF_BACKGROUND_PROMPTED = "background_prompted";

    // ── Broadcast Actions (received from CallDefenderService) ─────
    public static final String ACTION_CALL_STATE = "com.scamshield.CALL_STATE";
    public static final String ACTION_TRANSCRIPT = "com.scamshield.TRANSCRIPT";
    public static final String ACTION_THREAT_UPDATE = "com.scamshield.THREAT_UPDATE";
    public static final String ACTION_SMS_QUARANTINE = "com.scamshield.SMS_QUARANTINE";

    // ── Views ─────────────────────────────────────────────────────
    private View statusDot, livePulse;
    private TextView tvShieldStatus, tvCallState, tvCallerNumber;
    private TextView tvTranscript;
    private ProgressBar progressThreat;
    private TextView tvThreatPercent, tvThreatLevel, tvThreatType, tvKeywords, tvAiSource;
    private TextView tvCallsAnalyzed, tvScamsBlocked, tvCommunityReports;
    private Button btnToggleShield, btnUploadAudio;
    private ScrollView scrollTranscript;
    private TextView tvUserPhone;
    private ImageView ivUserProfile;
    private LinearLayout layoutRecentCalls, layoutQuarantineMessages;

    private boolean isDefenderRunning = false;
    private boolean startAfterAiKeys = false;
    private boolean startAfterPermissions = false;
    private StringBuilder transcriptBuilder = new StringBuilder();
    private ScamAnalyzerAI audioAnalyzer;
    private ActivityResultLauncher<Intent> audioPickerLauncher;

    // ── Required permissions ──────────────────────────────────────
    private static final String[] REQUIRED_PERMISSIONS;
    static {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.READ_CALL_LOG);
        perms.add(Manifest.permission.READ_CONTACTS);
        perms.add(Manifest.permission.RECEIVE_SMS);
        perms.add(Manifest.permission.READ_SMS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        REQUIRED_PERMISSIONS = perms.toArray(new String[0]);
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioAnalyzer = new ScamAnalyzerAI(this);
        setupAudioPicker();
        bindViews();
        loadState();
        setupListeners();
        updateShieldUI();
        loadUserInfo();
        runPostLoginSetup();

        // Handle audio shared from other apps (share sheet)
        handleIncomingShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Handle share when activity is already open
        handleIncomingShareIntent(intent);
    }

    /**
     * Handles audio files shared via Android share sheet (ACTION_SEND)
     * or opened via file managers (ACTION_VIEW).
     * Automatically triggers the cloud audio analysis pipeline.
     */
    private void handleIncomingShareIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        String type = intent.getType();

        if (type == null || !type.startsWith("audio/")) return;

        Uri audioUri = null;

        if (Intent.ACTION_SEND.equals(action)) {
            audioUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            audioUri = intent.getData();
        }

        if (audioUri != null) {
            Log.i(TAG, "🎵 Audio shared from external app: " + audioUri);

            if (!hasAudioUploadApiKey()) {
                Toast.makeText(this, "NVIDIA ASR key required for shared audio.",
                        Toast.LENGTH_LONG).show();
                promptForNvidiaApiKeys(false);
                return;
            }

            // Clear the intent action so it doesn't re-trigger on config change
            intent.setAction(null);

            // Small delay to let the UI fully render before starting analysis
            final Uri finalUri = audioUri;
            scrollTranscript.postDelayed(() -> processAudioFile(finalUri), 500);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerBroadcastReceivers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dashboardReceiver);
    }

    // ═══════════════════════════════════════════════════════════════
    // VIEW BINDING
    // ═══════════════════════════════════════════════════════════════

    private void bindViews() {
        statusDot = findViewById(R.id.status_dot);
        livePulse = findViewById(R.id.live_pulse);
        tvShieldStatus = findViewById(R.id.tv_shield_status);
        tvCallState = findViewById(R.id.tv_call_state);
        tvCallerNumber = findViewById(R.id.tv_caller_number);
        tvTranscript = findViewById(R.id.tv_transcript);
        progressThreat = findViewById(R.id.progress_threat);
        tvThreatPercent = findViewById(R.id.tv_threat_percent);
        tvThreatLevel = findViewById(R.id.tv_threat_level);
        tvThreatType = findViewById(R.id.tv_threat_type);
        tvKeywords = findViewById(R.id.tv_keywords);
        tvAiSource = findViewById(R.id.tv_ai_source);
        tvCallsAnalyzed = findViewById(R.id.tv_calls_analyzed);
        tvScamsBlocked = findViewById(R.id.tv_scams_blocked);
        tvCommunityReports = findViewById(R.id.tv_community_reports);
        btnToggleShield = findViewById(R.id.btn_toggle_shield);
        btnUploadAudio = findViewById(R.id.btn_upload_audio);
        scrollTranscript = findViewById(R.id.scroll_transcript);
        tvUserPhone = findViewById(R.id.tv_user_phone);
        ivUserProfile = findViewById(R.id.iv_user_profile);
        layoutRecentCalls = findViewById(R.id.layout_recent_calls);
        layoutQuarantineMessages = findViewById(R.id.layout_quarantine_messages);
    }

    // ═══════════════════════════════════════════════════════════════
    // SETUP
    // ═══════════════════════════════════════════════════════════════

    private void setupListeners() {
        btnToggleShield.setOnClickListener(v -> {
            if (isDefenderRunning) {
                stopDefender();
            } else {
                if (!hasNvidiaAnalysisKey()) {
                    Toast.makeText(this, "Add an AI API key before starting Call Trace", Toast.LENGTH_LONG).show();
                    startAfterAiKeys = true;
                    promptForNvidiaApiKeys(false);
                    return;
                }
                if (hasAllPermissions()) {
                    startDefender();
                } else {
                    startAfterPermissions = true;
                    requestPermissions();
                }
            }
        });

        ivUserProfile.setOnClickListener(v -> showProfileActions());

        btnUploadAudio.setOnClickListener(v -> launchAudioPicker());
    }

    private void showProfileActions() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PROFILE CONTROL");
        title.setTextColor(getColor(R.color.cyber_cyan));
        title.setTextSize(13);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        panel.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(hasNvidiaAnalysisKey() ? "NVIDIA AI connected" : "NVIDIA key required");
        subtitle.setTextColor(getColor(R.color.text_muted));
        subtitle.setTextSize(12);
        subtitle.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, dp(6), 0, dp(14));
        panel.addView(subtitle, subtitleParams);

        Button nvidiaButton = createMenuButton("AI API KEY", R.drawable.menu_button_cyan, R.color.cyber_cyan);
        Button historyButton = createMenuButton("CALL HISTORY", R.drawable.menu_button_cyan, R.color.cyber_cyan);
        Button backgroundButton = createMenuButton("BACKGROUND PROTECTION", R.drawable.menu_button_cyan, R.color.cyber_cyan);
        Button logoutButton = createMenuButton("LOGOUT", R.drawable.menu_button_red, R.color.neon_crimson);

        panel.addView(nvidiaButton);
        panel.addView(historyButton);
        panel.addView(backgroundButton);
        panel.addView(logoutButton);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel)
                .create();

        nvidiaButton.setOnClickListener(v -> {
            dialog.dismiss();
            promptForNvidiaApiKeys(false);
        });
        historyButton.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(MainActivity.this, CallHistoryActivity.class));
        });
        backgroundButton.setOnClickListener(v -> {
            dialog.dismiss();
            promptBackgroundRunPermission(false);
        });
        logoutButton.setOnClickListener(v -> {
            dialog.dismiss();
            performLogout();
        });

        dialog.show();
    }

    private Button createMenuButton(String text, int backgroundRes, int colorRes) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(getColor(colorRes));
        button.setTextSize(12);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setBackgroundResource(backgroundRes);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48));
        params.setMargins(0, dp(8), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void performLogout() {
        stopDefender();
        FirebaseAuth.getInstance().signOut();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("is_logged_in", false)
                .remove("user_phone")
                .remove("user_id")
                .apply();
        startActivity(new Intent(MainActivity.this, LoginActivity.class));
        finish();
    }

    private void loadUserInfo() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String phone = prefs.getString("user_phone", "");
        String identifier = prefs.getString("user_identifier", "");
        if (!phone.isEmpty() && !phone.equals("anonymous")) {
            String masked = phone.length() > 6
                    ? phone.substring(0, 4) + "****" + phone.substring(phone.length() - 2)
                    : phone;
            tvUserPhone.setText("Logged in as " + masked);
        } else if (identifier != null && identifier.contains("@")) {
            tvUserPhone.setText("Logged in as " + identifier);
        } else {
            tvUserPhone.setText("Demo mode");
        }

        // Load stats
        tvCallsAnalyzed.setText(String.valueOf(
                prefs.getInt("stat_calls_analyzed", 0)));
        tvScamsBlocked.setText(String.valueOf(
                prefs.getInt("stat_scams_blocked", 0)));
        tvCommunityReports.setText(String.valueOf(
                prefs.getInt("stat_community_reports", 0)));
        tvAiSource.setText(hasNvidiaAnalysisKey() ? "NVIDIA AI READY" : "NVIDIA KEY REQUIRED");
        loadRecentCallLogRows();
        loadQuarantineMessages();
    }

    private void runPostLoginSetup() {
        if (!hasAllPermissions()) {
            requestPermissions();
            return;
        }
        if (!hasNvidiaAnalysisKey()) {
            promptForNvidiaApiKeys(true);
            return;
        }
        promptBackgroundRunPermission(true);
    }

    private void promptBackgroundRunPermission(boolean firstRun) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (firstRun && prefs.getBoolean(PREF_BACKGROUND_PROMPTED, false)) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                prefs.edit().putBoolean(PREF_BACKGROUND_PROMPTED, true).apply();
                return;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Keep Call Trace Running")
                .setMessage("Allow Call Trace to run in the background so it can detect calls and scam messages even when the app is closed.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    prefs.edit().putBoolean(PREF_BACKGROUND_PROMPTED, true).apply();
                    openBackgroundSettings();
                })
                .setNegativeButton(firstRun ? "Later" : "Cancel", (dialog, which) ->
                        prefs.edit().putBoolean(PREF_BACKGROUND_PROMPTED, true).apply())
                .show();
    }

    private void openBackgroundSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "Battery optimization settings unavailable", e);
        }

        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean hasNvidiaAnalysisKey() {
        String key = getNvidiaLlmApiKey();
        return key != null && !key.trim().isEmpty();
    }

    private boolean hasNvidiaAsrKey() {
        String key = getNvidiaAsrApiKey();
        return key != null && !key.trim().isEmpty();
    }

    private String getNvidiaLlmApiKey() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_NVIDIA_LLM_API_KEY, "");
    }

    private String getNvidiaAsrApiKey() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_NVIDIA_ASR_API_KEY, "");
    }

    private boolean hasAudioUploadApiKey() {
        return hasNvidiaAsrKey() && hasNvidiaAnalysisKey();
    }

    private String getAiProviderLabel() {
        return "NVIDIA";
    }

    private boolean isNvidiaKey(String key) {
        return key != null && key.trim().toLowerCase().startsWith("nvapi-");
    }

    private String maskKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return "No key saved";
        }
        String trimmed = key.trim();
        int visible = Math.min(4, trimmed.length());
        return "Saved key: " + "**** **** " + trimmed.substring(trimmed.length() - visible);
    }

    private String getNvidiaKeySummary() {
        return "LLM: " + maskKey(getNvidiaLlmApiKey()) + "\nASR: " + maskKey(getNvidiaAsrApiKey());
    }

    @Deprecated
    private boolean hasNvidiaApiKeys() {
        return hasNvidiaAnalysisKey();
    }

    @Deprecated
    private String maskNvidiaKey(String key) {
        return maskKey(key);
    }

    private void promptForNvidiaApiKeys(boolean firstRun) {
        boolean keyExists = hasNvidiaAnalysisKey() && hasNvidiaAsrKey();
        boolean[] editingKey = { !keyExists };

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.login_panel_bg);
        container.setPadding(dp(20), dp(20), dp(20), dp(18));
        container.setAlpha(0f);
        container.setScaleX(0.94f);
        container.setScaleY(0.94f);

        TextView eyebrow = new TextView(this);
        eyebrow.setText(keyExists ? "AI ENGINE CONNECTED" : "AI ENGINE SETUP");
        eyebrow.setTextColor(getColor(R.color.cyber_cyan));
        eyebrow.setTextSize(11);
        eyebrow.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.18f);
        container.addView(eyebrow);

        TextView title = new TextView(this);
        title.setText(keyExists ? "NVIDIA Ready" : "NVIDIA API Keys");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(6), 0, 0);
        container.addView(title, titleParams);

        TextView message = new TextView(this);
        message.setText(keyExists
                ? "NVIDIA analysis and ASR keys are saved on this device. Keep them, or replace them below."
                : "Paste both NVIDIA keys: LLM for scam analysis, ASR/Riva for uploaded audio transcription.");
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(13);
        message.setLineSpacing(dp(2), 1.0f);
        message.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        messageParams.setMargins(0, dp(10), 0, dp(16));
        container.addView(message, messageParams);

        TextView inputLabel = new TextView(this);
        inputLabel.setText("API KEY");
        inputLabel.setTextColor(getColor(R.color.text_dim));
        inputLabel.setTextSize(11);
        inputLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        inputLabel.setLetterSpacing(0.12f);
        container.addView(inputLabel);

        TextView savedState = new TextView(this);
        savedState.setText(getNvidiaKeySummary());
        savedState.setTextColor(getColor(R.color.cyber_green));
        savedState.setTextSize(13);
        savedState.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        savedState.setBackgroundResource(R.drawable.login_status_bg);
        savedState.setPadding(dp(14), dp(12), dp(14), dp(12));
        savedState.setVisibility(keyExists ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams savedParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        savedParams.setMargins(0, dp(7), 0, 0);
        container.addView(savedState, savedParams);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(keyExists ? "Paste replacement NVIDIA LLM key" : "Paste NVIDIA LLM key");
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_muted));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setBackgroundResource(R.drawable.login_input_bg);
        input.setTypeface(Typeface.MONOSPACE);
        input.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52));
        inputParams.setMargins(0, dp(7), 0, 0);
        input.setVisibility(keyExists ? View.GONE : View.VISIBLE);
        container.addView(input, inputParams);

        EditText asrInput = new EditText(this);
        asrInput.setSingleLine(true);
        asrInput.setHint(keyExists ? "Paste replacement NVIDIA ASR key" : "Paste NVIDIA ASR/Riva key");
        asrInput.setTextColor(getColor(R.color.text_primary));
        asrInput.setHintTextColor(getColor(R.color.text_muted));
        asrInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        asrInput.setBackgroundResource(R.drawable.login_input_bg);
        asrInput.setTypeface(Typeface.MONOSPACE);
        asrInput.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams asrInputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52));
        asrInputParams.setMargins(0, dp(9), 0, 0);
        asrInput.setVisibility(keyExists ? View.GONE : View.VISIBLE);
        container.addView(asrInput, asrInputParams);

        TextView helper = new TextView(this);
        helper.setText(keyExists
                ? "Call Trace is ready for NVIDIA live analysis and audio transcription setup."
                : "Call Trace starts after the NVIDIA LLM key is saved. Audio upload also needs the ASR key.");
        helper.setTextColor(getColor(R.color.text_muted));
        helper.setTextSize(11);
        helper.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        helperParams.setMargins(0, dp(9), 0, dp(18));
        container.addView(helper, helperParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);

        Button cancelButton = createDialogButton(firstRun ? "LATER" : "CANCEL",
                R.drawable.menu_button_cyan, R.color.cyber_cyan);
        if (keyExists && !firstRun) {
            cancelButton.setText("CLOSE");
        }
        Button saveButton = createDialogButton(keyExists ? "CHANGE KEY" : "SAVE KEY",
                R.drawable.login_success_button, R.color.obsidian_black);

        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        cancelParams.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        saveParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(cancelButton, cancelParams);
        actions.addView(saveButton, saveParams);
        container.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(container)
                .create();

        cancelButton.setOnClickListener(v -> {
            animateClickThen(v, () -> {
                dialog.dismiss();
                if (firstRun) {
                    promptBackgroundRunPermission(true);
                }
            });
        });

        saveButton.setOnClickListener(v -> animateClickThen(v, () -> {
            if (!editingKey[0]) {
                editingKey[0] = true;
                eyebrow.setText("AI ENGINE UPDATE");
                title.setText("Change AI Key");
                message.setText("Paste a replacement key below. Your current key remains active until you save the new one.");
                inputLabel.setText("NEW API KEY");
                savedState.setVisibility(View.GONE);
                input.setVisibility(View.VISIBLE);
                asrInput.setVisibility(View.VISIBLE);
                input.requestFocus();
                helper.setText("Saving replaces the NVIDIA keys stored on this device.");
                cancelButton.setText("CANCEL");
                saveButton.setText("SAVE KEY");
                return;
            }
            String key = input.getText().toString().trim();
            String asrKey = asrInput.getText().toString().trim();
            if (key.isEmpty()) {
                input.setError("NVIDIA LLM key required");
                return;
            }
            if (!isNvidiaKey(key)) {
                input.setError("NVIDIA keys usually start with nvapi-");
                return;
            }
            if (asrKey.isEmpty()) {
                asrInput.setError("NVIDIA ASR key required for audio upload");
                return;
            }
            if (!isNvidiaKey(asrKey)) {
                asrInput.setError("NVIDIA keys usually start with nvapi-");
                return;
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_NVIDIA_LLM_API_KEY, key)
                    .putString(PREF_NVIDIA_ASR_API_KEY, asrKey)
                    .apply();
            tvAiSource.setText("NVIDIA AI READY");
            Toast.makeText(this, "NVIDIA APIs connected", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if (startAfterAiKeys && hasAllPermissions() && !isDefenderRunning) {
                startAfterAiKeys = false;
                startDefender();
            } else if (startAfterAiKeys && !hasAllPermissions()) {
                startAfterPermissions = true;
                requestPermissions();
            } else if (firstRun) {
                promptBackgroundRunPermission(true);
            }
        }));

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setDimAmount(0.76f);
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.90f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        container.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start();
        if (editingKey[0]) {
            input.requestFocus();
        }
    }

    private Button createDialogButton(String text, int backgroundRes, int colorRes) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(getColor(colorRes));
        button.setTextSize(12);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setBackgroundResource(backgroundRes);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .alpha(0.82f)
                        .setDuration(85)
                        .start();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(140)
                        .start();
            }
            return false;
        });
        return button;
    }

    private void animateClickThen(View view, Runnable afterAnimation) {
        view.animate()
                .scaleX(1.04f)
                .scaleY(1.04f)
                .alpha(1f)
                .setDuration(90)
                .withEndAction(() -> view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(110)
                        .withEndAction(afterAnimation)
                        .start())
                .start();
    }

    private void loadRecentCallLogRows() {
        if (layoutRecentCalls == null) return;
        layoutRecentCalls.removeAllViews();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            addLogRow(layoutRecentCalls, "Call log permission required", "Grant permission to show recent calls");
            return;
        }

        String[] projection = {
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
        };

        try (Cursor cursor = getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC")) {
            if (cursor == null || !cursor.moveToFirst()) {
                addLogRow(layoutRecentCalls, "No recent calls", "Intercept log will update after calls");
                return;
            }

            int numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
            int typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE);
            int durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);

            int count = 0;
            do {
                String number = cursor.getString(numberIdx);
                int type = cursor.getInt(typeIdx);
                int duration = cursor.getInt(durationIdx);
                addLogRow(layoutRecentCalls, maskForUi(number), callTypeLabel(type) + " / " + duration + "s");
                count++;
            } while (count < 5 && cursor.moveToNext());
        } catch (SecurityException e) {
            addLogRow(layoutRecentCalls, "Call log unavailable", "Permission was denied");
        }
    }

    private void loadQuarantineMessages() {
        if (layoutQuarantineMessages == null) return;
        layoutQuarantineMessages.removeAllViews();

        String data = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString("quarantine_messages", "");
        if (data == null || data.trim().isEmpty()) {
            addLogRow(layoutQuarantineMessages, "No quarantined messages", "Suspicious SMS and OTP messages appear here");
            return;
        }

        String[] rows = data.split("\n");
        int count = 0;
        for (String row : rows) {
            if (row.trim().isEmpty()) continue;
            String[] parts = row.split("\\|", 5);
            if (parts.length < 5) continue;
            String sender = parts[1];
            String score = parts[2];
            boolean hasOtp = Boolean.parseBoolean(parts[3]);
            String message = parts[4];
            addLogRow(
                    layoutQuarantineMessages,
                    sender + " / RISK " + score,
                    (hasOtp ? "OTP DETECTED / " : "") + truncate(message, 92));
            count++;
            if (count >= 5) break;
        }
    }

    private void addLogRow(LinearLayout parent, String title, String subtitle) {
        TextView row = new TextView(this);
        row.setText(title + "\n" + subtitle);
        row.setTextColor(getColor(R.color.text_primary));
        row.setTextSize(12);
        row.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundResource(R.drawable.log_row_bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        parent.addView(row, params);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private String callTypeLabel(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "INCOMING";
            case CallLog.Calls.OUTGOING_TYPE:
                return "OUTGOING";
            case CallLog.Calls.MISSED_TYPE:
                return "MISSED";
            case CallLog.Calls.REJECTED_TYPE:
                return "REJECTED";
            default:
                return "CALL";
        }
    }

    private String maskForUi(String number) {
        if (number == null || number.length() <= 4) return "UNKNOWN";
        return number.substring(0, Math.min(4, number.length()))
                + "****"
                + number.substring(number.length() - 2);
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    // ═══════════════════════════════════════════════════════════════
    // BROADCAST RECEIVERS (real-time updates from service)
    // ═══════════════════════════════════════════════════════════════

    private void registerBroadcastReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CALL_STATE);
        filter.addAction(ACTION_TRANSCRIPT);
        filter.addAction(ACTION_THREAT_UPDATE);
        filter.addAction(ACTION_SMS_QUARANTINE);
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(dashboardReceiver, filter);
    }

    private final BroadcastReceiver dashboardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == null) return;

            switch (intent.getAction()) {
                case ACTION_CALL_STATE:
                    handleCallStateUpdate(intent);
                    break;
                case ACTION_TRANSCRIPT:
                    handleTranscriptUpdate(intent);
                    break;
                case ACTION_THREAT_UPDATE:
                    handleThreatUpdate(intent);
                    break;
                case ACTION_SMS_QUARANTINE:
                    loadQuarantineMessages();
                    break;
            }
        }
    };

    private void handleCallStateUpdate(Intent intent) {
        String state = intent.getStringExtra("state");
        String number = intent.getStringExtra("number");

        if (state == null) return;

        switch (state) {
            case "RINGING":
                tvCallState.setText("INCOMING CALL");
                tvCallState.setTextColor(getColor(R.color.neon_amber));
                tvCallerNumber.setText("From: " + (number != null ? number : "Unknown"));
                transcriptBuilder.setLength(0);
                tvTranscript.setText("Waiting for call to be answered...");
                resetThreatUI();
                break;

            case "ACTIVE":
                tvCallState.setText("⚡ CALL ACTIVE — ANALYZING");
                tvCallState.setTextColor(getColor(R.color.neon_crimson));
                tvTranscript.setText("🔊 Enable SPEAKER MODE for live transcription\nListening...");
                livePulse.setVisibility(View.VISIBLE);
                break;

            case "IDLE":
                tvCallState.setText("MONITORING");
                tvCallState.setTextColor(getColor(R.color.cyber_cyan));
                tvCallerNumber.setText("Waiting for incoming call...");
                livePulse.setVisibility(View.GONE);

                // Update stats
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                int analyzed = prefs.getInt("stat_calls_analyzed", 0) + 1;
                prefs.edit().putInt("stat_calls_analyzed", analyzed).apply();
                tvCallsAnalyzed.setText(String.valueOf(analyzed));
                break;

            case "SCAM_BLOCKED":
                tvCallState.setText("🚫 SCAM BLOCKED");
                tvCallState.setTextColor(getColor(R.color.neon_crimson));

                SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                int blocked = p.getInt("stat_scams_blocked", 0) + 1;
                p.edit().putInt("stat_scams_blocked", blocked).apply();
                tvScamsBlocked.setText(String.valueOf(blocked));
                break;
        }
    }

    private void handleTranscriptUpdate(Intent intent) {
        String text = intent.getStringExtra("text");
        boolean isPartial = intent.getBooleanExtra("partial", false);

        if (text == null) return;

        if (isPartial) {
            // Show partial (in-progress) text in dimmer color
            String current = transcriptBuilder.toString();
            tvTranscript.setText(current + (current.isEmpty() ? "" : "\n") + "▸ " + text);
        } else {
            // Final text
            if (transcriptBuilder.length() > 0) transcriptBuilder.append("\n");
            transcriptBuilder.append("» ").append(text);
            tvTranscript.setText(transcriptBuilder.toString());
        }
    }

    private void handleThreatUpdate(Intent intent) {
        float confidence = intent.getFloatExtra("confidence", 0f);
        String level = intent.getStringExtra("level");
        String type = intent.getStringExtra("type");
        String keywords = intent.getStringExtra("keywords");
        boolean fromCloud = intent.getBooleanExtra("from_cloud", false);

        int percent = (int) (confidence * 100);
        progressThreat.setProgress(percent);
        tvThreatPercent.setText(percent + "%");

        // Color the progress bar based on threat level
        int color;
        if (confidence >= 0.70) {
            color = getColor(R.color.neon_crimson);
        } else if (confidence >= 0.40) {
            color = getColor(R.color.neon_amber);
        } else {
            color = getColor(R.color.cyber_green);
        }
        progressThreat.setProgressTintList(ColorStateList.valueOf(color));
        tvThreatPercent.setTextColor(color);

        if (level != null) {
            tvThreatLevel.setText(level);
            tvThreatLevel.setTextColor(color);
        }

        if (type != null && !type.equals("none")) {
            tvThreatType.setText("Category: " + type.replace("_", " ").toUpperCase());
            tvThreatType.setTextColor(getColor(R.color.neon_amber));
        }

        if (keywords != null && !keywords.isEmpty()) {
            tvKeywords.setText("⚠ Keywords: " + keywords);
            tvKeywords.setVisibility(View.VISIBLE);
        }

        tvAiSource.setText(fromCloud ? getAiProviderLabel() + " AI LIVE" : "LOCAL AI WATCH");
    }

    private void resetThreatUI() {
        progressThreat.setProgress(0);
        progressThreat.setProgressTintList(ColorStateList.valueOf(getColor(R.color.cyber_green)));
        tvThreatPercent.setText("0%");
        tvThreatPercent.setTextColor(getColor(R.color.cyber_green));
        tvThreatLevel.setText("SAFE");
        tvThreatLevel.setTextColor(getColor(R.color.cyber_green));
        tvThreatType.setText("No threats detected");
        tvThreatType.setTextColor(getColor(R.color.text_muted));
        tvKeywords.setVisibility(View.GONE);
        tvAiSource.setText(hasNvidiaAnalysisKey() ? "NVIDIA AI READY" : "NVIDIA KEY REQUIRED");
    }

    // ═══════════════════════════════════════════════════════════════
    // SERVICE CONTROL
    // ═══════════════════════════════════════════════════════════════

    private void startDefender() {
        if (!hasNvidiaAnalysisKey()) {
            Toast.makeText(this, "Add an AI API key before starting Call Trace", Toast.LENGTH_LONG).show();
            startAfterAiKeys = true;
            promptForNvidiaApiKeys(false);
            return;
        }
        Intent serviceIntent = new Intent(this, CallDefenderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        isDefenderRunning = true;
        saveState(true);
        updateShieldUI();
        Toast.makeText(this, "Call Trace activated", Toast.LENGTH_SHORT).show();
    }

    private void stopDefender() {
        stopService(new Intent(this, CallDefenderService.class));
        isDefenderRunning = false;
        saveState(false);
        updateShieldUI();
        Toast.makeText(this, "Call Trace stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateShieldUI() {
        if (isDefenderRunning) {
            btnToggleShield.setText("STOP TRACE");
            btnToggleShield.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.danger_red));
            tvShieldStatus.setText("ACTIVE");
            tvShieldStatus.setTextColor(getColor(R.color.cyber_green));
            statusDot.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.cyber_green)));
            tvCallState.setText("MONITORING");
            tvCallState.setTextColor(getColor(R.color.cyber_cyan));
            tvCallerNumber.setText("Waiting for incoming call...");
            tvAiSource.setText(hasNvidiaAnalysisKey() ? "NVIDIA AI READY" : "NVIDIA KEY REQUIRED");
        } else {
            btnToggleShield.setText("START TRACE");
            btnToggleShield.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.cyber_cyan));
            tvShieldStatus.setText("INACTIVE");
            tvShieldStatus.setTextColor(getColor(R.color.text_muted));
            statusDot.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.text_muted)));
            tvCallState.setText("TRACE OFF");
            tvCallState.setTextColor(getColor(R.color.text_muted));
            tvCallerNumber.setText("Start Call Trace to begin monitoring");
            tvAiSource.setText(hasNvidiaAnalysisKey() ? "NVIDIA AI READY" : "NVIDIA KEY REQUIRED");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ═══════════════════════════════════════════════════════════════

    private boolean hasAllPermissions() {
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) {
                loadRecentCallLogRows();
                loadQuarantineMessages();
                if (startAfterPermissions) {
                    startAfterPermissions = false;
                    if (hasNvidiaAnalysisKey()) {
                        startDefender();
                    } else {
                        startAfterAiKeys = true;
                        promptForNvidiaApiKeys(false);
                    }
                } else if (!hasNvidiaAnalysisKey()) {
                    promptForNvidiaApiKeys(true);
                } else {
                    promptBackgroundRunPermission(true);
                }
            } else {
                Toast.makeText(this, "⚠️ Permissions required for scam protection",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════

    private void loadState() {
        isDefenderRunning = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("defender_enabled", false);
    }

    private void saveState(boolean enabled) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("defender_enabled", enabled).apply();
    }

    // ═══════════════════════════════════════════════════════════════
    // AUDIO FILE UPLOAD & ANALYSIS
    // ═══════════════════════════════════════════════════════════════

    private void setupAudioPicker() {
        audioPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri audioUri = result.getData().getData();
                    if (audioUri != null) {
                        processAudioFile(audioUri);
                    }
                }
            }
        );
    }

    private void launchAudioPicker() {
        if (!hasAudioUploadApiKey()) {
            Toast.makeText(this, "NVIDIA ASR key required for audio upload.", Toast.LENGTH_LONG).show();
            promptForNvidiaApiKeys(false);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        audioPickerLauncher.launch(Intent.createChooser(intent, "Select Audio File"));
    }

    private void processAudioFile(Uri audioUri) {
        tvTranscript.setText("⏳ ANALYZING AUDIO FILE...\n\nPreparing NVIDIA ASR transcription and scam analysis...");
        tvTranscript.setTextColor(getColor(R.color.neon_amber));
        btnUploadAudio.setEnabled(false);
        btnUploadAudio.setText("⏳ ANALYZING...");

        // Reset threat UI
        progressThreat.setProgress(0);
        tvThreatPercent.setText("0%");
        tvThreatLevel.setText("ANALYZING");
        tvThreatType.setText("Processing audio file...");

        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(audioUri);
                if (is == null) {
                    runOnUiThread(() -> showAudioError("Cannot read audio file"));
                    return;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                is.close();
                byte[] audioData = baos.toByteArray();

                // Determine MIME type
                String mimeType = getContentResolver().getType(audioUri);
                if (mimeType == null) mimeType = "audio/mpeg";

                final String finalMime = mimeType;
                audioAnalyzer.analyzeAudioFile(audioData, finalMime, new ScamAnalyzerAI.AudioAnalysisCallback() {
                    @Override
                    public void onTranscriptReady(String transcript) {
                        runOnUiThread(() -> {
                            tvTranscript.setTextColor(getColor(R.color.cyber_cyan));
                            tvTranscript.setText("TRANSCRIPT\n\n" + transcript + "\n\nAnalyzing scam risk...");
                            scrollTranscript.post(() -> scrollTranscript.fullScroll(View.FOCUS_UP));
                        });
                    }

                    @Override
                    public void onAnalysisComplete(ScamAnalyzerAI.AnalysisResult result, String transcript) {
                        runOnUiThread(() -> {
                            btnUploadAudio.setEnabled(true);
                            btnUploadAudio.setText("⬆ UPLOAD AUDIO FILE FOR ANALYSIS");

                            // Update threat UI
                            int pct = (int)(result.confidence * 100);
                            progressThreat.setProgress(pct);
                            tvThreatPercent.setText(pct + "%");
                            tvThreatLevel.setText(result.getThreatLevel());
                            tvThreatType.setText(result.threatType.replace("_", " ").toUpperCase());

                            // Color the threat level
                            int levelColor;
                            if (result.confidence >= 0.70f) {
                                levelColor = getColor(R.color.neon_crimson);
                            } else if (result.confidence >= 0.40f) {
                                levelColor = getColor(R.color.neon_amber);
                            } else {
                                levelColor = getColor(R.color.cyber_green);
                            }
                            tvThreatLevel.setTextColor(levelColor);
                            tvThreatPercent.setTextColor(levelColor);
                            progressThreat.setProgressTintList(ColorStateList.valueOf(levelColor));

                            if (tvKeywords != null && !result.keywordsFound.isEmpty()) {
                                tvKeywords.setText(String.join(", ", result.keywordsFound));
                            }
                            if (tvAiSource != null) {
                                tvAiSource.setText("NVIDIA AI · FILE ANALYSIS");
                            }

                            // Update transcript with full analysis
                            StringBuilder sb = new StringBuilder();
                            sb.append("ANALYZER RESULT\n");
                            sb.append("Verdict: ").append(result.isScam ? "SCAM DETECTED" : "SAFE").append("\n");
                            sb.append("Risk: ").append(result.getThreatLevel()).append("\n");
                            sb.append("Confidence: ").append(pct).append("%\n");
                            sb.append("Type: ").append(result.threatType).append("\n");
                            sb.append("Reasoning: ").append(result.reasoning);
                            sb.append("\n\n---------------------------\n");
                            sb.append("TRANSCRIPT\n\n").append(transcript);
                            tvTranscript.setText(sb.toString());
                            scrollTranscript.post(() -> scrollTranscript.fullScroll(View.FOCUS_UP));

                            // Save to history
                            saveAudioAnalysisToHistory(transcript, result);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> showAudioError(error));
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Audio file read error", e);
                runOnUiThread(() -> showAudioError("Failed to read audio: " + e.getMessage()));
            }
        }).start();
    }

    private void showAudioError(String error) {
        btnUploadAudio.setEnabled(true);
        btnUploadAudio.setText("⬆ UPLOAD AUDIO FILE FOR ANALYSIS");
        tvTranscript.setTextColor(getColor(R.color.neon_crimson));
        tvTranscript.setText("❌ ERROR:\n\n" + error);
        tvThreatLevel.setText("ERROR");
        tvThreatLevel.setTextColor(getColor(R.color.neon_crimson));
    }

    private void saveAudioAnalysisToHistory(String transcript, ScamAnalyzerAI.AnalysisResult result) {
        try {
            Map<String, Object> entry = new HashMap<>();
            entry.put("phoneNumber", "AUDIO_UPLOAD");
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("transcript", transcript);
            entry.put("scamScore", result.confidence);
            entry.put("isScam", result.isScam);
            entry.put("threatType", result.threatType);
            entry.put("reasoning", result.reasoning);
            entry.put("riskLevel", result.getThreatLevel());
            entry.put("source", "audio_upload");
            entry.put("blocked", false);
            entry.put("callDurationSeconds", 0);
            FirestoreHelper.getInstance().saveCallLog(entry);
            Log.i(TAG, "Audio analysis saved to history");
        } catch (Exception e) {
            Log.e(TAG, "Failed to save audio analysis", e);
        }
    }
}
