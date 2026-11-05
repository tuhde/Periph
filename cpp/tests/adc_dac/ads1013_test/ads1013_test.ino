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
    I2CConnection connection(Wire, ADS1013Minimal::I2C_ADDRESS);

    ADS1013Minimal adc(connection);                     // Create ADS1013 driver, (connection)
    int16_t raw = adc.readRaw();                        // Read raw code, () → int16 (−2048…2047)
    check_true(raw >= -2048 && raw <= 2047, "raw_in_range");
    float v = adc.readVoltage();                        // Read voltage, () → V
    check_true(v > -2.1f && v < 2.1f, "voltage_plausible");

    ADS1013Full full(connection);                       // Create ADS1013 Full driver, (connection)
    full.reset();                                       // Reset to power-up defaults, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");  // Read CONFIG register, () → uint16
    check_true(full.fullScale() == 2.048f && full.lsb() == 0.001f, "full_scale_fixed");

    full.setDataRate(ADS1013Full::DR_250);              // Set data rate, (dr 0–6) → bool
    check_true(full.getDataRate() == ADS1013Full::DR_250 && full.dataRateSps() == 250, "dr_roundtrip");
    full.readRaw();                                     // Read raw code, () → int16
    check_true(((full.readConfig() >> 5) & 0x7) == ADS1013Full::DR_250, "config_dr_bits");
    full.setDataRate(ADS1013Full::DR_1600);             // Set data rate, (dr 0–6) → bool

    full.startContinuous();                             // Start continuous conversion, () → void
    delay(50);
    int16_t cont = full.readContinuousRaw();            // Read latest code, () → int16
    check_true(cont >= -2048 && cont <= 2047, "continuous_raw_in_range");
    check_true((full.readConfig() & 0x0100) == 0, "continuous_mode_bit");
    full.stopContinuous();                              // Stop continuous conversion, () → void
    check_true((full.readConfig() & 0x0100) != 0, "stop_mode_bit");

    full.reset();                                       // Reset to power-up defaults, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "final_reset");

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
