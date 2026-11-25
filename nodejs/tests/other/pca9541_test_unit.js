'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { PCA9541Minimal, PCA9541Full } = require('../../packages/periph/src/chips/other/pca9541');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const dataWrites = (conn) => conn.writes.filter((w) => w.length > 1);
const reg = (conn, r) => conn.registers.get(r);

// Simulates how the chip recomputes the read-only N bits after a CONTROL write (this master's view).
class SimMock extends I2CConnectionMock {
    async write(data) {
        const prior = this.registers.get(0x01) || 0;
        await super.write(data);
        if (data.length === 2 && data[0] === 0x01) {
            const w = data[1];
            const mybus = w & 1;
            const buson = (w >> 2) & 1;
            const nmybusPrior = (prior >> 1) & 1;
            const nbusonPrior = (prior >> 3) & 1;
            const control = mybus === nmybusPrior;
            const on = buson !== nbusonPrior;
            const nmybus = control ? mybus : 1 - mybus;
            const nbuson = on ? 1 - buson : buson;
            this.registers.set(0x01, (w & 0xF5) | (nmybus << 1) | (nbuson << 3));
        }
    }
}

async function fresh(control = 0x04, ie = 0x00, istat = 0x00) {
    const conn = new SimMock();
    conn.setRegister(0x00, [ie]); conn.setRegister(0x01, [control]); conn.setRegister(0x02, [istat]);
    conn.setRegister(0x10, [ie]); conn.setRegister(0x11, [control]); conn.setRegister(0x12, [istat]);
    const chip = new PCA9541Full(conn);
    await chip.init();
    return { conn, chip };
}

async function main() {
    // -- init: bus check + ISTAT read, nothing written
    {
        const { conn } = await fresh();
        checkTrue('init_writes_nothing', dataWrites(conn).length === 0);
        checkTrue('init_reads_control_and_istat', conn.writes.length === 2 && conn.writes[0][0] === 0x01 && conn.writes[1][0] === 0x02);
        const bad = new SimMock();
        bad.failNextWrites(1);
        let threw = false;
        try { await new PCA9541Minimal(bad).init(); } catch (e) { threw = true; }
        checkTrue('init_rejects_when_not_acknowledged', threw);
    }
    // -- Table 12
    {
        const table = { 0x0: 0x4, 0x1: 0x4, 0x2: 0x5, 0x3: 0x5, 0x5: 0x4, 0x6: 0x5, 0x9: 0x0, 0xA: 0x1, 0xC: 0x0, 0xD: 0x0, 0xE: 0x1, 0xF: 0x1 };
        let ok = true;
        for (const [read, want] of Object.entries(table)) {
            const { conn, chip } = await fresh(Number(read));
            await chip.acquire(false, 0);
            const w = dataWrites(conn).pop();
            ok = ok && w.length === 2 && w[0] === 0x01 && w[1] === want;
        }
        checkTrue('acquire_follows_table12', ok);
        ok = true;
        for (const read of [0x4, 0x7, 0x8, 0xB]) {
            const { chip } = await fresh(read);
            ok = ok && (await chip.acquire(false, 0)) === true;
        }
        checkTrue('acquire_keeps_control', ok);
    }
    // -- hasControl
    {
        const cases = { 0x04: true, 0x0A: false, 0x00: false, 0x02: false, 0x07: true, 0x05: false };
        let ok = true;
        for (const [c, v] of Object.entries(cases)) ok = ok && (await (await fresh(Number(c))).chip.hasControl()) === v;
        checkTrue('has_control_values', ok);
    }
    // -- acquire with bus init
    {
        const { conn, chip } = await fresh(0x0A, 0, 0x02);
        checkTrue('acquire_bus_init_ok', (await chip.acquire(true)) === true);
        checkTrue('acquire_bus_init_sets_bit', dataWrites(conn).pop()[1] === 0x11);
        const f2 = await fresh(0x0A, 0, 0x00);
        checkTrue('acquire_bus_init_times_out', (await f2.chip.acquire(true, 3)) === false);
    }
    // -- release
    {
        const { conn, chip } = await fresh(0x04);
        await chip.release();
        checkTrue('release_write', dataWrites(conn).pop()[1] === 0x05 && (await chip.hasControl()) === false);
    }
    // -- connect / disconnect
    {
        const a = await fresh(0x0D);
        await a.chip.connect();
        checkTrue('connect_write', dataWrites(a.conn).pop()[1] === 0x01);
        const b = await fresh(0x04);
        await b.chip.disconnect();
        checkTrue('disconnect_write', dataWrites(b.conn).pop()[1] === 0x00 && (await b.chip.isConnected()) === false);
        await b.chip.connect();
        checkTrue('connect_makes_connected', (await b.chip.isConnected()) === true);
    }
    // -- getControl / status
    {
        const { chip } = await fresh(0xD4);
        const st = await chip.status();
        checkTrue('status_decode', (await chip.getControl()) === 0xD4 && st.hasControl && st.connected && st.busInitRequested && st.ownTest && st.otherTest);
        const f2 = await fresh(0x0A);
        const s2 = await f2.chip.status();
        checkTrue('status_no_control', !s2.hasControl && s2.connected);
    }
    // -- recoverBus
    {
        const { conn, chip } = await fresh(0x0A, 0, 0x02);
        checkTrue('recover_bus', (await chip.recoverBus()) === true && (dataWrites(conn).pop()[1] & 0x10) !== 0);
    }
    // -- interrupt mask
    {
        const { conn, chip } = await fresh();
        await chip.setInterruptMask({ busLost: true, intIn: true });
        checkTrue('set_interrupt_mask', reg(conn, 0) === 0x09 && (await chip.getInterruptMask()) === 0x09);
        await chip.setInterruptMask();
        checkTrue('set_interrupt_mask_defaults_unmask', reg(conn, 0) === 0x00);
        await chip.disableInterrupt(PCA9541Full.SOURCE_BUS_INIT);
        checkTrue('disable_interrupt', reg(conn, 0) === 0x02);
        await chip.disableInterrupt(PCA9541Full.SOURCE_INT_IN);
        await chip.enableInterrupt(PCA9541Full.SOURCE_BUS_INIT);
        checkTrue('enable_interrupt_clears_one_bit', reg(conn, 0) === 0x01);
        const n = dataWrites(conn).length;
        await chip.enableInterrupt(PCA9541Full.SOURCE_TEST);
        checkTrue('test_source_needs_no_enable', dataWrites(conn).length === n);
    }
    // -- functional INT test keeps the bus bits
    {
        const { conn, chip } = await fresh(0x0D);
        const bits = reg(conn, 1) & 0x05;
        await chip.testInterrupt(true);
        checkTrue('test_interrupt_own', (reg(conn, 1) & 0xC5) === (bits | 0x40));
        await chip.testInterrupt(false, true);
        checkTrue('test_interrupt_other', (reg(conn, 1) & 0xC0) === 0x80);
        await chip.testInterrupt(true, true);
        checkTrue('test_interrupt_both', (reg(conn, 1) & 0xC0) === 0xC0);
        await chip.testInterrupt();
        checkTrue('test_interrupt_clear', (reg(conn, 1) & 0xC0) === 0 && (reg(conn, 1) & 0x05) === bits);
    }
    // -- readAll
    {
        const { conn, chip } = await fresh(0x04, 0x03, 0x08);
        const n = conn.writes.length;
        const all = await chip.readAll();
        checkTrue('read_all_values', all.ie === 0x03 && all.control === 0x04 && all.istat === 0x08);
        checkTrue('read_all_auto_increment', conn.writes.length === n + 1 && conn.writes[conn.writes.length - 1][0] === 0x10);
    }
    // -- pollInterrupt / callback
    {
        const { conn, chip } = await fresh(0x04, 0, 0x0A);
        checkTrue('poll_interrupt', (await chip.pollInterrupt()) === 0x0A);
        const seen = [];
        chip._callback = (s) => seen.push(s);
        await chip._service();
        conn.setRegister(0x02, [0x00]);
        await chip._service();
        await chip.offInterrupt();
        checkTrue('callback_receives_status', seen.length === 1 && seen[0] === 0x0A && chip._callback === null);
    }
    // -- minimal class has no Full-only methods
    checkTrue('minimal_has_no_connect', typeof new PCA9541Minimal(new SimMock()).connect === 'undefined');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
