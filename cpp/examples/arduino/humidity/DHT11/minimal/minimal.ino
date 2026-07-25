#ifndef DHT11_DATA_PIN
#define DHT11_DATA_PIN 4
#endif

#include <Arduino.h>
#include "../../src/transport/DHTxxTransport.h"
#include "../../src/chips/humidity/DHT11.h"

DHTxxTransport transport(DHT11_DATA_PIN);
DHT11Minimal dht(transport);                    // Create DHT11 driver, (transport)

void setup() {
    Serial.begin(115200);
    delay(2000);
}

void loop() {
    float t, h;
    dht.read(t, h);                             // Read temperature & humidity, (t°C out, h%RH out) → bool ok
    Serial.print(t); Serial.print(" C, "); Serial.print(h); Serial.println(" %RH");
    delay(2000);
}
