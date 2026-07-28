#include <SPI.h>
#include "NeoPixelConnection.h"
#include "WS2812B.h"

NeoPixelConnection connection(SPI);               // Create NeoPixel connection, (spi=SPIClass&)
WS2812BMinimal strip(connection, 30);            // Create WS2812B driver, (connection, n=30 pixels)

void setup() {
    Serial.begin(115200);
    SPI.begin();
}

void loop() {
    strip.fill(255, 0, 0);                      // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
    delay(1000);
    strip.fill(0, 255, 0);                      // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → void
    delay(1000);
    strip.fill(0, 0, 255);                      // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → void
    delay(1000);
    strip.off();                                // Turn off all pixels, () → void
    delay(1000);
}
