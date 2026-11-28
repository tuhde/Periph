///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rfid.St25dvFull;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HexFormat;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        HexFormat hex = HexFormat.of();

        // --- Two connections, one chip ---
        // The ST25DV answers on 0x53 (user memory, dynamic registers, mailbox) and on
        // 0x57 (system configuration). Both use 16-bit register addresses.
        try (var user = new I2CConnection(bus, 0x53, null, null, 2);                  // Create user-area connection, (bus, addr=0x53, int=null, en=null, regBytes=2)
             var system = new I2CConnection(bus, 0x57, null, null, 2)) {              // Create system-area connection, (bus, addr=0x57, int=null, en=null, regBytes=2)
            var tag = new St25dvFull(user, system);                                     // Create ST25DV driver, (user, system, gpoActiveHigh=false) → void

            // --- Identify the tag ---
            // IC_REF tells the 04K apart from the 16K/64K; the size comes from MEM_SIZE.
            System.out.printf("IC_REF 0x%02X, revision %d%n", tag.icReference(), tag.icRevision());  // Get IC reference, () → int ; Read IC revision, () → int
            System.out.println("UID " + hex.formatHex(tag.uid()) + " - " + tag.memorySize() + " bytes");  // Read unique ID, () → byte[8] ; Get user memory size, () → int bytes

            // --- NDEF text record a phone can read ---
            // Record header D1 (MB/ME/SR, well-known), type 'T', status 02 (language "en").
            byte[] text = "Hello from Periph".getBytes();
            var rec = new ByteArrayOutputStream();
            rec.writeBytes(new byte[]{(byte) 0xD1, 0x01, (byte) (3 + text.length), 0x54, 0x02, 0x65, 0x6E});
            rec.writeBytes(text);
            byte[] record = rec.toByteArray();
            tag.writeNdef(record);                                                      // Write NDEF message, (message) → void
            System.out.println("NDEF read back: " + Arrays.equals(tag.readNdef(), record));  // Read NDEF message, () → byte[]

            // --- Raw bytes across a 256-byte page boundary ---
            // The driver splits the write into 16-byte-aligned pieces and ACK-polls each.
            byte[] saved = tag.read(0x00F8, 32);                                        // Read user memory, (address, length) → byte[]
            byte[] pattern = new byte[32];
            for (int i = 0; i < 32; i++) pattern[i] = (byte) (0x40 + i);
            tag.write(0x00F8, pattern);                                                 // Write user memory, (address, data) → void
            tag.waitWriteComplete();                                                    // Wait for the write cycle, () → void
            System.out.println("Page-crossing write ok: " + Arrays.equals(tag.read(0x00F8, 32), pattern));  // Read user memory, (address, length) → byte[]
            tag.write(0x00F8, saved);                                                   // Write user memory, (address, data) → void

            // --- Password session and two memory areas ---
            // Area 1 = first 1 KiB (stays open), area 2 = the rest: RF writes need RF
            // password 1, I2C writes need the I2C session. The factory I2C password is 0.
            byte[] pwd = new byte[8];
            System.out.println("Session before: " + tag.sessionOpen());                // Query I²C security session, () → boolean
            System.out.println("Password accepted: " + tag.presentPassword(pwd));      // Present I²C password, (password byte[8]) → boolean
            int end1 = Math.min(tag.memorySize(), 1024) / 32 - 1;
            int last = tag.memorySize() / 32 - 1;
            tag.setAreaEnd(1, end1);                                                    // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
            tag.setAreaEnd(2, last);                                                    // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
            tag.setAreaEnd(3, last);                                                    // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
            tag.setAreaProtection(2, 0x05, 0x01);                                       // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → void
            System.out.println("Area 2 protection: " + tag.getAreaProtection(2));     // Get area protection, (area 1–4) → AreaProtection

            // --- Change and restore the I2C password ---
            byte[] newPwd = hex.parseHex("123456789ABCDEF0");
            tag.writePassword(newPwd);                                                  // Change I²C password, (password byte[8]) → void
            System.out.println("New password accepted: " + tag.presentPassword(newPwd));  // Present I²C password, (password byte[8]) → boolean
            tag.writePassword(pwd);                                                     // Change I²C password, (password byte[8]) → void
            System.out.println("Factory password restored: " + tag.presentPassword(pwd));  // Present I²C password, (password byte[8]) → boolean

            // --- GPO events while a phone approaches ---
            // Field change and RF write events pulse GPO; without a GPO pin the driver
            // polls IT_STS_Dyn every 10 ms. Hold a phone to the antenna now.
            tag.setInterruptPulse(3);                                                   // Set GPO pulse duration, (value 0–7) → void
            tag.setGpo(St25dvFull.GPO_FIELD_CHANGE);                                    // Write static GPO register, (sources, enable=true) → void
            tag.enableInterrupt(St25dvFull.SOURCE_FIELD_RISING | St25dvFull.SOURCE_RF_WRITE);  // Enable interrupt sources, (source) → void
            System.out.printf("GPO register: 0x%02X%n", tag.getGpo());                 // Read static GPO register, () → int
            System.out.printf("Pending before: 0x%02X%n", tag.pollInterrupt());        // Read and clear IT_STS_Dyn, () → int
            tag.onInterrupt(s -> {                                                      // Subscribe to GPO events, (callback, intPin=connection's) → void
                if ((s & St25dvFull.SOURCE_FIELD_RISING) != 0) System.out.println("  GPO event: field on");
                if ((s & St25dvFull.SOURCE_FIELD_FALLING) != 0) System.out.println("  GPO event: field off");
                if ((s & St25dvFull.SOURCE_RF_WRITE) != 0) System.out.println("  GPO event: RF write");
            });
            for (int i = 0; i < 10; i++) {
                System.out.println("  field present: " + tag.fieldPresent());          // Query RF field, () → boolean
                Thread.sleep(1000);
            }
            tag.offInterrupt();                                                         // Unsubscribe, () → void
            tag.disableInterrupt(St25dvFull.SOURCE_RF_WRITE);                           // Disable interrupt sources, (source) → void

            // --- Fast transfer mode: host <-> phone mailbox ---
            // The host puts a message; an RF reader (e.g. the ST25 app) reads it and can
            // answer. The watchdog releases an unread message after 2^(7-1) x 30 ms.
            tag.enableMailbox();                                                        // Enable fast transfer mode, () → void
            tag.setMailboxWatchdog(7);                                                  // Set mailbox watchdog, (value 0–7) → void
            tag.mailboxWrite("hello reader".getBytes());                                // Put mailbox message, (data 1–256 bytes) → void
            for (int i = 0; i < 10; i++) {
                var st = tag.mailboxStatus();                                           // Decode mailbox status, () → MailboxStatus
                if (st.rfPut()) {
                    System.out.println("Reader says: " + new String(tag.mailboxRead()));  // Read mailbox message, () → byte[]
                    break;
                }
                System.out.println("  mailbox: " + st);
                Thread.sleep(1000);
            }
            tag.disableMailbox();                                                       // Disable fast transfer mode, () → void

            // --- Energy harvesting and RF state ---
            tag.setEnergyHarvesting(true);                                              // Control energy harvesting, (enabled, forced=false) → void
            Thread.sleep(1000);
            tag.setEnergyHarvesting(false);                                             // Control energy harvesting, (enabled, forced=false) → void
            tag.setRfState(false, true);                                                // Set RF interface state, (disable, sleep) → void
            System.out.println("RF asleep: a phone no longer sees the tag");
            Thread.sleep(2000);
            tag.setRfState(false, false);                                               // Set RF interface state, (disable, sleep) → void

            // --- Restore the delivery defaults ---
            // One area, no protection, field-change GPO only, mailbox off, CC unlocked.
            tag.setAreaProtection(2, 0x00, 0x00);                                       // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → void
            tag.setAreaEnd(1, last);                                                    // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
            tag.setGpo(St25dvFull.GPO_FIELD_CHANGE);                                    // Write static GPO register, (sources, enable=true) → void
            tag.setGpoRuntime(true);                                                    // Switch GPO output at runtime, (enable) → void
            tag.writeSystem(0x000D, 0x00);                                              // Write system area, (address, value) → void
            System.out.println("MB_MODE: " + tag.readSystem(0x000D, 1)[0]);            // Read system area, (address, length) → byte[]
            tag.lockCcfile(false);                                                      // Lock CC file blocks, (lock) → void
            System.out.println("Done.");
        }
    }
}
