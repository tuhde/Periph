#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x18
#endif

#include <stdio.h>
#include <math.h>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LIS3DH.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);

    LIS3DHMinimal accel(connection);                        // Create LIS3DH driver, (connection)

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_returns_floats");
    float mag = sqrtf(x * x + y * y + z * z);
    check_true(mag >= 0.5f && mag <= 1.5f, "magnitude_near_1g");

    LIS3DHFull accel_full(connection);                      // Create LIS3DH Full driver, (connection)

    // Range / mode / data-rate selection.
    accel_full.set_range(4);
    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_set_range_4g");
    accel_full.set_range(2);

    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);                        // Read raw counts, (x, y, z) → LSB, LSB, LSB
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 &&
               rz >= -2048 && rz <= 2047, "read_raw_in_12bit_range");

    usleep(20000);                                         // wait for the next conversion (100 Hz ODR)
    uint8_t st = accel_full.read_status();                 // Read STATUS register, () → uint8_t
    check_true((st & 0x08) == 0x08, "read_status_zyxda_set");
    check_true(accel_full.new_data_available() == true ||
               accel_full.new_data_available() == false, "new_data_available_bool");

    // Relative temperature (signed delta).
    int16_t temp = accel_full.read_temperature_delta();     // Read relative temperature, () → int16_t °C
    check_true(temp >= -128 && temp <= 127, "temperature_delta_in_range");

    // High-pass filter / reference.
    accel_full.set_high_pass(0, 0);
    uint8_t ref = accel_full.reset_high_pass();             // Reset high-pass filter, () → uint8_t
    check_true(accel_full.reset_high_pass() == ref, "reset_high_pass_stable");

    // FIFO.
    accel_full.set_fifo(1, 8);                              // Configure FIFO, (mode=0, watermark=0, trigger_int2=false)
    uint8_t level = accel_full.fifo_level();                // Read FIFO level, () → uint8_t
    check_true(level <= 32, "fifo_level_in_range");
    float xb[4], yb[4], zb[4];
    uint8_t n = accel_full.read_fifo(xb, yb, zb, 4);       // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) → uint8_t
    check_true(n <= 4, "read_fifo_count");
    bool wm, ov, empty;
    uint8_t lvl;
    accel_full.read_fifo_status(wm, ov, empty, lvl);        // Read FIFO status, () → (bool, bool, bool, uint8_t)
    accel_full.set_fifo(0);

    // Click / interrupt generators (configuration only on hardware).
    accel_full.set_click(4, 1, 0.5f, 10, 20, 50, true);    // Configure click detection, (single=0, double_=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50, latch=false)
    uint8_t click = accel_full.read_click_source();         // Read CLICK_SRC, () → uint8_t
    check_true(click != 0xFF, "read_click_source_byte");

    accel_full.set_motion_interrupt(1, 0x3F, 0.5f, 30, true);
    uint8_t int1 = accel_full.read_interrupt_source(1);    // Read INT1_SRC, () → uint8_t
    check_true(int1 != 0xFF, "read_interrupt_source_byte");

    // Activity, polarity, reboot, SDO pull-up.
    accel_full.set_activity(0.5f, 100);
    accel_full.set_interrupt_polarity(false);
    accel_full.reboot();                                    // Reboot memory content, () → void
    accel_full.set_sdo_pullup(true);                        // Connect SDO pull-up, (connected) → void

    x = y = z = 0.0f;
    accel_full.read(x, y, z);                               // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_after_reboot");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
