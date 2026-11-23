///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.environmental.Aht20Full;

/**
 * Weather station logger demo: read temperature and humidity every 5 seconds
 * for 60 samples, compute dew point using the Magnus formula, and verify
 * each reading.
 */
public class Demo {

    private static final int    SAMPLES     = 60;
    private static final long   INTERVAL_MS = 5000;

    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x38)) {            // open I²C bus 1, device 0x38, (bus, address) → I2CConnection
            var aht = new Aht20Full(connection);                           // construct driver, (connection) → Aht20Full

            // --- Verify calibration before starting the logging session ---
            // Most AHT20 modules ship pre-calibrated; if the CAL bit is not set
            // the driver already sent the calibration init sequence during construction.
            System.out.println("Calibrated: " + aht.isCalibrated());      // check calibration status, () → boolean

            System.out.printf("%-8s %-10s %-10s %-10s%n", "Time", "T (°C)", "RH (%)", "Dew (°C)");

            for (int i = 0; i < SAMPLES; i++) {
                // --- Each reading requires an 80 ms measurement cycle ---
                // The sensor cannot output data faster than this; the driver
                // handles the trigger + wait internally.
                double[] rc = aht.read();                                 // trigger measurement, () → double[] {temperature_c, humidity_pct}

                double t  = rc[0];
                double rh = rc[1];

                // --- Magnus formula dew-point approximation ---
                // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
                // dew_point = (243.04 * gamma) / (17.625 - gamma)
                // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
                double gamma = Math.log(rh / 100.0) + (17.625 * t) / (243.04 + t);
                double dew   = (243.04 * gamma) / (17.625 - gamma);

                System.out.printf("%-8d %-10.2f %-10.2f %-10.2f%n", i, t, rh, dew);

                // --- Every 10th sample, verify the transfer with the CRC-8 ---
                // The 7th byte protects bytes 0-5; a bad CRC means the I²C line is noisy.
                if (i % 10 == 9) {
                    double[] crc = aht.readWithCrc();                     // read with CRC check, () → double[] {temperature_c, humidity_pct, crc_ok (1.0/0.0)}
                    System.out.printf("CRC ok: %b%n", crc[2] == 1.0);
                }
                Thread.sleep(INTERVAL_MS);
            }
        }
    }
}
