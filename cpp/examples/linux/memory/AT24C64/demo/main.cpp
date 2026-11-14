#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <chrono>
#include "I2CConnectionLinux.h"
#include "AT24C64.h"

static uint32_t now_us() {
    return (uint32_t)std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x50;
    I2CConnectionLinux connection(bus, addr, nullptr, nullptr, 2);          // Create I2C connection, (bus, addr, int=null, en=null, reg_bytes=2)
    AT24C64Full ee(connection);                                       // Create AT24C64 driver, (connection) → void
    uint8_t cb[4];
    uint8_t pattern[96], back[96];
    for (uint16_t i = 0; i < 96; i++) pattern[i] = (uint8_t)((i * 7) & 0xFF);

    // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
    ee.read(0x0000, cb, 4);                                             // Sequential read, (address, buf, length) → bool
    uint32_t counter = ((uint32_t)cb[0] << 24) | ((uint32_t)cb[1] << 16) | ((uint32_t)cb[2] << 8) | cb[3];
    counter++;
    cb[0] = counter >> 24; cb[1] = counter >> 16; cb[2] = counter >> 8; cb[3] = counter;
    ee.write(0x0000, cb, 4);                                            // Write data, (address, data, length) → bool
    printf("Boot counter: %lu\n", (unsigned long)counter);

    // --- Page-write benchmark: 3 pages, 32-byte aligned ---
    uint32_t t0 = (now_us() / 1000);
    ee.write(0x0080, pattern, 96);                                     // Write data, (address, data, length) → bool
    uint32_t elapsed = (now_us() / 1000) - t0;
    printf("Wrote 96 bytes in %lu ms (incl. ACK-polled write cycles)\n", (unsigned long)elapsed);
    ee.read(0x0080, back, 96);                                         // Sequential read, (address, buf, length) → bool
    bool same = true;
    for (uint16_t i = 0; i < 96; i++) same = same && back[i] == pattern[i];
    printf("Read-back matches: %u\n", (unsigned)same);

    // --- A write that crosses a page boundary is refused ---
    printf("write_page across boundary accepted: %u\n", (unsigned)ee.write_page(0x00FC, pattern, 8));
    // ee.write_page(address, data, length) → bool, false across a page boundary
    ee.fill(0x0080, 96);                                               // Fill a range, (address, length, value=0) → bool
    printf("size %lu, page %u, ready %u\n", (unsigned long)ee.size(), (unsigned)ee.page_size(), (unsigned)ee.wait_ready(10));
    return 0;
}
