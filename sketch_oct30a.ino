#include <WiFi.h>
#include <WebServer.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include "DHT.h"
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <ArduinoJson.h>

// ================= WIFI =================
static const char* WIFI_SSID     = "alnis";
static const char* WIFI_PASSWORD = "tanyaepa";

// ================= FIREBASE RTDB (REST) =================
static const char* FIREBASE_DB_URL = "https://hydro-guard-mobile-default-rtdb.firebaseio.com";

// ================= FIREBASE AUTH (WEB API KEY) =================
static const char* FIREBASE_WEB_API_KEY = "AIzaSyAoABIAwa9eofw6t0nm1nNSBEZn6vV-PKw";

// Akun khusus ESP32 (Firebase Auth -> Users)
static const char* USER_EMAIL    = "esp32@hg.local";
static const char* USER_PASSWORD = "esp32!";

// ================= DEVICE =================
static const char* DEVICE_ID = "HG-01";

// Path RTDB
static const char* PATH_CONTROL = "/kontrol_pompa"; // /kontrol_pompa/HG-01
static const char* PATH_SENSORS = "/telemetry";     // /telemetry/HG-01/latest

// ================= PIN ==================
static const int SOIL_PIN  = 34;
static const int RAIN_PIN  = 35;
static const int LDR_PIN   = 33;
static const int RELAY_PIN = 27;
static const int DHT_PIN   = 17;
#define DHTTYPE DHT22

// ================= RELAY ACTIVE LEVEL =================
static bool relayActiveHigh = false; // false = relay aktif LOW (umum)
static inline int RELAY_ON_LEVEL()  { return relayActiveHigh ? HIGH : LOW;  }
static inline int RELAY_OFF_LEVEL() { return relayActiveHigh ? LOW  : HIGH; }

// ================= OBJECTS =================
WebServer server(80);
LiquidCrystal_I2C lcd(0x27, 16, 2);
DHT dht(DHT_PIN, DHTTYPE);

// ================= CALIBRATION =================
static int dryADC = 3200;
static int wetADC = 1200;

// ================= LDR THRESHOLD =================
static int  LDR_ADC_THRESHOLD = 2000;
// Jika modul LDR Anda kebalik (siang terbaca gelap), ubah ke false.
static const bool LDR_INVERT = true;

// ================= AUTO RULE =================
static const int SOIL_ON_THRESHOLD  = 50;
static const int SOIL_OFF_THRESHOLD = 52;

static const int RAIN_ON_THRESHOLD  = 50;
static const int RAIN_OFF_THRESHOLD = 52;

// Debounce perubahan relay supaya tidak “cepat ON-OFF”
static const unsigned long PUMP_DEBOUNCE_MS = 1500UL; // dipercepat dari 3000ms

static const bool AUTO_USE_LOCAL_RULE = true;

// ================= STATE =================
static bool pumpState = false;
static unsigned long pumpStart = 0;
static const unsigned long pumpMaxTime = 30000UL;

// Mode dari Firebase: "manual" / "auto"
static String g_mode = "manual";

// ================= AUTO DEBOUNCE STATE =================
static bool autoPendingState = false;
static bool autoPendingInit  = false;
static unsigned long autoPendingSince = 0;
static String prevMode = "";

// ================= INTERVALS =================
// Lebih responsif untuk analog sensor (soil/rain/ldr)
static unsigned long lastFastRead = 0;
static const unsigned long FAST_READ_INTERVAL = 200UL;  // 500 -> 200 ms

// DHT jangan terlalu cepat (rawan NaN)
static unsigned long lastDhtRead = 0;
static const unsigned long DHT_READ_INTERVAL = 2000UL;

// Firebase poll bisa agak cepat, tapi jangan terlalu rapat
static unsigned long lastFbPoll = 0;
static const unsigned long FB_POLL_INTERVAL = 900UL;   // 1200 -> 900 ms

static unsigned long lastFbWrite = 0;
static const unsigned long FB_WRITE_INTERVAL = 2500UL; // 4000 -> 2500 ms

// LCD update lebih sering biar terasa responsif
static unsigned long lastLCDUpdate = 0;
static const unsigned long LCD_INTERVAL = 800UL;       // 2000 -> 800 ms

static unsigned long lastLog = 0;
static const unsigned long LOG_INTERVAL = 1200UL;

static unsigned long lastWiFiAttempt = 0;
static const unsigned long WIFI_RETRY_INTERVAL = 5000UL;

// ================= CACHED SENSOR =================
static int   g_soil = 0, g_rain = 0;
static int   g_ldr_adc = 0;
static bool  g_isBright = false; // status terang/gelap final

static float g_temp = -127.0f, g_hum = -1.0f;

static float lastGoodTemp = 0.0f;
static float lastGoodHum  = 0.0f;
static bool  hasGoodDHT   = false;

// ================= FIREBASE AUTH TOKEN =================
static String g_idToken = "";
static unsigned long g_tokenExpiresAtMs = 0;
static String g_lastAuthMsg = "";
static int g_lastAuthHttp = 0;

// ================= LCD RAPI =================
static char lastL1[17] = "";
static char lastL2[17] = "";

static inline String pad16(String s) {
  if (s.length() > 16) s = s.substring(0, 16);
  while (s.length() < 16) s += ' ';
  return s;
}

static void lcdWrite2(const String& l1, const String& l2, bool force = false) {
  String a = pad16(l1);
  String b = pad16(l2);
  if (!force && a.equals(lastL1) && b.equals(lastL2)) return;

  lcd.setCursor(0, 0); lcd.print(a);
  lcd.setCursor(0, 1); lcd.print(b);

  a.toCharArray(lastL1, 17);
  b.toCharArray(lastL2, 17);
}

static void lcd2(const String& l1, const String& l2) { lcdWrite2(l1, l2, true); }

static void lcdShowMain() {
  char l1[17];
  char l2[17];

  const char* mtxt = g_mode.equalsIgnoreCase("auto") ? "AUTO" : "MNL";
  snprintf(l1, sizeof(l1), "HG %s S:%3d", mtxt, g_soil);

  const char* ptxt = pumpState ? "PON" : "POF";
  const char* btxt = g_isBright ? "TERANG" : "GELAP";
  snprintf(l2, sizeof(l2), "R:%3d %s %s", g_rain, btxt, ptxt);

  lcdWrite2(String(l1), String(l2));
}

// ================= UTIL =================
// Sampling analog cepat TANPA delay.
// Tetap ada yield() berkala supaya WiFi stack aman.
static int readAverageFast(int pin) {
  uint32_t sum = 0;
  const int N = 8;              // cukup halus tapi tetap cepat
  for (int i = 0; i < N; i++) {
    sum += (uint32_t)analogRead(pin);
    if ((i & 0x03) == 0x03) yield(); // tiap 4 sampel
  }
  return (int)(sum / (uint32_t)N);
}

static int adcToPercent(int adcVal) {
  int w = wetADC, d = dryADC;
  if (w > d) { int tmp = w; w = d; d = tmp; }
  if (d == w) return 0;

  adcVal = constrain(adcVal, w, d);
  float ratio = (float)(adcVal - w) / (float)(d - w);
  int percent = 100 - (int)(ratio * 100.0f);
  return constrain(percent, 0, 100);
}

// Menghasilkan status "terang/gELAP" final.
// - threshold menentukan "brightRaw"
// - LDR_INVERT membalik jika wiring/modul kebalik
static bool computeIsBrightFromAdc(int adc) {
  bool brightRaw = (adc >= LDR_ADC_THRESHOLD);
  return LDR_INVERT ? !brightRaw : brightRaw;
}

static void applyRelay(bool on) {
  digitalWrite(RELAY_PIN, on ? RELAY_ON_LEVEL() : RELAY_OFF_LEVEL());
}

// ================= INTERNET TEST =================
static bool httpsGetCode(const char* url, int& outCode) {
  outCode = -999;

  WiFiClientSecure client;
  client.setInsecure();
  client.setTimeout(12000);

  HTTPClient http;
  http.setTimeout(12000);
  http.setReuse(false);

  if (!http.begin(client, url)) return false;

  int code = http.GET();
  outCode = code;
  http.end();
  return true;
}

// ================= WIFI =================
static bool connectWiFi(uint32_t timeoutMs) {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);

  WiFi.disconnect(true);
  delay(200);

  IPAddress dns1(8, 8, 8, 8);
  IPAddress dns2(1, 1, 1, 1);
  WiFi.config(INADDR_NONE, INADDR_NONE, INADDR_NONE, dns1, dns2);

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && (millis() - t0) < timeoutMs) {
    lcd2("WiFi Connecting", "...");
    delay(350);
    yield();
  }

  bool ok = (WiFi.status() == WL_CONNECTED);
  if (!ok) {
    lcd2("WiFi FAILED", "cek SSID/PASS");
    delay(800);
    return false;
  }

  lcd2("WiFi Connected", WiFi.localIP().toString());
  delay(500);

  int code = 0;
  if (httpsGetCode("https://www.google.com/generate_204", code)) {
    if (code == 204) {
      lcd2("Internet", "OK");
      delay(400);
    } else {
      lcd2("NO INTERNET", "code:" + String(code));
      delay(1200);
    }
  } else {
    lcd2("HTTPS BEGIN", "FAIL");
    delay(1200);
  }

  return true;
}

static void ensureWiFi() {
  if (WiFi.status() == WL_CONNECTED) return;
  unsigned long now = millis();
  if (now - lastWiFiAttempt < WIFI_RETRY_INTERVAL) return;
  lastWiFiAttempt = now;
  connectWiFi(12000);
}

// ================= FIREBASE AUTH (SIGN-IN) =================
static bool firebaseSignInEmailPassword() {
  g_lastAuthMsg = "";
  g_lastAuthHttp = 0;

  if (WiFi.status() != WL_CONNECTED) {
    g_lastAuthMsg = "WIFI_OFF";
    return false;
  }

  WiFiClientSecure client;
  client.setInsecure();
  client.setTimeout(12000);

  HTTPClient http;
  http.setTimeout(12000);
  http.setReuse(false);

  String url =
    "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" +
    String(FIREBASE_WEB_API_KEY);

  if (!http.begin(client, url)) {
    g_lastAuthMsg = "BEGIN_FAIL";
    return false;
  }

  http.addHeader("Content-Type", "application/json");

  StaticJsonDocument<256> req;
  req["email"] = USER_EMAIL;
  req["password"] = USER_PASSWORD;
  req["returnSecureToken"] = true;

  String payload;
  serializeJson(req, payload);

  int code = http.POST(payload);
  g_lastAuthHttp = code;

  String body = (code > 0) ? http.getString() : "";

  if (code <= 0) {
    String es = http.errorToString(code);
    Serial.printf("[AUTH] HTTP POST failed code=%d (%s)\n", code, es.c_str());
    g_lastAuthMsg = "POST " + String(code);
    http.end();
    return false;
  }

  http.end();

  if (code < 200 || code >= 300) {
    Serial.printf("[AUTH] signIn FAILED code=%d\n", code);
    Serial.println(body);

    StaticJsonDocument<1024> edoc;
    DeserializationError derr = deserializeJson(edoc, body);
    if (!derr) {
      const char* msg = edoc["error"]["message"] | "AUTH_FAIL";
      g_lastAuthMsg = String(code) + " " + String(msg);
    } else {
      g_lastAuthMsg = String(code) + " AUTH_FAIL";
    }
    return false;
  }

  StaticJsonDocument<2048> res;
  DeserializationError err = deserializeJson(res, body);
  if (err) {
    Serial.print("[AUTH] parse FAILED: ");
    Serial.println(err.c_str());
    g_lastAuthMsg = "PARSE_ERR";
    return false;
  }

  const char* idToken = res["idToken"] | "";
  long expiresInSec = 0;

  if (res["expiresIn"].is<const char*>()) expiresInSec = String((const char*)res["expiresIn"]).toInt();
  else if (res["expiresIn"].is<long>())  expiresInSec = res["expiresIn"].as<long>();

  if (String(idToken).length() < 20) {
    Serial.println("[AUTH] idToken kosong/tidak valid");
    g_lastAuthMsg = "NO_TOKEN";
    return false;
  }

  g_idToken = String(idToken);

  unsigned long ttlMs = (expiresInSec > 60 ? (unsigned long)(expiresInSec - 60) : 300UL) * 1000UL;
  g_tokenExpiresAtMs = millis() + ttlMs;

  Serial.println("[AUTH] OK: idToken didapat");
  g_lastAuthMsg = "OK";
  return true;
}

static void ensureFirebaseAuth() {
  if (WiFi.status() != WL_CONNECTED) return;

  if (g_idToken.length() == 0 || (long)(millis() - g_tokenExpiresAtMs) > 0) {
    lcd2("Firebase Auth", "Signing in...");
    bool ok = firebaseSignInEmailPassword();
    if (ok) {
      lcd2("Firebase Auth", "OK");
      delay(600);
    } else {
      lcd2("Firebase FAILED", g_lastAuthMsg);
      delay(1400);
    }
  }
}

// ================= FIREBASE REST =================
static String fbUrl(const String& path, bool silent) {
  String url = String(FIREBASE_DB_URL) + path + ".json";
  bool hasQ = false;

  if (silent) { url += "?print=silent"; hasQ = true; }

  if (g_idToken.length() > 0) {
    url += (hasQ ? "&" : "?");
    url += "auth=" + g_idToken;
  }
  return url;
}

static int fbGetCode(const String& path, String& outBody) {
  outBody = "";
  if (WiFi.status() != WL_CONNECTED) return -1000;

  WiFiClientSecure client; client.setInsecure(); client.setTimeout(12000);
  HTTPClient http; http.setTimeout(12000); http.setReuse(false);

  String url = fbUrl(path, false);
  if (!http.begin(client, url)) return -1001;

  int code = http.GET();
  if (code > 0) outBody = http.getString();
  http.end();

  if (code == 401 || code == 403) {
    g_idToken = "";
    g_tokenExpiresAtMs = 0;
  }
  return code;
}

static bool fbPutJson(const String& path, const String& jsonBody) {
  if (WiFi.status() != WL_CONNECTED) return false;

  WiFiClientSecure client; client.setInsecure(); client.setTimeout(12000);
  HTTPClient http; http.setTimeout(12000); http.setReuse(false);

  String url = fbUrl(path, true);
  if (!http.begin(client, url)) return false;

  http.addHeader("Content-Type", "application/json");
  int code = http.PUT(jsonBody);
  http.end();

  if (code == 401 || code == 403) {
    g_idToken = "";
    g_tokenExpiresAtMs = 0;
  }

  return ((code >= 200 && code < 300) || (code == 204));
}

static bool fbPatchJson(const String& path, const String& jsonBody) {
  if (WiFi.status() != WL_CONNECTED) return false;

  WiFiClientSecure client; client.setInsecure(); client.setTimeout(12000);
  HTTPClient http; http.setTimeout(12000); http.setReuse(false);

  String url = fbUrl(path, true);
  if (!http.begin(client, url)) return false;

  http.addHeader("Content-Type", "application/json");

  int code = http.sendRequest("PATCH", (uint8_t*)jsonBody.c_str(), jsonBody.length());
  http.end();

  if (code == 401 || code == 403) {
    g_idToken = "";
    g_tokenExpiresAtMs = 0;
  }

  return ((code >= 200 && code < 300) || (code == 204));
}

static bool parseBoolVariant(JsonVariant v, bool &out) {
  if (v.is<bool>()) { out = v.as<bool>(); return true; }
  if (v.is<int>() || v.is<long>()) { out = (v.as<long>() != 0); return true; }
  if (v.is<const char*>()) {
    String s = String((const char*)v);
    s.trim(); s.toLowerCase();
    if (s == "true" || s == "1" || s == "on"  || s == "yes") { out = true;  return true; }
    if (s == "false"|| s == "0" || s == "off" || s == "no")  { out = false; return true; }
  }
  return false;
}

// ================= PUMP =================
static void setPumpLocal(bool on, bool pushToFirebase) {
  if (pumpState == on) return;

  pumpState = on;
  applyRelay(on);
  if (on) pumpStart = millis();

  if (!pushToFirebase) return;

  StaticJsonDocument<192> doc;
  doc["status"] = pumpState;
  doc["updatedBy"] = "esp32";
  doc["updatedAt"][".sv"] = "timestamp";

  String payload; serializeJson(doc, payload);
  String path = String(PATH_CONTROL) + "/" + DEVICE_ID;

  fbPatchJson(path, payload);
}

// ================= SENSOR UPDATE =================
static void updateSensors() {
  unsigned long now = millis();

  // Analog cepat
  if (now - lastFastRead >= FAST_READ_INTERVAL) {
    lastFastRead = now;

    g_soil = adcToPercent(readAverageFast(SOIL_PIN));

    g_rain = map(readAverageFast(RAIN_PIN), 0, 4095, 100, 0);
    g_rain = constrain(g_rain, 0, 100);

    g_ldr_adc = readAverageFast(LDR_PIN);
    g_isBright  = computeIsBrightFromAdc(g_ldr_adc);
  }

  // DHT tetap interval aman
  if (now - lastDhtRead >= DHT_READ_INTERVAL) {
    lastDhtRead = now;

    float t = dht.readTemperature();
    float h = dht.readHumidity();

    if (!isnan(t) && !isnan(h)) {
      g_temp = t;
      g_hum  = h;

      lastGoodTemp = t;
      lastGoodHum  = h;
      hasGoodDHT   = true;
    } else {
      if (hasGoodDHT) {
        g_temp = lastGoodTemp;
        g_hum  = lastGoodHum;
      } else {
        g_temp = 0.0f;
        g_hum  = 0.0f;
      }
    }

    if (g_hum < 0) g_hum = 0;
    if (g_hum > 100) g_hum = 100;
  }
}

// ================= AUTO PUMP RULE =================
static bool computeAutoDesiredWithHysteresis() {
  if (!pumpState) {
    if (g_soil <= SOIL_ON_THRESHOLD && g_rain <= RAIN_ON_THRESHOLD) return true;
    return false;
  } else {
    if (g_soil >= SOIL_OFF_THRESHOLD || g_rain >= RAIN_OFF_THRESHOLD) return false;
    return true;
  }
}

static void autoPumpBySoilAndRain() {
  bool desired = computeAutoDesiredWithHysteresis();
  const unsigned long now = millis();

  if (!autoPendingInit) {
    autoPendingInit = true;
    autoPendingState = desired;
    autoPendingSince = now;
  }

  if (desired != autoPendingState) {
    autoPendingState = desired;
    autoPendingSince = now;
  }

  if ((autoPendingState != pumpState) && (now - autoPendingSince >= PUMP_DEBOUNCE_MS)) {
    setPumpLocal(autoPendingState, true);
  }
}

// ================= TELEMETRY =================
static void sendSensorsToFirebase() {
  float humToSend = g_hum;
  float tempToSend = g_temp;

  if (humToSend < 0) humToSend = 0;
  if (humToSend > 100) humToSend = 100;
  if (isnan(humToSend) || isinf(humToSend)) humToSend = 0;

  if (isnan(tempToSend) || isinf(tempToSend)) tempToSend = 0;

  StaticJsonDocument<360> doc;
  doc["temp"] = tempToSend;
  doc["hum"]  = humToSend;
  doc["soil"] = g_soil;
  doc["rain_pct"] = g_rain;

  // ✅ kirim status terang/gelap yang jelas
  doc["ldr_adc"]  = g_ldr_adc;
  doc["ldr_status"] = g_isBright ? "TERANG" : "GELAP";
  doc["bright"]   = g_isBright; // tetap kirim boolean kalau UI butuh

  doc["updatedAt"][".sv"] = "timestamp";

  String payload; serializeJson(doc, payload);

  String path = String(PATH_SENSORS) + "/" + DEVICE_ID + "/latest";
  if (!fbPutJson(path, payload)) {
    Serial.println("[FB] sendSensors FAILED");
  }
}

// ================= CONTROL POLL =================
static void pollControlFromFirebase() {
  String path = String(PATH_CONTROL) + "/" + DEVICE_ID;

  String body;
  int code = fbGetCode(path, body);
  if (code <= 0) {
    Serial.printf("[FB] GET control error code=%d\n", code);
    return;
  }

  body.trim();
  if (body.length() == 0 || body == "null") return;

  StaticJsonDocument<768> doc;
  auto err = deserializeJson(doc, body);
  if (err) {
    Serial.print("[FB] parse control gagal: ");
    Serial.println(err.c_str());
    return;
  }

  if (doc.containsKey("mode")) {
    const char* m = doc["mode"] | "";
    if (String(m).length() > 0) {
      g_mode = String(m);
      g_mode.trim();
    }
  }

  if (!prevMode.equalsIgnoreCase(g_mode)) {
    prevMode = g_mode;
    autoPendingInit = false;
    autoPendingState = pumpState;
    autoPendingSince = millis();
  }

  bool cmdStatus = false;
  bool hasStatus = false;
  if (doc.containsKey("status")) {
    hasStatus = parseBoolVariant(doc["status"], cmdStatus);
  }

  if (g_mode.equalsIgnoreCase("manual")) {
    if (hasStatus) {
      setPumpLocal(cmdStatus, false);
    }
    return;
  }

  // AUTO: status firebase diabaikan (sensor-only)
}

// ================= INIT CONTROL NODE =================
static void ensureControlNodeExists() {
  if (WiFi.status() != WL_CONNECTED) return;
  if (g_idToken.length() == 0) return;

  String path = String(PATH_CONTROL) + "/" + DEVICE_ID;

  String body;
  int code = fbGetCode(path, body);
  if (code <= 0) return;

  body.trim();

  if (body == "null" || body.length() == 0) {
    StaticJsonDocument<256> doc;
    doc["mode"] = "manual";
    doc["status"] = false;
    doc["updatedBy"] = "esp32_boot_init";
    doc["updatedAt"][".sv"] = "timestamp";

    String payload; serializeJson(doc, payload);

    bool ok = fbPutJson(path, payload);
    Serial.printf("[FB] init control node: %s\n", ok ? "OK" : "FAIL");
  } else {
    Serial.println("[FB] control node exists -> keep");
  }
}

// ================= WEB UI =================
static String pageHTML() {
  String html;
  html.reserve(1600);

  html += "<!DOCTYPE html><html><head><meta charset='UTF-8'>";
  html += "<meta name='viewport' content='width=device-width,initial-scale=1'/>";
  html += "<title>HYDRO-GUARD</title></head><body>";
  html += "<h3>HG ESP32</h3>";
  html += "<p>Device: "; html += DEVICE_ID; html += "</p>";
  html += "<p>Mode: "; html += g_mode; html += "</p>";
  html += "<p>";
  html += "Soil: " + String(g_soil) + "%<br>";
  html += "Rain: " + String(g_rain) + "%<br>";
  html += "Hum: " + String(g_hum, 1) + "%<br>";
  html += "Temp: " + String(g_temp, 1) + "C<br>";
  html += "LDR ADC: " + String(g_ldr_adc) + "<br>";
  html += "LDR Status: " + String(g_isBright ? "TERANG" : "GELAP") + "<br>";
  html += "Pump: " + String(pumpState ? "ON" : "OFF") + "<br>";
  html += "</p></body></html>";

  return html;
}

static void sendRoot() {
  updateSensors();
  server.send(200, "text/html", pageHTML());
}

// ================= SETUP =================
void setup() {
  Serial.begin(115200);
  delay(200);

  Wire.begin(21, 22);

  pinMode(RELAY_PIN, OUTPUT);
  applyRelay(false);

  analogReadResolution(12);
  analogSetPinAttenuation(SOIL_PIN, ADC_11db);
  analogSetPinAttenuation(RAIN_PIN, ADC_11db);
  analogSetPinAttenuation(LDR_PIN,  ADC_11db);

  lcd.init();
  lcd.backlight();
  lcd2("HYDRO-GUARD", "Starting...");
  delay(400);

  dht.begin();

  connectWiFi(30000);
  ensureFirebaseAuth();

  if (WiFi.status() == WL_CONNECTED) {
    String dummy;
    int code = fbGetCode("/", dummy);
    Serial.printf("[FB] ping code=%d\n", code);

    if (code == 401 || code == 403) {
      lcd2("RTDB DENIED", "rules/auth");
      delay(1200);
    } else {
      lcd2("Firebase Ping", "code:" + String(code));
      delay(900);
    }
  }

  server.on("/", sendRoot);
  server.begin();
  Serial.println("Web server aktif!");

  ensureControlNodeExists();
}

// ================= LOOP =================
void loop() {
  server.handleClient();
  yield();

  ensureWiFi();
  ensureFirebaseAuth();
  updateSensors();

  if (WiFi.status() == WL_CONNECTED && (millis() - lastFbPoll >= FB_POLL_INTERVAL)) {
    lastFbPoll = millis();
    pollControlFromFirebase();
  }

  if (g_mode.equalsIgnoreCase("auto") && AUTO_USE_LOCAL_RULE) {
    autoPumpBySoilAndRain();
  }

  if (pumpState && (millis() - pumpStart > pumpMaxTime)) {
    Serial.println("[PUMP] safety timeout -> OFF");
    setPumpLocal(false, true);
  }

  if (WiFi.status() == WL_CONNECTED && (millis() - lastFbWrite >= FB_WRITE_INTERVAL)) {
    lastFbWrite = millis();
    sendSensorsToFirebase();
  }

  if (millis() - lastLCDUpdate >= LCD_INTERVAL) {
    lastLCDUpdate = millis();
    lcdShowMain();
  }

  if (millis() - lastLog >= LOG_INTERVAL) {
    lastLog = millis();

    bool desiredAuto = computeAutoDesiredWithHysteresis();
    Serial.printf(
      "Mode=%s | Soil=%d | Rain=%d | LDR=%d (%s) | Pump=%s | AutoDesired=%s | Pending=%s (%lums)\n",
      g_mode.c_str(),
      g_soil,
      g_rain,
      g_ldr_adc,
      (g_isBright ? "TERANG" : "GELAP"),
      pumpState ? "ON" : "OFF",
      desiredAuto ? "ON" : "OFF",
      (autoPendingInit ? (autoPendingState ? "ON" : "OFF") : "N/A"),
      (autoPendingInit ? (unsigned long)(millis() - autoPendingSince) : 0UL)
    );
  }

  // Tidak pakai delay besar agar respons cepat, cukup yield.
  yield();
}
