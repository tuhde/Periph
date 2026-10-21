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
