///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.LM25066Full;

public class LM25066Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static boolean avg(LM25066Full g) throws Exception { g.setAveraging(4); boolean ok = g.getAveraging() == 4; g.setAveraging(0); return ok; }
    static boolean uv(LM25066Full g) throws Exception { g.setVinUvWarn(8.0); return Math.abs(g.getLimits().vinUvWarn - 8.0) < 0.05; }
    static boolean uvOff(LM25066Full g) throws Exception { g.setVinUvWarn(null); return g.getLimits().vinUvWarn == null; }
    static boolean ot(LM25066Full g) throws Exception { g.setOtWarn(110.0); boolean ok = Math.abs(g.getLimits().otWarn - 110.0) < 0.1; g.setOtWarn(125.0); return ok; }
    static boolean peak(LM25066Full g) throws Exception { g.clearPowerPeak(); g.clearFaults(); return g.powerPeak() >= 0.0; }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var hotswap = new LM25066Full(connection, 1.0, false);

            checkTrue("model is LM25066", hotswap.model().equals("LM25066"));
            checkTrue("manufacturer id", hotswap.manufacturerId().equals("NSC"));
            checkTrue("vin range", hotswap.voltageIn() >= 0.0 && hotswap.voltageIn() <= 24.0);
            checkTrue("vout range", hotswap.voltageOut() >= 0.0 && hotswap.voltageOut() <= 24.0);
            checkTrue("current readable", hotswap.current() > -1.0);
            checkTrue("power readable", hotswap.power() > -5.0);
            checkTrue("temperature plausible", hotswap.temperature() > -40.0 && hotswap.temperature() < 150.0);
            checkTrue("vaux range", hotswap.voltageAux() >= 0.0 && hotswap.voltageAux() <= 1.2);
            checkTrue("block readable", hotswap.readBlock().power > -5.0);
            checkTrue("avg block readable", hotswap.readAverage().power > -5.0);
            checkTrue("averaging", avg(hotswap));
            checkTrue("vin_uv_warn", uv(hotswap));
            checkTrue("vin_uv_warn disabled", uvOff(hotswap));
            checkTrue("ot_warn", ot(hotswap));
            checkTrue("peak cleared", peak(hotswap));
            checkTrue("status_word readable", hotswap.statusWord() >= 0);
            checkTrue("diagnostic readable", hotswap.diagnosticWord() >= 0);
            checkTrue("capability", hotswap.capability() == 0xB0);

            System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
            System.exit(failed == 0 ? 0 : 1);
        }
    }
}
