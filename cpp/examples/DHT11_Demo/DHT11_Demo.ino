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
    float temp, hum;
    dht.readRetry(temp, hum, 3);

    const char* comfort;
    if (hum < 30) {
        comfort = "dry";
    } else if (hum <= 60) {
        comfort = "comfortable";
    } else {
        comfort = "humid";
    }

    Serial.print("Temperature: ");
    Serial.print(temp);
    Serial.print(" C, Humidity: ");
    Serial.print(hum);
    Serial.print(" %RH -- ");
    Serial.println(comfort);

    delay(5000);
}
