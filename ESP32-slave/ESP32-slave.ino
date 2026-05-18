// ================================================
// SmartLock v5.1 - SLAVE (Csatornavadász Mód)
// Funkciók:
//   - NINCS WiFi jelszó, nincs router függőség!
//   - Automatikusan megkeresi a Mastert az összes csatornán.
//   - Megjegyzi a sikeres csatornát az azonnali gombnyomáshoz.
// ================================================

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

uint8_t masterMac[] = {0xB8, 0xF8, 0x62, 0xE0, 0xBC, 0xBC};

#define PIN_MEASURE_BUTTON 32
#define PIN_LED 2

typedef struct {
  uint8_t cmd;
} MasterCmd;

// ================================================
// Gomb, ACK és Csatornavadász változók
// ================================================
volatile bool ackReceived     = false;
bool waitingForAck            = false;
unsigned long ackWaitStart    = 0;

int currentChannel            = 1;  // Kezdő csatorna
int retryCnt                  = 0;
const int MAX_RETRIES         = 40; // Kb. 3 teljes kör az 1-13 csatornákon
const unsigned long ACK_TIMEOUT = 100; // Nagyon gyors (100ms) ugrás a csatornák közt

unsigned long lastButtonPress = 0;

// ================================================
// ESP-NOW fogado (Csak ACK-t var)
// ================================================
void onDataRecv(const uint8_t* mac, const uint8_t* incomingData, int len) {
  if (len == sizeof(MasterCmd)) {
    MasterCmd inc;
    memcpy(&inc, incomingData, sizeof(inc));
    if (inc.cmd == 100) {
      ackReceived = true;
    }
  }
}

// ================================================
// Gombnyomás esemény küldése
// ================================================
void sendMeasureButton() {
  // Mindig beállítjuk az aktuális próbálkozás csatornáját
  esp_wifi_set_channel(currentChannel, WIFI_SECOND_CHAN_NONE);
  
  MasterCmd btnCmd;
  btnCmd.cmd = 99;
  esp_now_send(masterMac, (uint8_t*)&btnCmd, sizeof(btnCmd));
}

// ================================================
// SETUP
// ================================================
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n=== KULSO ESP32 (SLAVE - Csatornavadasz) INDUL ===");

  pinMode(PIN_MEASURE_BUTTON, INPUT_PULLUP);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, LOW);

  // WiFi Station mód, de NEM csatlakozunk semmihez! (Nincs SSID/Jelszó)
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  if (esp_now_init() != ESP_OK) {
    Serial.println("ESP-NOW Init Hiba!");
    return;
  }
  esp_now_register_recv_cb(onDataRecv);

  // A channel = 0 azt jelenti, hogy azt használja, amit mi beállítunk a sendMeasureButton-ban
  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, masterMac, 6);
  peerInfo.channel = 0; 
  peerInfo.encrypt = false;
  esp_now_add_peer(&peerInfo);

  Serial.println("Rendszer felallt! Gombnyomasra keresem a Mastert...");
  digitalWrite(PIN_LED, HIGH); delay(100); digitalWrite(PIN_LED, LOW); delay(100);
  digitalWrite(PIN_LED, HIGH); delay(100); digitalWrite(PIN_LED, LOW);
}

// ================================================
// LOOP
// ================================================
void loop() {
  // --- GOMBNYOMAS FIGYELES ---
  if (digitalRead(PIN_MEASURE_BUTTON) == LOW && millis() - lastButtonPress > 2000) {
    lastButtonPress = millis();
    Serial.printf("\n[MERES] Gomb megnyomva! (Inditas a %d. csatornan)\n", currentChannel);

    ackReceived   = false;
    waitingForAck = true;
    retryCnt      = 0;
    
    // Nem nullázzuk a csatornát! Onnan folytatjuk, ahol legutóbb sikeres volt.
    sendMeasureButton();
    ackWaitStart = millis();
  }

  // --- VISSZAIGAZOLAS + CSATORNA UGRÁS (HOPPING) ---
  if (waitingForAck) {
    if (ackReceived) {
      waitingForAck = false;
      Serial.printf("[OK] Master vette az adast a(z) %d. csatornan! (%d ugras utan)\n", currentChannel, retryCnt);
      digitalWrite(PIN_LED, HIGH);
      delay(1000); // 1 mp-ig vilagit a LED sikeres nyugtazaskor
      digitalWrite(PIN_LED, LOW);
    } 
    else if (millis() - ackWaitStart > ACK_TIMEOUT) {
      retryCnt++;
      if (retryCnt <= MAX_RETRIES) {
        // Ha nem jött válasz 100ms alatt, ugrunk a következő csatornára
        currentChannel++;
        if (currentChannel > 13) currentChannel = 1; // 1-13 csatornák Európában
        
        sendMeasureButton();
        ackWaitStart = millis();
      } else {
        waitingForAck = false;
        Serial.println("[HIBA] Nem talalom a Mastert egyetlen csatornan sem!");
        for(int i = 0; i < 3; i++) {
          digitalWrite(PIN_LED, HIGH); delay(150);
          digitalWrite(PIN_LED, LOW);  delay(150);
        }
      }
    }
  }

  delay(10);
}