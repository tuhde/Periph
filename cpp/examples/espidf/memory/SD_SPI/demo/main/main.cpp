#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "SPIConnectionESPIDF.h"
#include "SD_SPI.h"

static const int MOSI_PIN = 23;
static const int MISO_PIN = 19;
static const int SCLK_PIN = 18;
static const int CS_PIN   = 5;

#define PF printf
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
static uint32_t now_ms() { return static_cast<uint32_t>(esp_timer_get_time() / 1000); }

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

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num   = MOSI_PIN;
    bus_cfg.miso_io_num   = MISO_PIN;
    bus_cfg.sclk_io_num   = SCLK_PIN;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode            = 0;
    dev_cfg.clock_speed_hz  = 400000;          // SD init needs <= 400 kHz; the driver raises it afterwards
    dev_cfg.spics_io_num    = -1;              // CS is driven manually: the SD init needs clocks with CS high
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    vTaskDelay(pdMS_TO_TICKS(2000));
    SPIConnectionESPIDF connection(dev);                                      // Create SPI connection, (dev) → SPIConnectionESPIDF
    connection.setManualCS(static_cast<gpio_num_t>(CS_PIN));                  // Drive CS from a GPIO, (cs_gpio)
    connection.enableSpeedChange(SPI2_HOST, dev_cfg);                         // Allow set_speed() to re-add the device, (host, dev_cfg)
    app(connection);
}
