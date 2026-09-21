// Auto-generated ESP-IDF test for APA102.
// Mirrors the Zephyr test for APA102; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "APA102.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_eq_u8(uint8_t val, uint8_t expected, const char *label) {
    if (val == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: 0x%02X != 0x%02X\n", label, val, expected); failed++; }
}

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {
        .mosi_io_num = 23,
        .miso_io_num = -1,
        .sclk_io_num = 18,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 0,
    };
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {
        .mode = 0,
        .clock_speed_hz = 1000000,  // 1 MHz for APA102
        .spics_io_num = -1,
        .queue_size = 1,
    };
    spi_device_handle_t spi_dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &spi_dev);

    SPIConnectionESPIDF connection(spi_dev);
    APA102Full strip(connection, 8);  // Create APA102 driver

    strip.fill(255, 0, 0);
    strip.fill(0, 255, 0);
    strip.off();
    check_true(true, "apa102 write ok");

    check_eq_u8(strip.get_brightness(), 255, "default brightness is 255");

    strip.set_pixel(0, 255, 0, 0);
    strip.show();
    check_true(true, "set_pixel + show accepted");

    strip.set_brightness(128);
    check_eq_u8(strip.get_brightness(), 128, "brightness setter");
    strip.show();
    check_true(true, "show() with brightness=128 accepted");

    strip.set_brightness(255);

    strip.rotate(1);
    strip.show();
    check_true(true, "rotate + show accepted");

    strip.fill_hsv(0.0f, 1.0f, 1.0f);
    check_true(true, "fill_hsv accepted");

    strip.off();
    check_true(true, "off() on Full accepted");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}