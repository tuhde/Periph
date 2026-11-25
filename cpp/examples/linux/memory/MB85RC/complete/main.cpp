#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include "I2CConnectionLinux.h"
#include "MB85RC.h"

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
    printf("Size: %u bytes\n", (unsigned)fram.size());                       // Get array size, () → uint16_t
                                                                            // always 32768 for the MB85RC256V
    fram.set_max_chunk(16);                                                 // Set max bytes per bus transaction, (max_chunk 1–32=30) → void
    printf("max_chunk: %u\n", (unsigned)fram.max_chunk());                   // Get max bytes per bus transaction, () → uint8_t

    fram.write_byte(0x0010, 0xA5);                                          // Write a single byte, (address 0–32767, value 0–255) → bool
                                                                            // no write cycle, no polling
    uint8_t b = fram.read_byte(0x0010);                                     // Read a single byte, (address 0–32767) → uint8_t
                                                                            // random read
    printf("Byte: 0x%02X\n", (unsigned)b);

    uint8_t block[40];
    for (uint8_t i = 0; i < 40; i++) block[i] = i;
    fram.write(0x0100, block, 40);                                          // Sequential write, (address, data, length) → bool
                                                                            // split into max_chunk-byte transactions
    uint8_t back[40];
    fram.read(0x0100, back, 40);                                            // Sequential read, (address, buf, length) → bool
                                                                            // split into max_chunk-byte transactions
    printf("Block[39]: %u\n", (unsigned)back[39]);

    fram.fill(0x0100, 40, 0xFF);                                            // Fill a range, (address, length, value=0) → bool
                                                                            // writes value over the range in chunks
    fram.read(0x0100, back, 8);                                             // Sequential read, (address, buf, length) → bool
    printf("After fill: 0x%02X\n", (unsigned)back[0]);

    uint16_t manufacturer = 0;
    uint8_t density = 0, product = 0;
    if (idPtr) fram.read_device_id(*idPtr, &manufacturer, &density, &product, 0x50);// Read Device ID, (id_connection, &mfr, &density, &product, address=0x50) → void
                                                                            // reads 3 bytes via reserved slave address 0x7C
    printf("Device ID: mfr=0x%03X density=0x%X product=0x%02X\n", (unsigned)manufacturer, (unsigned)density, (unsigned)product);
    delete idPtr;
    return 0;
}
