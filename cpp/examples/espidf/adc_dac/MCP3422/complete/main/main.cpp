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
    int32_t raw = adc.readRaw(0);                       // One-shot code, (channel 0–1) → int32_t
    // writes RDY = 1, O/C = 0, waits, polls RDY
    float v = adc.readVoltage(1);                       // One-shot voltage, (channel 0–1) → float V
    float both[2];
    adc.readAll(both);                                  // Both channels, (float out[2]) → bool
    const uint8_t scan[3] = {0, 1, 0};
    float scanned[3];
    adc.readScan(scan, 3, scanned);                     // Scan a list of channels, (channels, count, float out[]) → bool
    printf("raw %d v %.6f last ok %d\n", (int)raw, (double)v, (int)adc.lastReadOk());

    bool a = adc.setResolution(18);                     // Select 18 bit / 3.75 SPS, (bits 12|14|16|18) → bool
    // false for other values; nothing changes
    bool b = adc.setGain(8);                            // Select PGA x8, (gain 1|2|4|8) → bool
    printf("set %d %d bits %d gain %d\n", (int)a, (int)b, adc.getResolution(), adc.getGain());
    float lsb = adc.lsb();                              // LSB before gain, () → float V
    // 2·2.048/2^bits
    float fs = adc.fullScale();                         // Full-scale range, () → float V
    // 2.048/gain
    printf("lsb %.9f full scale %.3f\n", (double)lsb, (double)fs);

    adc.setResolution(12);                              // Back to 12 bit / 240 SPS, (bits) → bool
    adc.setGain(1);                                     // Back to x1, (gain) → bool
    adc.setChannel(0);                                  // Channel for continuous conversion, (channel 0–1) → void
    adc.startContinuous();                              // Start continuous conversion, (channel=-1) → void
    // O/C = 1; the first sample after a change is discarded
    int32_t cont = adc.readContinuousRaw();             // Next fresh code, () → int32_t
    float contV = adc.readContinuous();                 // Next fresh voltage, () → float V
    uint8_t cfg = adc.readConfig();                     // Read the config byte, () → uint8_t
    bool ready = adc.isReady();                         // Report RDY = 0, () → bool
    bool mode = adc.isContinuous();                     // Report the mode, () → bool
    printf("cont %d %.6f cfg %d ready %d continuous %d\n", (int)cont, (double)contV, (int)cfg, (int)ready, (int)mode);
    adc.stopContinuous();                               // Back to one-shot / standby, () → void

    adc.generalCallConvert();                           // Start a conversion on every device, () → void
    // sends 0x00 0x08
    vTaskDelay(pdMS_TO_TICKS(100));
    int32_t pending = adc.readPendingRaw();             // Result of that conversion, () → int32_t
    printf("pending %d\n", (int)pending);
    adc.generalCallReset();                             // Reset to power-on defaults, () → void
    // sends 0x00 0x06; the driver state follows
}
