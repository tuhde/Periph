///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.magnetometer.Hmc5883lFull;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x1E)) {            // open I²C bus 1, device 0x1E, (bus, address) → I2CConnection

            var hmc5883l = new Hmc5883lFull(connection);                    // construct driver, (connection) → Hmc5883lFull

            // --- Identification ---
            int[] id = hmc5883l.identify();                               // read ID registers, () → int[3]
            System.out.printf("ID: 0x%02X 0x%02X 0x%02X%n", id[0], id[1], id[2]);
                                                                          // expected: 0x48 0x34 0x33 = "H43"

            // --- Status ---
            int status = hmc5883l.status();                               // read raw status, () → int
            System.out.printf("Status: 0x%02X%n", status);
            boolean dr = hmc5883l.dataReady();                            // check data ready, () → boolean
            System.out.printf("Data ready: %s%n", dr);

            // --- Magnetic field readings ---
            Double[] field = hmc5883l.magneticField();                    // read magnetic field, () → Double[3] T
            System.out.printf("X=%.6f T  Y=%.6f T  Z=%.6f T%n",
                    field[0] != null ? field[0] : Double.NaN,
                    field[1] != null ? field[1] : Double.NaN,
                    field[2] != null ? field[2] : Double.NaN);

            // --- Configuration ---
            hmc5883l.configure(15, 8, 1);                                 // configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → void
            hmc5883l.setGain(2);                                          // set gain, (gain 0-7) → void
            hmc5883l.setMode("single");                                   // set operating mode, ('continuous'|'single'|'idle') → void

            // --- Single-shot measurement ---
            Thread.sleep(6);
            Double[] single = hmc5883l.singleMeasurement();               // single-shot measurement, () → Double[3] T
            System.out.printf("Single: X=%.6f T  Y=%.6f T  Z=%.6f T%n",
                    single[0] != null ? single[0] : Double.NaN,
                    single[1] != null ? single[1] : Double.NaN,
                    single[2] != null ? single[2] : Double.NaN);

            hmc5883l.setMode("continuous");                               // set operating mode, ('continuous'|'single'|'idle') → void

            // --- Self-test ---
            Double[] selfTest = hmc5883l.selfTest(true);                  // self-test with positive bias, (positive=boolean) → Double[3] T
            System.out.printf("Self-test: X=%.6f T  Y=%.6f T  Z=%.6f T%n",
                    selfTest[0] != null ? selfTest[0] : Double.NaN,
                    selfTest[1] != null ? selfTest[1] : Double.NaN,
                    selfTest[2] != null ? selfTest[2] : Double.NaN);
        }
    }
}