///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
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

private const val POLL_S    = 30
private const val R_SHUNT   = 0.1   // Ω
private const val ALERT_MUL = 1.5

fun main() {
    val pi4j = Pi4J.newAutoContext()                                    // initialise Pi4J, () → Context
    try {
        I2CTransport(pi4j, 1, 0x40).use { transport ->                 // open I²C bus 1, device 0x40, (bus, address) → I2CTransport

            // --- Construct driver with 0.1 Ω shunt on all rails ---
            // Using a common shunt value simplifies wiring; per-channel values can
            // be supplied via the DoubleArray constructor if rails differ.
            val ina = Ina3221Full(transport, R_SHUNT)                   // construct driver, (transport, rShunt=0.1 Ω) → Ina3221Full

            // --- Configure: 4-sample averaging, 1.1 ms conversions, continuous mode ---
            // 4-sample averaging reduces noise on switching-mode supplies without
            // significantly increasing the effective sample interval (~10 ms total).
            ina.configure(1, 4, 4, Ina3221Full.MODE_SHUNT_BUS_CONT)    // configure ADC, (avg=1→4 samples, vbusCt=4, vshCt=4, mode=7) → Unit

            val firstI = doubleArrayOf(0.0, 0.0, 0.0)
            var alertsArmed = false
            var summationEnabled = false

            println("%-5s  %-24s  %-24s  %-24s".format(
                "t(s)", "--- CH1 V/A/W ---", "--- CH2 V/A/W ---", "--- CH3 V/A/W ---"))
            println("-".repeat(85))

            for (t in 0 until POLL_S) {

                // --- Poll all three channels ---
                // Each read is two I²C transactions (bus + shunt register). Current
                // and power are derived in software — no hardware division needed.
                val v = DoubleArray(4)
                val i = DoubleArray(4)
                val p = DoubleArray(4)
                for (ch in 1..3) {
                    v[ch] = ina.voltage(ch)                             // read bus voltage, (channel=1–3) → Double V
                    i[ch] = ina.current(ch)                             // compute current, (channel=1–3) → Double A
                    p[ch] = ina.power(ch)                               // compute power, (channel=1–3) → Double W
                }

                if (t == 0) {
                    for (ch in 1..3) firstI[ch - 1] = i[ch]
                }

                println("%-5d  %6.3f V %6.3f A %6.3f W  %6.3f V %6.3f A %6.3f W  %6.3f V %6.3f A %6.3f W".format(
                    t,
                    v[1], i[1], p[1],
                    v[2], i[2], p[2],
                    v[3], i[3], p[3]))

                // --- At t=10 s: arm critical alerts at 1.5× initial draw ---
                // Setting the limit as a shunt voltage: limit_V = current × rShunt × 1.5.
                // This lets the hardware flag transient spikes without software polling.
                if (t == 10 && !alertsArmed) {
                    for (ch in 1..3) {
                        var limit = Math.abs(firstI[ch - 1]) * R_SHUNT * ALERT_MUL
                        if (limit < 40e-6) limit = 40e-6
                        ina.setCriticalAlert(ch, limit)                 // set critical alert, (channel=1–3, limitV) → Unit
                    }
                    println("  [t=10] Critical alerts armed at 1.5x initial draw")
                    alertsArmed = true
                }

                // --- At t=20 s: enable summation across all three channels ---
                // Summation lets the hardware accumulate shunt voltages; the sum
                // register gives total power-bus current in a single read.
                if (t == 20 && !summationEnabled) {
                    var sumLimit = (Math.abs(firstI[0]) + Math.abs(firstI[1]) + Math.abs(firstI[2])) *
                                   R_SHUNT * ALERT_MUL
                    if (sumLimit < 40e-6) sumLimit = 40e-6
                    ina.setSummationChannels(intArrayOf(1, 2, 3), sumLimit) // enable all-channel summation, (channels, limitV) → Unit
                    println("  [t=20] Summation enabled for channels 1+2+3")
                    summationEnabled = true
                }

                Thread.sleep(1000)
            }

            // --- Final alert flag dump ---
            // Reading Mask/Enable clears latched flags; inspect individual bits.
            val flags = ina.alertFlags()                                // read and clear alert flags, () → Int
            println("\nFinal alert flags: 0x%04X".format(flags))
            if (flags and Ina3221Full.CF1 != 0) println("  Critical alert fired on CH1")
            if (flags and Ina3221Full.CF2 != 0) println("  Critical alert fired on CH2")
            if (flags and Ina3221Full.CF3 != 0) println("  Critical alert fired on CH3")
            if (flags and Ina3221Full.SF  != 0) println("  Summation alert fired")
        }
    } finally {
        pi4j.shutdown()
    }
}
