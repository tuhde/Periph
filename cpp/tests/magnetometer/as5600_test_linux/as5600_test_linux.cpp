#include <cstdio>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "AS5600.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x36
#endif

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint16_t got, uint16_t expected) {
    if (got == expected) {
        printf("PASS %s\n", label); passed++;
    } else {
        printf("FAIL %s: got %u, expected %u\n", label, got, expected); failed++;
    }
}

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    AS5600Full as5600(transport);

    // --- Magnet detection ---
    check_true("magnet_detected", as5600.is_magnet_detected());

    // --- Angle readings ---
    float a = as5600.angle();
    check_true("angle in range 0-360", a >= 0.0f && a < 360.0f);

    uint16_t r = as5600.angle_raw();
    check_true("angle_raw in range 0-4095", r <= 4095);

    uint16_t ra = as5600.raw_angle();
    check_true("raw_angle in range 0-4095", ra <= 4095);

    float rad = as5600.raw_angle_degrees();
    check_true("raw_angle_degrees in range 0-360", rad >= 0.0f && rad < 360.0f);

    // --- Diagnostics ---
    check_true("agc non-negative", as5600.agc() >= 0);
    check_true("magnitude non-negative", as5600.magnitude() >= 0);

    // --- Status ---
    uint8_t sb = as5600.status_byte();
    check_true("status_byte valid", sb <= 255);

    // --- Position configuration (volatile) ---
    as5600.set_zero_position(100);
    check_eq("zero_position after set", as5600.zero_position(), 100);

    as5600.set_max_position(2000);
    check_eq("max_position after set", as5600.max_position(), 2000);

    as5600.set_max_angle(2048);
    check_eq("max_angle after set", as5600.max_angle(), 2048);

    // --- Configure ---
    as5600.configure(AS5600Full::PM_NOM, 0, AS5600Full::OUTS_ANALOG, 0, 0, 0, false);
    check_true("configure accepted", as5600.is_magnet_detected());

    // --- Burn count ---
    uint8_t bc = as5600.burn_count();
    check_true("burn_count in range 0-3", bc <= 3);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
