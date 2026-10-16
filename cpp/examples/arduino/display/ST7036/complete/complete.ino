// ST7036 complete example: EA DOGM162W-A (2 x 16), Arduino (3.3 V board and module)
// Wiring: SI=MOSI, SCL=SCK, CSB=10, RS=9, RESET=8; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Complete: every Full method — text, cursor and blink, entry mode, shift, contrast, booster, follower, bias, custom characters, display on/off.
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
