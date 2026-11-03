#include <zephyr/kernel.h>
#include <string.h>

#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "MX25L12845E.h"

#ifndef MX25L12845E_SPI_NODE
#define MX25L12845E_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef MX25L12845E_CS_GPIOS
#define MX25L12845E_CS_GPIOS GPIO_DT_SPEC_GET_BY_IDX(MX25L12845E_SPI_NODE, cs_gpios, 0)
#endif

#define PF printk
#define DELAY_MS(ms) k_msleep(ms)
static uint32_t now_ms() { return k_uptime_get_32(); }
static void delay_ms(uint32_t ms) { k_msleep(ms); }
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
    k_msleep(2000);
    const struct device *dev = DEVICE_DT_GET(MX25L12845E_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 8000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = MX25L12845E_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                                  // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    MX25L12845EOptions opt;                                                    // Driver options, (now_ms=nullptr, delay_ms=nullptr, auto_erase=false)
    opt.now_ms = now_ms;
    opt.delay_ms = delay_ms;
    app(connection, opt);
    return 0;
}
