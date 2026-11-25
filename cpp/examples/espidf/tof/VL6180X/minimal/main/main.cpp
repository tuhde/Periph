#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "VL6180X.h"

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
    dev_cfg.device_address = VL6180XMinimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev, nullptr, nullptr, 2);
    VL6180XMinimal sensor(connection);                      // Create VL6180X driver, (connection)
    sensor.init();                                          // Initialize, () → bool

    while (1) {
        uint8_t mm = 0;
        if (sensor.read_range(mm)) {                        // Single-shot range, (uint8_t& mm) → bool
            printf("%u mm\n", (unsigned)mm);
        } else {
            printf("range error %u\n", (unsigned)sensor.last_error()); // Last range error code, () → uint8_t
        }
        vTaskDelay(pdMS_TO_TICKS(200));
    }
}
