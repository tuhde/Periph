#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MPL115A2.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CConnectionLinux connection(bus, addr);

    MPL115A2Minimal baro(connection);                   // Create MPL115A2 driver, (connection)

    for (int i = 0; i < 5; i++) {
        float p = baro.read_pressure();                   // Read pressure, () → float Pa
        float t = baro.read_temperature();                // Read temperature, () → float °C (approximate)
        printf("%.0f Pa, %.1f C\n", p, t);
        usleep(1000000);
    }

    return 0;
}
