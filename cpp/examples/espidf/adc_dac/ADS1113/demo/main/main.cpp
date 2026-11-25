#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1113.h"

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
    dev_cfg.device_address = ADS1113Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS1113Full adc(connection);                            // Create ADS1113 Full driver, (connection)

    // --- Configure for low-noise slow sampling ---
    // 8 SPS gives the longest integration time per conversion, which
    // suppresses mains and switching noise on a slowly varying voltage.
    adc.setDataRate(ADS1113Full::DR_8);                     // Set data rate, (dr 0–7) → void

    // --- Log single-shot readings ---
    // Each readVoltage() wakes the chip, converts and powers down again.
    for (int i = 0; i < 5; i++) {
        float v = adc.readVoltage();                        // Read voltage, () → float V
        printf("%.5f V\n", (double)v);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // --- Average continuous readings ---
    // Back-to-back conversions averaged over 1.25 s reduce random noise.
    adc.startContinuous();                                  // Start continuous, () → void
    vTaskDelay(pdMS_TO_TICKS(200));
    float acc = 0.0f;
    for (int i = 0; i < 10; i++) {
        acc += adc.readContinuous();                        // Read continuous, () → float V
        vTaskDelay(pdMS_TO_TICKS(125));
    }
    adc.stopContinuous();                                   // Stop continuous, () → void
    printf("average %.5f V\n", (double)(acc / 10.0f));
}
