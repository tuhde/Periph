#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LC709203F.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x0B;
    I2CConnectionLinux connection(bus, addr);
    LC709203FMinimal gauge(connection, 1000, 1);                 // Create LC709203F driver, (connection, capacityMah, profile)

    while (true) {
        printf("%.3f V  %.1f %%  %.2f C\n",
               (double)gauge.voltage(), (double)gauge.percent(), (double)gauge.temperature());   // Read V / % / °C, () → float each
        usleep(5000000);
    }
    return 0;
}
