///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Si1145Full;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si1145Full(connection);                                                // Create Si1145 driver, (connection) → Si1145Full
            sensor.init();                                                                          // Check PART_ID, HW_KEY, UV + ALS + PS1, () → void

            // --- Identity and calibration ---
            // PART_ID tells the Si1145/46/47 apart (one, two or three LEDs); GET_CAL returns the
            // factory calibration bytes.
            var id = sensor.readId();                                                               // Identity registers, () → Id
            System.out.printf("PART_ID 0x%02X  REV_ID 0x%02X  SEQ_ID 0x%02X%n", id.partId(), id.revId(), id.seqId());
            System.out.println("calibration " + Arrays.toString(sensor.readCalibration()));         // GET_CAL, () → int[12]
            System.out.println(sensor.readResponse());                                              // RESPONSE, () → Response

            // --- Proximity, UV index and light for 10 s ---
            // Proximity counts rise as a hand approaches the LED/sensor window.
            for (int i = 0; i < 10; i++) {
                var m = sensor.read();                                                              // Forced PS + ALS, () → Reading
                System.out.printf("%2d s  prox %5d  UV %5.2f  vis %5d  ir %5d%n", i, m.proximity(), m.uvIndex(), m.visible(), m.infrared());
                Thread.sleep(1000);
            }

            // --- LED current and photodiode ---
            // Hold a hand ~5 cm above the sensor: higher LED current gives more counts, the small
            // photodiode a fraction of the large one.
            System.out.println("Hold a hand above the sensor");
            Thread.sleep(3000);
            for (int level : new int[]{1, 4, 8, 15}) {
                sensor.setLedCurrent(1, level);                                                     // LED drive, (led, level) → void
                System.out.printf("LED level %2d -> %d%n", level, sensor.readProximity());          // Forced PS, () → int counts
            }
            sensor.setLedCurrent(1, 3);
            for (boolean large : new boolean[]{false, true}) {
                sensor.setPsPhotodiode(1, large);                                                   // PS photodiode, (channel, large) → void
                System.out.printf("%s photodiode -> %d%n", large ? "large" : "small", sensor.readProximity());
            }
            sensor.setPsLed(1, 0x01);                                                               // PS LED select, (channel, leds) → void

            // --- Gain and high range ---
            // Each gain step doubles the integration time; the high range divides the sensitivity
            // by 14.5 for direct sunlight.
            for (int gain : new int[]{0, 2, 4}) {
                sensor.setGain(gain, gain, 0);                                                      // ADC gains, (visible, infrared, proximity) → void
                System.out.printf("gain %d  vis %d  ir %d%n", gain, sensor.readVisible(), sensor.readInfrared()); // Forced ALS, () → int counts
            }
            sensor.setGain(0, 0, 0);
            sensor.setHighRange(true, true, false);                                                 // Signal range, (visible, infrared, proximity) → void
            System.out.printf("high range  vis %d  UV %.2f%n", sensor.readVisible(), sensor.readUvIndex()); // Forced ALS, () → int / double
            sensor.setHighRange(false, false, false);
            sensor.setAlignment(false, false, false, false, false);                                 // Result alignment, (...) → void
            sensor.setUvCoefficients(Si1145Full.UCOEF_DEFAULT);                                     // UCOEF0..3, (ucoef) → void

            // --- Autonomous mode with interrupt events and a proximity switch ---
            // PS + ALS every 100 ms; each finished PS1 measurement raises INT (or is polled).
            final int threshold = 2000;
            sensor.setChannels(true, true, true, true);                                             // CHLIST, (uv, visible, infrared, ps1) → void
            var events = new AtomicInteger();
            sensor.enableInterrupt(Si1145Full.SOURCE_PS1);                                          // INT_CFG + IRQ_ENABLE, (source) → void
            sensor.onInterrupt(s -> events.incrementAndGet());                                      // Subscribe, (callback) → void
            sensor.startAutonomous(0.1);                                                            // Autonomous mode, (intervalS) → void
            boolean near = false;
            for (int i = 0; i < 50; i++) {
                int ps1 = sensor.readRaw().ps1();                                                   // Data registers, () → Raw
                if ((ps1 > threshold) != near) {
                    near = ps1 > threshold;
                    System.out.printf("output %s (prox %d)%n", near ? "ON" : "OFF", ps1);
                }
                Thread.sleep(100);
            }
            sensor.pause();                                                                         // PSALS_PAUSE, () → void
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
            System.out.println(events.get() + " interrupt events");
            sensor.disableInterrupt(Si1145Full.SOURCE_PS1);                                         // IRQ_ENABLE clear, (source) → void
            sensor.pollInterrupt();                                                                 // IRQ_STATUS, () → int sources

            // --- Auxiliary channels ---
            System.out.printf("temperature raw %d  VDD raw %d%n",
                    sensor.readTemperatureRaw(), sensor.readVddRaw());                              // AUX channel, () → int counts
            sensor.force(false, true);                                                              // ALS_FORCE without waiting, (proximity, als) → void
            Thread.sleep(50);
            System.out.println(sensor.readRaw());                                                   // Data registers, () → Raw

            // --- Address and reset ---
            sensor.setI2cAddress(0x60);                                                             // I2C_ADDR + BUSADDR, (address) → void
            sensor.reset();                                                                         // RESET + HW_KEY + defaults, () → void
            System.out.println("after reset " + sensor.read());
        }
    }
}
