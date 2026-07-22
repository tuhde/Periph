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
    MFRC522Full mfrc(transport);

    // --- Prepaid-card credit counter ---
    // Simulates a transit-gate / vending-machine credit system using a MIFARE
    // Classic value block. The factory default key A (FF FF FF FF FF FF) is
    // used for the demo only — replace with a per-deployment secret in any
    // real access-control system.
    const uint8_t CREDITS_BLOCK = 4;
    const uint32_t INITIAL_CREDITS = 10;

    // --- Detect a card and select it for authenticated access ---
    uint8_t uid[10];
    size_t  uid_len = 0;
    if (!mfrc.select_card(uid, uid_len)) {                         // Anticollision/Select only, (out, len) → bool
        printk("no card in field\n");
    } else {
        // --- Authenticate with the well-known MIFARE factory default key A ---
        // In a real deployment this would be a per-card key stored securely
        // (e.g. diversified per card UID and held in an HSM or secure element).
        uint8_t factory_key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
        if (!mfrc.authenticate(CREDITS_BLOCK, MFRC522Full::KEY_A, factory_key, uid)) { // MFAuthent, (block, key, uid) → bool
            printk("authentication failed\n");
        } else {
            // --- Read the current value block; initialise it if unprogrammed ---
            uint8_t block[16];
            bool all_zero = false;
            if (mfrc.read_block(CREDITS_BLOCK, block)) {            // Read 16-byte block, (block_address, out) → bool
                all_zero = true;
                for (int i = 0; i < 16; i++) {
                    if (block[i] != 0) { all_zero = false; break; }
                }
            }
            if (all_zero) {
                uint8_t value_block[16];
                for (int i = 0; i < 16; i++) value_block[i] = 0;
                value_block[0] = INITIAL_CREDITS & 0xFF;
                value_block[1] = (INITIAL_CREDITS >> 8) & 0xFF;
                value_block[2] = (INITIAL_CREDITS >> 16) & 0xFF;
                value_block[3] = (INITIAL_CREDITS >> 24) & 0xFF;
                uint32_t v = INITIAL_CREDITS;
                value_block[4] = (~v) & 0xFF;
                value_block[5] = ((~v) >> 8) & 0xFF;
                value_block[6] = ((~v) >> 16) & 0xFF;
                value_block[7] = ((~v) >> 24) & 0xFF;
                value_block[8]  = value_block[0];
                value_block[9]  = value_block[1];
                value_block[10] = value_block[2];
                value_block[11] = value_block[3];
                value_block[12] = CREDITS_BLOCK;
                value_block[13] = (~CREDITS_BLOCK) & 0xFF;
                value_block[14] = CREDITS_BLOCK;
                value_block[15] = (~CREDITS_BLOCK) & 0xFF;
                mfrc.write_block(CREDITS_BLOCK, value_block);       // Write 16 bytes, (block, data=16 B) → bool
                mfrc.restore_value(CREDITS_BLOCK);                  // Restore + Transfer, (block) → bool
                                                                    // normalises the value-block layout
            }

            // --- "Spend" one credit; refuse if balance is zero ---
            if (mfrc.read_block(CREDITS_BLOCK, block)) {            // Read current value, (block, out) → bool
                uint32_t credits = (uint32_t)block[0] |
                                   ((uint32_t)block[1] << 8) |
                                   ((uint32_t)block[2] << 16) |
                                   ((uint32_t)block[3] << 24);
                if (credits <= 0) {
                    printk("Access denied — no credits remaining\n");
                } else {
                    mfrc.decrement_value(CREDITS_BLOCK, 1);         // Decrement + Transfer, (block, delta) → bool
                    uint8_t updated[16];
                    if (mfrc.read_block(CREDITS_BLOCK, updated)) {  // Read updated value, (block, out) → bool
                        uint32_t new_balance = (uint32_t)updated[0] |
                                               ((uint32_t)updated[1] << 8) |
                                               ((uint32_t)updated[2] << 16) |
                                               ((uint32_t)updated[3] << 24);
                        printk("spent 1 credit — remaining: %u\n", (unsigned)new_balance);
                    }
                }
            }
            mfrc.stop_crypto();                                     // Clear MFCrypto1On, () → void
        }
        mfrc.halt_card();                                           // Send HLTA, () → void
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
