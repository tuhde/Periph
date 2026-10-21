#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "LIS3DH.h"

#ifndef LIS3DH_I2C_NODE
#define LIS3DH_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LIS3DH_ADDR
#define LIS3DH_ADDR 0x18
#endif

static int passed = 0, failed = 0;

#define LIS3DH_WAIT() k_sleep(K_MSEC(20))

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LIS3DH_I2C_NODE);
    I2CConnectionZephyr connection(dev, LIS3DH_ADDR);
    LIS3DHMinimal accel(connection);                        // Create LIS3DH driver, (connection)
    LIS3DHFull accel_full(connection);                      // Create LIS3DH Full driver, (connection)

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_returns_floats");
    float mag = sqrtf(x * x + y * y + z * z);
    check_true(mag >= 0.5f && mag <= 1.5f, "magnitude_near_1g");

    accel_full.set_range(4);
    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_set_range_4g");
    accel_full.set_range(2);

    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);                        // Read raw counts, (x, y, z) → LSB, LSB, LSB
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 &&
               rz >= -2048 && rz <= 2047, "read_raw_in_12bit_range");

    int16_t temp = accel_full.read_temperature_delta();     // Read relative temperature, () → int16_t °C
    check_true(temp >= -128 && temp <= 127, "temperature_delta_in_range");

    accel_full.set_high_pass(0, 0);
    uint8_t ref = accel_full.reset_high_pass();             // Reset high-pass filter, () → uint8_t
    check_true(accel_full.reset_high_pass() == ref, "reset_high_pass_stable");

    LIS3DH_WAIT();                                         // wait for the next conversion (100 Hz ODR)
    uint8_t st = accel_full.read_status();                 // Read STATUS register, () → uint8_t
    check_true((st & 0x08) == 0x08, "read_status_zyxda_set");

    accel_full.set_fifo(LIS3DHFull::FIFO_FIFO, 8, false);   // Configure FIFO, (mode, watermark, trigger_int2)
    uint8_t level = accel_full.fifo_level();                // Read FIFO level, () → uint8_t
    check_true(level <= 32, "fifo_level_in_range");
    float xb[4], yb[4], zb[4];
    uint8_t n = accel_full.read_fifo(xb, yb, zb, 4);       // Drain FIFO, (x_buf, y_buf, z_buf, max_samples)
    check_true(n <= 4, "read_fifo_count");
    accel_full.set_fifo(LIS3DHFull::FIFO_BYPASS);

    accel_full.set_click(4, 1, 0.5f, 10, 20, 50, true);    // Configure click detection
    uint8_t click = accel_full.read_click_source();         // Read CLICK_SRC, () → uint8_t
    check_true(click != 0xFF, "read_click_source_byte");

    accel_full.set_motion_interrupt(1, 0x3F, 0.5f, 30, true);
    uint8_t int1 = accel_full.read_interrupt_source(1);    // Read INT1_SRC, () → uint8_t
    check_true(int1 != 0xFF, "read_interrupt_source_byte");

    accel_full.set_activity(0.5f, 100);
    accel_full.set_interrupt_polarity(false);
    accel_full.reboot();                                    // Reboot memory content, () → void
    accel_full.set_sdo_pullup(true);                        // Connect SDO pull-up, (connected) → void

    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_reboot");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
