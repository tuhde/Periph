///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp384Full

fun main() {
    I2CConnection(1, 0x76).use { connection ->              // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CConnection
        val sensor = Bmp384Full(connection)                  // construct full driver, (connection) → Bmp384Full

        sensor.configure(4, 1, 2, 0x03)                      // configure oversampling/IIR/ODR, (osrP 0–5, osrT 0–5, iirFilter 0–7, odrSel 0x00–0x11) → Unit
                                                                // writes OSR, CONFIG, ODR
        sensor.setMode(Bmp384Full.MODE_NORMAL)               // set power mode, (mode) → Unit
        val ready = sensor.isDataReady()                     // check data-ready flag, () → Boolean
                                                                // true if STATUS.drdy_press is set
        val t = sensor.temperature()                          // read temperature, () → Double °C
        val p = sensor.pressure()                             // read pressure, () → Double hPa
        val reading = sensor.read()                            // read both values in one burst, () → DoubleArray
        val forced = sensor.readForced()                      // trigger forced measurement and read, () → DoubleArray
        sensor.fifoConfigure(true, true, 64, false)          // configure FIFO, (pressEn bool, tempEn bool, wtm Int, stopOnFull bool) → Unit
                                                                // enables FIFO, sets watermark, arms pressure+temperature frames
        val frames = sensor.fifoRead()                        // read and parse FIFO frames, () → List<FifoFrame>
        sensor.fifoFlush()                                    // flush FIFO contents, () → Unit
        val alt = sensor.altitude()                            // compute altitude, (seaLevelHpa=1013.25) → Double m
                                                                // uses barometric formula to convert pressure to metres
        sensor.softreset()                                    // soft reset chip, () → Unit
                                                                // writes 0xB6 to CMD, waits 2 ms, re-reads calibration

        println("T=${"%.1f".format(t)} C, P=${"%.1f".format(p)} hPa, ready=$ready, frames=${frames.size}, alt=${"%.1f".format(alt)} m")
    }
}
