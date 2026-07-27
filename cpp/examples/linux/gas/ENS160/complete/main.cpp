#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ENS160.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x52;
    I2CConnectionLinux connection(bus, addr);

    ENS160Full ens(connection);                                             // Create ENS160 driver, (connection)

    uint16_t part_id = ens.part_id();                                      // Read part ID, () → uint16_t  (0x0160)
    printf("part_id=0x%04X\n", part_id);
    uint8_t fw[3];
    ens.firmware_version(fw[0], fw[1], fw[2]);                             // Read firmware version, (major, minor, release) → void
    printf("fw=%u.%u.%u\n", fw[0], fw[1], fw[2]);
    ens.set_temp_rh_comp(25.0f, 50.0f);                                    // Set T/H compensation, (temp_c, humidity_pct) → void
                                                                           // improves accuracy when ambient conditions differ from 25 °C / 50 %RH
    printf("eCO2=%u  TVOC=%u  AQI=%u\n",
           ens.eco2(), ens.tvoc(), ens.aqi());                             // Read eCO2, () → uint16_t ppm ; tvoc() → uint16_t ppb ; aqi() → uint8_t 1–5
    ens.deep_sleep();                                                      // Enter deep-sleep, () → void
    usleep(10000);
    ens.wake();                                                            // Wake from deep-sleep, () → void
    return 0;
}
