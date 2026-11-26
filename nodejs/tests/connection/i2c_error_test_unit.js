'use strict';
// Unit test for I2CConnection / SMBusConnection error reporting (#767):
// short transfers throw, and writeRead never drops bytes of `data`.
// i2c-bus is replaced by a fake bus, so no hardware is needed.

const i2cBus = require('i2c-bus');

class FakeBus {
    constructor() { this.calls = []; this.shortBy = 0; this.reply = []; }
    _fill(buf, n) { for (let i = 0; i < n; i++) buf[i] = this.reply[i] ?? 0xA0 + i; }
    i2cWriteSync(addr, len, buf) {
        this.calls.push(['write', Array.from(buf.subarray(0, len))]);
        return len - this.shortBy;
    }
    i2cReadSync(addr, len, buf) {
        this.calls.push(['read', len]);
        this._fill(buf, len);
        return len - this.shortBy;
    }
    readI2cBlockSync(addr, cmd, len, buf) {
        if (len > 32) throw new RangeError('length > 32');
        this.calls.push(['block', cmd, len]);
        this._fill(buf, len);
        return len - this.shortBy;
    }
    closeSync() {}
}

let bus;
i2cBus.openSync = () => (bus = new FakeBus());

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { SMBusConnection } = require('../../packages/periph/src/connection/smbus');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function throws(fn) {
    try { await fn(); return null; } catch (e) { return e; }
}

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

(async () => {
    // --- I2CConnection -------------------------------------------------------
    let c = new I2CConnection(1, 0x48);

    await c.writeRead(Buffer.from([0x05]), 2);
    checkTrue('1-byte register, short read uses repeated-start block read',
        same(bus.calls, [['block', 0x05, 2]]));

    bus.calls = [];
    await c.writeRead(Buffer.from([0x12, 0x34]), 4);
    checkTrue('2-byte register is written in full (not truncated to data[0])',
        same(bus.calls, [['write', [0x12, 0x34]], ['read', 4]]));

    bus.calls = [];
    const big = await c.writeRead(Buffer.from([0x00]), 40);
    checkTrue('read over 32 bytes falls back to write + read',
        same(bus.calls, [['write', [0x00]], ['read', 40]]) && big.length === 40);

    bus.shortBy = 1;
    let e = await throws(() => c.read(4));
    checkTrue('short read throws', e && /i2cReadSync\(0x48\) transferred 3 of 4 bytes/.test(e.message));
    e = await throws(() => c.write(Buffer.from([1, 2, 3])));
    checkTrue('short write throws', e && /i2cWriteSync\(0x48\) transferred 2 of 3 bytes/.test(e.message));
    e = await throws(() => c.writeRead(Buffer.from([0x05]), 2));
    checkTrue('short block read throws', e && /readI2cBlockSync\(0x48\) transferred 1 of 2 bytes/.test(e.message));
    checkTrue('short-transfer error has code EIO', e && e.code === 'EIO');
    bus.shortBy = 0;

    // --- SMBusConnection -----------------------------------------------------
    const s = new SMBusConnection(1, 0x48, true);
    // PEC for write 0x90, cmd 0x05, read 0x91, data 0x11 0x22
    const crc8 = (bytes) => bytes.reduce((crc, b) => {
        crc ^= b;
        for (let i = 0; i < 8; i++) crc = (crc & 0x80) ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
        return crc;
    }, 0);
    bus.reply = [0x11, 0x22, crc8([0x90, 0x05, 0x91, 0x11, 0x22])];
    const r = await s.writeRead(Buffer.from([0x05]), 2);
    checkTrue('SMBus PEC block read returns data', same(Array.from(r), [0x11, 0x22]));

    bus.calls = [];
    bus.reply = [0x11, 0x22, crc8([0x91, 0x11, 0x22])];
    await s.writeRead(Buffer.from([0x12, 0x34]), 2);
    checkTrue('SMBus 2-byte command written in full with its own PEC',
        same(bus.calls, [['write', [0x12, 0x34, crc8([0x90, 0x12, 0x34])]], ['read', 3]]));

    bus.shortBy = 1;
    e = await throws(() => s.read(2));
    checkTrue('SMBus short read throws before PEC check', e && /transferred 2 of 3 bytes/.test(e.message));
    bus.shortBy = 0;

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
})();
