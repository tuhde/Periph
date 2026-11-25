#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "DS3502.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printk("FAIL device_ready\n");
        return 1;
    }
    I2CConnectionZephyr connection(i2c_dev, DS3502Minimal::I2C_ADDRESS);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
