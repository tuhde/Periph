#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LSM303.h"

#define LOG(...) printf(__VA_ARGS__)
static void lsm303_sleep_ms(uint32_t ms) { sleep_ms(ms); }
static uint32_t lsm303_now_ms() { return to_ms_since_boot(get_absolute_time()); }
static void on_int(uint16_t status) { printf("interrupt status: 0x%04X\n", status); }

int main(void) {
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();
    I2CConnectionPicoSDK accel_connection(i2c0, 0x19);
    I2CConnectionPicoSDK mag_connection(i2c0, 0x1E);
    LSM303Minimal compass(accel_connection, mag_connection);
    for (int i = 0; i < 10; i++) {
        float ax, ay, az, mx, my, mz;
        compass.read_acceleration(ax, ay, az);              // Read 3-axis acceleration, (x, y, z) -> g, g, g
        compass.read_magnetic(mx, my, mz);                  // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
        LOG("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) uT\n", ax, ay, az, mx, my, mz);
        lsm303_sleep_ms(100);
    }
    return 0;
}
