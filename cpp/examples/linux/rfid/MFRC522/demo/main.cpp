#include <cstdio>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "MFRC522.h"

int main() {
    SPIConnectionLinux connection(0, 0);

    MFRC522Full mfrc(connection);                                           // Create MFRC522 driver, (connection)

    // --- Access control reader ---
    mfrc.antenna_on();                                                     // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                             // Set receiver gain, (dB) → void
    while (true) {
        if (!mfrc.is_card_present()) { usleep(200000); continue; }        // Detect card in field, () → bool
        uint8_t uid[10];
        size_t uid_len = 0;
        if (!mfrc.select_card(uid, uid_len)) { usleep(200000); continue; } // Anticollision/Select, (out, len) → bool
        printf("UID:");
        for (size_t i = 0; i < uid_len; i++) printf(" %02X", uid[i]);
        uint8_t key[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
        bool auth = mfrc.authenticate(0, MFRC522Full::KEY_A, key, uid);   // Run MFAuthent, (block, key_type, key, uid) → bool
        printf("  %s\n", auth ? "GRANTED" : "DENIED");
        mfrc.stop_crypto();                                                // Clear MFCrypto1On, () → void
        mfrc.halt_card();                                                  // Send HLTA, () → void
        usleep(1000000);
    }
    return 0;
}
