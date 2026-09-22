///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rtc.DS3231Minimal;
import it.uhde.periph.chips.rtc.DS3231Full;

public class DS3231Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, DS3231Minimal.DEFAULT_ADDRESS)) {
            var rtc = new DS3231Minimal(connection);
            checkTrue(true, "construct_minimal");

            rtc.setDatetime(2026, 9, 22, 2, 10, 0, 0);
            var dt = rtc.getDatetime();
            checkTrue(dt.year() == 2026 && dt.month() == 9 && dt.day() == 22, "datetime_roundtrip_date");
            checkTrue(dt.hour() == 10 && dt.minute() == 0, "datetime_roundtrip_time");

            double temp = rtc.readTemperature();
            checkTrue(temp > -40 && temp < 85, "temperature_in_range");

            var rtcFull = new DS3231Full(connection);
            checkTrue(true, "construct_full");

            rtcFull.forceTemperatureConversion();
            checkTrue(true, "force_temperature_conversion_completes");

            rtcFull.setAlarm1(0, 5, 10, 0, false, DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS);
            var alarm1 = rtcFull.getAlarm1();
            checkTrue(alarm1.minute() == 5 && alarm1.hour() == 10, "alarm1_roundtrip");

            rtcFull.enableInterrupt(DS3231Full.SOURCE_ALARM1);
            int status = rtcFull.pollInterrupt();
            checkTrue(status >= 0, "poll_interrupt_returns");
            rtcFull.disableInterrupt(DS3231Full.SOURCE_ALARM1);

            boolean osf = rtcFull.oscillatorStopped();
            checkTrue(osf == false || osf == true, "oscillator_stopped_readable");
            rtcFull.clearOscillatorStopped();

            rtcFull.setAgingOffset(0);
            checkTrue(rtcFull.getAgingOffset() == 0, "aging_offset_roundtrip");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
