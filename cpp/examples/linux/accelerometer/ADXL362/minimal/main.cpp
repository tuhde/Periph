#include <cstdio>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "ADXL362.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 8000000);    // Create SPI connection, (bus=0, device=0, mode=0, max_speed_hz=8e6) → SPIConnectionLinux
    ADXL362Minimal accel(connection);                                              // Create ADXL362 driver, (connection) → ADXL362Minimal

    for (;;) {
        float x, y, z;
        accel.read(x, y, z);                                                       // Read 3-axis acceleration, (x, y, z) → g, g, g
        printf("x=%+.3f  y=%+.3f  z=%+.3f g\n", x, y, z);
        usleep(100000);
    }
    return 0;
}