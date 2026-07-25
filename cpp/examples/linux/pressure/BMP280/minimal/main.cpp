#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "BMP280.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CTransportLinux transport(bus, addr);

    BMP280Minimal bmp(transport);                                          // Create BMP280 driver, (transport)

    while (true) {
        float t = bmp.temperature();                                       // Read temperature, () → float °C
        float p = bmp.pressure();                                          // Read pressure, () → float Pa
        printf("%.2f C  %.2f Pa\n", t, p);
        usleep(1000000);
    }
    return 0;
}
