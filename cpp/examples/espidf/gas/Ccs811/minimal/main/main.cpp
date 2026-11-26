#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Ccs811.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define CCS811_ADDR 0x5A

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(I2C_SDA);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(I2C_SCL);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = CCS811_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    CCS811Minimal sensor(connection);                              // Create CCS811 driver, (connection) → void
    sensor.init();                                                 // Check HW_ID, start application, mode 1, () → bool
    while (true) {
        uint16_t eco2 = 0, tvoc = 0;
        bool ok = sensor.read(eco2, tvoc);                         // Wait for the next sample, (eco2_ppm&, tvoc_ppb&) → bool
        printf("eCO2 %u ppm TVOC %u ppb (ok=%u)\n", (unsigned)(eco2), (unsigned)(tvoc), (unsigned)(ok));
    }
}
