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
    SRAM23X1024Full sram(connection);                                        // Create 23X1024 Full driver, (connection) → SRAM23X1024Full
                                                                             // writes MODE=0x40 (Sequential) and verifies it with RDMR

    printf("size: %lu bytes\n", (unsigned long)sram.size());              // Array size, () → uint32_t bytes
                                                                             // always 131072

    sram.write_byte(0x00010, 0xA5);                                          // Write one byte, (address 0–131071, value 0–255)
                                                                             // WRITE 0x02 + 24-bit address + data
    printf("byte @0x10: 0x%02X\n", sram.read_byte(0x00010));               // Read one byte, (address 0–131071) → byte
                                                                             // READ 0x03 + 24-bit address

    uint8_t pattern[300];
    for (int i = 0; i < 300; i++) pattern[i] = (uint8_t)i;
    sram.write(0x00100, pattern, sizeof(pattern));                           // Block write, (address, data, length)
                                                                             // split into transactions of at most max_chunk() bytes
    uint8_t readback[300];
    sram.read(0x00100, readback, sizeof(readback));                          // Block read, (address, buf, length)
                                                                             // sequential read across the chunk boundary
    printf("block of 300: %s\n", memcmp(pattern, readback, sizeof(pattern)) == 0 ? "match" : "MISMATCH");

    sram.fill(0x00200, 64, 0xFF);                                            // Fill a range, (address, length, value=0)
                                                                             // writes value over the range, chunked
    printf("fill @0x200: 0x%02X\n", sram.read_byte(0x00200));

    sram.set_max_chunk(64);                                                  // Limit bytes per transaction, (n 1–256)
                                                                             // smaller frames for bus layers with small buffers
    printf("max_chunk: %u\n", (unsigned)sram.max_chunk());                // Current chunk limit, () → size_t bytes
    sram.set_max_chunk(256);                                                 // Limit bytes per transaction, (n 1–256)

    sram.set_mode(SRAM23X1024Full::MODE_PAGE);                               // Select operating mode, (mode=MODE_PAGE=0x80)
                                                                             // WRMR then RDMR check; accesses now wrap within 32-byte pages
    printf("mode: 0x%02X\n", sram.get_mode());                            // Read operating mode, () → byte
                                                                             // RDMR bits 7:6
    sram.write(0x00110, pattern, 64);                                        // Block write, (address, data, length)
                                                                             // the driver splits at page boundaries, so the data stays linear
    sram.read(0x00110, readback, 64);                                        // Block read, (address, buf, length)
    printf("page mode 64 bytes: %s\n", memcmp(pattern, readback, 64) == 0 ? "match" : "MISMATCH");

    sram.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);                         // Select operating mode, (mode=MODE_SEQUENTIAL=0x40)
    sram.reset_io();                                                         // Return to SPI mode, ()
                                                                             // sends RSTIO (0xFF); harmless while already in SPI mode
    return 0;
}
