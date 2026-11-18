#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Max17048.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x36;
    I2CConnectionLinux connection(bus, addr);
    Max17048Minimal gauge(connection, 1);                   // Create MAX17048 driver, (connection, cells)

    while (true) {
        printf("%.3f V  %.1f %%\n", (double)(gauge.voltage()), (double)(gauge.percent()));          // Read V / %, () → float each
        usleep(1000000);
    }
    return 0;
}
