///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Max17048Full;

public class Max17048Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static boolean rcomp(Max17048Full g) throws Exception { g.setRcomp(0x97); return g.getRcomp() == 0x97; }
    static boolean comp20(Max17048Full g) throws Exception { g.compensateTemperature(20.0); return g.getRcomp() == 0x97; }
    static boolean alertSoc(Max17048Full g) throws Exception { g.setAlertSoc(10); boolean ok = g.getAlertSoc() == 10; g.setAlertSoc(4); return ok; }
    static boolean vAlert(Max17048Full g) throws Exception { g.setVoltageAlert(3.0, 4.2); double[] w = g.getVoltageAlert(); g.setVoltageAlert(0, 5.1); return Math.abs(w[0] - 3.0) < 0.001 && Math.abs(w[1] - 4.2) < 0.001; }
    static boolean rstV(Max17048Full g) throws Exception { g.setResetVoltage(2.8); boolean ok = Math.abs(g.getResetVoltage() - 2.8) < 0.001; g.setResetVoltage(3.0); return ok; }
    static boolean hibThr(Max17048Full g) throws Exception { g.setHibernateThresholds(26.624, 0.06); double[] h = g.getHibernateThresholds(); return Math.abs(h[0] - 26.624) < 0.001 && Math.abs(h[1] - 0.06) < 0.001; }
    static boolean clr(Max17048Full g) throws Exception { g.clearStatus(0x3F00); return (g.readStatus() & 0x3F00) == 0; }
    static boolean cfg(Max17048Full g) throws Exception { g.writeConfig(g.readConfig()); return (g.readConfig() >> 8) == 0x97; }
    static boolean sw(Max17048Full g) throws Exception { g.enableSleep(true); g.sleep(); Thread.sleep(10); g.wake(); g.enableSleep(false); return g.voltage() > 0.0; }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x36").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var gauge = new Max17048Full(connection, 1);

            checkTrue("voltage range", gauge.voltage() >= 2.5 && gauge.voltage() <= 4.6);
            checkTrue("percent range", gauge.percent() >= 0.0 && gauge.percent() <= 100.0);
            checkTrue("version", (gauge.version() & 0xFFF0) == 0x0010);
            checkTrue("raw_voltage matches voltage", Math.abs((gauge.rawVoltage() >> 4) * 1.25e-3 - gauge.voltage()) < 0.05);
            checkTrue("charge_rate readable", Math.abs(gauge.chargeRate()) < 1000.0);
            checkTrue("rcomp", rcomp(gauge));
            checkTrue("compensate 20 C", comp20(gauge));
            checkTrue("alert_soc", alertSoc(gauge));
            checkTrue("voltage_alert", vAlert(gauge));
            checkTrue("reset_voltage", rstV(gauge));
            checkTrue("hibernate_thresholds", hibThr(gauge));
            checkTrue("status flags cleared", clr(gauge));
            checkTrue("poll_interrupt", gauge.pollInterrupt() == 0);
            checkTrue("config", cfg(gauge));
            checkTrue("sleep/wake", sw(gauge));

            System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
            System.exit(failed == 0 ? 0 : 1);
        }
    }
}
