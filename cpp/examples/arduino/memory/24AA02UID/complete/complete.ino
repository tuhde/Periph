#include <Wire.h>
#include "I2CTransport.h"
#include "24AA02UID.h"

I2CTransport transport(Wire, 0x50);
EEPROM24AA02UIDFull eeprom(transport);                         // Create 24AA02UID driver, (transport, addr=0x50) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();

    uint8_t uid[4];
    eeprom.read_uid(uid);                                       // Read 32-bit unique serial number, (buf[4]) → void
                                                                // reads 4 bytes at 0xFC-0xFF
    Serial.print("UID bytes: ");
    for (uint8_t i = 0; i < 4; i++) {
        if (uid[i] < 0x10) Serial.print('0');
        Serial.print(uid[i], HEX);
    }
    Serial.println();

    uint32_t uid_int = ((uint32_t)uid[0] << 24) | ((uint32_t)uid[1] << 16)
                     | ((uint32_t)uid[2] << 8)  |  (uint32_t)uid[3];
    Serial.print("UID int:   ");
    Serial.println(uid_int);

    uint8_t mfr = eeprom.read_manufacturer_code();             // Read manufacturer code, () → byte
                                                                // reads 0xFA; expect 0x29 (Microchip)
    uint8_t dev = eeprom.read_device_code();                   // Read device code, () → byte
                                                                // reads 0xFB; expect 0x41
    Serial.print("MFR: 0x");
    if (mfr < 0x10) Serial.print('0');
    Serial.print(mfr, HEX);
    Serial.print("  DEV: 0x");
    if (dev < 0x10) Serial.print('0');
    Serial.println(dev, HEX);

    uint8_t first = eeprom.read_byte(0x00);                    // Read a single byte, (address=0x00-0x7F) → byte
                                                                // random read at user EEPROM address
    Serial.print("First byte: 0x");
    if (first < 0x10) Serial.print('0');
    Serial.println(first, HEX);

    eeprom.write_byte(0x10, 0xA5);                             // Write a single byte, (address, value) → void
                                                                // byte write + delay until complete (max 5 ms)
    uint8_t verify = eeprom.read_byte(0x10);                   // Read a single byte, (address=0x00-0x7F) → byte
    Serial.print("Wrote 0xA5, read back: 0x");
    if (verify < 0x10) Serial.print('0');
    Serial.println(verify, HEX);

    uint8_t buf[8];
    eeprom.read(0x20, buf, 8);                                 // Sequential read, (address, buf, length) → void
                                                                // reads 8 bytes starting at address
    Serial.print("Block @ 0x20: ");
    for (uint8_t i = 0; i < 8; i++) {
        if (buf[i] < 0x10) Serial.print('0');
        Serial.print(buf[i], HEX);
        Serial.print(' ');
    }
    Serial.println();

    uint8_t page_data[] = { 0x01, 0x02, 0x03, 0x04 };
    eeprom.write_page(0x40, page_data, 4);                     // Page write, (address, data, length) → void
                                                                // writes up to 8 bytes within one page

    uint8_t cross[] = { 0xAA, 0xBB, 0xCC, 0xDD, 0xEE };
    eeprom.write(0x44, cross, 5);                              // Arbitrary-length write, (address, data, length) → void
                                                                // splits at 8-byte page boundaries; waits for each chunk
    Serial.println("Multi-page write complete");
}

void loop() {}
