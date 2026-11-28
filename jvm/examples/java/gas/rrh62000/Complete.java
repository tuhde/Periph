///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gas.Rrh62000Full;

import java.util.Arrays;
import java.util.HexFormat;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x69").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            // The module has the fixed address 0x69; SEL (pin 5) must be at GND for I²C.
            var sensor = new Rrh62000Full(connection);                                              // Create RRH62000 driver, (connection, accessIntervalMs=1000) → Rrh62000Full
            // Starts the start-up timers: I²C after 1 s, valid data after 3.3 s.
            sensor.init();                                                                          // Wait for start-up, check firmware version, () → void
            // Reads the firmware version as a presence check.

            System.out.println(Arrays.toString(sensor.firmwareVersion()));                          // Firmware version, () → int[] {major, minor}
            // Every access is at least 1 s after the previous one (tRD); the driver waits.
            System.out.println(Arrays.toString(sensor.algorithmVersion()));                         // TVOC algorithm version, () → int[] {major, minor, patch}
            // Version of the ZMOD4410 algorithm in the module.
            System.out.println(HexFormat.of().formatHex(sensor.uniqueId()));                        // Unique ID, () → byte[6]
            // Identifies the individual module.

            System.out.println(sensor.read());                                                      // Main values, () → Measurement
            // Waits for DATA, reads the 37-byte block, checks the CRC; PM uses the KCl reference.
            System.out.println(sensor.readAll());                                                   // Complete block, () → Data
            // Status, 5 number-concentration bins (cm⁻³), PM KCl and smoke, T, RH, TVOC, eCO₂, IAQ.
            System.out.println(sensor.readStatus());                                                // Status flags, () → Status {raw, highConcentration, dustAccumulation, fanSpeed, fan}
            // Reads the current block without waiting for new data.
            System.out.println(sensor.readParticles());                                             // Particle values, () → Particles
            // PM1/2.5/10 (KCl and cigarette smoke, µg/m³) and the number bins (cm⁻³).
            System.out.println(sensor.dataReady());                                                 // New sample available, () → boolean
            // DATA (0x40); a sample arrives every 3 s.

            sensor.setMovingAverage(20);                                                            // Moving average, (count 1–60) → void
            // More averages: smoother values, slower response (24 s at 10).
            System.out.println(sensor.getMovingAverage());                                          // Moving average, () → int
            // 20.
            sensor.setFanSpeed(80);                                                                 // Fan speed, (percent 60–100) → void
            // Lower speed is quieter; the default is 86 %.
            System.out.println(sensor.getFanSpeed());                                               // Fan speed, () → int %
            // 80.
            sensor.setCleaningInterval(3 * 24 * 3600);                                              // Cleaning interval, (seconds, multiple of 30) → void
            // Written as a 16-bit count of 30 s units, high byte first.
            System.out.println(sensor.getCleaningInterval());                                       // Cleaning interval, () → int s
            // 259200.
            sensor.setCleaningTime(15);                                                             // Cleaning time, (seconds 0–60) → void
            // How long the fan runs at full speed during the automatic cleaning.
            System.out.println(sensor.getCleaningTime());                                           // Cleaning time, () → int s
            // 15.

            sensor.startCleaning();                                                                 // Start fan cleaning, () → void
            // Blows dust out of the air channel.
            Thread.sleep(15000);
            System.out.println(sensor.cleaningDone());                                              // Cleaning status, () → boolean
            // CSTATUS (0x74): true when completed.
            System.out.println(sensor.readMoxResistance());                                         // MOX resistance, () → long Ω
            // Raw resistance of the TVOC sensing element.

            sensor.sleep();                                                                         // Sleep, () → void
            // Stops the fan and the measurement.
            Thread.sleep(5000);
            sensor.wake();                                                                          // Wake up, () → void
            // The next access waits 1 s, the next measurement 3.3 s.
            sensor.softReset();                                                                     // Reset, () → void
            // Same as power-on: restarts the start-up timers.
            System.out.println(sensor.read());                                                      // Main values, () → Measurement
            // First sample after the reset.
        }
    }
}
