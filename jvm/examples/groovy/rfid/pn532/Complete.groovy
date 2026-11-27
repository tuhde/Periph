///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rfid.Pn532Full

def hex = { byte[] b -> b.encodeHex().toString().toUpperCase() }
def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x24").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def reader = new Pn532Full(connection)                                                          // Create PN532 driver, (connection) → Pn532Full
    // Stores the connection only; no bus traffic yet. I0 low and I1 high select the I²C interface.
    reader.init()                                                                                   // Wake, check firmware, SAM normal mode, finite retries, () → void
    // GetFirmwareVersion (IC 0x32), SAMConfiguration normal mode, MxRtyPassiveActivation = 2.

    println(reader.firmwareVersion())                                                               // GetFirmwareVersion, () → FirmwareVersion {ic, version, revision, support, iso14443a, iso14443b, iso18092}
    // IC 0x32, firmware 1.6 on current parts; the support bits list the protocols.
    println(reader.generalStatus())                                                                 // GetGeneralStatus, () → GeneralStatus {error, field, targets, samStatus}
    // Last error code, external field detected, active targets, SAM status.
    println(reader.selfTest())                                                                      // Diagnose, (test=0x00) → boolean
    // Communication line test: the chip echoes an 8-byte pattern.
    println(reader.selfTest(0x01))                                                                  // Diagnose, (test=0x01) → boolean
    // ROM checksum test.

    byte[] uid = reader.readUid(1.0d)                                                               // Detect a type A card, (timeout=1.0 s) → byte[] | null
    // InListPassiveTarget at 106 kbps type A, then InRelease.
    println("UID " + (uid == null ? "none" : hex(uid)))

    def targets = reader.listPassiveTargets(Pn532Full.BAUD_106_A, 1, new byte[0], 1.0d)            // InListPassiveTarget, (baud=BAUD_106_A, maxTargets=1, initiatorData=[], timeout=1.0 s) → List<Target>
    // Keeps the target active; tg() is the logical number for the next commands.
    if (!targets.isEmpty()) {
        def t = targets[0]
        printf("tg %d SENS_RES 0x%04X SEL_RES 0x%02X UID %s%n", t.tg(), t.sensRes(), t.selRes(), hex(t.uid()))
        reader.deselect(t.tg())                                                                     // InDeselect, (target=0) → void
        // Puts the card to sleep but keeps its information in the PN532.
        reader.select(t.tg())                                                                       // InSelect, (target=0) → void
        // Wakes and reselects the deselected card.
        if (t.uid().length == 7) {
            println(hex(reader.ntagReadPage(t.tg(), 4)))                                            // NTAG READ, (target, page) → byte[16]
            // Returns pages 4..7 (16 bytes).
            reader.ntagWritePage(t.tg(), 4, [1, 2, 3, 4] as byte[])                                 // NTAG WRITE, (target, page, data byte[4]) → void
            // Writes one 4-byte user page.
        } else {
            reader.mifareAuthenticate(t.tg(), 4, ([0xFF] * 6) as byte[], t.uid(), false)           // MIFARE auth, (target, block, key byte[6], uid, keyB=false) → void
            // Key A authentication of sector 1 with the transport key.
            byte[] block = reader.mifareReadBlock(t.tg(), 4)                                        // MIFARE READ, (target, block) → byte[16]
            // Reads block 4.
            reader.mifareWriteBlock(t.tg(), 4, block)                                               // MIFARE WRITE, (target, block, data byte[16]) → void
            // Writes the same 16 bytes back.
        }
        println(hex(reader.dataExchange(t.tg(), [0x30, 0x00] as byte[])))                          // InDataExchange, (target, data) → byte[]
        // Raw READ of block/page 0 with PN532 framing and CRC.
        reader.release(t.tg())                                                                      // InRelease, (target=0) → void
        // Ends the session with the card.
    }

    reader.setRfField(false, false)                                                                 // RFConfiguration item 1, (on, autoRfca=false) → void
    // RF field off; a card in the field loses power.
    reader.setRfField(true, false)                                                                  // RFConfiguration item 1, (on, autoRfca=false) → void
    // RF field back on.
    reader.setMaxRetries(2, 0xFF, 1)                                                                // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → void
    // 0xFF would retry forever.
    int txMode = reader.readRegister(0x6302)                                                        // ReadRegister, (address) → int
    // 0x6302 is CIU_TxMode in the PN532's SFR space.
    reader.writeRegister(0x6302, txMode)                                                            // WriteRegister, (address, value) → void
    // Writes the value back unchanged.
    println(hex(reader.sendCommand(0x02, new byte[0], 1.0d)))                                      // Raw command, (command, data=[], timeout=1.0 s) → byte[]
    // GetFirmwareVersion through the raw frame interface.
    try {
        println(hex(reader.communicateThru([0x26] as byte[])))                                      // InCommunicateThru, (data) → byte[]
        // Raw RF exchange with the current target; fails without one.
    } catch (IOException e) {
        println("communicateThru: " + e.message)
    }

    reader.startCommand(0x02, new byte[0])                                                          // Command without waiting, (command, data=[]) → void
    // Writes the frame and reads the ACK only.
    println(reader.waitReady(1.0d))                                                                 // Wait for a frame, (timeout s) → boolean
    // Polls the status byte every 1 ms.
    println(reader.pollInterrupt())                                                                 // Frame ready?, () → boolean
    // A single status-byte read.
    println(hex(reader.readResponse(0x02, 1.0d)))                                                  // Read the pending response, (command, timeout=1.0 s) → byte[]
    // Header, NACK, full frame, checksums.

    reader.onInterrupt({ println("frame ready") } as Runnable)                                      // Subscribe to IRQ, (callback, intPin=connection.intPin()) → void
    // Falling edge on IRQ, or a 10 ms status-byte poll without an IRQ pin.
    reader.sendCommand(0x02, new byte[0])                                                           // Raw command, (command, data=[], timeout=1.0 s) → byte[]
    Thread.sleep(100)
    reader.offInterrupt()                                                                           // Unsubscribe, () → void
    // Stops the edge handler or the polling thread.

    reader.powerDown(0x20)                                                                          // PowerDown, (wakeupSources=0x20) → void
    // Sleeps until the next I²C access; the driver retries the wake-up write.
} finally {
    connection.close()
}
