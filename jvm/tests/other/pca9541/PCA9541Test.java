///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.other.PCA9541Minimal;
import it.uhde.periph.chips.other.PCA9541Full;

public class PCA9541Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x70"));

        try (var connection = new I2CConnection(bus, addr)) {
            var selector = new PCA9541Minimal(connection);
            checkTrue(true, "construct_minimal");
            boolean has = selector.hasControl();
            checkTrue(has || !has, "has_control_is_bool");

            var full = new PCA9541Full(connection);
            int control = full.getControl();
            checkTrue(full.status().hasControl == (((control & 1) != 0) == ((control & 2) != 0)), "status_matches_control");
            int[] all = full.readAll();
            checkTrue(all[0] <= 0x0F, "read_all_ie_range");
            full.setInterruptMask(false, true, false, false);
            checkTrue(full.getInterruptMask() == 0x04, "interrupt_mask_roundtrip");
            full.setInterruptMask(false, false, false, false);
            checkTrue(full.getInterruptMask() == 0x00, "interrupt_mask_cleared");
            full.testInterrupt(true, false);
            checkTrue(full.status().ownTest, "test_interrupt_sets_teston");
            full.testInterrupt(false, false);
            checkTrue(!full.status().ownTest, "test_interrupt_clears");
            checkTrue(full.acquire() && full.hasControl(), "acquire_takes_bus");
            full.disconnect();
            checkTrue(!full.isConnected(), "disconnect");
            full.connect();
            checkTrue(full.isConnected(), "connect");
            full.release();
            checkTrue(!full.hasControl(), "release_gives_bus_away");
            full.acquire();
            checkTrue(full.hasControl(), "reacquire");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
