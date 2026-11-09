///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rtc.PCF85063AMinimal;
import it.uhde.periph.chips.rtc.PCF85063AFull;

public class PCF85063ATest {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, PCF85063AMinimal.DEFAULT_ADDRESS)) {
            var rtc = new PCF85063AMinimal(connection);
            checkTrue(true, "construct_minimal");

            rtc.setDatetime(2026, 9, 23, 3, 14, 30, 0);
            var dt = rtc.getDatetime();
            checkTrue(dt.year() == 2026 && dt.month() == 9 && dt.day() == 23 && dt.weekday() == 3, "datetime_roundtrip_date");
            checkTrue(dt.hour() == 14 && (dt.minute() == 30 || dt.minute() == 31), "datetime_roundtrip_time");

            var full = new PCF85063AFull(connection);
            checkTrue(!full.oscillatorStopped(), "oscillator_running_after_set_datetime");

            var alarm = new PCF85063AFull.Alarm(0, 15, 6, null, null);
            full.setAlarm(alarm);
            checkTrue(alarm.equals(full.getAlarm()), "alarm_roundtrip");
            full.setAlarm(new PCF85063AFull.Alarm(null, null, null, null, null));

            full.setOffset(-3, PCF85063AFull.OffsetMode.COURSE);
            checkTrue(new PCF85063AFull.Offset(-3, PCF85063AFull.OffsetMode.COURSE).equals(full.getOffset()), "offset_roundtrip");
            full.setOffset(0);

            full.writeRam(0xA5);
            checkTrue(full.readRam() == 0xA5, "ram_roundtrip");

            // Countdown timer at 64 Hz from 64 counts expires after ~1 s.
            full.pollInterrupt();
            full.configureTimer(64, PCF85063AFull.SourceClock.HZ_64);
            Thread.sleep(1500);
            checkTrue((full.pollInterrupt() & PCF85063AFull.SOURCE_TIMER) != 0, "timer_fires");
            full.disableTimer();
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
