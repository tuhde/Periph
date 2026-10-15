'use strict';
const { DisplayConnection, WireMode } = require('../../packages/periph/src/connection/display');
const { DisplayConnectionMock } = require('../../packages/periph/src/connection/display_mock');
const { SoftSPI } = require('../../packages/periph/src/connection/softspi');
const { PollingInputPin } = require('../../packages/periph/src/connection/input_pin');

let passed = 0;
let failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}
async function throws(fn) { try { await fn(); return false; } catch (e) { return true; } }

class Log {
    constructor() { this.events = []; }
    pin(name) {
        const log = this.events;
        return { async set(high) { log.push([name, high]); } };
    }
}

class FakeDevice {
    constructor() { this.writes = []; this.readQueue = []; }
    transferSync(msgs) {
        for (const m of msgs) {
            if (m.sendBuffer) this.writes.push(Buffer.from(m.sendBuffer));
            if (m.receiveBuffer) for (let i = 0; i < m.byteLength; i++) m.receiveBuffer[i] = this.readQueue.shift() ?? 0;
        }
    }
}

(async () => {
    // --- hardware SPI: D/C level per transfer ---
    {
        const log = new Log();
        const dev = new FakeDevice();
        const c = new DisplayConnection({ device: dev, dc: log.pin('dc'), res: log.pin('res') });
        await c.ready();
        await c.sendCommand(0x10, [1, 2, 3]);
        checkTrue('command byte and params are separate transfers',
            dev.writes.length === 2 && dev.writes[0][0] === 0x10 && dev.writes[1].equals(Buffer.from([1, 2, 3])));
        const dcSeq = log.events.filter(e => e[0] === 'dc').map(e => e[1]);
        checkTrue('D/C low for command then high for data', dcSeq.slice(-2).join() === 'false,true');
        await c.sendCommand(0x04);
        checkTrue('command without data is one transfer', dev.writes.length === 3);
        await c.sendData([9, 9]);
        checkTrue('sendData one transfer', dev.writes.length === 4);
        checkTrue('readData throws in SIMPLEX', await throws(() => c.readData(1)));
        checkTrue('busyLevel false without BUSY pin', c.busyLevel() === false);
        checkTrue('waitBusy true without BUSY pin', await c.waitBusy(true, 10) === true);
    }
    // --- reset pulse order ---
    {
        const log = new Log();
        const c = new DisplayConnection({ device: new FakeDevice(), dc: log.pin('dc'), res: log.pin('res'),
            resetSettleMs: 1 });
        await c.ready();
        log.events.length = 0;
        await c.reset();
        checkTrue('reset: RES low then high', log.events.map(e => e.join(':')).join() === 'res:false,res:true');
    }
    // --- BUSY polarity and timeout ---
    {
        let level = true;
        const busy = new PollingInputPin(() => level, 1000);
        const c = new DisplayConnection({ device: new FakeDevice(), dc: new Log().pin('dc'), busy });
        await c.ready();
        checkTrue('busyLevel raw high', c.busyLevel() === true);
        checkTrue('active-low: high means ready', await c.waitBusy(false, 50) === true);
        checkTrue('active-high: high stuck times out', await c.waitBusy(true, 20) === false);
        setTimeout(() => { level = false; }, 10);
        checkTrue('active-high: released in time', await c.waitBusy(true, 500) === true);
    }
    // --- HALF_DUPLEX_PIN read: one window per byte ---
    {
        const dev = new FakeDevice();
        dev.readQueue = [0xA1, 0xB2];
        const c = new DisplayConnection({ device: dev, dc: new Log().pin('dc'), wireMode: WireMode.HALF_DUPLEX_PIN });
        await c.ready();
        checkTrue('readData bytes', (await c.readData(2)).equals(Buffer.from([0xA1, 0xB2])));
    }
    // --- HALF_DUPLEX_HW unsupported via spi-device ---
    checkTrue('HALF_DUPLEX_HW rejected',
        await throws(() => new DisplayConnection({ device: new FakeDevice(), dc: new Log().pin('dc'),
            wireMode: WireMode.HALF_DUPLEX_HW })));
    checkTrue('needs exactly one SPI source', await throws(() => new DisplayConnection({ dc: new Log().pin('dc') })));

    // --- disabled gate ---
    {
        const dev = new FakeDevice();
        const c = new DisplayConnection({ device: dev, dc: new Log().pin('dc') });
        await c.ready();
        await c.disable();
        await c.sendCommand(1, [2]);
        checkTrue('disabled: no transfers', dev.writes.length === 0 && !c.isEnabled());
    }

    // --- software SPI: bit order, CS framing, read ---
    {
        const clk = [];
        let mosiLevel = false;
        const sck = { set value(v) { if (v) clk.push(mosiLevel ? 1 : 0); } };
        const mosi = { set value(v) { mosiLevel = v; } };
        const soft = new SoftSPI(sck, mosi, null);
        soft.write(Buffer.from([0xA5]));
        checkTrue('SoftSPI MSB-first bits', clk.join('') === '10100101');
        checkTrue('SoftSPI write-only read throws', await throws(() => soft.read(1)));

        // read: MISO returns bits of 0x3C, MSB first
        const bits = '00111100'.split('').map(Number);
        let k = 0;
        const miso = new PollingInputPin(() => bits[k++ % 8], 1000);
        k = 0; // the constructor sampled the initial level once
        const soft2 = new SoftSPI({ set value(_v) {} }, { set value(_v) {} }, miso);
        checkTrue('SoftSPI read 0x3C', soft2.read(1)[0] === 0x3C);

        const log = new Log();
        const c = new DisplayConnection({ spi: soft, cs: log.pin('cs'), dc: log.pin('dc') });
        await c.ready();
        log.events.length = 0;
        await c.sendCommand(0x01);
        checkTrue('CS low then high around a transfer',
            log.events.filter(e => e[0] === 'cs').map(e => e[1]).join() === 'false,true');
    }

    // --- mock ---
    {
        const m = new DisplayConnectionMock({ wireMode: WireMode.HALF_DUPLEX_PIN });
        m.queueRead([7, 8]);
        await m.reset();
        await m.sendCommand(0x12, [1]);
        await m.waitBusy(false, 100);
        checkTrue('mock records', m.resetCount === 1 && m.commands.length === 1 && m.commands[0].cmd === 0x12);
        checkTrue('mock read queue', (await m.readData(2)).equals(Buffer.from([7, 8])));
        checkTrue('mock simplex read throws', await throws(() => new DisplayConnectionMock().readData(1)));
    }

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
})();
