#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>
#include <math.h>

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, DS3502Minimal::I2C_ADDRESS);

    DS3502Minimal pot(connection, 15.0f, 0.0f);             // Create DS3502 driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    pot.setWiper(100);                                      // Set wiper position, (code 0–127) → void
    check_true(pot.getWiper() == 100, "wiper_roundtrip");   // Read wiper position, () → uint8_t
    pot.setWiper(300);
    check_true(pot.getWiper() == 127, "wiper_clamps_127");
    pot.setVoltage(6.0f);                                   // Set RW output voltage, (volts V) → void
    check_true(fabsf(pot.getVoltage() - 6.0f) < 0.1f, "set_voltage"); // Read unloaded RW voltage, () → float V

    DS3502Full full(connection, 15.0f, 0.0f);               // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    check_true(!full.getMode(), "init_mode_sram");          // Read MODE, () → bool
    full.setRatio(0.5f);                                    // Set divider ratio, (ratio 0.0–1.0) → void
    check_true(full.getWiper() == 64 && fabsf(full.getRatio() - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, () → float
    full.setWiper(100);
    check_true(fabsf(full.getResistance('L') + full.getResistance('H') - 10000.0f) < 1.0f, "resistance_nominal"); // Read wiper resistance, (terminal='L') → float Ω
    check_true(full.increment(5) == 105 && full.getWiper() == 105, "increment"); // Step the wiper, (steps=1) → uint8_t
    check_true(full.increment(-500) == 0, "increment_clamps");
    full.setMode(true);                                     // Select EEPROM-backed writes, (eeprom bool) → void
    check_true(full.getMode(), "set_mode_eeprom");
    full.setMode(false);
    check_true(!full.getMode(), "set_mode_sram");
    check_true(full.waitReady(), "wait_ready");             // Acknowledge polling, (timeoutMs=30) → bool
    // storeWiper is not exercised: it consumes one of the EEPROM's limited write cycles.

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
