// ================================================
// SmartLock - MASTER MERESI MOD (v1)
// Csak BLE szken + Serial log + ESP-NOW gombmarker.
// NINCS WiFi/Firebase - tiszta BLE radio-ido a maximalis mintaszamert.
//
// Log format:
//   RSSI,<millis>,<dBm>      -> nyers RSSI minta
//   BUTTON,<millis>,0        -> slave-gomb (foldigazsag-jel)
//   STAT,<millis>,<samples/s>-> elo ellenorzes 5 mp-enkent
//   # ...                    -> komment/info sorok
// ================================================

#include <Arduino.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include <esp_now.h>
#include <NimBLEDevice.h>

// --- KONFIGURACIO ---
// Pastold be a sajat beacon UUID-d (a kotlin appbol, kis betuvel, kotojelek nelkul,
// pontosan 32 hex karakter). Hagyd uresen ("") ha BARMELY 0xABCD service-szel
// hirdeto eszkozt szeretned logolni - hasznos ha meg nem tudod, mi a UUID-d.
const char* TARGET_BEACON_UUID = "";  // pl. "a1b2c3d4e5f6..."

// Az ESP-NOW gomb-egyseg (slave) MAC cime. A gombnyomast logoljuk markerkent.
// Ha nem hasznalod a gombot, ezt nyugodtan hagyhatod.
uint8_t slaveMac[] = {0x0C, 0xDC, 0x7E, 0x5D, 0x07, 0x6C};

// Fix WiFi csatorna ESP-NOW-hoz (a slave hopping-gel megtalalja).
#define ESP_NOW_CHANNEL  1

// --- Globalisok ---
NimBLEScan* pScan = nullptr;
typedef struct { uint8_t cmd; } MasterCmd;

unsigned long lastStatTime    = 0;
unsigned long sampleCountWin  = 0;
unsigned long sampleCountTot  = 0;

// ================================================
// ESP-NOW: gombnyomas-marker fogadasa + azonnali ACK
// ================================================
void onDataRecv(const uint8_t* mac, const uint8_t* data, int len) {
  if (len == sizeof(MasterCmd)) {
    MasterCmd inc;
    memcpy(&inc, data, sizeof(inc));
    if (inc.cmd == 99) {
      Serial.printf("BUTTON,%lu,0\n", millis());
      // ACK kuldese, hogy a slave LED-je zoldet villantson
      MasterCmd ack; ack.cmd = 100;
      esp_now_send(slaveMac, (uint8_t*)&ack, sizeof(ack));
    }
  }
}

// ================================================
// BLE Scan Callback
// ================================================
class ScanCB : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* dev) override {
    if (!dev->haveServiceData()) return;
    int count = dev->getServiceDataCount();
    for (int s = 0; s < count; s++) {
      if (!dev->getServiceDataUUID(s).equals(NimBLEUUID((uint16_t)0xABCD))) continue;
      std::string sd = dev->getServiceData(s);
      if (sd.length() < 16) continue;

      // 16 byte hex stringgé alakitva osszevetes
      String found = "";
      for (int i = 0; i < 16; i++) {
        char h[3]; sprintf(h, "%02x", (uint8_t)sd[i]); found += h;
      }

      bool match = true;
      if (strlen(TARGET_BEACON_UUID) > 0) {
        match = found.equalsIgnoreCase(TARGET_BEACON_UUID);
      }
      if (match) {
        int rssi = dev->getRSSI();
        Serial.printf("RSSI,%lu,%d\n", millis(), rssi);
        sampleCountWin++;
        sampleCountTot++;
      }
      break;
    }
  }
  void onScanEnd(const NimBLEScanResults& r, int reason) override {
    // A loop ujrainditja, ha kellene
  }
};

// ================================================
// SETUP
// ================================================
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("# ===========================================");
  Serial.println("# SmartLock MERESI MOD - tiszta BLE log");
  Serial.println("# Format: TIPUS,t_ms,ertek");
  Serial.println("# RSSI,<millis>,<dBm>  |  BUTTON,<millis>,0  |  STAT,<millis>,<samples/s>");
  Serial.println("# ===========================================");

  // WiFi STA mod ESP-NOW-hoz, de NEM csatlakozunk semmihez (nincs AP, nincs forgalom)
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  WiFi.setSleep(true);  // Engedi a radiot BLE-re kapcsolni a holt WiFi idoben
  esp_wifi_set_channel(ESP_NOW_CHANNEL, WIFI_SECOND_CHAN_NONE);

  if (esp_now_init() == ESP_OK) {
    esp_now_register_recv_cb(onDataRecv);
    esp_now_peer_info_t peer = {};
    memcpy(peer.peer_addr, slaveMac, 6);
    peer.channel = ESP_NOW_CHANNEL;
    peer.encrypt = false;
    esp_now_add_peer(&peer);
    Serial.printf("# ESP-NOW kesz, csatorna=%d\n", ESP_NOW_CHANNEL);
  } else {
    Serial.println("# [FIGYELEM] ESP-NOW init sikertelen - a gombmarker nem fog mukodni");
  }

  // BLE szkenner inicializalasa
  NimBLEDevice::init("SmartLockMeasure");
  pScan = NimBLEDevice::getScan();
  pScan->setScanCallbacks(new ScanCB(), false);
  pScan->setActiveScan(false);   // PASSZIV szken - kevesebb radio idot eszik (nincs scan request)
  pScan->setInterval(100);
  pScan->setWindow(100);          // 100% duty cycle - folyamatos hallgatas
  pScan->setDuplicateFilter(false);
  pScan->start(0);                // Folyamatos szken

  Serial.println("# BLE szken elindult. Aktivald a telefon hirdetest es kezdj merni.");
  if (strlen(TARGET_BEACON_UUID) == 0) {
    Serial.println("# [INFO] UUID szuro KIKAPCSOLVA - minden 0xABCD beacon logolva lesz.");
  } else {
    Serial.printf("# [INFO] UUID szuro: %s\n", TARGET_BEACON_UUID);
  }
  lastStatTime = millis();
}

// ================================================
// LOOP
// ================================================
void loop() {
  unsigned long now = millis();

  // 5 mp-enkent kiir egy STAT sort - elo ellenorzes meres kozben
  if (now - lastStatTime >= 5000) {
    float rate = (float)sampleCountWin / 5.0f;
    Serial.printf("STAT,%lu,%.1f\n", now, rate);
    sampleCountWin = 0;
    lastStatTime = now;
  }

  // Biztonsagi ujrainditas, ha valamiert leallt volna
  if (!pScan->isScanning()) {
    pScan->start(0);
  }

  delay(50);
}
