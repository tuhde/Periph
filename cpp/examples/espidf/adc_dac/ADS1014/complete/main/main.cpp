#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1014.h"

static void onAlert(uint8_t status) { (void)status; }

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
    dev_cfg.device_address = ADS1014Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS1014Full adc(connection);                        // Create ADS1014 Full driver, (connection)
                                                        // no I2C traffic; defaults ±2.048 V, 1600 SPS

    int16_t raw = adc.readRaw();                        // Read raw code, () → int16 (−2048…2047)
                                                        // single-shot AIN0 − AIN1, polls OS until idle
    float v = adc.readVoltage();                        // Read voltage, () → V
                                                        // raw × full-scale / 2048
    printf("raw %d voltage %.4f V\n", (int)raw, (double)v);

    adc.setGain(ADS1014Full::PGA_1_024);                // Set PGA, (pga 0–5) → bool
                                                        // ±1.024 V range, 0.5 mV per code
    printf("gain %u\n", (unsigned)adc.getGain());       // Read PGA code, () → uint8
                                                        // cached PGA[2:0]
    printf("full scale %.3f V\n", (double)adc.fullScale());  // Read full-scale range, () → V
                                                        // ±FSR of the current gain
    printf("lsb %.6f V\n", (double)adc.lsb());          // Read code size, () → V
                                                        // fullScale() / 2048

    adc.setDataRate(ADS1014Full::DR_250);               // Set data rate, (dr 0–6) → bool
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

    adc.setThresholds(-0.2f, 0.5f);                     // Set both thresholds, (low V, high V) → bool
                                                        // converted with lsb(), clamped to the code range
    adc.setLowThresholdRaw(-200);                       // Set low threshold, (raw −2048…2047) → bool
                                                        // LO_THRESH = raw << 4
    adc.setHighThresholdRaw(500);                       // Set high threshold, (raw −2048…2047) → bool
                                                        // HI_THRESH = raw << 4
    int16_t lo = 0, hi = 0;
    adc.getThresholdsRaw(&lo, &hi);                     // Read thresholds, (low*, high*) → void
                                                        // sign-extended 12-bit codes
    printf("thresholds %d %d\n", (int)lo, (int)hi);

    adc.configureComparator(ADS1014Full::COMP_WINDOW, ADS1014Full::ACTIVE_LOW, true, ADS1014Full::QUEUE_2);  // Configure comparator, (mode, polarity, latch, queue) → bool
                                                        // window mode, latching, 2 consecutive conversions
    adc.clearAlert();                                   // Release latched alert, () → void
                                                        // reads CONVERSION
    adc.disableComparator();                            // Disable comparator, () → void
                                                        // COMP_QUE = 11, ALERT/RDY high-impedance

    adc.enableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Route conversion-ready to ALERT/RDY, (source) → bool
                                                        // HI_THRESH = 0x8000, LO_THRESH = 0, COMP_QUE = 00
    printf("status %u\n", (unsigned)adc.pollInterrupt());  // Poll ALERT/RDY, () → uint8 source
                                                        // INT pin level, else CONFIG.OS fallback
    adc.disableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Release ALERT/RDY, (source) → void
                                                        // COMP_QUE = 11

    adc.enableInterrupt(ADS1014Full::SOURCE_COMPARATOR);  // Route comparator to ALERT/RDY, (source) → bool
                                                        // restores the thresholds set above
    adc.onInterrupt(onAlert);                           // Subscribe to ALERT/RDY, (callback, intPin=nullptr) → void
                                                        // pin edge via connection.intPin(); no-op without a pin
    vTaskDelay(pdMS_TO_TICKS(1000));
    adc.offInterrupt();                                 // Unsubscribe, () → void
                                                        // detaches the edge handler
    adc.reset();                                        // Reset to power-up defaults, () → void
                                                        // rewrites CONFIG and both thresholds
}
