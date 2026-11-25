#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#include <stdio.h>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS1014.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, ADS1014Minimal::I2C_ADDRESS);

    ADS1014Minimal adc(connection);                     // Create ADS1014 driver, (connection)
    int16_t raw = adc.readRaw();                        // Read raw code, () → int16 (−2048…2047)
    check_true(raw >= -2048 && raw <= 2047, "raw_in_range");
    float v = adc.readVoltage();                        // Read voltage, () → V
    check_true(v > -7.0f && v < 7.0f, "voltage_plausible");

    ADS1014Full full(connection);                       // Create ADS1014 Full driver, (connection)
    full.reset();                                       // Reset to power-up defaults, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");  // Read CONFIG register, () → uint16

    full.setGain(ADS1014Full::PGA_0_256);               // Set PGA, (pga 0–5) → bool
    check_true(full.getGain() == ADS1014Full::PGA_0_256 && full.fullScale() == 0.256f, "gain_roundtrip");
    full.readRaw();                                     // Read raw code, () → int16
    check_true(((full.readConfig() >> 9) & 0x7) == ADS1014Full::PGA_0_256, "config_gain_bits");
    full.setGain(ADS1014Full::PGA_2_048);               // Set PGA, (pga 0–5) → bool

    full.setDataRate(ADS1014Full::DR_250);              // Set data rate, (dr 0–6) → bool
    check_true(full.getDataRate() == ADS1014Full::DR_250 && full.dataRateSps() == 250, "dr_roundtrip");
    full.readRaw();                                     // Read raw code, () → int16
    check_true(((full.readConfig() >> 5) & 0x7) == ADS1014Full::DR_250, "config_dr_bits");
    full.setDataRate(ADS1014Full::DR_1600);             // Set data rate, (dr 0–6) → bool

    full.startContinuous();                             // Start continuous conversion, () → void
    usleep(50UL * 1000UL);
    int16_t cont = full.readContinuousRaw();            // Read latest code, () → int16
    check_true(cont >= -2048 && cont <= 2047, "continuous_raw_in_range");
    check_true((full.readConfig() & 0x0100) == 0, "continuous_mode_bit");
    full.stopContinuous();                              // Stop continuous conversion, () → void
    check_true((full.readConfig() & 0x0100) != 0, "stop_mode_bit");

    full.setLowThresholdRaw(-100);                      // Set low threshold, (raw −2048…2047) → bool
    full.setHighThresholdRaw(300);                      // Set high threshold, (raw −2048…2047) → bool
    int16_t lo = 0, hi = 0;
    full.getThresholdsRaw(&lo, &hi);                    // Read thresholds, (low*, high*) → void
    check_true(lo == -100 && hi == 300, "threshold_roundtrip");
    full.setThresholds(-0.2f, 0.5f);                    // Set thresholds, (low V, high V) → bool
    full.getThresholdsRaw(&lo, &hi);                    // Read thresholds, (low*, high*) → void
    check_true(lo == -200 && hi == 500, "threshold_volts_roundtrip");

    full.configureComparator(ADS1014Full::COMP_WINDOW, ADS1014Full::ACTIVE_HIGH, true, ADS1014Full::QUEUE_2);  // Configure comparator, (mode, polarity, latch, queue) → bool
    check_true((full.readConfig() & 0x1F) == (0x10 | 0x08 | 0x04 | 0x01), "comparator_bits");
    full.clearAlert();                                  // Release latched alert, () → void
    full.disableComparator();                           // Disable comparator, () → void
    check_true((full.readConfig() & 0x3) == 3, "comparator_disabled");

    full.enableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Route conversion-ready to ALERT/RDY, (source) → bool
    full.getThresholdsRaw(&lo, &hi);                    // Read thresholds, (low*, high*) → void
    check_true(lo == 0 && hi == -2048, "ready_thresholds");
    check_true((full.readConfig() & 0x3) == 0, "ready_que_00");
    full.disableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Release ALERT/RDY, (source) → void
    full.reset();                                       // Reset to power-up defaults, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "final_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
