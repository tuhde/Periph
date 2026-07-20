///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.environmental.Aht21Full;

/**
 * Weather station logger demo: read temperature and humidity every 5 seconds
 * for 60 samples, compute dew point using the Magnus formula, and verify
 * each reading with CRC-8.
 */
public class Demo {

    private static final int    SAMPLES     = 60;
    private static final long   INTERVAL_MS = 5000;

    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x38)) {            // open I²C bus 1, device 0x38, (bus, address) → I2CTransport
            var aht = new Aht21Full(transport);                           // construct driver, (transport) → Aht21Full

            // --- Verify calibration before starting the logging session ---
            // Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
            // the driver already sent the calibration init sequence during construction.
            System.out.println("Calibrated: " + aht.isCalibrated());      // check calibration status, () → boolean

            System.out.printf("%-8s %-10s %-10s %-10s%n", "Time", "T (°C)", "RH (%)", "Dew (°C)");

            for (int i = 0; i < SAMPLES; i++) {
                // --- Each reading requires an 80 ms measurement cycle ---
                // The sensor cannot output data faster than this; the driver
                // handles the trigger + wait internally.
                double[] rc = aht.readWithCrc();                          // read with CRC verification, () → double[] {temperature_c, humidity_pct, crc_ok}
                if (rc[2] < 0.5) {
                    System.out.println("CRC error at sample " + i);
                    Thread.sleep(INTERVAL_MS);
                    continue;
                }

                double t  = rc[0];
                double rh = rc[1];

                // --- Magnus formula dew-point approximation ---
                // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
                // dew_point = (243.04 * gamma) / (17.625 - gamma)
                // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
                double gamma = Math.log(rh / 100.0) + (17.625 * t) / (243.04 + t);
                double dew   = (243.04 * gamma) / (17.625 - gamma);

                System.out.printf("%-8d %-10.2f %-10.2f %-10.2f%n", i, t, rh, dew);
                Thread.sleep(INTERVAL_MS);
            }
        }
    }
}
