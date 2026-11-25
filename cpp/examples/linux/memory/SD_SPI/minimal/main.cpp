#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>
#include <cstdlib>
#include "SPIConnectionLinux.h"
#include "OutputPinLinux.h"
#include "SD_SPI.h"

// Wiring: SPI bus plus a free GPIO for the card's chip select (default line 25 on /dev/gpiochip0):
// the kernel cannot hold chip select across calls, so the connection drives CS itself (SPI_NO_CS).
// Override with TEST_SPI_BUS, TEST_SPI_DEVICE, TEST_GPIOCHIP, TEST_CS_LINE (compile-time -D macros).
#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif
#ifndef TEST_GPIOCHIP
#define TEST_GPIOCHIP "/dev/gpiochip0"
#endif
#ifndef TEST_CS_LINE
#define TEST_CS_LINE 25
#endif

#define PF printf
#define DELAY_MS(ms) usleep((ms) * 1000)
static uint32_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint32_t>(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void print_hex(const uint8_t* p, int n) {
    for (int i = 0; i < n; i++) PF("%02X ", p[i]);
    PF("\n");
}

static void app(SPIExtensions& spi) {
    SDSPIMinimal::Options opt;                                                // Driver options, (speed_hz=12000000, now_ms=nullptr, crc=false)
    opt.now_ms = now_ms;
    SDSPIMinimal sd(spi, opt);                                                // Create SD driver and init the card, (spi, options) → SDSPIMinimal

    while (sd.status() != BlockError::Ok) {
        PF("no SD card (error %d), retrying\n", static_cast<int>(sd.status()));
        DELAY_MS(2000);
        sd.begin();                                                           // Re-run card init, () → BlockError
    }

    PF("capacity: %lu blocks = %lu MiB\n",
       static_cast<unsigned long>(sd.block_count()),                          // Capacity in 512-byte blocks, () → uint32 blocks
       static_cast<unsigned long>(sd.block_count() / 2048));
    static uint8_t block[512];
    while (true) {
        BlockError e = sd.read_block(0, block);                               // Read one 512-byte block, (block, buf) → BlockError
        PF("block 0 (%d): ", static_cast<int>(e));
        print_hex(block, 16);
        PF("boot signature: %02X%02X\n", block[510], block[511]);
        DELAY_MS(2000);
    }
}

int main() {
    OutputPinLinux cs(TEST_GPIOCHIP, TEST_CS_LINE, true);                      // Create CS pin, (chip=/dev/gpiochip0, line=25, initial_high=true) → OutputPinLinux
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 400000,    // Create SPI connection with GPIO chip select, (bus=0, device=0, mode=0, max_speed_hz=400e3, ..., csPin) → SPIConnectionLinux
                                  0x80, 0, nullptr, nullptr, true, &cs);
    app(connection);
    return 0;
}
