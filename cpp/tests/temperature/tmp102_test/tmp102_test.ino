#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, TMP102Minimal::I2C_ADDRESS);

    TMP102Minimal sensor(connection);                       // Create TMP102 driver, (connection)
    TMP102Full full(connection);                            // Create TMP102 Full driver, (connection)
    delay(50);
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    check_true(t >= -40.0f && t <= 125.0f, "temperature_plausible");
    float rawT = (float)full.readRaw() * 0.0625f;           // Read raw count, () → int16_t
    check_true(rawT - t < 1.0f && t - rawT < 1.0f, "raw_matches_temperature");
    check_true((full.readConfig() & 0x6000) == 0x6000, "default_config");  // Read CONFIGURATION, () → uint16_t

    full.setConversionRate(1.0f);                           // Set conversion rate, (hz) → bool
    check_true(((full.readConfig() >> 6) & 3) == 1, "rate_roundtrip");
    full.setConversionRate(4.0f);                           // Set conversion rate, (hz) → bool

    full.setTHigh(40.0f);                                   // Set T_HIGH, (celsius °C) → void
    check_true(full.getTHigh() == 40.0f, "t_high_roundtrip");
    full.setTLow(-10.25f);                                  // Set T_LOW, (celsius °C) → void
    check_true(full.getTLow() == -10.25f, "t_low_roundtrip");
    full.setTHigh(80.0f);                                   // Set T_HIGH, (celsius °C) → void
    full.setTLow(75.0f);                                    // Set T_LOW, (celsius °C) → void

    full.setFaultQueue(4);                                  // Set fault queue, (count) → bool
    check_true(((full.readConfig() >> 11) & 3) == 2, "fault_queue");
    full.setFaultQueue(1);                                  // Set fault queue, (count) → bool
    full.setPolarity(true);                                 // Set ALERT polarity, (activeHigh) → void
    check_true((full.readConfig() & 0x0400) != 0, "polarity");
    full.setPolarity(false);                                // Set ALERT polarity, (activeHigh) → void
    full.setThermostatMode(true);                           // Set thermostat mode, (interrupt) → void
    check_true((full.readConfig() & 0x0200) != 0, "thermostat_mode");
    full.setThermostatMode(false);                          // Set thermostat mode, (interrupt) → void

    full.setExtendedMode(true);                             // Set extended mode, (on) → void
    delay(300);
    float ext = full.readTemperature();
    check_true(ext - t < 3.0f && t - ext < 3.0f, "extended_mode_reading");
    full.setExtendedMode(false);                            // Set extended mode, (on) → void

    full.shutdown();                                        // Shut down, () → void
    check_true((full.readConfig() & 0x0100) != 0, "shutdown");
    float t1 = 0.0f;
    bool ok = full.oneShot(t1);                             // One-shot reading, (celsius&) → bool
    check_true(ok && t1 >= -40.0f && t1 <= 125.0f, "one_shot_plausible");
    check_true((full.readConfig() & 0x0100) != 0, "one_shot_stays_shutdown");
    full.wake();                                            // Wake, () → void
    check_true((full.readConfig() & 0x0100) == 0, "wake");

    // T_HIGH below ambient forces an alert after the fault queue elapses.
    full.setThermostatMode(false);                          // Set thermostat mode, (interrupt) → void
    full.setTLow(t - 21.0f);                                // Set T_LOW, (celsius °C) → void
    full.setTHigh(t - 20.0f);                               // Set T_HIGH, (celsius °C) → void
    delay(500);
    check_true(full.alertState(), "alert_state_active");    // Read alert state, () → bool
    check_true(full.pollInterrupt(), "poll_interrupt_active");  // Poll the alert state, () → bool
    full.setTHigh(80.0f);                                   // Set T_HIGH, (celsius °C) → void
    full.setTLow(75.0f);                                    // Set T_LOW, (celsius °C) → void
    delay(500);
    check_true(!full.alertState(), "alert_state_clear");

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
