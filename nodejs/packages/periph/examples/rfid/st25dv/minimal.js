'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ST25DVMinimal } = require('../../../src/chips/rfid/st25dv');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
    const user = new I2CConnection(1, 0x53, null, null, 2);                     // Create user-area connection, (bus=1, addr=0x53, intPin=null, enPin=null, regBytes=2) → Connection
    const system = new I2CConnection(1, 0x57, null, null, 2);                   // Create system-area connection, (bus=1, addr=0x57, intPin=null, enPin=null, regBytes=2) → Connection
    const tag = new ST25DVMinimal(user, system);                                // Create ST25DV driver, (user, system, gpoActiveHigh=false) → void
    await tag.init();                                                           // Read MEM_SIZE, BLK_SIZE, IC_REF, () → void

    await tag.write(0x0100, Buffer.from('Hello NFC'));                          // Write user memory, (address, data) → void
    for (;;) {
        console.log('Memory:', (await tag.read(0x0100, 9)).toString());         // Read user memory, (address, length) → Buffer
        await sleep(1000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
