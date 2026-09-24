#include <SPI.h>
#include <Periph.h>

SPISettings apa102_settings(1000000, MSBFIRST, SPI_MODE0);  // 1 MHz, Mode 0, MSB first
SPIConnection connection(SPI, 10, apa102_settings);         // Create SPI connection, (spi=SPIClass&, cs_pin=10, settings)
APA102Minimal strip(connection, 30);                         // Create APA102 driver, (connection, n=30 pixels)

void setup() {
    Serial.begin(115200);
    SPI.begin();
    pinMode(10, OUTPUT);
    digitalWrite(10, HIGH);
}

void loop() {
    strip.fill(255, 0, 0);                                  // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
    delay(1000);
    strip.fill(0, 255, 0);                                  // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → void
    delay(1000);
    strip.fill(0, 0, 255);                                  // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → void
    delay(1000);
    strip.off();                                            // Turn off all pixels, () → void
    delay(1000);
}
