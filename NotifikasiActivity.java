package com.example.hydro_guard;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NotifikasiActivity extends AppCompatActivity {

    private ImageView btnBack;
    private TextView tvInfo, tvEmpty, tvLabelToday, tvLabelWeek;
    private LinearLayout containerToday, containerWeek;

    private DatabaseReference usersRef;
    private ValueEventListener realtimeListener;

    // DULU: pendingRequests -> sekarang simpan semua (pending + approved + rejected)
    private final List<OperatorRequest> allRequests = new ArrayList<>();

    private final SimpleDateFormat fmtTime = new SimpleDateFormat("HH:mm", new Locale("id", "ID"));
    private final SimpleDateFormat fmtDate = new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifikasi);

        FirebaseApp.initializeApp(this);

        initViews();
        initFirebase();
        setupActions();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);

        tvInfo = findViewById(R.id.tvInfo);
        tvEmpty = findViewById(R.id.tvEmpty);

        tvLabelToday = findViewById(R.id.tvLabelToday);
        tvLabelWeek  = findViewById(R.id.tvLabelWeek);

        containerToday = findViewById(R.id.containerToday);
        containerWeek  = findViewById(R.id.containerWeek);
    }

    private void initFirebase() {
        usersRef = FirebaseDatabase.getInstance().getReference("users");
    }

    private void setupActions() {
        btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();
        attachRealtime();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (usersRef != null && realtimeListener != null) {
            usersRef.removeEventListener(realtimeListener);
        }
    }

    // ================== REALTIME LOAD + SORT TERBARU ==================
    private void attachRealtime() {
        tvInfo.setText("Memuat notifikasi operator...");

        realtimeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                allRequests.clear();

                for (DataSnapshot child : snapshot.getChildren()) {
                    String uid = child.getKey();

                    String role = child.child("role").getValue(String.class);
                    if (role == null || !role.equalsIgnoreCase("operator")) continue;

                    String nama  = child.child("name").getValue(String.class);
                    String email = child.child("email").getValue(String.class);

                    Boolean approved = child.child("approved").getValue(Boolean.class);
                    if (approved == null) {
                        String a = child.child("approved").getValue(String.class);
                        if (!TextUtils.isEmpty(a)) approved = "true".equalsIgnoreCase(a);
                    }

                    String status = child.child("status").getValue(String.class);
                    if (status == null) status = "";

                    long createdAt  = extractTimestamp(child);
                    long approvedAt = extractLongFlexible(child.child("approvedAt").getValue());
                    long rejectedAt = extractLongFlexible(child.child("rejectedAt").getValue());

                    // Tentukan status final agar konsisten
                    String finalStatus = normalizeStatus(status, approved, approvedAt, rejectedAt);

                    // activityAt dipakai untuk urutan & grouping (kalau di-ACC/Reject, pakai waktu handled)
                    long activityAt = max(createdAt, approvedAt, rejectedAt);
                    if (activityAt <= 0) activityAt = System.currentTimeMillis();

                    allRequests.add(new OperatorRequest(uid, nama, email, createdAt, activityAt, finalStatus));
                }

                // SORT: terbaru dulu berdasarkan activityAt (jadi setelah ACC/Reject tetap "naik" dan tidak hilang)
                Collections.sort(allRequests, (a, b) -> Long.compare(b.activityAt, a.activityAt));

                renderAll();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvInfo.setText("Gagal memuat data: " + error.getMessage());
                Toast.makeText(NotifikasiActivity.this, "Gagal memuat notifikasi operator.", Toast.LENGTH_LONG).show();
            }
        };

        usersRef.addValueEventListener(realtimeListener);
    }

    private void renderAll() {
        containerToday.removeAllViews();
        containerWeek.removeAllViews();

        if (allRequests.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvInfo.setText("Tidak ada notifikasi operator.");
            tvLabelToday.setVisibility(View.GONE);
            tvLabelWeek.setVisibility(View.GONE);
            return;
        }

        tvEmpty.setVisibility(View.GONE);

        int pendingCount = 0;
        for (OperatorRequest r : allRequests) {
            if ("pending".equalsIgnoreCase(r.status)) pendingCount++;
        }

        tvInfo.setText("Notifikasi operator (" + allRequests.size() + ") • Pending: " + pendingCount);
        tvLabelToday.setVisibility(View.VISIBLE);
        tvLabelWeek.setVisibility(View.VISIBLE);

        int countToday = 0;
        int countWeek = 0;

        long now = System.currentTimeMillis();
        long sevenDaysMs = 7L * 24 * 60 * 60 * 1000;

        for (OperatorRequest req : allRequests) {
            long ts = req.activityAt > 0 ? req.activityAt : req.createdAt;

            boolean isToday = isSameDay(ts, now);
            boolean isWithinWeek = (ts > 0 && (now - ts) <= sevenDaysMs);

            if (ts <= 0) isWithinWeek = true;

            View item = buildItemView(req);

            if (isToday) {
                containerToday.addView(item);
                countToday++;
            } else if (isWithinWeek) {
                containerWeek.addView(item);
                countWeek++;
            } else {
                // history lama tetap tampil
                containerWeek.addView(item);
                countWeek++;
            }
        }

        if (countToday == 0) containerToday.addView(buildEmptySectionHint("Tidak ada notifikasi hari ini."));
        if (countWeek == 0) containerWeek.addView(buildEmptySectionHint("Tidak ada notifikasi minggu ini."));
    }

    // ================== VIEW ITEM (DINAMIS) ==================
    private View buildItemView(OperatorRequest req) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setBackgroundColor(0xFFF7FAF5);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rlp.bottomMargin = dp(10);
        root.setLayoutParams(rlp);

        LinearLayout rowTop = new LinearLayout(this);
        rowTop.setOrientation(LinearLayout.HORIZONTAL);
        rowTop.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvNama = new TextView(this);
        tvNama.setText(safe(req.nama) + " ingin menjadi operator");
        tvNama.setTextColor(0xFF111111);
        tvNama.setTextSize(13);
        tvNama.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTime = new TextView(this);
        String statusLabel = TextUtils.isEmpty(req.status) ? "" : (" • " + req.status.toUpperCase(Locale.ROOT));
        long ts = req.activityAt > 0 ? req.activityAt : req.createdAt;
        tvTime.setText(formatTimeLabel(ts) + statusLabel);
        tvTime.setTextColor(0xFF555555);
        tvTime.setTextSize(12);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        tlp.leftMargin = dp(8);
        tvTime.setLayoutParams(tlp);

        rowTop.addView(tvNama);
        rowTop.addView(tvTime);

        // Tombol hanya muncul untuk PENDING
        if ("pending".equalsIgnoreCase(req.status)) {
            ImageView ivApprove = new ImageView(this);
            ivApprove.setImageResource(R.drawable.ic_check_24);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(18), dp(18));
            alp.leftMargin = dp(12);
            ivApprove.setLayoutParams(alp);

            ImageView ivReject = new ImageView(this);
            ivReject.setImageResource(R.drawable.ic_close_24);
            LinearLayout.LayoutParams rjp = new LinearLayout.LayoutParams(dp(18), dp(18));
            rjp.leftMargin = dp(12);
            ivReject.setLayoutParams(rjp);

            rowTop.addView(ivApprove);
            rowTop.addView(ivReject);

            ivApprove.setOnClickListener(v -> approve(req));
            ivReject.setOnClickListener(v -> reject(req));
        }

        TextView tvEmail = new TextView(this);
        tvEmail.setText(safe(req.email));
        tvEmail.setTextColor(0xFF555555);
        tvEmail.setTextSize(12);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        elp.topMargin = dp(6);
        tvEmail.setLayoutParams(elp);

        root.addView(rowTop);
        root.addView(tvEmail);

        return root;
    }

    private View buildEmptySectionHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF666666);
        tv.setTextSize(12);
        tv.setPadding(0, dp(2), 0, dp(10));
        return tv;
    }

    // ================== APPROVE / REJECT ==================
    private void approve(OperatorRequest req) {
        if (req == null || TextUtils.isEmpty(req.uid)) return;

        long now = System.currentTimeMillis();

        Map<String, Object> upd = new HashMap<>();
        upd.put("approved", true);
        upd.put("status", "approved");
        upd.put("approvedAt", now);

        usersRef.child(req.uid).updateChildren(upd)
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, "Operator " + safe(req.nama) + " disetujui.", Toast.LENGTH_SHORT).show()
                )
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Gagal menyetujui: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }

    private void reject(OperatorRequest req) {
        if (req == null || TextUtils.isEmpty(req.uid)) return;

        long now = System.currentTimeMillis();

        Map<String, Object> upd = new HashMap<>();
        upd.put("approved", false);
        upd.put("status", "rejected");
        upd.put("rejectedAt", now);

        usersRef.child(req.uid).updateChildren(upd)
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, "Permintaan " + safe(req.nama) + " ditolak.", Toast.LENGTH_SHORT).show()
                )
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Gagal menolak: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }

    // ================== TIMESTAMP HELPERS ==================
    private long extractTimestamp(DataSnapshot child) {
        Long t;

        t = child.child("createdAt").getValue(Long.class);
        if (t != null) return t;

        t = child.child("created_at").getValue(Long.class);
        if (t != null) return t;

        t = child.child("registeredAt").getValue(Long.class);
        if (t != null) return t;

        t = child.child("requestAt").getValue(Long.class);
        if (t != null) return t;

        t = child.child("requestedAt").getValue(Long.class);
        if (t != null) return t;

        String s = child.child("createdAt").getValue(String.class);
        if (!TextUtils.isEmpty(s)) {
            try { return Long.parseLong(s); } catch (Exception ignored) {}
        }
        s = child.child("created_at").getValue(String.class);
        if (!TextUtils.isEmpty(s)) {
            try { return Long.parseLong(s); } catch (Exception ignored) {}
        }

        return 0L;
    }

    private long extractLongFlexible(Object o) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof Double) return ((Double) o).longValue();
        if (o instanceof String) {
            try { return Long.parseLong((String) o); } catch (Exception ignored) {}
        }
        return 0L;
    }

    private String normalizeStatus(String status, Boolean approved, long approvedAt, long rejectedAt) {
        if ("approved".equalsIgnoreCase(status)) return "approved";
        if ("rejected".equalsIgnoreCase(status)) return "rejected";

        if (rejectedAt > 0) return "rejected";
        if (approvedAt > 0) return "approved";

        if (approved != null && approved) return "approved";

        // default kalau belum jelas
        return "pending";
    }

    private long max(long a, long b, long c) {
        return Math.max(a, Math.max(b, c));
    }

    private boolean isSameDay(long a, long b) {
        if (a <= 0 || b <= 0) return false;
        Calendar ca = Calendar.getInstance();
        ca.setTimeInMillis(a);
        Calendar cb = Calendar.getInstance();
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    private String formatTimeLabel(long ts) {
        if (ts <= 0) return "-";
        if (isSameDay(ts, System.currentTimeMillis())) {
            return fmtTime.format(new Date(ts));
        }
        return fmtDate.format(new Date(ts)) + " • " + fmtTime.format(new Date(ts));
    }

    // ================== UTIL ==================
    private String safe(String s) {
        return (s == null || s.trim().isEmpty()) ? "-" : s.trim();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ================== MODEL ==================
    private static class OperatorRequest {
        final String uid;
        final String nama;
        final String email;
        final long createdAt;
        final long activityAt; // waktu terakhir (created/approved/rejected) untuk history & sorting
        final String status;   // pending/approved/rejected

        OperatorRequest(String uid, String nama, String email, long createdAt, long activityAt, String status) {
            this.uid = uid;
            this.nama = nama;
            this.email = email;
            this.createdAt = createdAt;
            this.activityAt = activityAt;
            this.status = status;
        }
    }
}
