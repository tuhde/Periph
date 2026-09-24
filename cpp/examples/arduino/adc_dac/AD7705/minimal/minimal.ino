#include <SPI.h>
#include <Periph.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(5000000, MSBFIRST, SPI_MODE3);                  // 5 MHz, MSB first, CPOL=1 CPHA=1
SPIConnection connection(SPI, TEST_CS_PIN, settings);                // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
AD7705Minimal adc(connection, 2.5, AD7705Minimal::MCLK_2_4576MHZ);   // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nullptr) → AD7705Minimal
                                                                     // default configuration: gain 1, bipolar, unbuffered, 50 Hz, self-calibrated Channel 1

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
}

void loop() {
    float v = adc.read_voltage();                                    // Read Channel 1 voltage, () → float V
    Serial.println(v);
    delay(1000);
}
