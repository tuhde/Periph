// UC8159 minimal example: Waveshare 5.65inch e-Paper (F), ESP-IDF (ESP32-S3 / ESP32, SPI2).
// Wiring: DIN=GPIO23, CLK=GPIO18, CS=GPIO5, DC=GPIO17, RST=GPIO16, BUSY=GPIO4
// Each refresh blocks for about 30 s.
#include <stdio.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include <esp_timer.h>
#include "DisplayConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "InputPinESPIDF.h"
#include "UC8159.h"

#define PRINT printf
#define NOW_MS() ((uint32_t)(esp_timer_get_time() / 1000))

static uint8_t frameBuffer[100801];

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

    OutputPinESPIDF dc(static_cast<gpio_num_t>(17));    // Create D/C# pin, (gpio)
    OutputPinESPIDF res(static_cast<gpio_num_t>(16));   // Create RES# pin, (gpio)
    InputPinESPIDF  busy(static_cast<gpio_num_t>(4));   // Create BUSY pin, (gpio)

    DisplayConnectionESPIDF connection(dev, nullptr, &dc, &res, &busy);   // Create display connection, (dev, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    UC8159Minimal<DisplayConnectionESPIDF> epd(connection, frameBuffer, sizeof(frameBuffer));   // Create UC8159 driver, (connection, buffer, buffer_len, busy_timeout_ms=60000)
    if (!epd.valid()) {
        PRINT("UC8159 init failed: BUSY never went high\n");
        return;
    }
    typedef UC8159Minimal<DisplayConnectionESPIDF> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    for (int16_t x = 0; x < 600; x++) {
        uint8_t colour = static_cast<uint8_t>((x * 7) / 600);                         // seven vertical bars: black .. orange
        for (int16_t y = 0; y < 448; y++) {
            epd.set_pixel(x, y, colour);                                               // Set one pixel, (x, y, color=BLACK)
        }
    }
    PRINT("refreshing, about 30 s ...\n");
    bool ok = epd.show();                                                              // Update display (about 30 s), () → bool
    PRINT(ok ? "done\n" : "BUSY timeout during refresh\n");
    epd.sleep();                                                                       // Enter deep sleep, () → bool

}
