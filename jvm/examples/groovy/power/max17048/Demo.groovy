///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Max17048Full
import java.util.function.IntConsumer

def connection = new I2CConnection(1, 0x36)   // open I²C bus 1, device 0x36, (bus, address) → I2CConnection
try {
    def gauge = new Max17048Full(connection, 1)             // construct driver, (connection, cells) → Max17048Full

    printf("version 0x%04X, id 0x%02X%n", gauge.version(), gauge.chipId())
    gauge.clearStatus(Max17048Full.SOURCE_RESET)            // clear the reset indicator, (mask) → void

    // Battery fuel gauge: one line per second for 30 s
    30.times {
        printf("%.3f V  %.1f %%  %+.1f %%/h%n", gauge.voltage(), gauge.percent(), gauge.chargeRate())
        Thread.sleep(1000)
    }

    gauge.setAlertSoc(10)                                   // low-SOC alert at 10 %, (percent) → void
    gauge.setVoltageAlert(3.3, 4.25)                        // voltage window 3.3-4.25 V, (minV, maxV) → void
    gauge.onInterrupt({ int status -> printf("alert 0x%04X%n", status) } as IntConsumer)// print decoded alert sources, (callback[, intPin]) → void
    Thread.sleep(5000)
    gauge.offInterrupt()
    printf("poll_interrupt 0x%04X%n", gauge.pollInterrupt())// without an ALRT pin, () → int

    gauge.compensateTemperature(5.0)                        // RCOMP for the temperature, (celsius) → void
    printf("RCOMP at 5 C: 0x%02X%n", gauge.getRcomp())
    gauge.compensateTemperature(35.0)
    printf("RCOMP at 35 C: 0x%02X%n", gauge.getRcomp())
    gauge.compensateTemperature(20.0)

    gauge.disableHibernate()                                // never hibernate, () → void
    printf("hibernating: %s%n", gauge.isHibernating())
    gauge.setHibernateThresholds(26.624, 0.06)              // back to the defaults

    gauge.enableSleep(true)                                 // sleep round trip, (enabled) → void
    gauge.sleep()
    Thread.sleep(1000)
    gauge.wake()
    gauge.enableSleep(false)
    printf("after wake: %.3f V%n", gauge.voltage())
} finally {
    connection.close()
}
