#include <stdio.h>
#include <string.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPITransportPicoSDK.h"
#include "MFRC522.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 17;

static const uint8_t CREDITS_BLOCK  = 4;
static const uint32_t INITIAL_CREDITS = 10;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 1000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPITransportPicoSDK transport(spi0, CS_PIN);
    MFRC522Full mfrc(transport);                                   // Create MFRC522 driver, (transport)

    // --- Prepaid-card credit counter ---
    // Simulates a transit-gate / vending-machine credit system using a MIFARE
    // Classic value block. The factory default key A (FF FF FF FF FF FF) is
    // used for the demo only — replace with a per-deployment secret in any
    // real access-control system.
    mfrc.antenna_on();                                             // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                     // Set receiver gain, (dB=18/23/33/38/43/48) → void

    while (1) {
        if (!mfrc.is_card_present()) {                             // Detect card in field, () → bool
            sleep_ms(200);
            continue;
        }

        // --- Detect a card and select it for authenticated access ---
        uint8_t uid[10];
        size_t uid_len = 0;
        if (!mfrc.select_card(uid, uid_len)) {                     // Anticollision/Select (leaves card active), (out, len) → bool
            sleep_ms(200);
            continue;
        }

        // --- Authenticate with the well-known MIFARE factory default key A ---
        // In a real deployment this would be a per-card key stored securely
        // (e.g. diversified per card UID and held in an HSM or secure element).
        uint8_t factory_key[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
        if (!mfrc.authenticate(CREDITS_BLOCK, MFRC522Full::KEY_A, factory_key, uid)) { // Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
            printf("authentication failed\n");
            mfrc.halt_card();
            sleep_ms(1000);
            continue;
        }

        // --- Read the current value block; initialise it if unprogrammed ---
        uint8_t block[16];
        if (mfrc.read_block(CREDITS_BLOCK, block)) {               // Read 16-byte block, (block_address, out=16 B) → bool
            bool all_zero = true;
            for (int i = 0; i < 16; i++) if (block[i] != 0) { all_zero = false; break; }
            if (all_zero) {
                uint8_t vb[16] = {};
                uint32_t v = INITIAL_CREDITS;
                vb[0] = v & 0xFF; vb[1] = (v >> 8) & 0xFF;
                vb[2] = (v >> 16) & 0xFF; vb[3] = (v >> 24) & 0xFF;
                vb[4] = (~v) & 0xFF; vb[5] = ((~v) >> 8) & 0xFF;
                vb[6] = ((~v) >> 16) & 0xFF; vb[7] = ((~v) >> 24) & 0xFF;
                vb[8] = vb[0]; vb[9] = vb[1]; vb[10] = vb[2]; vb[11] = vb[3];
                vb[12] = CREDITS_BLOCK; vb[13] = ~CREDITS_BLOCK;
                vb[14] = CREDITS_BLOCK; vb[15] = ~CREDITS_BLOCK;
                mfrc.write_block(CREDITS_BLOCK, vb);               // Write 16 bytes, (block, data=16 B) → bool
                mfrc.restore_value(CREDITS_BLOCK);                 // Restore + Transfer, (block) → bool
                                                                   // normalises the value-block layout
            }
        }

        // --- "Spend" one credit; refuse if balance is zero ---
        if (mfrc.read_block(CREDITS_BLOCK, block)) {               // Read current value, (block, out=16 B) → bool
            uint32_t credits = (uint32_t)block[0] | ((uint32_t)block[1] << 8)
                             | ((uint32_t)block[2] << 16) | ((uint32_t)block[3] << 24);
            if (credits == 0) {
                printf("Access denied — no credits remaining\n");
            } else {
                mfrc.decrement_value(CREDITS_BLOCK, 1);            // Decrement + Transfer, (block, delta) → bool
                uint8_t updated[16];
                if (mfrc.read_block(CREDITS_BLOCK, updated)) {     // Read updated value, (block, out=16 B) → bool
                    uint32_t bal = (uint32_t)updated[0] | ((uint32_t)updated[1] << 8)
                                 | ((uint32_t)updated[2] << 16) | ((uint32_t)updated[3] << 24);
                    printf("spent 1 credit — remaining: %lu\n", (unsigned long)bal);
                }
            }
        }
        mfrc.stop_crypto();                                        // Clear MFCrypto1On, () → void
        mfrc.halt_card();                                          // Send HLTA, () → void
        sleep_ms(1000);
    }
    return 0;
}
