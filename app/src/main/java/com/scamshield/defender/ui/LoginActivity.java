package com.scamshield.defender.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.scamshield.defender.R;
import com.scamshield.defender.model.UserProfile;
import com.scamshield.defender.network.FirebaseClient;

import java.util.concurrent.TimeUnit;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";
    private static final String PREFS_NAME = "scam_shield_prefs";
    private static final int RC_SIGN_IN = 9001;

    private EditText etPhone, etOtp;
    private Button btnSendOtp, btnVerifyOtp, btnGoogleSignIn;
    private LinearLayout layoutPhoneInput, layoutOtpInput;
    private TextView tvOtpSentTo, tvResendOtp, tvStatus;
    private Button btnDemoMode;

    private String userPhone = "";
    private String verificationId;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FirebaseAuth mAuth;
    private GoogleSignInClient mGoogleSignInClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();

        if (isLoggedIn()) {
            checkProfileAndNavigate();
            return;
        }

        setContentView(R.layout.activity_login);
        bindViews();
        setupListeners();
        setupGoogleSignIn();
    }

    private boolean isLoggedIn() {
        return mAuth.getCurrentUser() != null || getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("is_logged_in", false);
    }

    private void bindViews() {
        etPhone = findViewById(R.id.et_phone);
        etOtp = findViewById(R.id.et_otp);
        btnSendOtp = findViewById(R.id.btn_send_otp);
        btnVerifyOtp = findViewById(R.id.btn_verify_otp);
        btnGoogleSignIn = findViewById(R.id.btn_google_signin);
        layoutPhoneInput = findViewById(R.id.layout_phone_input);
        layoutOtpInput = findViewById(R.id.layout_otp_input);
        tvOtpSentTo = findViewById(R.id.tv_otp_sent_to);
        tvResendOtp = findViewById(R.id.tv_resend_otp);
        tvStatus = findViewById(R.id.tv_status);
        btnDemoMode = findViewById(R.id.btn_demo_mode);
    }

    private void setupListeners() {
        btnSendOtp.setOnClickListener(v -> sendOtp());
        btnVerifyOtp.setOnClickListener(v -> verifyOtp());
        tvResendOtp.setOnClickListener(v -> sendOtp());
        btnDemoMode.setOnClickListener(v -> enterDemoMode());
        btnGoogleSignIn.setOnClickListener(v -> signInWithGoogle());
    }

    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);
    }

    private void signInWithGoogle() {
        setLoading(true, "Signing in with Google...");
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                firebaseAuthWithGoogle(account.getIdToken());
            } catch (ApiException e) {
                Log.w(TAG, "Google sign in failed", e);
                setLoading(false, null);
                showStatus("Google sign in failed", true);
            }
        }
    }

    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    setLoading(false, null);
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        saveSession(user.getEmail());
                        checkProfileAndNavigate();
                    } else {
                        showStatus("Authentication Failed", true);
                    }
                });
    }

    private void sendOtp() {
        String phone = etPhone.getText().toString().trim();
        if (phone.isEmpty()) {
            showStatus("Enter a valid phone number", true);
            return;
        }

        userPhone = phone;
        setLoading(true, "Sending OTP...");

        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(mAuth)
                .setPhoneNumber(userPhone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    @Override
                    public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                        signInWithPhoneAuthCredential(credential);
                    }

                    @Override
                    public void onVerificationFailed(@NonNull FirebaseException e) {
                        mainHandler.post(() -> {
                            setLoading(false, null);
                            showStatus("Verification failed: " + e.getMessage(), true);
                        });
                    }

                    @Override
                    public void onCodeSent(@NonNull String verificationIdResult,
                                           @NonNull PhoneAuthProvider.ForceResendingToken token) {
                        verificationId = verificationIdResult;
                        resendToken = token;
                        mainHandler.post(() -> {
                            setLoading(false, null);
                            showOtpScreen();
                        });
                    }
                })
                .build();
        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    private void verifyOtp() {
        String otp = etOtp.getText().toString().trim();
        if (otp.length() != 6) {
            showStatus("Enter the 6-digit OTP code", true);
            return;
        }

        setLoading(true, "Verifying...");
        PhoneAuthCredential credential = PhoneAuthProvider.getCredential(verificationId, otp);
        signInWithPhoneAuthCredential(credential);
    }

    private void signInWithPhoneAuthCredential(PhoneAuthCredential credential) {
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    mainHandler.post(() -> {
                        setLoading(false, null);
                        if (task.isSuccessful()) {
                            FirebaseUser user = task.getResult().getUser();
                            saveSession(user.getPhoneNumber());
                            checkProfileAndNavigate();
                        } else {
                            showStatus("Verification failed", true);
                        }
                    });
                });
    }

    private void enterDemoMode() {
        setLoading(true, "Bypassing login...");
        mainHandler.postDelayed(() -> {
            setLoading(false, null);
            saveSession("anonymous");
            navigateToDashboard();
        }, 500);
    }

    private void saveSession(String identifier) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean("is_logged_in", true);
        editor.putString("user_identifier", identifier);
        editor.apply();
    }

    private void showOtpScreen() {
        layoutPhoneInput.setVisibility(View.GONE);
        btnGoogleSignIn.setVisibility(View.GONE);
        layoutOtpInput.setVisibility(View.VISIBLE);
        btnDemoMode.setVisibility(View.GONE);

        tvOtpSentTo.setText("OTP sent to " + userPhone);
        etOtp.requestFocus();
        showStatus("Check your phone for the verification code", false);
    }

    private void showStatus(String msg, boolean isError) {
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(msg);
        tvStatus.setTextColor(getColor(isError ? R.color.neon_crimson : R.color.cyber_green));
    }

    private void setLoading(boolean loading, String message) {
        btnSendOtp.setEnabled(!loading);
        btnVerifyOtp.setEnabled(!loading);
        btnGoogleSignIn.setEnabled(!loading);
        etPhone.setEnabled(!loading);
        etOtp.setEnabled(!loading);

        if (loading && message != null) {
            showStatus(message, false);
        }
    }

    private void navigateToDashboard() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void checkProfileAndNavigate() {
        if (mAuth.getCurrentUser() != null) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean isProfileComplete = prefs.getBoolean("is_profile_complete", false);

            if (isProfileComplete) {
                navigateToDashboard();
            } else {
                setLoading(true, "Checking profile...");
                FirebaseClient.getInstance().getUserProfile(new FirebaseClient.DataCallback<UserProfile>() {
                    @Override
                    public void onSuccess(UserProfile profile) {
                        mainHandler.post(() -> {
                            setLoading(false, null);
                            if (profile != null && profile.getCountry() != null && !profile.getCountry().isEmpty()) {
                                prefs.edit().putBoolean("is_profile_complete", true).apply();
                                navigateToDashboard();
                            } else {
                                startActivity(new Intent(LoginActivity.this, ProfileActivity.class));
                                finish();
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        mainHandler.post(() -> {
                            setLoading(false, null);
                            startActivity(new Intent(LoginActivity.this, ProfileActivity.class));
                            finish();
                        });
                    }
                });
            }
        } else {
            navigateToDashboard();
        }
    }
}
