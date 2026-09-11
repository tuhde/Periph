#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BMP581.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x46;
    I2CConnectionLinux connection(bus, addr);

    BMP581Minimal bmp(connection);                                          // Create BMP581 driver, (connection, spi=false)

    while (true) {
        float p = bmp.pressure();                                          // Read pressure, () → float Pa
        float t = bmp.temperature();                                       // Read temperature, () → float °C
        printf("%.1f C  %.1f Pa\n", t, p);
        usleep(1000000);
    }
    return 0;
}
