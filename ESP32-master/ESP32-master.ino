// ================================================
// SmartLock v5.1 - MASTER (Javított Mérés + Stabil WiFi)
// ================================================

#include <Arduino.h>
#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <addons/TokenHelper.h>
#include <NimBLEDevice.h>
#include <WiFiManager.h>
#include <vector>
#include <esp_now.h>

#define API_KEY         "AIzaSyBKc9OM-4jN8IwKrUQaWsrLRlPy6djyPNw"
#define DATABASE_URL    "https://smartlock-system-f76d3-default-rtdb.europe-west1.firebasedatabase.app"
#define DEVICE_EMAIL    "device@smartlock.com"
#define DEVICE_PASSWORD "123456"

#define LOCK_ID         "LOCK_B8F862E0BCBC"

uint8_t slaveMac[] = {0x0C, 0xDC, 0x7E, 0x5D, 0x07, 0x6C};

#define PIN_RELAY  13
#define PIN_LED    2
#define PIN_BUTTON 0

FirebaseData streamData;
FirebaseData outputData;
FirebaseAuth auth;
FirebaseConfig config;

bool isLocked          = true;
bool isAuthenticated   = false;
bool streamActive      = false;
bool isActivated       = false;

std::vector<String> authorizedBeacons;
int bleRssiThreshold   = -75;
NimBLEScan* pScan = nullptr;
bool scannerMode   = false;
bool scanRunning   = false;
unsigned long lastScanStart = 0;
bool bleProximityEnabled = false;
bool bleProximityWasEnabled = false;

// RSSI / EMA
volatile int           rawMasterRssi      = -100;
volatile unsigned long rawMasterTime      = 0;
float emaMaster = -100.0f;
const float           EMA_ALPHA           = 0.35f;
const unsigned long EMA_TICK_MS           = 150;
unsigned long lastEmaTick = 0;
volatile int           bestMasterRssiThisWindow = -100;
volatile bool          haveMasterSampleThisWindow = false;

// Spatial Cooldown
bool spatialCooldownActive = false;
const float SPATIAL_RESET_RSSI = -70.0f;

// MÉRÉSI LOGIKA
unsigned long measureButtonTime = 0;
unsigned long measureBleUnlockTime = 0;
bool pendingButtonMeasure = false;
bool pendingBleMeasure = false;
const unsigned long MEASURE_TIMEOUT = 15000;

// Sorban álló feltöltések (nem blokkoló mentéshez)
bool shouldUpload = false;
long uploadDuration = 0;
String uploadMethod = "";

typedef struct { uint8_t cmd; } MasterCmd;

// MENTÉS FÜGGVÉNY
void pushMeasurement(long durationMs, String method) {
  String path = "/measurements/" + String(LOCK_ID) + "/tesztek";
  FirebaseJson json;
  json.set("duration_ms", durationMs);
  json.set("rssi_kuszob", bleRssiThreshold);
  json.set("nyitasi_mod", method);
  json.set("timestamp/.sv", "timestamp");

  if (Firebase.RTDB.pushJSON(&outputData, path.c_str(), &json)) {
    Serial.printf("[FIREBASE] SIKER: %ld ms | %s\n", durationMs, method.c_str());
  } else {
    Serial.println("[FIREBASE HIBA] " + outputData.errorReason());
  }
}

// ESP-NOW FOGADÓ
void onDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
  if (len == sizeof(MasterCmd)) {
    MasterCmd inc;
    memcpy(&inc, incomingData, sizeof(inc));

    if (inc.cmd == 99) { 
      // 1. LÉPÉS: AZONNALI VISSZAIGAZOLÁS
      MasterCmd ack;
      ack.cmd = 100;
      esp_now_send(slaveMac, (uint8_t*)&ack, sizeof(ack));
      Serial.println("[ESP-NOW] Gomb vette, ACK elkuldve.");

      // 2. LÉPÉS: Logika feldolgozása
      if (pendingBleMeasure) {
        uploadDuration = (long)measureBleUnlockTime - (long)millis();
        uploadMethod = "BLE_SUCCESS";
        shouldUpload = true; // Majd a loop-ban feltöltjük
        pendingBleMeasure = false;
      } else if (!pendingButtonMeasure) {
        measureButtonTime = millis();
        pendingButtonMeasure = true;
      }
    }
  }
}

// IDŐZÍTÉS ÉS PUFFER
unsigned long lastBleUnlockTime = 0;
const unsigned long BLE_UNLOCK_COOLDOWN = 5000;
unsigned long lastButtonPress = 0;
unsigned long lastHealthCheck = 0;
unsigned long autoLockTimer   = 0;
bool waitingForAutoLock       = false;
const unsigned long AUTO_LOCK_DELAY = 5000;

bool pendingStatusWrite     = false;
String pendingStatus        = "";
bool pendingCommandClear    = false;
unsigned long lastWriteAttempt = 0;
const unsigned long WRITE_INTERVAL = 500;

void authTokenCallback(token_info_t info) {
  if (info.status == token_status_ready) isAuthenticated = true;
}

// AJTÓ VEZÉRLÉS
void lockDoor() {
  digitalWrite(PIN_RELAY, HIGH);
  digitalWrite(PIN_LED, LOW);
  isLocked = true;
  waitingForAutoLock = false;
  pendingStatus = "LOCKED";
  pendingStatusWrite = true;
  pendingCommandClear = true;
}

void unlockDoor(String nyitasiMod = "MANUAL") {
  digitalWrite(PIN_RELAY, LOW);
  digitalWrite(PIN_LED, HIGH);
  isLocked = false;

  if (nyitasiMod == "BLE") {
    if (pendingButtonMeasure) {
      uploadDuration = (long)millis() - (long)measureButtonTime;
      uploadMethod = "BLE_SUCCESS";
      shouldUpload = true; 
      pendingButtonMeasure = false;
    } else {
      measureBleUnlockTime = millis();
      pendingBleMeasure = true;
    }
  }

  pendingStatus = "UNLOCKED";
  pendingStatusWrite = true;
  pendingCommandClear = true;
  autoLockTimer = millis();
  waitingForAutoLock = true;
}

void onStreamData(FirebaseStream data) {
  if (data.dataPath() == "/" || data.dataPath() == "/command") {
    String cmd = data.stringData();
    cmd.replace("\"", ""); cmd.trim();
    if (cmd == "OPEN") unlockDoor("MANUAL");
    else if (cmd == "CLOSE") { waitingForAutoLock = false; lockDoor(); }
  }
}

// ================================================
// JAVÍTÁS: HIÁNYZÓ FIREBASE FÜGGVÉNYEK VISSZATÉTELE
// ================================================
bool checkIfAlreadyActivated() {
  String path1 = "/locks/" + String(LOCK_ID) + "/activated";
  if (Firebase.RTDB.getBool(&outputData, path1)) return outputData.boolData();
  return false;
}

void loadAuthorizedBeacons() {
  String path = "/locks/" + String(LOCK_ID) + "/authorizedBeacons";
  if (Firebase.RTDB.getJSON(&outputData, path)) {
    FirebaseJson &json = outputData.jsonObject();
    authorizedBeacons.clear();
    size_t count = json.iteratorBegin();
    String key, value; int type;
    for (size_t i = 0; i < count; i++) {
      json.iteratorGet(i, type, key, value);
      value.replace("\"", ""); value.toLowerCase();
      if (value.length() > 0 && value != "null") authorizedBeacons.push_back(value);
    }
    json.iteratorEnd();
  }
}

// ================================================
// BLE SZKENNELÉS
// ================================================
void startScanning() {
  if (!pScan) return;
  pScan->setDuplicateFilter(false);
  pScan->start(0);
  scannerMode = true;
  scanRunning = true;
  lastScanStart = millis();

  emaMaster = -100.0f;
  rawMasterRssi = -100;
  rawMasterTime = 0;
  spatialCooldownActive = false;
}

void stopScanning() {
  if (pScan && scanRunning) pScan->stop();
  scannerMode = false;
  scanRunning = false;
  emaMaster = -100.0f;
}

class ScanCB : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* dev) override {
    if (dev->haveServiceData()) {
      int count = dev->getServiceDataCount();
      for (int s = 0; s < count; s++) {
        if (dev->getServiceDataUUID(s).equals(NimBLEUUID((uint16_t)0xABCD))) {
          std::string sd = dev->getServiceData(s);
          if (sd.length() >= 16) {
            String found = "";
            for (int i = 0; i < 16; i++) {
              char h[3]; sprintf(h, "%02x", (uint8_t)sd[i]); found += h;
            }
            for (String u : authorizedBeacons) {
              if (found.equalsIgnoreCase(u)) {
                int rssi = dev->getRSSI();
                if (!haveMasterSampleThisWindow || rssi > bestMasterRssiThisWindow) {
                    bestMasterRssiThisWindow = rssi;
                    haveMasterSampleThisWindow = true;
                    rawMasterRssi = rssi;
                    rawMasterTime = millis();
                }
                break;
              }
            }
          }
        }
      }
    }
  }
  void onScanEnd(const NimBLEScanResults& r, int reason) override {
    scanRunning = false;
  }
};

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n========== SmartLock v5.1 (Master) ==========");

  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, HIGH);

  NimBLEDevice::init("SmartLock");
  pScan = NimBLEDevice::getScan();
  pScan->setScanCallbacks(new ScanCB(), false);
  pScan->setActiveScan(true);
  pScan->setInterval(200);
  pScan->setWindow(40);

 // --- ÚJ: DIREKT CSATLAKOZÁS AZ IPHONE HOTSPOTRA ---
  const char* ssid = "istik iPhone-ja";      // Pontosan úgy, ahogy a telefonod írja (kis/nagybetű, szóköz számít!)
  const char* password = "12345678";        // A hotspotod jelszava

  Serial.printf("Csatlakozas a hotspotra: %s\n", ssid);
  
  WiFi.mode(WIFI_STA);
  WiFi.setTxPower(WIFI_POWER_19_5dBm); // A turbó bekapcsolva marad!
  WiFi.begin(ssid, password);

  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(500);
    Serial.print(".");
    attempts++;
  }

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("\n[HIBA] Nem talalom a hotspotot! Ujrainditas...");
    delay(3000);
    ESP.restart();
  }

  WiFi.setAutoReconnect(true);
  WiFi.persistent(true);
  Serial.printf("\nWiFi OK. IP: %s\n", WiFi.localIP().toString().c_str());
  // --------------------------------------------------

  if (esp_now_init() == ESP_OK) {
    esp_now_register_recv_cb(onDataRecv);
    esp_now_peer_info_t peerInfo = {};
    memcpy(peerInfo.peer_addr, slaveMac, 6);
    peerInfo.channel = 0; peerInfo.encrypt = false;
    esp_now_add_peer(&peerInfo);
  }

  config.api_key = API_KEY;
  config.database_url = DATABASE_URL;
  auth.user.email = DEVICE_EMAIL;
  auth.user.password = DEVICE_PASSWORD;
  config.token_status_callback = authTokenCallback;
  Firebase.begin(&config, &auth);

  int w = 0; while (!isAuthenticated && w < 30) { delay(1000); w++; }
  
  String path = "/locks/" + String(LOCK_ID) + "/activated";
  if (Firebase.RTDB.getBool(&outputData, path)) isActivated = outputData.boolData();

  if (isActivated) {
    loadAuthorizedBeacons();
    Firebase.RTDB.getInt(&outputData, "/locks/" + String(LOCK_ID) + "/rssiThreshold");
    bleRssiThreshold = outputData.intData();
    Firebase.RTDB.getBool(&outputData, "/locks/" + String(LOCK_ID) + "/bleProximityEnabled");
    bleProximityEnabled = outputData.boolData();
    
    Firebase.RTDB.beginStream(&streamData, ("/locks/" + String(LOCK_ID) + "/command").c_str());
    Firebase.RTDB.setStreamCallback(&streamData, onStreamData, [](bool timeout){});
  }
}

void loop() {
  unsigned long now = millis();

  // --- HALASZTOTT FIREBASE FELTÖLTÉS ---
  if (shouldUpload && isAuthenticated) {
    pushMeasurement(uploadDuration, uploadMethod);
    shouldUpload = false;
  }

  // Timeout kezelés
  if (pendingBleMeasure && (now - measureBleUnlockTime > MEASURE_TIMEOUT)) {
    pushMeasurement(0, "FALSE_POSITIVE");
    pendingBleMeasure = false;
  }
  if (pendingButtonMeasure && (now - measureButtonTime > MEASURE_TIMEOUT)) {
    pushMeasurement(15000, "FAIL_MISSED");
    pendingButtonMeasure = false;
  }

  // Proximity vezérlés
  if (bleProximityEnabled && !bleProximityWasEnabled) { startScanning(); bleProximityWasEnabled = true; }
  if (!bleProximityEnabled && bleProximityWasEnabled) { stopScanning(); bleProximityWasEnabled = false; }

  if (scannerMode && authorizedBeacons.size() > 0) {
    if (!scanRunning && (now - lastScanStart > 500)) { if (pScan->start(0)) { scanRunning = true; lastScanStart = now; } }

    if (now - lastEmaTick >= EMA_TICK_MS) {
      lastEmaTick = now;
      if (now - rawMasterTime > 2500) { emaMaster -= 2.0f; if(emaMaster < -100.0f) emaMaster = -100.0f; }
      else if (haveMasterSampleThisWindow) {
          emaMaster = EMA_ALPHA * (float)bestMasterRssiThisWindow + (1.0f - EMA_ALPHA) * emaMaster;
          haveMasterSampleThisWindow = false; bestMasterRssiThisWindow = -100;
      }
      if (spatialCooldownActive && emaMaster <= SPATIAL_RESET_RSSI) { spatialCooldownActive = false; }

      static unsigned long lastTele = 0;
      if (now - lastTele > 1000) {
        if (emaMaster > -95.0f) {
          char dbg[64]; sprintf(dbg, "RSSI: %.1f | Kuszob: %d | Blokk: %s", emaMaster, bleRssiThreshold, spatialCooldownActive ? "IGEN" : "NEM");
          Firebase.RTDB.setString(&outputData, "/locks/" + String(LOCK_ID) + "/telemetry/live_debug", String(dbg));
        } else {
          Firebase.RTDB.setString(&outputData, "/locks/" + String(LOCK_ID) + "/telemetry/live_debug", "Nincs a közelben (< -95 dBm)");
        }
        lastTele = now;
      }
    }

    if (emaMaster >= bleRssiThreshold && isLocked && !spatialCooldownActive) {
      if (now - lastBleUnlockTime > BLE_UNLOCK_COOLDOWN) {
        unlockDoor("BLE");
        lastBleUnlockTime = now;
        spatialCooldownActive = true;
        Firebase.RTDB.setInt(&outputData, "/locks/" + String(LOCK_ID) + "/telemetry/last_ble_rssi", (int)emaMaster);
        Firebase.RTDB.setString(&outputData, "/locks/" + String(LOCK_ID) + "/lastUnlockMethod", "SIMPLE_PROXIMITY");
      }
    }
  }

  // Fizikai gomb (belső)
  if (digitalRead(PIN_BUTTON) == LOW && now - lastButtonPress > 1000) {
    if (isLocked) unlockDoor("MANUAL_INTERNAL"); else lockDoor();
    lastButtonPress = now;
  }

  // Auto-lock
  if (waitingForAutoLock && (now - autoLockTimer > AUTO_LOCK_DELAY)) { lockDoor(); }

  // Firebase státusz puffer
  if ((pendingStatusWrite || pendingCommandClear) && isAuthenticated && (now - lastWriteAttempt > WRITE_INTERVAL)) {
    lastWriteAttempt = now;
    if (pendingStatusWrite) { if (Firebase.RTDB.setString(&outputData, "/locks/" + String(LOCK_ID) + "/status", pendingStatus)) pendingStatusWrite = false; }
    else if (pendingCommandClear) { if (Firebase.RTDB.setString(&outputData, "/locks/" + String(LOCK_ID) + "/command", "NONE")) pendingCommandClear = false; }
  }

  // Health check
  if (now - lastHealthCheck > 10000) {
    lastHealthCheck = now;
    loadAuthorizedBeacons();
    
    if (Firebase.RTDB.getBool(&outputData, "/locks/" + String(LOCK_ID) + "/bleProximityEnabled")) {
      bleProximityEnabled = outputData.boolData();
    }
    
    if (Firebase.RTDB.getInt(&outputData, "/locks/" + String(LOCK_ID) + "/rssiThreshold")) {
      int newThreshold = outputData.intData();
      if(newThreshold != bleRssiThreshold) {
         bleRssiThreshold = newThreshold;
      }
    }
  }
  delay(10);
}