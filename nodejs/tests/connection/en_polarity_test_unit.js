'use strict';
const { Connection } = require('../../packages/periph/src/connection/connection');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

class RecordingPin {
    constructor() { this.levels = []; }
    async set(high) { this.levels.push(high); }
}

class NullConnection extends Connection {
    async _read(n) { return Buffer.alloc(n); }
    async _write() {}
    async _writeRead(data, n) { return Buffer.alloc(n); }
}

(async () => {
    for (const activeHigh of [true, false]) {
        const pin = new RecordingPin();
        const conn = new NullConnection(null, pin, activeHigh);
        await conn.disable();
        await conn.enable();
        checkTrue(`disable drives ${!activeHigh} for activeHigh=${activeHigh}`, pin.levels[0] === !activeHigh);
        checkTrue(`enable drives ${activeHigh} for activeHigh=${activeHigh}`, pin.levels[1] === activeHigh);
        checkTrue(`software gate unaffected by polarity (activeHigh=${activeHigh})`, conn.isEnabled());
    }
    const pin = new RecordingPin();
    await new NullConnection(null, pin).enable();
    checkTrue('default polarity is active-high', pin.levels[0] === true);
    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
})();
