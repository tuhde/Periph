#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <chrono>
#include "I2CConnectionLinux.h"
#include "MB85RC.h"

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
    MB85RCFull fram(connection);                                             // Create MB85RC driver, (connection) → void
    // Device ID lives at the reserved address 0x7C; i2c-dev may refuse it.
    I2CConnectionLinux* idPtr = nullptr;
    try { idPtr = new I2CConnectionLinux(bus, 0x7C); } catch (...) {}
    // --- Persistent boot counter ---
    // A 4-byte big-endian counter at 0x0000 survives power loss. FRAM has no
    // write cycle, so the increment is committed the moment the write ends.
    uint8_t cbuf[4];
    fram.read(0x0000, cbuf, 4);                                             // Sequential read, (address, buf, length) → bool
    uint32_t counter = (((uint32_t)cbuf[0] << 24) | ((uint32_t)cbuf[1] << 16) | ((uint32_t)cbuf[2] << 8) | cbuf[3]) + 1;
    cbuf[0] = counter >> 24; cbuf[1] = counter >> 16; cbuf[2] = counter >> 8; cbuf[3] = counter;
    fram.write(0x0000, cbuf, 4);                                            // Sequential write, (address, data, length) → bool
    printf("Boot count: %lu\n", (unsigned long)counter);

    // --- Ring-buffer logger, back-to-back with no delays ---
    // 1,000 records of 16 bytes (index + counter + padding) are written one
    // after the other without any pause — an EEPROM would need ACK polling
    // after every page, FRAM does not.
    const uint16_t LOG_BASE = 0x0100;
    const uint16_t RECORD = 16;
    const uint16_t COUNT = 1000;
    const uint16_t RING = 200;   // records that fit before 0x7FFF
    uint8_t rec[16];
    uint32_t t0 = now_us();
    for (uint16_t i = 0; i < COUNT; i++) {
        for (uint8_t k = 0; k < 16; k++) rec[k] = 0;
        rec[0] = i >> 24; rec[1] = i >> 16; rec[2] = i >> 8; rec[3] = i;
        rec[4] = counter >> 24; rec[5] = counter >> 16; rec[6] = counter >> 8; rec[7] = counter;
        fram.write(LOG_BASE + (i % RING) * RECORD, rec, RECORD);            // Sequential write, (address, data, length) → bool
    }
    uint32_t dt_us = now_us() - t0;
    if (dt_us == 0) dt_us = 1;
    printf("Logged %u bytes in %lu us = %lu B/s\n", (unsigned)(COUNT * RECORD), (unsigned long)dt_us,
          (unsigned long)((uint64_t)COUNT * RECORD * 1000000ULL / dt_us));

    // --- Verify the last five records ---
    // Read back the newest records from the ring and check their contents.
    bool ok = true;
    for (uint16_t i = COUNT - 5; i < COUNT; i++) {
        fram.read(LOG_BASE + (i % RING) * RECORD, rec, RECORD);             // Sequential read, (address, buf, length) → bool
        uint32_t idx = ((uint32_t)rec[0] << 24) | ((uint32_t)rec[1] << 16) | ((uint32_t)rec[2] << 8) | rec[3];
        if (idx != i) ok = false;
    }
    printf("Last 5 records verified: %s\n", ok ? "yes" : "no");

    // --- Clear the log area ---
    // fill() zeroes the whole ring in chunked transactions.
    fram.fill(LOG_BASE, RING * RECORD, 0);                                  // Fill a range, (address, length, value=0) → bool
    printf("Log cleared; array size: %u bytes\n", (unsigned)fram.size());    // Get array size, () → uint16_t

    // --- Device ID (needs the reserved address 0x7C) ---
    // Some hosts (Linux i2c-dev) refuse addresses 0x78-0x7F.
    uint16_t manufacturer = 0;
    uint8_t density = 0, product = 0;
    if (idPtr) fram.read_device_id(*idPtr, &manufacturer, &density, &product, 0x50);// Read Device ID, (id_connection, &mfr, &density, &product, address=0x50) → void
    printf("Device ID: mfr=0x%03X density=0x%X product=0x%02X\n", (unsigned)manufacturer, (unsigned)density, (unsigned)product);
    delete idPtr;
    return 0;
}
