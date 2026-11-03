#include <string.h>
#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "MX25L12845E.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

#define PF printf
#define DELAY_MS(ms) sleep_ms(ms)
static uint32_t now_ms() { return to_ms_since_boot(get_absolute_time()); }
static void delay_ms(uint32_t ms) { sleep_ms(ms); }
// All writes and erases stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
static const uint32_t SCRATCH = 0xFF0000;

static void app(Connection& connection, MX25L12845EOptions& opt) {
    static MX25L12845EMinimal flash(connection, opt);                                 // Create MX25L12845E driver and check the ID, (connection, options) → MX25L12845EMinimal
    if (flash.status() != BlockError::Ok) {                                    // Result of init, () → BlockError
        PF("MX25L12845E not found (error %d)\n", static_cast<int>(flash.status()));
        while (true) DELAY_MS(1000);
    }
    uint8_t id[3];
    flash.jedec_id(id);                                                        // Read JEDEC ID, (out[3]) → BlockError
    PF("JEDEC ID %02X %02X %02X, %lu MiB\n", id[0], id[1], id[2], static_cast<unsigned long>(flash.size() / (1024 * 1024)));  // Capacity in bytes, () → uint32 bytes

    for (uint8_t counter = 0; counter < 10; counter++) {
        flash.write_bytes(SCRATCH, &counter, 1);                               // Write bytes (erases the sector when needed), (address, data, len) → BlockError
        uint8_t value = 0;
        flash.read_bytes(SCRATCH, &value, 1);                                  // Read bytes, (address, buf, len) → BlockError
        PF("wrote 0x%02X, read 0x%02X\n", counter, value);
        DELAY_MS(500);
    }
    flash.erase_sector(SCRATCH);                                               // Erase one 4 KiB sector, (address) → BlockError
    PF("done, scratch sector erased\n");
    while (true) DELAY_MS(1000);
}

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 8000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                             // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    MX25L12845EOptions opt;                                                    // Driver options, (now_ms=nullptr, delay_ms=nullptr, auto_erase=false)
    opt.now_ms = now_ms;
    opt.delay_ms = delay_ms;
    app(connection, opt);
    return 0;
}
