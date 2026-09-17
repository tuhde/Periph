#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADE7953.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .intr_priority = 0,
        .trans_queue_depth = 0,
        .flags = { .enable_internal_pullup = true, .allow_pd = false },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x38,
        .scl_speed_hz    = 400000,
        .scl_wait_us     = 0,
        .flags           = {},
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADE7953Minimal ade(connection, 251.0f, 30.0f);                       // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')
    while (1) {
        float v = ade.voltage();                                         // Read bus voltage, () → V
        float a = ade.current();                                         // Read load current, () → A
        float p = ade.activePower();                                     // Read active power, () → W
        float e = ade.activeEnergy();                                    // Read active energy, () → Wh
        printf("V=%.2f A=%.3f P=%.2f E=%.4f\n", v, a, p, e);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}