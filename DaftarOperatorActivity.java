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

public class DaftarOperatorActivity extends AppCompatActivity {

    private TextInputEditText etNama;
    private TextInputEditText etHp;
    private TextInputEditText etEmail;
    private TextInputEditText etPassword;
    private TextInputEditText etConfirm;
    private MaterialButton btnDaftar;
    private TextView tvMasuk;
    private View progressOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_daftar_operator);

        initViews();
        setupActions();
    }

    private void initViews() {
        etNama     = findViewById(R.id.etNama);
        etHp       = findViewById(R.id.etHp);
        etEmail    = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etConfirm  = findViewById(R.id.etConfirm);
        btnDaftar  = findViewById(R.id.btnDaftar);
        tvMasuk    = findViewById(R.id.tvMasuk);

        progressOverlay = findViewById(R.id.progressOverlay);
        if (progressOverlay != null) {
            progressOverlay.setVisibility(View.GONE);
        }
    }

    private void setupActions() {
        btnDaftar.setOnClickListener(v -> attemptRegister());

        tvMasuk.setOnClickListener(v -> {
            Intent intent = new Intent(DaftarOperatorActivity.this, LoginOperatorActivity.class);
            startActivity(intent);
            finish();
        });
    }

    private void attemptRegister() {
        String nama     = etNama.getText()     != null ? etNama.getText().toString().trim()     : "";
        String hp       = etHp.getText()       != null ? etHp.getText().toString().trim()       : "";
        String email    = etEmail.getText()    != null ? etEmail.getText().toString().trim()    : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";
        String confirm  = etConfirm.getText()  != null ? etConfirm.getText().toString().trim()  : "";

        if (TextUtils.isEmpty(nama)) {
            etNama.setError(getString(R.string.error_required));
            etNama.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(hp)) {
            etHp.setError(getString(R.string.error_required));
            etHp.requestFocus();
            return;
        }

        if (hp.length() < 8) {
            etHp.setError(getString(R.string.error_invalid_phone));
            etHp.requestFocus();
            return;
        }

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

        if (TextUtils.isEmpty(confirm)) {
            etConfirm.setError(getString(R.string.error_required));
            etConfirm.requestFocus();
            return;
        }

        if (!password.equals(confirm)) {
            etConfirm.setError(getString(R.string.error_password_not_match));
            etConfirm.requestFocus();
            return;
        }

        setLoading(true);

        HelperClass.registerOperator(
                nama,
                hp,
                email,
                password,
                new HelperClass.AuthCallback() {
                    @Override
                    public void onSuccess(@NonNull FirebaseUser user) {
                        setLoading(false);
                        Toast.makeText(
                                DaftarOperatorActivity.this,
                                getString(R.string.msg_register_operator_success),
                                Toast.LENGTH_SHORT
                        ).show();

                        Intent intent = new Intent(
                                DaftarOperatorActivity.this,
                                LoginOperatorActivity.class
                        );
                        startActivity(intent);
                        finish();
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        setLoading(false);
                        Toast.makeText(
                                DaftarOperatorActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    private void setLoading(boolean loading) {
        btnDaftar.setEnabled(!loading);
        if (progressOverlay != null) {
            progressOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }
}
