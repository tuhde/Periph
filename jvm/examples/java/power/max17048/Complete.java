///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-java:1.5.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Max17048Full;
import java.util.function.IntConsumer;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x36)) {   // open I²C bus 1, device 0x36, (bus, address) → I2CConnection
            var gauge = new Max17048Full(connection, 1);            // construct driver, (connection, cells) → Max17048Full

            System.out.printf("%.3f V%n", gauge.voltage());         // cell voltage, () → double V
            System.out.printf("%.2f %%%n", gauge.percent());        // state of charge, () → double %
            System.out.printf("raw VCELL 0x%04X%n", gauge.rawVoltage());// VCELL register, () → int
            System.out.printf("raw SOC 0x%04X%n", gauge.rawSoc());  // SOC register, () → int
            System.out.printf("%.2f %%/h%n", gauge.chargeRate());   // charge/discharge rate, () → double %/h
            System.out.printf("version 0x%04X%n", gauge.version()); // VERSION register, () → int
            System.out.printf("id 0x%02X%n", gauge.chipId());       // factory ID, () → int

            gauge.setRcomp(0x97);                                   // RCOMP, (value) → void
            System.out.printf("rcomp 0x%02X%n", gauge.getRcomp());  // () → int
            gauge.compensateTemperature(25.0);                      // RCOMP from the temperature, (celsius) → void

            gauge.setAlertSoc(10);                                  // empty alert threshold, (percent) → void; 1-32 %
            System.out.printf("alert soc %d %%%n", gauge.getAlertSoc());// () → int %
            gauge.setSocChangeAlert(true);                          // alert on 1 % SOC change, (enabled) → void
            gauge.setVoltageAlert(3.3, 4.25);                       // voltage window, (minV, maxV) → void; 0 / 5.1 V disable a limit
            var window = gauge.getVoltageAlert();                   // () → double[]{minV, maxV}
            System.out.printf("window %.2f .. %.2f V%n", window[0], window[1]);
            gauge.setResetVoltage(3.0);                             // reset voltage, (volts) → void
            System.out.printf("reset %.2f V%n", gauge.getResetVoltage());// () → double V
            gauge.enableVoltageResetAlert(true);                    // voltage reset asserts ALRT, (enabled) → void
            gauge.setComparatorDisabled(false);                     // hibernate comparator off, (disabled) → void

            gauge.setHibernateThresholds(26.624, 0.06);             // hibernate thresholds, (hibThrPctPerH, actThrV) → void
            var hib = gauge.getHibernateThresholds();               // () → double[]{hibThrPctPerH, actThrV}
            System.out.printf("hibernate %.2f %%/h, %.3f V%n", hib[0], hib[1]);
            gauge.disableHibernate();                               // never hibernate, () → void
            gauge.forceHibernate();                                 // always hibernate, () → void
            gauge.setHibernateThresholds(26.624, 0.06);             // restore the defaults
            System.out.printf("hibernating %s%n", gauge.isHibernating());// hibernate status, () → boolean

            System.out.printf("status 0x%04X%n", gauge.readStatus());// STATUS register, () → int
            gauge.clearStatus(Max17048Full.SOURCE_RESET);           // clear flags and ALRT, (mask) → void
            System.out.printf("config 0x%04X%n", gauge.readConfig());// CONFIG register, () → int
            gauge.writeConfig(gauge.readConfig());                  // write CONFIG, (value) → void

            System.out.printf("alert sources 0x%04X%n", gauge.pollInterrupt());// alert sources, () → int
            gauge.enableInterrupt(Max17048Full.SOURCE_SOC_CHANGE);  // enable a source, (source) → void
            gauge.disableInterrupt(Max17048Full.SOURCE_SOC_CHANGE); // disable a source, (source) → void
            gauge.onInterrupt(status -> System.out.printf("alert 0x%04X%n", status));// subscribe to ALRT, (callback[, intPin]) → void
            Thread.sleep(2000L);
            gauge.offInterrupt();                                   // unsubscribe, () → void

            gauge.enableSleep(true);                                // allow sleep mode, (enabled) → void
            gauge.sleep();                                          // sleep, () → void
            gauge.wake();                                           // wake up, () → void
            gauge.enableSleep(false);

            gauge.quickStart();                                     // restart the SOC estimate, () → void; only if the first SOC is clearly wrong
            gauge.reset();                                          // power-on reset, () → void; waits 1 s
        }
    }
}
