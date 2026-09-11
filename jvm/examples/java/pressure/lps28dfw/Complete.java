///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Lps28dfwFull;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x5C)) {       // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
            var sensor = new Lps28dfwFull(connection);                  // construct driver, (connection) → Lps28dfwFull
            int cid = sensor.chipId();                                  // read chip ID, () → int
            System.out.printf("chip_id=0x%02X%n", cid);                // returns 0xB4 for LPS28DFW
            sensor.configure(Lps28dfwFull.ODR_25_HZ, Lps28dfwFull.AVG_64, Lps28dfwFull.FS_MODE_1, true, Lps28dfwFull.LFPF_ODR_OVER_4);  // configure chip, (odr 0–8, avg 0–7, fs_mode 0/1, lpfEn bool, lpfCfg 0/1) → void
            sensor.setThreshold(1050.0, true, true);                   // set pressure threshold, (threshold_hpa, high, low) → void
            sensor.setOffset(0.5);                                     // set one-point calibration, (offset_hpa) → void
            boolean ready = sensor.isDataReady();                      // check data ready, () → boolean
            double[] vals = sensor.read();                             // read both values, () → double[2] (hPa, °C)
            sensor.softreset();                                         // soft reset, () → void
            sensor.fifoConfigure(Lps28dfwFull.FIFO_FIFO, 16, true);   // configure FIFO, (mode 0–6, wtm 0–127, stopOnWtm bool) → void
            int level = sensor.fifoLevel();                            // FIFO unread count, () → int
            double[] samples = sensor.fifoRead(level);                 // drain FIFO, (count) → double[] hPa
            double[] os = sensor.readOneshot();                        // one-shot read, () → double[2] (hPa, °C)
            double alt = sensor.altitude(1013.25);                     // compute altitude, (sea_level_hpa=1013.25) → double m
            System.out.printf("ready=%b vals=[%.2f,%.2f] fifo=%d os=[%.2f,%.2f] alt=%.1f%n",
                    ready, vals[0], vals[1], level, os[0], os[1], alt);
        }
    }
}