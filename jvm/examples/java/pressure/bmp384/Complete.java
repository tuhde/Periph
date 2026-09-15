///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Bmp384Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x76)) {       // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CConnection
            var sensor = new Bmp384Full(connection);                    // construct full driver, (connection) → Bmp384Full

            sensor.configure(4, 1, 2, 0x03);                            // configure oversampling/IIR/ODR, (osrP 0–5, osrT 0–5, iirFilter 0–7, odrSel 0x00–0x11) → void
                                                                            // writes OSR, CONFIG, ODR
            sensor.setMode(Bmp384Full.MODE_NORMAL);                     // set power mode, (mode) → void
            boolean ready = sensor.isDataReady();                       // check data-ready flag, () → boolean
                                                                            // true if STATUS.drdy_press is set
            double t = sensor.temperature();                            // read temperature, () → double °C
            double p = sensor.pressure();                               // read pressure, () → double hPa
            double[] reading = sensor.read();                            // read both values in one burst, () → double[pressure_hPa, temperature_C]
            double[] forced = sensor.readForced();                      // trigger forced measurement and read, () → double[pressure_hPa, temperature_C]
            sensor.fifoConfigure(true, true, 64, false);                // configure FIFO, (pressEn bool, tempEn bool, wtm int, stopOnFull bool) → void
                                                                            // enables FIFO, sets watermark, arms pressure+temperature frames
            var frames = sensor.fifoRead();                              // read and parse FIFO frames, () → FifoFrame[]
            sensor.fifoFlush();                                          // flush FIFO contents, () → void
            double alt = sensor.altitude();                              // compute altitude, (seaLevelHpa=1013.25) → double m
                                                                            // uses barometric formula to convert pressure to metres
            sensor.softreset();                                          // soft reset chip, () → void
                                                                            // writes 0xB6 to CMD, waits 2 ms, re-reads calibration

            System.out.printf("T=%.1f C, P=%.1f hPa, ready=%b, frames=%d, alt=%.1f m%n",
                t, p, ready, frames.length, alt);
        }
    }
}
