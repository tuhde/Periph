///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.CS43L22Full
import it.uhde.periph.chips.adc_dac.CS43L22Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, CS43L22Minimal.DEFAULT_ADDRESS).use { connection ->                              // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
        val dac = CS43L22Full(connection)                                                            // Create CS43L22 Full driver, (connection, resPin=null) → CS43L22Full
        // --- Bring up the headphone amplifier ---
        // The control port only configures the codec; MCLK = 12.288 MHz and the 48 kHz
        // I2S stream come from the board's I2S master. init() loads the I2S-slave
        // defaults while the chip is powered down; start() powers it up once MCLK runs.
        dac.init()                                                                                   // Reset, verify ID, load defaults, () → Unit
        println(dac.readId())                                                                        // Read chip ID and revision, () → int[] / Pair
        dac.setVolume(-60.0)                                                                         // Set master volume, (db -102–12 dB) → Unit
        dac.start()                                                                                  // Power up the DAC once MCLK is running, () → Unit

        // --- Wait for the serial port to lock ---
        // SPCLKERR reads 1 until LRCK is valid and the MCLK/LRCK ratio is measured;
        // reading the status register clears the flags.
        for (i in 0 until 50) {
            if (!dac.readStatus().clockError) break                                                  // Read status flags (clears them), () → Status
            Thread.sleep(100)
        }

        // --- Ramp the volume up ---
        // 2 dB steps every 100 ms avoid an abrupt start; the digital soft ramp
        // smooths each step further.
        for (vol in -60..0 step 2) {
            dac.setVolume(vol.toDouble())                                                            // Set master volume, (db -102–12 dB) → Unit
            Thread.sleep(100)
        }

        // --- Shape the tone and protect the headphones ---
        // +6 dB bass and treble shelves add presence; the limiter at -6 dB stops
        // loud peaks from clipping the amplifier.
        dac.setTone(6.0, 6.0, 0, 0, true)                                                            // Configure treble / bass shelves, (trebleDb, bassDb, trebleCorner, bassCorner, enabled) → Unit
        dac.setLimiter(true, -6, -12)                                                                // Configure the peak limiter, (enabled, maxDb, cushionDb[, release, attack, limitAll, softRampDisable, zeroCrossDisable]) → Unit

        // --- Play a single beep ---
        // The beep generator needs no audio data; it mixes into the output once MCLK
        // and LRCK are running. Frequency code 0x1 is 521.74 Hz.
        dac.setBeep(0x1, 2, 0, -6.0, CS43L22Full.BEEP_SINGLE, true)                                  // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDb, mode, mix) → Unit
        Thread.sleep(1500)
        dac.stopBeep()                                                                               // Stop the beep generator, () → Unit

        // --- Power down safely ---
        // stop() mutes, disables ramps and waits 100 us after POWER_CTL_1 = 0x9F;
        // only then may the application remove MCLK.
        dac.stop()                                                                                   // Power down the DAC, () → Unit
        println("MCLK may now be removed")
    }
}
