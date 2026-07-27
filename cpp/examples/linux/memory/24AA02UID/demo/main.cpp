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

    // --- Device identity tag ---
    // Reads the factory UID and displays it as a hex string, then reads
    // a 16-byte user label stored at address 0x00.
    uint8_t uid[8];
    mem.read_uid(uid);                                                     // Read factory-programmed 128-bit UID, (out[8]) → void
    printf("Device UID: ");
    for (int i = 0; i < 8; i++) printf("%s%02X", i ? ":" : "", uid[i]);
    printf("\n");
    char label[17] = {};
    mem.read(0x00, (uint8_t*)label, 16);                                   // Read user bytes, (addr, buf, len) → void
    printf("Label: %.16s\n", label);
    return 0;
}
