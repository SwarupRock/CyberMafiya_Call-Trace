package com.scamshield.defender.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.scamshield.defender.R;
import com.scamshield.defender.service.CallDefenderService;

import java.util.ArrayList;
import java.util.List;

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

    // ── Broadcast Actions (received from CallDefenderService) ─────
    public static final String ACTION_CALL_STATE = "com.scamshield.CALL_STATE";
    public static final String ACTION_TRANSCRIPT = "com.scamshield.TRANSCRIPT";
    public static final String ACTION_THREAT_UPDATE = "com.scamshield.THREAT_UPDATE";

    // ── Views ─────────────────────────────────────────────────────
    private View statusDot, livePulse;
    private TextView tvShieldStatus, tvCallState, tvCallerNumber;
    private TextView tvTranscript;
    private ProgressBar progressThreat;
    private TextView tvThreatPercent, tvThreatLevel, tvThreatType, tvKeywords, tvAiSource;
    private TextView tvCallsAnalyzed, tvScamsBlocked, tvCommunityReports;
    private Button btnToggleShield;
    private TextView tvUserPhone;
    private ImageView ivUserProfile;

    private boolean isDefenderRunning = false;
    private StringBuilder transcriptBuilder = new StringBuilder();

    // ── Required permissions ──────────────────────────────────────
    private static final String[] REQUIRED_PERMISSIONS;
    static {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.READ_CALL_LOG);
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

        bindViews();
        loadState();
        setupListeners();
        updateShieldUI();
        loadUserInfo();
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
        tvUserPhone = findViewById(R.id.tv_user_phone);
        ivUserProfile = findViewById(R.id.iv_user_profile);
    }

    // ═══════════════════════════════════════════════════════════════
    // SETUP
    // ═══════════════════════════════════════════════════════════════

    private void setupListeners() {
        btnToggleShield.setOnClickListener(v -> {
            if (isDefenderRunning) {
                stopDefender();
            } else {
                if (hasAllPermissions()) {
                    startDefender();
                } else {
                    requestPermissions();
                }
            }
        });

        ivUserProfile.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add("Logout");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getTitle().equals("Logout")) {
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
                return true;
            });
            popup.show();
        });
    }

    private void loadUserInfo() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String phone = prefs.getString("user_phone", "");
        if (!phone.isEmpty() && !phone.equals("anonymous")) {
            String masked = phone.substring(0, 4) + "****" + phone.substring(phone.length() - 2);
            tvUserPhone.setText("Logged in as " + masked);
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
    }

    // ═══════════════════════════════════════════════════════════════
    // BROADCAST RECEIVERS (real-time updates from service)
    // ═══════════════════════════════════════════════════════════════

    private void registerBroadcastReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CALL_STATE);
        filter.addAction(ACTION_TRANSCRIPT);
        filter.addAction(ACTION_THREAT_UPDATE);
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
                tvTranscript.setText("Listening...");
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

        tvAiSource.setText(fromCloud ? "☁ GEMINI AI" : "⚡ LOCAL AI");
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
        tvAiSource.setText("⚡ LOCAL AI");
    }

    // ═══════════════════════════════════════════════════════════════
    // SERVICE CONTROL
    // ═══════════════════════════════════════════════════════════════

    private void startDefender() {
        Intent serviceIntent = new Intent(this, CallDefenderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        isDefenderRunning = true;
        saveState(true);
        updateShieldUI();
        Toast.makeText(this, "🛡️ Scam Shield ACTIVATED", Toast.LENGTH_SHORT).show();
    }

    private void stopDefender() {
        stopService(new Intent(this, CallDefenderService.class));
        isDefenderRunning = false;
        saveState(false);
        updateShieldUI();
        Toast.makeText(this, "Shield deactivated", Toast.LENGTH_SHORT).show();
    }

    private void updateShieldUI() {
        if (isDefenderRunning) {
            btnToggleShield.setText("DEACTIVATE SHIELD");
            btnToggleShield.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.danger_red));
            tvShieldStatus.setText("ACTIVE");
            tvShieldStatus.setTextColor(getColor(R.color.cyber_green));
            statusDot.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.cyber_green)));
            tvCallState.setText("MONITORING");
            tvCallState.setTextColor(getColor(R.color.cyber_cyan));
            tvCallerNumber.setText("Waiting for incoming call...");
        } else {
            btnToggleShield.setText("ACTIVATE SHIELD");
            btnToggleShield.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.cyber_cyan));
            tvShieldStatus.setText("INACTIVE");
            tvShieldStatus.setTextColor(getColor(R.color.text_muted));
            statusDot.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.text_muted)));
            tvCallState.setText("SHIELD OFF");
            tvCallState.setTextColor(getColor(R.color.text_muted));
            tvCallerNumber.setText("Activate shield to start monitoring");
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
                startDefender();
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
}
