#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Apds9250.h"

int main() {
    I2CConnectionLinux connection(1, Apds9250Minimal::I2C_ADDRESS);
    Apds9250Minimal sensor(connection);                             // Create APDS-9250 driver, (connection)
    for (int i = 0; i < 10; i++) {
        Apds9250Counts c = {0, 0, 0, 0};
        sensor.read(c);                                             // Read the four channels, (counts&) → bool
        printf("R %lu G %lu B %lu IR %lu\n", (unsigned long)(c.red), (unsigned long)(c.green), (unsigned long)(c.blue), (unsigned long)(c.ir));
        usleep(1000 * 1000);
    }
    return 0;
}
