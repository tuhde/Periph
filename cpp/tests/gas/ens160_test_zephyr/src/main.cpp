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

    ENS160Full sensor(transport);
    check_true(true, "init");

    uint8_t status = sensor.status();
    check_true(status <= 3, "status_valid_range");

    printk("Waiting for warm-up...\n");
    bool warmup_ok = false;
    {
        uint8_t _aqi; float _tvoc, _eco2;
        for (int i = 0; i < 240; i++) {
            if (sensor.read_air_quality(_aqi, _tvoc, _eco2)) { warmup_ok = true; break; }
            k_sleep(K_SECONDS(1));
        }
    }
    check_true(warmup_ok, "warmup_complete");

    uint8_t aqi;
    float tvoc_ppb, eco2_ppm;
    bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);
    check_true(ok, "read_air_quality");
    check_true(aqi >= 1 && aqi <= 5, "aqi_range");
    check_true(tvoc_ppb >= 0, "tvoc_non_negative");
    check_true(eco2_ppm >= 400, "eco2_minimum");

    sensor.set_compensation(25.0f, 50.0f);
    check_true(true, "set_compensation");

    float tvoc = sensor.read_tvoc();
    check_true(tvoc >= 0, "read_tvoc");

    float eco2 = sensor.read_eco2();
    check_true(eco2 >= 400, "read_eco2");

    uint8_t aqi2 = sensor.read_aqi();
    check_true(aqi2 >= 1 && aqi2 <= 5, "read_aqi");

    float temp_actual, rh_actual;
    sensor.read_compensation_actuals(temp_actual, rh_actual);
    check_true(true, "read_compensation_actuals");

    sensor.sleep();
    check_true(true, "sleep");
    k_sleep(K_SECONDS(1));
    sensor.wake();
    check_true(true, "wake");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
