#include <string.h>
#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "SD_SPI.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

#define PF printf
#define DELAY_MS(ms) sleep_ms(ms)
static uint32_t now_ms() { return to_ms_since_boot(get_absolute_time()); }

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

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 400000);                    // SD init needs <= 400 kHz; the driver raises it afterwards
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                            // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    app(connection);
    return 0;
}
