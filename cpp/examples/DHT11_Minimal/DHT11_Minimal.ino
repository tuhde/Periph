#include "DHT11PinArduino.h"
#include "DHT11.h"

#ifndef DHT11_DATA_PIN
#define DHT11_DATA_PIN 4
#endif

DHT11PinArduino pin(DHT11_DATA_PIN);                    // DATA pin with external 4.7 kΩ pull-up
DHT11Minimal<DHT11PinArduino> dht(pin);                 // Create DHT11 driver, (pin) → DHT11Minimal

void setup() {
    Serial.begin(115200);
    delay(2000);
}

void loop() {
    float t, h;
    if (dht.read(t, h)) {                                // Read temperature and humidity, () → (float, float) °C, %RH
        Serial.print("T: "); Serial.print(t, 1);
        Serial.print(" °C  H: "); Serial.print(h, 1);
        Serial.println(" %RH");
    } else {
        Serial.println("read failed");
    }
    delay(2000);
}
