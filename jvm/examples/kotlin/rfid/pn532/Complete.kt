///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rfid.Pn532Full
import java.io.IOException

fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x24").replaceFirst("^0[xX]".toRegex(), "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val reader = Pn532Full(connection)                                                          // Create PN532 driver, (connection) → Pn532Full
        // Stores the connection only; no bus traffic yet. I0 low and I1 high select the I²C interface.
        reader.init()                                                                               // Wake, check firmware, SAM normal mode, finite retries, () → Unit
        // GetFirmwareVersion (IC 0x32), SAMConfiguration normal mode, MxRtyPassiveActivation = 2.

        println(reader.firmwareVersion())                                                           // GetFirmwareVersion, () → FirmwareVersion {ic, version, revision, support, iso14443a, iso14443b, iso18092}
        // IC 0x32, firmware 1.6 on current parts; the support bits list the protocols.
        println(reader.generalStatus())                                                             // GetGeneralStatus, () → GeneralStatus {error, field, targets, samStatus}
        // Last error code, external field detected, active targets, SAM status.
        println(reader.selfTest())                                                                  // Diagnose, (test=0x00) → Boolean
        // Communication line test: the chip echoes an 8-byte pattern.
        println(reader.selfTest(0x01))                                                              // Diagnose, (test=0x01) → Boolean
        // ROM checksum test.

        println("UID " + (reader.readUid(1.0)?.hex() ?: "none"))                                    // Detect a type A card, (timeout=1.0 s) → ByteArray?
        // InListPassiveTarget at 106 kbps type A, then InRelease.

        val targets = reader.listPassiveTargets(Pn532Full.BAUD_106_A, 1)                            // InListPassiveTarget, (baud=BAUD_106_A, maxTargets=1, initiatorData=[], timeout=1.0 s) → List<Target>
        // Keeps the target active; tg is the logical number for the next commands.
        targets.firstOrNull()?.let { t ->
            println(t)
            reader.deselect(t.tg)                                                                   // InDeselect, (target=0) → Unit
            // Puts the card to sleep but keeps its information in the PN532.
            reader.select(t.tg)                                                                     // InSelect, (target=0) → Unit
            // Wakes and reselects the deselected card.
            if (t.uid.size == 7) {
                println(reader.ntagReadPage(t.tg, 4).hex())                                         // NTAG READ, (target, page) → ByteArray(16)
                // Returns pages 4..7 (16 bytes).
                reader.ntagWritePage(t.tg, 4, byteArrayOf(1, 2, 3, 4))                              // NTAG WRITE, (target, page, data ByteArray(4)) → Unit
                // Writes one 4-byte user page.
            } else {
                reader.mifareAuthenticate(t.tg, 4, ByteArray(6) { 0xFF.toByte() }, t.uid)           // MIFARE auth, (target, block, key ByteArray(6), uid, keyB=false) → Unit
                // Key A authentication of sector 1 with the transport key.
                val block = reader.mifareReadBlock(t.tg, 4)                                         // MIFARE READ, (target, block) → ByteArray(16)
                // Reads block 4.
                reader.mifareWriteBlock(t.tg, 4, block)                                             // MIFARE WRITE, (target, block, data ByteArray(16)) → Unit
                // Writes the same 16 bytes back.
            }
            println(reader.dataExchange(t.tg, byteArrayOf(0x30, 0x00)).hex())                       // InDataExchange, (target, data) → ByteArray
            // Raw READ of block/page 0 with PN532 framing and CRC.
            reader.release(t.tg)                                                                    // InRelease, (target=0) → Unit
            // Ends the session with the card.
        }

        reader.setRfField(false)                                                                    // RFConfiguration item 1, (on, autoRfca=false) → Unit
        // RF field off; a card in the field loses power.
        reader.setRfField(true)                                                                     // RFConfiguration item 1, (on, autoRfca=false) → Unit
        // RF field back on.
        reader.setMaxRetries(passiveActivation = 2)                                                 // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → Unit
        // 0xFF would retry forever.
        val txMode = reader.readRegister(0x6302)                                                    // ReadRegister, (address) → Int
        // 0x6302 is CIU_TxMode in the PN532's SFR space.
        reader.writeRegister(0x6302, txMode)                                                        // WriteRegister, (address, value) → Unit
        // Writes the value back unchanged.
        println(reader.sendCommand(0x02).hex())                                                     // Raw command, (command, data=[], timeout=1.0 s) → ByteArray
        // GetFirmwareVersion through the raw frame interface.
        try {
            println(reader.communicateThru(byteArrayOf(0x26)).hex())                                // InCommunicateThru, (data) → ByteArray
            // Raw RF exchange with the current target; fails without one.
        } catch (e: IOException) {
            println("communicateThru: ${e.message}")
        }

        reader.startCommand(0x02)                                                                   // Command without waiting, (command, data=[]) → Unit
        // Writes the frame and reads the ACK only.
        println(reader.waitReady(1.0))                                                              // Wait for a frame, (timeout s) → Boolean
        // Polls the status byte every 1 ms.
        println(reader.pollInterrupt())                                                             // Frame ready?, () → Boolean
        // A single status-byte read.
        println(reader.readResponse(0x02).hex())                                                    // Read the pending response, (command, timeout=1.0 s) → ByteArray
        // Header, NACK, full frame, checksums.

        reader.onInterrupt({ println("frame ready") })                                              // Subscribe to IRQ, (callback, intPin=connection.intPin()) → Unit
        // Falling edge on IRQ, or a 10 ms status-byte poll without an IRQ pin.
        reader.sendCommand(0x02)                                                                    // Raw command, (command, data=[], timeout=1.0 s) → ByteArray
        Thread.sleep(100)
        reader.offInterrupt()                                                                       // Unsubscribe, () → Unit
        // Stops the edge handler or the polling thread.

        reader.powerDown()                                                                          // PowerDown, (wakeupSources=0x20) → Unit
        // Sleeps until the next I²C access; the driver retries the wake-up write.
    }
}
