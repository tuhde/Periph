///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.comms.Rda5807mFull

fun main() {
    I2CTransport(1, 0x10).use { transport ->                    // open I²C bus 1, device 0x10, (bus, address) → I2CTransport
        val fm = Rda5807mFull(transport, 100.0, 8)                // construct driver, (transport, frequencyMhz=100.0, volume=8) → Rda5807mFull
                                                                  // runs the init sequence and tunes to the initial frequency

        fm.setFrequency(97.5)                                     // tune to frequency, (frequencyMhz) → Unit
                                                                  // computes CHAN from the current band/spacing and blocks until STC
        println("frequency=%.2f MHz".format(fm.frequency()))      // read tuned frequency, () → Double MHz
                                                                  // converts READCHAN back to MHz

        fm.setVolume(10)                                          // set volume, (level 0-15) → Unit
        fm.mute(false)                                            // mute/unmute, (enable) → Unit
                                                                  // enable=true mutes; here we ensure audio is audible

        val freq = fm.seek(true)                                  // seek next station, (up=true) → Double?
                                                                  // blocks until STC; returns null if SF (seek fail) is set
        println("seek=$freq")

        fm.configure(Rda5807mFull.BAND_WORLD, Rda5807mFull.SPACE_100K, true, 8, true)
                                                                  // configure tuner, (band, space, deEmphasis, seekThreshold, seekMode, clkMode, afcDisable, eastEurope50m) → Unit
                                                                  // re-tunes to the current frequency if band or space changed

        fm.setBassBoost(true)                                     // enable bass boost, (enable) → Unit
        fm.setMono(false)                                         // force mono/allow stereo, (enable) → Unit
        fm.setSoftmute(true)                                      // enable soft mute, (enable) → Unit

        fm.enableRds(true)                                        // enable RDS/RBDS, (enable) → Unit
        Thread.sleep(1000)
        println("rdsReady=${fm.rdsReady()}")                      // check RDS group ready, () → Boolean
        val group = fm.readRdsGroup()                             // read raw RDS blocks, () → IntArray?
        if (group != null) {
            println("rdsGroup=${group.toList()}")
        }

        println("isStereo=${fm.isStereo()}")                      // check stereo indicator, () → Boolean
        println("isStation=${fm.isStation()}")                    // check real station, () → Boolean
        println("isReady=${fm.isReady()}")                        // check tuner ready, () → Boolean
        println("signalStrength=${fm.signalStrength()}")          // read RSSI, () → Int 0-127

        fm.standby(true)                                          // power down/up, (enable) → Unit
        Thread.sleep(10)
        fm.standby(false)

        fm.softReset()                                            // pulse soft reset, () → Unit
    }
}
