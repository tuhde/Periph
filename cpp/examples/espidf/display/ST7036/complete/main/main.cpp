// ST7036 complete example: EA DOGM162W-A (2 x 16), ESP-IDF (ESP32-S3 / ESP32, SPI2), 3.3 V module
// Wiring: SI=GPIO23, SCL=GPIO18, CSB=GPIO5, RS=GPIO17, RESET=GPIO16; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Complete: every Full method — text, cursor and blink, entry mode, shift, contrast, booster, follower, bias, custom characters, display on/off.
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
    lcd.clear();                                    // Clear display, ()
                                                    // writes spaces to all of DDRAM, cursor to home (2 ms)
    lcd.print_at(0, 0, "Complete example");         // Write text, (col, row, text)
    lcd.set_cursor(0, 1);                           // Move cursor, (col, row)
    lcd.write("Cursor + blink");                    // Write text at the cursor, (text)
    lcd.cursor(true, true);                         // Cursor, (on, blink=false)
    DELAY_MS(2000);
    lcd.cursor(false, false);                       // Cursor, (on, blink=false)

    static const uint8_t heart[8] = {0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00};
    static const uint8_t arrow[8] = {0x04, 0x02, 0x1F, 0x02, 0x04, 0x00, 0x00, 0x00};
    lcd.create_char(0, heart);                      // Define user character, (index 0..7, rows[8])
                                                    // 8 rows of 5 bits into CGRAM, DDRAM address restored
    lcd.create_char(1, arrow);                      // Define user character, (index 0..7, rows[8])
    lcd.clear();                                    // Clear display, ()
    lcd.set_cursor(0, 0);                           // Move cursor, (col, row)
    lcd.write("I ");                                // Write text at the cursor, (text)
    lcd.write_char(0);                              // Write raw character code, (code)
    lcd.write(" custom ");                          // Write text at the cursor, (text)
    lcd.write_char(1);                              // Write raw character code, (code)
    DELAY_MS(2000);

    lcd.set_contrast(0x08);                         // Set contrast, (value 0..63)
    lcd.print_at(0, 1, "low contrast");             // Write text, (col, row, text)
    DELAY_MS(1500);
    lcd.set_contrast(0x30);                         // Set contrast, (value 0..63)
    lcd.print_at(0, 1, "high contrast");            // Write text, (col, row, text)
    DELAY_MS(1500);
    lcd.set_contrast(0x18);                         // Set contrast, (value 0..63)
                                                    // 0x18 is the DOGM162 3.3 V preset
    lcd.set_bias(false);                            // LCD bias, (quarter=false) 1/5 bias
    lcd.set_follower(true, 5);                      // Voltage follower, (on, ratio 0..7) 1 + Rb/Ra = 2.5
    lcd.set_booster(true);                          // Voltage booster, (on)

    lcd.clear();                                    // Clear display, ()
    lcd.set_entry_mode(true, false);                // Entry mode, (increment, shift=false)
    lcd.print_at(0, 0, "Shift display ->");         // Write text, (col, row, text)
    lcd.print_at(0, 1, "0123456789ABCDEF");         // Write text, (col, row, text)
    DELAY_MS(1000);
    for (int i = 0; i < 6; i++) {
        lcd.shift_display(true);                    // Shift display, (right)
        DELAY_MS(300);
    }
    lcd.return_home();                              // Return home, () — undoes the display shift
    lcd.shift_cursor(true);                         // Shift cursor, (right)
    lcd.set_ddram_address(0x40);                    // Set DDRAM address, (address)
    DELAY_MS(1000);

    lcd.display(false);                             // Display on/off, (on)
    DELAY_MS(800);
    lcd.display(true);                              // Display on/off, (on)
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
