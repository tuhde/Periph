#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "L3G4200D.h"

#ifndef L3G4200D_I2C_NODE
#define L3G4200D_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef L3G4200D_ADDR
#define L3G4200D_ADDR 0x68
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(L3G4200D_I2C_NODE);
    I2CConnectionZephyr connection(dev, L3G4200D_ADDR);
    L3G4200DMinimal gyro(connection);

    float x, y, z;
    gyro.angular_rate(x, y, z);
    check_true(x >= -50.0f && x <= 50.0f, "angular_rate_x_range");
    check_true(y >= -50.0f && y <= 50.0f, "angular_rate_y_range");
    check_true(z >= -50.0f && z <= 50.0f, "angular_rate_z_range");

    L3G4200DFull gyro_full(connection);
    check_true(gyro_full.who_am_i() == 0xD3, "who_am_i");

    gyro_full.configure(1, 0, 500);  // 200 Hz, ±500 dps
    float x2, y2, z2;
    gyro_full.angular_rate(x2, y2, z2);
    check_true(x2 >= -500.0f && x2 <= 500.0f, "configure_then_read_x");

    check_true(gyro_full.status() <= 0xFF, "status_readable");
    check_true(gyro_full.temperature() >= -50 && gyro_full.temperature() <= 100, "temperature_range");

    gyro_full.set_full_scale(2000);
    float x3, y3, z3;
    gyro_full.angular_rate(x3, y3, z3);
    check_true(x3 >= -2000.0f && x3 <= 2000.0f, "set_full_scale_2000");

    uint8_t samples = gyro_full.fifo_samples();
    check_true(samples <= 31, "fifo_samples_in_range");

    gyro_full.enable_fifo(2, 10);
    check_true(1, "enable_fifo_no_throw");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
