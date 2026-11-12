///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.DS1881Minimal;
import it.uhde.periph.chips.adc_dac.DS1881Full;

public class DS1881Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x28"));

        try (var connection = new I2CConnection(bus, addr)) {
            var pot = new DS1881Minimal(connection);
            pot.init();
            checkTrue(true, "construct_minimal");
            pot.setAttenuation(0, 20.0);
            pot.setAttenuation(1, 33.0);
            checkTrue(pot.getPosition(0) == 20 && pot.getPosition(1) == 33, "position_roundtrip");
            checkTrue(pot.getAttenuation(0) == 20.0 && pot.getAttenuation(1) == 33.0, "attenuation_roundtrip");
            pot.mute(0);
            checkTrue(pot.getPosition(0) == 63 && pot.getAttenuation(0) == 80.0, "mute");
            pot.setPosition(1, 5);
            checkTrue(pot.getPosition(1) == 5, "set_position");

            var full = new DS1881Full(connection);
            full.init();
            var cfg = full.getConfig();
            checkTrue(cfg.option == 1 && cfg.zeroCrossing && !cfg.nonvolatile, "config_default");
            full.setBoth(10, 40);
            int[] r = full.readAll();
            checkTrue(r[0] == 10 && r[1] == 40, "set_both");
            full.setAttenuationBoth(15.0);
            r = full.readAll();
            checkTrue(r[0] == 15 && r[1] == 15, "set_attenuation_both");
            full.setZeroCrossing(false);
            checkTrue(!full.getConfig().zeroCrossing, "zero_crossing_off");
            full.setZeroCrossing(true);
            full.setOption(2);
            checkTrue(full.getConfig().option == 2 && full.maxPosition() == 32, "option2");
            full.setBoth(13, 33);
            r = full.readAll();
            checkTrue(r[0] == 13 && r[1] == 33 && full.positionToDb(13) == 14.0, "option2_positions");
            full.setOption(1);
            checkTrue(full.getConfig().option == 1, "option1_again");
            full.reset();
            r = full.readAll();
            checkTrue(r[0] == 63 && r[1] == 63, "reset_mutes");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        if (failed > 0) System.exit(1);
    }
}
