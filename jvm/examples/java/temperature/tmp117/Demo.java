///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.temperature.TMP117Full;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * PT100-replacement cold-chain container thermometer: maximum averaging gives
 * the lowest-noise reading, and the ALERT output fires when the cargo leaves
 * the -25 °C to 8 °C safe transport range; each event is reported with the
 * boundary that tripped. Set REFERENCE_C to a reference thermometer reading to
 * calibrate once and persist the offset to EEPROM.
 */
public class Demo {
    static final int MAX_ALERTS = 10;

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        String reference = System.getenv("REFERENCE_C");

        try (var connection = new I2CConnection(bus, TMP117Full.DEFAULT_ADDRESS)) {       // open I²C bus, device address, (bus, address=0x48) → I2CConnection
            var sensor = new TMP117Full(connection);                                      // construct driver and check identity, (connection) → TMP117Full

            // --- Lowest-noise continuous conversion ---
            // 64-conversion averaging with a 1 s cycle gives the quietest result the
            // chip can deliver — a cold-chain log needs stability, not speed.
            sensor.configure(TMP117Full.Mode.CONTINUOUS, 64, 1.0);                        // Configure conversion, (mode=CONTINUOUS, averaging=8, cycleSeconds=1.0 s) → void

            // --- One-time calibration against a reference thermometer ---
            // The observed error is written to TEMP_OFFSET with the EEPROM unlocked, so
            // the correction survives power cycles. EEPROM endurance is limited — this
            // is a once-per-deployment step, not a loop.
            if (reference != null) {
                Thread.sleep(1100);
                double offset = Double.parseDouble(reference) - sensor.readTemperature()  // Read temperature, () → double °C
                        + sensor.getTemperatureOffset();                                  // Read calibration offset, () → double °C
                sensor.unlockEeprom();                                                    // Unlock EEPROM, () → void
                sensor.setTemperatureOffset(offset);                                      // Set calibration offset, (celsius °C) → void
                while (sensor.isEepromBusy()) {                                           // Check EEPROM busy, () → boolean
                    Thread.sleep(1);
                }
                sensor.lockEeprom();                                                      // Lock EEPROM, () → void
                System.out.printf("calibrated, offset %.4f °C%n", offset);
            }

            // --- Program the safe transport range ---
            // Alert mode flags either side of the window independently; ALERT is
            // active-low open-drain, pulled up on the board.
            sensor.setHighLimit(8.0);                                                     // Set THIGH_LIMIT, (celsius °C) → void
            sensor.setLowLimit(-25.0);                                                    // Set TLOW_LIMIT, (celsius °C) → void
            sensor.configureAlert(TMP117Full.AlertMode.ALERT, TMP117Full.AlertPolarity.ACTIVE_LOW,
                    TMP117Full.AlertPinFunction.ALERT);                                   // Configure ALERT, (mode=ALERT, polarity=ACTIVE_LOW, pinFunction=ALERT) → void

            // --- Report which boundary tripped ---
            // The status mask comes from CONFIGURATION's alert flags; reading them
            // clears them in Alert mode, re-arming the pin for the next excursion.
            var events = new LinkedBlockingQueue<Integer>();
            sensor.onInterrupt(s -> { if (s != 0) events.add(s); });                      // Subscribe to ALERT, (callback) → void
            System.out.printf("monitoring, %.2f °C now%n", sensor.readTemperature());     // Read temperature, () → double °C
            for (int alerts = 0; alerts < MAX_ALERTS; alerts++) {
                int status = events.take();
                double t = sensor.readTemperature();                                      // Read temperature, () → double °C
                if ((status & TMP117Full.SOURCE_HIGH) != 0) System.out.printf("%.2f °C  too warm - cargo above 8 °C%n", t);
                else if ((status & TMP117Full.SOURCE_LOW) != 0) System.out.printf("%.2f °C  too cold - cargo below -25 °C%n", t);
            }

            // --- Stop monitoring after the demo run ---
            sensor.offInterrupt();                                                        // Unsubscribe, () → void
        }
    }
}
