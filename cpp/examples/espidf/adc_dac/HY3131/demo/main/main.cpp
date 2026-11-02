#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "HY3131.h"

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
    dev_cfg.mode            = 0;
    dev_cfg.clock_speed_hz  = 1000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    SPIConnectionESPIDF connection(dev);                                 // Create SPI connection, (dev) → SPIConnectionESPIDF
    HY3131Full adc(connection, 1.2f);                                       // Construct and initialise the HY3131, (connection, vref=1.2 V) → HY3131Full

    // --- Bench voltmeter with on-chip temperature and AC analysis ---
    // A 0–1.2 V source sits between PB2 and AGND. AD1 runs at ~40 Hz with the chopper on
    // for offset-free DC readings; AD2 + LPF + RMS + peak hold watch the same node for AC.
    adc.configure_ad1(4, 2, 0, 0, 0, 0);                                    // Configure AD1, (osr=7, chop=0, input_gain=0, ref_gain=0, offset=0, buffers=0) → void
    // --- One-point temperature calibration ---
    // Run the board at 25 °C (or replace with a stored constant) and remember the code.
    int32_t tcode_25 = adc.read_temperature_code();                         // Read temperature code, () → int32_t
    // --- Enable AC measurement chain on the same input ---
    adc.enable_ad2(5, 1, 0, false, false, 2, false);                        // Enable AD2, (osr=5, input_gain=1, ref_gain=0, chop=false, clk_div4=false, source=0, use_ad3=false) → void
    adc.enable_lpf(3);                                                      // Enable low-pass filter, (bw=0) → void
    adc.enable_rms();                                                       // Enable RMS converter, () → void
    adc.enable_peak_hold(0);                                                // Enable peak hold, (source=0) → void

    while (true) {
        float v = adc.read_voltage();                                       // Read AD1 voltage, () → float V
        float t = adc.read_temperature(tcode_25);                           // Read die temperature, (tcode_25) → float °C
        float rms = adc.read_rms();                                         // Read RMS, () → float LSB
        int32_t pk_min, pk_max;
        adc.read_peak(pk_min, pk_max);                                      // Read peak hold, (min&, max&) → void 19-bit
        printf("V=%.4f\n", (double)(v));
        printf("T=%.4f\n", (double)(t));
        printf("rms=%.4f\n", (double)(rms));
        printf("p-p=%ld\n", (long)(pk_max - pk_min));
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
