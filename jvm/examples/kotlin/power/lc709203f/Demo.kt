///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LC709203FFull

fun main() {
    I2CConnection(1, 0x0B).use { connection ->   // open I²C bus 1, device 0x0B, (bus, address) → I2CConnection
        val gauge = LC709203FFull(connection, 1000, 1)   // construct driver, (connection, capacityMah, profile) → LC709203FFull

        println("IC version 0x%04X, profile id 0x%04X, RSOC %d %%".format(gauge.icVersion(), gauge.profileId(), gauge.rsoc()))
        gauge.setAlarmRsoc(20)                                                        // alarm at 20 % RSOC, (percent) → Unit
        gauge.setAlarmVoltage(3.5)                                                    // alarm at 3.5 V, (volts) → Unit

        // Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
        repeat(12) {
            gauge.setTemperature(25.0)
            println("%.3f V  %.1f %%  %.1f C  alarm=%d".format(gauge.voltage(), gauge.percent(), gauge.temperature(), gauge.pollInterrupt()))
            Thread.sleep(5000)
        }

        gauge.sleep()
        gauge.wake()
        println("sleeping: ${gauge.isSleeping()}")
    }
}
