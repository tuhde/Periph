///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-groovy:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LC709203FFull

def connection = new I2CConnection(1, 0x0B)   // open I²C bus 1, device 0x0B, (bus, address) → I2CConnection
try {
    def gauge = new LC709203FFull(connection, 1000, 1)   // construct driver, (connection, capacityMah, profile) → LC709203FFull

    println(gauge.voltage())                                                          // read cell voltage, () → double V
    println(gauge.percent())                                                          // read state of charge (ITE), () → double %
    println(gauge.temperature())                                                      // read cell temperature, () → double °C
    gauge.setTemperature(25.0)                                                        // host temperature (I²C mode), (celsius) → void; clamped to -20...+60
    println(gauge.rsoc())                                                             // RSOC register, () → int %
    gauge.setRsoc(50)                                                                 // force RSOC, (percent) → void
    gauge.initRsoc()                                                                  // initial RSOC from present voltage, () → void
    gauge.beforeRsoc()                                                                // RSOC from max voltage since reset, () → void
    gauge.useThermistor(3380)                                                         // thermistor mode, (bConstant) → void
    println(gauge.isThermistorMode())                                                 // temperature source, () → boolean
    println(gauge.getBConstant())                                                     // thermistor B constant, () → int K
    gauge.useI2cTemperature(25.0)                                                     // back to I²C temperature mode, (celsius) → void
    gauge.setApa(0x19)                                                                // Adjustment Pack Application, (value) → void
    println(gauge.getApa())                                                           // () → int
    gauge.setApt(0x1E)                                                                // Adjustment Pack Thermistor, (value) → void
    println(gauge.getApt())                                                           // () → int
    gauge.setCurrentDirection(LC709203FFull.DIRECTION_AUTO)                           // current direction, (mode) → void; AUTO / CHARGE / DISCHARGE
    println(gauge.getCurrentDirection())                                              // () → int
    gauge.setProfile(1)                                                               // battery profile 0/1, (profile) → void; re-initializes the chip (90 ms)
    println(gauge.getProfile())                                                       // () → int
    println(gauge.profileId())                                                        // number of the parameter, () → int
    println(gauge.icVersion())                                                        // IC version, () → int
    gauge.setAlarmRsoc(20)                                                            // low-RSOC alarm, (percent) → void; 0 = off
    println(gauge.getAlarmRsoc())                                                     // () → int %
    gauge.setAlarmVoltage(3.5)                                                        // low-voltage alarm, (volts) → void; 0 = off
    println(gauge.getAlarmVoltage())                                                  // () → double V
    println(gauge.pollInterrupt())                                                    // alarm bitmask, () → int; ALARM_RSOC = 1, ALARM_VOLTAGE = 2
    gauge.sleep()                                                                     // sleep mode, () → void
    gauge.wake()                                                                      // wake up, () → void
    println(gauge.isSleeping())                                                       // power mode, () → boolean
} finally {
    connection.close()
}
