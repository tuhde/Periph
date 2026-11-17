#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LTC4151.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x6A;
    I2CConnectionLinux connection(bus, addr);

    LTC4151Minimal ltc(connection, 0.02f);                              // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void
    while (true) {
        printf("V=%.3f V\n", ltc.voltage());                            // Read supply voltage, () → float V
        printf("I=%.4f A\n", ltc.current());                            // Read load current, () → float A
        printf("P=%.3f W\n", ltc.power());                              // Read load power, () → float W
        usleep(1000000);
    }
    return 0;
}
