// ST7036 demo example: EA DOGM162W-A (2 x 16), Raspberry Pi Pico SDK (Pico / Pico W), 3.3 V module
// Wiring: SI=GP19 (SPI0 TX), SCL=GP18, CSB=GP17, RS=GP20, RESET=GP21; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Demo: a two-line status display — a title line and a bar graph built from five custom characters that fills and empties, then a contrast sweep.
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
