///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.motor.DRV2605Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, DRV2605Minimal.DEFAULT_ADDRESS)    // open I²C bus, device address, (bus, address=0x5A) → I2CConnection
try {
    def haptic = new DRV2605Minimal(connection, false)                                              // construct driver and run init, (connection, lra=false) → DRV2605Minimal

    for (int effect in [DRV2605Minimal.EFFECT_STRONG_CLICK, DRV2605Minimal.EFFECT_DOUBLE_CLICK, DRV2605Minimal.EFFECT_BUZZ]) {
        haptic.playEffect(effect)                                                                   // Play a ROM effect, (effectId 1–123) → void
        while (haptic.isPlaying()) {                                                                // Check playback, () → boolean
            Thread.sleep(10)
        }
        Thread.sleep(500)
    }
    haptic.stop()                                                                                   // Cancel playback, () → void
} finally {
    connection.close()
}
