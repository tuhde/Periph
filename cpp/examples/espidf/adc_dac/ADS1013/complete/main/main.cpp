#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1013.h"

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
                                                        // no I2C traffic; fixed ±2.048 V, 1600 SPS

    int16_t raw = adc.readRaw();                        // Read raw code, () → int16 (−2048…2047)
                                                        // single-shot AIN0 − AIN1, polls OS until idle
    float v = adc.readVoltage();                        // Read voltage, () → V
                                                        // raw × 0.001
    printf("raw %d voltage %.4f V\n", (int)raw, (double)v);
    printf("full scale %.3f V\n", (double)adc.fullScale());  // Read full-scale range, () → V
                                                        // always 2.048
    printf("lsb %.6f V\n", (double)adc.lsb());          // Read code size, () → V
                                                        // always 0.001

    adc.setDataRate(ADS1013Full::DR_250);               // Set data rate, (dr 0–6) → bool
                                                        // DR_250 = 250 SPS
    printf("dr code %u\n", (unsigned)adc.getDataRate());  // Read data-rate code, () → uint8
                                                        // cached DR[2:0]
    printf("sps %u\n", (unsigned)adc.dataRateSps());    // Read data rate, () → SPS
                                                        // table lookup of the DR code

    adc.startContinuous();                              // Start continuous conversion, () → void
                                                        // MODE = 0 at the current data rate
    vTaskDelay(pdMS_TO_TICKS(20));
    printf("continuous raw %d\n", (int)adc.readContinuousRaw());  // Read latest code, () → int16
                                                        // reads CONVERSION without touching Config
    printf("continuous %.4f V\n", (double)adc.readContinuous());  // Read latest voltage, () → V
                                                        // raw × lsb()
    adc.stopContinuous();                               // Stop continuous conversion, () → void
                                                        // MODE = 1, back to single-shot
    printf("converting %d\n", adc.isConverting());      // Check conversion in progress, () → bool
                                                        // CONFIG.OS = 0 (single-shot only)
    printf("config 0x%04X\n", (unsigned)adc.readConfig());  // Read CONFIG register, () → uint16
                                                        // raw 16-bit register
    adc.reset();                                        // Reset to power-up defaults, () → void
                                                        // rewrites CONFIG = 0x8583
}
