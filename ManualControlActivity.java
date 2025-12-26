package com.example.hydro_guard;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ManualControlActivity extends AppCompatActivity {

    private static final String DEFAULT_DEVICE_ID = "HG-01";
    private static final String TAG = "AI_CALL";

    private ImageView btnBack;
    private RadioGroup modeGroup;
    private RadioButton modeManual, modeAuto;
    private TextView tvPumpState;
    private View dotState;
    private MaterialButton btnPumpAction;

    private boolean pumpOn = false;
    private String deviceId;

    private FirebaseDatabase db;
    private DatabaseReference pumpRef;
    private DatabaseReference telemetryRef;
    private ValueEventListener pumpListener;

    private boolean suppressModeWrite = false;

    private boolean aiInFlight = false;
    private long lastAiCallMs = 0L;
    private static final long AI_COOLDOWN_MS = 1500L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manual_control);

        String extraDevice = getIntent().getStringExtra("deviceId");
        String extraTempat = getIntent().getStringExtra("tempatId");

        deviceId = !TextUtils.isEmpty(extraDevice) ? extraDevice
                : (!TextUtils.isEmpty(extraTempat) ? extraTempat : DEFAULT_DEVICE_ID);

        initViews();

        db = HelperClass.db();
        pumpRef = db.getReference("kontrol_pompa").child(deviceId);
        telemetryRef = db.getReference("telemetry").child(deviceId).child("latest");

        setupModeToggle();
        setupActions();
        attachPumpListener();

        ensureControlNodeExists();

        updatePumpUI();
        refreshButtonByMode();

        Toast.makeText(this, "Terhubung ke Device: " + deviceId, Toast.LENGTH_SHORT).show();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        modeGroup = findViewById(R.id.modeGroup);
        modeManual = findViewById(R.id.modeManual);
        modeAuto = findViewById(R.id.modeAuto);
        tvPumpState = findViewById(R.id.tvPumpState);
        dotState = findViewById(R.id.dotState);
        btnPumpAction = findViewById(R.id.btnPumpAction);
    }

    private void ensureControlNodeExists() {
        pumpRef.get().addOnSuccessListener(s -> {
            if (s.exists()) return;

            Map<String, Object> init = new HashMap<>();
            init.put("mode", "manual");
            init.put("status", false);
            init.put("updatedBy", "mobile_init");
            init.put("updatedAt", ServerValue.TIMESTAMP);
            pumpRef.updateChildren(init);
        });
    }

    private void setupModeToggle() {
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            refreshModeUI();
            refreshButtonByMode();
            if (suppressModeWrite) return;

            if (modeManual.isChecked()) {
                saveModeOnly("manual", "mobile_mode_manual");
            } else {
                saveModeOnly("auto", "mobile_mode_auto");
                runAiAndWriteDecision(true);
            }
        });

        refreshModeUI();
    }

    private void refreshModeUI() {
        if (modeManual.isChecked()) {
            modeManual.setBackgroundResource(R.drawable.selector_pill_choice);
            modeAuto.setBackgroundResource(R.drawable.shape_pill_outline);
        } else {
            modeManual.setBackgroundResource(R.drawable.shape_pill_outline);
            modeAuto.setBackgroundResource(R.drawable.selector_pill_choice);
        }
    }

    private void setupActions() {
        btnBack.setOnClickListener(v -> onBackPressed());

        btnPumpAction.setOnClickListener(v -> {
            if (modeManual.isChecked()) {
                pumpOn = !pumpOn;
                updatePumpUI();
                saveControlToFirebase("manual", pumpOn, "mobile_manual", null);
            } else {
                runAiAndWriteDecision(true);
            }
        });
    }

    private void refreshButtonByMode() {
        btnPumpAction.setEnabled(!aiInFlight);

        if (modeManual.isChecked()) {
            btnPumpAction.setText(pumpOn ? "Matikan pompa" : "Nyalakan pompa");
        } else {
            btnPumpAction.setText(aiInFlight ? "Memproses..." : "Jalankan Sistem Cerdas");
        }
    }

    private void updatePumpUI() {
        if (pumpOn) {
            tvPumpState.setText("Status pompa: Menyala");
            dotState.setBackgroundResource(R.drawable.shape_dot_green);
        } else {
            tvPumpState.setText("Status pompa: Mati");
            dotState.setBackgroundResource(R.drawable.shape_dot_red);
        }
        refreshButtonByMode();
    }

    // ==========================
    // SISTEM CERDAS (FIX)
    // ==========================
    private void runAiAndWriteDecision(boolean force) {
        if (telemetryRef == null || pumpRef == null) return;
        if (aiInFlight) return;

        if (!SmartIrrigationApi.isConfigured()) {
            Toast.makeText(this, "AI URL belum diisi / salah. Cek SmartIrrigationApi.", Toast.LENGTH_LONG).show();
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && (now - lastAiCallMs) < AI_COOLDOWN_MS) return;
        lastAiCallMs = now;

        aiInFlight = true;
        refreshButtonByMode();
        Toast.makeText(this, "Memproses sistem cerdas...", Toast.LENGTH_SHORT).show();

        telemetryRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) {
                    aiInFlight = false;
                    refreshButtonByMode();
                    Toast.makeText(
                            ManualControlActivity.this,
                            "Telemetry kosong di /telemetry/" + deviceId + "/latest",
                            Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                // ===== INPUT (dari Firebase telemetry) =====
                double humidity = getDoubleAny(s, "hum", "humidity", "Humidity");
                double soilMoisture = getDoubleAny(s, "soil", "soil_moisture", "Soil_Moisture");
                double rainPct = getDoubleAny(s, "rain_pct", "rainPct", "rain");

                // clamp nilai supaya aman
                humidity = clamp(humidity, 0, 100);
                soilMoisture = clamp(soilMoisture, 0, 100);
                rainPct = clamp(rainPct, 0, 100);

                // validasi NaN/Infinity
                if (Double.isNaN(humidity) || Double.isInfinite(humidity)
                        || Double.isNaN(soilMoisture) || Double.isInfinite(soilMoisture)
                        || Double.isNaN(rainPct) || Double.isInfinite(rainPct)) {
                    aiInFlight = false;
                    refreshButtonByMode();
                    Toast.makeText(ManualControlActivity.this, "Telemetry tidak valid (NaN/Infinity).", Toast.LENGTH_LONG).show();
                    return;
                }

                // rainfall bin (0/1)
                int rainfallBin = (rainPct >= 50.0) ? 1 : 0;

                // sunlight bin (0/1) - PASTI INT supaya tidak ada error lossy conversion
                int sunlightBin = 0;
                if (s.child("bright").exists()) {
                    sunlightBin = parseBoolLike(s.child("bright").getValue()) ? 1 : 0;
                } else if (s.child("ldr_adc").exists()) {
                    double ldr = getDoubleAny(s, "ldr_adc", "LDR_ADC");
                    sunlightBin = (ldr >= 2000.0) ? 1 : 0;
                } else {
                    // jika tidak ada field cahaya, tetap 0 (default)
                    sunlightBin = 0;
                }

                Log.i(TAG, "CALL AI hum=" + humidity
                        + " soil=" + soilMoisture
                        + " rainPct=" + rainPct
                        + " rainBin=" + rainfallBin
                        + " sunBin=" + sunlightBin);

                // ===== CALL AI =====
                SmartIrrigationApi.predict(
                        humidity,
                        rainfallBin,
                        sunlightBin,
                        soilMoisture,
                        new SmartIrrigationApi.PredictCallback() {
                            @Override
                            public void onSuccess(SmartIrrigationApi.PredictResult result) {
                                aiInFlight = false;
                                refreshButtonByMode();

                                // Ambil probability (0..1)
                                double probOn = result.probabilityOn;

                                // fallback kalau API tidak mengirim probability
                                if (Double.isNaN(probOn) || Double.isInfinite(probOn) || probOn < 0) {
                                    if (result.pumpStatus == 0 || result.pumpStatus == 1) {
                                        probOn = (result.pumpStatus == 1) ? 1.0 : 0.0;
                                    } else {
                                        probOn = 0.5; // netral
                                    }
                                }

                                double percent = probOn * 100.0;

                                // ===== ATURAN YANG KAMU MINTA =====
                                // di bawah 50% => harus disiram (tanah kering)
                                boolean shouldWater = (percent < 50.0);

                                String decisionText = shouldWater
                                        ? "Tanah kering, perlu disiram"
                                        : "Tanah lembab, tidak perlu disiram";

                                // set output yang konsisten untuk disimpan
                                result.pumpLabel = decisionText;
                                result.pumpStatus = shouldWater ? 1 : 0;
                                result.probabilityOn = probOn;

                                pumpOn = shouldWater;
                                updatePumpUI();

                                // simpan ke Firebase
                                saveControlToFirebase("auto", shouldWater, "mobile_ai", result);

                                Toast.makeText(
                                        ManualControlActivity.this,
                                        "AI OK (" + result.usedFormat + ")\n" +
                                                decisionText + " | Prob=" + String.format(Locale.getDefault(), "%.1f%%", percent),
                                        Toast.LENGTH_LONG
                                ).show();
                            }

                            @Override
                            public void onError(Exception e) {
                                aiInFlight = false;
                                refreshButtonByMode();

                                String msg = (e.getMessage() == null) ? "unknown" : e.getMessage();
                                Log.e(TAG, "AI ERROR: " + msg, e);

                                Toast.makeText(
                                        ManualControlActivity.this,
                                        "Gagal panggil AI: " + msg + "\nLihat Logcat tag=AI_CALL",
                                        Toast.LENGTH_LONG
                                ).show();
                            }
                        }
                );
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                aiInFlight = false;
                refreshButtonByMode();
                Toast.makeText(
                        ManualControlActivity.this,
                        "Gagal baca telemetry: " + error.getMessage(),
                        Toast.LENGTH_LONG
                ).show();
            }
        });
    }

    // ==========================
    // Helpers (tetap)
    // ==========================
    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private double getDoubleAny(DataSnapshot s, String... keys) {
        for (String k : keys) {
            Object v = s.child(k).getValue();
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) {
                try { return Double.parseDouble(((String) v).trim()); } catch (Exception ignored) {}
            }
        }
        return 0.0;
    }

    private void saveModeOnly(String mode, String updatedBy) {
        Map<String, Object> data = new HashMap<>();
        data.put("mode", mode);
        data.put("updatedBy", updatedBy);
        data.put("updatedAt", ServerValue.TIMESTAMP);

        pumpRef.updateChildren(data).addOnFailureListener(e ->
                Toast.makeText(this, "Gagal simpan mode: " + safeMsg(e), Toast.LENGTH_LONG).show()
        );
    }

    private void saveControlToFirebase(String mode, boolean status, String updatedBy, SmartIrrigationApi.PredictResult result) {
        Map<String, Object> data = new HashMap<>();
        data.put("mode", mode);
        data.put("status", status);
        data.put("updatedBy", updatedBy);
        data.put("updatedAt", ServerValue.TIMESTAMP);

        if (result != null) {
            data.put("ai_label", result.pumpLabel);
            data.put("ai_prob_on", result.probabilityOn);
            data.put("ai_http", result.httpCode);
            data.put("ai_used_url", result.usedUrl);
            data.put("ai_used_format", result.usedFormat);
            data.put("ai_decidedAt", ServerValue.TIMESTAMP);
        }

        pumpRef.updateChildren(data).addOnFailureListener(e ->
                Toast.makeText(this, "Gagal simpan kontrol: " + safeMsg(e), Toast.LENGTH_LONG).show()
        );
    }

    private void attachPumpListener() {
        pumpListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot s) {
                String mode = s.child("mode").getValue(String.class);
                Object rawStatus = s.child("status").getValue();
                boolean st = parseBoolLike(rawStatus);

                pumpOn = st;
                updatePumpUI();

                if (!TextUtils.isEmpty(mode)) {
                    suppressModeWrite = true;
                    if ("manual".equalsIgnoreCase(mode)) modeManual.setChecked(true);
                    else modeAuto.setChecked(true);
                    suppressModeWrite = false;

                    refreshModeUI();
                    refreshButtonByMode();
                }
            }

            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };

        pumpRef.addValueEventListener(pumpListener);
    }

    private boolean parseBoolLike(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.getDefault());
            return s.equals("true") || s.equals("1") || s.equals("on") || s.equals("yes");
        }
        return false;
    }

    private String safeMsg(Exception e) {
        return (e.getMessage() == null) ? "unknown" : e.getMessage();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pumpRef != null && pumpListener != null) pumpRef.removeEventListener(pumpListener);
    }
}
