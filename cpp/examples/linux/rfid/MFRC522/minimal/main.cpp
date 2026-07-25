#include <cstdio>
#include <unistd.h>
#include "SPITransportLinux.h"
#include "MFRC522.h"

int main() {
    SPITransportLinux transport(0, 0);

    MFRC522Minimal mfrc(transport);                                        // Create MFRC522 driver, (transport)

    while (true) {
        bool present = mfrc.is_card_present();                             // Detect card in field, () → bool
        uint8_t uid[10];
        size_t uid_len = 0;
        if (present) mfrc.read_uid(uid, uid_len);                          // Read card UID, (out, len) → bool
        if (uid_len) {
            printf("UID:");
            for (size_t i = 0; i < uid_len; i++) printf(" %02X", uid[i]);
            printf("\n");
        }
        usleep(500000);
    }
    return 0;
}
