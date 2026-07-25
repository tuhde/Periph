#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPITransportESPIDF.h"
#include "MFRC522.h"

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

    SPITransportESPIDF transport(dev);
    MFRC522Minimal mfrc(transport);                                // Create MFRC522 driver, (transport)

    while (1) {
        bool present = mfrc.is_card_present();                     // Detect card in field, () → bool
        uint8_t uid[10];
        size_t uid_len = 0;
        mfrc.read_uid(uid, uid_len);                               // Read card UID (REQA → anticollision → HLTA), (out, len) → bool
        printf("present=%d\n", present);
        vTaskDelay(pdMS_TO_TICKS(500));
    }
}
