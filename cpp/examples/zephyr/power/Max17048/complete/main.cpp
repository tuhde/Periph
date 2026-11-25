#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Max17048.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MAX17048_ADDR 0x36

static volatile uint16_t g_alert = 0;
static void on_alert(uint16_t status) { g_alert = status; }

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MAX17048_ADDR);
    Max17048Full gauge(connection, 1);                      // Create MAX17048 driver, (connection, cells)

    printk("%.3f V\n", (double)(gauge.voltage()));                                                  // Cell voltage, () → float V
    printk("%.2f %%\n", (double)(gauge.percent()));                                                 // State of charge, () → float %
    printk("raw VCELL 0x%04X\n", (unsigned)(gauge.raw_voltage()));                                  // VCELL register, () → uint16_t
    printk("raw SOC 0x%04X\n", (unsigned)(gauge.raw_soc()));                                        // SOC register, () → uint16_t
    printk("%.2f %%/h\n", (double)(gauge.charge_rate()));                                           // Charge/discharge rate, () → float %/h
    printk("version 0x%04X\n", (unsigned)(gauge.version()));                                        // VERSION register, () → uint16_t
    printk("id 0x%02X\n", (unsigned)(gauge.chip_id()));                                             // Factory ID, () → uint8_t
    gauge.set_rcomp(0x97);                                  // RCOMP, (value)
    printk("rcomp 0x%02X\n", (unsigned)(gauge.get_rcomp()));                                        // () → uint8_t
    gauge.compensate_temperature(25.0f);                    // RCOMP from the temperature, (celsius)
    gauge.set_alert_soc(10);                                // Empty alert threshold, (percent) 1-32
    printk("alert soc %d %%\n", (int)(gauge.get_alert_soc()));                                      // () → uint8_t %
    gauge.set_soc_change_alert(true);                       // Alert on a 1 % SOC change, (enabled)
    gauge.set_voltage_alert(3.3f, 4.25f);                   // Voltage window, (min_v, max_v) → bool; 0 / 5.1 V disable a limit
    float min_v = 0, max_v = 0;
    gauge.get_voltage_alert(&min_v, &max_v);                // () → min_v, max_v in V
    printk("window %.2f .. %.2f V\n", (double)(min_v), (double)(max_v));
    gauge.set_reset_voltage(3.0f);                          // Reset voltage, (volts)
    printk("reset %.2f V\n", (double)(gauge.get_reset_voltage()));                                  // () → float V
    gauge.enable_voltage_reset_alert(true);                 // Voltage reset asserts ALRT, (enabled)
    gauge.set_comparator_disabled(false);                   // Hibernate comparator off, (disabled)
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Hibernate thresholds, (hib_thr_pct_per_h, act_thr_v)
    float hib = 0, act = 0;
    gauge.get_hibernate_thresholds(&hib, &act);             // () → hib_thr_pct_per_h, act_thr_v
    printk("hibernate %.2f %%/h, %.3f V\n", (double)(hib), (double)(act));
    gauge.disable_hibernate();                              // Never hibernate, ()
    gauge.force_hibernate();                                // Always hibernate, ()
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Restore the defaults
    printk("hibernating %d\n", (int)gauge.is_hibernating());                                        // Hibernate status, () → bool
    printk("status 0x%04X\n", (unsigned)(gauge.read_status()));                                     // STATUS register, () → uint16_t
    gauge.clear_status(Max17048Full::SOURCE_RESET);         // Clear flags and ALRT, (mask)
    printk("config 0x%04X\n", (unsigned)(gauge.read_config()));                                     // CONFIG register, () → uint16_t
    gauge.write_config(gauge.read_config());                // Write CONFIG, (value)
    printk("alert sources 0x%04X\n", (unsigned)(gauge.pollInterrupt()));                            // Alert sources, () → uint16_t
    gauge.enableInterrupt(Max17048Full::SOURCE_SOC_CHANGE); // Enable a source, (source)
    gauge.disableInterrupt(Max17048Full::SOURCE_SOC_CHANGE);// Disable a source, (source)
    gauge.onInterrupt(on_alert);                            // Subscribe to ALRT, (callback, intPin = nullptr)
    gauge.offInterrupt();                                   // Unsubscribe, ()
    gauge.enable_sleep(true);                               // Allow sleep mode, (enabled)
    gauge.sleep();                                          // Sleep, ()
    gauge.wake();                                           // Wake up, ()
    gauge.enable_sleep(false);
    gauge.quick_start();                                    // Restart the SOC estimate, (); only if the first SOC is clearly wrong
    gauge.reset();                                          // Power-on reset, (); waits 1 s
    return 0;
}
