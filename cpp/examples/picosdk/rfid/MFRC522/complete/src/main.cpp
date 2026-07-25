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

    uint8_t chip_type, version;
    mfrc.version(chip_type, version);                              // Read version register, (chip_type, version) → void
                                                                    // for MFRC522 chip_type=0x09, version=1 (v1.0) or 2 (v2.0)
    printf("MFRC522 chip_type=0x");
    printf("0x%X", (unsigned)chip_type);
    printf(" version=");
    printf("%d\n", version);

    bool ok = mfrc.self_test();                                    // Run digital self test, () → bool
                                                                    // compares 64 FIFO bytes against the version-specific reference
    check_true(ok, "self_test");

    mfrc.antenna_on();                                              // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                      // Set receiver gain, (dB=18/23/33/38/43/48) → void
                                                                    // 38 dB gives better read range on most antennas
    printf("current gain: ");
    printf("%d", mfrc.antenna_gain());                              // Read receiver gain, () → int dB
    printf(" dB\n");

    mfrc.reset();                                                   // Soft reset and reinitialise, () → void
                                                                    // re-runs the full initialization sequence

    uint8_t uid[10];
    size_t  uid_len = 0;
    if (mfrc.select_card(uid, uid_len)) {                           // Anticollision/Select (leaves card active), (out, len) → bool
        printf("UID: ");
        for (size_t i = 0; i < uid_len; i++) {
            if (uid[i] < 0x10) printf("0");
            printf("0x%X", (unsigned)uid[i]);
        }
        printf("\n");
        // Authenticate MIFARE Classic sector 1 block 4 with factory default key A
        uint8_t factory_key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};    // well-known default key — see spec
        if (mfrc.authenticate(4, MFRC522Full::KEY_A, factory_key, uid)) { // Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
            uint8_t block[16];
            if (mfrc.read_block(4, block)) {                        // Read 16-byte block, (block_address, out=16 B) → bool
                                                                    // requires successful authenticate for the containing sector
                printf("block 4: ");
                for (int i = 0; i < 16; i++) {
                    if (block[i] < 0x10) printf("0");
                    printf("0x%X", (unsigned)block[i]);
                }
                printf("\n");
            }
            mfrc.decrement_value(4, 1);                             // Decrement value block, (block, delta=uint32) → bool
                                                                    // runs Decrement + Transfer to the same block
            mfrc.stop_crypto();                                     // Clear MFCrypto1On, () → void
                                                                    // required before authenticating a different sector
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
