///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp581Full

def connection = new I2CConnection(1, 0x46)                      // open I²C bus 1, device 0x46, (bus, address=0x46) → I2CConnection
def bmp = new Bmp581Full(connection)                                 // construct driver, (connection) → Bmp581Full
try {
    def cid = bmp.chipId()                                          // read chip ID, () → int
    printf("chip_id=0x%02x (expect 0x50)%n", cid)

    bmp.configure(0x1C, Bmp581Full.OSR_1X, Bmp581Full.OSR_1X, true)  // configure, (odr, osr_p, osr_t, press_en) → void
    bmp.setMode(Bmp581Full.MODE_NORMAL)                              // set power mode, (mode 0/1/2/3) → void
    bmp.setIirFilter(Bmp581Full.IIR_COEFF_3, Bmp581Full.IIR_BYPASS)  // set IIR filter, (coeff_p 0–7, coeff_t 0–7) → void
    bmp.configureFifo(Bmp581Full.FIFO_BOTH, Bmp581Full.FIFO_STREAM, 8) // configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → void
    def n = bmp.fifoCount()                                          // read FIFO frame count, () → int
    bmp.enableDrdyInterrupt(true)                                    // enable data-ready interrupt, (enable) → void
    def drdy = bmp.dataReady()                                       // check data ready, () → boolean
    def forced = bmp.forced()                                        // trigger FORCED measurement, () → double[] {Pa, °C}
    def both = bmp.both()                                            // read both atomically, () → double[] {Pa, °C}
    def alt = bmp.altitude()                                          // compute altitude, (seaLevelPa=101325.0) → double
    def st = bmp.status()                                            // read STATUS, () → int
    def ist = bmp.interruptStatus()                                  // read INT_STATUS, () → int
    def eff = bmp.effectiveOsr()                                     // read effective OSR, () → int[] {osr_p, osr_t}
    bmp.setOorThreshold(110000.0, 200.0, 1)                          // set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → void
    bmp.softwareReset()                                              // soft reset chip, () → void

    printf("P=%.1f Pa  T=%.2f C  alt=%.1f m  frames=%d  drdy=%s  eff=(%d,%d)  status=0x%02x  isr=0x%02x%n",
        both[0], both[1], alt, n, drdy, eff[0], eff[1], st, ist)
    printf("forced: P=%.1f Pa  T=%.2f C%n", forced[0], forced[1])
} finally {
    connection.close()
}