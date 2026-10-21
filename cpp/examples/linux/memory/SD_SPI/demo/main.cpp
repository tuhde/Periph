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

#ifdef __AVR__
#define DEMO_BLOCKS 4
#else
#define DEMO_BLOCKS 64
#endif

static void app(SPIExtensions& spi) {
    // --- Bring up the card ---
    // The card starts in SD mode; the driver sends 80 clocks with CS high, CMD0 with CS
    // low to enter SPI mode, then negotiates the card type. SCK runs at 400 kHz or below
    // during init and is raised to the configured speed afterwards.
    SDSPIFull::Options opt = SDSPIFull::defaults();                           // Options with CRC16 checking on, () → Options
    opt.now_ms = now_ms;
    SDSPIFull sd(spi, opt);                                                   // Create SD driver and init the card, (spi, options) → SDSPIFull
    if (sd.status() != BlockError::Ok) {
        PF("no SD card (error %d)\n", static_cast<int>(sd.status()));
        while (true) DELAY_MS(1000);
    }
    BlockInfo info = sd.info();                                               // Geometry and capabilities, () → BlockInfo
    PF("card %s, %lu MiB\n", info.name, static_cast<unsigned long>(info.block_count / 2048));

    // --- Log samples to a raw block range (filesystem-free data logger) ---
    // Each 512-byte block holds a header (sequence number, tick) and 126 samples.
    // The last blocks of the card are used and restored afterwards, so a card that
    // carries a filesystem keeps its data (only the tail of the volume is touched).
    static uint8_t saved[DEMO_BLOCKS * 512], blockbuf[512], readback[512];
    uint32_t first = sd.block_count() - DEMO_BLOCKS;
    sd.read(first, saved, DEMO_BLOCKS);                                       // Read blocks, (block, buf, count) → BlockError
    uint32_t t0 = now_ms();
    for (uint32_t seq = 0; seq < DEMO_BLOCKS; seq++) {
        uint32_t words[128];
        words[0] = seq;
        words[1] = now_ms();
        for (int i = 2; i < 128; i++) words[i] = seq * 1000 + static_cast<uint32_t>(i);
        memcpy(blockbuf, words, sizeof(words));
        sd.write(first + seq, blockbuf, 1);                                   // Write blocks, (block, data, count) → BlockError
    }
    sd.sync();                                                                // Wait until programming is done, () → BlockError
    uint32_t t1 = now_ms();
    PF("wrote %d blocks in %lu ms\n", DEMO_BLOCKS, static_cast<unsigned long>(t1 - t0));

    // --- Verify by reading back ---
    // Read every block back and recompute the expected sample values; any single
    // mismatch (or a CRC16 error reported by the driver) is counted.
    uint32_t bad = 0;
    for (uint32_t seq = 0; seq < DEMO_BLOCKS; seq++) {
        if (sd.read(first + seq, readback, 1) != BlockError::Ok) { bad++; continue; }  // Read blocks, (block, buf, count) → BlockError
        uint32_t words[128];
        memcpy(words, readback, sizeof(words));
        if (words[0] != seq) bad++;
        for (int i = 2; i < 128; i++) if (words[i] != seq * 1000 + static_cast<uint32_t>(i)) { bad++; break; }
    }
    PF("verify: %lu bad blocks\n", static_cast<unsigned long>(bad));

    // --- Restore the original contents ---
    sd.write(first, saved, DEMO_BLOCKS);
    sd.sync();
    PF("restored\n");
    while (true) DELAY_MS(1000);
}

int main() {
    OutputPinLinux cs(TEST_GPIOCHIP, TEST_CS_LINE, true);                      // Create CS pin, (chip=/dev/gpiochip0, line=25, initial_high=true) → OutputPinLinux
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 400000,    // Create SPI connection with GPIO chip select, (bus=0, device=0, mode=0, max_speed_hz=400e3, ..., csPin) → SPIConnectionLinux
                                  0x80, 0, nullptr, nullptr, true, &cs);
    app(connection);
    return 0;
}
