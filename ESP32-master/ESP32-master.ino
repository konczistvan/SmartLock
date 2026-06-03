// ================================================
// SmartLock v6.0 - MASTER (Dinamikus Aktiválással)
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

// --- AKTIVÁCIÓS BLE UUID-k (Egyeznie kell az Android appal!) ---
#define ACTIVATION_SERVICE_UUID "12345678-1234-1234-1234-123456789abc"
#define ACTIVATION_CHAR_RX_UUID "12345678-1234-1234-1234-123456789abd"
#define ACTIVATION_CHAR_TX_UUID "12345678-1234-1234-1234-123456789abe"

// A gomb MAC címe maradhat statikus, ha nem akarod azt is párosítani
uint8_t slaveMac[] = {0x0C, 0xDC, 0x7E, 0x5D, 0x07, 0x6C};

#define PIN_RELAY  13
#define PIN_LED    2
#define PIN_BUTTON 0

FirebaseData streamData;
FirebaseData outputData;
FirebaseAuth auth;
FirebaseConfig config;

String lockID = ""; // Dinamikusan lesz generálva!
bool isLocked          = true;
bool isAuthenticated   = false;
bool isActivated       = false;
bool inActivationMode  = false; // Jelzi, ha épp párosításra vár

std::vector<String> authorizedBeacons;
int bleRssiThreshold   = -75;
NimBLEScan* pScan = nullptr;
bool scannerMode   = false;
bool scanRunning   = false;
unsigned long lastScanStart = 0;
bool bleProximityEnabled = false;
bool bleProximityWasEnabled = false;

// RSSI / EMA változók
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

// IDŐZÍTÉSEK
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

// ==========================================
// SEGÉDFÜGGVÉNYEK
// ==========================================
String getMacFormatted() {
  String mac = WiFi.macAddress();
  mac.replace(":", "");
  mac.toUpperCase();
  return "LOCK_" + mac;
}

void authTokenCallback(token_info_t info) {
  if (info.status == token_status_ready) isAuthenticated = true;
}

// ==========================================
// BLE AKTIVÁCIÓS CALLBACKS
// ==========================================
class ActivationCallbacks: public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pChar) {
        std::string rxValue = pChar->getValue();
        if (rxValue.length() > 0) {
            String payload = String(rxValue.c_str());
            Serial.println("[AKTIVÁCIÓ] Kapott adat: " + payload);
            
            // Payload formátum az Androidból: uid|beaconUUID
            int separatorIdx = payload.indexOf('|');
            if (separatorIdx > 0) {
                String uid = payload.substring(0, separatorIdx);
                String beaconUUID = payload.substring(separatorIdx + 1);
                
                Serial.println("Készítem a Firebase bejegyzést...");
                
                FirebaseJson json;
                json.set("activated", true);
                json.set("owner", uid);
                json.set("beaconUUID", beaconUUID);
                json.set("status", "LOCKED");
                json.set("command", "NONE");
                json.set("macAddress", WiFi.macAddress());
                json.set("bleProximityEnabled", false);
                json.set("rssiThreshold", -80);

                // Csomópont létrehozása a Firebase-ben
                if (Firebase.RTDB.setJSON(&outputData, "/locks/" + lockID, &json)) {
                    Serial.println("[OK] Firebase létrehozva! Válasz küldése a telefonnak...");
                    
                    // Android app várja az OK|LOCK_ID választ
                    String response = "OK|" + lockID;
                    pTxChar->setValue(response.c_str());
                    pTxChar->notify();
                    
                    delay(2000);
                    Serial.println("Újraindítás normál módba...");
                    ESP.restart(); // Újraindítjuk, hogy normálisan töltsön be
                } else {
                    Serial.println("[HIBA] Nem sikerült írni a Firebase-be.");
                    pTxChar->setValue("FAIL");
                    pTxChar->notify();
                }
            }
        }
    }
};

void startActivationMode() {
  inActivationMode = true;
  Serial.println("=== AKTIVÁCIÓS MÓD INDÍTÁSA ===");
  Serial.println("Nyisd meg az appban a 'Activate New Lock' menüt!");
  
  NimBLEDevice::init("SmartLock");
  NimBLEServer *pServer = NimBLEDevice::createServer();
  NimBLEService *pService = pServer->createService(ACTIVATION_SERVICE_UUID);
  
  NimBLECharacteristic *pRxChar = pService->createCharacteristic(
                                     ACTIVATION_CHAR_RX_UUID,
                                     NIMBLE_PROPERTY::WRITE
                                 );
  pRxChar->setCallbacks(new ActivationCallbacks());
  
  pTxChar = pService->createCharacteristic(
                       ACTIVATION_CHAR_TX_UUID,
                       NIMBLE_PROPERTY::NOTIFY
                   );
                   
  pService->start();
  NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(ACTIVATION_SERVICE_UUID);
  pAdvertising->start();
}

// ==========================================
// MŰKÖDÉSI FUNKCIÓK (Ajtó, Firebase, stb.)
// ==========================================
// ... (Ide jön a pushMeasurement, onDataRecv, lockDoor, unlockDoor, onStreamData pontosan úgy, ahogy volt)
// Helytakarékosság miatt ezeket megtartod az eredetiből, a lockID változót használva!
void lockDoor() {
  digitalWrite(PIN_RELAY, HIGH);
  digitalWrite(PIN_LED, LOW);
  isLocked = true;
  Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/status", "LOCKED");
  Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/command", "NONE");
}

void unlockDoor(String nyitasiMod = "MANUAL") {
  digitalWrite(PIN_RELAY, LOW);
  digitalWrite(PIN_LED, HIGH);
  isLocked = false;
  Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/status", "UNLOCKED");
  Firebase.RTDB.setString(&outputData, "/locks/" + lockID + "/command", "NONE");
}

void onStreamData(FirebaseStream data) {
  if (data.dataPath() == "/" || data.dataPath() == "/command") {
    String cmd = data.stringData();
    cmd.replace("\"", ""); cmd.trim();
    if (cmd == "OPEN") unlockDoor("MANUAL");
    else if (cmd == "CLOSE") lockDoor();
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
  }
}

// BLE SZKENNER (Normál mód)
class ScanCB : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* dev) override {
    // Eredeti szkenner kód marad
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
};

void startScanning() {
  if (!pScan) return;
  pScan->start(0);
  scanRunning = true;
}

// ==========================================
// SETUP
// ==========================================
void setup() {
  Serial.begin(115200);
  delay(1000);
  
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, HIGH);

  // 1. WiFiManager Csatlakozás
  WiFiManager wm;
  // wm.resetSettings(); // Csak ha törölni akarod a mentett jelszót
  Serial.println("Csatlakozás WiFi-hez...");
  
  if (!wm.autoConnect("SmartLock_Setup", "12345678")) {
    Serial.println("Nem sikerült csatlakozni. Újraindítás...");
    delay(3000);
    ESP.restart();
  }
  Serial.println("WiFi OK!");

  // 2. Lock ID Generálása
  lockID = getMacFormatted();
  Serial.println("ESP32 LOCK ID: " + lockID);

  // 3. Firebase bejelentkezés
  config.api_key = API_KEY;
  config.database_url = DATABASE_URL;
  auth.user.email = DEVICE_EMAIL;
  auth.user.password = DEVICE_PASSWORD;
  config.token_status_callback = authTokenCallback;
  Firebase.begin(&config, &auth);

  int w = 0; 
  while (!isAuthenticated && w < 30) { delay(500); Serial.print("."); w++; }
  Serial.println();

  // 4. Aktivációs Állapot Lekérdezése
  String path = "/locks/" + lockID + "/activated";
  if (Firebase.RTDB.getBool(&outputData, path)) {
      isActivated = outputData.boolData();
  } else {
      isActivated = false; // Ha nincs még a Firebase-ben
  }

  if (!isActivated) {
    startActivationMode();
  } else {
    // === NORMÁL MŰKÖDÉS INDÍTÁSA ===
    Serial.println("Ajtózár aktiválva! Normál működés indul.");
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
    
    Firebase.RTDB.beginStream(&streamData, ("/locks/" + lockID + "/command").c_str());
    Firebase.RTDB.setStreamCallback(&streamData, onStreamData, [](bool timeout){});
  }
}

// ==========================================
// LOOP
// ==========================================
void loop() {
  unsigned long now = millis();

  // === HA AKTIVÁCIÓS MÓDBAN VAGYUNK ===
  if (inActivationMode) {
    // Gyorsan villogtatjuk a LED-et
    static unsigned long lastBlink = 0;
    if (now - lastBlink > 100) {
      digitalWrite(PIN_LED, !digitalRead(PIN_LED));
      lastBlink = now;
    }
    return; // KILÉPÜNK A LOOP-ból, nem futtatjuk a normál logikát!
  }

  // === NORMÁL MŰKÖDÉS LOOP ===
  
  if (bleProximityEnabled && !scanRunning) startScanning();
  if (!bleProximityEnabled && scanRunning) pScan->stop();

  // BLE Nyitás logika... (ugyanaz mint volt)
  if (scanRunning && authorizedBeacons.size() > 0) {
    if (now - lastEmaTick >= EMA_TICK_MS) {
      lastEmaTick = now;
      if (now - rawMasterTime > 2500) { emaMaster -= 2.0f; if(emaMaster < -100.0f) emaMaster = -100.0f; }
      else if (haveMasterSampleThisWindow) {
          emaMaster = EMA_ALPHA * (float)bestMasterRssiThisWindow + (1.0f - EMA_ALPHA) * emaMaster;
          haveMasterSampleThisWindow = false; bestMasterRssiThisWindow = -100;
      }
      if (spatialCooldownActive && emaMaster <= SPATIAL_RESET_RSSI) { spatialCooldownActive = false; }
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

  // Belső fizikai gomb
  static unsigned long lastBtn = 0;
  if (digitalRead(PIN_BUTTON) == LOW && now - lastBtn > 1000) {
    if (isLocked) unlockDoor("MANUAL_INTERNAL"); else lockDoor();
    lastBtn = now;
  }
  
  delay(20);
}