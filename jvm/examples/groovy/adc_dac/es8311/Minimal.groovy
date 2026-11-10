///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ES8311Minimal

def connection = new I2CConnection(1, 0x18)  // open I²C bus 1, device 0x18, (bus, address) → I2CConnection
try {
    def codec = new ES8311Minimal(connection, 12288000L, 48000L)  // construct driver and run the init sequence, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz) → ES8311Minimal
    codec.setMicGain(24.0)                                                                      // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → void
    codec.setMute(false)                                                                        // Unmute the DAC, (muted=false) → void
    codec.setVolume(-20.0)                                                                      // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → void
    Thread.sleep(5000L)
    codec.setMute(true)                                                                         // Mute the DAC, (muted=true) → void
} finally {
    connection.close()
}
