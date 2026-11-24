///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.environmental.Bme688Full;

/**
 * Room air probe with parallel mode.
 *
 * Part 1: ten forced-mode readings (T, RH, hPa, Ω, dew point), one per 5 s.
 * Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 °C;
 * repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
 * resistance of every heater step, then back to sleep.
 */
public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x76"));
        try (var connection = new I2CConnection(bus, addr)) {      // open I²C bus, (bus, address=0x76) → I2CConnection
            var sensor = new Bme688Full(connection);                     // construct driver, verifies chip ID, reads calibration, (connection) → Bme688Full
            sensor.configure(Bme688Full.OSRS_X2, Bme688Full.OSRS_X16, Bme688Full.OSRS_X1,
                    Bme688Full.MODE_SLEEP, Bme688Full.FILTER_15);       // configure oversampling, mode, filter, (osrsT, osrsP, osrsH, mode, filter) → void
            sensor.setHeater(320, 150);                                 // configure heater profile 0, (tempC=320 °C, durationMs=150 ms) → void

            System.out.println("--- forced mode ---");
            for (int i = 0; i < 10; i++) {
                double[] all = sensor.readAll();                        // read all four values from one TPHG cycle, () → double[4]
                double gm = Math.log(Math.max(all[2], 0.01) / 100.0) + 17.62 * all[0] / (243.12 + all[0]);
                double dew = 243.12 * gm / (17.62 - gm);                // Magnus dew point, °C
                System.out.printf("%d: %.1f C, %.1f %%RH, %.1f hPa, %.0f Ohm, dew %.1f C%n",
                        i, all[0], all[2], all[1], all[3], dew);
                Thread.sleep(5000);
            }

            System.out.println("--- parallel mode ---");
            sensor.startParallel(new int[]{320, 250, 180}, new int[]{1, 2, 1}, 100); // start parallel mode, (tempsC, repeats, sharedWaitMs) → void
            for (int i = 0; i < 60; i++) {
                for (Bme688Full.Field f : sensor.readParallel()) {      // read the new data fields, () → List<Field>
                    System.out.printf("step %d #%d: %.1f C, %.1f %%RH, %.0f Ohm (valid=%b, stable=%b)%n",
                            f.step, f.subIndex, f.temperature, f.humidity, f.gasResistance, f.gasValid, f.heaterStable);
                }
                Thread.sleep(1000);
            }
            sensor.stopParallel();                                      // back to sleep, () → void
        }
    }
}
