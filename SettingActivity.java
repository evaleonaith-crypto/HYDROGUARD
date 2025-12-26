package com.example.hydro_guard;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class SettingActivity extends AppCompatActivity {

    private static final int REQ_WIFI_PERMS = 2101;

    private static final String RTDB_URL = "https://hydro-guard-mobile-default-rtdb.firebaseio.com/";
    private static final String DEFAULT_DEVICE_ID = "HG-01";

    private ImageView btnBack;
    private TextView tvWifiName;

    private LinearLayout rowJadwalPagi, rowJadwalSore;
    private TextView tvJadwalPagi, tvJadwalSore;

    private MaterialButton btnGantiKoneksi, btnKeluar;

    private FirebaseAuth auth;
    private DatabaseReference jadwalAutoRef;
    private ValueEventListener jadwalListener;

    private String deviceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);

        FirebaseApp.initializeApp(this);

        deviceId = getIntent().getStringExtra("deviceId");
        if (TextUtils.isEmpty(deviceId)) deviceId = DEFAULT_DEVICE_ID;

        auth = FirebaseAuth.getInstance();

        // ✅ WAJIB pakai RTDB_URL + child(deviceId)
        FirebaseDatabase db = FirebaseDatabase.getInstance(RTDB_URL);
        jadwalAutoRef = db.getReference("jadwal_otomatis").child(deviceId);

        initViews();
        setupActions();

        listenJadwalOtomatis();

        ensureWifiPermissionIfNeeded();
        updateWifiName();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateWifiName();
    }

    private void initViews() {
        btnBack    = findViewById(R.id.btnBack);
        tvWifiName = findViewById(R.id.tvWifiName);

        rowJadwalPagi = findViewById(R.id.rowJadwalPagi);
        rowJadwalSore = findViewById(R.id.rowJadwalSore);
        tvJadwalPagi  = findViewById(R.id.tvJadwalPagi);
        tvJadwalSore  = findViewById(R.id.tvJadwalSore);

        btnGantiKoneksi = findViewById(R.id.btnGantiKoneksi);
        btnKeluar       = findViewById(R.id.btnKeluar);
    }

    private void setupActions() {
        btnBack.setOnClickListener(v -> finish());

        btnGantiKoneksi.setOnClickListener(v -> openWifiSettings());

        rowJadwalPagi.setOnClickListener(v -> openTimePickerAndSave("pagi"));
        rowJadwalSore.setOnClickListener(v -> openTimePickerAndSave("sore"));

        btnKeluar.setOnClickListener(v -> {
            if (auth != null) auth.signOut();
            Toast.makeText(this, "Anda telah keluar.", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, PeranActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            } else {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void updateWifiName() {
        String ssid = getConnectedWifiSsid();

        if (ssid == null) {
            tvWifiName.setText("Nama Wifi: -");
            return;
        }

        tvWifiName.setText("Nama Wifi: " + ssid);

        if ("Aktifkan Lokasi (GPS)".equals(ssid)) {
            Toast.makeText(this, "Aktifkan Lokasi agar SSID bisa terbaca.", Toast.LENGTH_SHORT).show();
        }
    }

    private String getConnectedWifiSsid() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return null;

        Network active = cm.getActiveNetwork();
        if (active == null) return "Tidak terhubung WiFi";

        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Tidak terhubung WiFi";
        }

        if (!hasWifiRuntimePermission()) return "Izin belum diberikan";
        if (!isLocationEnabled()) return "Aktifkan Lokasi (GPS)";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Object ti = caps.getTransportInfo();
                if (ti instanceof WifiInfo) {
                    String ssid = normalizeSsid(((WifiInfo) ti).getSSID());
                    if (!TextUtils.isEmpty(ssid)) return ssid;
                }
            } catch (Exception ignored) {}
        }

        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager == null) return null;

            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) return null;

            String ssid = normalizeSsid(info.getSSID());
            if (!TextUtils.isEmpty(ssid)) return ssid;

        } catch (Exception ignored) {}

        return null;
    }

    private String normalizeSsid(String ssid) {
        if (ssid == null) return null;
        ssid = ssid.replace("\"", "").trim();
        if ("<unknown ssid>".equalsIgnoreCase(ssid) || ssid.isEmpty()) return null;
        return ssid;
    }

    private boolean isLocationEnabled() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return false;
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureWifiPermissionIfNeeded() {
        if (hasWifiRuntimePermission()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                    REQ_WIFI_PERMS
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_WIFI_PERMS
            );
        }
    }

    private boolean hasWifiRuntimePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    // ================= Jadwal Otomatis (Firebase) =================

    private void listenJadwalOtomatis() {
        if (jadwalAutoRef == null) return;

        jadwalListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String pagi = snapshot.child("pagi").getValue(String.class);
                String sore = snapshot.child("sore").getValue(String.class);

                tvJadwalPagi.setText("Pagi: [ " + (TextUtils.isEmpty(pagi) ? "-" : pagi) + " ]");
                tvJadwalSore.setText("Sore: [ " + (TextUtils.isEmpty(sore) ? "-" : sore) + " ]");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(SettingActivity.this,
                        "Gagal memuat jadwal: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        };

        jadwalAutoRef.addValueEventListener(jadwalListener);
    }

    private void openTimePickerAndSave(String field) {
        String currentLabel = "pagi".equals(field)
                ? tvJadwalPagi.getText().toString()
                : tvJadwalSore.getText().toString();

        String currentTime = extractTimeFromLabel(currentLabel);

        int hh = "pagi".equals(field) ? 7 : 17;
        int mm = 0;

        if (!TextUtils.isEmpty(currentTime) && currentTime.contains(":")) {
            try {
                String[] parts = currentTime.split(":");
                hh = Integer.parseInt(parts[0]);
                mm = Integer.parseInt(parts[1]);
            } catch (Exception ignored) {}
        }

        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    String newTime = String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute);
                    saveJadwalOtomatis(field, newTime);
                },
                hh, mm, true
        );

        dialog.setTitle("Atur Jadwal " + ("pagi".equals(field) ? "Pagi" : "Sore"));
        dialog.show();
    }

    private String extractTimeFromLabel(String label) {
        if (label == null) return null;
        int idx1 = label.indexOf("[");
        int idx2 = label.indexOf("]");
        if (idx1 >= 0 && idx2 > idx1) {
            return label.substring(idx1 + 1, idx2).replace(" ", "").trim();
        }
        return null;
    }

    private void saveJadwalOtomatis(String field, String time) {
        if (jadwalAutoRef == null) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put(field, time);
        updates.put("updatedAt", ServerValue.TIMESTAMP);
        if (auth != null && auth.getCurrentUser() != null) {
            updates.put("updatedBy", auth.getCurrentUser().getUid());
        }

        jadwalAutoRef.updateChildren(updates)
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, "Jadwal diperbarui: " + time, Toast.LENGTH_SHORT).show()
                )
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Gagal update jadwal: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_WIFI_PERMS) {
            updateWifiName();

            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Izin diperlukan agar nama WiFi bisa terbaca.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (jadwalAutoRef != null && jadwalListener != null) {
            jadwalAutoRef.removeEventListener(jadwalListener);
        }
    }
}
