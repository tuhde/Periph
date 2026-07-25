#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "BME280.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CTransportLinux transport(bus, addr);

    BME280Full bme(transport);                                             // Create BME280 driver, (transport)

    uint8_t id = bme.chip_id();                                            // Read chip ID register, () → uint8_t
                                                                           // BME280 returns 0x60, BMP280 returns 0x58
    printf("chip_id=0x%02X\n", id);
    float t, h, p;
    bme.read(t, h, p);                                                     // Read T/H/P with compensation, (temp_c, humidity_pct, pressure_pa) → void
    printf("t=%.2f h=%.2f p=%.2f\n", t, h, p);
    bme.configure(2, 5, 1, 1, 1);                                         // Configure oversampling+filter+standby, (osrs_h, osrs_t, osrs_p, filter, t_sb) → void
                                                                           // osrs_h 0–5, osrs_t 0–5, osrs_p 0–5, filter 0–4, t_sb 0–7
    bme.read(t, h, p);
    printf("configured t=%.2f h=%.2f p=%.2f\n", t, h, p);
    bme.reset();                                                           // Trigger soft reset, () → void
    return 0;
}
