///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps22dfFull

def connection = new I2CConnection(1, 0x5C)            // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
try {
    def lps = new Lps22dfFull(connection)                     // construct driver, verifies chip ID and applies defaults, (connection) → Lps22dfFull
    lps.configure(3, 0, false, 0, true)                       // configure chip, (odr=10 Hz, avg=4, enLpfp=false, lfpfCfg=0, bdu=true) → void
    lps.oneshot()                                                // trigger one-shot conversion, () → void
    double p = lps.pressure()                                    // read pressure, () → double Pa
                                                                       // 24-bit two's complement, 4096 LSB/hPa → Pa
    double t = lps.temperature()                                 // read temperature, () → double °C
                                                                       // 16-bit two's complement, 100 LSB/°C
    double alt = lps.altitude(101325.0)                         // compute altitude, (seaLevelPa=101325.0) → double m
                                                                       // barometric formula
    lps.softwareReset()                                           // reset chip, () → void
    lps.setPressureOffset(-50.0)                                 // set pressure offset, (offsetPa=-50.0) → void
    lps.setPressureThreshold(102000.0)                           // set pressure threshold, (thresholdPa=102000.0) → void
    lps.configureInterrupt(false, false, true, false, true, false, false, false)  // configure interrupt, (intHL, ppOd, drdy, drdyPls, intEn, intFWtm, intFFull, intFOvr) → void
    lps.configurePressureEvent(true, false, false)              // configure pressure event, (phe=true, ple=false, lir=false) → void
    lps.autozero()                                                // capture AUTOZERO reference, () → void
    lps.resetReference()                                          // reset reference, () → void
    double ref = lps.referencePressure()                         // read reference pressure, () → double Pa
    lps.setFifoMode(Lps22dfFull.FIFO_FIFO)                       // set FIFO mode, (mode 0–5) → void
    lps.setFifoWatermark(64)                                      // set FIFO watermark, (level 0–127) → void
    int count = lps.fifoSampleCount()                             // read FIFO sample count, () → int
    double[] samples = new double[128]
    int nRead = lps.readFifo(samples)                            // read FIFO samples, (out: double[]) → int
    int src = lps.interruptSource()                               // read interrupt source, () → int
    printf("T=%.2f C, P=%.0f Pa, alt=%.1f m, ref=%.0f Pa, fifo=%d/%d, src=0x%02X%n", t, p, alt, ref, nRead, count, src)
} finally {
    connection.close()
}