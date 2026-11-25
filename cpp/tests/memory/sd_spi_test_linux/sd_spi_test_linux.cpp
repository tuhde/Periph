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

static int passed = 0, failed = 0;
static void check_true(bool cond, const char *label) {
    if (cond) { PF("PASS %s\n", label); passed++; }
    else       { PF("FAIL %s\n", label); failed++; }
}

#ifdef __AVR__
#define SCRATCH_BLOCKS 2
#else
#define SCRATCH_BLOCKS 8
#endif

static void app(SPIExtensions& spi) {
    SDSPIFull::Options opt = SDSPIFull::defaults();
    opt.now_ms = now_ms;
    SDSPIFull sd(spi, opt);
    check_true(sd.status() == BlockError::Ok, "init");
    if (sd.status() != BlockError::Ok) {
        PF("===DONE: %d passed, %d failed===\n", passed, failed);
        while (true) DELAY_MS(1000);
    }
    PF("type=%d blocks=%lu name=%s\n", static_cast<int>(sd.card_type()),
       static_cast<unsigned long>(sd.block_count()), sd.info().name);
    check_true(sd.card_type() != SDSPIMinimal::CardType::None, "card_type_detected");
    check_true(sd.block_count() > 0 && sd.block_size() == 512, "geometry");
    check_true(sd.erase_size() == 512 && !sd.needs_erase(), "no_erase_needed");
    check_true(sd.is_present(), "is_present");

    uint8_t cid[16];
    check_true(sd.cid(cid) == BlockError::Ok, "cid_read");
    uint32_t ocr = 0;
    check_true(sd.ocr(&ocr) == BlockError::Ok && (ocr & 0x00FF8000), "ocr_has_3v3_window");

    // Scratch area: the last blocks, contents saved and restored.
    static uint8_t saved[SCRATCH_BLOCKS * 512], pat[SCRATCH_BLOCKS * 512], back[SCRATCH_BLOCKS * 512];
    uint32_t scratch = sd.block_count() - SCRATCH_BLOCKS;
    check_true(sd.read(scratch, saved, SCRATCH_BLOCKS) == BlockError::Ok, "save_scratch");
    for (size_t i = 0; i < sizeof(pat); i++) pat[i] = static_cast<uint8_t>(i * 7 + 3);

    check_true(sd.write(scratch, pat, 1) == BlockError::Ok, "write_single");
    check_true(sd.read(scratch, back, 1) == BlockError::Ok && !memcmp(pat, back, 512), "single_roundtrip");
    memset(back, 0, sizeof(back));
    check_true(sd.write(scratch, pat, SCRATCH_BLOCKS) == BlockError::Ok, "write_multi");
    check_true(sd.sync() == BlockError::Ok, "sync");
    check_true(sd.read(scratch, back, SCRATCH_BLOCKS) == BlockError::Ok && !memcmp(pat, back, sizeof(pat)), "multi_roundtrip");
    check_true(sd.read(sd.block_count(), back, 1) == BlockError::OutOfRange, "out_of_range");

    check_true(sd.set_crc(false) == BlockError::Ok, "crc_off");
    check_true(sd.read(scratch, back, 1) == BlockError::Ok && !memcmp(pat, back, 512), "read_with_crc_off");
    check_true(sd.set_crc(true) == BlockError::Ok, "crc_on");
    check_true(sd.set_speed(8000000), "set_speed_8mhz");
    check_true(sd.read(scratch, back, SCRATCH_BLOCKS) == BlockError::Ok && !memcmp(pat, back, sizeof(pat)), "read_at_higher_speed");

    check_true(sd.write(scratch, saved, SCRATCH_BLOCKS) == BlockError::Ok, "restore_scratch");
    check_true(sd.sync() == BlockError::Ok, "final_sync");

    PF("===DONE: %d passed, %d failed===\n", passed, failed);
    while (true) DELAY_MS(1000);
}

int main() {
    OutputPinLinux cs(TEST_GPIOCHIP, TEST_CS_LINE, true);                      // Create CS pin, (chip=/dev/gpiochip0, line=25, initial_high=true) → OutputPinLinux
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 400000,    // Create SPI connection with GPIO chip select, (bus=0, device=0, mode=0, max_speed_hz=400e3, ..., csPin) → SPIConnectionLinux
                                  0x80, 0, nullptr, nullptr, true, &cs);
    app(connection);
    return 0;
}
