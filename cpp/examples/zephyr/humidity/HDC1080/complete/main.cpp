#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "HDC1080.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define HDC1080_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HDC1080_ADDR);
    HDC1080Full sensor(connection);                                     // Create HDC1080 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;

    uint64_t serial = 0; uint16_t cfg = 0;
    sensor.init();                                                      // Write default configuration, () → void
                                                                        // 0x1000: 14-bit T and RH, sequence mode, heater off
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    printk("serial id 0x%02X%08X\n", (unsigned)((serial) >> 32), (unsigned)((serial) & 0xFFFFFFFF));
    cfg = sensor.read_config();                                         // Read the configuration register, () → uint16_t
    printk("config 0x%04X\n", (unsigned)(cfg));
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // sequence mode, polls the NACKed read header (timeout 50 ms)
    printk("%.2f C, %.1f %%RH\n", (double)(t), (double)(rh));
    printk("temperature %.2f C\n", (double)(sensor.read_temperature()));
    // sensor.read_temperature() → float °C (NAN on failure), single mode
    printk("humidity %.1f %%RH\n", (double)(sensor.read_humidity()));
    // sensor.read_humidity() → float %RH (NAN on failure), single mode
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (temperature_raw, humidity_raw) → bool
                                                                        // 16-bit words, bits 1:0 zero
    printk("raw T %u raw RH %u\n", (unsigned)(raw_t), (unsigned)(raw_rh));

    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
                                                                        // T 14/11 bit, RH 14/11/8 bit; false for other values
    printk("conversion time %.2f ms\n", (double)(sensor.conversion_time() * 1000.0f));
    // sensor.conversion_time() → float s, typical T + RH time
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // runs only during measurements; readings drift while on
    printk("heater on %u\n", (unsigned)(sensor.is_heater_on()));
    // sensor.is_heater_on() → bool
    sensor.set_heater(false);                                           // Heater off
    printk("battery low %u\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
    sensor.software_reset();                                            // Software reset, () → void
                                                                        // sets RST, waits 15 ms, rewrites the stored configuration

    printk("manufacturer 0x%04X\n", (unsigned)sensor.manufacturer_id());     // Manufacturer ID (0x5449), () → uint16_t
    printk("device 0x%04X\n", (unsigned)sensor.device_id());                 // Device ID (0x1050), () → uint16_t
    printk("done\n");
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
