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

static int passed = 0, failed = 0;
static void check_true(bool c, const char *l) {
    if (c) { printf("PASS %s\n", l); passed++; }
    else   { printf("FAIL %s\n", l); failed++; }
}

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

    uint8_t chip_type, version;
    mfrc.version(chip_type, version);                              // Read version register, (chip_type, version) → void
                                                                   // for MFRC522 chip_type=0x09, version=1 (v1.0) or 2 (v2.0)
    printf("chip_type=0x%02X version=%d\n", chip_type, version);

    check_true(mfrc.self_test(), "self_test");                     // Run digital self test, () → bool
                                                                   // compares 64 FIFO bytes against the version-specific reference

    mfrc.antenna_on();                                             // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                     // Set receiver gain, (dB=18/23/33/38/43/48) → void
                                                                   // 38 dB gives better read range on most antennas
    printf("gain=%d dB\n", mfrc.antenna_gain());                 // Read receiver gain, () → int dB

    mfrc.reset();                                                  // Soft reset and reinitialise, () → void

    uint8_t uid[10];
    size_t uid_len = 0;
    if (mfrc.select_card(uid, uid_len)) {                          // Anticollision/Select (leaves card active), (out, len) → bool
        uint8_t key[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
        if (mfrc.authenticate(4, MFRC522Full::KEY_A, key, uid)) { // Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
            uint8_t block[16];
            check_true(mfrc.read_block(4, block), "read_block");   // Read 16-byte block, (block_address, out=16 B) → bool
                                                                   // requires successful authenticate for the containing sector
            mfrc.decrement_value(4, 1);                            // Decrement value block, (block, delta=uint32) → bool
            mfrc.stop_crypto();                                    // Clear MFCrypto1On, () → void
        }
        mfrc.halt_card();                                          // Send HLTA, () → void
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
