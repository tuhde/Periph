#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Shtc3.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define SHTC3_ADDR 0x70

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, SHTC3_ADDR);
    SHTC3Full sensor(connection);                                   // Create SHTC3 driver, (connection) → void
    sensor.init();                                                  // Soft reset and ID check, () → bool
                                                                    // opcode 0x01; the chip powers down by itself after each one-time measurement
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0, id = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
                                                                    // normal mode, both CRCs verified, one retry on a bad CRC
    printk("temperature %.2f C humidity %.1f %%RH\n", (double)(t), (double)(rh));
    printk("humidity %.1f %%RH\n", (double)(sensor.read_humidity())); // Read humidity only, () → float %RH
                                                                    // NAN on failure
    printk("temperature %.2f C\n", (double)(sensor.read_temperature())); // Read temperature only, () → float °C
                                                                    // NAN on failure
    ok = sensor.read_raw(t_raw, rh_raw);                            // Read raw codes, (t_raw&, rh_raw&) → bool
                                                                    // 16-bit codes
    printk("raw T %lu raw RH %lu\n", (unsigned long)(t_raw), (unsigned long)(rh_raw));
    ok = sensor.read_id(id);                                        // Read the ID word, (id&) → bool
                                                                    // (id & 0x083F) == 0x0807 for the SHTC3
    printk("id %02X\n", (unsigned)(id));
    sensor.set_low_power(true);                                     // Select low-power mode, (on) → void
                                                                    // 0.7 ms measurement, slightly noisier
    ok = sensor.read(t, rh);
    printk("low power %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_low_power(false);
    sensor.set_clock_stretching(true);                              // Select clock stretching, (on) → void
                                                                    // needs a controller that tolerates SCL held low
    ok = sensor.read(t, rh);
    sensor.set_clock_stretching(false);
    sensor.set_sleep_between(false);                                // Sleep after every read?, (on) → void
                                                                    // off keeps the sensor idle (higher supply current)
    ok = sensor.read(t, rh);
    sensor.set_sleep_between(true);
    sensor.wake();                                                  // Wake-up command, () → void
    sensor.sleep();                                                 // Sleep command, () → void
    printk("dew point %.2f C\n", (double)(sensor.dew_point()));     // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    sensor.soft_reset();                                            // Soft reset, () → void
                                                                    // 0x805D, leaves the sensor idle
    printk("ok %lu\n", (unsigned long)(ok));
    return 0;
}
