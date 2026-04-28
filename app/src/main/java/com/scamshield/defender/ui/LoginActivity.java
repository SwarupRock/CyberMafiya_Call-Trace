package com.scamshield.defender.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
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

    private EditText etEmail, etPassword;
    private Button btnEmailSignIn, btnEmailSignUp, btnGoogleSignIn, btnPhoneMode;
    private LinearLayout layoutEmailInput;
    private TextView tvStatus, tvForgotPassword, tvAuthHint, tvEmailMode, tvOtpEmailMode, tvPhoneRecovery;

    private EditText etPhone, etOtp;
    private Button btnSendOtp, btnVerifyOtp;
    private LinearLayout layoutPhoneInput, layoutOtpInput;
    private TextView tvOtpSentTo, tvResendOtp;
    private Spinner spinnerCountryCode;

    private String verificationId;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private String userPhone;

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
        showEmailMode();
    }

    private boolean isLoggedIn() {
        return mAuth.getCurrentUser() != null || getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("is_logged_in", false);
    }

    private void bindViews() {
        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        btnEmailSignIn = findViewById(R.id.btn_email_signin);
        btnEmailSignUp = findViewById(R.id.btn_email_signup);
        btnGoogleSignIn = findViewById(R.id.btn_google_signin);
        btnPhoneMode = findViewById(R.id.btn_phone_mode);
        layoutEmailInput = findViewById(R.id.layout_email_input);
        tvStatus = findViewById(R.id.tv_status);
        tvForgotPassword = findViewById(R.id.tv_forgot_password);
        tvAuthHint = findViewById(R.id.tv_auth_hint);

        etPhone = findViewById(R.id.et_phone);
        etOtp = findViewById(R.id.et_otp);
        btnSendOtp = findViewById(R.id.btn_send_otp);
        btnVerifyOtp = findViewById(R.id.btn_verify_otp);
        layoutPhoneInput = findViewById(R.id.layout_phone_input);
        layoutOtpInput = findViewById(R.id.layout_otp_input);
        tvOtpSentTo = findViewById(R.id.tv_otp_sent_to);
        tvResendOtp = findViewById(R.id.tv_resend_otp);
        tvEmailMode = findViewById(R.id.tv_email_mode);
        tvOtpEmailMode = findViewById(R.id.tv_otp_email_mode);
        tvPhoneRecovery = findViewById(R.id.tv_phone_recovery);
        spinnerCountryCode = findViewById(R.id.spinner_country_code);
    }

    private void setupListeners() {
        btnEmailSignIn.setOnClickListener(v -> signInWithEmailPassword());
        btnEmailSignUp.setOnClickListener(v -> createAccountWithEmailPassword());
        btnGoogleSignIn.setOnClickListener(v -> signInWithGoogle());
        btnPhoneMode.setOnClickListener(v -> showPhoneMode());
        tvForgotPassword.setOnClickListener(v -> sendPasswordReset());
        tvEmailMode.setOnClickListener(v -> showEmailMode());
        tvOtpEmailMode.setOnClickListener(v -> showEmailMode());
        tvPhoneRecovery.setOnClickListener(v -> {
            showEmailMode();
            showStatus("For phone login, resend the OTP. For an email account, enter your email and tap forgot password.", false);
        });

        btnSendOtp.setOnClickListener(v -> sendOtp());
        btnVerifyOtp.setOnClickListener(v -> verifyOtp());
        tvResendOtp.setOnClickListener(v -> sendOtp());
        
        setupCountryCodeSpinner();
    }

    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);
    }

    private void showEmailMode() {
        layoutEmailInput.setVisibility(View.VISIBLE);
        layoutPhoneInput.setVisibility(View.GONE);
        layoutOtpInput.setVisibility(View.GONE);
        tvAuthHint.setText("Login to continue to your account");
        tvStatus.setVisibility(View.GONE);
    }

    private void showPhoneMode() {
        layoutEmailInput.setVisibility(View.GONE);
        layoutPhoneInput.setVisibility(View.VISIBLE);
        layoutOtpInput.setVisibility(View.GONE);
        tvAuthHint.setText("Continue with your phone number");
        tvStatus.setVisibility(View.GONE);
    }

    private void signInWithGoogle() {
        setLoading(true, "Opening Google sign-in...");
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_SIGN_IN) {
            return;
        }

        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            String idToken = account.getIdToken();
            if (idToken == null || idToken.trim().isEmpty()) {
                setLoading(false, null);
                showStatus("Google did not return a Firebase token. Check the web client ID in Firebase.", true);
                return;
            }
            firebaseAuthWithGoogle(idToken);
        } catch (ApiException e) {
            Log.w(TAG, "Google sign in failed", e);
            setLoading(false, null);
            showStatus("Google sign in failed. Use forgot password for email accounts, or recover your Google account in Google.", true);
        }
    }

    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    setLoading(false, null);
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        onAuthSuccess(user != null ? user.getEmail() : "google");
                    } else {
                        showStatus(mapAuthError(task.getException(), "Google authentication failed"), true);
                    }
                });
    }

    private void setupCountryCodeSpinner() {
        String[] countryCodes = new String[]{"+91", "+1", "+44", "+61", "+81", "+86", "+49", "+33"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, countryCodes) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(getColor(R.color.text_primary));
                view.setTextSize(15);
                view.setGravity(Gravity.CENTER);
                view.setSingleLine(true);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(getColor(R.color.text_primary));
                view.setBackgroundColor(getColor(R.color.obsidian_800));
                view.setGravity(Gravity.CENTER);
                view.setMinHeight((int) (44 * getResources().getDisplayMetrics().density));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCountryCode.setAdapter(adapter);
        spinnerCountryCode.setSelection(0);
        spinnerCountryCode.setEnabled(true);
        spinnerCountryCode.setDropDownWidth((int) (82 * getResources().getDisplayMetrics().density));
        spinnerCountryCode.setDropDownVerticalOffset((int) (8 * getResources().getDisplayMetrics().density));
    }

    private void signInWithEmailPassword() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        if (!isValidEmail(email)) {
            showStatus("Enter a valid email address", true);
            return;
        }
        if (password.length() < 6) {
            showStatus("Password must be at least 6 characters", true);
            return;
        }

        setLoading(true, "Signing in...");
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, signInTask -> {
                    if (signInTask.isSuccessful()) {
                        onAuthSuccess(email);
                        return;
                    }

                    setLoading(false, null);
                    showStatus(mapAuthError(signInTask.getException(), "Email sign in failed"), true);
                });
    }

    private void createAccountWithEmailPassword() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        if (!isValidEmail(email)) {
            showStatus("Enter your email first, then choose a password to create the account.", true);
            return;
        }
        if (password.length() < 6) {
            etPassword.requestFocus();
            showStatus("Create account: enter a password with at least 6 characters.", true);
            return;
        }
        createEmailAccount(email, password);
    }

    private void createEmailAccount(String email, String password) {
        setLoading(true, "Creating account...");
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, createTask -> {
                    setLoading(false, null);
                    if (createTask.isSuccessful()) {
                        onAuthSuccess(email);
                    } else {
                        showStatus(mapAuthError(createTask.getException(), "Email signup failed"), true);
                    }
                });
    }

    private void sendPasswordReset() {
        String email = etEmail.getText().toString().trim();
        if (!isValidEmail(email)) {
            etEmail.requestFocus();
            showStatus("Enter your email address first, then tap forgot password again.", true);
            return;
        }

        setLoading(true, "Sending reset link...");
        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    setLoading(false, null);
                    if (task.isSuccessful()) {
                        showStatus("Password reset link sent to " + email, false);
                    } else {
                        showStatus(mapAuthError(task.getException(), "Could not send reset link"), true);
                    }
                });
    }

    private void sendOtp() {
        String phone = etPhone.getText().toString().trim();
        if (phone.isEmpty()) {
            showStatus("Enter a valid phone number", true);
            return;
        }

        String countryCode = spinnerCountryCode.getSelectedItem().toString();
        userPhone = countryCode + phone;
        setLoading(true, "Sending OTP...");

        PhoneAuthOptions.Builder optionsBuilder = PhoneAuthOptions.newBuilder(mAuth)
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
                });

        if (resendToken != null) {
            optionsBuilder.setForceResendingToken(resendToken);
        }

        PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build());
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
                            onAuthSuccess(user.getPhoneNumber());
                        } else {
                            showStatus("Verification failed", true);
                        }
                    });
                });
    }

    private void showOtpScreen() {
        layoutEmailInput.setVisibility(View.GONE);
        layoutPhoneInput.setVisibility(View.GONE);
        layoutOtpInput.setVisibility(View.VISIBLE);
        tvAuthHint.setText("Enter the OTP from Firebase. Forgot the code? Resend it below.");

        tvOtpSentTo.setText("OTP sent to " + userPhone);
        etOtp.requestFocus();
        showStatus("Check your phone for the verification code", false);
    }

    private void onAuthSuccess(String identifier) {
        setLoading(false, null);
        saveSession(identifier);
        checkProfileAndNavigate();
    }

    private void saveSession(String identifier) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean("is_logged_in", true);
        editor.putString("user_identifier", identifier);
        if (identifier != null && identifier.contains("@")) {
            editor.putString("user_email", identifier);
            editor.putString("user_phone", "");
        } else if (identifier != null && !identifier.trim().isEmpty()) {
            editor.putString("user_phone", identifier);
        }
        editor.apply();
    }

    private void showStatus(String msg, boolean isError) {
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(msg);
        tvStatus.setTextColor(getColor(isError ? R.color.neon_crimson : R.color.cyber_cyan));
    }

    private void setLoading(boolean loading, String message) {
        if (btnEmailSignIn == null) {
            return;
        }
        btnEmailSignIn.setEnabled(!loading);
        btnEmailSignUp.setEnabled(!loading);
        btnGoogleSignIn.setEnabled(!loading);
        btnPhoneMode.setEnabled(!loading);
        etEmail.setEnabled(!loading);
        etPassword.setEnabled(!loading);

        btnSendOtp.setEnabled(!loading);
        btnVerifyOtp.setEnabled(!loading);
        etPhone.setEnabled(!loading);
        etOtp.setEnabled(!loading);

        if (loading && message != null) {
            showStatus(message, false);
        }
    }

    private boolean isValidEmail(String email) {
        return !TextUtils.isEmpty(email) && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches();
    }

    private String mapAuthError(Exception error, String fallback) {
        if (error == null) {
            return fallback;
        }
        if (error instanceof FirebaseAuthInvalidCredentialsException) {
            return "Invalid credentials. Check the email/password.";
        }

        String message = error.getMessage();
        if (message == null) {
            return fallback;
        }
        if (message.toLowerCase().contains("network")) {
            return "Network issue while contacting Firebase. Check internet and retry.";
        }
        return fallback + ": " + message;
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
