///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rfid.St25dvFull
import java.util.HexFormat

fun main() {
    val bus = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val hex = HexFormat.of()

    I2CConnection(bus, 0x53, null, null, 2).use { user ->                               // Create user-area connection, (bus, addr=0x53, int=null, en=null, regBytes=2)
        I2CConnection(bus, 0x57, null, null, 2).use { system ->                         // Create system-area connection, (bus, addr=0x57, int=null, en=null, regBytes=2)
            val tag = St25dvFull(user, system)                                          // Create ST25DV driver, (user, system, gpoActiveHigh=false) → void
                                                                                        // reads MEM_SIZE, BLK_SIZE and IC_REF from the system area

            println("Size: ${tag.memorySize()} bytes")                                  // Get user memory size, () → Int bytes
                                                                                        // (MEM_SIZE + 1) × (BLK_SIZE + 1)
            println("IC_REF: 0x%02X".format(tag.icReference()))                         // Get IC reference, () → Int
                                                                                        // 0x24 = ST25DV04K, 0x26 = ST25DV16K/64K
            println("UID: " + hex.formatHex(tag.uid()))                                 // Read unique ID, () → ByteArray(8)
                                                                                        // most significant byte first, starts with E0 02
            println("IC_REV: ${tag.icRevision()}")                                      // Read IC revision, () → Int
                                                                                        // IC_REV register 0x0020

            tag.write(0x0100, ByteArray(40) { it.toByte() })                            // Write user memory, (address, data) → void
                                                                                        // split into 16-byte-aligned writes, ACK-polled each
            println("Data: " + hex.formatHex(tag.read(0x0100, 40)))                     // Read user memory, (address, length) → ByteArray
                                                                                        // 16-byte-aligned reads, retried while an RF reader holds the bus
            tag.waitWriteComplete()                                                     // Wait for the write cycle, () → void
                                                                                        // ACK polling on 0x53

            println("IT_TIME: ${tag.readSystem(0x0001, 1)[0]}")                         // Read system area, (address, length) → ByteArray
                                                                                        // raw access to the static registers at 0x57
            println("Session: ${tag.sessionOpen()}")                                    // Query I²C security session, () → Boolean
                                                                                        // I2C_SSO_Dyn bit 0
            println("Password OK: ${tag.presentPassword(ByteArray(8))}")                // Present I²C password, (password ByteArray(8)) → Boolean
                                                                                        // opens the session needed for system-area writes
            tag.writePassword(ByteArray(8))                                             // Change I²C password, (password ByteArray(8)) → void
                                                                                        // here rewritten with the factory value
            tag.writeSystem(0x0001, 0x03)                                               // Write system area, (address, value) → void
                                                                                        // IT_TIME back to its reset value, ACK-polled

            tag.setAreaEnd(1, tag.memorySize() / 32 - 1)                                // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
                                                                                        // area n ends at byte endBlock × 32 + 31
            tag.setAreaProtection(1, 0x00, 0x00)                                        // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → void
                                                                                        // RFAnSS value and the area's I2CSS bits
            println("Area 1 protection: ${tag.getAreaProtection(1)}")                   // Get area protection, (area 1–4) → AreaProtection

            tag.setGpo(St25dvFull.GPO_FIELD_CHANGE)                                     // Write static GPO register, (sources, enable=true) → void
                                                                                        // the chip copies it into GPO_CTRL_Dyn
            println("GPO: 0x%02X".format(tag.getGpo()))                                 // Read static GPO register, () → Int
            tag.setGpoRuntime(true)                                                     // Switch GPO output at runtime, (enable) → void
                                                                                        // GPO_CTRL_Dyn.GPO_EN, no session needed
            tag.setInterruptPulse(3)                                                    // Set GPO pulse duration, (value 0–7) → void
                                                                                        // 301 µs − value × 37.65 µs

            println("Field present: ${tag.fieldPresent()}")                             // Query RF field, () → Boolean
                                                                                        // EH_CTRL_Dyn.FIELD_ON
            tag.setEnergyHarvesting(false)                                              // Control energy harvesting, (enabled, forced=false) → void
                                                                                        // EH_EN now, EH_MODE written only when it changes
            tag.setRfState()                                                            // Set RF interface state, (disable=false, sleep=false) → void
                                                                                        // runtime RF_MNGT_Dyn

            tag.enableMailbox()                                                         // Enable fast transfer mode, () → void
                                                                                        // sets MB_MODE if needed, then MB_CTRL_Dyn.MB_EN
            tag.setMailboxWatchdog(0)                                                   // Set mailbox watchdog, (value 0–7) → void
                                                                                        // 2^(value−1) × 30 ms, 0 disables it
            tag.mailboxWrite("ping".toByteArray())                                      // Put mailbox message, (data 1–256 bytes) → void
                                                                                        // single write starting at 0x2008
            println("Mailbox: ${tag.mailboxStatus()}")                                  // Decode mailbox status, () → MailboxStatus
                                                                                        // enabled, hostPut, rfPut, hostMiss, rfMiss, …
            println("Message: " + String(tag.mailboxRead()))                            // Read mailbox message, () → ByteArray
                                                                                        // MB_LEN_Dyn + 1 bytes, empty if no message
            tag.disableMailbox()                                                        // Disable fast transfer mode, () → void
                                                                                        // clears MB_EN, which also drops the message

            tag.writeNdef(hex.parseHex("D101055402656E486921"))                         // Write NDEF message, (message) → void
                                                                                        // capability container + TLV 03 <len> … FE
            println("NDEF: " + hex.formatHex(tag.readNdef()))                           // Read NDEF message, () → ByteArray
                                                                                        // parses the CC and the first NDEF TLV
            tag.lockCcfile(false)                                                       // Lock CC file blocks, (lock=true) → void
                                                                                        // false keeps blocks 0/1 RF-writable

            tag.enableInterrupt(St25dvFull.SOURCE_FIELD_RISING or St25dvFull.SOURCE_RF_WRITE)  // Enable interrupt sources, (source) → void
                                                                                        // static GPO register bits plus GPO_EN
            tag.onInterrupt({ s -> println("GPO event: 0x%02X".format(s)) })            // Subscribe to GPO events, (callback, intPin=connection's) → void
                                                                                        // 10 ms IT_STS_Dyn polling thread without a GPO pin
            Thread.sleep(100)
            tag.offInterrupt()                                                          // Unsubscribe, () → void
                                                                                        // stops delivery and the polling thread
            println("Pending: 0x%02X".format(tag.pollInterrupt()))                      // Read and clear IT_STS_Dyn, () → Int
                                                                                        // SOURCE_* mask, 0 = none
            tag.disableInterrupt(St25dvFull.SOURCE_RF_WRITE)                            // Disable interrupt sources, (source) → void
                                                                                        // clears the matching GPO enable bits
        }
    }
}
