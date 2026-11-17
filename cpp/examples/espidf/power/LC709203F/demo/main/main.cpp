#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LC709203F.h"

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
    dev_cfg.device_address = 0x0B;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LC709203FFull gauge(connection, 1000, 1);                    // Create LC709203F driver, 1000 mAh 3.7 V LiPo

    printf("IC version 0x%04X, profile id 0x%04X, RSOC %d %%\n",
           gauge.ic_version(), gauge.profile_id(), gauge.rsoc());
    gauge.set_alarm_rsoc(20);                                   // Alarm at 20 % RSOC, (percent)
    gauge.set_alarm_voltage(3.5f);                              // Alarm at 3.5 V, (volts)

    // Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
    for (int n = 0; n < 12; n++) {
        gauge.set_temperature(25.0f);
        printf("%.3f V  %.1f %%  %.1f C  alarm=%d\n", (double)gauge.voltage(), (double)gauge.percent(),
               (double)gauge.temperature(), (int)gauge.pollInterrupt());
        vTaskDelay(pdMS_TO_TICKS(5000));
    }

    gauge.sleep();
    gauge.wake();
    printf("sleeping: %d\n", (int)gauge.is_sleeping());
}
