#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP3422.h"

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
    dev_cfg.device_address = MCP3422Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MCP3422Full adc(connection);                        // Create MCP3422 Full driver, (connection)
    // --- CH1: thermistor divider at 16 bit / x1 ---
    for (int i = 0; i < 3; i++) {
        int32_t raw = adc.readRaw(0);                   // One-shot code, (channel 0–1) → int32_t
        float v = adc.readVoltage(0);                   // One-shot voltage, (channel 0–1) → float V
        printf("CH1 raw %d = %.5f V\n", (int)raw, (double)v);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // --- CH2: bridge sensor at 18 bit / x8 ---
    adc.setResolution(18);                              // Select 18 bit / 3.75 SPS, (bits) → bool
    adc.setGain(8);                                     // Select PGA x8, (gain) → bool
    const uint8_t ch2[1] = {1};
    float bridge[1];
    adc.readScan(ch2, 1, bridge);                       // Scan channels, (channels, count, float out[]) → bool
    printf("CH2 %.1f uV, LSB %.3f uV\n", (double)bridge[0] * 1e6f, (double)adc.lsb() / adc.getGain() * 1e6f);

    // --- Continuous conversion on CH1 at 12 bit / 240 SPS ---
    adc.setResolution(12);                              // Back to 12 bit / 240 SPS, (bits) → bool
    adc.setGain(1);                                     // Back to x1, (gain) → bool
    adc.startContinuous(0);                             // Start continuous conversion, (channel=-1) → void
    float lo = 1e9f, hi = -1e9f, sum = 0;
    for (int i = 0; i < 20; i++) {
        float v = adc.readContinuous();                 // Next fresh voltage, () → float V
        if (v < lo) lo = v;
        if (v > hi) hi = v;
        sum += v;
    }
    adc.stopContinuous();                               // Back to one-shot / standby, () → void
    printf("min %.5f max %.5f mean %.5f V\n", (double)lo, (double)hi, (double)sum / 20.0f);
    adc.generalCallReset();                             // Reset to power-on defaults, () → void
}
