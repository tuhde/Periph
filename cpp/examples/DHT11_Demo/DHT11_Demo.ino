#include "DHT11PinArduino.h"
#include "DHT11.h"

#ifndef DHT11_DATA_PIN
#define DHT11_DATA_PIN 4
#endif

// Indoor comfort monitor: read temperature and humidity every 5 seconds and
// print a one-line status with a comfort assessment.
DHT11PinArduino pin(DHT11_DATA_PIN);                    // DATA pin with external 4.7 kΩ pull-up
DHT11Full<DHT11PinArduino> dht(pin);                     // Create DHT11 driver, (pin) → DHT11Full

void setup() {
    Serial.begin(115200);
    delay(2000);
}

void loop() {
    // --- Sample temperature and humidity with retry ---
    // Use read_retry() so a single dropped bit does not abort the loop.
    float t = 0.0f, h = 0.0f;
    bool ok = dht.read_retry(t, h, 3);                   // Read with retry, (t, h, max_retries=3) → bool

    // --- Classify comfort zone ---
    const char* comfort;
    if      (h < 30.0f)  comfort = "dry";
    else if (h <= 60.0f) comfort = "comfortable";
    else                 comfort = "humid";

    if (ok) {
        Serial.print("T="); Serial.print(t, 1);
        Serial.print(" C  H="); Serial.print(h, 1);
        Serial.print(" %RH  ("); Serial.print(comfort);
        Serial.println(")");
    } else {
        Serial.println("read failed after 3 attempts");
    }
    delay(5000);
}
