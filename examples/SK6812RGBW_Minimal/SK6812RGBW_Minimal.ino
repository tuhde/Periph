#include <SPI.h>
#include "NeoPixelTransport.h"
#include "SK6812RGBW.h"

NeoPixelTransport transport(SPI);               // Create NeoPixel transport, (spi=SPIClass&)
SK6812RGBWMinimal strip(transport, 30);         // Create SK6812RGBW driver, (transport, n=30 pixels)

void setup() {
    Serial.begin(115200);
    SPI.begin();
}

void loop() {
    strip.fill(255, 0, 0);                      // Fill all pixels red, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    delay(1000);
    strip.fill(0, 255, 0);                      // Fill all pixels green, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    delay(1000);
    strip.fill(0, 0, 255);                      // Fill all pixels blue, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    delay(1000);
    strip.fill(0, 0, 0, 255);                   // Fill all pixels white (W channel), (r=0–255, g=0–255, b=0–255, w=0–255) → void
    delay(1000);
    strip.off();                                // Turn off all pixels, () → void
    delay(1000);
}
