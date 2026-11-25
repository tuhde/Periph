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
MB85RCFull fram(connection);                                       // Create MB85RC driver, (connection) → void

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
    say("Size: %u bytes\n", (unsigned)fram.size());                       // Get array size, () → uint16_t
                                                                            // always 32768 for the MB85RC256V
    fram.set_max_chunk(16);                                                 // Set max bytes per bus transaction, (max_chunk 1–32=30) → void
    say("max_chunk: %u\n", (unsigned)fram.max_chunk());                   // Get max bytes per bus transaction, () → uint8_t

    fram.write_byte(0x0010, 0xA5);                                          // Write a single byte, (address 0–32767, value 0–255) → bool
                                                                            // no write cycle, no polling
    uint8_t b = fram.read_byte(0x0010);                                     // Read a single byte, (address 0–32767) → uint8_t
                                                                            // random read
    say("Byte: 0x%02X\n", (unsigned)b);

    uint8_t block[40];
    for (uint8_t i = 0; i < 40; i++) block[i] = i;
    fram.write(0x0100, block, 40);                                          // Sequential write, (address, data, length) → bool
                                                                            // split into max_chunk-byte transactions
    uint8_t back[40];
    fram.read(0x0100, back, 40);                                            // Sequential read, (address, buf, length) → bool
                                                                            // split into max_chunk-byte transactions
    say("Block[39]: %u\n", (unsigned)back[39]);

    fram.fill(0x0100, 40, 0xFF);                                            // Fill a range, (address, length, value=0) → bool
                                                                            // writes value over the range in chunks
    fram.read(0x0100, back, 8);                                             // Sequential read, (address, buf, length) → bool
    say("After fill: 0x%02X\n", (unsigned)back[0]);

    uint16_t manufacturer = 0;
    uint8_t density = 0, product = 0;
    fram.read_device_id(idConnection, &manufacturer, &density, &product, 0x50);// Read Device ID, (id_connection, &mfr, &density, &product, address=0x50) → void
                                                                            // reads 3 bytes via reserved slave address 0x7C
    say("Device ID: mfr=0x%03X density=0x%X product=0x%02X\n", (unsigned)manufacturer, (unsigned)density, (unsigned)product);
}

void loop() {
    delay(1000);
}
