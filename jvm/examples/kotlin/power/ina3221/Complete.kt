///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina3221Full

fun main() {
    I2CTransport(1, 0x40).use { transport ->                 // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
        val ina = Ina3221Full(transport)                            // construct driver (0.1 Ω shunt all channels), (transport) → Ina3221Full

        ina.configure(1, 4, 4, Ina3221Full.MODE_SHUNT_BUS_CONT)    // configure averaging and conversion times, (avg=0–7, vbusCt=0–7, vshCt=0–7, mode=0–7) → Unit
                                                                    // avg=1 means 4-sample average; vbusCt/vshCt=4 means 1.1 ms each

        for (ch in 1..3) {
            ina.enableChannel(ch, true)                             // enable a channel, (channel=1–3, enabled) → Unit
                                                                    // sets the CH<n>en bit in the configuration register
            val en = ina.channelEnabled(ch)                         // read channel enable state, (channel=1–3) → Boolean
                                                                    // true when the CH<n>en bit is set
            println("CH$ch enabled: $en")
        }

        for (ch in 1..3) {
            val v  = ina.voltage(ch)                                // read bus voltage, (channel=1–3) → Double V
                                                                    // left-aligned 12-bit unsigned, 8 mV LSB
            val sv = ina.shuntVoltage(ch)                           // read shunt voltage, (channel=1–3) → Double V
                                                                    // left-aligned 13-bit signed, 40 µV LSB
            val i  = ina.current(ch)                                // compute current from shunt voltage, (channel=1–3) → Double A
                                                                    // current = shuntVoltage / rShunt
            val p  = ina.power(ch)                                  // compute power, (channel=1–3) → Double W
                                                                    // power = busVoltage × current
            println("CH$ch: ${"%.3f".format(v)} V  ${"%.6f".format(sv)} V_shunt  ${"%.4f".format(i)} A  ${"%.4f".format(p)} W")
        }

        val cvrf = ina.conversionReady()                            // read conversion-ready flag, () → Boolean
                                                                    // CVRF bit (bit 0) of Mask/Enable; set after each full conversion
        println("Conversion ready: $cvrf")

        ina.setCriticalAlert(1, 0.05)                               // set critical alert on channel 1, (channel=1–3, limitV) → Unit
                                                                    // alert fires when |shunt voltage| exceeds this threshold
        ina.setWarningAlert(1, 0.04)                                // set warning alert on channel 1, (channel=1–3, limitV) → Unit
                                                                    // alert fires when |shunt voltage| exceeds this threshold
        ina.setCriticalAlert(2, 0.05)                               // set critical alert on channel 2, (channel=1–3, limitV) → Unit
        ina.setWarningAlert(2, 0.04)                                // set warning alert on channel 2, (channel=1–3, limitV) → Unit
        ina.setCriticalAlert(3, 0.05)                               // set critical alert on channel 3, (channel=1–3, limitV) → Unit
        ina.setWarningAlert(3, 0.04)                                // set warning alert on channel 3, (channel=1–3, limitV) → Unit

        ina.setPowerValidLimits(5.5, 4.5)                           // set power-valid thresholds, (upperV, lowerV) → Unit
                                                                    // PVF bit set when all enabled bus voltages are within [lowerV, upperV]
        val pv = ina.powerValid()                                    // read power-valid flag, () → Boolean
                                                                    // PVF bit (bit 2) of Mask/Enable register
        println("Power valid: $pv")

        ina.setSummationChannels(intArrayOf(1, 2, 3), 0.1)          // enable summation for all channels with limit, (channels, limitV) → Unit
                                                                    // sets SCC bits in Mask/Enable and writes the sum limit register
        val sumV = ina.summationValue()                             // read shunt-voltage sum, () → Double V
                                                                    // 14-bit signed, left-aligned by 1; LSB = 20 µV
        println("Shunt voltage sum: ${"%.6f".format(sumV)} V")

        val flags = ina.alertFlags()                                // read and clear alert flags, () → Int
                                                                    // reads Mask/Enable; clears latched flags; inspect against CF1/WF1/PVF etc.
        println("Alert flags: 0x%04X".format(flags))

        val mfr = ina.manufacturerId()                              // read manufacturer ID, () → Int
                                                                    // expected 0x5449 ("TI")
        val die = ina.dieId()                                       // read die ID, () → Int
                                                                    // expected 0x3220
        println("Manufacturer ID: 0x%04X  Die ID: 0x%04X".format(mfr, die))

        ina.shutdown()                                               // enter power-down mode (MODE=000), () → Unit
                                                                    // saves current MODE so wake() can restore it
        Thread.sleep(100)

        ina.wake()                                                   // restore saved operating mode, () → Unit
                                                                    // writes previously saved MODE bits back into config register
        Thread.sleep(100)

        ina.reset()                                                  // software reset via RST bit, then restore saved mode, () → Unit
                                                                    // resets all registers to hardware defaults, then writes saved MODE back
    }

}
