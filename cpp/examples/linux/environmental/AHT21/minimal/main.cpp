#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "AHT21.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CConnectionLinux connection(bus, addr);

    AHT21Minimal aht(connection);                                           // Create AHT21 driver, (connection, addr=0x38) → void

    while (true) {
        float t, h;
        aht.read(t, h);                                                    // Trigger measurement, (temperature_c, humidity_pct) → void
        printf("%.2f C  %.2f %%RH\n", t, h);
        usleep(1000000);
    }
    return 0;
}
