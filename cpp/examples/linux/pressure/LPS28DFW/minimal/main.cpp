#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

#include <cstdio>
#include "I2CConnectionLinux.h"
#include "LPS28DFW.h"

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    LPS28DFWMinimal lps(connection);                            // Create LPS28DFW driver, (connection)

    for (int i = 0; i < 5; i++) {
        float t = lps.read_temperature();                       // Read temperature, () → float °C
        float p = lps.read_pressure();                          // Read pressure, () → float hPa
        printf("%.1f C, %.1f hPa\n", t, p);
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
