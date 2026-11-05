#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1114.h"

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
    dev_cfg.device_address = ADS1114Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS1114Full adc(connection);                            // Create ADS1114 Full driver, (connection)

    // --- Configure the converter ---
    // ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
    // reads short while still averaging out most noise.
    adc.setGain(ADS1114Full::PGA_4_096);                    // Set gain, (pga 0–5) → void
    adc.setDataRate(ADS1114Full::DR_250);                   // Set data rate, (dr 0–7) → void

    // --- Read the inputs ---
    // Single-shot mode powers the chip down between conversions.
    for (int i = 0; i < 3; i++) {
        int16_t raw = adc.readRaw();                        // Read raw code, () → int16_t
        printf("raw %d %.5f V\n", (int)raw, (double)(raw * adc.lsb()));
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // --- Arm a window comparator ---
    // ALERT/RDY asserts when the input leaves 0.5 V ... 3.0 V for two
    // consecutive conversions; the latch holds it until clearAlert().
    adc.setThresholds(0.5f, 3.0f);                          // Set thresholds, (low V, high V) → void
    adc.configureComparator(ADS1114Full::COMP_WINDOW, ADS1114Full::ACTIVE_LOW, true, ADS1114Full::QUEUE_2);// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → void
    adc.startContinuous();                                  // Start continuous, () → void

    // --- Watch for out-of-range events ---
    // Without an interrupt pin pollInterrupt() cannot see a comparator
    // alert, so read the conversion and compare in software as a fallback.
    for (int i = 0; i < 100; i++) {
        uint8_t status = adc.pollInterrupt();               // Poll interrupt, () → uint8_t source
        if (status) {
            printf("OUT OF RANGE\n");
            adc.clearAlert();                               // Clear alert, () → void
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    adc.disableComparator();                                // Disable comparator, () → void
    adc.stopContinuous();                                   // Stop continuous, () → void
}
