// IrrigaIQ field node for ESP32: a capacitive soil-moisture probe and (optionally) a pulse water
// meter, reporting to IrrigaIQ over Wi-Fi.
//
// Wiring
//   Soil probe (capacitive v1.2 or similar): VCC -> 3V3, GND -> GND, AOUT -> GPIO 34 (ADC1)
//   Water meter (YF-S201 or any pulse meter): red -> 5V, black -> GND, yellow -> GPIO 27
//     (with a 10 kOhm pull-up to 3V3; YF-S201 outputs about 7.5 pulses per litre per L/min)
//
// Setup
//   1. In IrrigaIQ, open Devices & sensors > Register device. Register one soil probe (placed in
//      its field) and, if fitted, one water meter (on the pump or line it measures). Copy each key.
//   2. Copy secrets.example.h to secrets.h and fill in Wi-Fi, the server URL and the keys.
//   3. Calibrate the probe: note the percentage it reports a day after a full irrigation has
//      drained (field capacity) and when the crop starts to wilt, and enter both on the device in
//      IrrigaIQ. Until then IrrigaIQ assumes 40 % and 15 %.
//
// Readings are sent every REPORT_SECONDS without a timestamp, so the server's clock is used (no
// RTC or NTP needed). A failed send is retried at the next report; the server ignores duplicates.

#include <HTTPClient.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>

#include "secrets.h"  // WIFI_SSID, WIFI_PASSWORD, IRRIGAIQ_URL, PROBE_KEY, METER_KEY (may be "")

constexpr int SOIL_PIN = 34;
constexpr int FLOW_PIN = 27;
constexpr unsigned long REPORT_SECONDS = 300;

// Raw ADC readings of this probe in dry air and in a glass of water: measure once per probe.
constexpr int RAW_DRY = 3300;
constexpr int RAW_WET = 1350;

// Pulses per litre of the meter (YF-S201: about 450).
constexpr float PULSES_PER_LITRE = 450.0f;

volatile unsigned long pulses = 0;

void IRAM_ATTR onPulse() { pulses++; }

float readSoilMoisturePct() {
  long total = 0;
  for (int i = 0; i < 16; i++) {  // average out ADC noise
    total += analogRead(SOIL_PIN);
    delay(5);
  }
  float raw = total / 16.0f;
  float pct = 100.0f * (RAW_DRY - raw) / (RAW_DRY - RAW_WET);
  return constrain(pct, 0.0f, 100.0f);
}

bool send(const char* key, const String& reading) {
  if (strlen(key) == 0 || WiFi.status() != WL_CONNECTED) return false;
  WiFiClientSecure tls;
  tls.setInsecure();  // For a demo. In production pin the server's CA: tls.setCACert(...)
  HTTPClient http;
  bool https = String(IRRIGAIQ_URL).startsWith("https");
  WiFiClient plain;
  if (https) {
    http.begin(tls, String(IRRIGAIQ_URL) + "/api/v1/telemetry");
  } else {
    http.begin(plain, String(IRRIGAIQ_URL) + "/api/v1/telemetry");
  }
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Key", key);
  int status = http.POST("{\"readings\":[" + reading + "]}");
  Serial.printf("POST %s -> %d\n", reading.c_str(), status);
  http.end();
  return status == 202;
}

void connectWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; i++) delay(250);
  Serial.println(WiFi.status() == WL_CONNECTED ? "Wi-Fi connected" : "Wi-Fi not connected");
}

void setup() {
  Serial.begin(115200);
  analogReadResolution(12);
  pinMode(FLOW_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(FLOW_PIN), onPulse, FALLING);
  connectWifi();
}

unsigned long lastReport = 0;
float pendingLitres = 0;

void loop() {
  if (millis() - lastReport < REPORT_SECONDS * 1000UL && lastReport != 0) {
    delay(100);
    return;
  }
  lastReport = millis();
  if (WiFi.status() != WL_CONNECTED) connectWifi();

  float soil = readSoilMoisturePct();
  send(PROBE_KEY, "{\"soilMoisturePct\":" + String(soil, 1) + "}");

  noInterrupts();
  unsigned long counted = pulses;
  pulses = 0;
  interrupts();
  // Litres since the previous report; kept and re-sent if the send fails, so nothing is lost.
  pendingLitres += counted / PULSES_PER_LITRE;
  if (send(METER_KEY, "{\"volumeLitres\":" + String(pendingLitres, 2) + "}")) {
    pendingLitres = 0;
  }
}
