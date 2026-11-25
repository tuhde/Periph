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

#ifdef __AVR__
#define SCRATCH_BLOCKS 2
#else
#define SCRATCH_BLOCKS 8
#endif

static void app(SPIExtensions& spi) {
    SDSPIFull::Options opt = SDSPIFull::defaults();                           // Options with CRC16 checking on, () → Options
    opt.now_ms = now_ms;
    SDSPIFull sd(spi, opt);                                                   // Create SD driver and init the card, (spi, options) → SDSPIFull
    if (sd.status() != BlockError::Ok) {                                      // Result of card init, () → BlockError
        PF("no SD card (error %d)\n", static_cast<int>(sd.status()));
        while (true) DELAY_MS(1000);
    }

    PF("type: %d (1=v1 2=v2 3=SDHC)\n", static_cast<int>(sd.card_type()));    // Card generation, () → CardType
    BlockInfo info = sd.info();                                               // Geometry and capabilities, () → BlockInfo
                                                                              // block size, count, erase unit, name from the CID
    PF("name=%s blocks=%lu block_size=%lu erase_size=%lu needs_erase=%d\n", info.name,
       static_cast<unsigned long>(info.block_count), static_cast<unsigned long>(info.block_size),
       static_cast<unsigned long>(info.erase_size), info.needs_erase);

    uint8_t cid[16];
    sd.cid(cid);                                                              // Read CID register, (out[16]) → BlockError
                                                                              // manufacturer, name, revision, serial, date
    PF("CID: "); print_hex(cid, 16);
    PF("CSD: "); print_hex(sd.csd(), 16);                                     // CSD register read at init, () → const uint8_t*
    uint32_t ocr = 0;
    sd.ocr(&ocr);                                                             // Read OCR, (out) → BlockError
                                                                              // voltage window and CCS (block addressing) bit
    PF("OCR: %08lX\n", static_cast<unsigned long>(ocr));
    PF("present: %d\n", sd.is_present());                                     // Card responds, () → bool

    // Use the last blocks of the card as scratch, restoring their contents.
    static uint8_t saved[SCRATCH_BLOCKS * 512], pattern[SCRATCH_BLOCKS * 512], back[SCRATCH_BLOCKS * 512];
    uint32_t scratch = sd.block_count() - SCRATCH_BLOCKS;
    sd.read(scratch, saved, SCRATCH_BLOCKS);                                  // Read blocks, (block, buf, count) → BlockError
                                                                              // CMD18 multi-block read for count > 1
    for (size_t i = 0; i < sizeof(pattern); i++) pattern[i] = static_cast<uint8_t>(i * 7 + 3);
    PF("write: %d\n", static_cast<int>(sd.write(scratch, pattern, SCRATCH_BLOCKS)));  // Write blocks, (block, data, count) → BlockError
                                                                              // CMD25 multi-block write with stop token
    PF("sync: %d\n", static_cast<int>(sd.sync()));                            // Wait until programming is done, () → BlockError
    sd.read(scratch, back, SCRATCH_BLOCKS);
    PF("roundtrip: %s\n", memcmp(pattern, back, sizeof(pattern)) == 0 ? "ok" : "MISMATCH");

    PF("set_crc(false): %d\n", static_cast<int>(sd.set_crc(false)));          // Disable CRC checking, (enabled) → BlockError
                                                                              // CMD59 off; data CRC16 is no longer verified
    sd.set_crc(true);
    PF("set_speed: %d\n", sd.set_speed(6000000));                             // Change SCK frequency, (hz) → bool
                                                                              // largest rate the platform offers that is <= hz
    PF("erase: %d\n", static_cast<int>(sd.erase(scratch, SCRATCH_BLOCKS)));   // Erase blocks, (block, count) → BlockError
                                                                              // CMD32/33/38; contents afterwards are card dependent
    sd.write(scratch, saved, SCRATCH_BLOCKS);                                 // restore the original contents

    PF("done\n");
    while (true) DELAY_MS(1000);
}

int main() {
    OutputPinLinux cs(TEST_GPIOCHIP, TEST_CS_LINE, true);                      // Create CS pin, (chip=/dev/gpiochip0, line=25, initial_high=true) → OutputPinLinux
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 400000,    // Create SPI connection with GPIO chip select, (bus=0, device=0, mode=0, max_speed_hz=400e3, ..., csPin) → SPIConnectionLinux
                                  0x80, 0, nullptr, nullptr, true, &cs);
    app(connection);
    return 0;
}
