#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Si7021.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define Si7021_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, Si7021_ADDR);
    Si7021Full sensor(connection);                                      // Create Si7021 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw = 0;
    uint8_t rh_bits = 0, t_bits = 0;

    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // RH without hold master, temperature of the same conversion
    printk("%.2f C, %.1f %%RH\n", (double)(t), (double)(rh));
    printk("humidity %.1f %%RH\n", (double)(sensor.read_humidity()));
    // sensor.read_humidity() → float %RH (NAN on failure)
    printk("temperature %.2f C\n", (double)(sensor.read_temperature()));
    // sensor.read_temperature() → float °C, new conversion (NAN on failure)
    printk("temperature of last RH measurement %.2f C\n", (double)(sensor.read_temperature_previous()));
    // sensor.read_temperature_previous() → float °C, command 0xE0, no new conversion (NAN on failure)
    sensor.read_raw_humidity(raw);                                      // Read raw RH code, (code) → bool
                                                                        // 16-bit code, status bits included
    printk("raw RH %u\n", (unsigned)raw);
    sensor.read_raw_temperature(raw);                                   // Read raw temperature code, (code) → bool
    printk("raw T %u\n", (unsigned)raw);

    sensor.set_resolution(11, 11);                                      // Set resolution, (rh_bits, temperature_bits) → bool
                                                                        // (12, 14) / (8, 12) / (10, 13) / (11, 11)
    sensor.get_resolution(rh_bits, t_bits);                             // Get resolution, (rh_bits, t_bits) → void
    printk("resolution %u / %u bit\n", (unsigned)rh_bits, (unsigned)t_bits);
    printk("conversion time %.1f ms\n", (double)(sensor.conversion_time() * 1000.0f));
    // sensor.conversion_time() → float s, typical RH + temperature time
    sensor.set_resolution(12, 14);

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 3.1 mA; readings drift while on
    printk("heater on %u\n", (unsigned)sensor.is_heater_on());
    // sensor.is_heater_on() → bool
    sensor.set_heater(false);
    printk("VDD low %u\n", (unsigned)sensor.vdd_low());
    // sensor.vdd_low() → bool, true when VDD is below about 1.9 V
    uint8_t reg = sensor.read_user_register();                          // Read USER_REG_1, () → uint8_t
                                                                        // 0x3A after reset
    sensor.write_user_register(reg);                                    // Write USER_REG_1, (value) → void
                                                                        // reserved bits are preserved
    sensor.set_clock_stretching(false);                                 // Use hold-master commands, (on) → void
                                                                        // needs bus-layer support; off polls the NACKed read header

    uint64_t sn = sensor.serial_number();                               // Electronic serial number, () → uint64_t
    printk("serial number 0x%08lX%08lX\n", (unsigned long)(sn >> 32), (unsigned long)(sn & 0xFFFFFFFFUL));
    printk("device type 0x%02X\n", (unsigned)sensor.device_type());
    // sensor.device_type() → uint8_t, 0x15 = Si7021
    printk("firmware 0x%02X\n", (unsigned)sensor.firmware_revision());
    // sensor.firmware_revision() → uint8_t, 0xFF = firmware 1.0
    printk("dew point %.2f C\n", (double)(sensor.dew_point()));
    // sensor.dew_point() → float °C, one measurement, Magnus formula (NAN on failure)
    sensor.software_reset();                                            // Software reset, () → void
                                                                        // 0xFE, waits 15 ms
    printk("done\n");
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
