#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DS3502.h"

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

    I2CConnectionPicoSDK connection(i2c0, DS3502Minimal::I2C_ADDRESS);
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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
