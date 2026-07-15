#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "DHTxxTransportZephyr.h"
#include "DHT11.h"

#ifndef DHT11_GPIO_NODE
#define DHT11_GPIO_NODE DT_NODELABEL(gpio0)
#endif
#ifndef DHT11_GPIO_PIN
#define DHT11_GPIO_PIN 4
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

class MockTransport : public DHTxxTransportZephyr {
public:
    MockTransport(const uint8_t* frame) : DHTxxTransportZephyr({}), _frame(frame) {}
    bool read(uint8_t* out) {
        for (uint8_t i = 0; i < 5; i++) out[i] = _frame[i];
        return true;
    }
private:
    const uint8_t* _frame;
};

int main(void) {
    // Test 1: Decode datasheet example
    const uint8_t frame1[5] = { 0x35, 0x00, 0x18, 0x04, 0x51 };
    MockTransport t1(frame1);
    DHT11Minimal dht_min(t1);
    float temperature, humidity;
    bool ok = dht_min.read(temperature, humidity);
    check_true(ok && fabs(temperature - 24.4f) < 0.001f && fabs(humidity - 53.0f) < 0.001f, "decode_datasheet_example");

    // Test 2: Negative temperature
    const uint8_t frame2[5] = { 0x20, 0x00, 0x0A, 0x81, 0xAB };
    MockTransport t2(frame2);
    DHT11Minimal dht_min2(t2);
    ok = dht_min2.read(temperature, humidity);
    check_true(ok && fabs(temperature - (-10.1f)) < 0.001f && fabs(humidity - 32.0f) < 0.001f, "decode_negative_temperature");

    // Test 3: Checksum error
    const uint8_t bad_frame[5] = { 0x35, 0x00, 0x18, 0x04, 0x00 };
    MockTransport t3(bad_frame);
    DHT11Minimal dht_min3(t3);
    ok = dht_min3.read(temperature, humidity);
    check_true(!ok && !dht_min3.valid(), "checksum_error_invalidates");

    // Test 4: DHT11Full
    MockTransport t4(frame1);
    DHT11Full dht_full(t4, 3);
    check_true(fabs(dht_full.read_temperature() - 24.4f) < 0.001f, "read_temperature");
    check_true(fabs(dht_full.read_humidity() - 53.0f) < 0.001f, "read_humidity");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
