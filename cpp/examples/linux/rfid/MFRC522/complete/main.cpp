#include <cstdio>
#include <unistd.h>
#include "SPITransportLinux.h"
#include "MFRC522.h"

int main() {
    SPITransportLinux transport(0, 0);

    MFRC522Full mfrc(transport);                                           // Create MFRC522 driver, (transport)

    uint8_t chip_type, version;
    mfrc.version(chip_type, version);                                      // Read version register, (chip_type, version) → void
    printf("chip_type=0x%02X version=%d\n", chip_type, version);
    printf("self_test=%d\n", mfrc.self_test());                           // Run digital self test, () → bool
    mfrc.antenna_on();                                                     // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                             // Set receiver gain, (dB=18/23/33/38/43/48) → void
    printf("gain=%d dB\n", mfrc.antenna_gain());                          // Read receiver gain, () → int dB
    mfrc.reset();                                                          // Soft reset and reinitialise, () → void

    uint8_t uid[10];
    size_t uid_len = 0;
    if (mfrc.select_card(uid, uid_len)) {                                  // Anticollision/Select (leaves card active), (out, len) → bool
        uint8_t key[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
        if (mfrc.authenticate(4, MFRC522Full::KEY_A, key, uid)) {         // Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
            uint8_t block[16];
            mfrc.read_block(4, block);                                     // Read 16-byte block, (block_address, out=16 B) → bool
            mfrc.decrement_value(4, 1);                                    // Decrement value block, (block, delta) → bool
            mfrc.stop_crypto();                                            // Clear MFCrypto1On, () → void
        }
        mfrc.halt_card();                                                  // Send HLTA, () → void
    }
    return 0;
}
