#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "AD7705.h"

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

    SPIConnectionESPIDF connection(dev);                                 // Create SPI connection, (dev) → SPIConnectionESPIDF
    AD7705Minimal adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);  // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nullptr) → AD7705Minimal

    while (true) {
        float v = adc.read_voltage();                                    // Read Channel 1 voltage, () → float V
        printf("%.4f\n", (double)v);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
