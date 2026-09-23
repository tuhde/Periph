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

    EEPROM24AA02UIDMinimal mem(connection);                                 // Create 24AA02UID driver, (connection)

    uint8_t uid[4];
    mem.read_uid(uid);                                                     // Read factory 32-bit serial number, (buf[4]) → void
    printf("UID: %02X%02X%02X%02X\n", uid[0], uid[1], uid[2], uid[3]);
    return 0;
}
