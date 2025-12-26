package com.example.hydro_guard;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AdminDashboardActivity extends AppCompatActivity {

    private static final String TAG = "AdminDashboardActivity";

    private static final String TEMPAT_NODE = "tempat";
    private static final String USERS_NODE  = "users";
    private static final String DEFAULT_DEVICE_ID = "HG-01";

    // UI (Kelola Operator)
    private TextView tvAdminEmail;
    private EditText etSearchOperator;
    private Spinner spinnerRole, spinnerStatus;
    private TableLayout tableOperators;
    private Button btnTambahOperator;

    // UI (Kelola Tempat)
    private Spinner spinnerKecamatan, spinnerKelurahan;
    private LinearLayout listTempatContainer;
    private TextView tvTempatEmpty;

    // Adapters
    private android.widget.ArrayAdapter<String> roleAdapter;
    private android.widget.ArrayAdapter<String> statusAdapter;
    private android.widget.ArrayAdapter<String> kecamatanAdapter;
    private android.widget.ArrayAdapter<String> kelurahanAdapter;

    // Data
    private final List<UserItem> allUsers = new ArrayList<>();
    private final List<TempatItem> allTempat = new ArrayList<>();
    private final List<String> kecamatanOptions = new ArrayList<>();
    private final List<String> kelurahanOptions = new ArrayList<>();

    // Firebase
    private FirebaseDatabase db;
    private DatabaseReference usersRef;
    private DatabaseReference tempatRef;
    private ValueEventListener usersRealtimeListener;
    private ValueEventListener tempatRealtimeListener;

    // Simpan listener realtime per card tempat
    private static class LiveListener {
        final DatabaseReference ref;
        final ValueEventListener listener;
        LiveListener(DatabaseReference ref, ValueEventListener listener) {
            this.ref = ref; this.listener = listener;
        }
    }
    private final List<LiveListener> liveListeners = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseApp.initializeApp(this);
        setContentView(R.layout.activity_hydroguard_dashboard);

        // ✅ Konsisten RTDB
        db = HelperClass.db();

        bindViews();
        setupToolbar();
        setupTopBarActions();

        setupFiltersKelolaOperator();
        setupTambahOperator();
        setupFiltersKelolaTempat();

        usersRef  = db.getReference(USERS_NODE);
        tempatRef = db.getReference(TEMPAT_NODE);

        // ================= USERS LISTENER =================
        usersRealtimeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                allUsers.clear();

                for (DataSnapshot child : snapshot.getChildren()) {
                    String uid = child.getKey();

                    String name = child.child("name").getValue(String.class);
                    String email = child.child("email").getValue(String.class);
                    String role = child.child("role").getValue(String.class);

                    String wa = child.child("no_wa").getValue(String.class);
                    if (TextUtils.isEmpty(wa)) wa = child.child("hp").getValue(String.class);
                    if (TextUtils.isEmpty(wa)) wa = child.child("phone").getValue(String.class);
                    if (TextUtils.isEmpty(wa)) wa = child.child("wa").getValue(String.class);

                    Boolean approved = child.child("approved").getValue(Boolean.class);
                    if (approved == null) {
                        String a = child.child("approved").getValue(String.class);
                        approved = "true".equalsIgnoreCase(a);
                    }

                    if (TextUtils.isEmpty(role)) continue;
                    if (TextUtils.isEmpty(name)) name = "(tanpa nama)";
                    if (TextUtils.isEmpty(wa)) wa = "-";
                    if (email == null) email = "";

                    allUsers.add(new UserItem(uid, name, email, role, wa, approved != null && approved));
                }

                refreshAdminEmail();
                applyFiltersAndRenderOperators();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(AdminDashboardActivity.this,
                        "Gagal memuat data users: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        };

        // ================= TEMPAT LISTENER =================
        tempatRealtimeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                allTempat.clear();

                for (DataSnapshot child : snapshot.getChildren()) {
                    String tempatId = child.getKey();

                    String nama = child.child("nama").getValue(String.class);
                    if (TextUtils.isEmpty(nama)) nama = child.child("name").getValue(String.class);

                    String kecamatan = child.child("kecamatan").getValue(String.class);
                    String kelurahan = child.child("kelurahan").getValue(String.class);

                    String createdBy = child.child("createdBy").getValue(String.class);
                    if (TextUtils.isEmpty(createdBy)) createdBy = child.child("created_by").getValue(String.class);

                    String operatorId = child.child("operatorId").getValue(String.class);
                    if (TextUtils.isEmpty(operatorId)) operatorId = child.child("operator_id").getValue(String.class);

                    String operatorName = child.child("operatorName").getValue(String.class);
                    if (TextUtils.isEmpty(operatorName)) operatorName = child.child("operator_name").getValue(String.class);

                    String deviceId = child.child("deviceId").getValue(String.class);
                    if (TextUtils.isEmpty(deviceId)) deviceId = child.child("device_id").getValue(String.class);
                    if (TextUtils.isEmpty(deviceId)) deviceId = looksLikeDeviceId(tempatId) ? tempatId : DEFAULT_DEVICE_ID;

                    if (TextUtils.isEmpty(nama)) nama = "(Tanpa nama tempat)";
                    if (TextUtils.isEmpty(kecamatan)) kecamatan = "-";
                    if (TextUtils.isEmpty(kelurahan)) kelurahan = "-";

                    allTempat.add(new TempatItem(
                            tempatId, nama, kecamatan, kelurahan,
                            createdBy, operatorId, operatorName, deviceId
                    ));
                }

                if (allTempat.isEmpty()) {
                    allTempat.add(new TempatItem(
                            DEFAULT_DEVICE_ID,
                            "HG-01 (Default)",
                            "-", "-",
                            "", "", "",
                            DEFAULT_DEVICE_ID
                    ));
                }

                rebuildKecamatanKelurahanOptions();
                applyFiltersAndRenderTempat();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(AdminDashboardActivity.this,
                        "Gagal memuat tempat: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        };
    }

    private boolean looksLikeDeviceId(String s) {
        if (TextUtils.isEmpty(s)) return false;
        return s.startsWith("HG-") || s.startsWith("ESP-") || s.startsWith("DEV-");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (usersRef != null && usersRealtimeListener != null) usersRef.addValueEventListener(usersRealtimeListener);
        if (tempatRef != null && tempatRealtimeListener != null) tempatRef.addValueEventListener(tempatRealtimeListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (usersRef != null && usersRealtimeListener != null) usersRef.removeEventListener(usersRealtimeListener);
        if (tempatRef != null && tempatRealtimeListener != null) tempatRef.removeEventListener(tempatRealtimeListener);
        clearLiveListeners();
    }

    private void clearLiveListeners() {
        for (LiveListener l : liveListeners) {
            try { l.ref.removeEventListener(l.listener); } catch (Exception ignored) {}
        }
        liveListeners.clear();
    }

    private void bindViews() {
        tvAdminEmail = findViewById(R.id.tvAdminEmail);
        etSearchOperator = findViewById(R.id.etSearchOperator);
        spinnerRole = findViewById(R.id.spinnerRole);
        spinnerStatus = findViewById(R.id.spinnerStatus);
        tableOperators = findViewById(R.id.tableOperators);
        btnTambahOperator = findViewById(R.id.btnTambahOperator);

        spinnerKecamatan = findViewById(R.id.spinnerKecamatan);
        spinnerKelurahan = findViewById(R.id.spinnerKelurahan);
        listTempatContainer = findViewById(R.id.listTempatContainer);
        tvTempatEmpty = findViewById(R.id.tvTempatEmpty);
    }

    private void setupToolbar() {
        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        if (topAppBar != null) {
            setSupportActionBar(topAppBar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(getString(R.string.title_dashboard));
            }
            topAppBar.setNavigationOnClickListener(v -> onBackPressed());
        } else {
            Log.e(TAG, "topAppBar null. Cek id topAppBar di XML.");
        }
    }

    private void setupTopBarActions() {
        ImageButton btnNotifications = findViewById(R.id.btnNotifications);
        ImageButton btnSettings = findViewById(R.id.btnSettings);

        if (btnNotifications != null) {
            btnNotifications.setOnClickListener(v ->
                    startActivity(new Intent(this, NotifikasiActivity.class))
            );
        }
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v ->
                    startActivity(new Intent(this, SettingActivity.class))
            );
        }
    }

    // ===================== KELOLA OPERATOR =====================
    private void setupFiltersKelolaOperator() {
        List<String> roles = new ArrayList<>();
        roles.add("Semua");
        roles.add("admin");
        roles.add("operator");

        roleAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, roles);
        roleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerRole != null) spinnerRole.setAdapter(roleAdapter);

        List<String> statuses = new ArrayList<>();
        statuses.add("Semua");
        statuses.add("Aktif");
        statuses.add("Menunggu");

        statusAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, statuses);
        statusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerStatus != null) spinnerStatus.setAdapter(statusAdapter);

        if (spinnerRole != null) spinnerRole.setOnItemSelectedListener(new SimpleItemSelectedListener(this::applyFiltersAndRenderOperators));
        if (spinnerStatus != null) spinnerStatus.setOnItemSelectedListener(new SimpleItemSelectedListener(this::applyFiltersAndRenderOperators));

        if (etSearchOperator != null) {
            etSearchOperator.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) { applyFiltersAndRenderOperators(); }
            });
        }
    }

    private void setupTambahOperator() {
        if (btnTambahOperator != null) {
            btnTambahOperator.setOnClickListener(v ->
                    startActivity(new Intent(AdminDashboardActivity.this, DaftarOperatorActivity.class))
            );
        }
    }

    private void refreshAdminEmail() {
        if (tvAdminEmail == null) return;

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null && u.getEmail() != null) tvAdminEmail.setText(u.getEmail());
        else tvAdminEmail.setText("admin@hydroguard.local");
    }

    private void applyFiltersAndRenderOperators() {
        if (tableOperators == null) return;

        String q = etSearchOperator != null ? etSearchOperator.getText().toString().trim() : "";
        q = q.toLowerCase(Locale.getDefault());

        String roleFilter = spinnerRole != null ? String.valueOf(spinnerRole.getSelectedItem()) : "Semua";
        String statusFilter = spinnerStatus != null ? String.valueOf(spinnerStatus.getSelectedItem()) : "Semua";

        List<UserItem> filtered = new ArrayList<>();
        for (UserItem u : allUsers) {
            if (!"Semua".equalsIgnoreCase(roleFilter) && !u.role.equalsIgnoreCase(roleFilter)) continue;

            if (!"Semua".equalsIgnoreCase(statusFilter)) {
                boolean isAktif = "admin".equalsIgnoreCase(u.role) || u.approved;
                if ("Aktif".equalsIgnoreCase(statusFilter) && !isAktif) continue;
                if ("Menunggu".equalsIgnoreCase(statusFilter) && isAktif) continue;
            }

            if (!TextUtils.isEmpty(q)) {
                String hay = (u.name + " " + u.email + " " + u.wa).toLowerCase(Locale.getDefault());
                if (!hay.contains(q)) continue;
            }

            filtered.add(u);
        }

        renderOperatorTable(filtered);
    }

    private void approveOperator(String uid) {
        if (TextUtils.isEmpty(uid)) return;

        Map<String, Object> upd = new HashMap<>();
        upd.put("approved", true);
        upd.put("status", "active");
        upd.put("approvedAt", System.currentTimeMillis());

        usersRef.child(uid).updateChildren(upd)
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, "Operator disetujui.", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Gagal menyetujui: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void renderOperatorTable(List<UserItem> items) {
        int childCount = tableOperators.getChildCount();
        if (childCount > 1) tableOperators.removeViews(1, childCount - 1);

        for (UserItem u : items) {
            TableRow row = new TableRow(this);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView tvName = cell(u.name, 2f, false);
            TextView tvRole = cell(u.role, 1f, false);
            TextView tvWa   = cell(u.wa, 2f, false);

            boolean isAktif = "admin".equalsIgnoreCase(u.role) || u.approved;
            TextView tvStatus = cell(isAktif ? "Aktif" : "Menunggu", 1f, true);
            tvStatus.setTextColor(isAktif ? Color.parseColor("#059669") : Color.parseColor("#B45309"));

            row.addView(tvName);
            row.addView(tvRole);
            row.addView(tvWa);
            row.addView(tvStatus);

            if ("operator".equalsIgnoreCase(u.role) && !u.approved) {
                MaterialButton btnAcc = new MaterialButton(this);
                btnAcc.setText("Setujui");
                btnAcc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                btnAcc.setInsetTop(0);
                btnAcc.setInsetBottom(0);
                btnAcc.setOnClickListener(v -> approveOperator(u.uid));

                TableRow.LayoutParams lpBtn = new TableRow.LayoutParams(
                        TableRow.LayoutParams.WRAP_CONTENT,
                        TableRow.LayoutParams.WRAP_CONTENT
                );
                lpBtn.leftMargin = dp(8);
                btnAcc.setLayoutParams(lpBtn);
                row.addView(btnAcc);
            }

            tableOperators.addView(row);
        }

        if (items.isEmpty()) {
            TableRow row = new TableRow(this);
            row.setPadding(0, dp(10), 0, dp(10));

            TextView empty = new TextView(this);
            empty.setText("Tidak ada data yang sesuai filter.");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setTextColor(Color.parseColor("#6B7280"));

            TableRow.LayoutParams lp = new TableRow.LayoutParams(
                    TableRow.LayoutParams.MATCH_PARENT,
                    TableRow.LayoutParams.WRAP_CONTENT
            );
            lp.span = 5;
            empty.setLayoutParams(lp);

            row.addView(empty);
            tableOperators.addView(row);
        }
    }

    // ===================== KELOLA TEMPAT =====================
    private void setupFiltersKelolaTempat() {
        kecamatanOptions.clear();
        kelurahanOptions.clear();
        kecamatanOptions.add("Semua");
        kelurahanOptions.add("Semua");

        kecamatanAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, kecamatanOptions);
        kecamatanAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerKecamatan != null) spinnerKecamatan.setAdapter(kecamatanAdapter);

        kelurahanAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, kelurahanOptions);
        kelurahanAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerKelurahan != null) spinnerKelurahan.setAdapter(kelurahanAdapter);

        if (spinnerKecamatan != null) spinnerKecamatan.setOnItemSelectedListener(new SimpleItemSelectedListener(this::applyFiltersAndRenderTempat));
        if (spinnerKelurahan != null) spinnerKelurahan.setOnItemSelectedListener(new SimpleItemSelectedListener(this::applyFiltersAndRenderTempat));
    }

    private void rebuildKecamatanKelurahanOptions() {
        String keepKec = spinnerKecamatan != null && spinnerKecamatan.getSelectedItem() != null
                ? String.valueOf(spinnerKecamatan.getSelectedItem()) : "Semua";
        String keepKel = spinnerKelurahan != null && spinnerKelurahan.getSelectedItem() != null
                ? String.valueOf(spinnerKelurahan.getSelectedItem()) : "Semua";

        Set<String> kecSet = new LinkedHashSet<>();
        Set<String> kelSet = new LinkedHashSet<>();
        kecSet.add("Semua");
        kelSet.add("Semua");

        for (TempatItem t : allTempat) {
            if (!TextUtils.isEmpty(t.kecamatan) && !"-".equals(t.kecamatan)) kecSet.add(t.kecamatan);
            if (!TextUtils.isEmpty(t.kelurahan) && !"-".equals(t.kelurahan)) kelSet.add(t.kelurahan);
        }

        kecamatanOptions.clear();
        kecamatanOptions.addAll(kecSet);

        kelurahanOptions.clear();
        kelurahanOptions.addAll(kelSet);

        if (kecamatanAdapter != null) kecamatanAdapter.notifyDataSetChanged();
        if (kelurahanAdapter != null) kelurahanAdapter.notifyDataSetChanged();

        int idxKec = kecamatanOptions.indexOf(keepKec);
        if (idxKec >= 0 && spinnerKecamatan != null) spinnerKecamatan.setSelection(idxKec, false);

        int idxKel = kelurahanOptions.indexOf(keepKel);
        if (idxKel >= 0 && spinnerKelurahan != null) spinnerKelurahan.setSelection(idxKel, false);
    }

    private void applyFiltersAndRenderTempat() {
        if (listTempatContainer == null || tvTempatEmpty == null) return;

        String kecFilter = spinnerKecamatan != null && spinnerKecamatan.getSelectedItem() != null
                ? String.valueOf(spinnerKecamatan.getSelectedItem()) : "Semua";
        String kelFilter = spinnerKelurahan != null && spinnerKelurahan.getSelectedItem() != null
                ? String.valueOf(spinnerKelurahan.getSelectedItem()) : "Semua";

        List<TempatItem> filtered = new ArrayList<>();
        for (TempatItem t : allTempat) {
            if (!"Semua".equalsIgnoreCase(kecFilter) && !kecFilter.equalsIgnoreCase(t.kecamatan)) continue;
            if (!"Semua".equalsIgnoreCase(kelFilter) && !kelFilter.equalsIgnoreCase(t.kelurahan)) continue;
            filtered.add(t);
        }

        renderTempatList(filtered);
    }

    private void renderTempatList(List<TempatItem> items) {
        listTempatContainer.removeAllViews();
        clearLiveListeners();

        if (items.isEmpty()) {
            tvTempatEmpty.setVisibility(View.VISIBLE);
            tvTempatEmpty.setText("Belum ada tempat");
            return;
        }
        tvTempatEmpty.setVisibility(View.GONE);

        for (TempatItem t : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundResource(R.drawable.bg_item_tempat);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(8);
            card.setLayoutParams(lp);

            TextView tvNama = new TextView(this);
            tvNama.setText(t.nama);
            tvNama.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvNama.setTypeface(Typeface.DEFAULT_BOLD);
            tvNama.setTextColor(Color.parseColor("#111827"));

            TextView tvLok = new TextView(this);
            tvLok.setText("Lokasi: " + t.kecamatan + " - " + t.kelurahan);
            tvLok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvLok.setTextColor(Color.parseColor("#6B7280"));

            TextView tvBy = new TextView(this);
            tvBy.setText("Diinput oleh: " + resolveUserLabel(t.createdBy));
            tvBy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvBy.setTextColor(Color.parseColor("#111827"));

            TextView tvOp = new TextView(this);
            String opLabel = !TextUtils.isEmpty(t.operatorName) ? t.operatorName : "-";
            tvOp.setText("Operator: " + opLabel);
            tvOp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvOp.setTextColor(Color.parseColor("#111827"));

            TextView tvSensor = new TextView(this);
            tvSensor.setText("Sensor: memuat...");
            tvSensor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvSensor.setTextColor(Color.parseColor("#111827"));
            tvSensor.setPadding(0, dp(6), 0, 0);

            TextView tvPump = new TextView(this);
            tvPump.setText("Pompa: memuat...");
            tvPump.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvPump.setTextColor(Color.parseColor("#111827"));

            card.addView(tvNama);
            card.addView(tvLok);
            card.addView(tvBy);
            card.addView(tvOp);
            card.addView(tvSensor);
            card.addView(tvPump);

            listTempatContainer.addView(card);

            // ✅ PENTING: pakai deviceId & parser aman (ini yang mencegah admin keluar)
            attachLiveStatusToCard(t.deviceId, tvSensor, tvPump);
        }
    }

    private void attachLiveStatusToCard(String deviceId, TextView tvSensor, TextView tvPump) {
        if (TextUtils.isEmpty(deviceId)) deviceId = DEFAULT_DEVICE_ID;

        DatabaseReference tRef = db.getReference("telemetry").child(deviceId).child("latest");
        ValueEventListener tl = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) {
                    tvSensor.setText("Sensor: (telemetry kosong)");
                    return;
                }

                // ✅ FIX UTAMA: gunakan getDoubleSafe, bukan Number.class
                double tempVal = getDoubleSafe(s, "temp", Double.NaN);
                double humVal  = getDoubleSafe(s, "hum", Double.NaN);
                double soilVal = getDoubleSafe(s, "soil", Double.NaN);
                double rainVal = getDoubleSafe(s, "rain_pct", Double.NaN);
                double ldrVal  = getDoubleSafe(s, "ldr_adc", Double.NaN);

                String light = "-";
                if (s.child("bright").exists()) {
                    boolean bright = parseBoolLike(s.child("bright").getValue());
                    light = bright ? "Terang" : "Gelap";
                } else if (!Double.isNaN(ldrVal)) {
                    light = ((int) Math.round(ldrVal)) + " (adc)";
                }

                String line =
                        "Sensor: T=" + (Double.isNaN(tempVal) ? "-" : String.format(Locale.getDefault(),"%.1f°C", tempVal))
                                + " | H=" + (Double.isNaN(humVal) ? "-" : String.format(Locale.getDefault(),"%.1f%%", humVal))
                                + " | Soil=" + (Double.isNaN(soilVal) ? "-" : ((int)Math.round(soilVal))+"%")
                                + " | Rain=" + (Double.isNaN(rainVal) ? "-" : ((int)Math.round(rainVal))+"%")
                                + " | Light=" + light;

                tvSensor.setText(line);
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                tvSensor.setText("Sensor: error " + error.getMessage());
            }
        };
        tRef.addValueEventListener(tl);
        liveListeners.add(new LiveListener(tRef, tl));

        DatabaseReference pRef = db.getReference("kontrol_pompa").child(deviceId);
        ValueEventListener pl = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String mode = s.child("mode").getValue(String.class);
                Object raw = s.child("status").getValue();
                boolean isOn = parseBoolLike(raw);

                String m = TextUtils.isEmpty(mode) ? "-" : (mode.equalsIgnoreCase("manual") ? "Manual" : "Otomatis");
                tvPump.setText("Pompa: " + (isOn ? "Menyala" : "Mati") + " | Mode: " + m);
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                tvPump.setText("Pompa: error " + error.getMessage());
            }
        };
        pRef.addValueEventListener(pl);
        liveListeners.add(new LiveListener(pRef, pl));
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

    // ✅ helper aman untuk angka (FIX crash)
    private double getDoubleSafe(@NonNull DataSnapshot parent, @NonNull String key, double def) {
        Object v = parent.child(key).getValue();
        if (v == null) return def;

        if (v instanceof Double) return (Double) v;
        if (v instanceof Long) return ((Long) v).doubleValue();
        if (v instanceof Integer) return ((Integer) v).doubleValue();
        if (v instanceof Float) return ((Float) v).doubleValue();
        if (v instanceof Number) return ((Number) v).doubleValue();

        if (v instanceof String) {
            try {
                String s = ((String) v).trim();
                if (s.isEmpty()) return def;
                return Double.parseDouble(s);
            } catch (Exception ignored) {
                return def;
            }
        }
        return def;
    }

    private String resolveUserLabel(String uid) {
        if (TextUtils.isEmpty(uid)) return "-";
        for (UserItem u : allUsers) {
            if (uid.equals(u.uid)) {
                if (!TextUtils.isEmpty(u.name)) return u.name;
                if (!TextUtils.isEmpty(u.email)) return u.email;
                break;
            }
        }
        return uid;
    }

    private TextView cell(String text, float weight, boolean alignEnd) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.parseColor("#111827"));
        tv.setPadding(dp(2), 0, dp(2), 0);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);

        TableRow.LayoutParams lp = new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight);
        tv.setLayoutParams(lp);

        if (alignEnd) tv.setGravity(Gravity.END);
        return tv;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ===================== MODEL =====================
    static class UserItem {
        final String uid, name, email, role, wa;
        final boolean approved;

        UserItem(String uid, String name, String email, String role, String wa, boolean approved) {
            this.uid = uid; this.name = name; this.email = email; this.role = role; this.wa = wa; this.approved = approved;
        }
    }

    static class TempatItem {
        final String tempatId, nama, kecamatan, kelurahan, createdBy, operatorId, operatorName, deviceId;

        TempatItem(String tempatId, String nama, String kecamatan, String kelurahan,
                   String createdBy, String operatorId, String operatorName, String deviceId) {
            this.tempatId = tempatId;
            this.nama = nama;
            this.kecamatan = kecamatan;
            this.kelurahan = kelurahan;
            this.createdBy = createdBy;
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.deviceId = deviceId;
        }
    }

    static class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable onChange;
        SimpleItemSelectedListener(Runnable onChange) { this.onChange = onChange; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            if (onChange != null) onChange.run();
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}
