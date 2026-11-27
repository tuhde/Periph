///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Si1145Full;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si1145Full(connection);                                                // Create Si1145 driver, (connection) → Si1145Full
            // Fixed address 0x60 (older silicon: 0x5A); the constructor does no bus traffic.
            sensor.init();                                                                          // Check PART_ID, HW_KEY, UV + ALS + PS1, () → void
            // Waits 25 ms, verifies PART_ID 0x45/0x46/0x47, writes HW_KEY 0x17, UCOEF, CHLIST 0xB1, LED1 level 3.

            System.out.println(sensor.readId());                                                    // Identity registers, () → Id {partId, revId, seqId}
            // PART_ID 0x45 for the Si1145; SEQ_ID 0x01 parts swap the MEAS_RATE bytes.
            System.out.println(sensor.readResponse());                                              // RESPONSE decoded, () → Response {counter, error}
            // 4-bit command counter and the error code (0 = none, 0x8x = ADC overflow / invalid setting).

            System.out.println(sensor.read());                                                      // Forced PS + ALS, () → Reading {proximity, uvIndex, visible, infrared}
            // PSALS_FORCE, waits for ALS_INT and PS1_INT; raw counts and the UV index (AUX_DATA / 100).
            System.out.println(sensor.readProximity(1));                                            // Forced PS, (channel=1) → int counts
            // PS1 only; PS2/PS3 exist on the Si1146/47.
            System.out.println(sensor.readUvIndex());                                               // Forced ALS, () → double UV index
            // UV index with the default UV coefficients 7B 6B 01 00.
            System.out.println(sensor.readVisible());                                               // Forced ALS, () → int counts
            // Raw visible ambient light.
            System.out.println(sensor.readInfrared());                                              // Forced ALS, () → int counts
            // Raw infrared ambient light.
            System.out.println(sensor.readRaw());                                                   // Data registers, () → Raw {visible, infrared, ps1, ps2, ps3, aux}
            // No new measurement; the latest values of 0x22–0x2D.
            System.out.println(sensor.readTemperatureRaw());                                        // AUX temperature, () → int counts
            // Relative die temperature; CHLIST is switched to EN_AUX and restored.
            System.out.println(sensor.readVddRaw());                                                // AUX VDD, () → int counts
            // Supply voltage on the auxiliary channel.

            sensor.setChannels(true, true, true, true, false, false, false);                        // CHLIST, (uv, visible, infrared, ps1, ps2, ps3, aux) → void
            // UV and AUX share AUX_DATA, so only one of them can be enabled.
            sensor.setLedCurrent(1, 8);                                                             // LED drive, (led, level) → void
            // Level 0 = off … 15 = maximum (~359 mA); keep the gain low at high currents.
            sensor.setPsLed(1, 0x01);                                                               // PS LED select, (channel, leds) → void
            // PS1 drives LED1; the Si1145 has no LED2/LED3.
            sensor.setPsPhotodiode(1, true);                                                        // PS photodiode, (channel, large) → void
            // Large IR photodiode (0x03); false selects the small one.
            sensor.setGain(0, 0, 0);                                                                // ADC gains, (visible, infrared, proximity) → void
            // Integration time × 2^n, recovery counts at the one's complement of the gain.
            sensor.setHighRange(false, false, false);                                               // Signal range, (visible, infrared, proximity) → void
            // true divides the sensitivity by 14.5 (direct sunlight).
            sensor.setAlignment(false, false, false, false, false);                                 // Result alignment, (visible, infrared, ps1, ps2, ps3) → void
            // true reports the 16 LSBs instead of the 16 MSBs of the ADC result.
            sensor.setUvCoefficients(Si1145Full.UCOEF_DEFAULT);                                     // UCOEF0..3, (ucoef int[4]) → void
            // 7B 6B 01 00 make AUX_DATA the UV index × 100.
            System.out.println(Arrays.toString(sensor.readCalibration()));                          // GET_CAL, () → int[12]
            // Factory calibration bytes; overwrites the data registers.

            sensor.pollInterrupt();                                                                 // IRQ_STATUS, () → int sources
            // Reads and clears pending flags.
            sensor.startAutonomous(0.1, true, true);                                                // Autonomous mode, (intervalS, proximity=true, als=true) → void
            // MEAS_RATE = 0.1 s / 31.25 µs = 3200, then PSALS_AUTO.
            Thread.sleep(500);
            System.out.printf("sources 0x%02X%n", sensor.pollInterrupt());                          // IRQ_STATUS, () → int sources
            // SOURCE_ALS | SOURCE_PS1 when both finished at least once.
            sensor.pause();                                                                         // PSALS_PAUSE, () → void
            // Stops the autonomous loop.
            sensor.force(true, false);                                                              // Forced measurement without waiting, (proximity=true, als=true) → void
            // PS_FORCE; the result arrives when IRQ_STATUS flags PS1.
            Thread.sleep(50);

            List<Integer> events = Collections.synchronizedList(new ArrayList<>());
            sensor.enableInterrupt(Si1145Full.SOURCE_ALS | Si1145Full.SOURCE_PS1);                  // INT_CFG + IRQ_ENABLE, (source) → void
            // INT pin (active low) driven for ALS and PS1 completions.
            sensor.onInterrupt(events::add);                                                        // Subscribe, (callback, intPin=connection.intPin()) → void
            // Falling edge on the connection's INT pin, otherwise a 10 ms polling thread.
            sensor.force();                                                                         // PSALS_FORCE without waiting, () → void
            Thread.sleep(200);
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
            System.out.println(events);
            sensor.disableInterrupt(Si1145Full.SOURCE_CMD);                                         // IRQ_ENABLE clear, (source) → void
            // CMD_INT is not used by default.

            sensor.setI2cAddress(0x60);                                                             // I2C_ADDR + BUSADDR, (address) → void
            // Volatile; here the address stays 0x60, otherwise reconnect on the new address.
            sensor.reset();                                                                         // RESET + HW_KEY + defaults, () → void
            // Software reset, 10 ms wait, then the Minimal configuration again.
        }
    }
}
