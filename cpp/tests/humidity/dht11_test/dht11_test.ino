#ifndef TEST_DATA_PIN
#define TEST_DATA_PIN 4
#endif

int passed = 0, failed = 0;

void check_true(bool cond, const char* label) {
    if (cond) {
        Serial.print("PASS ");
        Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL ");
        Serial.println(label);
        failed++;
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);

    DHTxxTransport transport(TEST_DATA_PIN);
    DHT11Full dht(transport);

    float temp = dht.readTemperature();
    float hum = dht.readHumidity();

    check_true(temp > -40.0f && temp < 80.0f, "temperature_range");
    check_true(hum >= 0.0f && hum <= 100.0f, "humidity_range");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() {}
