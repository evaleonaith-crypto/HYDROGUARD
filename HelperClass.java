package com.example.hydro_guard;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

public class HelperClass {

    // ===== WAJIB SAMAKAN DENGAN ESP32 =====
    public static final String RTDB_URL = "https://hydro-guard-mobile-default-rtdb.firebaseio.com/";

    // Singleton FirebaseDatabase instance (agar tidak bikin instance baru berulang)
    private static FirebaseDatabase instance;

    // Node & role
    private static final String USERS_NODE = "users";
    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_OPERATOR = "operator";

    // Email admin permanen (samakan dengan akun admin Anda di Firebase Auth)
    public static final String ADMIN_EMAIL = "admin@hydroguard.com";

    public interface AuthCallback {
        void onSuccess(@NonNull FirebaseUser user);
        void onError(@NonNull String message);
    }

    private static FirebaseAuth auth() {
        return FirebaseAuth.getInstance();
    }

    // Penting: selalu pakai RTDB_URL agar tidak nyasar database lain
    public static synchronized FirebaseDatabase db() {
        if (instance == null) {
            instance = FirebaseDatabase.getInstance(RTDB_URL);
        }
        return instance;
    }

    private static DatabaseReference usersRef() {
        return db().getReference(USERS_NODE);
    }

    // ===================== ADMIN LOGIN =====================
    public static void loginAdmin(
            @NonNull String email,
            @NonNull String password,
            @NonNull AuthCallback cb
    ) {
        auth().signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        cb.onError(getErrorMessage(task));
                        return;
                    }

                    FirebaseUser user = auth().getCurrentUser();
                    if (user == null) {
                        cb.onError("Login berhasil tetapi user null.");
                        return;
                    }

                    // Pastikan profile admin ada di /users/{uid}
                    ensureAdminProfile(user, cb);
                });
    }

    private static void ensureAdminProfile(@NonNull FirebaseUser user, @NonNull AuthCallback cb) {
        String uid = user.getUid();
        DatabaseReference ref = usersRef().child(uid);

        ref.get().addOnCompleteListener(t -> {
            if (!t.isSuccessful()) {
                cb.onError("Gagal membaca data admin di RTDB. Pastikan RTDB_URL benar & rules mengizinkan.");
                return;
            }

            String userEmail = user.getEmail() == null ? "" : user.getEmail();

            // Validasi admin berdasarkan email permanen
            if (!ADMIN_EMAIL.equalsIgnoreCase(userEmail)) {
                auth().signOut();
                cb.onError("Email ini bukan admin yang diizinkan.");
                return;
            }

            Map<String, Object> up = new HashMap<>();
            up.put("uid", uid);
            up.put("email", userEmail);

            String existingName = t.getResult().child("name").getValue(String.class);
            up.put("name", existingName != null ? existingName : "Admin");

            up.put("role", ROLE_ADMIN);
            up.put("approved", true);
            up.put("status", "active");
            up.put("lastLoginAt", ServerValue.TIMESTAMP);

            if (!t.getResult().child("createdAt").exists()) {
                up.put("createdAt", ServerValue.TIMESTAMP);
            }

            ref.updateChildren(up)
                    .addOnSuccessListener(unused -> cb.onSuccess(user))
                    .addOnFailureListener(e -> cb.onError("Gagal menyimpan data admin: " + safeMsg(e)));
        });
    }

    // ===================== OPERATOR REGISTER =====================
    public static void registerOperator(
            @NonNull String name,
            @NonNull String wa,
            @NonNull String email,
            @NonNull String password,
            @NonNull AuthCallback cb
    ) {
        auth().createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        cb.onError(getErrorMessage(task));
                        return;
                    }

                    FirebaseUser user = auth().getCurrentUser();
                    if (user == null) {
                        cb.onError("Pendaftaran berhasil tetapi user null.");
                        return;
                    }

                    String uid = user.getUid();
                    DatabaseReference ref = usersRef().child(uid);

                    Map<String, Object> data = new HashMap<>();
                    data.put("uid", uid);
                    data.put("name", name);
                    data.put("no_wa", wa);
                    data.put("hp", wa);
                    data.put("email", email);
                    data.put("role", ROLE_OPERATOR);
                    data.put("approved", false);
                    data.put("status", "pending");
                    data.put("createdAt", ServerValue.TIMESTAMP);

                    ref.setValue(data)
                            .addOnSuccessListener(unused -> cb.onSuccess(user))
                            .addOnFailureListener(e -> cb.onError("Gagal menyimpan data operator: " + safeMsg(e)));
                });
    }

    // ===================== OPERATOR LOGIN =====================
    public static void loginOperator(
            @NonNull String email,
            @NonNull String password,
            @NonNull AuthCallback cb
    ) {
        auth().signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        cb.onError(getErrorMessage(task));
                        return;
                    }

                    FirebaseUser user = auth().getCurrentUser();
                    if (user == null) {
                        cb.onError("Login berhasil tetapi user null.");
                        return;
                    }

                    ensureOperatorProfileAndCheckApproved(user, cb);
                });
    }

    private static void ensureOperatorProfileAndCheckApproved(@NonNull FirebaseUser user, @NonNull AuthCallback cb) {
        String uid = user.getUid();
        DatabaseReference ref = usersRef().child(uid);

        ref.get().addOnCompleteListener(t -> {
            if (!t.isSuccessful()) {
                cb.onError("Gagal membaca profil operator di RTDB. Pastikan RTDB_URL benar & rules mengizinkan.");
                return;
            }

            Map<String, Object> up = new HashMap<>();

            // Jika node belum ada, buat default operator (approved=false)
            if (!t.getResult().exists()) {
                up.put("uid", uid);
                up.put("email", user.getEmail());
                up.put("name", user.getEmail());
                up.put("role", ROLE_OPERATOR);
                up.put("approved", false);
                up.put("status", "pending");
                up.put("createdAt", ServerValue.TIMESTAMP);
                up.put("lastLoginAt", ServerValue.TIMESTAMP);

                ref.updateChildren(up).addOnSuccessListener(unused -> {
                    auth().signOut();
                    cb.onError("Akun operator belum disetujui admin.");
                }).addOnFailureListener(e -> cb.onError("Gagal membuat profil operator: " + safeMsg(e)));
                return;
            }

            String role = t.getResult().child("role").getValue(String.class);
            Boolean approved = t.getResult().child("approved").getValue(Boolean.class);

            // Role kosong → set operator
            if (role == null || role.trim().isEmpty()) {
                up.put("role", ROLE_OPERATOR);
            }

            // Pastikan field dasar ada
            if (!t.getResult().child("email").exists()) up.put("email", user.getEmail());
            if (!t.getResult().child("name").exists())  up.put("name", user.getEmail());
            up.put("lastLoginAt", ServerValue.TIMESTAMP);

            ref.updateChildren(up).addOnSuccessListener(unused -> {
                String finalRole = (role == null || role.isEmpty()) ? ROLE_OPERATOR : role;

                if (!ROLE_OPERATOR.equalsIgnoreCase(finalRole)) {
                    auth().signOut();
                    cb.onError("Akun ini bukan operator (role=" + finalRole + ").");
                    return;
                }

                boolean isApproved = (approved != null && approved);
                if (!isApproved) {
                    auth().signOut();
                    cb.onError("Akun operator belum disetujui admin.");
                    return;
                }

                cb.onSuccess(user);
            }).addOnFailureListener(e -> cb.onError("Gagal update profil operator: " + safeMsg(e)));
        });
    }

    private static String getErrorMessage(Task<AuthResult> task) {
        if (task.getException() != null && task.getException().getMessage() != null) {
            return task.getException().getMessage();
        }
        return "Operasi autentikasi gagal.";
    }

    private static String safeMsg(Exception e) {
        return e.getMessage() == null ? "Unknown error" : e.getMessage();
    }
}
