#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AD5243.h"

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

    I2CConnectionPicoSDK connection(i2c0, AD5243Minimal::I2C_ADDRESS);
    AD5243Minimal pot(connection);                          // Create AD5243 driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    pot.setWiper(0, 100);                                   // Set wiper position, (channel 0–1, code 0–255) → bool
    check_true(pot.getWiper(0) == 100, "wiper0_roundtrip"); // Read wiper position, (channel 0–1) → uint8_t
    pot.setWiper(1, 200);
    check_true(pot.getWiper(1) == 200, "wiper1_roundtrip");
    check_true(pot.getWiper(0) == 100, "wiper0_independent");
    pot.setWiper(0, 300);
    check_true(pot.getWiper(0) == 255, "wiper_clamps_255");
    pot.setResistance(0, 5000.0f);                          // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(pot.getResistance(0) - 5000.0f) < 50.0f, "set_resistance"); // Read W–B resistance, (channel 0–1) → float Ω

    AD5243Full full(connection);                            // Create AD5243 Full driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    full.setBoth(10, 250);                                  // Write both wipers, (code1 0–255, code2 0–255) → void
    check_true(full.getWiper(0) == 10 && full.getWiper(1) == 250, "set_both");
    full.setRatio(0, 0.5f);                                 // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    check_true(full.getWiper(0) == 128 && fabsf(full.getRatio(0) - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, (channel 0–1) → float
    full.setResistanceWa(1, 3000.0f);                       // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(full.getResistanceWa(1) - 3000.0f) < 50.0f, "set_resistance_wa"); // Read W–A resistance, (channel 0–1) → float Ω
    full.setWiper(0, 100);
    check_true(full.increment(0, 5) == 105 && full.getWiper(0) == 105, "increment"); // Step the wiper, (channel 0–1, steps=1) → uint8_t
    check_true(full.increment(0, -500) == 0, "increment_clamps");

    full.setWiper(1, 77);
    full.shutdown(1);                                       // Shut channel down, (channel 0–1) → void
    check_true(full.isShutdown(1), "is_shutdown");          // Query shutdown state, (channel 0–1) → bool
    check_true(full.getWiper(1) == 77, "shutdown_keeps_rdac");
    full.resume(1);                                         // Leave shutdown, (channel 0–1) → void
    check_true(!full.isShutdown(1) && full.getWiper(1) == 77, "resume");

    full.setBoth(1, 2);
    full.reset();                                           // Restore power-up preset, () → void
    check_true(full.getWiper(0) == 128 && full.getWiper(1) == 128, "reset_midscale");


    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
