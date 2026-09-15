///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Bmp384Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x76)) {       // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CConnection
            var sensor = new Bmp384Full(connection);                    // construct full driver, (connection) → Bmp384Full

            // --- Configure for noise-sensitive altitude logging ---
            // osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
            // coefficient 3 suppresses door-slam / gust spikes without too much step lag.
            // ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
            sensor.configure(4, 1, 2, 0x03);                            // configure oversampling/IIR/ODR, (osrP 0–5, osrT 0–5, iirFilter 0–7, odrSel 0x00–0x11) → void
            sensor.setMode(Bmp384Full.MODE_NORMAL);                     // set power mode, (mode) → void

            // --- Sample for 30 seconds, logging altitude every 500 ms ---
            // P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
            final double SEA_LEVEL_HPA = 1013.25;
            long start = System.currentTimeMillis();
            long next = start;
            int rows = 0;
            while (System.currentTimeMillis() - start < 30_000) {
                long now = System.currentTimeMillis();
                if (now >= next) {
                    double t = sensor.temperature();                    // read temperature, () → double °C
                    double p = sensor.pressure();                       // read pressure, () → double hPa
                    double altitude = 44330.0 * (1.0 - Math.pow(p / SEA_LEVEL_HPA, 1.0 / 5.255));
                    double elapsed = (now - start) / 1000.0;
                    System.out.printf("%.1fs  %.2f hPa  %.1f C  %.1f m%n",
                        elapsed, p, t, altitude);
                    rows++;
                    next += 500;
                }
                Thread.sleep(50);
            }
            System.out.printf("Sampled %d rows over 30 s%n", rows);
        }
    }
}
