#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MAX44009.h"

int main() {
    I2CConnectionLinux connection(1, MAX44009Minimal::I2C_ADDRESS);
    MAX44009Minimal sensor(connection);                 // Create MAX44009 driver, (connection)
    for (int i = 0; i < 10; i++) {
        float lx = sensor.lux();                        // Read illuminance, () → float lux
        printf("lux %.2f lx\n", (double)lx);
        usleep(1000UL * 1000UL);
    }
    return 0;
}
