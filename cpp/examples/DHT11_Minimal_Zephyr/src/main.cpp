#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "DHTxxTransportZephyr.h"
#include "DHT11.h"

#ifndef DHT11_DATA_PIN
#define DHT11_DATA_PIN 4
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct gpio_dt_spec spec = GPIO_DT_SPEC_GET_OR(DT_NODELABEL(gpio0), DHT11_DATA_PIN, {0});
    DHTxxTransportZephyr transport(&spec);
    DHT11Minimal dht(transport);

    float temp, hum;
    dht.read(temp, hum);

    check_true(temp > -40.0f && temp < 80.0f, "temperature_range");
    check_true(hum >= 0.0f && hum <= 100.0f, "humidity_range");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
