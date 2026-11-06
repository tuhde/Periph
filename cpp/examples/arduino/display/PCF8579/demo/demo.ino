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
    // --- Initialise a 16 x 40 column driver ---
    // 1:16 multiplex gives 16 rows and 40 columns; the constructor clears the RAM
    // before switching the display on so no random start-up pattern is shown.
    PCF8579Full lcd(connection, 0, 16);                       // Create PCF8579 driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false)

    // --- Draw a border and both diagonals ---
    // Lines and rectangles are rendered in the shadow buffer and sent as one
    // batch of changed bytes per run of columns.
    lcd.draw_rect(0, 0, 40, 16);                              // Draw rectangle outline, (x, y, w, h, on=true, fill=false) → void
    lcd.draw_line(0, 0, 39, 15);                              // Draw line, (x0, y0, x1, y1, on=true) → void
    lcd.draw_line(0, 15, 39, 0);                              // Draw line, (x0, y0, x1, y1, on=true) → void

    // --- Flash the display twice with inverse video ---
    // Inverse video only changes how the RAM is shown, so the picture is kept.
    for (int i = 0; i < 2; i++) {
        lcd.set_display(PCF8579Full::DISPLAY_INVERSE);        // Set display status, (status 0–3) → bool
        delay(400);
        lcd.set_display(PCF8579Full::DISPLAY_NORMAL);         // Set display status, (status 0–3) → bool
        delay(400);
    }

    // --- Fill all four RAM banks with distinct patterns ---
    // At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
    // buffers that the start bank can scroll into view.
    static const uint8_t patterns[4] = {0x55, 0x33, 0x0F, 0xFF};
    for (int bank = 0; bank < 4; bank++) {
        uint8_t col[40];
        for (int i = 0; i < 40; i++) col[i] = patterns[bank];
        lcd.write_ram(0, bank, col, sizeof(col));             // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
    }

    // --- Scroll through the banks with the hardware start bank ---
    // Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
    for (int bank = 0; bank < 4; bank++) {
        lcd.set_start_bank(bank);                             // Set start bank, (bank 0–3) → bool
        uint8_t first = 0;
        lcd.read_ram(0, bank, &first, 1);                     // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
        Serial.print("first column byte 0x"); Serial.println((unsigned)(first), HEX);
        delay(1000);
    }

    // --- Read back the first 8 RAM columns of bank 0 ---
    // Writing a counting pattern and reading it back verifies the bus path.
    lcd.set_start_bank(0);                                    // Set start bank, (bank 0–3) → bool
    static const uint8_t scratch[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    lcd.write_ram(0, 0, scratch, sizeof(scratch));            // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
    uint8_t back[8];
    lcd.read_ram(0, 0, back, sizeof(back));                   // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
    for (int i = 0; i < 8; i++) {
        Serial.print("scratch byte 0x"); Serial.println((unsigned)(back[i]), HEX);
    }

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
