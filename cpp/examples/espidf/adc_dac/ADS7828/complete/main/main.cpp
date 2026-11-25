// Auto-generated ESP-IDF example for ADS7828 (Complete).
// Mirrors the Arduino ADS7828_Complete example using the
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
        uint16_t raw = adc.readRaw(3);                              // Read CH3 raw code, (channel=0–7) → uint16_t
                                                            // single-ended, interleaved channel encoding; returns 0–4095
        float v3 = adc.readVoltage(3);                              // Read CH3 voltage, (channel=0–7) → float V
                                                            // raw / 4096 × vref
        float all[8];
        adc.readAll(all);                                           // Read all eight channels, (out[8]) → void
                                                            // one conversion per channel, CH0…CH7
        uint16_t draw = adc.readDifferentialRaw(0);                 // Read pair 0 raw code, (pair=0–3, inverted=false) → uint16_t
                                                            // CH0 (+) / CH1 (−); a negative difference reads 0
        float dv = adc.readDifferential(1, true);                   // Read pair 1 inverted, (pair=0–3, inverted=false) → float V
                                                            // CH3 (+) / CH2 (−)
        float sv = adc.readSignedDifferential(0);                   // Read pair 0 signed, (pair=0–3) → float V
                                                            // two conversions (normal and inverted), range −vref … +vref
        float lsb = adc.lsb();                                      // Get code step, () → float V
                                                            // vref / 4096 (610 µV at 2.5 V)
        const uint8_t chans[4] = {0, 2, 4, 6};
        uint16_t scan[4];
        adc.readScan(chans, 4, scan);                               // Scan channels, (channels, count, out) → void
                                                            // one raw conversion per channel in the given order
        adc.setReference(5.0f);                                     // Use external reference, (vref V; 0 = internal) → void
                                                            // PD1 = 0 from now on; the reference must already be stable
        float ref = adc.getReference();                             // Get reference voltage, () → float V
        bool internal = adc.usesInternalReference();                // Internal reference selected?, () → bool
        adc.setReference(0.0f);                                     // Use internal reference, (vref V; 0 = internal) → void
                                                            // re-runs the reference turn-on and 1 ms settle wait
        bool ok = adc.setPowerMode(ADS7828Full::REF_ON_ADC_ON);     // Set PD bits, (pd=0–3) → bool
                                                            // internal reference requires PD1 = 1 (values 2 or 3)
        adc.powerDown();                                            // Power down completely, () → void
                                                            // lone command with PD=00; next read re-enables the reference
        printf("raw=%u v3=%.3f V draw=%u dv=%.3f V sv=%.3f V lsb=%.6f V ref=%.2f V internal=%d ok=%d scan0=%u all7=%.3f V\n", raw, (double)v3, draw, (double)dv, (double)sv, (double)lsb, (double)ref, (int)internal, (int)ok, scan[0], (double)all[7]);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
