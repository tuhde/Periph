'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { PN532Full } = require('../../../src/chips/rfid/pn532');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x24', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x24) → Connection
// The PN532 address is fixed; I0 low and I1 high select the I²C interface.
const reader = new PN532Full(connection);                                // Create PN532 driver, (connection) → void
// Constructor stores the connection only; no bus traffic yet.

async function main() {
    await reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → void
    // GetFirmwareVersion (IC 0x32), SAMConfiguration normal mode, MxRtyPassiveActivation = 2.

    console.log(await reader.firmwareVersion());                         // GetFirmwareVersion, () → {ic, version, revision, support, iso14443a, iso14443b, iso18092}
    // IC 0x32, firmware 1.6 on current parts; the support bits list the protocols.
    console.log(await reader.generalStatus());                           // GetGeneralStatus, () → {error, field, targets, samStatus}
    // Last error code, external field detected, active targets, SAM status.
    console.log(await reader.selfTest());                                // Diagnose, (test=0x00) → boolean
    // Communication line test: the chip echoes an 8-byte pattern.
    console.log(await reader.selfTest(0x01));                            // Diagnose, (test=0x01) → boolean
    // ROM checksum test.

    console.log('UID', await reader.readUid());                          // Detect a type A card, (timeout=1.0 s) → Buffer | null
    // InListPassiveTarget at 106 kbps type A, then InRelease.

    const targets = await reader.listPassiveTargets({ baud: PN532Full.BAUD_106_A, maxTargets: 1 }); // InListPassiveTarget, ({baud=BAUD_106_A, maxTargets=1, initiatorData=[], timeout=1.0 s}) → Target[]
    // Keeps the target active; target.tg is the logical number for the next commands.
    if (targets.length) {
        const t = targets[0];
        console.log(t);
        await reader.deselect(t.tg);                                     // InDeselect, (target=0) → void
        // Puts the card to sleep but keeps its information in the PN532.
        await reader.select(t.tg);                                       // InSelect, (target=0) → void
        // Wakes and reselects the deselected card.
        if (t.uid.length === 7) {
            console.log(await reader.ntagReadPage(t.tg, 4));             // NTAG READ, (target, page) → Buffer (16)
            // Returns pages 4..7 (16 bytes).
            await reader.ntagWritePage(t.tg, 4, [1, 2, 3, 4]);           // NTAG WRITE, (target, page, data 4 bytes) → void
            // Writes one 4-byte user page.
        } else {
            await reader.mifareAuthenticate(t.tg, 4, Buffer.alloc(6, 0xFF), t.uid); // MIFARE auth, (target, block, key 6 bytes, uid, keyB=false) → void
            // Key A authentication of sector 1 with the transport key.
            const data = await reader.mifareReadBlock(t.tg, 4);          // MIFARE READ, (target, block) → Buffer (16)
            // Reads block 4.
            await reader.mifareWriteBlock(t.tg, 4, data);                // MIFARE WRITE, (target, block, data 16 bytes) → void
            // Writes the same 16 bytes back.
        }
        console.log(await reader.dataExchange(t.tg, [0x30, 0x00]));      // InDataExchange, (target, data) → Buffer
        // Raw READ of block/page 0 with PN532 framing and CRC.
        await reader.release(t.tg);                                      // InRelease, (target=0) → void
        // Ends the session with the card.
    }

    await reader.setRfField(false);                                      // RFConfiguration item 1, (on, autoRfca=false) → void
    // RF field off; a card in the field loses power.
    await reader.setRfField(true);                                       // RFConfiguration item 1, (on, autoRfca=false) → void
    // RF field back on.
    await reader.setMaxRetries({ passiveActivation: 2 });                // RFConfiguration item 5, ({passiveActivation=2, atr=0xFF, psl=1}) → void
    // 0xFF would retry forever.
    const txMode = await reader.readRegister(0x6302);                    // ReadRegister, (address) → number
    // 0x6302 is CIU_TxMode in the PN532's SFR space.
    await reader.writeRegister(0x6302, txMode);                          // WriteRegister, (address, value) → void
    // Writes the value back unchanged.
    console.log(await reader.sendCommand(0x02));                         // Raw command, (command, data=[], timeout=1.0 s) → Buffer
    // GetFirmwareVersion through the raw frame interface.
    try {
        console.log(await reader.communicateThru([0x26]));               // InCommunicateThru, (data) → Buffer
        // Raw RF exchange with the current target; fails without one.
    } catch (e) {
        console.log('communicateThru:', e.message);
    }

    await reader.startCommand(0x02);                                     // Command without waiting, (command, data=[]) → void
    // Writes the frame and reads the ACK only.
    console.log(await reader.waitReady(1.0));                            // Wait for a frame, (timeout s) → boolean
    // Polls the status byte every 1 ms.
    console.log(await reader.pollInterrupt());                           // Frame ready?, () → boolean
    // A single status-byte read.
    console.log(await reader.readResponse(0x02));                        // Read the pending response, (command, timeout=1.0 s) → Buffer
    // Header, NACK, full frame, checksums.

    await reader.onInterrupt(() => console.log('frame ready'));          // Subscribe to IRQ, (callback, intPin=null) → void
    // Falling edge on IRQ, or a 10 ms status-byte poll without an IRQ pin.
    await reader.sendCommand(0x02);                                      // Raw command, (command, data=[], timeout=1.0 s) → Buffer
    await sleep(100);
    await reader.offInterrupt();                                         // Unsubscribe, () → void
    // Stops the edge handler or the polling timer.

    await reader.powerDown();                                            // PowerDown, (wakeupSources=0x20) → void
    // Sleeps until the next I²C access; the driver retries the wake-up write.
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
