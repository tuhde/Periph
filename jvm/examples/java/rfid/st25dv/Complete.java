///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rfid.St25dvFull;

import java.util.HexFormat;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        HexFormat hex = HexFormat.of();

        try (var user = new I2CConnection(bus, 0x53, null, null, 2);                  // Create user-area connection, (bus, addr=0x53, int=null, en=null, regBytes=2)
             var system = new I2CConnection(bus, 0x57, null, null, 2)) {              // Create system-area connection, (bus, addr=0x57, int=null, en=null, regBytes=2)
            var tag = new St25dvFull(user, system);                                     // Create ST25DV driver, (user, system, gpoActiveHigh=false) → void
                                                                                        // reads MEM_SIZE, BLK_SIZE and IC_REF from the system area

            System.out.println("Size: " + tag.memorySize() + " bytes");                // Get user memory size, () → int bytes
                                                                                        // (MEM_SIZE + 1) × (BLK_SIZE + 1)
            System.out.printf("IC_REF: 0x%02X%n", tag.icReference());                  // Get IC reference, () → int
                                                                                        // 0x24 = ST25DV04K, 0x26 = ST25DV16K/64K
            System.out.println("UID: " + hex.formatHex(tag.uid()));                    // Read unique ID, () → byte[8]
                                                                                        // most significant byte first, starts with E0 02
            System.out.println("IC_REV: " + tag.icRevision());                         // Read IC revision, () → int
                                                                                        // IC_REV register 0x0020

            byte[] data = new byte[40];
            for (int i = 0; i < 40; i++) data[i] = (byte) i;
            tag.write(0x0100, data);                                                    // Write user memory, (address, data) → void
                                                                                        // split into 16-byte-aligned writes, ACK-polled each
            System.out.println("Data: " + hex.formatHex(tag.read(0x0100, 40)));        // Read user memory, (address, length) → byte[]
                                                                                        // 16-byte-aligned reads, retried while an RF reader holds the bus
            tag.waitWriteComplete();                                                    // Wait for the write cycle, () → void
                                                                                        // ACK polling on 0x53

            System.out.println("IT_TIME: " + tag.readSystem(0x0001, 1)[0]);            // Read system area, (address, length) → byte[]
                                                                                        // raw access to the static registers at 0x57
            System.out.println("Session: " + tag.sessionOpen());                       // Query I²C security session, () → boolean
                                                                                        // I2C_SSO_Dyn bit 0
            System.out.println("Password OK: " + tag.presentPassword(new byte[8]));    // Present I²C password, (password byte[8]) → boolean
                                                                                        // opens the session needed for system-area writes
            tag.writePassword(new byte[8]);                                             // Change I²C password, (password byte[8]) → void
                                                                                        // here rewritten with the factory value
            tag.writeSystem(0x0001, 0x03);                                              // Write system area, (address, value) → void
                                                                                        // IT_TIME back to its reset value, ACK-polled

            tag.setAreaEnd(1, tag.memorySize() / 32 - 1);                              // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
                                                                                        // area n ends at byte endBlock × 32 + 31
            tag.setAreaProtection(1, 0x00, 0x00);                                       // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → void
                                                                                        // RFAnSS value and the area's I2CSS bits
            System.out.println("Area 1 protection: " + tag.getAreaProtection(1));     // Get area protection, (area 1–4) → AreaProtection

            tag.setGpo(St25dvFull.GPO_FIELD_CHANGE);                                    // Write static GPO register, (sources, enable=true) → void
                                                                                        // the chip copies it into GPO_CTRL_Dyn
            System.out.printf("GPO: 0x%02X%n", tag.getGpo());                          // Read static GPO register, () → int
            tag.setGpoRuntime(true);                                                    // Switch GPO output at runtime, (enable) → void
                                                                                        // GPO_CTRL_Dyn.GPO_EN, no session needed
            tag.setInterruptPulse(3);                                                   // Set GPO pulse duration, (value 0–7) → void
                                                                                        // 301 µs − value × 37.65 µs

            System.out.println("Field present: " + tag.fieldPresent());                // Query RF field, () → boolean
                                                                                        // EH_CTRL_Dyn.FIELD_ON
            tag.setEnergyHarvesting(false);                                             // Control energy harvesting, (enabled, forced=false) → void
                                                                                        // EH_EN now, EH_MODE written only when it changes
            tag.setRfState(false, false);                                               // Set RF interface state, (disable, sleep) → void
                                                                                        // runtime RF_MNGT_Dyn

            tag.enableMailbox();                                                        // Enable fast transfer mode, () → void
                                                                                        // sets MB_MODE if needed, then MB_CTRL_Dyn.MB_EN
            tag.setMailboxWatchdog(0);                                                  // Set mailbox watchdog, (value 0–7) → void
                                                                                        // 2^(value−1) × 30 ms, 0 disables it
            tag.mailboxWrite("ping".getBytes());                                        // Put mailbox message, (data 1–256 bytes) → void
                                                                                        // single write starting at 0x2008
            System.out.println("Mailbox: " + tag.mailboxStatus());                     // Decode mailbox status, () → MailboxStatus
                                                                                        // enabled, hostPut, rfPut, hostMiss, rfMiss, …
            System.out.println("Message: " + new String(tag.mailboxRead()));           // Read mailbox message, () → byte[]
                                                                                        // MB_LEN_Dyn + 1 bytes, empty if no message
            tag.disableMailbox();                                                       // Disable fast transfer mode, () → void
                                                                                        // clears MB_EN, which also drops the message

            tag.writeNdef(hex.parseHex("D101055402656E486921"));                       // Write NDEF message, (message) → void
                                                                                        // capability container + TLV 03 <len> … FE
            System.out.println("NDEF: " + hex.formatHex(tag.readNdef()));              // Read NDEF message, () → byte[]
                                                                                        // parses the CC and the first NDEF TLV
            tag.lockCcfile(false);                                                      // Lock CC file blocks, (lock) → void
                                                                                        // false keeps blocks 0/1 RF-writable

            tag.enableInterrupt(St25dvFull.SOURCE_FIELD_RISING | St25dvFull.SOURCE_RF_WRITE);  // Enable interrupt sources, (source) → void
                                                                                        // static GPO register bits plus GPO_EN
            tag.onInterrupt(s -> System.out.printf("GPO event: 0x%02X%n", s));        // Subscribe to GPO events, (callback, intPin=connection's) → void
                                                                                        // 10 ms IT_STS_Dyn polling thread without a GPO pin
            Thread.sleep(100);
            tag.offInterrupt();                                                         // Unsubscribe, () → void
                                                                                        // stops delivery and the polling thread
            System.out.printf("Pending: 0x%02X%n", tag.pollInterrupt());               // Read and clear IT_STS_Dyn, () → int
                                                                                        // SOURCE_* mask, 0 = none
            tag.disableInterrupt(St25dvFull.SOURCE_RF_WRITE);                           // Disable interrupt sources, (source) → void
                                                                                        // clears the matching GPO enable bits
        }
    }
}
