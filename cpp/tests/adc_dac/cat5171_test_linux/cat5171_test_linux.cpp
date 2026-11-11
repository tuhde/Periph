#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#include <stdio.h>
#include <math.h>
#include "I2CConnectionLinux.h"
#include "CAT5171.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, CAT5171Minimal::I2C_ADDRESS);

    CAT5171Minimal pot(connection);                         // Create CAT5171 driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)
    pot.setWiper(100);                                      // Set wiper position, (code 0–255) → void
    check_true(pot.getWiper() == 100, "wiper_roundtrip");   // Read wiper position, () → uint8_t
    pot.setWiper(300);
    check_true(pot.getWiper() == 255, "wiper_clamps_255");
    pot.setResistance(50000.0f);                            // Set W–B resistance, (ohms Ω) → void
    check_true(fabsf(pot.getResistance() - 50000.0f) < 500.0f, "set_resistance"); // Read W–B resistance, () → float Ω

    CAT5171Full full(connection);                           // Create CAT5171 Full driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)
    full.setRatio(0.5f);                                    // Set divider ratio, (ratio 0.0–1.0) → void
    check_true(full.getWiper() == 128 && fabsf(full.getRatio() - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, () → float
    full.setResistanceWa(30000.0f);                         // Set W–A resistance, (ohms Ω) → void
    check_true(fabsf(full.getResistanceWa() - 30000.0f) < 500.0f, "set_resistance_wa"); // Read W–A resistance, () → float Ω
    full.setWiper(100);
    check_true(full.increment(5) == 105 && full.getWiper() == 105, "increment"); // Step the wiper, (steps=1) → uint8_t
    check_true(full.increment(-500) == 0, "increment_clamps");

    full.setWiper(77);
    full.shutdown();                                        // Shut down, () → void
    check_true(full.isShutdown(), "is_shutdown");           // Query shutdown state, () → bool
    check_true(full.getWiper() == 77, "shutdown_keeps_wcr");
    full.resume();                                          // Leave shutdown, () → void
    check_true(!full.isShutdown() && full.getWiper() == 77, "resume");

    full.setWiper(10);
    full.midscale();                                        // Move wiper to midscale, () → void
    check_true(full.getWiper() == 128, "midscale");

    full.setWiper(1);
    full.reset();                                           // Midscale reset, () → void
    check_true(full.getWiper() == 128, "reset_midscale");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
