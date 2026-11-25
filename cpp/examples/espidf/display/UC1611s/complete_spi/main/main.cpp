// UC1611s complete example (4-wire SPI): EA DOGXL240-7, ESP-IDF (ESP32-S3 / ESP32, SPI2)
// Wiring: SI=GPIO23, SCK=GPIO18, CS0=GPIO5, CD=GPIO17, RST=GPIO16; VDD=3V3 (no BUSY line).
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), 4-wire SPI: BM0 = D13 = VSS. Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD.
// Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, partial display, RAM screens, sleep, raw access, reset (SPI has no read-back).
#include <stdio.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include <esp_timer.h>
#include "DisplayConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "InputPinESPIDF.h"
#include "UC1611s.h"

#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#define PRINT printf
#define NOW_MS() ((uint32_t)(esp_timer_get_time() / 1000))

static void run(UC1611sSpiFull<DisplayConnectionESPIDF>& lcd) {
    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear();                                    // Clear framebuffer, ()
                                                    // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true);                                 // Fill framebuffer, (on=true)
                                                    // sets (or clears) every pixel
    lcd.fill(false);                                // Fill framebuffer, (on=true)
    lcd.draw_rect(0, 0, 240, 128);                  // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // one-pixel outline around the whole panel
    lcd.draw_line(0, 0, 239, 127);                  // Draw line, (x0, y0, x1, y1, on=true)
                                                    // Bresenham line into the framebuffer
    lcd.draw_line(0, 127, 239, 0);                  // Draw line, (x0, y0, x1, y1, on=true)
    lcd.draw_rect(20, 20, 40, 30, true, true);      // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // filled rectangle
    lcd.draw_rect(30, 28, 20, 14, false, true);     // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // on=false clears pixels: a hole in the filled rectangle
    lcd.set_pixel(120, 64, true);                   // Set pixel, (x, y, on=true)
    bool lit = lcd.get_pixel(120, 64);              // Read framebuffer pixel, (x, y) → bool
                                                    // reads the framebuffer, not the panel
    static const uint8_t icon[16] = {0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00};
    lcd.blit(180, 16, icon, sizeof(icon), 2);       // Copy bitmap, (x, y multiple of 8, columns, len, height_pages=1)
                                                    // two page rows of 8 columns, bit 0 = top pixel
    lcd.show();                                     // Send framebuffer to the display, ()
                                                    // per page: page + column address, then 240 data bytes in one transfer
    DELAY_MS(2000);
    lcd.show_page(2);                               // Send one framebuffer page, (page 0..15)
                                                    // cheaper partial update of 8 rows
    
    // Contrast and display modes.
    lcd.set_contrast(0x70);                         // Set contrast, (value 0..255)
                                                    // writes the potentiometer PM; lower = lighter
    DELAY_MS(800);
    lcd.set_contrast(0x8F);                         // Set contrast, (value 0..255)
                                                    // 0x8F is the DOG example value
    lcd.set_invert(true);                           // Invert display, (on)
                                                    // bit-wise inversion of what is displayed; RAM unchanged
    DELAY_MS(800);
    lcd.set_invert(false);                          // Invert display, (on)
    lcd.set_all_on(true);                           // Light all pixels, (on)
                                                    // test mode, RAM unchanged
    DELAY_MS(800);
    lcd.set_all_on(false);                          // Light all pixels, (on)
    lcd.set_flip(false, true);                      // Mirror display, (mirror_x, mirror_y)
                                                    // MY = 1: rows mirrored at once; MX is applied to RAM writes
    DELAY_MS(800);
    lcd.set_flip(true, false);                      // Mirror display, (mirror_x, mirror_y)
                                                    // back to the bottom-view default
    lcd.show();                                     // Send framebuffer to the display, ()
    
    // Hardware scroll with a fixed header.
    lcd.set_fixed_lines(2);                         // Set fixed lines, (lines 0..15)
                                                    // the top 2 x 2 = 4 rows stay put
    for (uint8_t line = 0; line <= 32; line += 4) {
        lcd.set_scroll_line(line);                  // Scroll the image up, (line 0..159 - 2 * fixed)
                                                    // rows enter from the bottom as the image moves up
        DELAY_MS(120);
    }
    lcd.set_scroll_line(0);                         // Scroll the image up, (line 0..159 - 2 * fixed)
    lcd.set_fixed_lines(0);                         // Set fixed lines, (lines 0..15)
    
    // LCD drive tuning (the DOG defaults are already sent at init).
    lcd.set_bias_ratio(2);                          // Set bias ratio, (ratio 0..3)
                                                    // 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12
    lcd.set_temperature_compensation(1);            // Set temperature compensation, (curve 0..3)
                                                    // 1 = -0.10 %/degC, the DOG example value
    lcd.set_line_rate(3);                           // Set line rate, (rate 0..3)
                                                    // 3 = 9.4 kilo-lines per second in On/Off mode
    lcd.set_pump(true);                             // Select VLCD source, (internal=true)
                                                    // internal 11x charge pump
    lcd.set_panel_loading(true);                    // Set panel loading, (high=true)
                                                    // 33..55 nF panel capacitance
    lcd.set_nline_inversion(0, false);              // Set N-line inversion, (lines 0..63, xor=false)
                                                    // 0 = off
    lcd.set_isolation_clock(1, 0);                  // Set isolation clock, (front 0..15, back 0..15)
                                                    // the reset values
    
    // Partial display: scan only the middle 64 rows.
    lcd.set_partial_display(true, 32, 95);          // Set partial display, (on, start=0, end=height-1)
                                                    // rows outside 32..95 are not scanned and save power
    DELAY_MS(1500);
    lcd.set_partial_display(false);                 // Set partial display, (on, start=0, end=height-1)
                                                    // back to the full panel
    
    // Four RAM screens: draw into screen 1, then switch to it.
    lcd.set_write_pattern(1);                       // Select RAM screen to write, (pattern 0..3)
                                                    // show() now writes RAM screen 1
    lcd.fill(false);                                // Fill framebuffer, (on=true)
    lcd.draw_rect(40, 30, 160, 68, true, true);     // Draw rectangle, (x, y, w, h, on=true, fill=false)
    lcd.show();                                     // Send framebuffer to the display, ()
                                                    // screen 0 is still displayed
    lcd.set_display_pattern(1);                     // Select displayed RAM screen, (pattern 0..3)
                                                    // the display now shows RAM screen 1
    DELAY_MS(1500);
    lcd.set_display_pattern(0);                     // Select displayed RAM screen, (pattern 0..3)
    lcd.set_write_pattern(0);                       // Select RAM screen to write, (pattern 0..3)
    
    // Sleep, raw access, reset.
    lcd.sleep();                                    // Enter sleep, ()
                                                    // drivers, pump and clock stop; registers and RAM are kept
    DELAY_MS(1000);
    lcd.wake();                                     // Leave sleep, ()
                                                    // the chip restores VLCD itself
    static const uint8_t nop[1] = {0xE3};
    lcd.send_command(nop, sizeof(nop));             // Send raw command bytes, (bytes, len)
                                                    // 0xE3 = NOP
    static const uint8_t zeros[8] = {0};
    lcd.set_display_pattern(0);                     // Select displayed RAM screen, (pattern 0..3)
    lcd.send_data(zeros, sizeof(zeros));            // Send raw RAM data, (bytes, len)
                                                    // written at the current RAM address
    lcd.reset();                                    // System Reset and reconfigure, ()
                                                    // registers back to the DOG settings with the cached contrast and mapping; RAM is kept
    (void)lit;
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
    UC1611sSpiFull<DisplayConnectionESPIDF> lcd(connection);  // Create UC1611s driver, (connection, width=240, height=128, topView=false, contrast=0x8F)
    if (!lcd.valid()) {
        PRINT("UC1611s init failed (check wiring)\n");
        return;
    }
    run(lcd);
}
