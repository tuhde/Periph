#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>
#include <stdarg.h>
#include <stdio.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x50
#endif

I2CConnection connection(Wire, TEST_ADDR, nullptr, nullptr, 2);   // 2-byte FRAM address
I2CConnection idConnection(Wire, 0x7C);                           // reserved Device ID address
MB85RCMinimal fram(connection);                                       // Create MB85RC driver, (connection) → void

static void say(const char* fmt, ...) {
    char b[96];
    va_list a;
    va_start(a, fmt);
    vsnprintf(b, sizeof(b), fmt, a);
    va_end(a);
    Serial.print(b);
}

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
}

uint8_t counter = 0;

void loop() {
    fram.write_byte(0x0000, counter);                                       // Write a single byte, (address 0–32767, value 0–255) → bool
    uint8_t value = fram.read_byte(0x0000);                                 // Read a single byte, (address 0–32767) → uint8_t
    say("Stored: %u\n", (unsigned)value);
    counter++;
    delay(1000);
}
