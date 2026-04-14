#include <Arduino.h>
#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <addons/TokenHelper.h>
#include <NimBLEDevice.h>
#include <WiFiManager.h>
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"
#include <vector>

#define API_KEY         "AIzaSyBKc9OM-4jN8IwKrUQaWsrLRlPy6djyPNw"
#define DATABASE_URL    "https://smartlock-system-f76d3-default-rtdb.europe-west1.firebasedatabase.app"
#define DEVICE_EMAIL    "device@smartlock.com"
#define DEVICE_PASSWORD "123456"
#define LOCK_ID         "LOCK_0CDC7E5D076C" 

#define PIN_RELAY  13
#define PIN_LED    2
#define PIN_BUTTON 0

#define ACTIVATION_SERVICE_UUID     "12345678-1234-1234-1234-123456789abc"
#define ACTIVATION_CHAR_UUID        "12345678-1234-1234-1234-123456789abd"
#define ACTIVATION_STATUS_CHAR_UUID "12345678-1234-1234-1234-123456789abe"
#define SMARTLOCK_SVC_UUID          "0000abcd-0000-1000-8000-00805f9b34fb"

FirebaseData streamData;
FirebaseData outputData;
FirebaseAuth auth;
FirebaseConfig config;

bool isLocked          = true;
bool isAuthenticated   = false;
bool streamActive      = false;
bool isActivated       = false;

// BLE scanning
std::vector<String> authorizedBeacons;
bool phoneNearby       = false;
bool phoneWasNearby    = false;
unsigned long lastSeenPhone = 0;
const unsigned long BLE_TIMEOUT = 8000;
int bleRssiThreshold   = -75;
NimBLEScan* pScan = nullptr;
bool scannerMode   = false;
bool scanRunning   = false;
unsigned long lastScanStart = 0;
bool bleNeedsInit  = false;

// BLE proximity control from app
bool bleProximityEnabled = false;
bool bleProximityWasEnabled = false;
bool scannerInitialized = false;

// RSSI sliding window (5 samples)
#define RSSI_WINDOW_SIZE 5
int rssiWindow[RSSI_WINDOW_SIZE];
int rssiWindowIndex = 0;
int rssiSampleCount = 0;

// Unlock cooldown
unsigned long lastBleUnlockTime = 0;
const unsigned long BLE_UNLOCK_COOLDOWN = 30000;  // 30 sec

// Activation
bool activationMode     = false;
bool activationComplete = false;
bool activationDataReceived = false;
String ownerUID         = "";
String authorizedBeaconUUID = ""; // Visszakerült az aktiváláshoz

// Timers
unsigned long lastButtonPress = 0;
unsigned long lastHealthCheck = 0;
unsigned long autoLockTimer   = 0;
bool waitingForAutoLock       = false;
const unsigned long AUTO_LOCK_DELAY = 5000;

// Firebase write queue
bool pendingStatusWrite  = false;
String pendingStatus     = "";
bool pendingCommandClear = false;
unsigned long lastWriteAttempt = 0;
const unsigned long WRITE_INTERVAL = 500;

unsigned long lastLedBlink = 0;
bool ledState = false;

// ===================== RSSI HELPERS =====================
void rssiWindowReset() {
  rssiWindowIndex = 0;
  rssiSampleCount = 0;
  for (int i = 0; i < RSSI_WINDOW_SIZE; i++) rssiWindow[i] = -100;
}

void rssiWindowAdd(int rssi) {
  rssiWindow[rssiWindowIndex] = rssi;
  rssiWindowIndex = (rssiWindowIndex + 1) % RSSI_WINDOW_SIZE;
  if (rssiSampleCount < RSSI_WINDOW_SIZE) rssiSampleCount++;
}

int rssiWindowAvg() {
  if (rssiSampleCount == 0) return -100;
  int sum = 0;
  for (int i = 0; i < rssiSampleCount; i++) sum += rssiWindow[i];
  return sum / rssiSampleCount;
}

// ===================== AUTH =====================
void authTokenCallback(token_info_t info) {
  if (info.status == token_status_ready) {
    isAuthenticated = true;
    Serial.println("Auth: Token ready");
  } else if (info.status == token_status_error) {
    isAuthenticated = false;
    Serial.printf("Auth error: %s\n", info.error.message.c_str());
  }
}

// ===================== LOCK =====================
void lockDoor() {
  Serial.println("Locking...");
  digitalWrite(PIN_RELAY, HIGH);
  digitalWrite(PIN_LED, LOW);
  isLocked = true;
  waitingForAutoLock = false;
  pendingStatus = "LOCKED";
  pendingStatusWrite = true;
  pendingCommandClear = true;
  Serial.println("Locked.");
}

void unlockDoor() {
  Serial.println("Unlocking...");
  digitalWrite(PIN_RELAY, LOW);
  digitalWrite(PIN_LED, HIGH);
  isLocked = false;
  pendingStatus = "UNLOCKED";
  pendingStatusWrite = true;
  pendingCommandClear = true;
  autoLockTimer = millis();
  waitingForAutoLock = true;
  Serial.println("Unlocked. Auto-lock in 5s...");
}

// ===================== STREAM =====================
void onStreamData(FirebaseStream data) {
  Serial.printf("Stream: %s = %s\n",
                data.dataPath().c_str(),
                data.stringData().c_str());
  if (data.dataPath() == "/" || data.dataPath() == "/command") {
    String cmd = data.stringData();
    cmd.replace("\"", "");
    cmd.trim();
    if (cmd == "OPEN") { Serial.println("Cmd: OPEN"); unlockDoor(); }
    else if (cmd == "CLOSE") { Serial.println("Cmd: CLOSE"); waitingForAutoLock = false; lockDoor(); }
  }
}

void onStreamTimeout(bool timeout) {
  if (timeout) { Serial.println("Stream timeout"); streamActive = false; }
}

bool startStream() {
  Serial.println("Starting Firebase Stream...");
  streamData.setBSSLBufferSize(4096, 1024);
  streamData.setResponseSize(2048);
  streamData.keepAlive(30, 30, 1);
  String path = "/locks/" + String(LOCK_ID) + "/command";
  if (Firebase.RTDB.beginStream(&streamData, path.c_str())) {
    Firebase.RTDB.setStreamCallback(&streamData, onStreamData, onStreamTimeout);
    streamActive = true;
    Serial.println("Stream connected");
    return true;
  }
  Serial.printf("Stream error: %s\n", streamData.errorReason().c_str());
  return false;
}

// ===================== FIREBASE HELPERS =====================
bool checkIfAlreadyActivated() {
  String path1 = "/locks/" + String(LOCK_ID) + "/activated";
  if (Firebase.RTDB.getBool(&outputData, path1)) {
    if (outputData.boolData()) return true;
  }
  String path2 = "/locks/" + String(LOCK_ID) + "/owner";
  if (Firebase.RTDB.getString(&outputData, path2)) {
    String v = outputData.stringData();
    v.replace("\"", "");
    if (v.length() > 0 && v != "null") {
      Firebase.RTDB.setBool(&outputData, "/locks/" + String(LOCK_ID) + "/activated", true);
      return true;
    }
  }
  return false;
}

void loadAuthorizedBeacons() {
  String path = "/locks/" + String(LOCK_ID) + "/authorizedBeacons";
  
  if (Firebase.RTDB.getJSON(&outputData, path)) {
    FirebaseJson &json = outputData.jsonObject();
    authorizedBeacons.clear(); // Lista kiürítése frissítés előtt
    
    // Végigmegyünk a JSON összes elemén (felhasználókon)
    size_t count = json.iteratorBegin();
    String key, value;
    int type;
    for (size_t i = 0; i < count; i++) {
      json.iteratorGet(i, type, key, value);
      value.replace("\"", "");
      value.toLowerCase();
      if (value.length() > 0 && value != "null") {
        authorizedBeacons.push_back(value);
      }
    }
    json.iteratorEnd();
    Serial.printf("Betoltve %d db engedelyezett kulcs.\n", authorizedBeacons.size());
  } else {
    Serial.println("Nem talalhatok engedelyezett kulcsok.");
  }
}

bool registerOwnerInFirebase(String userUID, String beaconUUID) {
  String lp = "/locks/" + String(LOCK_ID);
  bool ok = true;
  ok &= Firebase.RTDB.setString(&outputData, lp + "/owner", userUID);
  ok &= Firebase.RTDB.setBool(&outputData,   lp + "/activated", true);
  ok &= Firebase.RTDB.setString(&outputData, lp + "/authorizedBeacons/" + userUID, beaconUUID);
  ok &= Firebase.RTDB.setString(&outputData, lp + "/status", "LOCKED");
  ok &= Firebase.RTDB.setString(&outputData, lp + "/command", "NONE");
  ok &= Firebase.RTDB.setString(&outputData, lp + "/name", "SmartLock");
  ok &= Firebase.RTDB.setString(&outputData, lp + "/macAddress",
          String(NimBLEDevice::getAddress().toString().c_str()));
  String pp = "/permissions/" + String(LOCK_ID) + "/" + userUID;
  ok &= Firebase.RTDB.setString(&outputData, pp + "/role", "owner");
  ok &= Firebase.RTDB.setString(&outputData, pp + "/grantedBy", "ESP32_ACTIVATION");
  ok &= Firebase.RTDB.setInt(&outputData, pp + "/grantedAt", (int)(millis()/1000));
  Serial.printf("Registration: %s\n", ok ? "OK" : "FAIL");
  return ok;
}

// ===================== BLE INIT =====================
void initBLE() {
  NimBLEDevice::init("SmartLock");
  NimBLEDevice::createServer();
  Serial.println("BLE initialized");
}

// ===================== BEACON (normal mode only) =====================
void startBeaconAdvertising() {
  NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
  std::string beaconData = "";
  beaconData += (char)0x4C; beaconData += (char)0x00;
  beaconData += (char)0x02; beaconData += (char)0x15;
  std::string uuid = "54c136ce1aca407fb5be9cf2a9cd69f0";
  for (int i = 0; i < (int)uuid.length(); i += 2)
    beaconData += (char)strtol(uuid.substr(i, 2).c_str(), NULL, 16);
  beaconData += (char)0x00; beaconData += (char)0x01;
  beaconData += (char)0x00; beaconData += (char)0x01;
  beaconData += (char)0xC5;
  NimBLEAdvertisementData advData;
  advData.setManufacturerData(beaconData);
  pAdv->setAdvertisementData(advData);
  pAdv->start();
  Serial.println("iBeacon started");
}

// ===================== BLE SCANNER CONTROL =====================
void initScanner() {
  Serial.println("Initializing BLE scanner...");
  Serial.printf("Heap before scanner: %d\n", ESP.getFreeHeap());

  NimBLEDevice::getAdvertising()->stop();
  delay(500);
  NimBLEDevice::deinit(true);
  delay(500);
  NimBLEDevice::init("SmartLock");
  delay(200);

  pScan = NimBLEDevice::getScan();
  // ScanCB is set below after class definition — forward declared
  pScan->setActiveScan(true);
  pScan->setInterval(100);
  pScan->setWindow(100);
  pScan->setMaxResults(0);
  scannerInitialized = true;

  Serial.printf("Heap after scanner: %d\n", ESP.getFreeHeap());
  Serial.println("Scanner initialized (not running yet)");
}

void startScanning() {
  if (!pScan) return;
  bool ok = pScan->start(0);
  Serial.printf("BLE scan started: %s\n", ok ? "YES" : "NO");
  scannerMode = true;
  scanRunning = true;
  rssiWindowReset();
}

void stopScanning() {
  if (pScan && scanRunning) {
    pScan->stop();
    Serial.println("BLE scan stopped");
  }
  scannerMode = false;
  scanRunning = false;
  phoneNearby = false;
  phoneWasNearby = false;
  rssiWindowReset();
}

// ===================== BLE SCAN CALLBACK =====================
class ScanCB : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* dev) override {
    if (!dev->haveServiceData() && !dev->haveManufacturerData()) return;

    if (dev->haveServiceData()) {
      int count = dev->getServiceDataCount();
      for (int s = 0; s < count; s++) {
        NimBLEUUID svcUUID = dev->getServiceDataUUID(s);
        if (svcUUID.equals(NimBLEUUID((uint16_t)0xABCD))) {
          std::string sd = dev->getServiceData(s);
          if (sd.length() >= 16) {
            String found = "";
            for (int i = 0; i < 16; i++) {
              char h[3]; sprintf(h, "%02x", (uint8_t)sd[i]); found += h;
            }
            bool isAuthorized = false;
            for (String u : authorizedBeacons) {
              if (found.equalsIgnoreCase(u)) {
                isAuthorized = true;
                break; // Megtaláltuk, nem kell tovább keresni
              }
            }

            if (isAuthorized) {
              int rssi = dev->getRSSI();
              rssiWindowAdd(rssi);
              int avg = rssiWindowAvg();
              if (avg > bleRssiThreshold && rssiSampleCount >= 3) {
                phoneNearby = true;
                lastSeenPhone = millis();
              }
            }
          }
          return;
        }
      }
    }
    if (dev->haveManufacturerData()) {
      std::string m = dev->getManufacturerData();
      if (m.length() < 25) return;
      if ((uint8_t)m[0]!=0x4C||(uint8_t)m[1]!=0x00) return;
      if ((uint8_t)m[2]!=0x02||(uint8_t)m[3]!=0x15) return;
      String found = "";
      for (int i = 4; i < 20; i++) {
        char h[3]; sprintf(h, "%02x", (uint8_t)m[i]); found += h;
      }
      bool isAuthorized = false;
      for (String u : authorizedBeacons) {
        if (found.equalsIgnoreCase(u)) {
          isAuthorized = true;
          break; // Megtaláltuk, nem kell tovább keresni
        }
      }

      if (isAuthorized) {
        int rssi = dev->getRSSI();
        rssiWindowAdd(rssi);
        int avg = rssiWindowAvg();
        if (avg > bleRssiThreshold && rssiSampleCount >= 3) {
          phoneNearby = true;
          lastSeenPhone = millis();
        }
      }
    }
  }
  void onScanEnd(const NimBLEScanResults& r, int reason) override { scanRunning = false; }
};

// ===================== ACTIVATION CALLBACK =====================
class ActivationCB : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& ci) override {
    String received = String(pChar->getValue().c_str());
    Serial.printf("Activation received: %s\n", received.c_str());
    int sep = received.indexOf('|');
    if (sep <= 0) { Serial.println("Invalid format!"); return; }

    ownerUID = received.substring(0, sep);
    authorizedBeaconUUID = received.substring(sep + 1);
    authorizedBeaconUUID.toLowerCase();
    activationDataReceived = true;

    Serial.println("Data saved. Will process in loop...");
  }
};

void startActivationMode() {
  Serial.println("\n=== ACTIVATION MODE ===");
  activationMode = true;

  NimBLEDevice::getAdvertising()->stop();
  delay(200);

  NimBLEServer* srv = NimBLEDevice::getServer();
  NimBLEService* svc = srv->createService(ACTIVATION_SERVICE_UUID);
  NimBLECharacteristic* wc = svc->createCharacteristic(ACTIVATION_CHAR_UUID, NIMBLE_PROPERTY::WRITE);
  wc->setCallbacks(new ActivationCB());
  NimBLECharacteristic* sc = svc->createCharacteristic(ACTIVATION_STATUS_CHAR_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  sc->setValue("WAITING");
  svc->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->reset();
  adv->setName("SmartLock");
  adv->addServiceUUID(ACTIVATION_SERVICE_UUID);
  NimBLEAdvertisementData scanResp;
  scanResp.setName("SmartLock");
  adv->setScanResponseData(scanResp);
  adv->start();

  Serial.println("Advertising: name='SmartLock' + service UUID");
  Serial.println("Waiting for phone...");
}

// ===================== SETUP =====================
void setup() {
  //WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n========== SmartLock v2.0 ==========");

  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, HIGH);
  digitalWrite(PIN_LED, LOW);

  initBLE();

  WiFiManager wm;
  wm.setConfigPortalTimeout(180);
  Serial.println("Connecting to WiFi...");
  String apName = "SmartLock-" + String(LOCK_ID).substring(strlen(LOCK_ID) - 4);
  if (!wm.autoConnect(apName.c_str(), "smartlock123")) {
    delay(3000); ESP.restart();
  }
  Serial.printf("WiFi OK. IP: %s\n", WiFi.localIP().toString().c_str());

  config.api_key = API_KEY;
  config.database_url = DATABASE_URL;
  config.timeout.socketConnection = 10000;
  config.timeout.serverResponse = 8000;
  outputData.setBSSLBufferSize(2048, 512);
  outputData.setResponseSize(1024);
  auth.user.email = DEVICE_EMAIL;
  auth.user.password = DEVICE_PASSWORD;
  config.token_status_callback = authTokenCallback;
  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);

  Serial.print("Authenticating");
  int w = 0;
  while (!isAuthenticated && w < 30) { Serial.print("."); delay(1000); w++; }
  Serial.println();
  if (!isAuthenticated) { delay(3000); ESP.restart(); }
  Serial.println("Firebase OK.");

  isActivated = checkIfAlreadyActivated();

  if (!isActivated) {
    startActivationMode();
  } else {
    Serial.println("Lock activated. Loading...");
    loadAuthorizedBeacons();

    // Load RSSI threshold from Firebase
    String rssiPath = "/locks/" + String(LOCK_ID) + "/rssiThreshold";
    if (Firebase.RTDB.getInt(&outputData, rssiPath)) {
      bleRssiThreshold = outputData.intData();
    }
    Serial.printf("RSSI threshold: %d\n", bleRssiThreshold);

    // Check if proximity was left enabled
    String proxPath = "/locks/" + String(LOCK_ID) + "/bleProximityEnabled";
    if (Firebase.RTDB.getBool(&outputData, proxPath)) {
      bleProximityEnabled = outputData.boolData();
    }
    Serial.printf("BLE Proximity: %s\n", bleProximityEnabled ? "ON" : "OFF");

    startBeaconAdvertising();

    Firebase.RTDB.setString(&outputData, "/locks/" + String(LOCK_ID) + "/status", "LOCKED");
    Firebase.RTDB.setString(&outputData, "/locks/" + String(LOCK_ID) + "/command", "NONE");

    Serial.printf("Free heap before stream: %d\n", ESP.getFreeHeap());
    startStream();
    Serial.printf("Free heap after stream: %d\n", ESP.getFreeHeap());

    Serial.println("=== READY ===");
  }

  Serial.printf("BLE MAC: %s\n", NimBLEDevice::getAddress().toString().c_str());
}

// ===================== LOOP =====================
void loop() {

  // ---- ACTIVATION MODE ----
  if (activationMode) {
    if (millis() - lastLedBlink > 300) {
      lastLedBlink = millis(); ledState = !ledState;
      digitalWrite(PIN_LED, ledState);
    }

    if (activationDataReceived) {
      activationDataReceived = false;
      Serial.println("Processing activation in loop...");
      Serial.printf("Owner: %s\n", ownerUID.c_str());
      Serial.printf("Beacon: %s\n", authorizedBeaconUUID.c_str());

      bool ok = registerOwnerInFirebase(ownerUID, authorizedBeaconUUID);

      NimBLEService* svc = NimBLEDevice::getServer()->getServiceByUUID(ACTIVATION_SERVICE_UUID);
      if (svc) {
        NimBLECharacteristic* sc = svc->getCharacteristic(ACTIVATION_STATUS_CHAR_UUID);
        if (sc) {
          String response = ok ? ("OK|" + String(LOCK_ID)) : "FAIL";
          sc->setValue(response.c_str());
          sc->notify();
          Serial.printf("Sent BLE response: %s\n", response.c_str());
        }
      }

      if (ok) {
        activationComplete = true;
        Serial.println("=== ACTIVATION OK ===");
      }
    }

    if (activationComplete) {
      delay(1000);
      Serial.println("Switching to normal mode...");
      activationMode = false;
      isActivated = true;
      digitalWrite(PIN_LED, LOW);

      NimBLEDevice::getAdvertising()->stop();
      delay(200);

      startStream();
      Serial.println("=== NORMAL MODE ===");
    }

    delay(10);
    return;
  }

  // ---- BLE PROXIMITY ON/OFF CONTROL ----
  if (bleProximityEnabled && !bleProximityWasEnabled) {
    // Just turned ON
    Serial.println(">>> BLE Proximity ENABLED by app");
    if (!scannerInitialized) {
      // First time: init scanner (deinit/reinit BLE)
      NimBLEDevice::getAdvertising()->stop();
      delay(500);
      NimBLEDevice::deinit(true);
      delay(500);
      NimBLEDevice::init("SmartLock");
      delay(200);

      pScan = NimBLEDevice::getScan();
      pScan->setScanCallbacks(new ScanCB(), false);
      pScan->setActiveScan(true);
      pScan->setInterval(100);
      pScan->setWindow(100);
      pScan->setMaxResults(0);
      scannerInitialized = true;
      Serial.println("Scanner initialized");
    }
    startScanning();
    bleProximityWasEnabled = true;
  }
  if (!bleProximityEnabled && bleProximityWasEnabled) {
    // Just turned OFF
    Serial.println(">>> BLE Proximity DISABLED by app");
    stopScanning();
    bleProximityWasEnabled = false;
  }

  // ---- BLE Proximity ----
  if (scannerMode && authorizedBeacons.size() > 0) {
    // Restart scan if it stopped
    if (!scanRunning && (millis() - lastScanStart > 6000)) {
      Serial.println("Restarting BLE scan...");
      bool started = pScan->start(0);
      if (started) scanRunning = true;
      lastScanStart = millis();
    }

    // Phone approaching → UNLOCK (with cooldown)
    if (phoneNearby && !phoneWasNearby) {
      unsigned long now = millis();
      if (now - lastBleUnlockTime > BLE_UNLOCK_COOLDOWN) {
        Serial.println(">>> Phone approaching -> UNLOCK");
        unlockDoor();
        lastBleUnlockTime = now;

        // Set method flag so app can log it
        Firebase.RTDB.setString(&outputData,
          "/locks/" + String(LOCK_ID) + "/lastUnlockMethod", "AUTO_BLE");

      } else {
        Serial.println(">>> Phone nearby but cooldown active, skipping unlock");
      }
      phoneWasNearby = true;
    }

    // Phone left → LOCK
    if (phoneWasNearby && (millis() - lastSeenPhone > BLE_TIMEOUT)) {
      phoneNearby = false;
      phoneWasNearby = false;
      rssiWindowReset();
      Serial.println("<<< Phone left -> LOCK");
      lockDoor();
    }
  }

  // ---- Button ----
  if (digitalRead(PIN_BUTTON) == LOW && millis() - lastButtonPress > 1000) {
    Serial.println("Button");
    if (isLocked) unlockDoor(); else lockDoor();
    lastButtonPress = millis();
  }

  // ---- Auto-lock ----
  if (waitingForAutoLock && !phoneNearby && (millis() - autoLockTimer > AUTO_LOCK_DELAY)) {
    Serial.println("Auto-lock");
    lockDoor();
  }

  // ---- Firebase write ----
  if ((pendingStatusWrite || pendingCommandClear) &&
      isAuthenticated && (millis() - lastWriteAttempt > WRITE_INTERVAL)) {
    lastWriteAttempt = millis();
    if (pendingStatusWrite) {
      if (Firebase.RTDB.setString(&outputData,
            "/locks/" + String(LOCK_ID) + "/status", pendingStatus)) {
        pendingStatusWrite = false;
        Serial.printf("Status: %s\n", pendingStatus.c_str());
      }
    }
    if (!pendingStatusWrite && pendingCommandClear) {
      if (Firebase.RTDB.setString(&outputData,
            "/locks/" + String(LOCK_ID) + "/command", "NONE")) {
        pendingCommandClear = false;
      }
    }
  }

  // ---- Health + Firebase polling (every 10s) ----
  if (millis() - lastHealthCheck > 10000) {
    lastHealthCheck = millis();

    // Firebase kulcslista frissítése 10 másodpercenként
    loadAuthorizedBeacons();

    // Poll bleProximityEnabled from Firebase
    String proxPath = "/locks/" + String(LOCK_ID) + "/bleProximityEnabled";
    if (Firebase.RTDB.getBool(&outputData, proxPath)) {
      bool newVal = outputData.boolData();
      if (newVal != bleProximityEnabled) {
        bleProximityEnabled = newVal;
        Serial.printf("BLE Proximity changed: %s\n", bleProximityEnabled ? "ON" : "OFF");
      }
    }

    // Refresh RSSI threshold
    String rssiPath = "/locks/" + String(LOCK_ID) + "/rssiThreshold";
    if (Firebase.RTDB.getInt(&outputData, rssiPath)) {
      int newVal = outputData.intData();
      if (newVal != bleRssiThreshold) {
        bleRssiThreshold = newVal;
        Serial.printf("RSSI threshold updated: %d\n", bleRssiThreshold);
      }
    }

    Serial.printf("Health | WiFi:%s FB:%s Stream:%s Lock:%s Phone:%s Scan:%s Prox:%s RSSI:%d Heap:%d\n",
      WiFi.status()==WL_CONNECTED?"OK":"ERR",
      Firebase.ready()?"OK":"ERR",
      streamActive?"OK":"ERR",
      isLocked?"LOCKED":"OPEN",
      phoneNearby?"NEAR":"FAR",
      scannerMode?"ON":"OFF",
      bleProximityEnabled?"ON":"OFF",
      bleRssiThreshold,
      ESP.getFreeHeap());

    if (WiFi.status() != WL_CONNECTED) { delay(3000); ESP.restart(); }
    if (!streamActive && isActivated) { delay(3000); ESP.restart(); }
  }

  // ---- 6h restart ----
  if (millis() > 6UL*60UL*60UL*1000UL) { delay(1000); ESP.restart(); }
  delay(10);
}