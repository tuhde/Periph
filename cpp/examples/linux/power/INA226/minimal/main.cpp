#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "INA226.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CTransportLinux transport(bus, addr);

    INA226Minimal ina(transport);                                          // Create INA226 driver, (transport)

    while (true) {
        printf("V=%.4f V  I=%.4f A  P=%.4f W\n",
               ina.voltage(), ina.current(), ina.power());                 // Read bus voltage () → float V ; current() → float A ; power() → float W
        usleep(1000000);
    }
    return 0;
}
