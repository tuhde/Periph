///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina3221Full

/**
 * Three-rail power monitor demo.
 *
 * Wire the INA3221 across three power rails (e.g. 3.3 V, 5 V, 12 V) each with
 * a 0.1 Ω shunt. The script polls all three channels once per second for 30 s,
 * printing a tabular row per second. At t=10 s it arms critical alerts at 1.5×
 * the observed current draw on each channel. At t=20 s it enables summation
 * across all three channels. After the run it dumps the final alert flags.
 */

final POLL_S    = 30
final R_SHUNT   = 0.1   // Ω
final ALERT_MUL = 1.5

def transport = new I2CTransport(1, 0x40)            // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
try {

    // --- Construct driver with 0.1 Ω shunt on all rails ---
    // Using a common shunt value simplifies wiring; per-channel values can
    // be supplied via the double[] constructor if rails differ.
    def ina = new Ina3221Full(transport, R_SHUNT)           // construct driver, (transport, rShunt=0.1 Ω) → Ina3221Full

    // --- Configure: 4-sample averaging, 1.1 ms conversions, continuous mode ---
    // 4-sample averaging reduces noise on switching-mode supplies without
    // significantly increasing the effective sample interval (~10 ms total).
    ina.configure(1, 4, 4, Ina3221Full.MODE_SHUNT_BUS_CONT) // configure ADC, (avg=1→4 samples, vbusCt=4, vshCt=4, mode=7) → void

    def firstI = [0.0d, 0.0d, 0.0d]
    boolean alertsArmed = false
    boolean summationEnabled = false

    printf("%-5s  %-24s  %-24s  %-24s%n",
           't(s)', '--- CH1 V/A/W ---', '--- CH2 V/A/W ---', '--- CH3 V/A/W ---')
    println('-' * 85)

    (0..<POLL_S).each { t ->

        // --- Poll all three channels ---
        // Each read is two I²C transactions (bus + shunt register). Current
        // and power are derived in software — no hardware division needed.
        def v = [0d, 0d, 0d, 0d]
        def i = [0d, 0d, 0d, 0d]
        def p = [0d, 0d, 0d, 0d]
        (1..3).each { ch ->
            v[ch] = ina.voltage(ch)                         // read bus voltage, (channel=1–3) → double V
            i[ch] = ina.current(ch)                         // compute current, (channel=1–3) → double A
            p[ch] = ina.power(ch)                           // compute power, (channel=1–3) → double W
        }

        if (t == 0) {
            (1..3).each { ch -> firstI[ch - 1] = i[ch] }
        }

        printf("%-5d  %6.3f V %6.3f A %6.3f W  %6.3f V %6.3f A %6.3f W  %6.3f V %6.3f A %6.3f W%n",
               t,
               v[1], i[1], p[1],
               v[2], i[2], p[2],
               v[3], i[3], p[3])

        // --- At t=10 s: arm critical alerts at 1.5× initial draw ---
        // Setting the limit as a shunt voltage: limit_V = current × rShunt × 1.5.
        // This lets the hardware flag transient spikes without software polling.
        if (t == 10 && !alertsArmed) {
            (1..3).each { ch ->
                double limit = Math.abs(firstI[ch - 1]) * R_SHUNT * ALERT_MUL
                if (limit < 40e-6) limit = 40e-6
                ina.setCriticalAlert(ch, limit)             // set critical alert, (channel=1–3, limitV) → void
            }
            println('  [t=10] Critical alerts armed at 1.5x initial draw')
            alertsArmed = true
        }

        // --- At t=20 s: enable summation across all three channels ---
        // Summation lets the hardware accumulate shunt voltages; the sum
        // register gives total power-bus current in a single read.
        if (t == 20 && !summationEnabled) {
            double sumLimit = (Math.abs(firstI[0]) + Math.abs(firstI[1]) + Math.abs(firstI[2])) *
                               R_SHUNT * ALERT_MUL
            if (sumLimit < 40e-6) sumLimit = 40e-6
            ina.setSummationChannels([1, 2, 3] as int[], sumLimit) // enable all-channel summation, (channels, limitV) → void
            println('  [t=20] Summation enabled for channels 1+2+3')
            summationEnabled = true
        }

        Thread.sleep(1000)
    }

    // --- Final alert flag dump ---
    // Reading Mask/Enable clears latched flags; inspect individual bits.
    def flags = ina.alertFlags()                            // read and clear alert flags, () → int
    printf("%nFinal alert flags: 0x%04X%n", flags)
    if (flags & Ina3221Full.CF1) println('  Critical alert fired on CH1')
    if (flags & Ina3221Full.CF2) println('  Critical alert fired on CH2')
    if (flags & Ina3221Full.CF3) println('  Critical alert fired on CH3')
    if (flags & Ina3221Full.SF)  println('  Summation alert fired')

} finally {
    transport.close()
}
