// ST7036 demo example: EA DOGM162W-A (2 x 16), Arduino (3.3 V board and module)
// Wiring: SI=MOSI, SCL=SCK, CSB=10, RS=9, RESET=8; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Demo: a two-line status display — a title line and a bar graph built from five custom characters that fills and empties, then a contrast sweep.
#include <stdarg.h>
#include <stdio.h>
#include <SPI.h>
#include <Periph.h>


static void PRINT(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}
#define DELAY_MS(ms) delay(ms)
#define NOW_MS() ((uint32_t)millis())

OutputPinArduino cs(10);                            // Create CS# pin, (pin)
OutputPinArduino dc(9);                             // Create D/C# pin, (pin)
OutputPinArduino res(8);                            // Create RES# pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, nullptr, SPISettings(1000000, MSBFIRST, SPI_MODE0));  // Create display connection, (spi, cs, dc, res, busy, settings=1 MHz, wireMode=kSimplex)
ST7036Full<DisplayConnection> lcd(connection);  // Create ST7036 driver, (connection, lines=2, supply=SUPPLY_3V3, contrast=-1 preset)

static void run(ST7036Full<DisplayConnection>& lcd) {
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

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!lcd.valid()) {
        PRINT("ST7036 init failed (check wiring)\n");
        return;
    }
    run(lcd);
}

void loop() {}
