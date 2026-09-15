///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps22dfFull

fun main() {
    I2CConnection(1, 0x5C).use { connection ->                 // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
        val lps = Lps22dfFull(connection)                          // construct driver, verifies chip ID and applies defaults, (connection) → Lps22dfFull
        lps.configure(3, 0, false, 0, true)                       // configure chip, (odr=10 Hz, avg=4, enLpfp=false, lfpfCfg=0, bdu=true) → Unit
        lps.oneshot()                                                // trigger one-shot conversion, () → Unit
        val p = lps.pressure()                                       // read pressure, () → Double Pa
                                                                       // 24-bit two's complement, 4096 LSB/hPa → Pa
        val t = lps.temperature()                                    // read temperature, () → Double °C
                                                                       // 16-bit two's complement, 100 LSB/°C
        val alt = lps.altitude(101325.0)                            // compute altitude, (seaLevelPa=101325.0) → Double m
                                                                       // barometric formula
        lps.softwareReset()                                           // reset chip, () → Unit
        lps.setPressureOffset(-50.0)                                 // set pressure offset, (offsetPa=-50.0) → Unit
        lps.setPressureThreshold(102000.0)                           // set pressure threshold, (thresholdPa=102000.0) → Unit
        lps.configureInterrupt(false, false, true, false, true, false, false, false)  // configure interrupt, (intHL, ppOd, drdy, drdyPls, intEn, intFWtm, intFFull, intFOvr) → Unit
        lps.configurePressureEvent(true, false, false)              // configure pressure event, (phe=true, ple=false, lir=false) → Unit
        lps.autozero()                                                // capture AUTOZERO reference, () → Unit
        lps.resetReference()                                          // reset reference, () → Unit
        val ref = lps.referencePressure()                            // read reference pressure, () → Double Pa
        lps.setFifoMode(Lps22dfFull.FIFO_FIFO)                       // set FIFO mode, (mode 0–5) → Unit
        lps.setFifoWatermark(64)                                      // set FIFO watermark, (level 0–127) → Unit
        val count = lps.fifoSampleCount()                            // read FIFO sample count, () → Int
        val samples = DoubleArray(128)
        val nRead = lps.readFifo(samples)                            // read FIFO samples, (out: DoubleArray) → Int
        val src = lps.interruptSource()                              // read interrupt source, () → Int
        println("T=%.2f C, P=%.0f Pa, alt=%.1f m, ref=%.0f Pa, fifo=%d/%d, src=0x%02X".format(t, p, alt, ref, nRead, count, src))
    }
}