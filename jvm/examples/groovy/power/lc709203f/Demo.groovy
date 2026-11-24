///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LC709203FFull

def connection = new I2CConnection(1, 0x0B)   // open I²C bus 1, device 0x0B, (bus, address) → I2CConnection
try {
    def gauge = new LC709203FFull(connection, 1000, 1)   // construct driver, (connection, capacityMah, profile) → LC709203FFull

    printf("IC version 0x%04X, profile id 0x%04X, RSOC %d %%%n", gauge.icVersion(), gauge.profileId(), gauge.rsoc())
    gauge.setAlarmRsoc(20)                                                            // alarm at 20 % RSOC, (percent) → void
    gauge.setAlarmVoltage(3.5d)                                                       // alarm at 3.5 V, (volts) → void

    // Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
    12.times {
        gauge.setTemperature(25.0d)
        printf("%.3f V  %.1f %%  %.1f C  alarm=%d%n", gauge.voltage(), gauge.percent(), gauge.temperature(), gauge.pollInterrupt())
        Thread.sleep(5000L)
    }

    gauge.sleep()
    gauge.wake()
    println("sleeping: " + gauge.isSleeping())
} finally {
    connection.close()
}
