#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x50);
AT24C02NFull ee(connection);                                           // Create AT24C02N driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
    uint8_t data[40], back[40];
    uint8_t page[16];
    for (uint8_t i = 0; i < 40; i++) data[i] = i;
    for (uint8_t i = 0; i < 16; i++) page[i] = i;

    Serial.print("size "); Serial.print((unsigned long)ee.size()); Serial.print(" bytes, page "); Serial.print((unsigned)ee.page_size()); Serial.print(" bytes"); Serial.println();
    // ee.size() → uint32_t (256), ee.page_size() → uint16_t (8)
    ee.set_max_chunk(16);                                               // Set max bytes per bus transaction, (value 1-32, default 30) → void
    Serial.print("max_chunk "); Serial.print((unsigned)ee.max_chunk()); Serial.println();
    // ee.max_chunk() → uint8_t

    ee.write_byte(0x0010, 0xA5);                                        // Write a single byte, (address 0-255, value 0-255) → bool
                                                                        // ACK-polled until the write cycle ends
    Serial.print("byte 0x"); Serial.print((unsigned)ee.read_byte(0x0010), HEX); Serial.println();
    // ee.read_byte(address) → uint8_t, random read
    ee.write(0x0040, data, 40);                                         // Write data, (address, data, length) → bool
                                                                        // split at 8-byte page boundaries and max_chunk, ACK-polled per chunk
    ee.read(0x0040, back, 40);                                          // Sequential read, (address, buf, length) → bool
                                                                        // split into max_chunk-byte transactions
    Serial.print("block[39] = "); Serial.print((unsigned)back[39]); Serial.println();
    ee.write_page(0x00B8, page, 8);                                    // Write within one page, (address, data, length) → bool
                                                                        // false if the data crosses a page boundary
    ee.fill(0x0040, 40, 0xFF);                                          // Fill a range, (address, length, value=0) → bool
                                                                        // page-wise writes of value
    Serial.print("ready "); Serial.print((unsigned)ee.wait_ready(10)); Serial.println();
    // ee.wait_ready(timeout_ms) → bool, ACK-polls the chip
    Serial.print("done"); Serial.println();
}

void loop() {
    delay(1000);
}
