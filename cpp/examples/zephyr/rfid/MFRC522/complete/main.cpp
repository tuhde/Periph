#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPITransportZephyr.h"
#include "MFRC522.h"

#ifndef MFRC522_SPI_NODE
#define MFRC522_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef MFRC522_CS_GPIOS
#define MFRC522_CS_GPIOS DT_PROP(MFRC522_SPI_NODE, cs_gpios)
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MFRC522_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = MFRC522_CS_GPIOS, .delay = 0 },
    };
    SPITransportZephyr transport(dev, cfg);
    MFRC522Full mfrc(transport);                                    // Create MFRC522 driver, (transport, bus_type=BUS_SPI)

    uint8_t chip_type, version;
    mfrc.version(chip_type, version);                              // Read version register, (chip_type, version) → void
                                                                    // for MFRC522 chip_type=0x09, version=1 (v1.0) or 2 (v2.0)
    printk("MFRC522 chip_type=0x%X version=%d\n", chip_type, version);

    bool ok = mfrc.self_test();                                    // Run digital self test, () → bool
                                                                    // compares 64 FIFO bytes against the version-specific reference
    check_true(ok, "self_test");

    mfrc.antenna_on();                                              // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                      // Set receiver gain, (dB=18/23/33/38/43/48) → void
                                                                    // 38 dB gives better read range on most antennas
    printk("current gain: %d dB\n", mfrc.antenna_gain());           // Read receiver gain, () → int dB

    mfrc.reset();                                                   // Soft reset and reinitialise, () → void
                                                                    // re-runs the full initialization sequence

    uint8_t uid[10];
    size_t  uid_len = 0;
    if (mfrc.select_card(uid, uid_len)) {                           // Anticollision/Select (leaves card active), (out, len) → bool
        printk("UID ok, len=%d\n", (int)uid_len);
        // Authenticate MIFARE Classic sector 1 block 4 with factory default key A
        uint8_t factory_key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};    // well-known default key — see spec
        if (mfrc.authenticate(4, MFRC522Full::KEY_A, factory_key, uid)) { // Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
            uint8_t block[16];
            if (mfrc.read_block(4, block)) {                        // Read 16-byte block, (block_address, out=16 B) → bool
                                                                    // requires successful authenticate for the containing sector
                printk("read_block 4 ok\n");
            }
            mfrc.decrement_value(4, 1);                             // Decrement value block, (block, delta=uint32) → bool
                                                                    // runs Decrement + Transfer to the same block
            mfrc.stop_crypto();                                     // Clear MFCrypto1On, () → void
                                                                    // required before authenticating a different sector
        }
        mfrc.halt_card();                                           // Send HLTA, () → void
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
