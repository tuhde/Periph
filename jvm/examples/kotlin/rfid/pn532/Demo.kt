///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rfid.Pn532Full
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x24").replaceFirst("^0[xX]".toRegex(), "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val reader = Pn532Full(connection)                                                          // Create PN532 driver, (connection) → Pn532Full
        reader.init()                                                                               // Wake, check firmware, SAM normal mode, finite retries, () → Unit

        // --- Identity and health ---
        // IC 0x32 is the PN532; the support byte lists ISO 14443 A/B and ISO 18092.
        // The communication-line self test echoes a pattern through the firmware.
        val fw = reader.firmwareVersion()                                                           // GetFirmwareVersion, () → FirmwareVersion
        println("PN532 IC 0x%02X firmware %d.%d support 0x%02X".format(fw.ic, fw.version, fw.revision, fw.support))
        println("status ${reader.generalStatus()}")                                                 // GetGeneralStatus, () → GeneralStatus
        println("self test ${reader.selfTest()}")                                                   // Diagnose, (test=0x00) → Boolean

        // --- Card polling: 15 s at 1 Hz ---
        // Present a card or tag. The finite retry count makes every poll return
        // after a few milliseconds when the field is empty.
        for (i in 0 until 15) {
            val t = reader.listPassiveTargets().firstOrNull()                                       // InListPassiveTarget, (baud=BAUD_106_A, maxTargets=1, ...) → List<Target>
            if (t != null) {
                println("%2d s UID %s SENS_RES 0x%04X SEL_RES 0x%02X".format(i, t.uid.hex(), t.sensRes, t.selRes))
                reader.release(t.tg)                                                                // InRelease, (target=0) → Unit
            } else {
                println("%2d s no card".format(i))
            }
            Thread.sleep(1000)
        }

        // --- Tag access ---
        // SEL_RES 0x00 is an NTAG / MIFARE Ultralight: read page 4 and write it back.
        // SEL_RES 0x08 is a MIFARE Classic 1K: authenticate sector 1 with the transport
        // key FF FF FF FF FF FF, read block 4 and write it back. Writing sector trailers
        // can lock a sector forever.
        val t = reader.listPassiveTargets().firstOrNull()                                           // InListPassiveTarget, (baud=BAUD_106_A, ...) → List<Target>
        if (t != null) {
            reader.deselect(t.tg)                                                                   // InDeselect, (target=0) → Unit
            reader.select(t.tg)                                                                     // InSelect, (target=0) → Unit
            try {
                if (t.selRes == 0x00) {
                    val page = reader.ntagReadPage(t.tg, 4)                                         // NTAG READ, (target, page) → ByteArray(16)
                    println("pages 4..7 ${page.hex()}")
                    reader.ntagWritePage(t.tg, 4, page.copyOf(4))                                   // NTAG WRITE, (target, page, data ByteArray(4)) → Unit
                    println("raw read ${reader.dataExchange(t.tg, byteArrayOf(0x30, 0x00)).hex()}") // InDataExchange, (target, data) → ByteArray
                } else if (t.selRes and 0x08 != 0) {
                    reader.mifareAuthenticate(t.tg, 4, ByteArray(6) { 0xFF.toByte() }, t.uid)       // MIFARE auth, (target, block, key, uid, keyB=false) → Unit
                    val block = reader.mifareReadBlock(t.tg, 4)                                     // MIFARE READ, (target, block) → ByteArray(16)
                    println("block 4 ${block.hex()}")
                    reader.mifareWriteBlock(t.tg, 4, block)                                         // MIFARE WRITE, (target, block, data ByteArray(16)) → Unit
                }
            } catch (e: IOException) {
                println("tag access failed: ${e.message}")
            }
            reader.release(t.tg)                                                                    // InRelease, (target=0) → Unit
        } else {
            println("no card for the tag access part")
        }

        // --- Raw RF and raw commands ---
        // InCommunicateThru sends bytes without PN532 protocol handling; with no
        // target selected it reports an error. sendCommand reaches every command.
        try {
            println("thru ${reader.communicateThru(byteArrayOf(0x30, 0x00)).hex()}")                // InCommunicateThru, (data) → ByteArray
        } catch (e: IOException) {
            println("communicateThru: ${e.message}")
        }
        println("raw GetFirmwareVersion ${reader.sendCommand(0x02).hex()}")                         // Raw command, (command, data=[], timeout=1.0 s) → ByteArray
        val txMode = reader.readRegister(0x6302)                                                    // ReadRegister, (address) → Int
        println("CIU_TxMode 0x%02X".format(txMode))
        reader.writeRegister(0x6302, txMode)                                                        // WriteRegister, (address, value) → Unit

        // --- Wake on card with onInterrupt ---
        // Infinite activation retries make InListPassiveTarget wait for a card;
        // IRQ (or the status-byte poll) wakes the callback when the response is ready.
        reader.setMaxRetries(passiveActivation = 0xFF)                                              // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → Unit
        val ready = CountDownLatch(1)
        reader.onInterrupt({ ready.countDown() })                                                   // Subscribe to IRQ, (callback, intPin=connection.intPin()) → Unit
        reader.startCommand(0x4A, byteArrayOf(0x01, 0x00))                                          // Command without waiting, (command, data=[]) → Unit
        println("waiting 10 s for a card ...")
        ready.await(10, TimeUnit.SECONDS)
        reader.offInterrupt()                                                                       // Unsubscribe, () → Unit
        if (reader.pollInterrupt() && reader.waitReady(0.01)) {                                     // Frame ready?, () → Boolean; Wait for a frame, (timeout s) → Boolean
            val resp = reader.readResponse(0x4A)                                                    // Read the pending response, (command, timeout=1.0 s) → ByteArray
            println("card arrived, UID ${resp.copyOfRange(6, 6 + resp[5]).hex()}")
            reader.release()                                                                        // InRelease, (target=0) → Unit
        } else {
            try {
                reader.readResponse(0x4A, 0.0)                                                      // Read the pending response, (command, timeout=1.0 s) → ByteArray
            } catch (e: IOException) {
                // timed out: the command was aborted with an ACK frame
            }
            println("no card arrived")
        }
        reader.setMaxRetries()                                                                      // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → Unit
        println("readUid ${reader.readUid()?.hex() ?: "none"}")                                     // Detect a type A card, (timeout=1.0 s) → ByteArray?

        // --- RF field off and on, then power down ---
        // With the field off a card loses power; the reader draws less current.
        reader.setRfField(false)                                                                    // RFConfiguration item 1, (on, autoRfca=false) → Unit
        Thread.sleep(1000)
        reader.setRfField(true)                                                                     // RFConfiguration item 1, (on, autoRfca=false) → Unit
        reader.powerDown()                                                                          // PowerDown, (wakeupSources=0x20) → Unit
        println("powered down; the next I²C access wakes the chip")
    }
}
