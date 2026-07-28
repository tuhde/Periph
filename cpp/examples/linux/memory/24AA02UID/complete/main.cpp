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

    Uid24AA02UIDAFull mem(connection);                                      // Create 24AA02UID driver, (connection)

    uint8_t uid[8];
    mem.read_uid(uid);                                                     // Read factory-programmed 128-bit UID, (out[8]) → void
    printf("UID:");
    for (int i = 0; i < 8; i++) printf(" %02X", uid[i]);
    printf("\n");
    uint8_t page[16];
    mem.read(0x00, page, 16);                                              // Read user bytes, (addr, buf, len) → void
    printf("page0:");
    for (int i = 0; i < 16; i++) printf(" %02X", page[i]);
    printf("\n");
    uint8_t val = 0xAB;
    mem.write(0x00, &val, 1);                                              // Write user bytes, (addr, data, len) → void
    usleep(5000);
    mem.read(0x00, &val, 1);
    printf("readback=0x%02X\n", val);
    return 0;
}
