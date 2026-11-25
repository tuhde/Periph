#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Isl29125.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    I2CConnectionPicoSDK connection(i2c0, Isl29125Minimal::I2C_ADDRESS);
    Isl29125Minimal sensor(connection);                    // Create Isl29125 driver, (connection)
    Isl29125Full full(connection);                          // Create Isl29125 Full driver, (connection)

    Isl29125Counts c = {0, 0, 0};
    check_true(sensor.valid(), "valid");
    check_true(sensor.read(c), "read");                    // Read the three channels, (counts&) → bool
    check_true((uint32_t)c.red + c.green + c.blue < 3u * 65536u, "read_range");

    check_true(full.deviceId() == 0x7D, "device_id");       // Read DEVICE_ID, () → uint8_t
    check_true(full.setRange(375) && full.setRange(10000), "set_range");
    check_true(full.setResolution(12) && full.setResolution(16), "set_resolution");
    check_true(!full.setRange(100) && !full.setResolution(8), "invalid_config");
    check_true(full.setIrCompensation(true, 0x3F), "ir_compensation");
    full.setSync(false);
    full.readRgb(c);
    check_true((uint32_t)c.red + c.green + c.blue < 3u * 65536u, "read_rgb_range");
    check_true((uint32_t)full.readGreen() + full.readRed() + full.readBlue() < 3u * 65536u, "read_single");
    check_true(full.setMode(Isl29125Full::MODE_GREEN), "mode_green");
    check_true((uint32_t)full.readGreen() + full.readRed() < 2u * 65536u, "green_mode_reads");
    full.setMode(Isl29125Full::MODE_RGB);
    check_true(full.setThresholds(100, 60000), "set_thresholds");
    uint16_t lo = 0, hi = 0;
    full.getThresholds(lo, hi);
    check_true(lo == 100 && hi == 60000, "thresholds_roundtrip");
    check_true(full.setPersistence(2) && full.setInterruptChannel(Isl29125Full::CHANNEL_GREEN), "interrupt_config");
    full.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
    full.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
    full.status();
    check_true((full.pollInterrupt() & Isl29125Full::STATUS_THRESHOLD) == 0, "status_clean");
    full.setMode(Isl29125Full::MODE_POWER_DOWN);
    full.softReset();
    full.setMode(Isl29125Full::MODE_RGB);
    check_true(full.deviceId() == 0x7D, "soft_reset_id");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
