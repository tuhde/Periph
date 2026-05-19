///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina3221Full

def transport = new I2CTransport(1, 0x40)                        // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
try {
    def ina = new Ina3221Full(transport)                                // construct driver (0.1 Ω shunt all channels), (transport) → Ina3221Full

    ina.configure(1, 4, 4, Ina3221Full.MODE_SHUNT_BUS_CONT)            // configure averaging and conversion times, (avg=0–7, vbusCt=0–7, vshCt=0–7, mode=0–7) → void
                                                                        // avg=1 means 4-sample average; vbusCt/vshCt=4 means 1.1 ms each

    (1..3).each { ch ->
        ina.enableChannel(ch, true)                                     // enable a channel, (channel=1–3, enabled) → void
                                                                        // sets the CH<n>en bit in the configuration register
        def en = ina.channelEnabled(ch)                                 // read channel enable state, (channel=1–3) → boolean
                                                                        // true when the CH<n>en bit is set
        println("CH${ch} enabled: ${en}")
    }

    (1..3).each { ch ->
        def v  = ina.voltage(ch)                                        // read bus voltage, (channel=1–3) → double V
                                                                        // left-aligned 12-bit unsigned, 8 mV LSB
        def sv = ina.shuntVoltage(ch)                                   // read shunt voltage, (channel=1–3) → double V
                                                                        // left-aligned 13-bit signed, 40 µV LSB
        def i  = ina.current(ch)                                        // compute current from shunt voltage, (channel=1–3) → double A
                                                                        // current = shuntVoltage / rShunt
        def p  = ina.power(ch)                                          // compute power, (channel=1–3) → double W
                                                                        // power = busVoltage × current
        printf("CH%d: %.3f V  %.6f V_shunt  %.4f A  %.4f W%n", ch, v, sv, i, p)
    }

    def cvrf = ina.conversionReady()                                    // read conversion-ready flag, () → boolean
                                                                        // CVRF bit (bit 0) of Mask/Enable; set after each full conversion
    println("Conversion ready: ${cvrf}")

    ina.setCriticalAlert(1, 0.05)                                       // set critical alert on channel 1, (channel=1–3, limitV) → void
                                                                        // alert fires when |shunt voltage| exceeds this threshold
    ina.setWarningAlert(1, 0.04)                                        // set warning alert on channel 1, (channel=1–3, limitV) → void
                                                                        // alert fires when |shunt voltage| exceeds this threshold
    ina.setCriticalAlert(2, 0.05)                                       // set critical alert on channel 2, (channel=1–3, limitV) → void
    ina.setWarningAlert(2, 0.04)                                        // set warning alert on channel 2, (channel=1–3, limitV) → void
    ina.setCriticalAlert(3, 0.05)                                       // set critical alert on channel 3, (channel=1–3, limitV) → void
    ina.setWarningAlert(3, 0.04)                                        // set warning alert on channel 3, (channel=1–3, limitV) → void

    ina.setPowerValidLimits(5.5, 4.5)                                   // set power-valid thresholds, (upperV, lowerV) → void
                                                                        // PVF bit set when all enabled bus voltages are within [lowerV, upperV]
    def pv = ina.powerValid()                                           // read power-valid flag, () → boolean
                                                                        // PVF bit (bit 2) of Mask/Enable register
    println("Power valid: ${pv}")

    ina.setSummationChannels([1, 2, 3] as int[], 0.1)                   // enable summation for all channels with limit, (channels, limitV) → void
                                                                        // sets SCC bits in Mask/Enable and writes the sum limit register
    def sumV = ina.summationValue()                                     // read shunt-voltage sum, () → double V
                                                                        // 14-bit signed, left-aligned by 1; LSB = 20 µV
    printf("Shunt voltage sum: %.6f V%n", sumV)

    def flags = ina.alertFlags()                                        // read and clear alert flags, () → int
                                                                        // reads Mask/Enable; clears latched flags; inspect against CF1/WF1/PVF etc.
    printf("Alert flags: 0x%04X%n", flags)

    def mfr = ina.manufacturerId()                                      // read manufacturer ID, () → int
                                                                        // expected 0x5449 ("TI")
    def die = ina.dieId()                                               // read die ID, () → int
                                                                        // expected 0x3220
    printf("Manufacturer ID: 0x%04X  Die ID: 0x%04X%n", mfr, die)

    ina.shutdown()                                                       // enter power-down mode (MODE=000), () → void
                                                                        // saves current MODE so wake() can restore it
    Thread.sleep(100)

    ina.wake()                                                           // restore saved operating mode, () → void
                                                                        // writes previously saved MODE bits back into config register
    Thread.sleep(100)

    ina.reset()                                                          // software reset via RST bit, then restore saved mode, () → void
                                                                        // resets all registers to hardware defaults, then writes saved MODE back

} finally {
    transport.close()
}
