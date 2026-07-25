#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "INA3221.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CTransportLinux transport(bus, addr);

    INA3221Minimal ina(transport);                                         // Create INA3221 driver, (transport)

    while (true) {
        for (int ch = 1; ch <= 3; ch++)
            printf("ch%d: %.4f V  %.4f A\n",
                   ch, ina.voltage(ch), ina.current(ch));                  // Read bus voltage (ch=1-3) → float V ; current(ch) → float A
        usleep(1000000);
    }
    return 0;
}
