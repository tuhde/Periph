#include <DHT11.h>

#ifndef TEST_DATA_PIN
#define TEST_DATA_PIN 4
#endif

DHTxxTransport transport(TEST_DATA_PIN);
DHT11Minimal dht(transport);

void setup() {
    Serial.begin(115200);
    delay(2000);
}

void loop() {
    float temp, hum;
    dht.read(temp, hum);
    Serial.print("Temperature: ");
    Serial.print(temp);
    Serial.print(" C, Humidity: ");
    Serial.print(hum);
    Serial.println(" %RH");
    delay(2000);
}
