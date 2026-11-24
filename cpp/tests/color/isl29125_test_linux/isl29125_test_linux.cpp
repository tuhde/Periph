#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#include <stdio.h>
#include <math.h>
#include "I2CConnectionLinux.h"
#include "Isl29125.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, Isl29125Minimal::I2C_ADDRESS);

    Isl29125Minimal sensor(connection);                    // Create Isl29125 driver, (connection)
    Isl29125Full full(connection);                          // Create Isl29125 Full driver, (connection)

    Isl29125Counts c = {0, 0, 0};
    check_true(sensor.valid(), "valid");
    check_true(sensor.read(c), "read");                    // Read the three channels, (counts&) → bool
    check_true(c.red <= 0xFFFF && c.green <= 0xFFFF && c.blue <= 0xFFFF, "read_range");

    check_true(full.deviceId() == 0x7D, "device_id");       // Read DEVICE_ID, () → uint8_t
    check_true(full.setRange(375) && full.setRange(10000), "set_range");
    check_true(full.setResolution(12) && full.setResolution(16), "set_resolution");
    check_true(!full.setRange(100) && !full.setResolution(8), "invalid_config");
    check_true(full.setIrCompensation(true, 0x3F), "ir_compensation");
    full.setSync(false);
    full.readRgb(c);
    check_true(c.green <= 0xFFFF, "read_rgb_range");
    check_true(full.readGreen() <= 0xFFFF && full.readRed() <= 0xFFFF && full.readBlue() <= 0xFFFF, "read_single");
    check_true(full.setMode(Isl29125Full::MODE_GREEN), "mode_green");
    check_true(full.readGreen() <= 0xFFFF, "green_mode_reads");
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
