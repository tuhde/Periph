#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "AD5251.h"

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
    I2CConnectionZephyr connection(i2c_dev, AD5251Minimal::I2C_ADDRESS);
    AD5251Minimal pot(connection);                          // Create AD5251 driver, (connection, rAb=50000.0 Ω, rWiper=75.0 Ω)
    pot.setWiper(0, 40);                                   // Set wiper position, (channel 0–1, code 0–63) → bool
    check_true(pot.getWiper(0) == 40, "wiper0_roundtrip"); // Read wiper position, (channel 0–1) → uint8_t
    pot.setWiper(1, 60);
    check_true(pot.getWiper(1) == 60, "wiper1_roundtrip");
    check_true(pot.getWiper(0) == 40, "wiper0_independent");
    pot.setWiper(0, 300);
    check_true(pot.getWiper(0) == 63, "wiper_clamps_255");
    pot.setResistance(0, 25000.0f);                          // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(pot.getResistance(0) - 25000.0f) < 800.0f, "set_resistance"); // Read W–B resistance, (channel 0–1) → float Ω

    AD5251Full full(connection);                            // Create AD5251 Full driver, (connection, rAb=50000.0 Ω, rWiper=75.0 Ω)
    full.setBoth(10, 58);                                  // Write both wipers, (code0 0–63, code1 0–63) → void
    check_true(full.getWiper(0) == 10 && full.getWiper(1) == 58, "set_both");
    full.setRatio(0, 0.5f);                                 // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    check_true(full.getWiper(0) == 32 && fabsf(full.getRatio(0) - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, (channel 0–1) → float
    full.setResistanceWa(1, 15000.0f);                       // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(full.getResistanceWa(1) - 15000.0f) < 800.0f, "set_resistance_wa"); // Read W–A resistance, (channel 0–1) → float Ω
    full.setWiper(0, 40);
    full.increment(0);                                      // Step up one code, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 21, "increment");
    full.decrement(0);                                      // Step down one code, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 40, "decrement");
    full.doubleCode(0);                                     // Raise by 6 dB, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 60, "double");
    full.halve(0);                                          // Lower by 6 dB, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 40, "halve");
    full.setBoth(10, 20);
    full.increment();
    check_true(full.getWiper(0) == 11 && full.getWiper(1) == 21, "increment_both");

    full.setBoth(40, 60);
    full.store();                                           // Store wipers in EEMEM, (channel=BOTH 0–1) → bool
    full.setBoth(1, 2);
    full.restore();                                         // Restore wipers from EEMEM, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 40 && full.getWiper(1) == 60, "store_restore");
    full.setBoth(1, 2);
    full.reset();                                           // Reset from EEMEM, () → bool
    check_true(full.getWiper(0) == 40 && full.getWiper(1) == 60, "reset_restores_eemem");

    full.writeEemem(15, 0xA5);                              // Write user EEMEM byte, (index 4–15, value 0–63) → bool
    check_true(full.readEemem(15) == 0xA5, "eemem_roundtrip"); // Read user EEMEM byte, (index 4–15) → uint8_t
    float tol = full.readTolerance(0);                      // Read stored RAB tolerance, (channel 0–1) → float %
    check_true(tol > -50.0f && tol < 50.0f, "tolerance_range");
    check_true(fabsf(full.rabActual(0) - 50000.0f * (1.0f + tol / 100.0f)) < 0.5f, "rab_actual"); // (channel 0–1) → float Ω
    full.nop();                                             // Send NOP, () → void


    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
