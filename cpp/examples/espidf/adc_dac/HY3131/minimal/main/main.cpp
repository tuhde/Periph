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
    HY3131Minimal adc(connection, 1.2f);                                    // Construct and initialise the HY3131, (connection, vref=1.2 V) → HY3131Minimal

    while (true) {
        float v = adc.read_voltage();                                       // Read AD1 differential voltage, () → float V
        printf("%.5f\n", (double)(v));
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
