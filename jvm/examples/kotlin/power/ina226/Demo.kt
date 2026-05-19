///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina226Full

/**
 * Power-supply monitoring demo: poll voltage, current, and power once per second
 * for 10 seconds, track min/max/mean, and demonstrate a power over-limit alert.
 *
 * Between samples 4 and 5 the user is prompted to switch on a load so that the
 * effect of load variation is visible in the statistics. The power over-limit
 * alert is set to 1 W; alertFlags() is checked on every iteration.
 */

private const val SAMPLES     = 10
private const val INTERVAL_MS = 1000L
private const val ALERT_POWER = 1.0  // W

fun main() {
    I2CTransport(1, 0x40).use { transport ->                 // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
        val ina = Ina226Full(transport, 0.1, 2.0)                  // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina226Full

        // --- Configure for noise-sensitive power rail monitoring ---
        // 128-sample averaging suppresses switching noise on a noisy supply;
        // continuous mode avoids re-triggering overhead between measurements.
        ina.configure(Ina226Full.AVG_128, Ina226Full.CT_1100US,    // configure ADC, (avg=4, vbusCt=4, vshCt=4, mode=7) → Unit
                      Ina226Full.CT_1100US, Ina226Full.MODE_SHUNT_BUS_CONT)

        // --- Arm a power over-limit alert at 1 W ---
        // The POL function asserts the ALERT pin and sets the OVF latch when
        // the calculated power register exceeds the threshold. We poll alertFlags()
        // on each iteration instead of wiring a GPIO interrupt.
        ina.setAlert(Ina226Full.POL, ALERT_POWER)                  // set power over-limit alert, (function=POL, limit=1.0 W) → Unit

        var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE; var sumV = 0.0
        var minI = Double.MAX_VALUE; var maxI = -Double.MAX_VALUE; var sumI = 0.0
        var minP = Double.MAX_VALUE; var maxP = -Double.MAX_VALUE; var sumP = 0.0

        for (i in 0 until SAMPLES) {
            if (i == 4) println("Switch on load now...")

            val v = ina.voltage()      // read bus voltage, () → Double V
            val c = ina.current()      // read current, () → Double A
            val p = ina.power()        // read power, () → Double W

            val alertRaw = ina.alertFlags()  // read alert flags (clears latch), () → Int
            val alert = (alertRaw and Ina226Full.POL) != 0
            println("Sample %2d: V=%.3f V  I=%.4f A  P=%.4f W%s".format(
                i + 1, v, c, p, if (alert) "  [ALERT: power over limit]" else ""))

            if (v < minV) minV = v; if (v > maxV) maxV = v; sumV += v
            if (c < minI) minI = c; if (c > maxI) maxI = c; sumI += c
            if (p < minP) minP = p; if (p > maxP) maxP = p; sumP += p

            Thread.sleep(INTERVAL_MS)
        }

        // --- Print summary statistics ---
        // Min/max/mean over the 10-second window gives a compact view of
        // supply stability and load variation.
        println()
        println("Bus voltage  — min=%.3f V   max=%.3f V   mean=%.3f V".format(minV, maxV, sumV / SAMPLES))
        println("Current      — min=%.4f A  max=%.4f A  mean=%.4f A".format(minI, maxI, sumI / SAMPLES))
        println("Power        — min=%.4f W  max=%.4f W  mean=%.4f W".format(minP, maxP, sumP / SAMPLES))
    }

}
