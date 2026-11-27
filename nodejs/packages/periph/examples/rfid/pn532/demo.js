'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { PN532Full } = require('../../../src/chips/rfid/pn532');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x24', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const hex = (b) => Buffer.from(b).toString('hex').toUpperCase();

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x24) → Connection
const reader = new PN532Full(connection);                                // Create PN532 driver, (connection) → void

async function main() {
    await reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → void

    // --- Identity and health ---
    // IC 0x32 is the PN532; the support byte lists ISO 14443 A/B and ISO 18092.
    // The communication-line self test echoes a pattern through the firmware.
    const fw = await reader.firmwareVersion();                           // GetFirmwareVersion, () → object
    console.log(`PN532 IC 0x${fw.ic.toString(16)} firmware ${fw.version}.${fw.revision} support 0x${fw.support.toString(16)}`);
    console.log('status', await reader.generalStatus());                 // GetGeneralStatus, () → object
    console.log('self test', await reader.selfTest());                   // Diagnose, (test=0x00) → boolean

    // --- Card polling: 15 s at 1 Hz ---
    // Present a card or tag. The finite retry count makes every poll return
    // after a few milliseconds when the field is empty.
    for (let i = 0; i < 15; i++) {
        const targets = await reader.listPassiveTargets();               // InListPassiveTarget, ({baud=BAUD_106_A, maxTargets=1, ...}) → Target[]
        if (targets.length) {
            const t = targets[0];
            console.log(`${i} s  UID ${hex(t.uid)}  SENS_RES 0x${t.sensRes.toString(16)}  SEL_RES 0x${t.selRes.toString(16)}`);
            await reader.release(t.tg);                                  // InRelease, (target=0) → void
        } else {
            console.log(`${i} s  no card`);
        }
        await sleep(1000);
    }

    // --- Tag access ---
    // SEL_RES 0x00 is an NTAG / MIFARE Ultralight: read page 4 and write it back.
    // SEL_RES 0x08 is a MIFARE Classic 1K: authenticate sector 1 with the transport
    // key FF FF FF FF FF FF, read block 4 and write it back. Writing sector
    // trailers can lock a sector forever.
    const targets = await reader.listPassiveTargets();                   // InListPassiveTarget, ({baud=BAUD_106_A, ...}) → Target[]
    if (targets.length) {
        const t = targets[0];
        await reader.deselect(t.tg);                                     // InDeselect, (target=0) → void
        await reader.select(t.tg);                                       // InSelect, (target=0) → void
        try {
            if (t.selRes === 0x00) {
                const page = await reader.ntagReadPage(t.tg, 4);         // NTAG READ, (target, page) → Buffer (16)
                console.log('pages 4..7', hex(page));
                await reader.ntagWritePage(t.tg, 4, page.subarray(0, 4)); // NTAG WRITE, (target, page, data 4 bytes) → void
                console.log('raw read', hex(await reader.dataExchange(t.tg, [0x30, 0x00]))); // InDataExchange, (target, data) → Buffer
            } else if (t.selRes & 0x08) {
                await reader.mifareAuthenticate(t.tg, 4, Buffer.alloc(6, 0xFF), t.uid); // MIFARE auth, (target, block, key, uid, keyB=false) → void
                const block = await reader.mifareReadBlock(t.tg, 4);     // MIFARE READ, (target, block) → Buffer (16)
                console.log('block 4', hex(block));
                await reader.mifareWriteBlock(t.tg, 4, block);           // MIFARE WRITE, (target, block, data 16 bytes) → void
            }
        } catch (e) {
            console.log('tag access failed:', e.message);
        }
        await reader.release(t.tg);                                      // InRelease, (target=0) → void
    } else {
        console.log('no card for the tag access part');
    }

    // --- Raw RF and raw commands ---
    // InCommunicateThru sends bytes without PN532 protocol handling; with no
    // target selected it reports an error. sendCommand reaches every command.
    try {
        console.log('thru', hex(await reader.communicateThru([0x30, 0x00]))); // InCommunicateThru, (data) → Buffer
    } catch (e) {
        console.log('communicateThru:', e.message);
    }
    console.log('raw GetFirmwareVersion', hex(await reader.sendCommand(0x02))); // Raw command, (command, data=[], timeout=1.0 s) → Buffer
    const txMode = await reader.readRegister(0x6302);                    // ReadRegister, (address) → number
    console.log(`CIU_TxMode 0x${txMode.toString(16)}`);
    await reader.writeRegister(0x6302, txMode);                          // WriteRegister, (address, value) → void

    // --- Wake on card with onInterrupt ---
    // Infinite activation retries make InListPassiveTarget wait for a card;
    // IRQ (or the status-byte poll) wakes the callback when the response is ready.
    await reader.setMaxRetries({ passiveActivation: 0xFF });             // RFConfiguration item 5, ({passiveActivation=2, atr=0xFF, psl=1}) → void
    let ready = false;
    await reader.onInterrupt(() => { ready = true; });                   // Subscribe to IRQ, (callback, intPin=null) → void
    await reader.startCommand(0x4A, [0x01, 0x00]);                       // Command without waiting, (command, data=[]) → void
    console.log('waiting 10 s for a card ...');
    for (let i = 0; i < 100 && !ready; i++) {
        if (await reader.pollInterrupt()) break;                         // Frame ready?, () → boolean
        await sleep(100);
    }
    await reader.offInterrupt();                                         // Unsubscribe, () → void
    if (await reader.waitReady(0.01)) {                                  // Wait for a frame, (timeout s) → boolean
        const resp = await reader.readResponse(0x4A);                    // Read the pending response, (command, timeout=1.0 s) → Buffer
        console.log('card arrived, UID', hex(resp.subarray(6, 6 + resp[5])));
        await reader.release();                                          // InRelease, (target=0) → void
    } else {
        try {
            await reader.readResponse(0x4A, 0);                          // Read the pending response, (command, timeout=1.0 s) → Buffer
        } catch (e) {
            /* timed out: the command was aborted with an ACK frame */
        }
        console.log('no card arrived');
    }
    await reader.setMaxRetries();                                        // RFConfiguration item 5, ({passiveActivation=2, atr=0xFF, psl=1}) → void
    console.log('readUid', await reader.readUid());                      // Detect a type A card, (timeout=1.0 s) → Buffer | null

    // --- RF field off and on, then power down ---
    // With the field off a card loses power; the reader draws less current.
    await reader.setRfField(false);                                      // RFConfiguration item 1, (on, autoRfca=false) → void
    await sleep(1000);
    await reader.setRfField(true);                                       // RFConfiguration item 1, (on, autoRfca=false) → void
    await reader.powerDown();                                            // PowerDown, (wakeupSources=0x20) → void
    console.log('powered down; the next I²C access wakes the chip');
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
