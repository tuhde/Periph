#ifndef TEST_DATA_PIN
#define TEST_DATA_PIN 4
#endif

#include <Arduino.h>
#include "../../src/transport/DHTxxTransport.h"
#include "../../src/chips/humidity/DHT11.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

class MockTransport : public DHTxxTransport {
public:
    MockTransport(const uint8_t* frame) : DHTxxTransport(TEST_DATA_PIN), _frame(frame) {}
    bool read(uint8_t* out) {
        for (uint8_t i = 0; i < 5; i++) out[i] = _frame[i];
        return true;
    }
private:
    const uint8_t* _frame;
};

void setup() {
    Serial.begin(115200);
    delay(2000);

    // Test 1: Decode datasheet example — 53.0%RH, 24.4°C
    const uint8_t frame1[5] = { 0x35, 0x00, 0x18, 0x04, 0x51 };
    MockTransport t1(frame1);
    DHT11Minimal dht_min(t1);
    float temperature, humidity;
    bool ok = dht_min.read(temperature, humidity);
    check_true(ok && abs(temperature - 24.4f) < 0.001f && abs(humidity - 53.0f) < 0.001f, "decode_datasheet_example");

    // Test 2: Negative temperature
    const uint8_t frame2[5] = { 0x20, 0x00, 0x0A, 0x81, 0xAB };
    MockTransport t2(frame2);
    DHT11Minimal dht_min2(t2);
    ok = dht_min2.read(temperature, humidity);
    check_true(ok && abs(temperature - (-10.1f)) < 0.001f && abs(humidity - 32.0f) < 0.001f, "decode_negative_temperature");

    // Test 3: Checksum error
    const uint8_t bad_frame[5] = { 0x35, 0x00, 0x18, 0x04, 0x00 };
    MockTransport t3(bad_frame);
    DHT11Minimal dht_min3(t3);
    ok = dht_min3.read(temperature, humidity);
    check_true(!ok && !dht_min3.valid(), "checksum_error_invalidates");

    // Test 4: DHT11Full read_temperature / read_humidity
    MockTransport t4(frame1);
    DHT11Full dht_full(t4, 3);
    check_true(abs(dht_full.read_temperature() - 24.4f) < 0.001f, "read_temperature");
    check_true(abs(dht_full.read_humidity()   - 53.0f) < 0.001f, "read_humidity");

    // Test 5: read_raw
    MockTransport t5(frame1);
    DHT11Full dht_full2(t5, 3);
    uint8_t raw[5];
    ok = dht_full2.read_raw(raw);
    check_true(ok && raw[0] == 0x35 && raw[4] == 0x51, "read_raw");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}
void loop() { delay(1000); }
