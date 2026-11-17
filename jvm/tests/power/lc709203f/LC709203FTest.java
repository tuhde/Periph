///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-java:1.5.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.LC709203FFull;

public class LC709203FTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static boolean sT30(LC709203FFull g) throws Exception { g.setTemperature(30.0); return Math.abs(g.temperature() - 30.0) < 0.2; }
    static boolean sApa(LC709203FFull g) throws Exception { g.setApa(0x19); return g.getApa() == 0x19; }
    static boolean sApt(LC709203FFull g) throws Exception { g.setApt(0x1E); return g.getApt() == 0x1E; }
    static boolean sAr(LC709203FFull g) throws Exception { g.setAlarmRsoc(20); return g.getAlarmRsoc() == 20; }
    static boolean sAv(LC709203FFull g) throws Exception { g.setAlarmVoltage(3.5); return Math.abs(g.getAlarmVoltage() - 3.5) < 0.001; }
    static boolean sInit(LC709203FFull g) throws Exception { g.initRsoc(); return g.rsoc() <= 100; }
    static boolean sSleep(LC709203FFull g) throws Exception { g.sleep(); return g.isSleeping(); }
    static boolean sWake(LC709203FFull g) throws Exception { g.wake(); return !g.isSleeping(); }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x0B").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var gauge = new LC709203FFull(connection, 1000, 1);

            checkTrue("voltage range", gauge.voltage() >= 2.5 && gauge.voltage() <= 4.6);
            checkTrue("percent range", gauge.percent() >= 0.0 && gauge.percent() <= 100.0);
            checkTrue("temperature range", gauge.temperature() > 0.0 && gauge.temperature() < 60.0);
            checkTrue("rsoc range", gauge.rsoc() <= 100);
            checkTrue("profile_id readable", gauge.profileId() != 0);
            checkTrue("get_profile", gauge.getProfile() == 1);
            checkTrue("set_temperature", sT30(gauge));
            checkTrue("apa", sApa(gauge));
            checkTrue("apt", sApt(gauge));
            checkTrue("alarm_rsoc", sAr(gauge));
            checkTrue("alarm_voltage", sAv(gauge));
            checkTrue("poll_interrupt", gauge.pollInterrupt() <= 3);
            checkTrue("init_rsoc", sInit(gauge));
            checkTrue("sleep", sSleep(gauge));
            checkTrue("wake", sWake(gauge));
            checkTrue("voltage after wake", gauge.voltage() > 0.0);

            System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
            System.exit(failed == 0 ? 0 : 1);
        }
    }
}
