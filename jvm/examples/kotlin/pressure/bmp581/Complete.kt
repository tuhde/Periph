///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp581Full

fun main() {
    I2CConnection(1, 0x46).use { connection ->                  // open I²C bus 1, device 0x46, (bus, address=0x46) → I2CConnection
        val bmp = Bmp581Full(connection)                            // construct driver, (connection) → Bmp581Full
        val cid = bmp.chipId()                                      // read chip ID, () → Int
        println("chip_id=0x%02x (expect 0x50)".format(cid))

        bmp.configure(0x1C, Bmp581Full.OSR_1X, Bmp581Full.OSR_1X, true) // configure, (odr, osr_p, osr_t, press_en) → Unit
        bmp.setMode(Bmp581Full.MODE_NORMAL)                         // set power mode, (mode 0/1/2/3) → Unit
        bmp.setIirFilter(Bmp581Full.IIR_COEFF_3, Bmp581Full.IIR_BYPASS) // set IIR filter, (coeff_p 0–7, coeff_t 0–7) → Unit
        bmp.configureFifo(Bmp581Full.FIFO_BOTH, Bmp581Full.FIFO_STREAM, 8) // configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → Unit
        val n = bmp.fifoCount()                                     // read FIFO frame count, () → Int
        bmp.enableDrdyInterrupt(true)                               // enable data-ready interrupt, (enable) → Unit
        val drdy = bmp.dataReady()                                  // check data ready, () → Boolean
        val forced = bmp.forced()                                   // trigger FORCED measurement, () → Pair<Double, Double>
        val both = bmp.both()                                       // read both atomically, () → Pair<Double, Double>
        val alt = bmp.altitude()                                     // compute altitude, (seaLevelPa=101325.0) → Double
        val st = bmp.status()                                       // read STATUS, () → Int
        val ist = bmp.interruptStatus()                             // read INT_STATUS, () → Int
        val eff = bmp.effectiveOsr()                                // read effective OSR, () → Pair<Int, Int>
        bmp.setOorThreshold(110000.0, 200.0, 1)                      // set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → Unit
        bmp.softwareReset()                                         // soft reset chip, () → Unit

        println("P=%.1f Pa  T=%.2f C  alt=%.1f m  frames=%d  drdy=%s  eff=(%d,%d)  status=0x%02x  isr=0x%02x"
            .format(both.first, both.second, alt, n, drdy, eff.first, eff.second, st, ist))
        println("forced: P=%.1f Pa  T=%.2f C".format(forced.first, forced.second))
    }
}