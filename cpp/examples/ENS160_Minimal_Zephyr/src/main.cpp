#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "ENS160.h"

#ifndef ENS160_I2C_NODE
#define ENS160_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef ENS160_ADDR
#define ENS160_ADDR 0x52
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ENS160_I2C_NODE);
    I2CTransportZephyr transport(dev, ENS160_ADDR);
    ENS160Minimal sensor(transport);                     // Create ENS160 driver, (transport)

    printk("Waiting for sensor warm-up...\n");
    {
        uint8_t _aqi; float _tvoc, _eco2;
        while (!sensor.read_air_quality(_aqi, _tvoc, _eco2)) {  // Wait for valid data, () → blocks until warm
            k_sleep(K_SECONDS(1));
        }
    }

    for (int i = 0; i < 10; i++) {
        uint8_t aqi;
        float tvoc_ppb, eco2_ppm;
        bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);  // Read air quality, (aqi&, tvoc_ppb&, eco2_ppm&) → bool
        if (ok) {
            printk("AQI=%d TVOC=%.0f ppb eCO2=%.0f ppm\n", aqi, tvoc_ppb, eco2_ppm);
        }
        k_sleep(K_SECONDS(1));
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
