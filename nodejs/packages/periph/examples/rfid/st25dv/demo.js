'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ST25DVFull, ST25DVSource } = require('../../../src/chips/rfid/st25dv');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
    // --- Two connections, one chip ---
    // The ST25DV answers on 0x53 (user memory, dynamic registers, mailbox) and on
    // 0x57 (system configuration). Both use 16-bit register addresses.
    const user = new I2CConnection(1, 0x53, null, null, 2);                     // Create user-area connection, (bus=1, addr=0x53, intPin=null, enPin=null, regBytes=2) → Connection
    const system = new I2CConnection(1, 0x57, null, null, 2);                   // Create system-area connection, (bus=1, addr=0x57, intPin=null, enPin=null, regBytes=2) → Connection
    const tag = new ST25DVFull(user, system);                                   // Create ST25DV driver, (user, system, gpoActiveHigh=false) → void
    await tag.init();                                                           // Read MEM_SIZE, BLK_SIZE, IC_REF, () → void

    // --- Identify the tag ---
    // IC_REF tells the 04K apart from the 16K/64K; the size comes from MEM_SIZE.
    console.log('IC_REF 0x' + tag.icReference().toString(16), 'revision', await tag.icRevision());  // Get IC reference, () → number ; Read IC revision, () → number
    console.log('UID', (await tag.uid()).toString('hex'), '-', tag.memorySize(), 'bytes');         // Read unique ID, () → Buffer (8) ; Get user memory size, () → number bytes

    // --- NDEF text record a phone can read ---
    // Record header D1 (MB/ME/SR, well-known), type 'T', status 02 (language "en").
    const text = Buffer.from('Hello from Periph');
    const record = Buffer.concat([Buffer.from([0xD1, 0x01, 3 + text.length, 0x54, 0x02]), Buffer.from('en'), text]);
    await tag.writeNdef(record);                                                // Write NDEF message, (message) → void
    console.log('NDEF read back:', (await tag.readNdef()).equals(record));      // Read NDEF message, () → Buffer

    // --- Raw bytes across a 256-byte page boundary ---
    // The driver splits the write into 16-byte-aligned pieces and ACK-polls each.
    const saved = await tag.read(0x00F8, 32);                                   // Read user memory, (address, length) → Buffer
    const pattern = Buffer.from(Array.from({ length: 32 }, (_, i) => 0x40 + i));
    await tag.write(0x00F8, pattern);                                           // Write user memory, (address, data) → void
    await tag.waitWriteComplete();                                              // Wait for the write cycle, () → void
    console.log('Page-crossing write ok:', (await tag.read(0x00F8, 32)).equals(pattern));  // Read user memory, (address, length) → Buffer
    await tag.write(0x00F8, saved);                                             // Write user memory, (address, data) → void

    // --- Password session and two memory areas ---
    // Area 1 = first 1 KiB (stays open), area 2 = the rest: RF writes need RF
    // password 1, I2C writes need the I2C session. The factory I2C password is 0.
    const pwd = Buffer.alloc(8);
    console.log('Session before:', await tag.sessionOpen());                    // Query I²C security session, () → boolean
    console.log('Password accepted:', await tag.presentPassword(pwd));          // Present I²C password, (password 8 bytes) → boolean
    const end1 = Math.min(tag.memorySize(), 1024) / 32 - 1;
    const last = tag.memorySize() / 32 - 1;
    await tag.setAreaEnd(1, end1);                                              // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
    await tag.setAreaEnd(2, last);                                              // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
    await tag.setAreaEnd(3, last);                                              // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
    await tag.setAreaProtection(2, 0x05, 0x01);                                 // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → void
    console.log('Area 2 protection:', await tag.getAreaProtection(2));          // Get area protection, (area 1–4) → {rf, i2c}

    // --- Change and restore the I2C password ---
    const newPwd = Buffer.from([0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0]);
    await tag.writePassword(newPwd);                                            // Change I²C password, (password 8 bytes) → void
    console.log('New password accepted:', await tag.presentPassword(newPwd));   // Present I²C password, (password 8 bytes) → boolean
    await tag.writePassword(pwd);                                               // Change I²C password, (password 8 bytes) → void
    console.log('Factory password restored:', await tag.presentPassword(pwd));  // Present I²C password, (password 8 bytes) → boolean

    // --- GPO events while a phone approaches ---
    // Field change and RF write events pulse GPO; without a GPO pin the driver
    // polls IT_STS_Dyn every 10 ms. Hold a phone to the antenna now.
    await tag.setInterruptPulse(3);                                             // Set GPO pulse duration, (value 0–7) → void
    await tag.setGpo(ST25DVFull.GPO_FIELD_CHANGE);                              // Write static GPO register, (sources, enable=true) → void
    await tag.enableInterrupt(ST25DVSource.FIELD_RISING | ST25DVSource.RF_WRITE);  // Enable interrupt sources, (source) → void
    console.log('GPO register: 0x' + (await tag.getGpo()).toString(16));        // Read static GPO register, () → number
    console.log('Pending before: 0x' + (await tag.pollInterrupt()).toString(16));  // Read and clear IT_STS_Dyn, () → number
    await tag.onInterrupt((status) => {                                         // Subscribe to GPO events, (callback, intPin=null) → void
        const names = [];
        if (status & ST25DVSource.FIELD_RISING) names.push('field on');
        if (status & ST25DVSource.FIELD_FALLING) names.push('field off');
        if (status & ST25DVSource.RF_WRITE) names.push('RF write');
        console.log('  GPO event:', names.join(', ') || '0x' + status.toString(16));
    });
    for (let i = 0; i < 10; i++) {
        console.log('  field present:', await tag.fieldPresent());              // Query RF field, () → boolean
        await sleep(1000);
    }
    await tag.offInterrupt();                                                   // Unsubscribe, () → void
    await tag.disableInterrupt(ST25DVSource.RF_WRITE);                          // Disable interrupt sources, (source) → void

    // --- Fast transfer mode: host <-> phone mailbox ---
    // The host puts a message; an RF reader (e.g. the ST25 app) reads it and can
    // answer. The watchdog releases an unread message after 2^(7-1) x 30 ms.
    await tag.enableMailbox();                                                  // Enable fast transfer mode, () → void
    await tag.setMailboxWatchdog(7);                                            // Set mailbox watchdog, (value 0–7) → void
    await tag.mailboxWrite(Buffer.from('hello reader'));                        // Put mailbox message, (data 1–256 bytes) → void
    for (let i = 0; i < 10; i++) {
        const st = await tag.mailboxStatus();                                   // Decode mailbox status, () → object
        if (st.rfPut) {
            console.log('Reader says:', (await tag.mailboxRead()).toString());  // Read mailbox message, () → Buffer
            break;
        }
        console.log('  mailbox:', st);
        await sleep(1000);
    }
    await tag.disableMailbox();                                                 // Disable fast transfer mode, () → void

    // --- Energy harvesting and RF state ---
    await tag.setEnergyHarvesting(true);                                        // Control energy harvesting, (enabled, forced=false) → void
    await sleep(1000);
    await tag.setEnergyHarvesting(false);                                       // Control energy harvesting, (enabled, forced=false) → void
    await tag.setRfState(false, true);                                          // Set RF interface state, (disable=false, sleep=false) → void
    console.log('RF asleep: a phone no longer sees the tag');
    await sleep(2000);
    await tag.setRfState();                                                     // Set RF interface state, (disable=false, sleep=false) → void

    // --- Restore the delivery defaults ---
    // One area, no protection, field-change GPO only, mailbox off, CC unlocked.
    await tag.setAreaProtection(2, 0x00, 0x00);                                 // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → void
    await tag.setAreaEnd(1, last);                                              // Set end of area 1-3, (area 1–3, endBlock 0–255) → void
    await tag.setGpo(ST25DVFull.GPO_FIELD_CHANGE);                              // Write static GPO register, (sources, enable=true) → void
    await tag.setGpoRuntime(true);                                              // Switch GPO output at runtime, (enable) → void
    await tag.writeSystem(0x000D, 0x00);                                        // Write system area, (address, data) → void
    console.log('MB_MODE:', (await tag.readSystem(0x000D, 1))[0]);              // Read system area, (address, length) → Buffer
    await tag.lockCcfile(false);                                                // Lock CC file blocks, (lock=true) → void
    console.log('Done.');
    user.close();
    system.close();
}

main().catch(err => { console.error(err); process.exit(1); });
