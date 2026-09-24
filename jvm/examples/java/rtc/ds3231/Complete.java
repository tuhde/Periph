///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rtc.DS3231Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, DS3231Full.DEFAULT_ADDRESS)) {
            var rtc = new DS3231Full(connection);                                                                     // construct driver and confirm presence, (connection) → DS3231Full

            rtc.setDatetime(2026, 9, 22, 2, 14, 30, 0);                                                                // Set calendar clock, (year, month, day, weekday, hour, minute, second) → None
                                                                                                                            // writes all 7 registers, forces 24h mode, clears OSF
            var dt = rtc.getDatetime();                                                                                // Read calendar clock, () → DateTime
                                                                                                                            // decodes the 7 BCD registers
            double tempC = rtc.readTemperature();                                                                     // Read temperature, () → double C
                                                                                                                            // last completed conversion, may be up to 64 s stale
            rtc.forceTemperatureConversion();                                                                          // Force temperature conversion, () → None
                                                                                                                            // sets CONV, polls BSY until clear (max 200 ms)

            rtc.setAlarm1(0, 0, 9, 0, false, DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS);                          // Configure Alarm 1, (second, minute, hour, day_or_date, is_day_of_week, match_mode) → None
                                                                                                                            // fires daily at 09:00:00
            var alarm1 = rtc.getAlarm1();                                                                              // Read Alarm 1 config, () → Alarm1
                                                                                                                            // decodes mask bits back into match_mode
            rtc.setAlarm2(30, 12, 0, false, DS3231Full.ALARM2_MATCH_HOURS_MINUTES);                                   // Configure Alarm 2, (minute, hour, day_or_date, is_day_of_week, match_mode) → None
                                                                                                                            // fires daily at 12:30
            var alarm2 = rtc.getAlarm2();                                                                              // Read Alarm 2 config, () → Alarm2
                                                                                                                            // decodes mask bits back into match_mode

            rtc.enableInterrupt(DS3231Full.SOURCE_ALARM1);                                                             // Enable an alarm interrupt, (source) → None
                                                                                                                            // sets A1IE and routes INT/SQW to interrupt mode (INTCN=1)
            rtc.enableInterrupt(DS3231Full.SOURCE_ALARM2);                                                             // Enable an alarm interrupt, (source) → None
                                                                                                                            // sets A2IE
            int status = rtc.pollInterrupt();                                                                          // Poll and clear alarm flags, () → int
                                                                                                                            // mask with SOURCE_ALARM1/SOURCE_ALARM2 to test each source
            rtc.disableInterrupt(DS3231Full.SOURCE_ALARM2);                                                            // Disable an alarm interrupt, (source) → None
                                                                                                                            // clears A2IE only

            rtc.enableSquareWave(DS3231Full.SQUARE_WAVE_1_HZ, false);                                                  // Enable square wave, (rate_hz=1, battery_backed=false) → None
                                                                                                                            // shares INTCN with alarm interrupts — overrides the interrupt routing above
            rtc.disableSquareWave();                                                                                   // Disable square wave, () → None
                                                                                                                            // returns INT/SQW to interrupt mode

            boolean has32k = rtc.is32kHzEnabled();                                                                     // Query 32kHz output, () → bool
            rtc.disable32kHzOutput();                                                                                  // Disable 32kHz output, () → None
            rtc.enable32kHzOutput();                                                                                   // Enable 32kHz output, () → None

            boolean stopped = rtc.oscillatorStopped();                                                                 // Query Oscillator Stop Flag, () → bool
            rtc.clearOscillatorStopped();                                                                              // Clear Oscillator Stop Flag, () → None
            rtc.enableBatteryOscillator();                                                                             // Enable battery-backed oscillator, () → None
                                                                                                                            // EOSC=0, power-on default
            rtc.disableBatteryOscillator();                                                                            // Disable battery-backed oscillator, () → None
            rtc.enableBatteryOscillator();

            int aging = rtc.getAgingOffset();                                                                          // Read aging offset trim, () → int (raw signed byte)
            rtc.setAgingOffset(0);                                                                                     // Set aging offset trim, (offset=0) → None
                                                                                                                            // restores the neutral (power-on) trim value

            System.out.printf("datetime=%s temp=%.2fC alarm1=%s alarm2=%s status=0x%02X 32kHz=%b osf=%b aging=%d%n",
                    dt, tempC, alarm1, alarm2, status, has32k, stopped, aging);
        }
    }
}
