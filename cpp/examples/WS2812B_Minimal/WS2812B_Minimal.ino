#include <SPI.h>
#include "NeoPixelTransport.h"
#include "WS2812B.h"

NeoPixelTransport transport(SPI);               // Create NeoPixel transport, (spi=SPIClass&)
WS2812BMinimal strip(transport, 30);            // Create WS2812B driver, (transport, n=30 pixels)

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
