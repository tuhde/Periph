#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Si1132.h"

int main() {
    I2CConnectionLinux connection(1, Si1132Minimal::I2C_ADDRESS);
    Si1132Minimal sensor(connection);                               // Create Si1132 driver, (connection)
    sensor.init();                                                  // Initialize the chip, () → bool
    for (int i = 0; i < 10; i++) {
        float uv = 0; uint16_t vis = 0, ir = 0;
        sensor.read(uv, vis, ir);                                   // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
        printf("UV %.2f visible %lu infrared %lu\n", (double)(uv), (unsigned long)(vis), (unsigned long)(ir));
        usleep(1000 * 1000);
    }
    return 0;
}
