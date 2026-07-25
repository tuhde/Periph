#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "INA3221.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define INA3221_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, INA3221_ADDR);
    INA3221Full ina(transport);                           // Create INA3221 driver, (transport, r_shunt=0.1 Ω)

    printk("manufacturer_id: 0x%04X\n", ina.manufacturer_id());  // Read Manufacturer ID, () → int 0x5449
    printk("die_id: 0x%04X\n", ina.die_id());            // Read Die ID, () → int 0x3220

    for (uint8_t ch = 1; ch <= 3; ch++) {
        printk("ch%d voltage: %.3fV\n", ch, (double)ina.voltage(ch));       // Read bus voltage, (channel) → float V
        printk("ch%d shunt: %.6fV\n", ch, (double)ina.shunt_voltage(ch));   // Read shunt voltage, (channel) → float V
        printk("ch%d current: %.4fA\n", ch, (double)ina.current(ch));        // Read load current, (channel) → float A
        printk("ch%d power: %.4fW\n", ch, (double)ina.power(ch));            // Read power, (channel) → float W
    }

    printk("conversion_ready: %d\n", ina.conversion_ready() ? 1 : 0);  // Check conversion done, () → bool

    ina.configure(4, 4, 4, 7);                         // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → None

    ina.enable_channel(1, true);                        // Enable channel, (channel, enabled) → None
    bool ena = ina.channel_enabled(1);                  // Read channel enabled, (channel) → bool

    ina.set_critical_alert(1, 0.1f);                   // Set critical alert, (channel, limit_v, latch=False) → None
    ina.set_warning_alert(2, 0.05f);                    // Set warning alert, (channel, limit_v, latch=False) → None

    uint16_t flags = ina.alert_flags();                 // Read alert flags, () → int

    uint8_t channels[] = {1, 2};
    ina.set_summation_channels(channels, 2, 0.2f);      // Set summation channels, (channels, n, limit_v) → None
    float sv_sum = ina.summation_value();              // Read summation value, () → float V

    ina.set_power_valid_limits(5.5f, 4.5f);            // Set PV limits, (upper_v, lower_v) → None
    bool pv = ina.power_valid();                        // Read power valid, () → bool

    ina.shutdown();                                     // Put chip into power-down mode, () → None
    k_sleep(K_MSEC(1));
    ina.wake();                                         // Restore operating mode, () → None

    ina.reset();                                        // Reset all registers, () → None

    printk("===DONE===\n");
    return 0;
}