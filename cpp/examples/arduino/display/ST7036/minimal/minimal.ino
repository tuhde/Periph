// ST7036 minimal example: EA DOGM162W-A (2 x 16), Arduino (3.3 V board and module)
// Wiring: SI=MOSI, SCL=SCK, CSB=10, RS=9, RESET=8; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Minimal use: two lines of text.
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
ST7036Minimal<DisplayConnection> lcd(connection);  // Create ST7036 driver, (connection)

static void run(ST7036Minimal<DisplayConnection>& lcd) {
    lcd.print_at(0, 0, "Hello, Periph!");              // Write text, (col, row, text)
    lcd.print_at(0, 1, "ST7036 DOGM162");              // Write text, (col, row, text)
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
