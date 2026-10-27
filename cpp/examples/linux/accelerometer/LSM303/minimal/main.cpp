#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <time.h>
#include "I2CConnectionLinux.h"
#include "LSM303.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#define LOG(...) printf(__VA_ARGS__)

static void lsm303_sleep_ms(uint32_t ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000};
    nanosleep(&ts, nullptr);
}
static uint32_t lsm303_now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}
I2CConnectionLinux accel_connection(TEST_I2C_BUS, 0x19);
I2CConnectionLinux mag_connection(TEST_I2C_BUS, 0x1E);
static void on_int(uint16_t status) { printf("interrupt status: 0x%04X\n", status); }

int main(void) {
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
