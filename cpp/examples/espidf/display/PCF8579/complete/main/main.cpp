// Auto-generated ESP-IDF example for PCF8579 (Minimal).
// Mirrors the Arduino PCF8579_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCF8579.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x3C;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    PCF8579Full lcd(connection, 0, 16);                       // Create PCF8579 driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false)
                                                              // clears RAM and switches the display on in mixed mode

    lcd.clear();                                              // Clear display RAM, () → void
                                                              // zeroes all 4 banks x 40 columns and the shadow
    lcd.set_pixel(1, 1);                                      // Set one pixel, (x 0–39, y 0–15, on=true) → void
                                                              // writes a single RAM byte from the shadow
    static const uint8_t frame[] = {0xFF, 0x81, 0xFF};
    lcd.write_columns(4, 0, frame, sizeof(frame));            // Write column bytes, (x 0–39, bank 0–1, data, len) → bool
                                                              // character access mode, bit 0 = top row of the bank
    bool on = lcd.get_pixel(1, 1);                            // Read pixel from shadow, (x, y) → bool
                                                              // no bus traffic
    lcd.fill(true);                                           // Fill display, (on=true) → void
                                                              // every displayed pixel on
    lcd.fill(false);                                          // Fill display, (on=true) → void
                                                              // every displayed pixel off
    lcd.draw_line(0, 0, 39, 15);                              // Draw line, (x0, y0, x1, y1, on=true) → void
                                                              // Bresenham, written as one batch of changed bytes
    lcd.draw_rect(0, 0, 40, 16);                              // Draw rectangle outline, (x, y, w, h, on=true, fill=false) → void
                                                              // border around the whole panel
    lcd.draw_rect(8, 4, 8, 8, true, true);                    // Draw filled rectangle, (x, y, w, h, on=true, fill=false) → void
                                                              // solid 8x8 block in the middle
    static const uint8_t bitmap[] = {0x18, 0x24, 0x42, 0x81};
    lcd.show_bitmap(2, 8, bitmap, sizeof(bitmap));            // Blit bitmap, (x, y multiple of 8, columns, width) → bool
                                                              // one 8-pixel-high band at the lower bank
    lcd.set_display(PCF8579Full::DISPLAY_INVERSE);            // Set display status, (status 0 blank/1 normal/2 all on/3 inverse) → bool
                                                              // inverse video; RAM is preserved
    lcd.set_display(PCF8579Full::DISPLAY_NORMAL);             // Set display status, (status 0–3) → bool
                                                              // back to normal video
    lcd.set_mode(24, false);                                  // Set multiplex rate, (rows 8/16/24/32, mixed=false) → bool
                                                              // 1:24 drive; mixed is the PCF8578 mode bit T
    lcd.set_mode(16, false);                                  // Set multiplex rate, (rows 8/16/24/32, mixed=false) → bool
                                                              // back to 1:16
    lcd.set_start_bank(1);                                    // Set start bank, (bank 0–3) → bool
                                                              // shows RAM bank 1 at the top (hardware scroll)
    lcd.scroll(-1);                                           // Scroll by banks, (banks int) → void
                                                              // adds -1 to the start bank modulo banks in use
    static const uint8_t ram[] = {0xAA, 0x55};
    lcd.write_ram(16, 0, ram, sizeof(ram));                   // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
                                                              // character order at RAM column 16
    uint8_t data[2];
    lcd.read_ram(16, 0, data, sizeof(data));                  // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
                                                              // address-set commands, repeated start, then a read
    static const uint8_t spill[160] = {0};
    lcd.write_ram_cascade(spill, sizeof(spill));              // Write across devices, (data, len, mode=2) → bool
                                                              // runs on into the following subaddresses after column 39
    lcd.reset();                                              // Re-initialise, () → void
                                                              // clears RAM and restores normal video
    (void)on;
    (void)data;

    printf("===DONE: 0 passed, 0 failed===\n");
    while (1) vTaskDelay(pdMS_TO_TICKS(1000));
}
