package com.example.hydro_guard;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class PeranActivity extends AppCompatActivity {

    private MaterialButton btnAdmin;
    private MaterialButton btnOperator;

    private static final int REQ_POST_NOTIF = 2001;

    // ✅ Samakan dengan yang dipakai MainActivity/AdminDashboardActivity
    private static final String RTDB_URL = "https://hydro-guard-mobile-default-rtdb.firebaseio.com/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_peran);

        FirebaseApp.initializeApp(this);

        askNotifPermissionIfNeeded();

        btnAdmin = findViewById(R.id.btnAdmin);
        btnOperator = findViewById(R.id.btnOperator);

        btnAdmin.setOnClickListener(v ->
                startActivity(new Intent(PeranActivity.this, LoginAdminActivity.class))
        );

        btnOperator.setOnClickListener(v ->
                startActivity(new Intent(PeranActivity.this, LoginOperatorActivity.class))
        );
    }

    @Override
    protected void onStart() {
        super.onStart();

        // ✅ Jika user sudah login, langsung arahkan sesuai role (agar tidak terlihat "logout")
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) {
            routeByRole(u);
        }
    }

    private void routeByRole(@NonNull FirebaseUser user) {
        DatabaseReference userRef = FirebaseDatabase.getInstance(RTDB_URL)
                .getReference("users")
                .child(user.getUid());

        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {

                String role = s.child("role").getValue(String.class);
                if (TextUtils.isEmpty(role)) {
                    // Role belum diset -> tetap di Peran, jangan signOut diam-diam
                    Toast.makeText(PeranActivity.this,
                            "Role akun belum di-set di /users/{uid}/role. Hubungi admin.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if ("admin".equalsIgnoreCase(role)) {
                    Intent i = new Intent(PeranActivity.this, AdminDashboardActivity.class);
                    i.putExtra("role", "admin");
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    finish();
                    return;
                }

                if ("operator".equalsIgnoreCase(role)) {
                    Boolean approved = s.child("approved").getValue(Boolean.class);
                    boolean isApproved = approved != null && approved;

                    // Jika Anda memang pakai approval operator:
                    if (!isApproved) {
                        Toast.makeText(PeranActivity.this,
                                "Akun operator belum disetujui admin.",
                                Toast.LENGTH_LONG).show();
                        // optional: signOut agar tidak nyangkut session operator pending
                        FirebaseAuth.getInstance().signOut();
                        return;
                    }

                    String name = s.child("name").getValue(String.class);
                    if (TextUtils.isEmpty(name)) name = user.getEmail();

                    Intent i = new Intent(PeranActivity.this, MainActivity.class);
                    i.putExtra("role", "operator");
                    i.putExtra("operator_name", name);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    finish();
                    return;
                }

                Toast.makeText(PeranActivity.this,
                        "Role tidak dikenali: " + role,
                        Toast.LENGTH_LONG).show();
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(PeranActivity.this,
                        "Gagal cek role: " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void askNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Izin Notifikasi")
                    .setMessage("Hydro Guard membutuhkan izin notifikasi agar peringatan sensor & pompa dapat muncul sebagai pop-up.")
                    .setNegativeButton("Nanti", (d, w) -> d.dismiss())
                    .setPositiveButton("Izinkan", (d, w) -> ActivityCompat.requestPermissions(
                            this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            REQ_POST_NOTIF
                    ))
                    .show();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIF
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQ_POST_NOTIF) return;

        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            Toast.makeText(this, "Notifikasi diizinkan.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.POST_NOTIFICATIONS
        );

        if (!canAskAgain) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Izin Notifikasi Diperlukan")
                    .setMessage("Anda menolak izin notifikasi. Aktifkan dari Pengaturan agar peringatan dapat muncul.")
                    .setNegativeButton("Tutup", (d, w) -> d.dismiss())
                    .setPositiveButton("Buka Pengaturan", (d, w) -> openAppSettings())
                    .show();
        } else {
            Toast.makeText(this, "Izin notifikasi ditolak.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(i);
    }
}
