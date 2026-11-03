#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "MX25L12845E.h"

static const int MOSI_PIN = 23;
static const int MISO_PIN = 19;
static const int SCLK_PIN = 18;
static const int CS_PIN   = 5;

#define PF printf
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
static uint32_t now_ms() { return static_cast<uint32_t>(esp_timer_get_time() / 1000); }
static void delay_ms(uint32_t ms) { vTaskDelay(pdMS_TO_TICKS(ms ? ms : 1)); }
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

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num   = MOSI_PIN;
    bus_cfg.miso_io_num   = MISO_PIN;
    bus_cfg.sclk_io_num   = SCLK_PIN;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    bus_cfg.max_transfer_sz = 4096;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode            = 0;
    dev_cfg.clock_speed_hz  = 8000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    vTaskDelay(pdMS_TO_TICKS(2000));
    SPIConnectionESPIDF connection(dev);                                       // Create SPI connection, (dev) → SPIConnectionESPIDF
    MX25L12845EOptions opt;                                                    // Driver options, (now_ms=nullptr, delay_ms=nullptr, auto_erase=false)
    opt.now_ms = now_ms;
    opt.delay_ms = delay_ms;
    app(connection, opt);
}
