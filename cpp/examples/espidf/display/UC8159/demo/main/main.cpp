// UC8159 demo example: Waveshare 5.65inch e-Paper (F), ESP-IDF (ESP32-S3 / ESP32, SPI2).
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
    UC8159Full<DisplayConnectionESPIDF> epd(connection, frameBuffer, sizeof(frameBuffer));   // Create UC8159 driver, (connection, buffer, buffer_len, busy_timeout_ms=60000)
    if (!epd.valid()) {
        PRINT("UC8159 init failed: BUSY never went high\n");
        return;
    }
    typedef UC8159Full<DisplayConnectionESPIDF> Epd;

    // --- Compose the colour chart in the framebuffer ---
    // White page, seven vertical bars (black, white, green, blue, red, yellow, orange) and a
    // one-pixel black frame inset by 4 px (a frame on the very edge can hide behind the bezel).
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    for (uint8_t c = 0; c < 7; c++) {
        epd.draw_rect(20 + c * 80, 40, 80, 368, c, true);                              // Draw rectangle, (x, y, w, h, color, fill)
    }
    epd.draw_rect(4, 4, 592, 440, Epd::BLACK);                                         // Draw rectangle, (x, y, w, h, color, fill=false)

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/PON/DRF/POF sequence: about 30 s.
    uint32_t t0 = NOW_MS();
    bool ok = epd.show();                                                              // Update display, () → bool
    uint32_t elapsed = NOW_MS() - t0;
    PRINT("%s after %lu ms\n", ok ? "refresh took" : "BUSY timeout", (unsigned long)elapsed);

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep();                                                                       // Enter deep sleep, () → bool

}
