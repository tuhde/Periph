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
