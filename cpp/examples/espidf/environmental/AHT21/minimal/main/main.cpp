#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "AHT21.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x38

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(I2C_SDA),
        .scl_io_num = static_cast<gpio_num_t>(I2C_SCL),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = AHT_ADDR,
        .scl_speed_hz    = 100000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CTransportESPIDF transport(dev);
    AHT21Minimal aht(transport);                                       // Create AHT21 driver, (transport, addr=0x38) → void

    while (1) {
        float t, h;
        aht.read(t, h);                                                // Trigger measurement, (temperature_c, humidity_pct) → void
        printf("%.2f C  %.2f %%RH\n", (double)t, (double)h);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
