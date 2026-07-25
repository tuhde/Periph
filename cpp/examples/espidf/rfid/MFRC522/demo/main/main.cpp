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
    MFRC522Full mfrc(transport);                                   // Create MFRC522 driver, (transport)

    // --- Access control reader ---
    // Poll continuously for cards. When a card is detected, read its UID and
    // attempt to authenticate sector 0 with the factory default key. Logs
    // the UID and access decision to the serial console.
    mfrc.antenna_on();                                             // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                     // Set receiver gain, (dB=18/23/33/38/43/48) → void

    while (1) {
        if (!mfrc.is_card_present()) {                             // Detect card in field, () → bool
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }

        uint8_t uid[10];
        size_t uid_len = 0;
        if (!mfrc.select_card(uid, uid_len)) {                     // Anticollision/Select (leaves card active), (out, len) → bool
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }

        printf("UID:");
        for (size_t i = 0; i < uid_len; i++) printf(" %02X", uid[i]);

        uint8_t key[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
        bool auth = mfrc.authenticate(0, MFRC522Full::KEY_A, key, uid); // Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
        printf("  access=%s\n", auth ? "GRANTED" : "DENIED");
        mfrc.stop_crypto();                                        // Clear MFCrypto1On, () → void
        mfrc.halt_card();                                          // Send HLTA, () → void
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
