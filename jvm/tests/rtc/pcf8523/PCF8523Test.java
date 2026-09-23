///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rtc.PCF8523Minimal;
import it.uhde.periph.chips.rtc.PCF8523Full;

public class PCF8523Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, PCF8523Minimal.DEFAULT_ADDRESS)) {
            var rtc = new PCF8523Minimal(connection);
            checkTrue(true, "construct_minimal");

            rtc.setDatetime(2026, 9, 23, 3, 14, 30, 0);
            var dt = rtc.getDatetime();
            checkTrue(dt.year() == 2026 && dt.month() == 9 && dt.day() == 23 && dt.weekday() == 3, "datetime_roundtrip_date");
            checkTrue(dt.hour() == 14 && (dt.minute() == 30 || dt.minute() == 31), "datetime_roundtrip_time");

            var full = new PCF8523Full(connection);
            checkTrue(!full.oscillatorStopped(), "oscillator_running_after_set_datetime");

            var alarm = new PCF8523Full.Alarm(15, 6, null, null);
            full.setAlarm(alarm);
            checkTrue(alarm.equals(full.getAlarm()), "alarm_roundtrip");
            full.setAlarm(new PCF8523Full.Alarm(null, null, null, null));

            full.setOffset(-3, PCF8523Full.OffsetMode.EVERY_MINUTE);
            checkTrue(new PCF8523Full.Offset(-3, PCF8523Full.OffsetMode.EVERY_MINUTE).equals(full.getOffset()), "offset_roundtrip");
            full.setOffset(0);

            // Timer B at 64 Hz from 64 counts expires after ~1 s.
            full.pollInterrupt();
            full.configureTimerB(64, PCF8523Full.SourceClock.HZ_64);
            Thread.sleep(1500);
            checkTrue((full.pollInterrupt() & PCF8523Full.SOURCE_TIMER_B) != 0, "timer_b_fires");
            full.disableTimerB();
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
