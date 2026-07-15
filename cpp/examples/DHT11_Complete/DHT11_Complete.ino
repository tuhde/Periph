#include "DHT11PinArduino.h"
#include "DHT11.h"

#ifndef DHT11_DATA_PIN
#define DHT11_DATA_PIN 4
#endif

DHT11PinArduino pin(DHT11_DATA_PIN);                    // DATA pin with external 4.7 kΩ pull-up
DHT11Full<DHT11PinArduino> dht(pin);                     // Create DHT11 driver, (pin) → DHT11Full

void setup() {
    Serial.begin(115200);
    delay(2000);
}

void loop() {
    float t = 0.0f, h = 0.0f;
    bool ok = dht.read_retry(t, h, 3);                   // Read with retry, (t, h, max_retries=3) → bool
                                                         // returns true on success; t and h updated on success
    uint8_t raw[5];
    bool raw_ok = dht.read_raw(raw);                     // Read raw 5-byte frame, (b: uint8_t[5]) → bool
                                                         // [hum_int, hum_dec, temp_int, temp_dec, checksum]
    Serial.print("ok="); Serial.print(ok);
    Serial.print(" t="); Serial.print(t, 1);
    Serial.print(" h="); Serial.print(h, 1);
    if (raw_ok) {
        Serial.print(" raw=[");
        for (uint8_t i = 0; i < 5; i++) {
            if (i) Serial.print(", ");
            Serial.print(raw[i], HEX);
        }
        Serial.print("]");
    }
    Serial.println();
    delay(2000);
}
