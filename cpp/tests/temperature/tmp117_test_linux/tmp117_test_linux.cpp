#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#include <stdio.h>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "TMP117.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TMP117Minimal::I2C_ADDRESS);

    TMP117Minimal sensor(connection);                       // Create TMP117 driver, (connection)
    TMP117Full full(connection);                            // Create TMP117 Full driver, (connection)
    full.configure(TMP117Full::Mode::Continuous, 8, 0.125f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    usleep(300UL * 1000UL);
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    check_true(t >= -40.0f && t <= 125.0f, "temperature_plausible");

    full.configure(TMP117Full::Mode::Continuous, 32, 4.0f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    TMP117Full::Config cfg = full.getConfig();              // Read conversion config, () → Config
    check_true(cfg.mode == TMP117Full::Mode::Continuous && cfg.averaging == 32 && cfg.cycleSeconds == 4.0f,
               "config_roundtrip");
    full.configure(TMP117Full::Mode::Shutdown, 0, 0.0155f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    check_true(full.isShutdown(), "shutdown");
    full.triggerOneShot();                                  // Start one conversion, () → void
    usleep(50UL * 1000UL);
    check_true(full.isDataReady(), "one_shot_data_ready");
    check_true(full.isShutdown(), "one_shot_returns_to_shutdown");
    full.configure();                                       // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    check_true(!full.isShutdown(), "continuous");

    full.setHighLimit(80.0f);                               // Set THIGH_LIMIT, (celsius °C) → void
    check_true(full.getHighLimit() == 80.0f, "high_limit_roundtrip");
    full.setLowLimit(-10.25f);                              // Set TLOW_LIMIT, (celsius °C) → void
    check_true(full.getLowLimit() == -10.25f, "low_limit_roundtrip");
    full.setTemperatureOffset(0.5f);                        // Set calibration offset, (celsius °C) → void
    check_true(full.getTemperatureOffset() == 0.5f, "offset_roundtrip");
    full.setTemperatureOffset(0.0f);                        // Set calibration offset, (celsius °C) → void

    check_true(!full.isEepromBusy(), "eeprom_not_busy");
    uint16_t eeprom2 = 0;
    full.readEepromScratch(2, eeprom2);                     // Read EEPROM scratch, (slot 1|2|3, value&) → bool
    full.writeEepromScratch(2, 0xA55A);                     // Write EEPROM scratch, (slot 2, value 16-bit) → bool
    uint16_t scratch = 0;
    check_true(full.readEepromScratch(2, scratch) && scratch == 0xA55A, "eeprom2_volatile_roundtrip");

    // One real EEPROM program cycle (the conformance-checked eeprom_write_ready
    // timing): rewrite EEPROM2's original value while unlocked, so the stored
    // power-on value is unchanged. Costs one EEPROM2 endurance cycle per run.
    full.unlockEeprom();                                    // Unlock EEPROM, () → void
    full.writeEepromScratch(2, eeprom2);                    // Write EEPROM scratch, (slot 2, value 16-bit) → bool
    int waitedMs = 0;
    while (full.isEepromBusy() && waitedMs < 50) {          // Check EEPROM busy, () → bool
        usleep(1000UL);
        waitedMs += 1;
    }
    full.lockEeprom();                                      // Lock EEPROM, () → void
    check_true(waitedMs < 50, "eeprom_write_ready");
    check_true(full.readEepromScratch(2, scratch) && scratch == eeprom2, "eeprom2_restored");

    // High limit below ambient forces HIGH_Alert on the next conversion; in
    // Alert mode the flag latches until CONFIGURATION is read.
    full.configureAlert();                                  // Configure ALERT, (mode=Alert, polarity=ActiveLow, pinFunction=Alert) → void
    full.configure(TMP117Full::Mode::Continuous, 0, 0.0155f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    full.setHighLimit(t - 20.0f);                           // Set THIGH_LIMIT, (celsius °C) → void
    usleep(100UL * 1000UL);
    check_true((full.pollInterrupt() & TMP117Full::SOURCE_HIGH) != 0, "poll_interrupt_high");
    full.setHighLimit(80.0f);                               // Set THIGH_LIMIT, (celsius °C) → void
    usleep(100UL * 1000UL);
    full.pollInterrupt();                                   // Read alert flags, () → uint8_t mask
    check_true((full.pollInterrupt() & TMP117Full::SOURCE_HIGH) == 0, "poll_interrupt_clear");

    // Soft reset reloads CONFIGURATION, the limits and the offset from EEPROM.
    full.reset();                                           // Software reset, () → void
    check_true(full.getConfig().mode == TMP117Full::Mode::Continuous, "reset_restores_config");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
