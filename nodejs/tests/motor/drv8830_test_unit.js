'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { DRV8830Minimal, DRV8830Full } = require('../../packages/periph/src/chips/motor/drv8830');

const _REG_CONTROL = 0x00;
const _REG_FAULT   = 0x01;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

function lastWrite(conn, reg) {
    const w = conn.writes.filter((b) => b.length === 2 && b[0] === reg);
    return w.length ? w[w.length - 1][1] : undefined;
}

async function main() {
    // -- init(): plain presence read, no register writes -----------------
    {
        const conn = new I2CConnectionMock();
        const motor = new DRV8830Minimal(conn);
        await motor.init();
        checkEq('init_probe_is_address_byte_only', conn.writes.length, 1);
        checkEq('init_probe_reads_control', conn.writes[0][0], _REG_CONTROL);
    }

    // -- drive(): conversion, direction bits, floor and clamp ------------
    {
        const conn = new I2CConnectionMock();
        const motor = new DRV8830Minimal(conn);
        await motor.drive(3.0);
        checkEq('drive_forward_3v', lastWrite(conn, _REG_CONTROL), (37 << 2) | 0x01);
        await motor.drive(-2.0);
        checkEq('drive_reverse_2v', lastWrite(conn, _REG_CONTROL), (25 << 2) | 0x02);
        await motor.drive(0);
        checkEq('drive_zero_coasts', lastWrite(conn, _REG_CONTROL), 0x00);
        await motor.drive(0.4);
        checkEq('drive_below_floor_coasts', lastWrite(conn, _REG_CONTROL), 0x00);
        await motor.drive(0.48);
        checkEq('drive_floor_vset6', lastWrite(conn, _REG_CONTROL), (6 << 2) | 0x01);
        await motor.drive(9.0);
        checkEq('drive_clamps_to_vset63', lastWrite(conn, _REG_CONTROL), (63 << 2) | 0x01);
        await motor.brake();
        checkEq('brake_writes_0x03', lastWrite(conn, _REG_CONTROL), 0x03);
        await motor.stop();
        checkEq('stop_writes_0x00', lastWrite(conn, _REG_CONTROL), 0x00);
        checkTrue('minimal_has_no_read_fault', typeof motor.readFault === 'undefined');
    }

    // -- setOutput(): raw fields, reserved codes rejected ----------------
    {
        const conn = new I2CConnectionMock();
        const motor = new DRV8830Full(conn);
        await motor.setOutput(20, false, true);
        checkEq('set_output_raw', lastWrite(conn, _REG_CONTROL), (20 << 2) | 0x02);
        let threw = false;
        try { await motor.setOutput(5, true, false); } catch (e) { threw = e instanceof RangeError; }
        checkTrue('set_output_rejects_reserved', threw);
    }

    // -- readOutput(): decode VSET and direction -------------------------
    {
        const conn = new I2CConnectionMock();
        const motor = new DRV8830Full(conn);
        conn.setRegister(_REG_CONTROL, [(63 << 2) | 0x01]);
        let out = await motor.readOutput();
        checkTrue('read_output_forward', out.direction === 'forward' && Math.abs(out.voltage - 5.06) < 0.01);
        conn.setRegister(_REG_CONTROL, [(16 << 2) | 0x02]);
        out = await motor.readOutput();
        checkTrue('read_output_reverse', out.direction === 'reverse' && Math.abs(out.voltage - 1.285) < 0.001);
        conn.setRegister(_REG_CONTROL, [0x03]);
        out = await motor.readOutput();
        checkTrue('read_output_brake', out.direction === 'brake' && out.voltage === 0);
        conn.setRegister(_REG_CONTROL, [0x00]);
        out = await motor.readOutput();
        checkTrue('read_output_coast', out.direction === 'coast' && out.voltage === 0);
    }

    // -- readFault()/pollInterrupt()/clearFault() ------------------------
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_FAULT, [0x01 | 0x10]);
        const motor = new DRV8830Full(conn);
        let f = await motor.readFault();
        checkTrue('read_fault_ilimit', f.fault && !f.ocp && !f.uvlo && !f.ots && f.ilimit);
        conn.setRegister(_REG_FAULT, [0x01 | 0x02 | 0x04 | 0x08]);
        f = await motor.pollInterrupt();
        checkTrue('poll_interrupt_ocp_uvlo_ots', f.fault && f.ocp && f.uvlo && f.ots && !f.ilimit);
        checkEq('read_fault_does_not_clear', lastWrite(conn, _REG_FAULT), undefined);
        await motor.clearFault();
        checkEq('clear_fault_writes_0x80', lastWrite(conn, _REG_FAULT), 0x80);
    }

    // -- onInterrupt(): polling fallback fires once per new fault --------
    {
        const conn = new I2CConnectionMock();
        const motor = new DRV8830Full(conn);
        const calls = [];
        await motor.onInterrupt((s) => calls.push(s));
        await new Promise((r) => setTimeout(r, 30));
        conn.setRegister(_REG_FAULT, [0x01 | 0x08]);
        await new Promise((r) => setTimeout(r, 40));
        await motor.offInterrupt();
        checkEq('on_interrupt_fires_once_per_fault', calls.length, 1);
        checkTrue('on_interrupt_passes_fault_status', calls.length === 1 && calls[0].ots);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
