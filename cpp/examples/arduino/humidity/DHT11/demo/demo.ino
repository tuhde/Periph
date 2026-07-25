#ifndef DHT11_DATA_PIN
#define DHT11_DATA_PIN 4
#endif

#include <Arduino.h>
#include "../../src/transport/DHTxxTransport.h"
#include "../../src/chips/humidity/DHT11.h"

DHTxxTransport transport(DHT11_DATA_PIN);
DHT11Full dht(transport, 3);                    // Create DHT11 driver, (transport, max_retries=3)

// --- Indoor comfort monitor ---
// Reads temperature and humidity every 5 seconds and prints a one-line
// status with a comfort assessment. Demonstrates reliable real-world polling
// with retry-based error recovery.
void setup() {
    Serial.begin(115200);
    delay(2000);
}

const char* comfort(float h) {
    if (h < 30.0f) return "dry";
    if (h > 60.0f) return "humid";
    return "comfortable";
}

void loop() {
    float t, h;
    bool ok = dht.read_retry(3, t, h);          // Read with retries, (max_retries 1..255, t out, h out) → bool ok
    if (!ok) {
        // --- Handle read failure ---
        // After all retries are exhausted, log a warning and continue.
        // The next loop iteration will try again with a fresh sample.
        Serial.println("WARN: DHT11 read failed after retries");
    } else {
        Serial.print(t); Serial.print(" C, ");
        Serial.print(h); Serial.print(" %RH, ");
        Serial.println(comfort(h));
    }
    delay(5000);
}
