#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Pn532.h"

static void print_hex(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) printf("%02X", data[i]);
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x24;
    I2CConnectionLinux connection(bus, addr);

    PN532Minimal reader(connection);                               // Create PN532 driver, (connection) → void
    reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → bool
    while (true) {
        uint8_t uid[10];
        uint8_t len = reader.read_uid(uid);                        // Detect a type A card, (uid*, timeoutMs=1000) → uint8_t length
        printf("UID ");
        if (len) print_hex(uid, len); else printf("none");
        printf("\n");
        usleep((unsigned)(1000) * 1000);
    }
    return 0;
}
