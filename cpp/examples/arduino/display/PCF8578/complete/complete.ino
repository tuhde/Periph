#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x3C
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 100000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(100000);
#endif
    I2CConnection connection(Wire, TEST_ADDR);
    PCF8578Full lcd(connection, 16);                          // Create PCF8578 driver, (connection, rows=16 8/16/24/32)
                                                              // clears RAM and switches the display on in mixed mode

    lcd.clear();                                              // Clear display RAM, () → void
                                                              // zeroes all 4 banks x 40 columns and the shadow
    lcd.set_pixel(1, 1);                                      // Set one pixel, (x 0–23, y 0–15, on=true) → void
                                                              // writes a single RAM byte from the shadow
    static const uint8_t frame[] = {0xFF, 0x81, 0xFF};
    lcd.write_columns(4, 0, frame, sizeof(frame));            // Write column bytes, (x 0–23, bank 0–1, data, len) → bool
                                                              // character access mode, bit 0 = top row of the bank
    bool on = lcd.get_pixel(1, 1);                            // Read pixel from shadow, (x, y) → bool
                                                              // no bus traffic
    lcd.fill(true);                                           // Fill display, (on=true) → void
                                                              // every displayed pixel on
    lcd.fill(false);                                          // Fill display, (on=true) → void
                                                              // every displayed pixel off
    lcd.draw_line(0, 0, 23, 15);                              // Draw line, (x0, y0, x1, y1, on=true) → void
                                                              // Bresenham, written as one batch of changed bytes
    lcd.draw_rect(0, 0, 24, 16);                              // Draw rectangle outline, (x, y, w, h, on=true, fill=false) → void
                                                              // border around the whole panel
    lcd.draw_rect(8, 4, 8, 8, true, true);                    // Draw filled rectangle, (x, y, w, h, on=true, fill=false) → void
                                                              // solid 8x8 block in the middle
    static const uint8_t bitmap[] = {0x18, 0x24, 0x42, 0x81};
    lcd.show_bitmap(2, 8, bitmap, sizeof(bitmap), 1);         // Blit bitmap, (x, y multiple of 8, data, width, bands) → bool
                                                              // one 8-pixel-high band at the lower bank
    lcd.set_display(PCF8578Full::DISPLAY_INVERSE);            // Set display status, (status 0 blank/1 normal/2 all on/3 inverse) → bool
                                                              // inverse video; RAM is preserved
    lcd.set_display(PCF8578Full::DISPLAY_NORMAL);             // Set display status, (status 0–3) → bool
                                                              // back to normal video
    lcd.set_mode(24, true);                                   // Set multiplex rate, (rows 8/16/24/32, mixed=true) → bool
                                                              // 1:24 drive; mixed mode keeps RAM accessible
    lcd.set_mode(16, true);                                   // Set multiplex rate, (rows 8/16/24/32, mixed=true) → bool
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
    lcd.device_select(0);                                     // Select device subaddress, (subaddress 0–15) → bool
                                                              // 0 addresses this PCF8578; others target cascaded PCF8579s
    lcd.reset();                                              // Re-initialise, () → void
                                                              // clears RAM and restores normal video
    (void)on;
    (void)data;

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
