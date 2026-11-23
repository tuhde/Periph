///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-java:1.5.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.LC709203FFull;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x0B)) {   // open I²C bus 1, device 0x0B, (bus, address) → I2CConnection
            var gauge = new LC709203FFull(connection, 1000, 1);   // construct driver, (connection, capacityMah, profile) → LC709203FFull

            System.out.printf("IC version 0x%04X, profile id 0x%04X, RSOC %d %%%n", gauge.icVersion(), gauge.profileId(), gauge.rsoc());
            gauge.setAlarmRsoc(20);                                                   // alarm at 20 % RSOC, (percent) → void
            gauge.setAlarmVoltage(3.5);                                               // alarm at 3.5 V, (volts) → void

            // Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
            for (int i = 0; i < 12; i++) {
                gauge.setTemperature(25.0);
                System.out.printf("%.3f V  %.1f %%  %.1f C  alarm=%d%n", gauge.voltage(), gauge.percent(), gauge.temperature(), gauge.pollInterrupt());
                Thread.sleep(5000);
            }

            gauge.sleep();
            gauge.wake();
            System.out.println("sleeping: " + gauge.isSleeping());
        }
    }
}
