#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCF8579.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x3C);
    PCF8579Minimal lcd(connection, 0, 16);                    // Create PCF8579 driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false)

    lcd.clear();                                              // Clear display RAM, () → void
    lcd.set_pixel(0, 0);                                      // Set one pixel, (x 0–39, y 0–15, on=true) → void
    lcd.set_pixel(39, 15);                                    // Set opposite corner, (x 0–39, y 0–15, on=true) → void
    static const uint8_t frame[] = {0xFF, 0x81, 0x81, 0xFF};
    lcd.write_columns(4, 0, frame, sizeof(frame));            // Write column bytes, (x 0–39, bank 0–1, data, len) → bool

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);
}
