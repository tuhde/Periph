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
        float t, h, p;
        bme.read(t, h, p);                                                 // Read T/H/P with compensation, (temp_c, humidity_pct, pressure_pa) → void
        printf("%.2f C  %.2f %%RH  %.2f Pa\n", t, h, p);
        usleep(1000000);
    }
    return 0;
}
