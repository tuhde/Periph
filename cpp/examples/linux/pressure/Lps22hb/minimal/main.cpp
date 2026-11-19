#include <cstdio>
#include <cstdint>
#include <unistd.h>
#include <time.h>
#include "I2CConnectionLinux.h"
#include "Lps22hb.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    LPS22HBMinimal lps(connection);                        // Create LPS22HB driver, (connection)

    for (int i = 0; i < 5; i++) {
        float p = lps.pressure();                         // Read pressure, () → float Pa
        float t = lps.temperature();                      // Read temperature, () → float °C
        printf("%.1f Pa, %.2f C\n", p, t);
        sleep(1);
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}