///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Hts221Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5F").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Hts221Full(connection);                                                          // Create HTS221 driver, (connection) → void

            // --- Identify the chip and show the factory calibration ---
            // Every HTS221 stores its own two-point calibration; the driver read it
            // during construction and uses it for every conversion.
            int id = sensor.whoAmI();                                                                         // Read WHO_AM_I, () → int (0xBC)
            System.out.printf("who am i 0x%02X%n", id);
            double[] cal = sensor.readCalibration();                                                          // Read calibration, () → double[]
            System.out.println("calibration " + java.util.Arrays.toString(cal));

            // --- One-shot hygrometer: 10 readings, 2 s apart ---
            // Each read() starts a conversion; the dew point shows what the pair
            // means for condensation risk.
            for (int i = 0; i < 10; i++) {
                double[] v = sensor.read();                                                                   // Read temperature and humidity, () → double[] {°C, %rH}
                double dew = sensor.dewPoint();                                                               // Compute dew point, () → double °C
                System.out.printf("%.2f C  %.1f %%rH  dew %.2f C%n", v[0], v[1], dew);
                int rawH = sensor.readRawHumidity();                                                          // Read raw humidity, () → int
                int rawT = sensor.readRawTemperature();                                                       // Read raw temperature, () → int
                System.out.println("raw " + rawH + " " + rawT);
                double h = sensor.readHumidity();                                                             // Read humidity only, () → double %rH
                double t = sensor.readTemperature();                                                          // Read temperature only, () → double °C
                System.out.printf("h %.1f t %.2f%n", h, t);
                Thread.sleep(2000);
            }

            // --- Continuous sampling at 1 Hz and 12.5 Hz ---
            // dataReady() paces the loop; the sample count per second follows the rate.
            sensor.setBlockDataUpdate(true);                                                                  // Set block data update, (enabled) → void
            for (double hz : new double[]{1.0, 12.5}) {
                sensor.setOutputDataRate(hz);                                                                 // Set output data rate, (hz 0|1|7|12.5) → void
                double got = sensor.getOutputDataRate();                                                      // Get output data rate, () → double Hz
                System.out.println("rate " + got + " Hz");
                int count = 0;
                long end = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < end) {
                    if (sensor.dataReady()) {                                                                 // Check data available, () → boolean
                        sensor.readContinuous();                                                              // Read latest sample, () → double[] {°C, %rH}
                        count++;
                    }
                    Thread.sleep(5);
                }
                System.out.printf("%.1f samples/s%n", count / 3.0);
            }
            sensor.setOutputDataRate(0);                                                                      // Set output data rate, (hz 0|1|7|12.5) → void

            // --- Noise at lowest and highest averaging ---
            // Ten one-shot samples each; the spread of the raw humidity shrinks
            // as the averaging grows.
            for (int[] a : new int[][]{{2, 4}, {256, 512}}) {
                sensor.setAveraging(a[0], a[1]);                                                              // Set averaging, (temperatureSamples, humiditySamples) → void
                int[] avg = sensor.getAveraging();                                                            // Get averaging, () → int[] {temperature samples, humidity samples}
                System.out.println("averaging " + avg[0] + " " + avg[1]);
                int lo = 32767;
                int hi = -32768;
                for (int i = 0; i < 10; i++) {
                    int raw = sensor.readRawHumidity();                                                       // Read raw humidity, () → int
                    if (raw < lo) lo = raw;
                    if (raw > hi) hi = raw;
                }
                System.out.println("raw humidity spread " + (hi - lo));
            }
            sensor.setAveraging(16, 32);                                                                      // Set averaging, (temperatureSamples, humiditySamples) → void

            // --- Heater: drive off condensation for 10 s ---
            // Humidity falls and temperature rises while the heater runs; measure
            // before and after, never during, then let the sensor cool.
            double[] before = sensor.read();                                                                  // Read temperature and humidity, () → double[] {°C, %rH}
            sensor.setHeater(true);                                                                           // Switch the heater, (on) → void
            boolean heating = sensor.isHeaterOn();                                                            // Heater state, () → boolean
            System.out.println("heater on: " + heating);
            Thread.sleep(10000);
            sensor.setHeater(false);                                                                          // Switch the heater, (on) → void
            double[] after = sensor.read();                                                                   // Read temperature and humidity, () → double[] {°C, %rH}
            System.out.printf("before %.2f C %.1f %%rH, after %.2f C %.1f %%rH%n", before[0], before[1], after[0], after[1]);

            // --- Data-ready notification for 5 s ---
            // Uses the DRDY pin when the connection has one, otherwise a polling thread.
            sensor.configureDrdy(true, true, false);                                                          // Configure DRDY pin, (enabled, activeHigh, openDrain) → void
            sensor.setOutputDataRate(1);                                                                      // Set output data rate, (hz 0|1|7|12.5) → void
            sensor.onInterrupt(s -> System.out.printf("DRDY status 0x%02X%n", s));                            // Subscribe to data-ready, (callback IntConsumer) → void
            Thread.sleep(5000);
            sensor.offInterrupt();                                                                            // Unsubscribe, () → void
            int ps = sensor.pollInterrupt();                                                                  // Poll interrupt status, () → int
            int sr = sensor.status();                                                                         // Read STATUS_REG, () → int
            System.out.printf("poll 0x%02X status 0x%02X%n", ps, sr);
            sensor.setOutputDataRate(0);

            // --- Power cycle and reload ---
            sensor.powerDown();                                                                               // Power down, () → void
            sensor.powerUp();                                                                                 // Power up, () → void
            sensor.reboot();                                                                                  // Reload trimming, () → void
            double[] last = sensor.read();
            System.out.printf("done: %.2f C%n", last[0]);
        }
    }
}
