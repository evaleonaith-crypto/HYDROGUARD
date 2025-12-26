package com.example.hydro_guard;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    // WAJIB: pakai ID BARU agar importance HIGH berlaku (channel lama tidak bisa dinaikkan)
    private static final String CH_ID = "hydro_alerts_v2";
    private static final String OLD_CH_ID = "hydro_alerts";
    private static final String CH_NAME = "Hydro Guard Alerts";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannelIfNeeded();
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage msg) {
        createChannelIfNeeded();

        String title = "Hydro Guard";
        String body  = "";

        // notification payload
        if (msg.getNotification() != null) {
            if (!TextUtils.isEmpty(msg.getNotification().getTitle())) {
                title = msg.getNotification().getTitle();
            }
            if (!TextUtils.isEmpty(msg.getNotification().getBody())) {
                body = msg.getNotification().getBody();
            }
        }

        // data payload fallback
        Map<String, String> data = msg.getData();
        if (TextUtils.isEmpty(body) && data != null && !data.isEmpty()) {
            String t = data.get("title");
            String b = data.get("body");
            if (!TextUtils.isEmpty(t)) title = t;
            if (!TextUtils.isEmpty(b)) body  = b;
        }

        if (TextUtils.isEmpty(body)) body = "Ada pembaruan dari perangkat.";

        showNotif(title, body);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        android.util.Log.d("FCM", "New token: " + token);
        // simpan token bila diperlukan
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            // OPSIONAL tapi sangat membantu: hapus channel lama agar tidak nyangkut
            // (boleh Anda hapus kalau tidak ingin override setting lama)
            try { nm.deleteNotificationChannel(OLD_CH_ID); } catch (Exception ignored) {}

            NotificationChannel ch = new NotificationChannel(
                    CH_ID,
                    CH_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Notifikasi peringatan dan informasi sistem Hydro Guard");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 250, 150, 250});
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);

            nm.createNotificationChannel(ch);
        }
    }

    private void showNotif(String title, String body) {
        // Android 13+: wajib izin runtime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        if (!nm.areNotificationsEnabled()) return;

        // Klik notif -> buka halaman (sesuaikan kalau mau langsung ke NotifikasiOpActivity)
        Intent openIntent = new Intent(this, PeranActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent contentPI = PendingIntent.getActivity(
                this,
                100,
                openIntent,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Fullscreen intent (membantu heads-up pada sebagian device)
        PendingIntent fullScreenPI = PendingIntent.getActivity(
                this,
                101,
                openIntent,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_notif) // pastikan ADA & valid
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)     // heads-up
                .setCategory(NotificationCompat.CATEGORY_ALARM)    // membantu pop-up di beberapa device
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(contentPI)
                .setFullScreenIntent(fullScreenPI, true);

        nm.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), b.build());
    }
}
