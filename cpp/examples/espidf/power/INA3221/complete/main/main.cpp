// Auto-generated ESP-IDF example for INA3221 (Complete).
// Mirrors the Arduino INA3221_Complete example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "INA3221.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x40,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CTransportESPIDF transport(dev);
    INA3221Full chip(transport);  // Create INA3221 driver
    float v, sv, i, p;
    bool pvf, cr;
    uint16_t flags, mfr, die;
    uint8_t chans[2] = { 1, 2 };
    chip.manufacturer_id();                           // Read manufacturer ID, () → uint16_t
    chip.die_id();                                    // Read die ID, () → uint16_t
    chip.voltage(1);                                  // Read bus voltage channel 1, (channel 1-3) → float V
    chip.shunt_voltage(1);                            // Read shunt voltage channel 1, (channel 1-3) → float V
    chip.current(1);                                  // Read current channel 1, (channel 1-3) → float A
    chip.power(1);                                    // Read power channel 1, (channel 1-3) → float W
    chip.configure(3, 4, 4, 7);                       // Configure, (avg 0-7, vbus_ct 0-7, vsh_ct 0-7, mode 0-7) → void
    chip.enable_channel(2, true);                     // Enable channel 2, (channel, enabled) → void
    chip.channel_enabled(2);                          // Read channel-enabled, (channel) → bool
    chip.set_critical_alert(1, 5.5f);                 // Set critical alert, (channel, limit_v, latch=false) → void
    chip.set_warning_alert(1, 5.0f);                  // Set warning alert, (channel, limit_v, latch=false) → void
    chip.alert_flags();                               // Read Mask/Enable, () → uint16_t
    chip.set_summation_channels(chans, 2, 0.1f);      // Set summation channels, (channels, n, limit_v) → void
    chip.summation_value();                           // Read shunt-voltage sum, () → float V
    chip.set_power_valid_limits(5.5f, 4.5f);          // Set power-valid limits, (upper_v, lower_v) → void
    chip.power_valid();                               // Read power-valid, () → bool
    chip.shutdown();                                  // Enter power-down, () → void
    chip.wake();                                      // Restore operating mode, () → void
    chip.reset();                                     // Reset all registers, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
