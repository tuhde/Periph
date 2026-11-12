///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.DS1881Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, DS1881Full.DEFAULT_ADDRESS).use { connection ->                               // open I²C bus, device address, (bus, address=0x28) → I2CConnection
        val amp = DS1881Full(connection)                                                            // construct driver, (connection) → DS1881Full
        amp.init()                                                                                  // Apply defaults, () → Unit
                                                                                                    // waits 1 ms, rewrites the config only if it differs

        amp.setAttenuation(0, 12.0)                                                                 // Set attenuation, (channel 0–1, db dB) → Unit
                                                                                                    // nearest position, one command byte
        println(amp.getAttenuation(0))                                                              // Read attenuation, (channel 0–1) → double dB
                                                                                                    // three-byte round-robin read, position in dB
        amp.setPosition(1, 30)                                                                      // Set raw wiper position, (channel 0–1, position 0–63) → Unit
                                                                                                    // position 63 is mute in Option 1
        println(amp.getPosition(1))                                                                 // Read wiper position, (channel 0–1) → int
                                                                                                    // low six bits of the read-back byte
        amp.mute(1)                                                                                 // Mute channel, (channel 0–1) → Unit
                                                                                                    // writes the mute position (63)

        amp.setBoth(5, 25)                                                                          // Set both wipers, (position0 0–63, position1 0–63) → Unit
                                                                                                    // one transaction, both wipers update together
        amp.setAttenuationBoth(20.0)                                                                // Set both attenuations, (db0 dB, db1=db0 dB) → Unit
                                                                                                    // db1 defaults to db0 (stereo volume)
        val all = amp.readAll()                                                                     // Read everything, () → int[] {position0, position1, config}
                                                                                                    // one three-byte round-robin read
        println("positions " + all[0] + " " + all[1] + " config " + all[2])

        amp.configure(2, true, false)                                                               // Configure, (option 1–2, zeroCrossing bool, nonvolatile bool) → Unit
                                                                                                    // writes the config byte only if it changed
        val cfg = amp.getConfig()                                                                   // Read configuration, () → Config {option, zeroCrossing, nonvolatile, raw}
                                                                                                    // fresh read; refreshes the driver's table
        println("option " + cfg.option + " zc " + cfg.zeroCrossing + " nv " + cfg.nonvolatile)
        println("13 -> " + amp.positionToDb(13) + " dB, max position " + amp.maxPosition())         // Table lookup, (position) → double dB; Last non-mute position, () → int
                                                                                                    // Option 2: 13 → 14 dB, max position 32
        println(amp.dbToPosition(39.0))                                                             // Nearest position, (db dB) → int
                                                                                                    // 70 dB and above map to mute
        amp.setOption(1)                                                                            // Select option, (option 1–2) → Unit
                                                                                                    // positions are not converted
        amp.setZeroCrossing(false)                                                                  // Set zero-crossing, (enabled bool) → Unit
                                                                                                    // ZC bit of the configuration
        amp.setZeroCrossing(true)                                                                   // Set zero-crossing, (enabled bool) → Unit
                                                                                                    // clickless updates again
        amp.waitReady(0.05)                                                                         // Acknowledge-poll, (timeoutS=0.05 s) → Unit
                                                                                                    // returns once the chip ACKs its address

        amp.setNonvolatile(true)                                                                    // Select EEPROM storage, (enabled bool) → Unit
                                                                                                    // V/NV bit; every wiper write now wears the EEPROM (50 000 writes)
        amp.store()                                                                                 // Store positions, () → Unit
                                                                                                    // rewrites both wipers and polls for completion
        amp.setNonvolatile(false)                                                                   // Select EEPROM storage, (enabled bool) → Unit
                                                                                                    // back to volatile wipers
        Thread.sleep(1000)
        amp.reset()                                                                                 // Reset, () → Unit
                                                                                                    // volatile, zero-crossing, Option 1, both wipers muted
    }
}
