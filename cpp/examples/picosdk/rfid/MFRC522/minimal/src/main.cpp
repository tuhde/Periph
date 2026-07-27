#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "MFRC522.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 17;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 1000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);
    MFRC522Minimal mfrc(connection);                                // Create MFRC522 driver, (connection)

    while (1) {
        bool present = mfrc.is_card_present();                     // Detect card in field, () → bool
        uint8_t uid[10];
        size_t uid_len = 0;
        mfrc.read_uid(uid, uid_len);                               // Read card UID (REQA → anticollision → HLTA), (out, len) → bool
        printf("present=%d\n", present);
        sleep_ms(500);
    }
    return 0;
}
