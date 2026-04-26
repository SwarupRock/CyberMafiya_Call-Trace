package com.scamshield.defender.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseUser;
import com.scamshield.defender.R;
import com.scamshield.defender.model.UserProfile;
import com.scamshield.defender.network.FirebaseClient;

import java.util.Arrays;
import java.util.List;

public class ProfileActivity extends AppCompatActivity {

    private Spinner spinnerCountry;
    private Button btnSaveProfile;
    private ProgressBar progressProfile;
    
    private FirebaseClient firebaseClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        firebaseClient = FirebaseClient.getInstance();

        spinnerCountry = findViewById(R.id.spinner_country);
        btnSaveProfile = findViewById(R.id.btn_save_profile);
        progressProfile = findViewById(R.id.progress_profile);

        setupCountrySpinner();

        btnSaveProfile.setOnClickListener(v -> saveProfile());
    }

    private void setupCountrySpinner() {
        // A short list of common countries. In a real app, use a complete list or a library.
        List<String> countries = Arrays.asList(
                "Select Country",
                "United States",
                "India",
                "United Kingdom",
                "Canada",
                "Australia",
                "Germany",
                "France",
                "Brazil",
                "Japan",
                "Other"
        );

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                countries
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCountry.setAdapter(adapter);
    }

    private void saveProfile() {
        String selectedCountry = (String) spinnerCountry.getSelectedItem();
        if (selectedCountry == null || selectedCountry.equals("Select Country")) {
            Toast.makeText(this, "Please select your country", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSaveProfile.setEnabled(false);
        progressProfile.setVisibility(View.VISIBLE);

        FirebaseUser user = firebaseClient.getAuth().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not authenticated", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String phone = user.getPhoneNumber() != null ? user.getPhoneNumber() : "";
        String email = user.getEmail() != null ? user.getEmail() : "";

        UserProfile profile = new UserProfile(
                user.getUid(),
                phone,
                email,
                selectedCountry,
                System.currentTimeMillis()
        );

        firebaseClient.saveUserProfile(profile, new FirebaseClient.DataCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                // Save complete state to SharedPreferences
                SharedPreferences prefs = getSharedPreferences("scam_shield_prefs", MODE_PRIVATE);
                prefs.edit()
                        .putBoolean("is_profile_complete", true)
                        .putString("user_country", selectedCountry)
                        .apply();

                Toast.makeText(ProfileActivity.this, "Profile Saved", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(ProfileActivity.this, MainActivity.class));
                finish();
            }

            @Override
            public void onError(String error) {
                btnSaveProfile.setEnabled(true);
                progressProfile.setVisibility(View.GONE);
                Toast.makeText(ProfileActivity.this, "Error saving profile: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }
}
