#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x50, nullptr, nullptr, 2);
CAT24C512Full ee(connection);                                           // Create CAT24C512 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
    uint8_t cb[4];
    uint8_t pattern[384], back[384];
    for (uint16_t i = 0; i < 384; i++) pattern[i] = (uint8_t)((i * 7) & 0xFF);

    // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
    ee.read(0x0000, cb, 4);                                             // Sequential read, (address, buf, length) → bool
    uint32_t counter = ((uint32_t)cb[0] << 24) | ((uint32_t)cb[1] << 16) | ((uint32_t)cb[2] << 8) | cb[3];
    counter++;
    cb[0] = counter >> 24; cb[1] = counter >> 16; cb[2] = counter >> 8; cb[3] = counter;
    ee.write(0x0000, cb, 4);                                            // Write data, (address, data, length) → bool
    Serial.print("Boot counter: "); Serial.print((unsigned long)counter); Serial.println();

    // --- Page-write benchmark: 3 pages, 128-byte aligned ---
    uint32_t t0 = millis();
    ee.write(0x0080, pattern, 384);                                     // Write data, (address, data, length) → bool
    uint32_t elapsed = millis() - t0;
    Serial.print("Wrote 384 bytes in "); Serial.print((unsigned long)elapsed); Serial.print(" ms (incl. ACK-polled write cycles)"); Serial.println();
    ee.read(0x0080, back, 384);                                         // Sequential read, (address, buf, length) → bool
    bool same = true;
    for (uint16_t i = 0; i < 384; i++) same = same && back[i] == pattern[i];
    Serial.print("Read-back matches: "); Serial.print((unsigned)same); Serial.println();

    // --- A write that crosses a page boundary is refused ---
    Serial.print("write_page across boundary accepted: "); Serial.print((unsigned)ee.write_page(0x00FC, pattern, 8)); Serial.println();
    // ee.write_page(address, data, length) → bool, false across a page boundary
    ee.fill(0x0080, 384);                                               // Fill a range, (address, length, value=0) → bool
    Serial.print("size "); Serial.print((unsigned long)ee.size()); Serial.print(", page "); Serial.print((unsigned)ee.page_size()); Serial.print(", ready "); Serial.print((unsigned)ee.wait_ready(10)); Serial.println();
}

void loop() {
    delay(1000);
}
