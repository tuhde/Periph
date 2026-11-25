#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Htu31d.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define HTU31D_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HTU31D_ADDR);
    HTU31DFull sensor(connection);                                  // Create HTU31D driver, (connection) → void
    sensor.init();                                                  // Soft reset, () → void
                                                                    // 0x1E, waits 15 ms; highest resolution, heater off
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0;
    uint8_t osr_rh = 0, osr_t = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
                                                                    // OSR 3 / 3 by default, both CRCs verified
    printk("temperature %.2f C humidity %.1f %%RH\n", (double)(t), (double)(rh));
    printk("humidity %.1f %%RH\n", (double)(sensor.read_humidity()));// Read humidity only, () → float %RH
                                                                    // command 0x10; NAN on failure
    printk("temperature %.2f C\n", (double)(sensor.read_temperature()));// Read temperature only, () → float °C
                                                                    // NAN on failure
    ok = sensor.read_raw(t_raw, rh_raw);                            // Read raw codes, (t_raw&, rh_raw&) → bool
                                                                    // 16-bit codes
    printk("raw T %lu raw RH %lu\n", (unsigned long)(t_raw), (unsigned long)(rh_raw));
    ok = sensor.set_resolution(1, 2);                               // Set oversampling, (osr_rh, osr_t) → bool
                                                                    // 0–3 each; higher OSR = finer resolution, longer conversion
    sensor.get_resolution(osr_rh, osr_t);                           // Get oversampling, (osr_rh&, osr_t&) → void
    printk("osr rh %lu osr t %lu\n", (unsigned long)(osr_rh), (unsigned long)(osr_t));
    printk("conversion %.1f ms\n", (double)(sensor.conversion_time() * 1000.0f));// Typical conversion time, () → float s
    sensor.set_resolution(3, 3);
    sensor.start_conversion();                                      // Start a conversion, () → void
                                                                    // non-blocking; wait conversion_time() before reading
    k_sleep(K_MSEC(30));
    ok = sensor.read_result(t, rh);                                 // Read last conversion result, (temperature_c&, humidity_pct&) → bool
    printk("result %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_heater(true);                                        // Switch the heater on, (on) → void
                                                                    // 0x04; readings drift while the heater runs
    printk("heater on %lu\n", (unsigned long)(sensor.is_heater_on()));// Heater state, () → bool
    sensor.set_heater(false);                                       // Heater off
    printk("diagnostic 0x%02X\n", (unsigned)(sensor.diagnostic())); // Read the diagnostic byte, () → uint8_t
                                                                    // test against HTU31DFull::DIAG_* masks
    uint32_t serial = 0;
    ok = sensor.serial_number(serial);                              // Read the serial number, (serial&) → bool
                                                                    // 24-bit, CRC verified
    printk("serial 0x%02X\n", (unsigned)(serial));
    printk("dew point %.2f C\n", (double)(sensor.dew_point()));     // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula; NAN on failure
    sensor.software_reset();                                        // Soft reset, () → void
                                                                    // 0x1E, waits 15 ms; heater off, OSR 3 / 3
    printk("ok %lu\n", (unsigned long)(ok));
    return 0;
}
