#include <SPI.h>
#include <Periph.h>
#include <string.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

// Formatted output over Serial (AVR has no printf() to Serial).
static void out(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

void setup() {
    Serial.begin(115200);
    SPI.begin();
    delay(2000);

    SPISettings settings(8000000, MSBFIRST, SPI_MODE0);
    SPIConnection connection(SPI, TEST_CS_PIN, settings);                        // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
    SRAM23X1024Minimal sram(connection);                                     // Create 23X1024 driver, (connection) → SRAM23X1024Minimal

    uint8_t counter = 0;
    while (true) {
        sram.write_byte(0x00000, counter);                                   // Write one byte, (address 0–131071, value 0–255)
        uint8_t value = sram.read_byte(0x00000);                             // Read one byte, (address 0–131071) → byte
        out("wrote 0x%02X, read 0x%02X\n", counter, value);
        counter++;
        delay(500);
    }
}

void loop() {
}
