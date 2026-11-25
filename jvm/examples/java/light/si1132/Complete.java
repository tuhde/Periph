///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Si1132Full;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si1132Full(connection);                                                          // Create Si1132 driver, (connection) → void

            double[] m = sensor.read();                                                                       // Read UV index and ALS counts, () → double[] {UV index, visible, infrared}
                                                                                                              // forced measurement of the enabled channels
            System.out.printf("UV %.2f, visible %.0f, infrared %.0f%n", m[0], m[1], m[2]);
            System.out.println("uv index " + sensor.readUvIndex());                                           // Read UV index, () → double
                                                                                                              // forced measurement, AUX_DATA / 100
            System.out.println("visible " + sensor.readVisible());                                            // Read visible counts, () → int
                                                                                                              // forced measurement, 16-bit raw count
            System.out.println("infrared " + sensor.readInfrared());                                          // Read infrared counts, () → int
                                                                                                              // forced measurement, 16-bit raw count
            int[] raw = sensor.readRaw();                                                                     // Read data registers, () → int[] {visible, infrared, aux}
                                                                                                              // latest values, no measurement is started
            System.out.println("raw " + raw[0] + " " + raw[1] + " " + raw[2]);
            System.out.println("temperature raw " + sensor.readTemperatureRaw());                             // Read die temperature, () → int
                                                                                                              // auxiliary channel, relative only
            System.out.println("vdd raw " + sensor.readVddRaw());                                             // Read supply voltage, () → int
                                                                                                              // auxiliary channel with AUX_ADCMUX = 0x75
            sensor.setChannels(true, true, true, false);                                                      // Select channels, (uv, visible, infrared, aux) → void
                                                                                                              // writes CHLIST; uv and aux cannot be combined
            sensor.setGain(2, 2);                                                                             // Set ADC gain, (visible 0–7, infrared 0–7) → void
                                                                                                              // integration time x 2^n plus the recommended recovery count
            sensor.setGain(0, 0);
            sensor.setHighRange(false, false);                                                                // Set high signal range, (visible, infrared) → void
                                                                                                              // true divides the sensitivity by 14.5 (direct sunlight)
            sensor.setAlignment(true, true);                                                                  // Set data alignment, (visibleLow, infraredLow) → void
                                                                                                              // true reports the least significant 16 bits
            sensor.setUvCoefficients(new int[]{0x7B, 0x6B, 0x01, 0x00});                                      // Set UV coefficients, (ucoef int[4]) → void
                                                                                                              // datasheet defaults for an open-air UV index
            int[] cal = sensor.readCalibration();                                                             // Read factory calibration, () → int[12]
                                                                                                              // GET_CAL then registers 0x22–0x2D
            System.out.println("calibration " + cal.length + " bytes, first 0x" + Integer.toHexString(cal[0]));
            sensor.force();                                                                                   // Start a forced measurement, () → void
                                                                                                              // ALS_FORCE without waiting
            Thread.sleep(50);
            System.out.println("ready " + sensor.measurementReady());                                         // Check measurement done, () → boolean
                                                                                                              // IRQ_STATUS.ALS_INT
            int[] resp = sensor.readResponse();                                                               // Read command response, () → int[] {counter, error}
                                                                                                              // RESPONSE decoded, error 0 = none
            System.out.println("response " + resp[0] + " error " + resp[1]);
            System.out.println("poll " + sensor.pollInterrupt());                                             // Poll interrupt status, () → int
                                                                                                              // reads IRQ_STATUS and clears it
            sensor.startAutonomous(0.5);                                                                      // Start autonomous mode, (interval 0–2.05 s) → void
                                                                                                              // MEAS_RATE in 31.25 µs steps then ALS_AUTO
            sensor.onInterrupt(v -> System.out.println("INT status 0x" + Integer.toHexString(v)));            // Subscribe to measurements, (callback) → void
                                                                                                              // falling edge on INT, else polling
            Thread.sleep(2000);
            sensor.offInterrupt();                                                                            // Unsubscribe, () → void
            sensor.pause();                                                                                   // Pause autonomous mode, () → void
                                                                                                              // ALS_PAUSE
            sensor.setI2cAddress(0x60);                                                                       // Set I²C address, (address 7-bit) → void
                                                                                                              // I2C_ADDR parameter then BUSADDR, volatile
            sensor.reset();                                                                                   // Reset the chip, () → void
                                                                                                              // RESET, waits, writes HW_KEY again
        }
    }
}
