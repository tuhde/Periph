#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BMP280.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CConnectionLinux connection(bus, addr);

    BMP280Full bmp(connection);                                             // Create BMP280 driver, (connection)

    // --- Altitude tracker ---
    // Ultra-high-resolution oversampling with the strongest IIR filter keeps
    // the altitude estimate steady; update the sea-level reference to the
    // local forecast for absolute accuracy.
    const float P0_HPA = 1013.25f;
    bmp.configure(BMP280Full::OSRS_X2, BMP280Full::OSRS_X16, BMP280Full::MODE_NORMAL,
                  BMP280Full::FILTER_16, BMP280Full::T_SB_62_5_MS);        // Configure in one write, (osrs_t, osrs_p, mode, filter, t_sb) → void
    while (true) {
        float p = bmp.pressure();                                          // Read pressure, () → float hPa
        float alt = bmp.altitude(P0_HPA);                                  // Altitude from pressure, (sea_level_hpa hPa) → float m
        printf("%.2f hPa  %.1f m\n", (double)p, (double)alt);
        usleep(1000000);
    }
    return 0;
}
