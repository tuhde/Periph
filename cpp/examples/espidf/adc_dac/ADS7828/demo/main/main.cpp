// Auto-generated ESP-IDF example for ADS7828 (Demo).
// Mirrors the Arduino ADS7828_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS7828.h"

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
    dev_cfg.device_address = 0x48;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS7828Full adc(connection);                            // Create ADS7828 driver, (connection, vref=0 → internal 2.5 V)

    while (1) {
        const float DIVIDER = 2.0f;   // inputs sit behind 2:1 dividers (rails up to 5 V)
        const uint8_t chans[8] = {0, 1, 2, 3, 4, 5, 6, 7};

        // --- Eight-channel supply monitor ---
        // Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
        // voltages (after the divider ratio) and keep a running minimum/maximum per channel.
        float vmin[8], vmax[8];
        for (int ch = 0; ch < 8; ch++) { vmin[ch] = 99.0f; vmax[ch] = 0.0f; }
        for (int n = 0; n < 5; n++) {
            uint16_t raws[8];
            adc.readScan(chans, 8, raws);                           // Scan channels, (channels, count, out) → void
            for (int ch = 0; ch < 8; ch++) {
                float volts = raws[ch] * adc.lsb() * DIVIDER;
                if (volts < vmin[ch]) vmin[ch] = volts;
                if (volts > vmax[ch]) vmax[ch] = volts;
                printf("ch%d=%u (%.3f V)  ", ch, raws[ch], (double)volts);
            }
            printf("\n");
            vTaskDelay(pdMS_TO_TICKS(1000));
        }
        for (int ch = 0; ch < 8; ch++) printf("ch%d min=%.3f max=%.3f\n", ch, (double)vmin[ch], (double)vmax[ch]);

        // --- Read a bridge sensor on CH0/CH1 ---
        // The signed differential read does two conversions (normal and inverted
        // polarity) so the sign of the bridge output is preserved.
        float mv = adc.readSignedDifferential(0) * 1000.0f;         // Read pair 0 signed, (pair=0–3) → float V
        printf("bridge %+.2f mV\n", (double)mv);

        // --- Second scan against an external 5.0 V reference ---
        // Switching to a 5 V external reference gives full-scale range up to VDD.
        adc.setReference(5.0f);                                     // Use external reference, (vref V; 0 = internal) → void
        uint16_t ext[8];
        adc.readScan(chans, 8, ext);                                // Scan channels, (channels, count, out) → void
        printf("ext 5 V scan ch0=%u ch7=%u\n", ext[0], ext[7]);

        // --- Finish in full power-down ---
        adc.powerDown();                                            // Power down completely, () → void
        adc.setReference(0.0f);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
