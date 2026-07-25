#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPITransportPicoSDK.h"
#include "MFRC522.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 17;

static int passed = 0, failed = 0;
static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 1000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPITransportPicoSDK transport(spi0, CS_PIN);
    MFRC522Full mfrc(transport);                                   // Create MFRC522 driver, (transport)

    uint8_t chip_type, version;
    mfrc.version(chip_type, version);                              // Read version register, (chip_type, version) → void
                                                                   // for MFRC522 chip_type=0x09, version=1 (v1.0) or 2 (v2.0)
    printf("MFRC522 chip_type=0x%02X version=%d\n", chip_type, version);

    check_true(mfrc.self_test(), "self_test");                     // Run digital self test, () → bool
                                                                   // compares 64 FIFO bytes against the version-specific reference

    mfrc.antenna_on();                                             // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                     // Set receiver gain, (dB=18/23/33/38/43/48) → void
                                                                   // 38 dB gives better read range on most antennas
    printf("gain=%d dB\n", mfrc.antenna_gain());                  // Read receiver gain, () → int dB

    mfrc.reset();                                                  // Soft reset and reinitialise, () → void
                                                                   // re-runs the full initialization sequence

    uint8_t uid[10];
    size_t uid_len = 0;
    if (mfrc.select_card(uid, uid_len)) {                          // Anticollision/Select (leaves card active), (out, len) → bool
        printf("UID:");
        for (size_t i = 0; i < uid_len; i++) printf(" %02X", uid[i]);
        printf("\n");
        uint8_t factory_key[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
        if (mfrc.authenticate(4, MFRC522Full::KEY_A, factory_key, uid)) { // Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
            uint8_t block[16];
            check_true(mfrc.read_block(4, block), "read_block");   // Read 16-byte block, (block_address, out=16 B) → bool
                                                                   // requires successful authenticate for the containing sector
            mfrc.decrement_value(4, 1);                            // Decrement value block, (block, delta=uint32) → bool
                                                                   // runs Decrement + Transfer to the same block
            mfrc.stop_crypto();                                    // Clear MFCrypto1On, () → void
                                                                   // required before authenticating a different sector
        }
        mfrc.halt_card();                                          // Send HLTA, () → void
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    while (1) sleep_ms(1000);
    return 0;
}
