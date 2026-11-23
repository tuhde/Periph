///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-groovy:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Max17048Full
import java.util.function.IntConsumer

def connection = new I2CConnection(1, 0x36)   // open I²C bus 1, device 0x36, (bus, address) → I2CConnection
try {
    def gauge = new Max17048Full(connection, 1)             // construct driver, (connection, cells) → Max17048Full

    printf("%.3f V%n", gauge.voltage())                     // cell voltage, () → double V
    printf("%.2f %%%n", gauge.percent())                    // state of charge, () → double %
    printf("raw VCELL 0x%04X%n", gauge.rawVoltage())        // VCELL register, () → int
    printf("raw SOC 0x%04X%n", gauge.rawSoc())              // SOC register, () → int
    printf("%.2f %%/h%n", gauge.chargeRate())               // charge/discharge rate, () → double %/h
    printf("version 0x%04X%n", gauge.version())             // VERSION register, () → int
    printf("id 0x%02X%n", gauge.chipId())                   // factory ID, () → int

    gauge.setRcomp(0x97)                                    // RCOMP, (value) → void
    printf("rcomp 0x%02X%n", gauge.getRcomp())              // () → int
    gauge.compensateTemperature(25.0)                       // RCOMP from the temperature, (celsius) → void

    gauge.setAlertSoc(10)                                   // empty alert threshold, (percent) → void; 1-32 %
    printf("alert soc %d %%%n", gauge.getAlertSoc())        // () → int %
    gauge.setSocChangeAlert(true)                           // alert on 1 % SOC change, (enabled) → void
    gauge.setVoltageAlert(3.3, 4.25)                        // voltage window, (minV, maxV) → void; 0 / 5.1 V disable a limit
    def window = gauge.getVoltageAlert()                    // () → double[]{minV, maxV}
    printf("window %.2f .. %.2f V%n", window[0], window[1])
    gauge.setResetVoltage(3.0)                              // reset voltage, (volts) → void
    printf("reset %.2f V%n", gauge.getResetVoltage())       // () → double V
    gauge.enableVoltageResetAlert(true)                     // voltage reset asserts ALRT, (enabled) → void
    gauge.setComparatorDisabled(false)                      // hibernate comparator off, (disabled) → void

    gauge.setHibernateThresholds(26.624, 0.06)              // hibernate thresholds, (hibThrPctPerH, actThrV) → void
    def hib = gauge.getHibernateThresholds()                // () → double[]{hibThrPctPerH, actThrV}
    printf("hibernate %.2f %%/h, %.3f V%n", hib[0], hib[1])
    gauge.disableHibernate()                                // never hibernate, () → void
    gauge.forceHibernate()                                  // always hibernate, () → void
    gauge.setHibernateThresholds(26.624, 0.06)              // restore the defaults
    printf("hibernating %s%n", gauge.isHibernating())       // hibernate status, () → boolean

    printf("status 0x%04X%n", gauge.readStatus())           // STATUS register, () → int
    gauge.clearStatus(Max17048Full.SOURCE_RESET)            // clear flags and ALRT, (mask) → void
    printf("config 0x%04X%n", gauge.readConfig())           // CONFIG register, () → int
    gauge.writeConfig(gauge.readConfig())                   // write CONFIG, (value) → void

    printf("alert sources 0x%04X%n", gauge.pollInterrupt()) // alert sources, () → int
    gauge.enableInterrupt(Max17048Full.SOURCE_SOC_CHANGE)   // enable a source, (source) → void
    gauge.disableInterrupt(Max17048Full.SOURCE_SOC_CHANGE)  // disable a source, (source) → void
    gauge.onInterrupt({ int status -> printf("alert 0x%04X%n", status) } as IntConsumer)// subscribe to ALRT, (callback[, intPin]) → void
    Thread.sleep(2000)
    gauge.offInterrupt()                                    // unsubscribe, () → void

    gauge.enableSleep(true)                                 // allow sleep mode, (enabled) → void
    gauge.sleep()                                           // sleep, () → void
    gauge.wake()                                            // wake up, () → void
    gauge.enableSleep(false)

    gauge.quickStart()                                      // restart the SOC estimate, () → void; only if the first SOC is clearly wrong
    gauge.reset()                                           // power-on reset, () → void; waits 1 s
} finally {
    connection.close()
}
