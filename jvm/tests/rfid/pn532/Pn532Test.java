///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rfid.Pn532Full;

public class Pn532Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x24").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var reader = new Pn532Full(connection);
            reader.init();
            var fw = reader.firmwareVersion();
            checkTrue("firmware_ic", fw.ic() == 0x32);
            checkTrue("firmware_support_a", fw.iso14443a());
            var gs = reader.generalStatus();
            checkTrue("general_status_targets", gs.targets() >= 0 && gs.targets() <= 2);
            checkTrue("self_test_echo", reader.selfTest());
            checkTrue("self_test_rom", reader.selfTest(0x01));
            checkTrue("send_command", reader.sendCommand(0x02, new byte[0])[0] == 0x32);
            reader.startCommand(0x02, new byte[0]);
            checkTrue("wait_ready", reader.waitReady(1.0));
            checkTrue("poll_interrupt", reader.pollInterrupt());
            checkTrue("read_response", reader.readResponse(0x02, 1.0)[0] == 0x32);
            int old = reader.readRegister(0x6302);
            reader.writeRegister(0x6302, old);
            checkTrue("register_roundtrip", reader.readRegister(0x6302) == old);
            reader.setRfField(false, false);
            reader.setRfField(true, false);
            checkTrue("rf_field_toggle", true);
            reader.setMaxRetries(2);
            long t0 = System.currentTimeMillis();
            byte[] uid = reader.readUid();
            checkTrue("read_uid_returns", uid == null || uid.length == 4 || uid.length == 7 || uid.length == 10);
            checkTrue("read_uid_bounded", System.currentTimeMillis() - t0 < 2000);
            var targets = reader.listPassiveTargets();
            checkTrue("list_passive_targets", targets != null);
            if (!targets.isEmpty()) reader.release(targets.get(0).tg());
            reader.powerDown();
            Thread.sleep(10);
            checkTrue("wake_after_power_down", reader.firmwareVersion().ic() == 0x32);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
