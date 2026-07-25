// Auto-generated ESP-IDF example for INA219 (Complete).
// Mirrors the Arduino INA219_Complete example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "INA219.h"

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
    INA219Full chip(transport);  // Create INA219 driver
    float v, i, p;
    bool cr, ov;
    chip.voltage();                                   // Read bus voltage, () → float V
    chip.shunt_voltage();                             // Read shunt voltage, () → float V
    chip.current();                                   // Read current, () → float A
    chip.power();                                     // Read power, () → float W
    chip.configure(INA219Full::BRNG_32V, INA219Full::PGA_8, INA219Full::ADC_12BIT, INA219Full::ADC_12BIT, INA219Full::MODE_SHUNT_BUS_CONT);  // Configure, (brng 0/1, pga 0-3, badc 0x00-0x0F, sadc 0x00-0x0F, mode 0-7) → void
    // writes the Configuration Register
    chip.conversion_ready();                          // Check conversion ready, () → bool
    chip.overflow();                                  // Check math overflow, () → bool
    chip.reset();                                     // Reset all registers, () → void
    // re-writes the Calibration Register after reset
    chip.shutdown();                                  // Enter power-down, () → void
    chip.wake();                                      // Restore operating mode, () → void
    chip.trigger();                                   // Trigger a single-shot conversion, () → void
    // only effective in triggered modes (1, 2, 3)
    vTaskDelay(pdMS_TO_TICKS(1000));
}
