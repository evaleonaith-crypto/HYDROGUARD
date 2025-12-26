package com.example.hydro_guard;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseUser;

public class LoginAdminActivity extends AppCompatActivity {

    private TextInputEditText etEmail;
    private TextInputEditText etPassword;
    private MaterialButton btnMasuk;
    private View progressOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login_admin);

        FirebaseApp.initializeApp(this);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnMasuk = findViewById(R.id.btnMasuk);
        progressOverlay = findViewById(R.id.progressOverlay);

        if (progressOverlay != null) progressOverlay.setVisibility(View.GONE);

        if (etEmail != null) {
            etEmail.setText(HelperClass.ADMIN_EMAIL);
            etEmail.setEnabled(false);
        }

        btnMasuk.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String email = HelperClass.ADMIN_EMAIL;
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        if (TextUtils.isEmpty(password)) {
            etPassword.setError(getString(R.string.error_required));
            etPassword.requestFocus();
            return;
        }
        if (password.length() < 6) {
            etPassword.setError(getString(R.string.error_password_short));
            etPassword.requestFocus();
            return;
        }

        setLoading(true);

        HelperClass.loginAdmin(email, password, new HelperClass.AuthCallback() {
            @Override
            public void onSuccess(@NonNull FirebaseUser user) {
                setLoading(false);
                Toast.makeText(LoginAdminActivity.this, "Login admin berhasil.", Toast.LENGTH_SHORT).show();

                Intent i = new Intent(LoginAdminActivity.this, AdminDashboardActivity.class);
                i.putExtra("role", "admin");
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
            }

            @Override
            public void onError(@NonNull String message) {
                setLoading(false);
                Toast.makeText(LoginAdminActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setLoading(boolean loading) {
        btnMasuk.setEnabled(!loading);
        if (progressOverlay != null) progressOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
