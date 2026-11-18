#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Max17048.h"

static volatile uint16_t g_alert = 0;
static void on_alert(uint16_t status) { g_alert = status; }

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x36;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Max17048Full gauge(connection, 1);                      // Create MAX17048 driver, (connection, cells)

    printf("%.3f V\n", (double)(gauge.voltage()));                                                  // Cell voltage, () → float V
    printf("%.2f %%\n", (double)(gauge.percent()));                                                 // State of charge, () → float %
    printf("raw VCELL 0x%04X\n", (unsigned)(gauge.raw_voltage()));                                  // VCELL register, () → uint16_t
    printf("raw SOC 0x%04X\n", (unsigned)(gauge.raw_soc()));                                        // SOC register, () → uint16_t
    printf("%.2f %%/h\n", (double)(gauge.charge_rate()));                                           // Charge/discharge rate, () → float %/h
    printf("version 0x%04X\n", (unsigned)(gauge.version()));                                        // VERSION register, () → uint16_t
    printf("id 0x%02X\n", (unsigned)(gauge.chip_id()));                                             // Factory ID, () → uint8_t
    gauge.set_rcomp(0x97);                                  // RCOMP, (value)
    printf("rcomp 0x%02X\n", (unsigned)(gauge.get_rcomp()));                                        // () → uint8_t
    gauge.compensate_temperature(25.0f);                    // RCOMP from the temperature, (celsius)
    gauge.set_alert_soc(10);                                // Empty alert threshold, (percent) 1-32
    printf("alert soc %d %%\n", (int)(gauge.get_alert_soc()));                                      // () → uint8_t %
    gauge.set_soc_change_alert(true);                       // Alert on a 1 % SOC change, (enabled)
    gauge.set_voltage_alert(3.3f, 4.25f);                   // Voltage window, (min_v, max_v) → bool; 0 / 5.1 V disable a limit
    float min_v = 0, max_v = 0;
    gauge.get_voltage_alert(&min_v, &max_v);                // () → min_v, max_v in V
    printf("window %.2f .. %.2f V\n", (double)(min_v), (double)(max_v));
    gauge.set_reset_voltage(3.0f);                          // Reset voltage, (volts)
    printf("reset %.2f V\n", (double)(gauge.get_reset_voltage()));                                  // () → float V
    gauge.enable_voltage_reset_alert(true);                 // Voltage reset asserts ALRT, (enabled)
    gauge.set_comparator_disabled(false);                   // Hibernate comparator off, (disabled)
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Hibernate thresholds, (hib_thr_pct_per_h, act_thr_v)
    float hib = 0, act = 0;
    gauge.get_hibernate_thresholds(&hib, &act);             // () → hib_thr_pct_per_h, act_thr_v
    printf("hibernate %.2f %%/h, %.3f V\n", (double)(hib), (double)(act));
    gauge.disable_hibernate();                              // Never hibernate, ()
    gauge.force_hibernate();                                // Always hibernate, ()
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Restore the defaults
    printf("hibernating %d\n", (int)gauge.is_hibernating());                                        // Hibernate status, () → bool
    printf("status 0x%04X\n", (unsigned)(gauge.read_status()));                                     // STATUS register, () → uint16_t
    gauge.clear_status(Max17048Full::SOURCE_RESET);         // Clear flags and ALRT, (mask)
    printf("config 0x%04X\n", (unsigned)(gauge.read_config()));                                     // CONFIG register, () → uint16_t
    gauge.write_config(gauge.read_config());                // Write CONFIG, (value)
    printf("alert sources 0x%04X\n", (unsigned)(gauge.pollInterrupt()));                            // Alert sources, () → uint16_t
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
}
