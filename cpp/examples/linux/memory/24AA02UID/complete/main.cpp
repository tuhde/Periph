#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "24AA02UID.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x50;
    I2CConnectionLinux connection(bus, addr);

    EEPROM24AA02UIDFull mem(connection);                                    // Create 24AA02UID driver, (connection)

    uint8_t uid[4];
    mem.read_uid(uid);                                                     // Read factory 32-bit serial number, (buf[4]) → void
                                                                           // read-only upper block 0xFC–0xFF
    printf("UID: %02X%02X%02X%02X\n", uid[0], uid[1], uid[2], uid[3]);
    printf("manufacturer=0x%02X device=0x%02X\n",
           mem.read_manufacturer_code(),                                   // Manufacturer code at 0xFA, () → uint8_t (0x29 Microchip)
           mem.read_device_code());                                        // Device code at 0xFB, () → uint8_t (0x41)

    mem.write_byte(0x10, 0xAB);                                            // Write one byte, (address 0x00–0x7F, value) → void
                                                                           // waits for the internal write cycle (≤ 5 ms)
    printf("byte 0x10=0x%02X\n", mem.read_byte(0x10));                    // Read one byte, (address) → uint8_t

    const uint8_t page[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    mem.write_page(0x00, page, sizeof(page));                              // Write within one 8-byte page, (address, data, length 1–8) → void
                                                                           // address must be page-aligned (0, 8, 16, …)
    const char text[] = "Periph 24AA02UID";
    mem.write(0x20, (const uint8_t*)text, 16);                             // Write any length, (address, data, length) → void
                                                                           // split into page writes automatically
    uint8_t buf[16];
    mem.read(0x20, buf, sizeof(buf));                                      // Sequential read, (address, buf, length) → void
    printf("0x20: %.16s\n", (const char*)buf);
    return 0;
}
