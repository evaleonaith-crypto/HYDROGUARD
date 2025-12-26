package com.example.hydro_guard;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseUser;

public class LoginOperatorActivity extends AppCompatActivity {

    private TextInputEditText etEmail, etPassword;
    private MaterialButton btnMasuk;
    private TextView tvDaftar, tvLupaPassword;
    private View progressOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login_operator);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnMasuk = findViewById(R.id.btnMasuk);
        tvDaftar = findViewById(R.id.tvDaftar);
        tvLupaPassword = findViewById(R.id.tvForgotPassword);
        progressOverlay = findViewById(R.id.progressOverlay);

        if (progressOverlay != null) progressOverlay.setVisibility(View.GONE);

        btnMasuk.setOnClickListener(v -> attemptLogin());

        tvDaftar.setOnClickListener(v ->
                startActivity(new Intent(this, DaftarOperatorActivity.class))
        );

        if (tvLupaPassword != null) {
            tvLupaPassword.setOnClickListener(v -> {
                Toast.makeText(this, "Gunakan fitur reset password Anda (FirebaseAuth).", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void attemptLogin() {
        String email = getText(etEmail);
        String password = getText(etPassword);

        if (TextUtils.isEmpty(email)) {
            etEmail.setError(getString(R.string.error_required));
            etEmail.requestFocus();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError(getString(R.string.error_invalid_email));
            etEmail.requestFocus();
            return;
        }
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

        HelperClass.loginOperator(email, password, new HelperClass.AuthCallback() {
            @Override
            public void onSuccess(@NonNull FirebaseUser user) {
                setLoading(false);
                Toast.makeText(LoginOperatorActivity.this, "Login operator berhasil.", Toast.LENGTH_SHORT).show();

                Intent i = new Intent(LoginOperatorActivity.this, MainActivity.class);
                i.putExtra("role", "operator");
                i.putExtra("operator_name", user.getEmail());
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
            }

            @Override
            public void onError(@NonNull String message) {
                setLoading(false);
                Toast.makeText(LoginOperatorActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setLoading(boolean loading) {
        btnMasuk.setEnabled(!loading);
        tvDaftar.setEnabled(!loading);
        if (tvLupaPassword != null) tvLupaPassword.setEnabled(!loading);
        if (progressOverlay != null) progressOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private String getText(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }
}
