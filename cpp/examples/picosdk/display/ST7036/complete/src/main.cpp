// ST7036 complete example: EA DOGM162W-A (2 x 16), Raspberry Pi Pico SDK (Pico / Pico W), 3.3 V module
// Wiring: SI=GP19 (SPI0 TX), SCL=GP18, CSB=GP17, RS=GP20, RESET=GP21; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Complete: every Full method — text, cursor and blink, entry mode, shift, contrast, booster, follower, bias, custom characters, display on/off.
#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "DisplayConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "ST7036.h"

#define DELAY_MS(ms) sleep_ms(ms)
#define PRINT printf
#define NOW_MS() to_ms_since_boot(get_absolute_time())

static void run(ST7036Full<DisplayConnectionPicoSDK>& lcd) {
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

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 1000 * 1000);
    gpio_set_function(18, GPIO_FUNC_SPI);   // SCK
    gpio_set_function(19, GPIO_FUNC_SPI);   // MOSI

    OutputPinPicoSDK cs(17);                  // Create CS# pin, (gpio)
    OutputPinPicoSDK dc(20);                  // Create D/C# pin, (gpio)
    OutputPinPicoSDK res(21);                 // Create RES# pin, (gpio)

    DisplayConnectionPicoSDK connection(spi0, &cs, &dc, &res, nullptr);   // Create display connection, (spi, cs, dc, res, busy, wireMode=kSimplex)
    ST7036Full<DisplayConnectionPicoSDK> lcd(connection);  // Create ST7036 driver, (connection, lines=2, supply=SUPPLY_3V3, contrast=-1 preset)
    if (!lcd.valid()) {
        PRINT("ST7036 init failed (check wiring)\n");
        return 1;
    }
    run(lcd);
    return 0;
}
