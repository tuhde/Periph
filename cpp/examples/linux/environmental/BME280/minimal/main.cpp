#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BME280.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CConnectionLinux connection(bus, addr);

    BME280Minimal bme(connection);                                          // Create BME280 driver, (connection)

    while (true) {
        float t = bme.temperature();                                       // Read temperature, () → float °C
        float h = bme.humidity();                                          // Read relative humidity, () → float %RH
        float p = bme.pressure();                                          // Read pressure, () → float hPa
        printf("%.2f C  %.2f %%RH  %.2f hPa\n", (double)t, (double)h, (double)p);
        usleep(1000000);
    }
    return 0;
}
