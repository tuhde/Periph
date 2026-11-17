///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-groovy:1.5.0
import groovy.transform.Field
import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LC709203FFull

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

boolean sT30(LC709203FFull g) { g.setTemperature(30.0d); Math.abs(g.temperature() - 30.0d) < 0.2d }
boolean sApa(LC709203FFull g) { g.setApa(0x19); g.getApa() == 0x19 }
boolean sApt(LC709203FFull g) { g.setApt(0x1E); g.getApt() == 0x1E }
boolean sAr(LC709203FFull g) { g.setAlarmRsoc(20); g.getAlarmRsoc() == 20 }
boolean sAv(LC709203FFull g) { g.setAlarmVoltage(3.5d); Math.abs(g.getAlarmVoltage() - 3.5d) < 0.001d }
boolean sInit(LC709203FFull g) { g.initRsoc(); g.rsoc() <= 100 }
boolean sSleep(LC709203FFull g) { g.sleep(); g.isSleeping() }
boolean sWake(LC709203FFull g) { g.wake(); !g.isSleeping() }

int bus  = (System.getenv('I2C_BUS') ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x0B').replaceFirst(/^0[xX]/, ''), 16)

def connection = new I2CConnection(bus, addr)
try {
    def gauge = new LC709203FFull(connection, 1000, 1)

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
    System.exit(failed == 0 ? 0 : 1)
} finally {
    connection.close()
}
