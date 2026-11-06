#include <Wire.h>
#include <Periph.h>

void setup() {
    Wire.begin();
    I2CConnection connection(Wire, DRV2605Minimal::I2C_ADDRESS);
    DRV2605Minimal haptic(connection);                     // Create DRV2605 driver, (connection, lra=false)

    while (1) {
        haptic.playEffect(DRV2605Minimal::EFFECT_STRONG_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
        while (haptic.isPlaying()) {                       // Check playback, () → bool
            delay(10);
        }
        delay(500);
        haptic.playEffect(DRV2605Minimal::EFFECT_DOUBLE_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
        while (haptic.isPlaying()) {                       // Check playback, () → bool
            delay(10);
        }
        delay(500);
        haptic.playEffect(DRV2605Minimal::EFFECT_BUZZ);    // Play a ROM effect, (effectId 1–123) → bool
        delay(1000);
        haptic.stop();                                     // Cancel playback, () → void
        delay(2000);
    }
}

void loop() {}
