#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "LPS22DF.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);

    LPS22DFMinimal lps(connection);

    float t = lps.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature_range");

    float p = lps.pressure();
    check_true(p >= 26000.0f && p <= 126000.0f, "pressure_range");

    LPS22DFFull lps_full(connection);
    check_true(lps_full.who_am_i() == 0xB4, "who_am_i");

    lps_full.configure(4, 1, true, 1, true);
    float p2 = lps_full.pressure();
    check_true(p2 >= 26000.0f && p2 <= 126000.0f, "configure_then_read");

    float alt = lps_full.altitude(101325.0f);
    check_true(alt >= -500.0f && alt <= 10000.0f, "altitude");

    lps_full.set_pressure_threshold(102000.0f);
    lps_full.configure_interrupt(false, false, true, false, true, false, false, false);
    uint8_t src = lps_full.interrupt_source();
    check_true(1, "interrupt_source_readable");

    float samples[16];
    uint8_t n = lps_full.read_fifo(samples, 16);
    check_true(1, "read_fifo");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}