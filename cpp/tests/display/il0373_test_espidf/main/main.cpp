#include <stdio.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include "DisplayConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "InputPinESPIDF.h"
#include "IL0373.h"
#include "il0373_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printf("PASS %s\n", label); passed++; }
    else    { printf("FAIL %s\n", label); failed++; }
}

static uint8_t frameBuffer[2756];

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num = static_cast<gpio_num_t>(23);
    bus_cfg.miso_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.sclk_io_num = static_cast<gpio_num_t>(18);
    bus_cfg.quadwp_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.quadhd_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.max_transfer_sz = 4092;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    // CS# is driven by the SPI driver (spics_io_num), so the connection gets cs = nullptr.
    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.clock_speed_hz = 4000000;
    dev_cfg.mode = 0;
    dev_cfg.spics_io_num = static_cast<gpio_num_t>(5);
    dev_cfg.queue_size = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    OutputPinESPIDF dc(static_cast<gpio_num_t>(17));
    OutputPinESPIDF res(static_cast<gpio_num_t>(16));
    InputPinESPIDF  busy(static_cast<gpio_num_t>(4));

    DisplayConnectionESPIDF connection(dev, nullptr, &dc, &res, &busy);
    IL0373Full<DisplayConnectionESPIDF> chip(connection, frameBuffer);
    il0373Checks(chip, report);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
