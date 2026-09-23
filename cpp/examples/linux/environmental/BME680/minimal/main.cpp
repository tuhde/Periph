#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BME680.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CConnectionLinux connection(bus, addr);

    BME680Minimal bme(connection);                                          // Create BME680 driver, (connection)
                                                                           // forced mode, heater 320 °C for 150 ms

    while (true) {
        float t = bme.temperature();                                       // Read temperature, () → float °C
        float h = bme.humidity();                                          // Read relative humidity, () → float %RH
        float p = bme.pressure();                                          // Read pressure, () → float hPa
        float gas = bme.gas_resistance();                                  // Read gas resistance, () → float Ω (NaN if invalid)
        printf("%.2f C  %.2f %%RH  %.2f hPa  %.0f Ohm\n", (double)t, (double)h, (double)p, (double)gas);
        usleep(1000000);
    }
    return 0;
}
