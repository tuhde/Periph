#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "AM2315.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AM2315_ADDR 0x5C

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AM2315_ADDR);
    AM2315Full sensor(connection);                                           // Create AM2315 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0, model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0, regs[7] = {0};
    uint32_t device_id = 0;

    sensor.read(t, rh);                                                      // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                             // previous measurement; reads are kept >= 2 s apart, CRC-16 checked
    printk("%.1f C, %.1f %%RH\n", (double)(t), (double)(rh));
    printk("humidity %.1f %%RH\n", (double)(sensor.read_humidity()));
    // sensor.read_humidity() → float %RH (NAN on failure)
    printk("temperature %.1f C\n", (double)(sensor.read_temperature()));
    // sensor.read_temperature() → float °C (NAN on failure)
    sensor.read_raw(rh_raw, t_raw);                                          // Read raw words, (humidity_raw, temperature_raw) → bool
                                                                             // humidity x10; temperature x10 as sign + magnitude
    printk("raw RH %u raw T %u\n", (unsigned)(rh_raw), (unsigned)(t_raw));
    sensor.read_fresh(t, rh);                                                // Real-time value, (t, rh) → bool
                                                                             // two reads >= 2 s apart; returns the second
    printk("fresh %.1f C, %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.read_registers(0x08, 7, regs);                                    // Read registers, (start, count, out) → bool
                                                                             // function 0x03, 1-10 registers, CRC verified
    printk("register 0x08 = 0x%02X\n", (unsigned)(regs[0]));
    sensor.read_device_info(model, version, device_id);                      // Read device information, (model, version, device_id) → bool
    printk("model %u version %u device id 0x%08lX\n", (unsigned)(model), (unsigned)(version), (unsigned long)(device_id));
    sensor.write_user_registers(0x1234, 0xABCD);                             // Write user registers, (user1, user2) → bool
                                                                             // registers 0x10-0x13
    sensor.read_user_registers(user1, user2);                                // Read user registers, (user1, user2) → bool
    printk("user registers 0x%02X 0x%02X\n", (unsigned)(user1), (unsigned)(user2));
    sensor.write_registers(0x10, regs, 2);                                   // Write registers, (start, data, len) → bool
                                                                             // only the user registers 0x10-0x13 are allowed
    sensor.wake();                                                           // Wake the sensor, () → void
                                                                             // dummy write, the NACK is ignored
    printk("last error %u\n", (unsigned)(sensor.last_error()));
    // sensor.last_error() → uint8_t, 0x80-0x84 after a sensor error response, else 0
    printk("done\n");
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
