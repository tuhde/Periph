#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADS1114.h"

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

    I2CConnectionPicoSDK connection(i2c0, ADS1114Minimal::I2C_ADDRESS);
    ADS1114Minimal sensor(connection);                      // Create ADS1114 driver, (connection)
    ADS1114Full full(connection);                           // Create ADS1114 Full driver, (connection)
    int16_t raw = sensor.readRaw();                         // Read raw code, () → int16_t
    int16_t raw2 = sensor.readRaw();
    check_true(raw - raw2 < 4000 && raw2 - raw < 4000, "read_raw_stable");
    float v = sensor.readVoltage();                         // Read voltage, () → float V
    check_true(v - raw * 0.0000625f < 0.25f && raw * 0.0000625f - v < 0.25f, "read_voltage_matches_raw");
    full.setDataRate(ADS1114Full::DR_860);                  // Set data rate, (dr 0–7) → void
    check_true(full.getDataRate() == 7 && full.dataRateSps() == 860, "data_rate_roundtrip");
    full.setGain(ADS1114Full::PGA_4_096);                   // Set gain, (pga 0–5) → void
    check_true(full.getGain() == 1 && full.fullScale() == 4.096f, "gain_roundtrip");
    full.setGain(ADS1114Full::PGA_2_048);
    full.startContinuous();                                 // Start continuous, () → void
    sleep_ms(50);
    int16_t cont = full.readContinuousRaw();                // Read continuous raw, () → int16_t
    check_true(cont - raw < 8000 && raw - cont < 8000, "continuous_matches_single_shot");
    check_true((full.readConfig() & 0x0100) == 0, "continuous_config_mode");
    full.stopContinuous();                                  // Stop continuous, () → void
    check_true((full.readConfig() & 0x0100) != 0, "single_shot_config_mode");
    full.setLowThresholdRaw(-1000);
    full.setHighThresholdRaw(1000);
    int16_t lo = 0, hi = 0;
    full.getThresholdsRaw(&lo, &hi);                        // Get thresholds, (int16_t* low, int16_t* high) → void
    check_true(lo == -1000 && hi == 1000, "threshold_roundtrip");
    full.configureComparator(ADS1114Full::COMP_WINDOW, ADS1114Full::ACTIVE_LOW, false, ADS1114Full::QUEUE_4);
    check_true((full.readConfig() & 0x0017) == 0x0012, "comparator_bits");
    full.disableComparator();
    check_true((full.readConfig() & 0x0003) == 0x0003, "comparator_disabled");
    full.reset();                                           // Reset, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
