#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <time.h>
#include "I2CConnectionLinux.h"
#include "LIS3DH.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

I2CConnectionLinux connection(TEST_I2C_BUS, 0x18);
LIS3DHMinimal accel(connection);                                // Create LIS3DH driver, (connection)
static void lis3dh_sleep_ms(uint32_t ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000};
    nanosleep(&ts, nullptr);
}

int main(void) {
    for (int i = 0; i < 10; i++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        printf("x=%.3f y=%.3f z=%.3f g\n", x, y, z);
        lis3dh_sleep_ms(100);
    }
    return 0;
}
