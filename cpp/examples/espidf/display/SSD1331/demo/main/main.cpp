// SSD1331 demo example: Waveshare 0.95inch RGB OLED, ESP-IDF (ESP32-S3 / ESP32, SPI2)
// Wiring: DIN=GPIO23, CLK=GPIO18, CS=GPIO5, DC=GPIO17, RST=GPIO16 (no BUSY line).
// Demo: a colour test card — eight colour bars over a gray ramp, a frame, a marker drawn with the controller's own rectangle command, then a slow scroll.
#include <stdio.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include <esp_timer.h>
#include "DisplayConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "InputPinESPIDF.h"
#include "SSD1331.h"

#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#define PRINT printf
#define NOW_MS() ((uint32_t)(esp_timer_get_time() / 1000))

static uint8_t frameBuffer[96 * 64 * 2];

static void run(SSD1331Full<DisplayConnectionESPIDF>& oled) {
    typedef SSD1331Full<DisplayConnectionESPIDF> Chip;
    // --- Draw the test card into the framebuffer ---
    // Eight 12-pixel colour bars over a 32-row gray ramp show that all channels and the
    // 5-6-5 depth work; the one-pixel frame shows that the whole 96 x 64 area is addressed.
    static const uint16_t bars[8] = {
        Chip::WHITE, Chip::YELLOW, Chip::CYAN, Chip::GREEN, Chip::MAGENTA, Chip::RED, Chip::BLUE, Chip::BLACK
    };
    oled.clear(Chip::BLACK);                        // Fill framebuffer, (color=BLACK)
    for (int16_t x = 0; x < 96; x++) {
        oled.draw_rect(x, 2, 1, 30, bars[x / 12], true);  // Software rectangle, (x, y, w, h, color, fill=false)
        uint8_t g = static_cast<uint8_t>(x * 255 / 95);
        oled.draw_rect(x, 34, 1, 26, Chip::rgb(g, g, g), true);  // Software rectangle, (x, y, w, h, color, fill=false)
    }
    oled.draw_rect(0, 0, 96, 64, Chip::WHITE);      // Software rectangle, (x, y, w, h, color, fill=false)

    // --- Send it once ---
    oled.show();                                    // Send framebuffer, () → None
    DELAY_MS(3000);

    // --- Animate with hardware drawing ---
    // The chip draws the marker straight into its RAM: no framebuffer transfer per step.
    for (int step = 0; step < 60; step++) {
        int x = 4 + (step * 3) % 80;
        oled.show();                                // Send framebuffer, () → None
        oled.hw_rect(static_cast<uint8_t>(x), 36, 10, 10, Chip::WHITE, Chip::RED, true);  // Hardware rectangle, (x, y, w, h, line_color, fill_color=0, fill=false)
        DELAY_MS(50);
    }

    // --- Scroll the test card ---
    // The chip scrolls its own RAM, one column every 6 frames.
    oled.show();                                    // Send framebuffer, () → None
    oled.scroll(1, 0, 64, 0, 0);                    // Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
    PRINT("done\n");
}

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

    DisplayConnectionESPIDF connection(dev, nullptr, &dc, &res, nullptr);   // Create display connection, (dev, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    SSD1331Full<DisplayConnectionESPIDF> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)
    if (!oled.valid()) {
        PRINT("SSD1331 init failed (check wiring)\n");
        return;
    }
    run(oled);
}
