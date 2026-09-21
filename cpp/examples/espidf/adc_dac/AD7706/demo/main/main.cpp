#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "AD7706.h"

#define FILTER_DP_THRESHOLD 0.001f

static const int MOSI_PIN = 23;
static const int MISO_PIN = 19;
static const int SCLK_PIN = 18;
static const int CS_PIN   = 5;

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num   = MOSI_PIN;
    bus_cfg.miso_io_num   = MISO_PIN;
    bus_cfg.sclk_io_num   = SCLK_PIN;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode            = 3;
    dev_cfg.clock_speed_hz  = 5000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    SPIConnectionESPIDF connection(dev);                                          // Create SPI connection, (dev) → SPIConnectionESPIDF
    AD7706Full adc(connection, 2.5f, AD7706Minimal::MCLK_2_4576MHZ);               // Construct and initialise the AD7706, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7706Full

    // --- Configure all three channels for the HVAC manifold-pressure application ---
    // All three pressure transducers are bridge-type with small mV-level output
    // signals, so the AD7706's high-gain (128) pseudo-differential input is ideal.
    // The shared-COMMON architecture lets all three bridges share a single return
    // line instead of three fully-differential pairs.
    adc.configure(1, AD7706Full::GAIN_128, true, true, 50);                        // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(2, AD7706Full::GAIN_128, true, true, 50);                        // Configure channel 2, (channel=2, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(3, AD7706Full::GAIN_128, true, true, 50);                        // Configure channel 3, (channel=3, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None

    // --- Self-calibrate all three channels before the measurement loop ---
    adc.self_calibrate(1);                                                          // Self-calibrate channel 1, (channel=1) → None
    adc.self_calibrate(2);                                                          // Self-calibrate channel 2, (channel=2) → None
    adc.self_calibrate(3);                                                          // Self-calibrate channel 3, (channel=3) → None

    float last_filter_dp = 0.0f;
    bool first = true;

    while (true) {
        // --- Sample continuously and compute filter differential pressure ---
        // Channel 1 = filter-inlet static, Channel 2 = filter-outlet static, Channel 3 = duct static.
        // filter_dp = ch1 - ch2 is the filter differential pressure (clog indicator).
        float inlet  = adc.read_voltage(1);                                         // Read voltage on channel 1, (channel=1) → float V
        float outlet = adc.read_voltage(2);                                         // Read voltage on channel 2, (channel=2) → float V
        float duct   = adc.read_voltage(3);                                         // Read voltage on channel 3, (channel=3) → float V
        float filter_dp = inlet - outlet;
        if (first || fabsf(filter_dp - last_filter_dp) > FILTER_DP_THRESHOLD) {
            printf("→ filter_dp=%.4f V, duct_pressure=%.4f V\n",
                   (double)filter_dp, (double)duct);
            last_filter_dp = filter_dp;
            first = false;
        }
        vTaskDelay(pdMS_TO_TICKS(200));
    }
}
