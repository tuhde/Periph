///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp384Full

def connection = new I2CConnection(1, (byte) 0x76)        // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CConnection
try {
    def sensor = new Bmp384Full(connection)                   // construct full driver, (connection) → Bmp384Full

    sensor.configure(4, 1, 2, 0x03)                           // configure oversampling/IIR/ODR, (osrP 0–5, osrT 0–5, iirFilter 0–7, odrSel 0x00–0x11) → void
                                                                  // writes OSR, CONFIG, ODR
    sensor.setMode(Bmp384Full.MODE_NORMAL)                    // set power mode, (mode) → void
    def ready = sensor.isDataReady()                          // check data-ready flag, () → boolean
                                                                  // true if STATUS.drdy_press is set
    def t = sensor.temperature()                              // read temperature, () → double °C
    def p = sensor.pressure()                                 // read pressure, () → double hPa
    def reading = sensor.read()                                // read both values in one burst, () → double[]
    def forced = sensor.readForced()                           // trigger forced measurement and read, () → double[]
    sensor.fifoConfigure(true, true, 64, false)               // configure FIFO, (pressEn bool, tempEn bool, wtm int, stopOnFull bool) → void
                                                                  // enables FIFO, sets watermark, arms pressure+temperature frames
    def frames = sensor.fifoRead()                             // read and parse FIFO frames, () → FifoFrame[]
    sensor.fifoFlush()                                         // flush FIFO contents, () → void
    def alt = sensor.altitude()                                // compute altitude, (seaLevelHpa=1013.25) → double m
                                                                  // uses barometric formula to convert pressure to metres
    sensor.softreset()                                         // soft reset chip, () → void
                                                                  // writes 0xB6 to CMD, waits 2 ms, re-reads calibration

    printf("T=%.1f C, P=%.1f hPa, ready=%s, frames=%d, alt=%.1f m%n", t, p, ready, frames.length, alt)
} finally {
    connection.close()
}
