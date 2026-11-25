#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1013.h"

// Battery-voltage logger: the cell is read through a 2:1 resistor divider
// (AIN0, AIN1 to GND).

static const float DIVIDER_RATIO = 2.0f;
static const int LOG_SAMPLES = 10;

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
    dev_cfg.device_address = ADS1013Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS1013Full adc(connection);                        // Create ADS1013 Full driver, (connection)

    // --- Slow, low-noise single-shot logging ---
    // 128 SPS gives the lowest noise; one conversion per second keeps the chip
    // in power-down in between.
    adc.setDataRate(ADS1013Full::DR_128);               // Set data rate, (dr 0–6) → bool
    float low = 0.0f, high = 0.0f;
    for (int i = 0; i < LOG_SAMPLES; i++) {
        int16_t raw = adc.readRaw();                    // Read raw code, () → int16 (−2048…2047)
        float v = adc.readVoltage();                    // Read voltage, () → V
        float battery = v * DIVIDER_RATIO;
        if (i == 0 || battery < low) low = battery;
        if (i == 0 || battery > high) high = battery;
        printf("raw %d divider %.3f V battery %.3f V (min %.3f, max %.3f)\n",
          (int)raw, (double)v, (double)battery, (double)low, (double)high);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // --- Fast continuous averaging ---
    // At 3300 SPS in continuous mode, 100 samples take about 30 ms.
    adc.setDataRate(ADS1013Full::DR_3300);              // Set data rate, (dr 0–6) → bool
    adc.startContinuous();                              // Start continuous conversion, () → void
    vTaskDelay(pdMS_TO_TICKS(10));
    float total = 0.0f;
    for (int i = 0; i < 100; i++) {
        total += adc.readContinuous();                  // Read latest voltage, () → V
        vTaskDelay(pdMS_TO_TICKS(1));
    }
    adc.stopContinuous();                               // Stop continuous conversion, () → void
    printf("average battery %.3f V\n", (double)(total / 100.0f * DIVIDER_RATIO));
}
