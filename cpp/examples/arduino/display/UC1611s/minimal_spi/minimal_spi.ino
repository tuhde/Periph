// UC1611s minimal example (4-wire SPI): EA DOGXL240-7, Arduino (3.3 V board and module)
// Wiring: SI=MOSI, SCK=SCK, CS0=10, CD=9, RST=8; VDD=3V3 (no BUSY line).
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), 4-wire SPI: BM0 = D13 = VSS. Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD.
// Minimal use: a border and a diagonal on the 240 x 128 panel.
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
UC1611sSpiMinimal<DisplayConnection> lcd(connection);  // Create UC1611s driver, (connection, width=240, height=128)

static void run(UC1611sSpiMinimal<DisplayConnection>& lcd) {
    lcd.clear();                                    // Clear framebuffer, ()
    for (int16_t x = 0; x < 240; x++) {
        lcd.set_pixel(x, 0);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, 127);                      // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, static_cast<int16_t>(x * 127 / 239));  // Set pixel, (x 0..239, y 0..127, on=true)
    }
    for (int16_t y = 0; y < 128; y++) {
        lcd.set_pixel(0, y);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(239, y);                      // Set pixel, (x 0..239, y 0..127, on=true)
    }
    lcd.show();                                     // Send framebuffer to the display, ()
    PRINT("done\n");
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!lcd.valid()) {
        PRINT("UC1611s init failed (check wiring)\n");
        return;
    }
    run(lcd);
}

void loop() {}
