#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include <math.h>
#include "I2CConnectionESPIDF.h"
#include "AM2315.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x5C

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
    dev_cfg.device_address = AHT_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AM2315Minimal sensor(connection);                                        // Create AM2315 driver, (connection) → void
    float t = 0, rh = 0;

    while (true) {
        bool ok = sensor.read(t, rh);                                            // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                                 // previous measurement; reads are kept >= 2 s apart
        printf("%.1f C, %.1f %%RH (ok=%u)\n", t, rh, (unsigned)(ok));
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}
