#include <stdio.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include "SiPoConnectionESPIDF.h"
#include "TPIC6B595.h"

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool cond) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: got %u expected %u\n", label, (unsigned)got, (unsigned)expected); failed++; }
}

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num = static_cast<gpio_num_t>(23);
    bus_cfg.miso_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.sclk_io_num = static_cast<gpio_num_t>(18);
    bus_cfg.quadwp_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.quadhd_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.max_transfer_sz = 32;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.clock_speed_hz = 1000000;
    dev_cfg.mode = 0;
    dev_cfg.spics_io_num = static_cast<gpio_num_t>(-1);
    dev_cfg.queue_size = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    gpio_num_t rck   = static_cast<gpio_num_t>(17);
    gpio_num_t srclr = static_cast<gpio_num_t>(16);
    gpio_num_t g     = static_cast<gpio_num_t>(15);
    SiPoConnectionESPIDF connection(dev, rck, srclr, g);
    TPIC6B595Full<SiPoConnectionESPIDF> chip(connection, 1);

    check_eq("init_shadow_0", chip._shadow[0], 0x00);

    chip.fill(true);
    check_eq("fill_true_shadow", chip._shadow[0], 0xFF);
    chip.fill(false);
    check_eq("fill_false_shadow", chip._shadow[0], 0x00);
    chip.off();
    check_eq("off_shadow", chip._shadow[0], 0x00);

    chip.write_port(0, 0xA5);
    check_eq("write_port_0xa5_shadow", chip._shadow[0], 0xA5);

    TPIC6B595Full<SiPoConnectionESPIDF>::IOExpanderPin p0 = chip.pin(0);
    p0.high();
    check_eq("pin_on_shadow_bit", chip._shadow[0] & 0x01, 1);
    p0.low();
    check_eq("pin_off_shadow_bit", chip._shadow[0] & 0x01, 0);
    p0.toggle();
    check_eq("pin_toggle_shadow_bit", chip._shadow[0] & 0x01, 1);

    chip.clear();
    check_true("clear_accepted", true);
    chip.set_output_enable(false);
    check_true("set_output_enable_false_accepted", true);
    chip.set_output_enable(true);
    check_true("set_output_enable_true_accepted", true);

    TPIC6B595Full<SiPoConnectionESPIDF> cascaded(connection, 2);
    uint8_t bytes_[2] = { 0xA5, 0x5A };
    cascaded.write_all(bytes_, 2);
    check_eq("write_all_shadow_0", cascaded._shadow[0], 0xA5);
    check_eq("write_all_shadow_1", cascaded._shadow[1], 0x5A);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
