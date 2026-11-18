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

    printf("version 0x%04X, id 0x%02X\n", (unsigned)(gauge.version()), (unsigned)(gauge.chip_id()));
    gauge.clear_status(Max17048Full::SOURCE_RESET);         // Clear the reset indicator, (mask)

    // Battery fuel gauge: one line per second for 30 s
    for (int n = 0; n < 30; n++) {
        printf("%.3f V  %.1f %%  %+.1f %%/h\n", (double)(gauge.voltage()), (double)(gauge.percent()), (double)(gauge.charge_rate()));
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    gauge.set_alert_soc(10);                                // Low-SOC alert at 10 %, (percent)
    gauge.set_voltage_alert(3.3f, 4.25f);                   // Voltage window 3.3-4.25 V, (min_v, max_v)
    gauge.onInterrupt(on_alert);                            // Subscribe to ALRT, (callback, intPin = nullptr)
    vTaskDelay(pdMS_TO_TICKS(5000));
    gauge.offInterrupt();
    printf("poll_interrupt 0x%04X (callback saw 0x%04X)\n", (unsigned)(gauge.pollInterrupt()), (unsigned)g_alert);  // Without an ALRT pin, () → uint16_t

    gauge.compensate_temperature(5.0f);                     // RCOMP for the temperature, (celsius)
    printf("RCOMP at 5 C: 0x%02X\n", (unsigned)(gauge.get_rcomp()));
    gauge.compensate_temperature(35.0f);
    printf("RCOMP at 35 C: 0x%02X\n", (unsigned)(gauge.get_rcomp()));
    gauge.compensate_temperature(20.0f);

    gauge.disable_hibernate();                              // Never hibernate, ()
    printf("hibernating %d\n", (int)gauge.is_hibernating());
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Back to the defaults

    gauge.enable_sleep(true);                               // Sleep round trip, (enabled)
    gauge.sleep();
    vTaskDelay(pdMS_TO_TICKS(1000));
    gauge.wake();
    gauge.enable_sleep(false);
    printf("after wake: %.3f V\n", (double)(gauge.voltage()));
}
