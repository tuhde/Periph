'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { ST25DVFull } = require('../../packages/periph/src/chips/rfid/st25dv');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x53', 16);
const SYSTEM_ADDR = 0x57;

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label + ': got', got, 'expected', expected); failed++; }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const user = new I2CConnection(I2C_BUS, I2C_ADDR, null, null, 2);
    const system = new I2CConnection(I2C_BUS, SYSTEM_ADDR, null, null, 2);
    const tag = new ST25DVFull(user, system);
    await tag.init();

    checkTrue('ic_reference', [0x24, 0x26].includes(tag.icReference()));
    checkTrue('memory_size', [512, 2048, 8192].includes(tag.memorySize()));
    const uid = await tag.uid();
    checkEq('uid length', uid.length, 8);
    checkTrue('uid manufacturer', uid[0] === 0xE0 && uid[1] === 0x02);
    const rev = await tag.icRevision();
    checkTrue('ic_revision', rev >= 0 && rev <= 255);

    const addr = tag.memorySize() - 64;
    const saved = await tag.read(addr, 40);
    const pattern = Buffer.from(Array.from({ length: 40 }, (_, i) => (i * 7 + 3) & 0xFF));
    await tag.write(addr, pattern);
    checkTrue('write/read round-trip', (await tag.read(addr, 40)).equals(pattern));
    await tag.write(addr, saved);
    checkTrue('restore', (await tag.read(addr, 40)).equals(saved));

    checkTrue('session_open is boolean', typeof (await tag.sessionOpen()) === 'boolean');
    checkTrue('present factory password', await tag.presentPassword(Buffer.alloc(8)));
    checkTrue('session open after present', await tag.sessionOpen());

    const prot = await tag.getAreaProtection(1);
    checkTrue('get_area_protection', prot.rf <= 0x0F && prot.i2c <= 3);
    const gpo = await tag.getGpo();
    checkTrue('get_gpo', gpo >= 0 && gpo <= 255);
    checkTrue('field_present is boolean', typeof (await tag.fieldPresent()) === 'boolean');
    const it = await tag.pollInterrupt();
    checkTrue('poll_interrupt', it >= 0 && it <= 255);
    checkTrue('mailbox_status', 'enabled' in (await tag.mailboxStatus()));
    checkTrue('read_ndef', Buffer.isBuffer(await tag.readNdef()));
    await tag.waitWriteComplete();
    checkTrue('wait_write_complete', true);

    user.close();
    system.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(1); });
