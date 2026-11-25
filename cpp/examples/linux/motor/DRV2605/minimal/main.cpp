#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "DRV2605.h"

int main() {
    I2CConnectionLinux connection(1, DRV2605Minimal::I2C_ADDRESS);
    DRV2605Minimal haptic(connection);                     // Create DRV2605 driver, (connection, lra=false)

    while (1) {
        haptic.playEffect(DRV2605Minimal::EFFECT_STRONG_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
        while (haptic.isPlaying()) {                       // Check playback, () → bool
            usleep(10 * 1000UL);
        }
        usleep(500 * 1000UL);
        haptic.playEffect(DRV2605Minimal::EFFECT_DOUBLE_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
        while (haptic.isPlaying()) {                       // Check playback, () → bool
            usleep(10 * 1000UL);
        }
        usleep(500 * 1000UL);
        haptic.playEffect(DRV2605Minimal::EFFECT_BUZZ);    // Play a ROM effect, (effectId 1–123) → bool
        usleep(1000 * 1000UL);
        haptic.stop();                                     // Cancel playback, () → void
        usleep(2000 * 1000UL);
    }
    return 0;
}
