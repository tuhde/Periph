///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rfid.St25dvFull;

import java.util.Arrays;

public class St25dvTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x53").replaceFirst("^0[xX]", ""), 16);

        try (var user = new I2CConnection(bus, addr, null, null, 2);
             var system = new I2CConnection(bus, 0x57, null, null, 2)) {
            var tag = new St25dvFull(user, system);

            checkTrue("ic_reference", tag.icReference() == 0x24 || tag.icReference() == 0x26);
            int size = tag.memorySize();
            checkTrue("memory_size", size == 512 || size == 2048 || size == 8192);
            byte[] uid = tag.uid();
            checkTrue("uid manufacturer", uid.length == 8 && (uid[0] & 0xFF) == 0xE0 && uid[1] == 0x02);
            int rev = tag.icRevision();
            checkTrue("ic_revision", rev >= 0 && rev <= 255);

            int pos = size - 64;
            byte[] saved = tag.read(pos, 40);
            byte[] pattern = new byte[40];
            for (int i = 0; i < 40; i++) pattern[i] = (byte) (i * 7 + 3);
            tag.write(pos, pattern);
            checkTrue("write/read round-trip", Arrays.equals(tag.read(pos, 40), pattern));
            tag.write(pos, saved);
            checkTrue("restore", Arrays.equals(tag.read(pos, 40), saved));
            boolean rejected = false;
            try { tag.read(size - 1, 2); } catch (IllegalArgumentException e) { rejected = true; }
            checkTrue("range rejected", rejected);

            checkTrue("present factory password", tag.presentPassword(new byte[8]));
            checkTrue("session open after present", tag.sessionOpen());
            var prot = tag.getAreaProtection(1);
            checkTrue("get_area_protection", prot.rf() <= 0x0F && prot.i2c() <= 3);
            int gpo = tag.getGpo();
            checkTrue("get_gpo", gpo >= 0 && gpo <= 255);
            tag.fieldPresent();
            checkTrue("field_present", true);
            int it = tag.pollInterrupt();
            checkTrue("poll_interrupt", it >= 0 && it <= 255);
            checkTrue("mailbox_status", tag.mailboxStatus() != null);
            checkTrue("read_ndef", tag.readNdef() != null);
            tag.waitWriteComplete();
            checkTrue("wait_write_complete", true);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
