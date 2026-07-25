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
MFRC522Minimal mfrc(transport, /*bus_type=*/0);

int main(void) {
    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    for (int i = 0; i < 10; i++) {
        bool present = mfrc.is_card_present();                     // Detect card in field, () → bool
        uint8_t uid[10];
        size_t  uid_len = 0;
        bool ok = mfrc.read_uid(uid, uid_len);                     // Read card UID (REQA → anticollision → HLTA), (out, len) → bool
        printf("present=");
        printf("%d", present);
        printf(" uid=");
        for (size_t j = 0; j < uid_len; j++) {
            if (uid[j] < 0x10) printf("0");
            printf("0x%X", (unsigned)uid[j]);
        }
        printf("\n");
        sleep_ms(500);
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
