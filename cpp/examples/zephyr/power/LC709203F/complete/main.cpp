#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LC709203F.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define LC709203F_ADDR 0x0B

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LC709203F_ADDR);
    LC709203FFull gauge(connection, 1000, 1);                    // Create LC709203F driver, (connection, capacityMah, profile)

    printk("%.3f\n", (double)gauge.voltage());                  // Cell voltage, () → float V
    printk("%.1f\n", (double)gauge.percent());                  // State of charge (ITE), () → float %
    printk("%.2f\n", (double)gauge.temperature());              // Cell temperature, () → float °C
    printk("%d\n", (int)gauge.rsoc());                          // RSOC register, () → uint8_t %
    printk("%d\n", (int)gauge.profile_id());                    // Number of the parameter, () → uint16_t
    printk("%d\n", (int)gauge.ic_version());                    // IC version, () → uint16_t
    printk("%d\n", (int)gauge.get_profile());                   // Battery profile, () → uint8_t
    printk("%d\n", (int)gauge.get_apa());                       // Adjustment Pack Application, () → uint8_t
    printk("%d\n", (int)gauge.get_apt());                       // Adjustment Pack Thermistor, () → uint16_t
    printk("%d\n", (int)gauge.get_b_constant());                // Thermistor B constant, () → uint16_t K
    printk("%d\n", (int)gauge.get_current_direction());         // Current direction, () → uint16_t
    printk("%d\n", (int)gauge.is_thermistor_mode());            // Temperature source, () → bool
    printk("%d\n", (int)gauge.get_alarm_rsoc());                // Low-RSOC alarm threshold, () → uint8_t %
    printk("%.3f\n", (double)gauge.get_alarm_voltage());        // Low-voltage alarm threshold, () → float V
    printk("%d\n", (int)gauge.pollInterrupt());                 // Alarm bitmask, () → uint8_t (ALARM_RSOC = 1, ALARM_VOLTAGE = 2)
    gauge.set_temperature(25.0f);                               // Host temperature (I²C mode), (celsius) clamped to -20...+60 °C
    gauge.set_rsoc(50);                                         // Force RSOC, (percent)
    gauge.init_rsoc();                                          // Initial RSOC from present voltage, ()
    gauge.before_rsoc();                                        // RSOC from max voltage since reset, ()
    gauge.use_thermistor(3380);                                 // Thermistor mode, (bConstant)
    gauge.use_i2c_temperature(25.0f);                           // Back to I²C temperature mode, (celsius)
    gauge.set_apa(0x19);                                        // Adjustment Pack Application, (value)
    gauge.set_apt(0x1E);                                        // Adjustment Pack Thermistor, (value)
    gauge.set_current_direction(LC709203FFull::DIRECTION_AUTO); // Current direction, (mode) AUTO / CHARGE / DISCHARGE
    gauge.set_profile(1);                                       // Battery profile 0/1, (profile) — re-initializes the chip (90 ms)
    gauge.set_alarm_rsoc(20);                                   // Low-RSOC alarm, (percent) 0 = off
    gauge.set_alarm_voltage(3.5f);                              // Low-voltage alarm, (volts) 0 = off
    gauge.sleep();                                              // Sleep mode, ()
    gauge.wake();                                               // Wake up, ()
    printk("%d\n", (int)gauge.is_sleeping());                   // Power mode, () → bool
    return 0;
}
