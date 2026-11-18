///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-kotlin:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Max17048Full

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun rcomp(g: Max17048Full): Boolean { g.setRcomp(0x97); return g.getRcomp() == 0x97 }
fun comp20(g: Max17048Full): Boolean { g.compensateTemperature(20.0); return g.getRcomp() == 0x97 }
fun alertSoc(g: Max17048Full): Boolean { g.setAlertSoc(10); val ok = g.getAlertSoc() == 10; g.setAlertSoc(4); return ok }
fun vAlert(g: Max17048Full): Boolean { g.setVoltageAlert(3.0, 4.2); val w = g.getVoltageAlert(); g.setVoltageAlert(0.0, 5.1); return Math.abs(w[0] - 3.0) < 0.001 && Math.abs(w[1] - 4.2) < 0.001 }
fun rstV(g: Max17048Full): Boolean { g.setResetVoltage(2.8); val ok = Math.abs(g.getResetVoltage() - 2.8) < 0.001; g.setResetVoltage(3.0); return ok }
fun hibThr(g: Max17048Full): Boolean { g.setHibernateThresholds(26.624, 0.06); val h = g.getHibernateThresholds(); return Math.abs(h[0] - 26.624) < 0.001 && Math.abs(h[1] - 0.06) < 0.001 }
fun clr(g: Max17048Full): Boolean { g.clearStatus(0x3F00); return (g.readStatus() and 0x3F00) == 0 }
fun cfg(g: Max17048Full): Boolean { g.writeConfig(g.readConfig()); return (g.readConfig() shr 8) == 0x97 }
fun sw(g: Max17048Full): Boolean { g.enableSleep(true); g.sleep(); Thread.sleep(10); g.wake(); g.enableSleep(false); return g.voltage() > 0.0 }

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.removePrefix("0X")?.toInt(16) ?: 0x36

    I2CConnection(bus, addr).use { connection ->
        val gauge = Max17048Full(connection, 1)

        checkTrue("voltage range", gauge.voltage() >= 2.5 && gauge.voltage() <= 4.6)
        checkTrue("percent range", gauge.percent() >= 0.0 && gauge.percent() <= 100.0)
        checkTrue("version", (gauge.version() and 0xFFF0) == 0x0010)
        checkTrue("raw_voltage matches voltage", Math.abs((gauge.rawVoltage() shr 4) * 1.25e-3 - gauge.voltage()) < 0.05)
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
        System.exit(if (failed == 0) 0 else 1)
    }
}
