#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "MFRC522.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x28);
MFRC522Full mfrc(transport, /*bus_type=*/0);

int main(void) {
    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

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
        printf("no card in field\n");
    } else {
        // --- Authenticate with the well-known MIFARE factory default key A ---
        // In a real deployment this would be a per-card key stored securely
        // (e.g. diversified per card UID and held in an HSM or secure element).
        uint8_t factory_key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
        if (!mfrc.authenticate(CREDITS_BLOCK, MFRC522Full::KEY_A, factory_key, uid)) { // MFAuthent, (block, key, uid) → bool
            printf("authentication failed\n");
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
                memset(value_block, 0, sizeof(value_block));
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
                    printf("Access denied — no credits remaining\n");
                } else {
                    mfrc.decrement_value(CREDITS_BLOCK, 1);         // Decrement + Transfer, (block, delta) → bool
                    uint8_t updated[16];
                    if (mfrc.read_block(CREDITS_BLOCK, updated)) {  // Read updated value, (block, out) → bool
                        uint32_t new_balance = (uint32_t)updated[0] |
                                               ((uint32_t)updated[1] << 8) |
                                               ((uint32_t)updated[2] << 16) |
                                               ((uint32_t)updated[3] << 24);
                        printf("spent 1 credit — remaining: ");
                        printf("%d\n", new_balance);
                    }
                }
            }
            mfrc.stop_crypto();                                     // Clear MFCrypto1On, () → void
        }
        mfrc.halt_card();                                           // Send HLTA, () → void
    }

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
