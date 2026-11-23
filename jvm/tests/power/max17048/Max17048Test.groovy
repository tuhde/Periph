///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-groovy:1.5.1
import groovy.transform.Field
import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Max17048Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

boolean rcomp(Max17048Full g) { g.setRcomp(0x97); g.getRcomp() == 0x97 }
boolean comp20(Max17048Full g) { g.compensateTemperature(20.0d); g.getRcomp() == 0x97 }
boolean alertSoc(Max17048Full g) { g.setAlertSoc(10); boolean ok = g.getAlertSoc() == 10; g.setAlertSoc(4); ok }
boolean vAlert(Max17048Full g) { g.setVoltageAlert(3.0d, 4.2d); double[] w = g.getVoltageAlert(); g.setVoltageAlert(0.0d, 5.1d); Math.abs(w[0] - 3.0d) < 0.001d && Math.abs(w[1] - 4.2d) < 0.001d }
boolean rstV(Max17048Full g) { g.setResetVoltage(2.8d); boolean ok = Math.abs(g.getResetVoltage() - 2.8d) < 0.001d; g.setResetVoltage(3.0d); ok }
boolean hibThr(Max17048Full g) { g.setHibernateThresholds(26.624d, 0.06d); double[] h = g.getHibernateThresholds(); Math.abs(h[0] - 26.624d) < 0.001d && Math.abs(h[1] - 0.06d) < 0.001d }
boolean clr(Max17048Full g) { g.clearStatus(0x3F00); (g.readStatus() & 0x3F00) == 0 }
boolean cfg(Max17048Full g) { g.writeConfig(g.readConfig()); (g.readConfig() >> 8) == 0x97 }
boolean sw(Max17048Full g) { g.enableSleep(true); g.sleep(); Thread.sleep(10L); g.wake(); g.enableSleep(false); g.voltage() > 0.0d }

int bus  = (System.getenv('I2C_BUS') ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x36').replaceFirst(/^0[xX]/, ''), 16)

def connection = new I2CConnection(bus, addr)
try {
    def gauge = new Max17048Full(connection, 1)

    checkTrue("voltage range", gauge.voltage() >= 2.5 && gauge.voltage() <= 4.6)
    checkTrue("percent range", gauge.percent() >= 0.0 && gauge.percent() <= 100.0)
    checkTrue("version", (gauge.version() & 0xFFF0) == 0x0010)
    checkTrue("raw_voltage matches voltage", Math.abs((gauge.rawVoltage() >> 4) * 1.25e-3 - gauge.voltage()) < 0.05)
    checkTrue("charge_rate readable", Math.abs(gauge.chargeRate()) < 1000.0)
    checkTrue("rcomp", rcomp(gauge))
    checkTrue("compensate 20 C", comp20(gauge))
    checkTrue("alert_soc", alertSoc(gauge))
    checkTrue("voltage_alert", vAlert(gauge))
    checkTrue("reset_voltage", rstV(gauge))
    checkTrue("hibernate_thresholds", hibThr(gauge))
    checkTrue("status flags cleared", clr(gauge))
    checkTrue("poll_interrupt", gauge.pollInterrupt() == 0)
    checkTrue("config", cfg(gauge))
    checkTrue("sleep/wake", sw(gauge))

    println("===DONE: $passed passed, $failed failed===")
    System.exit(failed == 0 ? 0 : 1)
} finally {
    connection.close()
}
