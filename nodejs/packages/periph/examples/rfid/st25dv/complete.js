'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ST25DVFull, ST25DVSource } = require('../../../src/chips/rfid/st25dv');

async function main() {
    const user = new I2CConnection(1, 0x53, null, null, 2);                     // Create user-area connection, (bus=1, addr=0x53, intPin=null, enPin=null, regBytes=2) → Connection
    const system = new I2CConnection(1, 0x57, null, null, 2);                   // Create system-area connection, (bus=1, addr=0x57, intPin=null, enPin=null, regBytes=2) → Connection
    const tag = new ST25DVFull(user, system);                                   // Create ST25DV driver, (user, system, gpoActiveHigh=false) → void
    await tag.init();                                                           // Read MEM_SIZE, BLK_SIZE, IC_REF, () → void
                                                                                // derives the user memory size from the system area

    console.log('Size:', tag.memorySize(), 'bytes');                            // Get user memory size, () → number bytes
                                                                                // (MEM_SIZE + 1) × (BLK_SIZE + 1)
    console.log('IC_REF: 0x' + tag.icReference().toString(16));                 // Get IC reference, () → number
                                                                                // 0x24 = ST25DV04K, 0x26 = ST25DV16K/64K
    console.log('UID:', (await tag.uid()).toString('hex'));                     // Read unique ID, () → Buffer (8)
                                                                                // most significant byte first, starts with E0 02
    console.log('IC_REV:', await tag.icRevision());                             // Read IC revision, () → number
                                                                                // IC_REV register 0x0020

    await tag.write(0x0100, Buffer.from(Array.from({ length: 40 }, (_, i) => i)));  // Write user memory, (address, data) → void
                                                                                // split into 16-byte-aligned writes, ACK-polled each
    console.log('Data:', (await tag.read(0x0100, 40)).toString('hex'));         // Read user memory, (address, length) → Buffer
                                                                                // 16-byte-aligned reads, retried while an RF reader holds the bus
    await tag.waitWriteComplete();                                              // Wait for the write cycle, () → void
                                                                                // ACK polling on 0x53

    console.log('IT_TIME:', (await tag.readSystem(0x0001, 1))[0]);              // Read system area, (address, length) → Buffer
                                                                                // raw access to the static registers at 0x57
    console.log('Session:', await tag.sessionOpen());                           // Query I²C security session, () → boolean
                                                                                // I2C_SSO_Dyn bit 0
    console.log('Password OK:', await tag.presentPassword(Buffer.alloc(8)));    // Present I²C password, (password 8 bytes) → boolean
                                                                                // opens the session needed for system-area writes
    await tag.writePassword(Buffer.alloc(8));                                   // Change I²C password, (password 8 bytes) → void
                                                                                // here rewritten with the factory value
    await tag.writeSystem(0x0001, 0x03);                                        // Write system area, (address, data) → void
                                                                                // IT_TIME back to its reset value, ACK-polled

    await tag.setAreaEnd(1, tag.memorySize() / 32 - 1);                         // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
                                                                                // area n ends at byte endBlock × 32 + 31
    await tag.setAreaProtection(1, 0x00, 0x00);                                 // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → void
                                                                                // RFAnSS value and the area's I2CSS bits
    console.log('Area 1 protection:', await tag.getAreaProtection(1));          // Get area protection, (area 1–4) → {rf, i2c}

    await tag.setGpo(ST25DVFull.GPO_FIELD_CHANGE);                              // Write static GPO register, (sources, enable=true) → void
                                                                                // the chip copies it into GPO_CTRL_Dyn
    console.log('GPO: 0x' + (await tag.getGpo()).toString(16));                 // Read static GPO register, () → number
    await tag.setGpoRuntime(true);                                              // Switch GPO output at runtime, (enable) → void
                                                                                // GPO_CTRL_Dyn.GPO_EN, no session needed
    await tag.setInterruptPulse(3);                                             // Set GPO pulse duration, (value 0–7) → void
                                                                                // 301 µs − value × 37.65 µs

    console.log('Field present:', await tag.fieldPresent());                    // Query RF field, () → boolean
                                                                                // EH_CTRL_Dyn.FIELD_ON
    await tag.setEnergyHarvesting(false);                                       // Control energy harvesting, (enabled, forced=false) → void
                                                                                // EH_EN now, EH_MODE written only when it changes
    await tag.setRfState();                                                     // Set RF interface state, (disable=false, sleep=false) → void
                                                                                // runtime RF_MNGT_Dyn

    await tag.enableMailbox();                                                  // Enable fast transfer mode, () → void
                                                                                // sets MB_MODE if needed, then MB_CTRL_Dyn.MB_EN
    await tag.setMailboxWatchdog(0);                                            // Set mailbox watchdog, (value 0–7) → void
                                                                                // 2^(value−1) × 30 ms, 0 disables it
    await tag.mailboxWrite(Buffer.from('ping'));                                // Put mailbox message, (data 1–256 bytes) → void
                                                                                // single write starting at 0x2008
    console.log('Mailbox:', await tag.mailboxStatus());                         // Decode mailbox status, () → object
                                                                                // enabled, hostPut, rfPut, hostMiss, rfMiss, …
    console.log('Message:', (await tag.mailboxRead()).toString());              // Read mailbox message, () → Buffer
                                                                                // MB_LEN_Dyn + 1 bytes, empty if no message
    await tag.disableMailbox();                                                 // Disable fast transfer mode, () → void
                                                                                // clears MB_EN, which also drops the message

    await tag.writeNdef([0xD1, 0x01, 0x05, 0x54, 0x02, 0x65, 0x6E, 0x48, 0x69, 0x21]);  // Write NDEF message, (message) → void
                                                                                // capability container + TLV 03 <len> … FE
    console.log('NDEF:', (await tag.readNdef()).toString('hex'));               // Read NDEF message, () → Buffer
                                                                                // parses the CC and the first NDEF TLV
    await tag.lockCcfile(false);                                                // Lock CC file blocks, (lock=true) → void
                                                                                // false keeps blocks 0/1 RF-writable

    await tag.enableInterrupt(ST25DVSource.FIELD_RISING | ST25DVSource.RF_WRITE);  // Enable interrupt sources, (source) → void
                                                                                // static GPO register bits plus GPO_EN
    await tag.onInterrupt((s) => console.log('GPO event: 0x' + s.toString(16))); // Subscribe to GPO events, (callback, intPin=null) → void
                                                                                // 10 ms IT_STS_Dyn polling without a GPO pin
    console.log('Pending: 0x' + (await tag.pollInterrupt()).toString(16));      // Read and clear IT_STS_Dyn, () → number
                                                                                // ST25DVSource mask, 0 = none
    await tag.offInterrupt();                                                   // Unsubscribe, () → void
    await tag.disableInterrupt(ST25DVSource.RF_WRITE);                          // Disable interrupt sources, (source) → void
                                                                                // clears the matching GPO enable bits
    user.close();
    system.close();
}

main().catch(err => { console.error(err); process.exit(1); });
