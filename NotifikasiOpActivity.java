package com.example.hydro_guard;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class NotifikasiOpActivity extends AppCompatActivity {

    private ImageView btnBack;
    private LinearLayout containerToday;
    private LinearLayout containerWeek;

    private Query notifQuery;
    private ChildEventListener childListener;

    // Cache in-memory agar tidak duplikat & mudah update UI
    private final Map<String, NotifItem> all = new HashMap<>();

    private final SimpleDateFormat sdf =
            new SimpleDateFormat("dd MMM yyyy, HH:mm", new Locale("id", "ID"));

    // Debounce render biar tidak berat ketika banyak childAdded saat load history
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean renderQueued = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifikasi_op);

        initViews();
        setupActions();

        // Sesuaikan timezone tampilan
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Makassar"));

        attachRealtimeListenerLast7Days();
    }

    private void initViews() {
        btnBack        = findViewById(R.id.btnBack);
        containerToday = findViewById(R.id.containerToday);
        containerWeek  = findViewById(R.id.containerWeek);
    }

    private void setupActions() {
        btnBack.setOnClickListener(v -> finish());
    }

    /**
     * Ambil history 7 hari terakhir + realtime update.
     * Pakai startAt(startWeek) agar history 7 hari stabil (tidak "terdorong" seperti limitToLast).
     */
    private void attachRealtimeListenerLast7Days() {
        long now = System.currentTimeMillis();
        long startToday = startOfDayMillis(now);
        long startWeek  = startToday - (6L * 24 * 60 * 60 * 1000); // 7 hari termasuk hari ini

        // IMPORTANT:
        // Untuk notifikasi operator, gunakan scopeId = "operator"
        // Pastikan saat menulis notifikasi operator juga pakai scope ini.
        String scopeId = "operator";

        DatabaseReference notifRef = FirebaseDatabase.getInstance()
                .getReference("notifications")
                .child(scopeId);

        notifRef.keepSynced(true);

        notifQuery = notifRef.orderByChild("ts").startAt(startWeek);

        childListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                NotifItem item = snapshot.getValue(NotifItem.class);
                if (item == null) return;

                item.id = snapshot.getKey();
                if (item.id == null) return;

                all.put(item.id, item);
                queueRender();
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {
                NotifItem item = snapshot.getValue(NotifItem.class);
                if (item == null) return;

                item.id = snapshot.getKey();
                if (item.id == null) return;

                all.put(item.id, item);
                queueRender();
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                String id = snapshot.getKey();
                if (id != null) {
                    all.remove(id);
                    queueRender();
                }
            }

            @Override public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {}
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };

        notifQuery.addChildEventListener(childListener);
    }

    private void queueRender() {
        if (renderQueued) return;
        renderQueued = true;
        uiHandler.postDelayed(() -> {
            renderQueued = false;
            render();
        }, 120);
    }

    private void render() {
        containerToday.removeAllViews();
        containerWeek.removeAllViews();

        long now = System.currentTimeMillis();
        long startToday = startOfDayMillis(now);
        long startWeek  = startToday - (6L * 24 * 60 * 60 * 1000);

        List<NotifItem> todayList = new ArrayList<>();
        List<NotifItem> weekList  = new ArrayList<>();

        for (NotifItem n : all.values()) {
            if (n == null || n.ts <= 0) continue;

            if (n.ts >= startToday) {
                todayList.add(n);
            } else if (n.ts >= startWeek) {
                weekList.add(n);
            }
        }

        // Sort terbaru di atas
        Comparator<NotifItem> desc = (a, b) -> Long.compare(b.ts, a.ts);
        Collections.sort(todayList, desc);
        Collections.sort(weekList, desc);

        if (todayList.isEmpty()) {
            addEmptyLabel(containerToday, "Belum ada notifikasi hari ini.");
        } else {
            for (NotifItem item : todayList) addNotificationView(containerToday, item);
        }

        if (weekList.isEmpty()) {
            addEmptyLabel(containerWeek, "Belum ada notifikasi minggu ini.");
        } else {
            for (NotifItem item : weekList) addNotificationView(containerWeek, item);
        }
    }

    private long startOfDayMillis(long timeMillis) {
        long offset = sdf.getTimeZone().getOffset(timeMillis);
        long local  = timeMillis + offset;
        long day    = local / (24L * 60 * 60 * 1000);
        long startLocal = day * (24L * 60 * 60 * 1000);
        return startLocal - offset;
    }

    private void addNotificationView(LinearLayout parent, NotifItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, 10);
        row.setLayoutParams(lp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(item.message != null ? item.message : "-");
        tvTitle.setTextSize(13);
        tvTitle.setTextColor(0xFF222222);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);

        String meta = sdf.format(item.ts);

        // type: IN / ACC / REJECT (biar jelas “masuk” vs “acc”)
        if (item.type != null && !item.type.isEmpty()) meta += " • " + item.type;

        // level: INFO/WARN/ERROR
        if (item.level != null && !item.level.isEmpty()) meta += " • " + item.level;

        TextView tvTime = new TextView(this);
        tvTime.setText(meta);
        tvTime.setTextSize(11);
        tvTime.setTextColor(0xFF777777);

        row.addView(tvTitle);
        row.addView(tvTime);
        parent.addView(row);
    }

    private void addEmptyLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF777777);
        parent.addView(tv);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (notifQuery != null && childListener != null) {
            notifQuery.removeEventListener(childListener);
        }
        uiHandler.removeCallbacksAndMessages(null);
    }

    // Model notif (wajib public + constructor kosong untuk Firebase)
    public static class NotifItem {
        public String id;
        public String message;
        public String level;   // INFO/WARN/ERROR (opsional)
        public String type;    // IN / ACC / REJECT
        public String refId;   // id request terkait (opsional)
        public long ts;        // epoch millis

        public NotifItem() {}
    }
}
