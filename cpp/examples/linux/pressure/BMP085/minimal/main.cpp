#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x77
#endif

#include <cstdio>
#include "I2CConnectionLinux.h"
#include "BMP085.h"

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    BMP085Minimal bmp(connection);                          // Create BMP085 driver, (connection)

    for (int i = 0; i < 5; i++) {
        float t = bmp.temperature();                        // Read temperature, () → float C
        float p = bmp.pressure();                          // Read pressure, () → float Pa
        printf("%.1f C, %.1f Pa\n", t, p);
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
