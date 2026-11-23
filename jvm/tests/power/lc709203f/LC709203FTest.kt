///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-kotlin:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LC709203FFull

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun sT30(g: LC709203FFull): Boolean { g.setTemperature(30.0); return Math.abs(g.temperature() - 30.0) < 0.2 }
fun sApa(g: LC709203FFull): Boolean { g.setApa(0x19); return g.getApa() == 0x19 }
fun sApt(g: LC709203FFull): Boolean { g.setApt(0x1E); return g.getApt() == 0x1E }
fun sAr(g: LC709203FFull): Boolean { g.setAlarmRsoc(20); return g.getAlarmRsoc() == 20 }
fun sAv(g: LC709203FFull): Boolean { g.setAlarmVoltage(3.5); return Math.abs(g.getAlarmVoltage() - 3.5) < 0.001 }
fun sInit(g: LC709203FFull): Boolean { g.initRsoc(); return g.rsoc() <= 100 }
fun sSleep(g: LC709203FFull): Boolean { g.sleep(); return g.isSleeping() }
fun sWake(g: LC709203FFull): Boolean { g.wake(); return !g.isSleeping() }

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.removePrefix("0X")?.toInt(16) ?: 0x0B

    I2CConnection(bus, addr).use { connection ->
        val gauge = LC709203FFull(connection, 1000, 1)

        checkTrue("voltage range", gauge.voltage() >= 2.5 && gauge.voltage() <= 4.6)
        checkTrue("percent range", gauge.percent() >= 0.0 && gauge.percent() <= 100.0)
        checkTrue("temperature range", gauge.temperature() > 0.0 && gauge.temperature() < 60.0)
        checkTrue("rsoc range", gauge.rsoc() <= 100)
        checkTrue("profile_id readable", gauge.profileId() != 0)
        checkTrue("get_profile", gauge.getProfile() == 1)
        checkTrue("set_temperature", sT30(gauge))
        checkTrue("apa", sApa(gauge))
        checkTrue("apt", sApt(gauge))
        checkTrue("alarm_rsoc", sAr(gauge))
        checkTrue("alarm_voltage", sAv(gauge))
        checkTrue("poll_interrupt", gauge.pollInterrupt() <= 3)
        checkTrue("init_rsoc", sInit(gauge))
        checkTrue("sleep", sSleep(gauge))
        checkTrue("wake", sWake(gauge))
        checkTrue("voltage after wake", gauge.voltage() > 0.0)

        println("===DONE: $passed passed, $failed failed===")
        System.exit(if (failed == 0) 0 else 1)
    }
}
