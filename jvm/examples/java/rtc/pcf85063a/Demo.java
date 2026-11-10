///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

// Scheduling core of a battery-powered logger: reseeds the clock after a
// power loss, counts boots in the free RAM byte, then wakes on an hourly
// alarm to print a timestamp while a 30-second pulsed countdown timer
// toggles an LED as a "still running" heartbeat.

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rtc.PCF85063AFull;
import it.uhde.periph.chips.rtc.PCF85063AFull.Alarm;
import it.uhde.periph.chips.rtc.PCF85063AFull.MinuteInterrupt;
import it.uhde.periph.chips.rtc.PCF85063AFull.SourceClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, PCF85063AFull.DEFAULT_ADDRESS)) {     // open I²C bus, fixed device address, (bus, address) → I2CConnection
            var rtc = new PCF85063AFull(connection);                                         // Create PCF85063A Full driver, (connection) → PCF85063AFull

            // --- Detect a lost time reference and reseed if needed ---
            // A fresh chip, or one whose supply dropped out, reports the OS flag
            // set: its calendar cannot be trusted until reseeded. The crystal
            // needs up to 2 s to start, so wait until the flag stays clear.
            if (rtc.oscillatorStopped()) {                                                   // Query oscillator-stop flag, () → boolean
                rtc.setDatetime(2026, 1, 1, 4, 0, 0, 0);                                     // Set calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → void
                System.out.println("oscillator was stopped - reseeded from reference timestamp");
                while (rtc.oscillatorStopped()) {                                            // Query oscillator-stop flag, () → boolean
                    Thread.sleep(100);
                }
            }

            // --- Count boots in the free RAM byte ---
            // The RAM byte survives as long as VDD is present, so it works as a
            // tiny non-volatile counter next to the clock.
            int boots = (rtc.readRam() + 1) & 0xFF;                                          // Read RAM byte, () → int
            rtc.writeRam(boots);                                                             // Write RAM byte, (value 0–255) → void
            System.out.println("boot #" + boots + " " + rtc.getDatetime() + " " + rtc.getOffset());  // Read calendar clock, () → DateTime
                                                                                              // Read offset calibration, () → Offset

            // --- Tune the board for low power ---
            // Match the crystal's load capacitance and switch the 32.768 kHz
            // CLKOUT off — it is enabled at power-on and costs current on a
            // battery device.
            rtc.setOscillatorLoad(12.5);                                                     // Select crystal load, (picofarads 7|12.5) → void
            rtc.disableClockOutput();                                                        // Disable CLKOUT, () → void

            // --- Hourly wake-up plus a 30 s heartbeat ---
            // Only second and minute are enabled, so the alarm matches at
            // hh:00:00 every hour. The pulsed timer reloads automatically; minute
            // ticks share the same TF flag and are reported as SOURCE_TIMER.
            rtc.setAlarm(new Alarm(0, 0, null, null, null));                                 // Configure alarm, (Alarm(second, minute, hour, day, weekday)) → void
            rtc.configureTimer(30, SourceClock.HZ_1, true);                                  // Start countdown timer, (value 1–255, sourceClock, pulsed=false) → void
            rtc.setMinuteInterrupt(MinuteInterrupt.MINUTE);                                  // Select minute interrupt, (mode OFF|MINUTE|HALF_MINUTE) → void

            // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
            var alarms = new CountDownLatch(5);
            boolean[] ledOn = {false};
            rtc.onInterrupt(status -> {                                                      // Subscribe to interrupts, (callback) → void
                try {
                    if ((status & PCF85063AFull.SOURCE_ALARM) != 0) {
                        var dt = rtc.getDatetime();                                          // Read calendar clock, () → DateTime
                        System.out.printf("[hourly] %04d-%02d-%02d %02d:%02d:%02d%n",
                                dt.year(), dt.month(), dt.day(), dt.hour(), dt.minute(), dt.second());
                        alarms.countDown();
                    }
                    if ((status & PCF85063AFull.SOURCE_TIMER) != 0) {
                        ledOn[0] = !ledOn[0];
                        System.out.println("[heartbeat] LED " + (ledOn[0] ? "on" : "off"));
                    }
                } catch (java.io.IOException e) {
                    System.err.println("bus error: " + e.getMessage());
                }
            });
            rtc.enableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_TIMER);    // Enable sources, (source) → void
            alarms.await(5, TimeUnit.MINUTES);

            // --- Leave the chip quiet on exit ---
            rtc.disableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_TIMER);   // Disable sources, (source) → void
            rtc.disableTimer();                                                              // Stop timer, () → void
            rtc.setMinuteInterrupt(MinuteInterrupt.OFF);                                     // Select minute interrupt, (mode OFF|MINUTE|HALF_MINUTE) → void
            rtc.offInterrupt();                                                              // Unsubscribe, () → void
        }
    }
}
