#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DRV2605.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DRV2605Minimal::I2C_ADDRESS);
    DRV2605Minimal haptic(connection);                     // Create DRV2605 driver, (connection, lra=false)

    while (1) {
        haptic.playEffect(DRV2605Minimal::EFFECT_STRONG_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
        while (haptic.isPlaying()) {                       // Check playback, () → bool
            k_sleep(K_MSEC(10));
        }
        k_sleep(K_MSEC(500));
        haptic.playEffect(DRV2605Minimal::EFFECT_DOUBLE_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
        while (haptic.isPlaying()) {                       // Check playback, () → bool
            k_sleep(K_MSEC(10));
        }
        k_sleep(K_MSEC(500));
        haptic.playEffect(DRV2605Minimal::EFFECT_BUZZ);    // Play a ROM effect, (effectId 1–123) → bool
        k_sleep(K_MSEC(1000));
        haptic.stop();                                     // Cancel playback, () → void
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
