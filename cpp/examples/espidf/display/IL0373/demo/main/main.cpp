// IL0373 demo example: Waveshare 2.13inch e-Paper (D), ESP-IDF (ESP32-S3 / ESP32, SPI2).
// Wiring: DIN=GPIO23, CLK=GPIO18, CS=GPIO5, DC=GPIO17, RST=GPIO16, BUSY=GPIO4
// Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
#include <stdio.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include <esp_timer.h>
#include "DisplayConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "InputPinESPIDF.h"
#include "IL0373.h"

#define PRINT printf
#define NOW_MS() ((uint32_t)(esp_timer_get_time() / 1000))

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

    OutputPinESPIDF dc(static_cast<gpio_num_t>(17));    // Create D/C# pin, (gpio)
    OutputPinESPIDF res(static_cast<gpio_num_t>(16));   // Create RES# pin, (gpio)
    InputPinESPIDF  busy(static_cast<gpio_num_t>(4));   // Create BUSY pin, (gpio)

    DisplayConnectionESPIDF connection(dev, nullptr, &dc, &res, &busy);   // Create display connection, (dev, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    IL0373Full<DisplayConnectionESPIDF> epd(connection, frameBuffer);   // Create IL0373 driver, (connection, buffer, width=104, height=212, busy_timeout_ms=30000)
    if (!epd.valid()) {
        PRINT("IL0373 init failed: BUSY never went high\n");
        return;
    }
    typedef IL0373Full<DisplayConnectionESPIDF> Epd;

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 4 px (a frame on the very edge is
    // hidden by the panel bezel) and a black header bar across the top 24 rows.
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.draw_rect(2, 2, 100, 208, Epd::BLACK);                                         // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    epd.draw_rect(3, 3, 98, 24, Epd::BLACK, true);                                      // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is filled and the others are outlines.
    static const int16_t values[8] = {30, 55, 80, 45, 120, 90, 60, 100};
    int16_t peak = 0;
    for (int i = 0; i < 8; i++) {
        if (values[i] > peak) peak = values[i];
    }
    epd.draw_line(10, 190, 95, 190, Epd::BLACK);                                      // Draw line, (x0, y0, x1, y1, color=BLACK)
    epd.draw_line(10, 60, 10, 190, Epd::BLACK);                                        // Draw line, (x0, y0, x1, y1, color=BLACK)
    for (int i = 0; i < 8; i++) {
        epd.draw_rect(14 + i * 10, 190 - values[i], 8, values[i], Epd::BLACK, values[i] == peak); // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    }

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/DTM2/DRF/POF sequence.
    uint32_t t0 = NOW_MS();
    bool ok = epd.show();                                                              // Update display, () → bool
    uint32_t elapsed = NOW_MS() - t0;
    PRINT("%s after %lu ms\n", ok ? "refresh took" : "BUSY timeout", (unsigned long)elapsed);

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep();                                                                       // Enter deep sleep, () → bool
}
