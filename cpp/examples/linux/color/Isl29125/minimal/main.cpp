#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Isl29125.h"

int main() {
    I2CConnectionLinux connection(1, Isl29125Minimal::I2C_ADDRESS);
    Isl29125Minimal sensor(connection);                             // Create ISL29125 driver, (connection)
    for (int i = 0; i < 10; i++) {
        Isl29125Counts c = {0, 0, 0};
        sensor.read(c);                                             // Read the three channels, (counts&) → bool
        printf("R %lu G %lu B %lu\n", (unsigned long)(c.red), (unsigned long)(c.green), (unsigned long)(c.blue));
        usleep(1000 * 1000);
    }
    return 0;
}
