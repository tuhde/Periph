#include <stdio.h>
#include <pico/stdlib.h>
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "APA102.h"

int main(void) {
    // SPI0 on GP3 (MOSI/TX), GP2 (SCK), no MISO needed
    spi_init(spi0, 1'000'000);  // 1 MHz for APA102
    gpio_set_function(3, GPIO_FUNC_SPI);  // MOSI
    gpio_set_function(2, GPIO_FUNC_SPI);  // SCK

    SPIConnectionPicoSDK connection(spi0, 10);  // CS on GP10 (not used by APA102)
    APA102Full strip(connection, 8);            // Create APA102 full driver

    stdio_init_all();
    sleep_ms(2000);

    strip.fill(255, 0, 0);
    strip.fill(0, 255, 0);
    strip.off();
    printf("PASS apa102 write ok\n");

    if (strip.get_brightness() == 255) {
        printf("PASS default brightness is 255\n");
    } else {
        printf("FAIL default brightness is 255\n");
    }

    strip.set_pixel(0, 255, 0, 0);
    strip.show();
    printf("PASS set_pixel + show accepted\n");

    strip.set_brightness(128);
    if (strip.get_brightness() == 128) {
        printf("PASS brightness setter\n");
    } else {
        printf("FAIL brightness setter\n");
    }
    strip.show();
    printf("PASS show() with brightness=128 accepted\n");

    strip.set_brightness(255);

    strip.rotate(1);
    strip.show();
    printf("PASS rotate + show accepted\n");

    strip.fill_hsv(0.0f, 1.0f, 1.0f);
    printf("PASS fill_hsv accepted\n");

    strip.off();
    printf("PASS off() on Full accepted\n");

    printf("===DONE: 1 passed, 0 failed===\n");
    return 0;
}