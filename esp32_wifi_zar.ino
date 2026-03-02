/**
 * - Real-time Firebase Stream 
 * - auto reload
 */

#include <Arduino.h>
#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <addons/TokenHelper.h>
#include <ESP32Servo.h>
#include <NimBLEDevice.h>
#include <NimBLEBeacon.h>

// Brownout 
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

#define WIFI_SSID "Ki ki ki rah ay ay ay"
#define WIFI_PASSWORD "istike96"

#define API_KEY "AIzaSyBKc9OM-4jN8IwKrUQaWsrLRlPy6djyPNw"
#define DATABASE_URL "https://smartlock-system-f76d3-default-rtdb.europe-west1.firebasedatabase.app"

#define DEVICE_EMAIL "device@smartlock.com"
#define DEVICE_PASSWORD "123456"
#define LOCK_ID "LOCK_0CDC7E614160"

// BLE Beacon UUID (iBeacon protokoll)
#define BEACON_UUID_RAW "54c136ce1aca407fb5be9cf2a9cd69f0"

// ================= HARDVER KIOSZTÁS =================
#define PIN_SERVO 13        // Szervó motor (zár mechanizmus)
#define PIN_LED 2           // Státusz LED
#define PIN_BUTTON 0        // Manuális nyitó gomb (BOOT)

// ================= GLOBÁLIS OBJEKTUMOK =================
FirebaseData streamData;    // Stream kapcsolat objektum
FirebaseAuth auth;          // Autentikációs objektum
FirebaseConfig config;      // Firebase konfiguráció
Servo myServo;             // Szervó vezérlő

// ================= ÁLLAPOTVÁLTOZÓK =================
bool isLocked = true;                    // Zár állapota
bool isAuthenticated = false;            // Firebase auth státusz
bool streamActive = false;               // Stream működési státusz
unsigned long lastButtonPress = 0;       // Gomb debounce
unsigned long lastHealthCheck = 0;       // Kapcsolat ellenőrzés időzítő
const int AUTO_LOCK_DELAY = 5000;       // Auto-lock késleltetés (ms)

// ================= TOKEN CALLBACK =================
/**
 * Firebase authentication token státusz figyelő
 * Meghívódik amikor a token állapota változik
 */
void authTokenCallback(token_info_t info) {
  if (info.status == token_status_ready) {
    isAuthenticated = true;
    Serial.println("✓ Authentication successful - Token ready");
  } else if (info.status == token_status_error) {
    isAuthenticated = false;
    Serial.printf("✗ Authentication error: %s\n", info.error.message.c_str());
  }
}

// ================= ZÁR MŰVELETEK =================

/**
 * Zár nyitása
 * - Szervó mozgatás
 * - LED jelzés
 * - Firebase státusz frissítés
 * - Aktivitás naplózás
 */
void unlockDoor() {
  myServo.write(90);
  digitalWrite(PIN_LED, HIGH);
  isLocked = false;
  
  if (isAuthenticated) {
    Firebase.RTDB.setString(&streamData, 
                           "/locks/" + String(LOCK_ID) + "/status", 
                           "UNLOCKED");
    Firebase.RTDB.setString(&streamData, 
                           "/locks/" + String(LOCK_ID) + "/command", 
                           "NONE");
    // Log eltávolítva – a telefon már naplóz
  }
  
  delay(AUTO_LOCK_DELAY);
  lockDoor();
}

/**
 * Zár lezárása
 * - Szervó visszaállítás
 * - LED kikapcsolás
 * - Firebase státusz frissítés
 */
void lockDoor() {
  Serial.println("\n╔══════════════════════╗");
  Serial.println("║   🔒 LOCKING...     ║");
  Serial.println("╚══════════════════════╝");
  
  // Fizikai művelet
  myServo.write(0);                // Szervó 0° (zárt pozíció)
  digitalWrite(PIN_LED, LOW);       // LED kikapcsolás
  isLocked = true;
  
  // Firebase státusz szinkronizálás
  if (isAuthenticated) {
    Firebase.RTDB.setString(&streamData, 
                           "/locks/" + String(LOCK_ID) + "/status", 
                           "LOCKED");
    Firebase.RTDB.setString(&streamData, 
                           "/locks/" + String(LOCK_ID) + "/command", 
                           "NONE");
  }
  
  Serial.println("✓ Door secured\n");
}

// ================= FIREBASE STREAM CALLBACKS =================

/**
 * Stream adat callback
 * Real-time változások kezelése Firebase-ből
 * 
 * @param data - Firebase stream adat objektum
 */
void onStreamData(FirebaseStream data) {
  Serial.printf("📡 Stream event: %s = %s\n", 
                data.dataPath().c_str(), 
                data.stringData().c_str());
  
  // Parancs mező változásának figyelése
  if (data.dataPath() == "/" || data.dataPath() == "/command") {
    String command = data.stringData();
    command.replace("\"", "");  // JSON idézőjelek eltávolítása
    command.trim();
    
    // Parancs végrehajtás
    if (command == "OPEN") {
      Serial.println("→ Executing OPEN command");
      unlockDoor();
    } 
    else if (command == "CLOSE") {
      Serial.println("→ Executing CLOSE command");
      lockDoor();
    }
  }
}

/**
 * Stream timeout callback
 * Kapcsolat megszakadás kezelése
 * 
 * @param timeout - true ha timeout történt
 */
void onStreamTimeout(bool timeout) {
  if (timeout) {
    Serial.println("⚠ Stream timeout - Connection will auto-reconnect");
    streamActive = false;
  }
}

// ================= BLE BEACON SETUP =================

/**
 * iBeacon protokoll inicializálás
 * Háttérben futó mobil észleléshez (geofencing)
 */
void initBeacon() {
  NimBLEDevice::init("SmartLock");
  NimBLEServer *pServer = NimBLEDevice::createServer();
  NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
  
  // iBeacon adat csomag építése
  std::string beaconData = "";
  beaconData += (char)0x4C; beaconData += (char)0x00;  // Apple Company ID
  beaconData += (char)0x02; beaconData += (char)0x15;  // iBeacon type & length
  
  // UUID beillesztése (32 hex karakter)
  std::string uuid = BEACON_UUID_RAW;
  for(int i = 0; i < uuid.length(); i += 2) {
    std::string hexByte = uuid.substr(i, 2);
    char byte = (char)strtol(hexByte.c_str(), NULL, 16);
    beaconData += byte;
  }
  
  // Major & Minor version, TX power
  beaconData += (char)0x00; beaconData += (char)0x01;  // Major: 1
  beaconData += (char)0x00; beaconData += (char)0x01;  // Minor: 1
  beaconData += (char)0xC5;                            // TX Power: -59dBm
  
  // Advertisement konfiguráció
  NimBLEAdvertisementData advData;
  advData.setManufacturerData(beaconData);
  pAdvertising->setAdvertisementData(advData);
  pAdvertising->start();
  
  Serial.println("✓ BLE Beacon broadcasting");
}

// ================= STREAM KEZELÉS =================

/**
 * Firebase Stream inicializálás
 * WebSocket kapcsolat létrehozása
 * 
 * @return true ha sikeres, false ha hiba
 */
bool startStream() {
  Serial.println("🔄 Starting Firebase Stream...");
  
  // Stream paraméterek
  streamData.setBSSLBufferSize(4096, 1024);
  streamData.setResponseSize(2048);
  
  // Keep-alive beállítások (kapcsolat fenntartás)
  // (timeout, interval, max_retry)
  streamData.keepAlive(30, 30, 1);
  
  // Stream indítás a command mezőre
  String streamPath = "/locks/" + String(LOCK_ID) + "/command";
  
  if (Firebase.RTDB.beginStream(&streamData, streamPath.c_str())) {
    // Callback-ek regisztrálása
    Firebase.RTDB.setStreamCallback(&streamData, onStreamData, onStreamTimeout);
    streamActive = true;
    Serial.println("✓ Stream connected - Real-time mode active");
    return true;
  } else {
    Serial.printf("✗ Stream error: %s\n", streamData.errorReason().c_str());
    streamActive = false;
    return false;
  }
}

/**
 * Stream újraindítás hiba esetén
 */
void restartStream() {
  Serial.println("♻️  Restarting stream...");
  
  if (streamActive) {
    Firebase.RTDB.endStream(&streamData);
    streamActive = false;
    delay(2000);  // Várakozás a clean shutdown-ra
  }
  
  startStream();
}

// ================= RENDSZER INICIALIZÁLÁS =================

void setup() {
  // Brownout védelem kikapcsolás (stabilitás)
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);
  
  // Soros kommunikáció
  Serial.begin(115200);
  delay(1000);
  
  Serial.println("\n\n╔═══════════════════════════════════════╗");
  Serial.println("║   SMARTLOCK SYSTEM v2.0 (FINAL)      ║");
  Serial.println("║   Real-time Firebase Stream          ║");
  Serial.println("╚═══════════════════════════════════════╝\n");
  
  // ===== HARDVER INICIALIZÁLÁS =====
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  myServo.attach(PIN_SERVO);
  myServo.write(0);           // Kezdeti pozíció: zárt
  digitalWrite(PIN_LED, LOW);
  
  Serial.println("✓ Hardware initialized");
  
  // ===== WIFI KAPCSOLAT =====
  Serial.print("📡 Connecting to WiFi");
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(true);  // Power saving mode (BLE miatt)
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    Serial.print(".");
    delay(500);
    attempts++;
  }
  
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println(" ✓");
    Serial.printf("   IP: %s\n", WiFi.localIP().toString().c_str());
    Serial.printf("   Signal: %d dBm\n\n", WiFi.RSSI());
  } else {
    Serial.println(" ✗ FAILED");
    Serial.println("⚠ Cannot continue without WiFi\n");
    while(1) { delay(1000); }  // Halt
  }
  
  // ===== BLE BEACON =====
  initBeacon();
  Serial.println();
  
  // ===== FIREBASE KONFIGURÁCIÓ =====
  Serial.println("🔥 Configuring Firebase...");
  
  config.api_key = API_KEY;
  config.database_url = DATABASE_URL;
  config.timeout.socketConnection = 30000;
  config.timeout.serverResponse = 10000;
  
  auth.user.email = DEVICE_EMAIL;
  auth.user.password = DEVICE_PASSWORD;
  config.token_status_callback = authTokenCallback;
  
  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);
  
  // ===== AUTHENTICATION VÁRAKOZÁS =====
  Serial.print("🔐 Authenticating");
  int authWait = 0;
  while (!Firebase.ready() && authWait < 30) {
    Serial.print(".");
    delay(1000);
    authWait++;
  }
  Serial.println();
  
  if (!Firebase.ready()) {
    Serial.println("✗ Firebase connection timeout!");
    Serial.println("⚠ Check your credentials and network\n");
    while(1) { delay(1000); }  // Halt
  }
  
  // Token várakozás
  delay(3000);
  
  if (!isAuthenticated) {
    Serial.println("✗ Authentication failed!");
    Serial.println("⚠ Check Firebase Authentication settings\n");
    while(1) { delay(1000); }  // Halt
  }
  
  Serial.println("✓ Firebase authenticated\n");
  
  // ===== KEZDETI STÁTUSZ BEÁLLÍTÁS =====
  Firebase.RTDB.setString(&streamData, "/locks/" + String(LOCK_ID) + "/status", "LOCKED");
  Firebase.RTDB.setString(&streamData, "/locks/" + String(LOCK_ID) + "/command", "NONE");
  
  // ===== STREAM INDÍTÁS =====
  if (!startStream()) {
    Serial.println("⚠ Stream failed to start, will retry in loop\n");
  }
  
  Serial.println("\n╔═══════════════════════════════════════╗");
  Serial.println("║      🚀 SYSTEM READY                  ║");
  Serial.println("╚═══════════════════════════════════════╝\n");
}

// ================= FŐ PROGRAM LOOP =================

void loop() {
  
  // ===== MANUÁLIS GOMB KEZELÉS =====
  if (digitalRead(PIN_BUTTON) == LOW) {
    if (millis() - lastButtonPress > 1000) {  // Debounce: 1 sec
      Serial.println("🔘 Manual button pressed");
      
      if (isLocked) {
        unlockDoor();
      } else {
        lockDoor();
      }
      
      lastButtonPress = millis();
    }
  }
  
  // ===== KAPCSOLAT HEALTH CHECK =====
  // 30 másodpercenként ellenőrizzük a kapcsolatot
  if (millis() - lastHealthCheck > 30000) {
    lastHealthCheck = millis();
    
    Serial.println("💓 Health check:");
    Serial.printf("   WiFi: %s (%d dBm)\n", 
                  WiFi.status() == WL_CONNECTED ? "Connected" : "Disconnected",
                  WiFi.RSSI());
    Serial.printf("   Firebase: %s\n", Firebase.ready() ? "Ready" : "Not Ready");
    Serial.printf("   Auth: %s\n", isAuthenticated ? "Valid" : "Invalid");
    Serial.printf("   Stream: %s\n\n", streamActive ? "Active" : "Inactive");
    
    // WiFi újracsatlakozás ha szükséges
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("⚠ WiFi disconnected, reconnecting...");
      WiFi.reconnect();
      streamActive = false;
    }
    
    // Stream újraindítás ha megszakadt
    if (Firebase.ready() && isAuthenticated && !streamActive) {
      Serial.println("⚠ Stream inactive, restarting...");
      restartStream();
    }
  }
  
  delay(10);  // CPU tehermentesítés
}
