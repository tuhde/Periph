#include <SPI.h>
#include <Periph.h>
#include <string.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

// Formatted output over Serial (AVR has no printf() to Serial).
static void out(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

void setup() {
    Serial.begin(115200);
    SPI.begin();
    delay(2000);

    SPISettings settings(8000000, MSBFIRST, SPI_MODE0);
    SPIConnection connection(SPI, TEST_CS_PIN, settings);                        // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
    SRAM23X1024Full sram(connection);                                        // Create 23X1024 Full driver, (connection) → SRAM23X1024Full
                                                                             // writes MODE=0x40 (Sequential) and verifies it with RDMR

    out("size: %lu bytes\n", (unsigned long)sram.size());              // Array size, () → uint32_t bytes
                                                                             // always 131072

    sram.write_byte(0x00010, 0xA5);                                          // Write one byte, (address 0–131071, value 0–255)
                                                                             // WRITE 0x02 + 24-bit address + data
    out("byte @0x10: 0x%02X\n", sram.read_byte(0x00010));               // Read one byte, (address 0–131071) → byte
                                                                             // READ 0x03 + 24-bit address

    uint8_t pattern[300];
    for (int i = 0; i < 300; i++) pattern[i] = (uint8_t)i;
    sram.write(0x00100, pattern, sizeof(pattern));                           // Block write, (address, data, length)
                                                                             // split into transactions of at most max_chunk() bytes
    uint8_t readback[300];
    sram.read(0x00100, readback, sizeof(readback));                          // Block read, (address, buf, length)
                                                                             // sequential read across the chunk boundary
    out("block of 300: %s\n", memcmp(pattern, readback, sizeof(pattern)) == 0 ? "match" : "MISMATCH");

    sram.fill(0x00200, 64, 0xFF);                                            // Fill a range, (address, length, value=0)
                                                                             // writes value over the range, chunked
    out("fill @0x200: 0x%02X\n", sram.read_byte(0x00200));

    sram.set_max_chunk(64);                                                  // Limit bytes per transaction, (n 1–256)
                                                                             // smaller frames for bus layers with small buffers
    out("max_chunk: %u\n", (unsigned)sram.max_chunk());                // Current chunk limit, () → size_t bytes
    sram.set_max_chunk(256);                                                 // Limit bytes per transaction, (n 1–256)

    sram.set_mode(SRAM23X1024Full::MODE_PAGE);                               // Select operating mode, (mode=MODE_PAGE=0x80)
                                                                             // WRMR then RDMR check; accesses now wrap within 32-byte pages
    out("mode: 0x%02X\n", sram.get_mode());                            // Read operating mode, () → byte
                                                                             // RDMR bits 7:6
    sram.write(0x00110, pattern, 64);                                        // Block write, (address, data, length)
                                                                             // the driver splits at page boundaries, so the data stays linear
    sram.read(0x00110, readback, 64);                                        // Block read, (address, buf, length)
    out("page mode 64 bytes: %s\n", memcmp(pattern, readback, 64) == 0 ? "match" : "MISMATCH");

    sram.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);                         // Select operating mode, (mode=MODE_SEQUENTIAL=0x40)
    sram.reset_io();                                                         // Return to SPI mode, ()
                                                                             // sends RSTIO (0xFF); harmless while already in SPI mode
}

void loop() {
}
