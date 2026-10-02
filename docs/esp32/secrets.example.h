// Copy to secrets.h (never commit secrets.h).
#pragma once

#define WIFI_SSID "farm-wifi"
#define WIFI_PASSWORD "change-me"

// Where the ESP32 reaches the IrrigaIQ API, without a trailing slash. On a laptop during a demo,
// use its LAN address: http://192.168.1.20:8080
#define IRRIGAIQ_URL "http://192.168.1.20:8080"

// Device keys from IrrigaIQ > Devices & sensors (shown once at registration).
#define PROBE_KEY "iqd_xxxxxxxxxx_..."
#define METER_KEY ""  // leave empty if no water meter is fitted
