#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AD5252.h"

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

    I2CConnectionPicoSDK connection(i2c0, AD5252Minimal::I2C_ADDRESS);
    AD5252Minimal pot(connection);                          // Create AD5252 driver, (connection, rAb=10000.0 Ω, rWiper=75.0 Ω)
    pot.setWiper(0, 100);                                   // Set wiper position, (channel 0–1, code 0–255) → bool
    check_true(pot.getWiper(0) == 100, "wiper0_roundtrip"); // Read wiper position, (channel 0–1) → uint8_t
    pot.setWiper(1, 200);
    check_true(pot.getWiper(1) == 200, "wiper1_roundtrip");
    check_true(pot.getWiper(0) == 100, "wiper0_independent");
    pot.setWiper(0, 300);
    check_true(pot.getWiper(0) == 255, "wiper_clamps_255");
    pot.setResistance(0, 5000.0f);                          // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(pot.getResistance(0) - 5000.0f) < 50.0f, "set_resistance"); // Read W–B resistance, (channel 0–1) → float Ω

    AD5252Full full(connection);                            // Create AD5252 Full driver, (connection, rAb=10000.0 Ω, rWiper=75.0 Ω)
    full.setBoth(10, 250);                                  // Write both wipers, (code0 0–255, code1 0–255) → void
    check_true(full.getWiper(0) == 10 && full.getWiper(1) == 250, "set_both");
    full.setRatio(0, 0.5f);                                 // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    check_true(full.getWiper(0) == 128 && fabsf(full.getRatio(0) - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, (channel 0–1) → float
    full.setResistanceWa(1, 3000.0f);                       // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(full.getResistanceWa(1) - 3000.0f) < 50.0f, "set_resistance_wa"); // Read W–A resistance, (channel 0–1) → float Ω
    full.setWiper(0, 100);
    full.increment(0);                                      // Step up one code, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 101, "increment");
    full.decrement(0);                                      // Step down one code, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 100, "decrement");
    full.doubleCode(0);                                     // Raise by 6 dB, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 200, "double");
    full.halve(0);                                          // Lower by 6 dB, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 100, "halve");
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

    full.writeEemem(15, 0xA5);                              // Write user EEMEM byte, (index 4–15, value 0–255) → bool
    check_true(full.readEemem(15) == 0xA5, "eemem_roundtrip"); // Read user EEMEM byte, (index 4–15) → uint8_t
    float tol = full.readTolerance(0);                      // Read stored RAB tolerance, (channel 0–1) → float %
    check_true(tol > -50.0f && tol < 50.0f, "tolerance_range");
    check_true(fabsf(full.rabActual(0) - 10000.0f * (1.0f + tol / 100.0f)) < 0.5f, "rab_actual"); // (channel 0–1) → float Ω
    full.nop();                                             // Send NOP, () → void


    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
