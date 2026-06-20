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

// Activation BLE UUIDs (must match the Android app)
#define ACTIVATION_SERVICE_UUID "12345678-1234-1234-1234-123456789abc"
#define ACTIVATION_CHAR_RX_UUID "12345678-1234-1234-1234-123456789abd"
#define ACTIVATION_CHAR_TX_UUID "12345678-1234-1234-1234-123456789abe"

uint8_t slaveMac[] = {0x0C, 0xDC, 0x7E, 0x5D, 0x07, 0x6C};

#define PIN_RELAY  13
#define PIN_LED    2
#define PIN_BUTTON 0

FirebaseData streamData;
FirebaseData outputData;
FirebaseAuth auth;
FirebaseConfig config;

String lockID = ""; // Generated dynamically at startup
bool isLocked          = true;
bool isAuthenticated   = false;
bool isActivated       = false;
bool inActivationMode  = false; 

std::vector<String> authorizedBeacons;
int bleRssiThreshold   = -75;
NimBLEScan* pScan = nullptr;
bool scannerMode   = false;
bool scanRunning   = false;
unsigned long lastScanStart = 0;
bool bleProximityEnabled = false;
bool bleProximityWasEnabled = false;

// RSSI / EMA filter state
volatile int           rawMasterRssi      = -100;
volatile unsigned long rawMasterTime      = 0;
float emaMaster = -100.0f;
const float           EMA_ALPHA           = 0.35f;
const unsigned long EMA_TICK_MS           = 150;
unsigned long lastEmaTick = 0;
volatile int           bestMasterRssiThisWindow = -100;
volatile bool          haveMasterSampleThisWindow = false;

bool spatialCooldownActive = false;
const float SPATIAL_RESET_RSSI = -70.0f;

// Timers
unsigned long measureButtonTime = 0;
unsigned long measureBleUnlockTime = 0;
bool pendingButtonMeasure = false;
bool pendingBleMeasure = false;
const unsigned long MEASURE_TIMEOUT = 15000;

bool shouldUpload = false;
long uploadDuration = 0;
String uploadMethod = "";

typedef struct { uint8_t cmd; } MasterCmd;
NimBLECharacteristic* pTxChar = nullptr;

// Activation state (communication between callback and loop)
volatile bool activationDataReceived = false;
String pendingUid    = "";
String pendingBeacon = "";

// Stream -> loop signals.
// IMPORTANT: never call a Firebase operation from the stream callback (it tears
// down the SSL engine). Only set flags here; the real work runs in loop().
volatile bool reloadBeaconsRequested = false;
volatile bool pendingCmdProcess      = false;
String        pendingCmd             = "";

// Automatic re-lock
unsigned long unlockTime = 0;
const unsigned long AUTO_RELOCK_MS = 5000; // Re-locks by itself after 5 seconds

// Live telemetry (RSSI / sample count)
volatile unsigned long sampleCountWindow = 0; // Incremented by the callback on every valid sample
unsigned long lastTelemetry = 0;


// HELPER FUNCTIONS

String getMacFormatted() {
  String mac = WiFi.macAddress();
  mac.replace(":", "");
  mac.toUpperCase();
  return "LOCK_" + mac;
}

void authTokenCallback(token_info_t info) {
  if (info.status == token_status_ready) isAuthenticated = true;
}


// BLE ACTIVATION CALLBACKS
class ActivationCallbacks: public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo& connInfo) override {
        std::string rxValue = pChar->getValue();
        if (rxValue.length() > 0) {
            String payload = String(rxValue.c_str());
            Serial.println("[ACTIVATION] Received: " + payload);
            int separatorIdx = payload.indexOf('|');
            if (separatorIdx > 0) {
                pendingUid    = payload.substring(0, separatorIdx);
                pendingBeacon = payload.substring(separatorIdx + 1);
                activationDataReceived = true; // Processed by loop()
            }
        }
    }
};

void processActivation() {
  Serial.println("Building the Firebase entry...");

  FirebaseJson json;
  json.set("activated", true);
  json.set("owner", pendingUid);
  json.set("beaconUUID", pendingBeacon);
  json.set("status", "LOCKED");
  json.set("command", "NONE");
  json.set("macAddress", WiFi.macAddress());
  json.set("bleProximityEnabled", false);
  json.set("rssiThreshold", -80);

  if (Firebase.RTDB.setJSON(&outputData, "/locks/" + lockID, &json)) {
    Serial.println("[OK] Firebase created! Sending response to the phone...");
    String response = "OK|" + lockID;
    pTxChar->setValue(response.c_str());
    pTxChar->notify();
    delay(2000);
    Serial.println("Restarting into normal mode...");
    ESP.restart();
  } else {
    Serial.println("[ERROR] Failed to write to Firebase: " + outputData.errorReason());
    pTxChar->setValue("FAIL");
    pTxChar->notify();
  }
}

void startActivationMode() {
  inActivationMode = true;
  Serial.println("=== STARTING ACTIVATION MODE ===");
  Serial.println("Open the 'Activate New Lock' menu in the app!");

  NimBLEDevice::init("SmartLock");
  NimBLEServer *pServer = NimBLEDevice::createServer();
  NimBLEService *pService = pServer->createService(ACTIVATION_SERVICE_UUID);

  NimBLECharacteristic *pRxChar = pService->createCharacteristic(
                                     ACTIVATION_CHAR_RX_UUID, NIMBLE_PROPERTY::WRITE);
  pRxChar->setCallbacks(new ActivationCallbacks());

  pTxChar = pService->createCharacteristic(
                       ACTIVATION_CHAR_TX_UUID, NIMBLE_PROPERTY::NOTIFY);

  pService->start();

  NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->setName("SmartLock");
  pAdvertising->addServiceUUID(ACTIVATION_SERVICE_UUID);
  pAdvertising->start();

  Serial.println("BLE advertising started...");
}


// CORE FUNCTIONS (door, Firebase, etc.)
void lockDoor() {
  digitalWrite(PIN_RELAY, HIGH);
  digitalWrite(PIN_LED, LOW);
  isLocked = true;
  Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/status", "LOCKED");
  Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/command", "NONE");
}

void unlockDoor(String unlockMethod = "MANUAL") {
  digitalWrite(PIN_RELAY, LOW);
  digitalWrite(PIN_LED, HIGH);
  isLocked = false;
  unlockTime = millis(); // Start of the 5 s re-lock timer
  Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/status", "UNLOCKED");
  Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/lastUnlockMethod", unlockMethod);
  Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/command", "NONE");
}

// Process a received command (OPEN / CLOSE) - called from loop()!
void applyCommand(String cmd) {
  cmd.replace("\"", ""); cmd.trim();
  if (cmd == "OPEN") unlockDoor("MANUAL");
  else if (cmd == "CLOSE") lockDoor();
}


// SINGLE STREAM ON THE WHOLE /locks/<id> NODE
// The callback only reads / sets flags, it NEVER calls Firebase.
void onStreamData(FirebaseStream data) {
  String path = data.dataPath();

  if (path == "/") {
    FirebaseJson *json = data.to<FirebaseJson *>();
    if (json != nullptr) {
      FirebaseJsonData r;
      if (json->get(r, "bleProximityEnabled")) bleProximityEnabled = r.boolValue;
      if (json->get(r, "rssiThreshold"))        bleRssiThreshold    = r.intValue;
      if (json->get(r, "command")) { pendingCmd = r.stringValue; pendingCmdProcess = true; }
    }
    reloadBeaconsRequested = true;
    return;
  }

  if (path == "/command") {
    pendingCmd = data.to<String>();
    pendingCmdProcess = true; // Processed by loop() (not here!)
  } else if (path == "/bleProximityEnabled") {
    bleProximityEnabled = data.to<bool>();
    Serial.printf("[STREAM] bleProximityEnabled -> %s\n", bleProximityEnabled ? "true" : "false");
  } else if (path == "/rssiThreshold") {
    bleRssiThreshold = data.to<int>();
    Serial.printf("[STREAM] rssiThreshold -> %d\n", bleRssiThreshold);
  } else if (path.startsWith("/authorizedBeacons")) {
    reloadBeaconsRequested = true;
  }
}

void loadAuthorizedBeacons() {
  String path = "/locks/" + lockID + "/authorizedBeacons";
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
    Serial.printf("[BEACONS] Loaded: %d keys\n", (int)authorizedBeacons.size());
  }
}

// BLE SCANNER (normal mode)
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
                sampleCountWindow++; // Telemetry: number of valid samples measured
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
};

void startScanning() {
  if (!pScan) return;
  pScan->start(0);
  scanRunning = true;
}


// SETUP
void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, HIGH);

  // 1. WiFiManager connection
  WiFiManager wm;
  Serial.println("Connecting to WiFi...");
  if (!wm.autoConnect("SmartLock_Setup", "12345678")) {
    Serial.println("Connection failed. Restarting...");
    delay(3000);
    ESP.restart();
  }
  Serial.println("WiFi OK!");
  
  // The ESP32 has a single radio; WiFi+BLE coexistence relies on modem-sleep.

  // 2. Generate lock ID
  lockID = getMacFormatted();
  Serial.println("ESP32 LOCK ID: " + lockID);

  // 3. Firebase login + SSL/timeout tuning
  config.api_key = API_KEY;
  config.database_url = DATABASE_URL;
  auth.user.email = DEVICE_EMAIL;
  auth.user.password = DEVICE_PASSWORD;
  config.token_status_callback = authTokenCallback;
  config.timeout.serverResponse   = 10 * 1000;
  config.timeout.socketConnection = 10 * 1000;
  config.timeout.wifiReconnect    = 10 * 1000;

  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);

  // Smaller, fixed BearSSL buffers -> less RAM pressure, fewer SSL errors
  streamData.setBSSLBufferSize(4096, 1024);
  outputData.setBSSLBufferSize(2048, 1024);
  streamData.setResponseSize(4096);
  outputData.setResponseSize(2048);

  int w = 0;
  while (!isAuthenticated && w < 30) { delay(500); Serial.print("."); w++; }
  Serial.println();

  // 4. Query activation state
  String path = "/locks/" + lockID + "/activated";
  if (Firebase.RTDB.getBool(&outputData, path)) isActivated = outputData.boolData();
  else isActivated = false;

  if (!isActivated) {
    startActivationMode();
  } else {
    Serial.println("Lock activated! Starting normal operation.");
    NimBLEDevice::init("SmartLock");
    pScan = NimBLEDevice::getScan();
    pScan->setScanCallbacks(new ScanCB(), false);
    pScan->setActiveScan(true);
    pScan->setInterval(200);
    pScan->setWindow(40);

    loadAuthorizedBeacons();

    Firebase.RTDB.getInt(&outputData, "/locks/" + lockID + "/rssiThreshold");
    bleRssiThreshold = outputData.intData();
    Firebase.RTDB.getBool(&outputData, "/locks/" + lockID + "/bleProximityEnabled");
    bleProximityEnabled = outputData.boolData();

    // One stream on the whole lock node
    Firebase.RTDB.beginStream(&streamData, ("/locks/" + lockID).c_str());
    Firebase.RTDB.setStreamCallback(&streamData, onStreamData, [](bool timeout){});
  }
}


// LOOP
void loop() {
  unsigned long now = millis();

  // ACTIVATION MODE 
  if (inActivationMode) {
    if (activationDataReceived) {
      activationDataReceived = false;
      processActivation();
    }
    static unsigned long lastBlink = 0;
    if (now - lastBlink > 100) {
      digitalWrite(PIN_LED, !digitalRead(PIN_LED));
      lastBlink = now;
    }
    return;
  }

  // NORMAL OPERATION
  bool fbReady = Firebase.ready();

  // Process the stream-signalled command HERE (not in the callback!) -> no SSL crash
  if (fbReady && pendingCmdProcess) {
    pendingCmdProcess = false;
    applyCommand(pendingCmd);
  }

  // Reload beacon keys if the stream requested it
  if (fbReady && reloadBeaconsRequested) {
    reloadBeaconsRequested = false;
    loadAuthorizedBeacons();
  }

  // Scanner on/off according to bleProximityEnabled (kept live by the stream)
  if (bleProximityEnabled && !scanRunning) startScanning();
  if (!bleProximityEnabled && scanRunning) { pScan->stop(); scanRunning = false; }

  // AUTO RE-LOCK: re-locks 5 s after any unlock
  if (!isLocked && (now - unlockTime >= AUTO_RELOCK_MS)) {
    lockDoor();
  }

  // BLE unlock logic (with EMA filtering)
  if (scanRunning && authorizedBeacons.size() > 0) {
    if (now - lastEmaTick >= EMA_TICK_MS) {
      lastEmaTick = now;
      if (now - rawMasterTime > 2500) { emaMaster -= 2.0f; if(emaMaster < -100.0f) emaMaster = -100.0f; }
      else if (haveMasterSampleThisWindow) {
          emaMaster = EMA_ALPHA * (float)bestMasterRssiThisWindow + (1.0f - EMA_ALPHA) * emaMaster;
          haveMasterSampleThisWindow = false; bestMasterRssiThisWindow = -100;
      }
      if (spatialCooldownActive && emaMaster <= SPATIAL_RESET_RSSI) spatialCooldownActive = false;
    }

    static unsigned long lastBleUnlock = 0;
    if (emaMaster >= bleRssiThreshold && isLocked && !spatialCooldownActive) {
      if (now - lastBleUnlock > 5000) {
        unlockDoor("BLE");
        lastBleUnlock = now;
        spatialCooldownActive = true;
      }
    }
  }

  // LIVE TELEMETRY for the Settings page (only while scanning, max 1/s)
  static bool wasScanning = false;
  if (scanRunning) {
    if (fbReady && (now - lastTelemetry >= 1000)) {
      lastTelemetry = now;
      float hz = (float)sampleCountWindow; // 1 s window -> count = Hz
      sampleCountWindow = 0;
      String t = "EMA: " + String((int)emaMaster) + " dBm\n" +
                 "Raw: " + String(rawMasterRssi) + " dBm\n" +
                 "Rate: " + String(hz, 1) + " /s\n" +
                 "Threshold: " + String(bleRssiThreshold) + " dBm";
      Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/telemetry/live_debug", t);
    }
    wasScanning = true;
  } else if (wasScanning) {
    if (fbReady) {
      Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/telemetry/live_debug",
                              "Proximity scanning off\n(enable auto-unlock and move in range)");
    }
    wasScanning = false;
  }

  // Internal physical button
  static unsigned long lastBtn = 0;
  if (digitalRead(PIN_BUTTON) == LOW && now - lastBtn > 1000) {
    if (isLocked) unlockDoor("MANUAL_INTERNAL"); else lockDoor();
    lastBtn = now;
  }

  delay(20);
}
