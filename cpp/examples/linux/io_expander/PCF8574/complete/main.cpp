#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "PCF8574.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CTransportLinux transport(bus, addr);

    PCF8574Full pcf(transport);                                            // Create PCF8574 driver, (transport)

    printf("port=0x%02X\n", pcf.read());                                  // Read all 8 pins, () → uint8_t
    pcf.write(0xFF);                                                       // Write all 8 pins, (value) → void
    pcf.write_pin(3, false);                                               // Drive single pin low, (pin=0–7, value) → void
    printf("pin3=%d\n", pcf.read_pin(3));                                 // Read single pin, (pin=0–7) → bool
    pcf.toggle(0x0F);                                                      // Toggle masked pins, (mask) → void
    return 0;
}
