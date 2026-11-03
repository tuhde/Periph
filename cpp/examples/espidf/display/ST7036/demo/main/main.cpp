// ST7036 demo example: EA DOGM162W-A (2 x 16), ESP-IDF (ESP32-S3 / ESP32, SPI2), 3.3 V module
// Wiring: SI=GPIO23, SCL=GPIO18, CSB=GPIO5, RS=GPIO17, RESET=GPIO16; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Demo: a two-line status display — a title line and a bar graph built from five custom characters that fills and empties, then a contrast sweep.
#include <stdio.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include <esp_timer.h>
#include "DisplayConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "InputPinESPIDF.h"
#include "ST7036.h"

#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#define PRINT printf
#define NOW_MS() ((uint32_t)(esp_timer_get_time() / 1000))

static void run(ST7036Full<DisplayConnectionESPIDF>& lcd) {
    // --- Define the bar-graph glyphs ---
    // Custom characters 0..4 are columns of 1..5 filled pixel columns, so a 16-character bar
    // can show 80 steps with the eight CGRAM slots.
    for (uint8_t g = 0; g < 5; g++) {
        uint8_t rows[8];
        uint8_t pattern = static_cast<uint8_t>(0x1F << (4 - g)) & 0x1F;
        for (uint8_t r = 0; r < 8; r++) rows[r] = (r == 0 || r == 7) ? 0 : pattern;
        lcd.create_char(g, rows);                   // Define user character, (index 0..7, rows[8])
    }

    // --- Static title line ---
    lcd.clear();                                    // Clear display, ()
    lcd.print_at(0, 0, "Periph ST7036");            // Write text, (col, row, text)

    // --- Animate a progress bar on line 2 ---
    // Each step fills 1/5 of a character cell; a full bar is 16 x 5 = 80 steps.
    for (int step = 0; step <= 80; step++) {
        lcd.set_cursor(0, 1);                       // Move cursor, (col, row)
        for (int col = 0; col < 16; col++) {
            int fill = step - col * 5;              // pixel columns of this cell that are filled
            if (fill >= 5)      lcd.write_char(4);  // Write raw character code, (code)
            else if (fill > 0)  lcd.write_char(static_cast<uint8_t>(fill - 1));  // Write raw character code, (code)
            else                lcd.write_char(' ');  // Write raw character code, (code)
        }
        DELAY_MS(40);
    }
    DELAY_MS(1000);

    // --- Contrast sweep ---
    // The contrast is set once in normal use; sweeping it shows the usable range of the module.
    lcd.print_at(0, 0, "Contrast sweep ");          // Write text, (col, row, text)
    for (int c = 0x10; c <= 0x30; c++) {
        lcd.set_contrast(static_cast<uint8_t>(c));  // Set contrast, (value 0..63)
        DELAY_MS(80);
    }
    lcd.set_contrast(0x18);                         // Set contrast, (value 0..63)
    lcd.print_at(0, 0, "Periph ST7036   ");         // Write text, (col, row, text)
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
    dev_cfg.clock_speed_hz = 1000000;
    dev_cfg.mode = 0;
    dev_cfg.spics_io_num = static_cast<gpio_num_t>(5);
    dev_cfg.queue_size = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    OutputPinESPIDF dc(static_cast<gpio_num_t>(17));    // Create D/C# pin, (gpio)
    OutputPinESPIDF res(static_cast<gpio_num_t>(16));   // Create RES# pin, (gpio)

    DisplayConnectionESPIDF connection(dev, nullptr, &dc, &res, nullptr);   // Create display connection, (dev, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    ST7036Full<DisplayConnectionESPIDF> lcd(connection);  // Create ST7036 driver, (connection, lines=2, supply=SUPPLY_3V3, contrast=-1 preset)
    if (!lcd.valid()) {
        PRINT("ST7036 init failed (check wiring)\n");
        return;
    }
    run(lcd);
}
