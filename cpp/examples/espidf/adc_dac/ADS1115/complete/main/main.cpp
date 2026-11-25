#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1115.h"

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
    dev_cfg.device_address = ADS1115Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS1115Full adc(connection);                            // Create ADS1115 Full driver, (connection)
                                                            // no I²C traffic; Config word is rebuilt per conversion
    int16_t raw = adc.readRaw(2);                           // Read raw code, (channel=0) → int16_t
                                                            // single-shot conversion, signed 16-bit
    printf("raw %d\n", (int)raw);
    float v = adc.readVoltage(2);                           // Read voltage, (channel=0) → float V
                                                            // raw × lsb()
    printf("voltage %.5f V\n", (double)v);
    adc.setGain(ADS1115Full::PGA_4_096);                    // Set gain, (pga 0–5) → void
                                                            // ±4.096 V full scale
    printf("gain %d\n", (int)adc.getGain());
    float fs = adc.fullScale();                             // Full scale, () → float V
                                                            // ±FSR of the current gain
    float lsb = adc.lsb();                                  // LSB, () → float V
                                                            // fullScale() / 32768
    printf("fsr %.3f lsb %.7f\n", (double)fs, (double)lsb);
    adc.setDataRate(ADS1115Full::DR_250);                   // Set data rate, (dr 0–7) → void
                                                            // DR field of the Config word
    uint16_t sps = adc.dataRateSps();                       // Data rate, () → uint16_t SPS
                                                            // samples per second of the current setting
    printf("rate %u SPS\n", (unsigned)sps);
    float d = adc.readDifferential(ADS1115Full::DIFF_2_3);  // Read differential, (pair 0–3) → float V
                                                            // single-shot MUX = pair
    printf("diff %.6f V\n", (double)d);
    int16_t draw = adc.readDifferentialRaw(ADS1115Full::DIFF_0_1);// Read differential raw, (pair 0–3) → int16_t
                                                            // signed 16-bit code
    printf("diff raw %d\n", (int)draw);
    adc.startContinuous(ADS1115Full::MUX_AIN0_GND);         // Start continuous, (mux=MUX_AIN0_GND 0–7) → void
                                                            // MODE ← 0
    vTaskDelay(pdMS_TO_TICKS(50));
    int16_t craw = adc.readContinuousRaw();                 // Read continuous raw, () → int16_t
                                                            // CONVERSION without touching Config
    float cv = adc.readContinuous();                        // Read continuous, () → float V
                                                            // readContinuousRaw() × lsb()
    printf("continuous %d %.5f V\n", (int)craw, (double)cv);
    adc.stopContinuous();                                   // Stop continuous, () → void
                                                            // MODE ← 1
    bool busy = adc.isConverting();                         // Check conversion, () → bool
                                                            // CONFIG.OS == 0
    uint16_t cfg = adc.readConfig();                        // Read CONFIG, () → uint16_t
                                                            // raw 16-bit register
    printf("converting %d config 0x%04X\n", (int)busy, (unsigned)cfg);
    adc.setThresholds(0.5f, 3.0f);                          // Set thresholds, (low V, high V) → void
                                                            // converted with the current lsb(), clamped
    adc.setLowThresholdRaw(4000);                           // Set low threshold, (raw) → void
                                                            // LO_THRESH ← raw & 0xFFFF
    adc.setHighThresholdRaw(20000);                         // Set high threshold, (raw) → void
                                                            // HI_THRESH ← raw & 0xFFFF
    int16_t lo, hi;
    adc.getThresholdsRaw(&lo, &hi);                         // Get thresholds, (int16_t* low, int16_t* high) → void
                                                            // read back, sign-extended
    printf("thresholds %d %d\n", (int)lo, (int)hi);
    adc.configureComparator(ADS1115Full::COMP_WINDOW, ADS1115Full::ACTIVE_LOW, true, ADS1115Full::QUEUE_2);// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → void
                                                            // window mode, latching, 2-sample queue
    adc.clearAlert();                                       // Clear alert, () → void
                                                            // reads CONVERSION to release the latch
    adc.disableComparator();                                // Disable comparator, () → void
                                                            // COMP_QUE ← 11
    adc.enableInterrupt(ADS1115Full::SOURCE_CONVERSION_READY);// Enable interrupt, (source) → void
                                                            // ALERT/RDY signals conversion-ready
    uint8_t status = adc.pollInterrupt();                   // Poll interrupt, () → uint8_t source
                                                            // enabled source if ALERT/RDY is asserted
    printf("poll %u\n", (unsigned)status);
    adc.disableInterrupt(ADS1115Full::SOURCE_CONVERSION_READY);// Disable interrupt, (source) → void
                                                            // releases ALERT/RDY
    adc.reset();                                            // Reset, () → void
                                                            // writes the reset values back
}
