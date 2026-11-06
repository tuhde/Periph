#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DRV2605.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, DRV2605Minimal::I2C_ADDRESS);
    DRV2605Minimal haptic(connection);                     // Create DRV2605 driver, (connection, lra=false)

    while (1) {
        haptic.playEffect(DRV2605Minimal::EFFECT_STRONG_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
        while (haptic.isPlaying()) {                       // Check playback, () → bool
            sleep_ms(10);
        }
        sleep_ms(500);
        haptic.playEffect(DRV2605Minimal::EFFECT_DOUBLE_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
        while (haptic.isPlaying()) {                       // Check playback, () → bool
            sleep_ms(10);
        }
        sleep_ms(500);
        haptic.playEffect(DRV2605Minimal::EFFECT_BUZZ);    // Play a ROM effect, (effectId 1–123) → bool
        sleep_ms(1000);
        haptic.stop();                                     // Cancel playback, () → void
        sleep_ms(2000);
    }
    return 0;
}
