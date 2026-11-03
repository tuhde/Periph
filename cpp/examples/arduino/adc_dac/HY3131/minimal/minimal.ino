#include <SPI.h>
#include <Periph.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(1000000, MSBFIRST, SPI_MODE0);                  // 1 MHz, MSB first, CPOL=0 CPHA=0
SPIConnection connection(SPI, TEST_CS_PIN, settings);                // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
HY3131Minimal* adc;

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    adc = new HY3131Minimal(connection, 1.2f);                              // Construct and initialise the HY3131, (connection, vref=1.2 V) → HY3131Minimal
}

void loop() {
    float v = adc->read_voltage();                                          // Read AD1 differential voltage, () → float V
    Serial.println(v);
    delay(1000);
}
