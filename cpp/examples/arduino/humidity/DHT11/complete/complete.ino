#ifndef DHT11_DATA_PIN
#define DHT11_DATA_PIN 4
#endif

#include <Arduino.h>
#include "../../src/connection/DHTxxConnection.h"
#include "../../src/chips/humidity/DHT11.h"

DHTxxConnection connection(DHT11_DATA_PIN);
DHT11Full dht(connection, 3);                    // Create DHT11 driver, (connection, max_retries=3)

void setup() {
    Serial.begin(115200);
    delay(2000);
}

void loop() {
    float t = dht.read_temperature();           // Read temperature, () → float °C
                                               // returns a fresh conversion each call
    float h = dht.read_humidity();              // Read humidity, () → float %RH
                                               // returns a fresh conversion each call
    float t2, h2;
    bool ok = dht.read_retry(5, t2, h2);        // Read with retries, (max_retries 1..255, t out, h out) → bool ok
                                               // retries up to 5 times on checksum error
    uint8_t raw[5];
    bool rok = dht.read_raw_with_retry(raw);    // Read raw frame, (out[5]) → bool ok
                                               // returns the validated 5-byte frame
    Serial.print("t="); Serial.print(t);
    Serial.print(" h="); Serial.print(h);
    Serial.print(" retry_ok="); Serial.print(ok);
    Serial.print(" raw[0]=0x"); Serial.print(raw[0], HEX);
    Serial.println();
    delay(2000);
}
