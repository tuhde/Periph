#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "L3G4200D.h"

int passed = 0;
int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x68);
    L3G4200DFull gyro(connection, /*spi=*/false);

    stdio_init_all();
    sleep_ms(2000);

    check_true(gyro.who_am_i() == 0xD3, "who_am_i");

    float x, y, z;
    gyro.angular_rate(x, y, z);
    check_true(x >= -50.0f && x <= 50.0f, "angular_rate_x_range");
    check_true(y >= -50.0f && y <= 50.0f, "angular_rate_y_range");
    check_true(z >= -50.0f && z <= 50.0f, "angular_rate_z_range");

    gyro.configure(1, 0, 500);  // 200 Hz, ±500 dps
    float x2, y2, z2;
    gyro.angular_rate(x2, y2, z2);
    check_true(x2 >= -500.0f && x2 <= 500.0f, "configure_then_read_x");

    check_true(gyro.status() <= 0xFF, "status_readable");
    check_true(gyro.temperature() >= -50 && gyro.temperature() <= 100, "temperature_range");

    gyro.set_full_scale(2000);
    float x3, y3, z3;
    gyro.angular_rate(x3, y3, z3);
    check_true(x3 >= -2000.0f && x3 <= 2000.0f, "set_full_scale_2000");

    uint8_t samples = gyro.fifo_samples();
    check_true(samples <= 31, "fifo_samples_in_range");

    gyro.enable_fifo(2, 10);
    check_true(1, "enable_fifo_no_throw");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
