///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.other.Mpr121Full

fun main() {
    I2CConnection(1, 0x5A).use { connection ->                         // Open I2C connection, (bus=1, address=0x5A) → I2CConnection
        val mpr = Mpr121Full(connection)                                 // Create MPR121 driver, (connection) → Mpr121Full
                                                                       // runs Minimal init then exposes Full configuration methods

        mpr.stop()                                                       // Enter Stop Mode, () → Unit
                                                                       // required before writing most config registers
        mpr.configureThresholds(0, 15, 8)                                // Set thresholds, (electrode=0, touch=15, release=8) → Unit
                                                                       // ELE0: touch at 15 LSBs below baseline, release at 8 LSBs
        mpr.configureAllThresholds(12, 6)                                 // Apply thresholds to all, (touch=12, release=6) → Unit
                                                                       // ELE1..ELE11: same touch/release values
        mpr.configureProximityThresholds(8, 4)                           // Set ELEPROX thresholds, (touch=8, release=4) → Unit
                                                                       // ELEPROX touch at 8 LSBs, release at 4
        mpr.configureBaselineFilter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0)    // Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → Unit
                                                                       // baseline filter defaults; tracks capacitance drift only
        mpr.configureSampling(16, 1, 0, 0, 4)                            // Set AFE config, (cdc=16, cdt=1, ffi=0, sfi=0, esi=4) → Unit
                                                                       // 16 µA global CDC, 0.5 µs charge time, 16 ms sample interval
        mpr.configureDebounce(1, 1)                                      // Set debounce, (touch=1, release=1) → Unit
                                                                       // one consecutive measurement to detect touch/release
        mpr.configureAutoconfig(3300, 0, false, true, true)               // Configure autoconfig, (vddMv=3300, retry=0, scts=false, are=true, ace=true) → Unit
                                                                       // recompute USL/TL/LSL for 3.3 V supply
        mpr.start(12, 2, 0)                                             // Enter Run Mode, (nElectrodes=12, cl=2, eleproxEn=0) → Unit
                                                                       // all 12 electrodes, baseline init from first measurement, proximity off

        repeat(10) {
            Thread.sleep(200)
            val t = mpr.touched()                                          // Read 12-bit touch bitmask, () → Int bitmask
            val f0 = mpr.filtered(0)                                       // Read ELE0 filtered, (electrode=0) → Int 0..1023
                                                                       // raw 10-bit value, inversely proportional to capacitance
            val b0 = mpr.baseline(0)                                       // Read ELE0 baseline, (electrode=0) → Int 0..1023
                                                                       // 8 MSBs from baseline register, shifted left 2
            val oor = mpr.oorStatus()                                      // Read OOR bitmask, () → Int bitmask
                                                                       // bits 0..11 = ELE0..11 out-of-range
            val pt = mpr.proximityTouched()                                // Read proximity touched, () → Boolean
                                                                       // false: ELEPROX not enabled in this example
            println("t=0x%03X f0=%d b0=%d oor=0x%04X pt=%s".format(t, f0, b0, oor, pt))
        }
        mpr.enableInterrupt(Mpr121Full.SOURCE_OOR)                        // Enable interrupt source, (source=SOURCE_OOR) → Unit
                                                                       // OOR will now assert INT as well as touch/release
        mpr.disableInterrupt(Mpr121Full.SOURCE_OOR)                      // Disable interrupt source, (source=SOURCE_OOR) → Unit
                                                                       // OOR no longer asserts INT
        mpr.clearOvercurrent()                                            // Clear OVCF, () → Unit
                                                                       // safe no-op when overcurrent has not occurred
        mpr.reset()                                                       // Soft reset, () → Unit
                                                                       // reapplies Minimal defaults; device ready for fresh use
    }
}
