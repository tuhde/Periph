#include <string.h>
#include <math.h>
#include <time.h>
#include <stdint.h>
#include <cstdio>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "23X1024.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 8000000);    // Create SPI connection, (bus=0, device=0, mode=0, max_speed_hz=8e6) → SPIConnectionLinux
    SRAM23X1024Minimal sram(connection);                                     // Create 23X1024 driver, (connection) → SRAM23X1024Minimal

    uint8_t counter = 0;
    while (true) {
        sram.write_byte(0x00000, counter);                                   // Write one byte, (address 0–131071, value 0–255)
        uint8_t value = sram.read_byte(0x00000);                             // Read one byte, (address 0–131071) → byte
        printf("wrote 0x%02X, read 0x%02X\n", counter, value);
        counter++;
        usleep((500) * 1000);
    }
    return 0;
}
