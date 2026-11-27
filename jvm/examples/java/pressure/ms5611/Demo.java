///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Ms5611Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Ms5611Full(connection);                                                                // Create MS5611 driver, (connection) → Ms5611Full
            sensor.init();                                                                                          // Reset, read PROM, verify CRC-4, () → void

            // --- Calibration data ---
            // Six coefficients and a CRC-4 are factory-programmed into the PROM.
            System.out.println("PROM " + java.util.Arrays.toString(sensor.readProm()));                             // Read the PROM words, () → int[8]
            System.out.println("CRC ok " + sensor.promCrcOk());                                                     // Verify the PROM CRC-4, () → boolean
            System.out.println("coefficients " + java.util.Arrays.toString(sensor.coefficients()) + " serial " + sensor.serialCode());

            // --- Barometric altimeter / variometer: 20 s, one reading per second ---
            double reference = sensor.readPressure();                                                               // Read pressure only, () → double Pa
            double previous = 0.0;
            double lowest = 100.0;
            for (int i = 0; i < 20; i++) {
                double[] m = sensor.read();                                                                         // Read temperature and pressure, () → double[] {°C, Pa}
                lowest = Math.min(lowest, m[0]);
                double height = sensor.altitude(reference);                                                         // Altitude from pressure, (seaLevelPa) → double m
                double climb = i == 0 ? 0.0 : height - previous;
                previous = height;
                System.out.printf("%.2f C  %.1f Pa  %.2f m  %+.2f m/s%n", m[0], m[1], height, climb);
                Thread.sleep(1000);
            }

            // --- Noise and time versus oversampling: ten readings each ---
            for (int osr : new int[]{256, 512, 1024, 2048, 4096}) {
                sensor.setOversampling(osr);                                                                        // Select oversampling ratio, (osr) → void
                long start = System.nanoTime();
                double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                for (int n = 0; n < 10; n++) {
                    double v = sensor.readPressure();
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
                System.out.printf("OSR %4d  %.1f ms/read  spread %.1f Pa%n", osr, (System.nanoTime() - start) / 1.0e7, hi - lo);
            }
            sensor.setOversampling(4096);

            // --- Raw codes with and without the second-order correction ---
            int[] raw = sensor.readRaw();                                                                           // Read raw ADC codes, () → int[] {d1, d2}
            System.out.println("raw " + raw[0] + " " + raw[1]);
            System.out.printf("first order  %.2f C %.1f Pa%n", sensor.compensate(raw[0], raw[1], false)[0], sensor.compensate(raw[0], raw[1], false)[1]); // Compensate raw codes, (d1, d2, secondOrder) → double[] {°C, Pa}
            System.out.printf("second order %.2f C %.1f Pa%n", sensor.compensate(raw[0], raw[1])[0], sensor.compensate(raw[0], raw[1])[1]);
            System.out.printf("lowest temperature seen %.2f C%n", lowest);

            // --- Non-blocking conversion, then reset ---
            sensor.startConversion(Ms5611Full.PRESSURE);                                                            // Start a conversion, (kind[, osr]) → void
            Thread.sleep(12);
            System.out.println("D1 " + sensor.readAdc());                                                           // Read the ADC result, () → int
            sensor.reset();                                                                                         // Reset and reload PROM, () → void
        }
    }
}
