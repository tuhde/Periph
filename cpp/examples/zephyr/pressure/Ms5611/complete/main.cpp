#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Ms5611.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MS5611_ADDR 0x77

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MS5611_ADDR);
    MS5611Full sensor(connection);                                  // Create MS5611 driver, (connection) → void
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool
    float t = 0, p = 0;
    uint32_t d1 = 0, d2 = 0, adc = 0;
    uint16_t prom[8], k[6];
    bool ok = sensor.read(t, p);                                    // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
                                                                    // OSR 4096, second-order compensation applied
    printk("temperature %.2f C pressure %.1f Pa\n", (double)(t), (double)(p));
    printk("pressure %.1f Pa\n", (double)(sensor.read_pressure())); // Read pressure only, () → float Pa
                                                                    // NAN on failure
    printk("temperature %.2f C\n", (double)(sensor.read_temperature())); // Read temperature only, () → float °C
                                                                    // converts D2 only
    ok = sensor.read_raw(d1, d2);                                   // Read raw ADC codes, (d1&, d2&) → bool
                                                                    // 24-bit pressure and temperature codes
    printk("raw D1 %lu raw D2 %lu\n", (unsigned long)(d1), (unsigned long)(d2));
    sensor.compensate(d1, d2, t, p);                                // Compensate raw codes, (d1, d2, temperature_c&, pressure_pa&, second_order=true) → void
                                                                    // pure computation, no bus access
    sensor.compensate(d1, d2, t, p, false);
    sensor.set_oversampling(1024);                                  // Select oversampling ratio, (osr) → bool
                                                                    // 256, 512, 1024, 2048 or 4096
    printk("osr %lu\n", (unsigned long)(sensor.get_oversampling())); // Current oversampling ratio, () → uint16
    printk("conversion time %.3f ms\n", (double)(sensor.conversion_time() * 1000.0f)); // Max conversion time, (osr=0) → float s
    sensor.start_conversion(MS5611Full::TEMPERATURE);               // Start a conversion, (kind, osr=0) → bool
                                                                    // PRESSURE = D1, TEMPERATURE = D2; non-blocking
    k_sleep(K_MSEC(12));
    ok = sensor.read_adc(adc);                                      // Read the ADC result, (value&) → bool
                                                                    // only after the conversion time, 24-bit
    printk("adc %lu\n", (unsigned long)(adc));
    sensor.set_oversampling(4096);
    ok = sensor.read_prom(prom);                                    // Read the PROM words, (words[8]) → bool
    sensor.coefficients(k);                                         // Calibration coefficients, (c[6]) → void
                                                                    // C1 ... C6
    printk("crc ok %lu\n", (unsigned long)(sensor.prom_crc_ok()));  // Verify the PROM CRC-4, () → bool
    printk("serial %lu\n", (unsigned long)(sensor.serial_code()));  // Serial code, () → uint16
                                                                    // PROM word 7 bits 15:4
    printk("altitude %.2f m\n", (double)(sensor.altitude()));       // Altitude from pressure, (sea_level_pa=101325) → float m
                                                                    // international barometric formula
    sensor.reset();                                                 // Reset and reload PROM, () → bool
                                                                    // 0x1E, 3 ms wait
    printk("ok %lu\n", (unsigned long)(ok));
    return 0;
}
