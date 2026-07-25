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

    BMP280Full bmp(transport);                                             // Create BMP280 driver, (transport)

    uint8_t id = bmp.chip_id();                                            // Read chip ID, () → uint8_t  (0x58 BMP280 / 0x60 BME280)
    printf("chip_id=0x%02X\n", id);
    float t = bmp.temperature();                                           // Read temperature, () → float °C
    float p = bmp.pressure();                                              // Read pressure, () → float Pa
    printf("t=%.2f p=%.2f\n", t, p);
    bmp.configure(2, 5, 1, 0);                                            // Configure oversampling+filter, (osrs_t, osrs_p, mode, filter) → void
    t = bmp.temperature(); p = bmp.pressure();
    printf("configured t=%.2f p=%.2f\n", t, p);
    bmp.reset();                                                           // Trigger soft reset, () → void
    return 0;
}
