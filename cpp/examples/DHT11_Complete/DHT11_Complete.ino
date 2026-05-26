#include <DHT11.h>

#ifndef TEST_DATA_PIN
#define TEST_DATA_PIN 4
#endif

DHTxxTransport transport(TEST_DATA_PIN);
DHT11Full dht(transport);

void setup() {
    Serial.begin(115200);
    delay(2000);
}

void loop() {
    float temp = dht.readTemperature();
    float hum = dht.readHumidity();
    Serial.print("Temperature: ");
    Serial.print(temp);
    Serial.print(" C, Humidity: ");
    Serial.print(hum);
    Serial.println(" %RH");

    uint8_t raw[5];
    dht.readRaw(raw, 5);
    Serial.print("Raw: ");
    for (int i = 0; i < 5; i++) {
        Serial.print(raw[i], HEX);
        Serial.print(" ");
    }
    Serial.println();

    delay(2000);
}
