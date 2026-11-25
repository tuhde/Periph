#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <time.h>
#include "I2CConnectionLinux.h"
#include "FXOS8700.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

static void sleepMs(uint32_t ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000};
    nanosleep(&ts, nullptr);
}
static void printVec(const char* label, float x, float y, float z, const char* unit) {
    printf("%s x=%.3f y=%.3f z=%.3f %s\n", label, x, y, z, unit);
}

int main(void) {
    I2CConnectionLinux connection(TEST_I2C_BUS, 0x1E);
    FXOS8700Minimal compass(connection);                    // Create FXOS8700 driver, (connection)
    for (int i = 0; i < 10; i++) {
        float ax, ay, az, mx, my, mz;
        compass.readAccel(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
        compass.readMag(mx, my, mz);                        // Read magnetic field, (x, y, z) → µT, µT, µT
        printVec("accel", ax, ay, az, "g");
        printVec("mag", mx, my, mz, "uT");
        sleepMs(500);
    }
    return 0;
}
