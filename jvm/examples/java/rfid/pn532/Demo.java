///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rfid.Pn532Full;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x24").replaceFirst("^0[xX]", ""), 16);
        var hex = HexFormat.of().withUpperCase();

        try (var connection = new I2CConnection(bus, addr)) {
            var reader = new Pn532Full(connection);                                                 // Create PN532 driver, (connection) → Pn532Full
            reader.init();                                                                          // Wake, check firmware, SAM normal mode, finite retries, () → void

            // --- Identity and health ---
            // IC 0x32 is the PN532; the support byte lists ISO 14443 A/B and ISO 18092.
            // The communication-line self test echoes a pattern through the firmware.
            var fw = reader.firmwareVersion();                                                      // GetFirmwareVersion, () → FirmwareVersion
            System.out.printf("PN532 IC 0x%02X firmware %d.%d support 0x%02X%n", fw.ic(), fw.version(), fw.revision(), fw.support());
            System.out.println("status " + reader.generalStatus());                                 // GetGeneralStatus, () → GeneralStatus
            System.out.println("self test " + reader.selfTest());                                   // Diagnose, (test=0x00) → boolean

            // --- Card polling: 15 s at 1 Hz ---
            // Present a card or tag. The finite retry count makes every poll return
            // after a few milliseconds when the field is empty.
            for (int i = 0; i < 15; i++) {
                var targets = reader.listPassiveTargets();                                          // InListPassiveTarget, (baud=BAUD_106_A, maxTargets=1, ...) → List<Target>
                if (!targets.isEmpty()) {
                    var t = targets.get(0);
                    System.out.printf("%2d s UID %s SENS_RES 0x%04X SEL_RES 0x%02X%n", i, hex.formatHex(t.uid()), t.sensRes(), t.selRes());
                    reader.release(t.tg());                                                         // InRelease, (target=0) → void
                } else {
                    System.out.printf("%2d s no card%n", i);
                }
                Thread.sleep(1000);
            }

            // --- Tag access ---
            // SEL_RES 0x00 is an NTAG / MIFARE Ultralight: read page 4 and write it back.
            // SEL_RES 0x08 is a MIFARE Classic 1K: authenticate sector 1 with the transport
            // key FF FF FF FF FF FF, read block 4 and write it back. Writing sector trailers
            // can lock a sector forever.
            var targets = reader.listPassiveTargets();                                              // InListPassiveTarget, (baud=BAUD_106_A, ...) → List<Target>
            if (!targets.isEmpty()) {
                var t = targets.get(0);
                reader.deselect(t.tg());                                                            // InDeselect, (target=0) → void
                reader.select(t.tg());                                                              // InSelect, (target=0) → void
                try {
                    if (t.selRes() == 0x00) {
                        byte[] page = reader.ntagReadPage(t.tg(), 4);                               // NTAG READ, (target, page) → byte[16]
                        System.out.println("pages 4..7 " + hex.formatHex(page));
                        reader.ntagWritePage(t.tg(), 4, Arrays.copyOf(page, 4));                    // NTAG WRITE, (target, page, data byte[4]) → void
                        System.out.println("raw read " + hex.formatHex(reader.dataExchange(t.tg(), new byte[]{0x30, 0x00}))); // InDataExchange, (target, data) → byte[]
                    } else if ((t.selRes() & 0x08) != 0) {
                        byte[] key = new byte[6];
                        Arrays.fill(key, (byte) 0xFF);
                        reader.mifareAuthenticate(t.tg(), 4, key, t.uid(), false);                  // MIFARE auth, (target, block, key, uid, keyB=false) → void
                        byte[] block = reader.mifareReadBlock(t.tg(), 4);                           // MIFARE READ, (target, block) → byte[16]
                        System.out.println("block 4 " + hex.formatHex(block));
                        reader.mifareWriteBlock(t.tg(), 4, block);                                  // MIFARE WRITE, (target, block, data byte[16]) → void
                    }
                } catch (IOException e) {
                    System.out.println("tag access failed: " + e.getMessage());
                }
                reader.release(t.tg());                                                             // InRelease, (target=0) → void
            } else {
                System.out.println("no card for the tag access part");
            }

            // --- Raw RF and raw commands ---
            // InCommunicateThru sends bytes without PN532 protocol handling; with no
            // target selected it reports an error. sendCommand reaches every command.
            try {
                System.out.println("thru " + hex.formatHex(reader.communicateThru(new byte[]{0x30, 0x00}))); // InCommunicateThru, (data) → byte[]
            } catch (IOException e) {
                System.out.println("communicateThru: " + e.getMessage());
            }
            System.out.println("raw GetFirmwareVersion " + hex.formatHex(reader.sendCommand(0x02, new byte[0]))); // Raw command, (command, data=[], timeout=1.0 s) → byte[]
            int txMode = reader.readRegister(0x6302);                                               // ReadRegister, (address) → int
            System.out.printf("CIU_TxMode 0x%02X%n", txMode);
            reader.writeRegister(0x6302, txMode);                                                   // WriteRegister, (address, value) → void

            // --- Wake on card with onInterrupt ---
            // Infinite activation retries make InListPassiveTarget wait for a card;
            // IRQ (or the status-byte poll) wakes the callback when the response is ready.
            reader.setMaxRetries(0xFF);                                                             // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → void
            var ready = new java.util.concurrent.CountDownLatch(1);
            reader.onInterrupt(ready::countDown);                                                   // Subscribe to IRQ, (callback, intPin=connection.intPin()) → void
            reader.startCommand(0x4A, new byte[]{0x01, 0x00});                                      // Command without waiting, (command, data=[]) → void
            System.out.println("waiting 10 s for a card ...");
            ready.await(10, java.util.concurrent.TimeUnit.SECONDS);
            reader.offInterrupt();                                                                  // Unsubscribe, () → void
            if (reader.pollInterrupt() && reader.waitReady(0.01)) {                                 // Frame ready?, () → boolean; Wait for a frame, (timeout s) → boolean
                byte[] resp = reader.readResponse(0x4A, 1.0);                                       // Read the pending response, (command, timeout=1.0 s) → byte[]
                System.out.println("card arrived, UID " + hex.formatHex(Arrays.copyOfRange(resp, 6, 6 + resp[5])));
                reader.release(0);                                                                  // InRelease, (target=0) → void
            } else {
                try {
                    reader.readResponse(0x4A, 0);                                                   // Read the pending response, (command, timeout=1.0 s) → byte[]
                } catch (IOException e) {
                    // timed out: the command was aborted with an ACK frame
                }
                System.out.println("no card arrived");
            }
            reader.setMaxRetries(2);                                                                // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → void
            byte[] uid = reader.readUid();                                                          // Detect a type A card, (timeout=1.0 s) → byte[] | null
            System.out.println("readUid " + (uid == null ? "none" : hex.formatHex(uid)));

            // --- RF field off and on, then power down ---
            // With the field off a card loses power; the reader draws less current.
            reader.setRfField(false, false);                                                        // RFConfiguration item 1, (on, autoRfca=false) → void
            Thread.sleep(1000);
            reader.setRfField(true, false);                                                         // RFConfiguration item 1, (on, autoRfca=false) → void
            reader.powerDown();                                                                     // PowerDown, (wakeupSources=0x20) → void
            System.out.println("powered down; the next I²C access wakes the chip");
        }
    }
}
