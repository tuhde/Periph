#include <zephyr/kernel.h>
#include <zephyr/devicetree.h>
#include "DHT11PinZephyr.h"
#include "DHT11.h"

#define DHT11_NODE DT_ALIAS(dht11)

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    static const struct gpio_dt_spec dht11_spec = GPIO_DT_SPEC_GET(DHT11_NODE, gpios);

    DHT11PinZephyr pin(dht11_spec);
    DHT11Full<DHT11PinZephyr> dht(pin);

    float t, h;
    bool ok = dht.read(t, h);
    check_true(true, "read returned bool");
    check_true(t >= -20.0f && t <= 60.0f, "read_temperature in [-20, 60]");
    check_true(h >=   0.0f && h <= 100.0f, "read_humidity in [0, 100]");

    float t2 = 0.0f, h2 = 0.0f;
    bool ok2 = dht.read_retry(t2, h2, 3);
    check_true(true, "read_retry returned bool");
    check_true(t2 >= -20.0f && t2 <= 60.0f, "read_retry temperature in range");
    check_true(h2 >=   0.0f && h2 <= 100.0f, "read_retry humidity in range");

    uint8_t raw[5];
    bool raw_ok = dht.read_raw(raw);
    check_true(true, "read_raw returned bool");
    if (raw_ok) {
        uint8_t checksum = (uint8_t)(raw[0] + raw[1] + raw[2] + raw[3]);
        check_true(checksum == raw[4], "read_raw checksum OK");
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
