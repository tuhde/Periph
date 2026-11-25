#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "SHT31.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define SHT31_ADDR 0x44

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, SHT31_ADDR);
    SHT31Full sensor(connection);                                       // Create SHT31 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0, status = 0;

    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // single shot, high repeatability, CRC checked
    printk("%.2f C, %.1f %%RH\n", (double)(t), (double)(rh));
    printk("temperature %.2f C\n", (double)(sensor.read_temperature()));
    // sensor.read_temperature() → float °C (NAN on failure)
    printk("humidity %.1f %%RH\n", (double)(sensor.read_humidity()));
    // sensor.read_humidity() → float %RH (NAN on failure)
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (raw_t, raw_rh) → bool
                                                                        // 16-bit temperature and humidity ticks
    printk("raw %u %u\n", (unsigned)raw_t, (unsigned)raw_rh);

    sensor.set_repeatability(SHT31Full::REPEATABILITY_LOW);             // Select repeatability, (level) → bool
                                                                        // low / medium / high (4 / 6 / 15 ms)
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool
    sensor.set_repeatability(SHT31Full::REPEATABILITY_HIGH);            // Select repeatability, (level) → bool
    sensor.set_clock_stretching(false);                                 // Use clock-stretching commands, (on) → void
                                                                        // needs bus-layer support; off polls the NACKed read header

    sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH);         // Start periodic mode, (rate, repeatability=HIGH, art=false) → bool
                                                                        // rate 0.5 / 1 / 2 / 4 / 10 mps; art=true selects the 4 mps ART mode
    k_sleep(K_MSEC(1000));
    bool have = sensor.fetch(t, rh);                                    // Fetch the latest periodic result, (t, rh) → bool
                                                                        // false while the read header is NACKed
    printk("fetch %u: %.2f C %.1f %%RH\n", (unsigned)have, (double)(t), (double)(rh));
    sensor.stop_periodic();                                             // Stop periodic mode, () → void
                                                                        // break command 0x3093, waits 1 ms

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 5-25 mW; readings are unusable while on
    sensor.read_status(status);                                         // Read status register, (status) → bool
                                                                        // CRC verified; bit 13 = heater on
    printk("status 0x%04X\n", (unsigned)status);
    sensor.set_heater(false);                                           // Switch the heater, (on) → void
    sensor.clear_status();                                              // Clear status flags, () → void
                                                                        // command 0x3041
    printk("dew point %.2f C\n", (double)(sensor.dew_point()));
    // sensor.dew_point() → float °C, one measurement, Magnus formula (NAN on failure)
    sensor.soft_reset();                                                // Soft reset, () → void
                                                                        // 0x30A2, waits 1 ms, back to single shot
    printk("done\n");
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
