///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Hts221Full;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5F").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Hts221Full(connection);                                                          // Create HTS221 driver, (connection) → void
            // reads the factory calibration and starts one-shot mode

            int id = sensor.whoAmI();                                                                         // Read WHO_AM_I, () → int (0xBC)
            // identifies the chip on the bus
            System.out.printf("who am i 0x%02X%n", id);
            double[] v = sensor.read();                                                                       // Read temperature and humidity, () → double[] {°C, %rH}
            // one-shot conversion, converted with the calibration
            System.out.printf("%.2f C, %.1f %%rH%n", v[0], v[1]);
            double h = sensor.readHumidity();                                                                 // Read humidity only, () → double %rH
            // one-shot conversion, clamped to 0–100
            double t = sensor.readTemperature();                                                              // Read temperature only, () → double °C
            // one-shot conversion
            System.out.printf("humidity %.1f temperature %.2f%n", h, t);
            int rawH = sensor.readRawHumidity();                                                              // Read raw humidity, () → int
            // signed 16-bit ADC output
            int rawT = sensor.readRawTemperature();                                                           // Read raw temperature, () → int
            // signed 16-bit ADC output
            System.out.println("raw " + rawH + " " + rawT);
            double[] cal = sensor.readCalibration();                                                          // Read calibration, () → double[] {H0_rH, H1_rH, T0_degC, T1_degC, H0_T0_OUT, H1_T0_OUT, T0_OUT, T1_OUT}
            // decoded two-point coefficients
            System.out.println("calibration " + java.util.Arrays.toString(cal));
            double dew = sensor.dewPoint();                                                                   // Compute dew point, () → double °C
            // Magnus formula over one measurement
            System.out.printf("dew point %.2f C%n", dew);
            int st = sensor.status();                                                                         // Read STATUS_REG, () → int
            // H_DA = 0x02, T_DA = 0x01
            boolean ready = sensor.dataReady();                                                               // Check data available, () → boolean
            // both H_DA and T_DA set
            System.out.printf("status 0x%02X ready %b%n", st, ready);

            sensor.setOutputDataRate(1);                                                                      // Set output data rate, (hz 0|1|7|12.5) → void
            // continuous sampling at 1 Hz
            double hz = sensor.getOutputDataRate();                                                           // Get output data rate, () → double Hz
            // decodes CTRL_REG1.ODR
            System.out.println("rate " + hz + " Hz");
            Thread.sleep(1200);
            double[] c = sensor.readContinuous();                                                             // Read latest sample, () → double[] {°C, %rH}
            // no conversion is started
            System.out.printf("continuous %.2f C %.1f %%rH%n", c[0], c[1]);
            sensor.setOutputDataRate(0);                                                                      // Set output data rate, (hz 0|1|7|12.5) → void
            // back to one-shot mode

            sensor.setAveraging(32, 64);                                                                      // Set averaging, (temperatureSamples 2–256, humiditySamples 4–512) → void
            // more samples lower the noise and lengthen the conversion
            int[] avg = sensor.getAveraging();                                                                // Get averaging, () → int[] {temperature samples, humidity samples}
            // decodes AV_CONF
            System.out.println("averaging " + avg[0] + " " + avg[1]);
            sensor.setAveraging(16, 32);
            sensor.setBlockDataUpdate(true);                                                                  // Set block data update, (enabled) → void
            // keeps LSB/MSB of one sample consistent

            sensor.setHeater(true);                                                                           // Switch the heater, (on) → void
            // drives off condensation; do not measure while on
            boolean on = sensor.isHeaterOn();                                                                 // Heater state, () → boolean
            // reads CTRL_REG2.HEATER
            System.out.println("heater on: " + on);
            sensor.setHeater(false);

            sensor.configureDrdy(true, true, false);                                                          // Configure DRDY pin, (enabled, activeHigh, openDrain) → void
            // routes data-ready to the DRDY pin
            int ps = sensor.pollInterrupt();                                                                  // Poll interrupt status, () → int
            // STATUS_REG; flags clear when the data is read
            System.out.printf("poll 0x%02X%n", ps);
            sensor.configureDrdy(false, true, false);

            sensor.powerDown();                                                                               // Power down, () → void
            // clears CTRL_REG1.PD
            sensor.powerUp();                                                                                 // Power up, () → void
            // sets CTRL_REG1.PD
            sensor.onInterrupt(s -> System.out.printf("DRDY status 0x%02X%n", s));                            // Subscribe to data-ready, (callback IntConsumer) → void
            // edge on the pin or a polling thread
            Thread.sleep(2000);
            sensor.offInterrupt();                                                                            // Unsubscribe, () → void
            // disables the DRDY output
            sensor.reboot();                                                                                  // Reload trimming, () → void
            // sets BOOT, waits, re-reads the calibration
        }
    }
}
