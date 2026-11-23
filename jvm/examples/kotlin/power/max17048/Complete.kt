///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Max17048Full
import java.util.function.IntConsumer

fun main() {
    I2CConnection(1, 0x36).use { connection ->   // open I²C bus 1, device 0x36, (bus, address) → I2CConnection
        val gauge = Max17048Full(connection, 1)             // construct driver, (connection, cells) → Max17048Full

        println("%.3f V".format(gauge.voltage()))               // cell voltage, () → double V
        println("%.2f %%".format(gauge.percent()))              // state of charge, () → double %
        println("raw VCELL 0x%04X".format(gauge.rawVoltage()))  // VCELL register, () → int
        println("raw SOC 0x%04X".format(gauge.rawSoc()))        // SOC register, () → int
        println("%.2f %%/h".format(gauge.chargeRate()))         // charge/discharge rate, () → double %/h
        println("version 0x%04X".format(gauge.version()))       // VERSION register, () → int
        println("id 0x%02X".format(gauge.chipId()))             // factory ID, () → int

        gauge.setRcomp(0x97)                                    // RCOMP, (value) → void
        println("rcomp 0x%02X".format(gauge.getRcomp()))        // () → int
        gauge.compensateTemperature(25.0)                       // RCOMP from the temperature, (celsius) → void

        gauge.setAlertSoc(10)                                   // empty alert threshold, (percent) → void; 1-32 %
        println("alert soc %d %%".format(gauge.getAlertSoc()))  // () → int %
        gauge.setSocChangeAlert(true)                           // alert on 1 % SOC change, (enabled) → void
        gauge.setVoltageAlert(3.3, 4.25)                        // voltage window, (minV, maxV) → void; 0 / 5.1 V disable a limit
        val window = gauge.getVoltageAlert()                    // () → double[]{minV, maxV}
        println("window %.2f .. %.2f V".format(window[0], window[1]))
        gauge.setResetVoltage(3.0)                              // reset voltage, (volts) → void
        println("reset %.2f V".format(gauge.getResetVoltage())) // () → double V
        gauge.enableVoltageResetAlert(true)                     // voltage reset asserts ALRT, (enabled) → void
        gauge.setComparatorDisabled(false)                      // hibernate comparator off, (disabled) → void

        gauge.setHibernateThresholds(26.624, 0.06)              // hibernate thresholds, (hibThrPctPerH, actThrV) → void
        val hib = gauge.getHibernateThresholds()                // () → double[]{hibThrPctPerH, actThrV}
        println("hibernate %.2f %%/h, %.3f V".format(hib[0], hib[1]))
        gauge.disableHibernate()                                // never hibernate, () → void
        gauge.forceHibernate()                                  // always hibernate, () → void
        gauge.setHibernateThresholds(26.624, 0.06)              // restore the defaults
        println("hibernating %s".format(gauge.isHibernating())) // hibernate status, () → boolean

        println("status 0x%04X".format(gauge.readStatus()))     // STATUS register, () → int
        gauge.clearStatus(Max17048Full.SOURCE_RESET)            // clear flags and ALRT, (mask) → void
        println("config 0x%04X".format(gauge.readConfig()))     // CONFIG register, () → int
        gauge.writeConfig(gauge.readConfig())                   // write CONFIG, (value) → void

        println("alert sources 0x%04X".format(gauge.pollInterrupt()))// alert sources, () → int
        gauge.enableInterrupt(Max17048Full.SOURCE_SOC_CHANGE)   // enable a source, (source) → void
        gauge.disableInterrupt(Max17048Full.SOURCE_SOC_CHANGE)  // disable a source, (source) → void
        gauge.onInterrupt(IntConsumer { status -> println("alert 0x%04X".format(status)) })// subscribe to ALRT, (callback[, intPin]) → void
        Thread.sleep(2000)
        gauge.offInterrupt()                                    // unsubscribe, () → void

        gauge.enableSleep(true)                                 // allow sleep mode, (enabled) → void
        gauge.sleep()                                           // sleep, () → void
        gauge.wake()                                            // wake up, () → void
        gauge.enableSleep(false)

        gauge.quickStart()                                      // restart the SOC estimate, () → void; only if the first SOC is clearly wrong
        gauge.reset()                                           // power-on reset, () → void; waits 1 s
    }
}
