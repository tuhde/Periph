///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gas.Rrh46410Full;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Rrh46410Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Rrh46410Full(connection);
            sensor.init();
            checkTrue("product_id", sensor.productId().productId() == 0x2310);
            checkTrue("tracking_number", sensor.trackingNumber().length == 6);
            checkTrue("mode_iaq", sensor.getMode() == Rrh46410Full.MODE_IAQ_2ND_GEN);
            sensor.setHumidity(50);
            checkTrue("status_ok", sensor.status().raw() == 0);
            var r = sensor.read();
            checkTrue("read_iaq_range", r.iaq() >= 0 && r.iaq() <= 25.5);
            checkTrue("read_tvoc_range", r.tvocMgM3() >= 0 && r.tvocMgM3() <= 655.35);
            checkTrue("read_not_warmed_up", !r.warmedUp());
            checkTrue("wait_for_sample", sensor.waitForSample(5.0));
            checkTrue("read_results", sensor.readResults() != null);
            checkTrue("poll_interrupt_false", !sensor.pollInterrupt());
            List<Object> hits = Collections.synchronizedList(new ArrayList<>());
            sensor.onInterrupt(hits::add);
            Thread.sleep(7000);
            sensor.offInterrupt();
            checkTrue("on_interrupt", !hits.isEmpty());
            sensor.setMode(Rrh46410Full.MODE_PBAQ);
            checkTrue("mode_pbaq", sensor.getMode() == Rrh46410Full.MODE_PBAQ);
            checkTrue("read_pbaq", sensor.read().pbaq());
            sensor.configGpio(0x03);
            sensor.setGpio(0x01);
            checkTrue("set_gpio", (sensor.getGpio() & 0x01) == 1);
            sensor.clearGpio(0x01);
            checkTrue("clear_gpio", (sensor.getGpio() & 0x01) == 0);
            checkTrue("send_command", sensor.sendCommand(0x10, new byte[0], 1)[0] == Rrh46410Full.MODE_PBAQ);
            sensor.suspend();
            checkTrue("suspend", sensor.getMode() == Rrh46410Full.MODE_SUSPEND);
            sensor.reset();
            checkTrue("reset_suspend", sensor.getMode() == Rrh46410Full.MODE_SUSPEND);
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
