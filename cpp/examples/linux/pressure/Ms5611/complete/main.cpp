#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Ms5611.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x77;
    I2CConnectionLinux connection(bus, addr);

    MS5611Full sensor(connection);                                  // Create MS5611 driver, (connection) → void
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool
    float t = 0, p = 0;
    uint32_t d1 = 0, d2 = 0, adc = 0;
    uint16_t prom[8], k[6];
    bool ok = sensor.read(t, p);                                    // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
                                                                    // OSR 4096, second-order compensation applied
    printf("temperature %.2f C pressure %.1f Pa\n", (double)(t), (double)(p));
    printf("pressure %.1f Pa\n", (double)(sensor.read_pressure())); // Read pressure only, () → float Pa
                                                                    // NAN on failure
    printf("temperature %.2f C\n", (double)(sensor.read_temperature())); // Read temperature only, () → float °C
                                                                    // converts D2 only
    ok = sensor.read_raw(d1, d2);                                   // Read raw ADC codes, (d1&, d2&) → bool
                                                                    // 24-bit pressure and temperature codes
    printf("raw D1 %lu raw D2 %lu\n", (unsigned long)(d1), (unsigned long)(d2));
    sensor.compensate(d1, d2, t, p);                                // Compensate raw codes, (d1, d2, temperature_c&, pressure_pa&, second_order=true) → void
                                                                    // pure computation, no bus access
    sensor.compensate(d1, d2, t, p, false);
    sensor.set_oversampling(1024);                                  // Select oversampling ratio, (osr) → bool
                                                                    // 256, 512, 1024, 2048 or 4096
    printf("osr %lu\n", (unsigned long)(sensor.get_oversampling())); // Current oversampling ratio, () → uint16
    printf("conversion time %.3f ms\n", (double)(sensor.conversion_time() * 1000.0f)); // Max conversion time, (osr=0) → float s
    sensor.start_conversion(MS5611Full::TEMPERATURE);               // Start a conversion, (kind, osr=0) → bool
                                                                    // PRESSURE = D1, TEMPERATURE = D2; non-blocking
    usleep(12 * 1000);
    ok = sensor.read_adc(adc);                                      // Read the ADC result, (value&) → bool
                                                                    // only after the conversion time, 24-bit
    printf("adc %lu\n", (unsigned long)(adc));
    sensor.set_oversampling(4096);
    ok = sensor.read_prom(prom);                                    // Read the PROM words, (words[8]) → bool
    sensor.coefficients(k);                                         // Calibration coefficients, (c[6]) → void
                                                                    // C1 ... C6
    printf("crc ok %lu\n", (unsigned long)(sensor.prom_crc_ok()));  // Verify the PROM CRC-4, () → bool
    printf("serial %lu\n", (unsigned long)(sensor.serial_code()));  // Serial code, () → uint16
                                                                    // PROM word 7 bits 15:4
    printf("altitude %.2f m\n", (double)(sensor.altitude()));       // Altitude from pressure, (sea_level_pa=101325) → float m
                                                                    // international barometric formula
    sensor.reset();                                                 // Reset and reload PROM, () → bool
                                                                    // 0x1E, 3 ms wait
    printf("ok %lu\n", (unsigned long)(ok));
    return 0;
}
