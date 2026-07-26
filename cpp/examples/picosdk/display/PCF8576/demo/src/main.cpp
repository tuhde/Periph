#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "PCF8576.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x70);
    PCF8576Full lcd(transport);

    stdio_init_all();
    sleep_ms(2000);

    // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
    // The PCF8576 drives four 7-segment digits from a single I2C bus; the host
    // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
    // and writes all four with one write_raw() call. The countdown runs once
    // per second and the terminal mirrors the value sent to the display.

    for (int n = 9999; n >= 0; n--) {
        uint8_t d0 = (n / 1000) % 10;
        uint8_t d1 = (n / 100) % 10;
        uint8_t d2 = (n / 10) % 10;
        uint8_t d3 = n % 10;
        uint8_t out[4] = {
            PCF8576Full::SEVEN_SEG[d0],                  // Encode 7-segment digit, (digit 0–9) → uint8_t
            PCF8576Full::SEVEN_SEG[d1],                  // Encode 7-segment digit, (digit 0–9) → uint8_t
            PCF8576Full::SEVEN_SEG[d2],                  // Encode 7-segment digit, (digit 0–9) → uint8_t
            PCF8576Full::SEVEN_SEG[d3],                  // Encode 7-segment digit, (digit 0–9) → uint8_t
        };
        lcd.write_raw(0, out, 4);                        // Write all four digits, (address 0, 4 bytes) → void
        printf("countdown: ");
        if (n < 1000) printf("%d", '0');
        if (n < 100) printf("%d", '0');
        if (n < 10) printf("%d", '0');
        printf("%d\n", n);
        sleep_ms(1000);
    }

    // --- Stop indicator: light only the middle segments (g) on every digit ---
    // When the counter reaches zero we replace the "0000" pattern with "----" to
    // signal that the demo has finished. Each digit's g segment is bit 1, so a
    // 0x02 byte lights just the bar across the middle.
    uint8_t dash[4] = {0x02, 0x02, 0x02, 0x02};
    lcd.write_raw(0, dash, 4);                           // Write dash pattern, (address 0, 4 bytes) → void
    printf("countdown complete\n");

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
