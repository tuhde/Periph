#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MPL115A2.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CConnectionLinux connection(bus, addr);

    MPL115A2Full baro(connection);                      // Create MPL115A2 driver, (connection)

    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);                   // Read cached coefficients, (a0&, b1&, b2&, c12&) → void
    printf("a0=%.3f b1=%.5f b2=%.5f c12=%.8f\n", a0, b1, b2, c12);

    // --- Barometer log ---
    // Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
    // altitude and the raw counts, then the min/max of the run.
    float pmin = 1e9f, pmax = 0.0f;
    for (int n = 0; n < 15; n++) {
        float pa = baro.read_pressure();                  // Read pressure, () → float Pa
        float hpa = baro.pressure_hpa();                  // Read pressure, () → float hPa
        float alt = baro.altitude();                      // Compute altitude, (sea_level_pa=101325.0) → float m
        uint16_t padc, tadc;
        baro.read_raw(padc, tadc);                        // One conversion, (padc&, tadc&) → void
        if (pa < pmin) pmin = pa;
        if (pa > pmax) pmax = pa;
        printf("%ds: %.0f Pa, %.2f hPa, alt=%.1f m, raw=(%d, %d)\n", n * 2, pa, hpa, alt, (int)padc, (int)tadc);
        usleep(2000000);
    }
    printf("Summary: min=%.0f Pa, max=%.0f Pa\n", pmin, pmax);

    return 0;
}
