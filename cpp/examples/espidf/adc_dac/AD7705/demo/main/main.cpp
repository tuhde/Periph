#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "AD7705.h"

#define TEMP_COEFF 0.05f
#define TEMP_REFERENCE 1.25f
#define CHANGE_THRESHOLD 0.001f

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
    AD7705Full adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);               // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7705Full

    // --- Configure both channels for the bridge-pressure application ---
    adc.configure(1, AD7705Full::GAIN_128, true, true, 50);                        // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(2, AD7705Full::GAIN_2, true, false, 50);                         // Configure channel 2, (channel=2, gain=GAIN_2, bipolar=true, buffered=false, output_rate_hz=50) → None

    // --- Self-calibrate both channels before the measurement loop ---
    adc.self_calibrate(1);                                                          // Self-calibrate channel 1, (channel=1) → None
    adc.self_calibrate(2);                                                          // Self-calibrate channel 2, (channel=2) → None

    float last_pressure = 0.0f;
    bool first = true;

    while (true) {
        // --- Sample continuously and compensate the pressure reading for temperature ---
        float pressure_raw = adc.read_voltage(1);                                   // Read voltage on channel 1, (channel=1) → float V
        float temp = adc.read_voltage(2);                                           // Read voltage on channel 2, (channel=2) → float V
        float pressure = pressure_raw - TEMP_COEFF * (temp - TEMP_REFERENCE);
        if (first || fabsf(pressure - last_pressure) > CHANGE_THRESHOLD) {
            printf("→ pressure=%.4f V (raw %.4f V, temp %.4f V)\n",
                   (double)pressure, (double)pressure_raw, (double)temp);
            last_pressure = pressure;
            first = false;
        }
        vTaskDelay(pdMS_TO_TICKS(200));
    }
}
