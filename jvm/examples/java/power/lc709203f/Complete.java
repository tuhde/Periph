///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-java:1.5.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.LC709203FFull;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x0B)) {   // open I²C bus 1, device 0x0B, (bus, address) → I2CConnection
            var gauge = new LC709203FFull(connection, 1000, 1);   // construct driver, (connection, capacityMah, profile) → LC709203FFull

            System.out.println(gauge.voltage());                                      // read cell voltage, () → double V
            System.out.println(gauge.percent());                                      // read state of charge (ITE), () → double %
            System.out.println(gauge.temperature());                                  // read cell temperature, () → double °C
            gauge.setTemperature(25.0);                                               // host temperature (I²C mode), (celsius) → void; clamped to -20...+60
            System.out.println(gauge.rsoc());                                         // RSOC register, () → int %
            gauge.setRsoc(50);                                                        // force RSOC, (percent) → void
            gauge.initRsoc();                                                         // initial RSOC from present voltage, () → void
            gauge.beforeRsoc();                                                       // RSOC from max voltage since reset, () → void
            gauge.useThermistor(3380);                                                // thermistor mode, (bConstant) → void
            System.out.println(gauge.isThermistorMode());                             // temperature source, () → boolean
            System.out.println(gauge.getBConstant());                                 // thermistor B constant, () → int K
            gauge.useI2cTemperature(25.0);                                            // back to I²C temperature mode, (celsius) → void
            gauge.setApa(0x19);                                                       // Adjustment Pack Application, (value) → void
            System.out.println(gauge.getApa());                                       // () → int
            gauge.setApt(0x1E);                                                       // Adjustment Pack Thermistor, (value) → void
            System.out.println(gauge.getApt());                                       // () → int
            gauge.setCurrentDirection(LC709203FFull.DIRECTION_AUTO);                  // current direction, (mode) → void; AUTO / CHARGE / DISCHARGE
            System.out.println(gauge.getCurrentDirection());                          // () → int
            gauge.setProfile(1);                                                      // battery profile 0/1, (profile) → void; re-initializes the chip (90 ms)
            System.out.println(gauge.getProfile());                                   // () → int
            System.out.println(gauge.profileId());                                    // number of the parameter, () → int
            System.out.println(gauge.icVersion());                                    // IC version, () → int
            gauge.setAlarmRsoc(20);                                                   // low-RSOC alarm, (percent) → void; 0 = off
            System.out.println(gauge.getAlarmRsoc());                                 // () → int %
            gauge.setAlarmVoltage(3.5);                                               // low-voltage alarm, (volts) → void; 0 = off
            System.out.println(gauge.getAlarmVoltage());                              // () → double V
            System.out.println(gauge.pollInterrupt());                                // alarm bitmask, () → int; ALARM_RSOC = 1, ALARM_VOLTAGE = 2
            gauge.sleep();                                                            // sleep mode, () → void
            gauge.wake();                                                             // wake up, () → void
            System.out.println(gauge.isSleeping());                                   // power mode, () → boolean
        }
    }
}
