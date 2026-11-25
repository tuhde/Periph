#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#include <stdio.h>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS1113.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, ADS1113Minimal::I2C_ADDRESS);
    ADS1113Minimal sensor(connection);                      // Create ADS1113 driver, (connection)
    ADS1113Full full(connection);                           // Create ADS1113 Full driver, (connection)
    int16_t raw = sensor.readRaw();                         // Read raw code, () → int16_t
    int16_t raw2 = sensor.readRaw();
    check_true(raw - raw2 < 4000 && raw2 - raw < 4000, "read_raw_stable");
    float v = sensor.readVoltage();                         // Read voltage, () → float V
    check_true(v - raw * 0.0000625f < 0.25f && raw * 0.0000625f - v < 0.25f, "read_voltage_matches_raw");
    full.setDataRate(ADS1113Full::DR_860);                  // Set data rate, (dr 0–7) → void
    check_true(full.getDataRate() == 7 && full.dataRateSps() == 860, "data_rate_roundtrip");
    full.startContinuous();                                 // Start continuous, () → void
    usleep(50UL * 1000UL);
    int16_t cont = full.readContinuousRaw();                // Read continuous raw, () → int16_t
    check_true(cont - raw < 8000 && raw - cont < 8000, "continuous_matches_single_shot");
    check_true((full.readConfig() & 0x0100) == 0, "continuous_config_mode");
    full.stopContinuous();                                  // Stop continuous, () → void
    check_true((full.readConfig() & 0x0100) != 0, "single_shot_config_mode");
    full.reset();                                           // Reset, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
