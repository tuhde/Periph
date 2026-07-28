#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BMP180.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x77;
    I2CConnectionLinux connection(bus, addr);

    BMP180Full bmp(connection);                                             // Create BMP180 driver, (connection)

    uint8_t id = bmp.chip_id();                                            // Read chip ID, () → uint8_t  (0x55)
    printf("chip_id=0x%02X\n", id);
    float t = bmp.temperature();                                           // Read temperature, () → float °C
    float p = bmp.pressure();                                              // Read pressure (OSS=0), () → float Pa
    float p3 = bmp.pressure(3);                                            // Read pressure (OSS=3 high-res), (oss=0–3) → float Pa
    float alt = bmp.altitude();                                            // Read altitude from pressure, () → float m
    printf("t=%.2f p=%.2f p3=%.2f alt=%.1f\n", t, p, p3, alt);
    bmp.soft_reset();                                                      // Trigger soft reset, () → void
    return 0;
}
