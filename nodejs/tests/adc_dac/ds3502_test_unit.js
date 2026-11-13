'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { DS3502Minimal, DS3502Full } = require('../../packages/periph/src/chips/adc_dac/ds3502');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const dataWrites = (conn) => conn.writes.filter((w) => w.length > 1);

async function fresh(wr = 0x40, vHigh, vLow) {
    const conn = new I2CConnectionMock();
    conn.setRegister(0x00, [wr]);
    conn.setRegister(0x02, [0x00]);
    const pot = new DS3502Full(conn, vHigh, vLow);
    await pot.init();
    return { conn, pot };
}
const wr = (conn) => conn.registers.get(0x00);
const cr = (conn) => conn.registers.get(0x02);
const near = (a, b) => Math.abs(a - b) < 1e-9;

async function main() {
    // -- init: CR <- 0x80 (MODE = 1), then WR is read; the wiper is not written.
    {
        const { conn, pot } = await fresh(0x23);
        checkTrue('init_sets_mode_sram', cr(conn) === 0x80);
        checkTrue('init_writes_only_cr', dataWrites(conn).length === 1 && dataWrites(conn)[0][0] === 0x02);
        checkTrue('init_keeps_wiper', wr(conn) === 0x23);
        checkTrue('init_caches_wr', (await pot.getWiper()) === 0x23);
    }
    // -- init retries once after a NACK (power-up recall).
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(0x00, [0x40]);
        conn.failNextWrites(1);
        await new DS3502Minimal(conn).init();
        checkTrue('init_retries_after_nack', cr(conn) === 0x80 && dataWrites(conn).length === 2);
    }
    // -- setWiper / getWiper
    {
        const { conn, pot } = await fresh();
        await pot.setWiper(100);
        checkTrue('set_wiper', wr(conn) === 100);
        await pot.setWiper(300);
        checkTrue('set_wiper_clamps_high', wr(conn) === 127);
        await pot.setWiper(-5);
        checkTrue('set_wiper_clamps_low', wr(conn) === 0);
        conn.setRegister(0x00, [0xFF]);
        checkTrue('get_wiper_masks_bit7', (await pot.getWiper()) === 127);
        conn.setRegister(0x00, [17]);
        checkTrue('get_wiper', (await pot.getWiper()) === 17);
    }
    // -- voltage conversion (RH = 15 V, RL = 0 V)
    {
        const { conn, pot } = await fresh(0x40, 15.0, 0.0);
        await pot.setVoltage(6.0);
        checkTrue('set_voltage_code', wr(conn) === 51);
        checkTrue('get_voltage', near(await pot.getVoltage(), 15.0 * 51 / 127));
        await pot.setVoltage(99.0);
        checkTrue('set_voltage_clamps_high', wr(conn) === 127);
        await pot.setVoltage(-3.0);
        checkTrue('set_voltage_clamps_low', wr(conn) === 0);
        conn.setRegister(0x00, [127]);
        checkTrue('get_voltage_full_scale', near(await pot.getVoltage(), 15.0));
    }
    {
        const { conn, pot } = await fresh(0x40, 12.0, 2.0);
        await pot.setVoltage(7.0);
        checkTrue('set_voltage_offset_low', wr(conn) === 64);
    }
    {
        const { conn, pot } = await fresh(0x40, 1.0, 3.0);
        await pot.setVoltage(2.0);
        checkTrue('set_voltage_inverted_ends', wr(conn) === 64);
    }
    {
        const { conn, pot } = await fresh(0x40, 5.0, 5.0);
        await pot.setVoltage(5.0);
        checkTrue('set_voltage_zero_span', wr(conn) === 0);
    }
    // -- ratio / resistance
    {
        const { conn, pot } = await fresh();
        await pot.setRatio(0.5);
        checkTrue('set_ratio', wr(conn) === 64);
        await pot.setRatio(2.0);
        checkTrue('set_ratio_clamps', wr(conn) === 127);
        checkTrue('get_ratio', (await pot.getRatio()) === 1.0);
        conn.setRegister(0x00, [0]);
        checkTrue('resistance_zero', (await pot.getResistance('L')) === 0 && (await pot.getResistance('H')) === 10000.0);
        conn.setRegister(0x00, [127]);
        checkTrue('resistance_full', (await pot.getResistance()) === 10000.0 && (await pot.getResistance('H')) === 0);
        let threw = false;
        try { await pot.getResistance('X'); } catch (e) { threw = true; }
        checkTrue('resistance_bad_terminal', threw);
    }
    // -- increment
    {
        const { conn, pot } = await fresh(0x40);
        checkTrue('increment_returns_code', (await pot.increment(5)) === 69 && wr(conn) === 69);
        checkTrue('increment_negative', (await pot.increment(-10)) === 59);
        conn.setRegister(0x00, [125]);
        checkTrue('increment_clamps_high', (await pot.increment(10)) === 127);
        conn.setRegister(0x00, [3]);
        checkTrue('increment_clamps_low', (await pot.increment(-10)) === 0);
    }
    // -- MODE
    {
        const { conn, pot } = await fresh();
        checkTrue('get_mode_sram', (await pot.getMode()) === false);
        await pot.setMode(true);
        checkTrue('set_mode_eeprom', cr(conn) === 0x00 && (await pot.getMode()) === true);
        await pot.setMode(false);
        checkTrue('set_mode_sram', cr(conn) === 0x80);
    }
    // -- storeWiper: CR <- 0x00, WR <- code, (poll), CR <- 0x80
    {
        const { conn, pot } = await fresh(0x40);
        const n0 = dataWrites(conn).length;
        await pot.storeWiper(77);
        const seq = dataWrites(conn).slice(n0).map((w) => [w[0], w[1]]);
        checkTrue('store_wiper_sequence', JSON.stringify(seq) === JSON.stringify([[0x02, 0x00], [0x00, 77], [0x02, 0x80]]));
        checkTrue('store_wiper_restores_mode', cr(conn) === 0x80 && (await pot.getMode()) === false);
        checkTrue('store_wiper_moves_wiper', (await pot.getWiper()) === 77);
        conn.setRegister(0x00, [33]);
        await pot.storeWiper();
        checkTrue('store_wiper_default_current', wr(conn) === 33);
    }
    // -- storeWiper restores MODE = 1 even when the EEPROM write never finishes.
    {
        const { conn, pot } = await fresh(0x40);
        conn.failNextWrites(31, 2);  // CR <- 0, WR <- code succeed; all 31 polls NACK
        let threw = false;
        try { await pot.storeWiper(10); } catch (e) { threw = true; }
        checkTrue('store_wiper_timeout_raises', threw);
        checkTrue('store_wiper_restores_mode_on_error', cr(conn) === 0x80);
    }
    // -- waitReady
    {
        const { conn, pot } = await fresh();
        conn.failNextWrites(3);
        await pot.waitReady();
        checkTrue('wait_ready_polls_until_ack', true);
        conn.failNextWrites(1000);
        let threw = false;
        try { await pot.waitReady(5); } catch (e) { threw = true; }
        checkTrue('wait_ready_times_out', threw);
    }
    // -- setEndpoints: no I2C traffic, changes the conversions.
    {
        const { conn, pot } = await fresh(0x40, 1.0, 0.0);
        const n = conn.writes.length;
        pot.setEndpoints(10.0, 0.0);
        checkTrue('set_endpoints_no_traffic', conn.writes.length === n);
        conn.setRegister(0x00, [127]);
        checkTrue('set_endpoints_used', near(await pot.getVoltage(), 10.0));
    }
    // -- Minimal class has no Full-only methods.
    checkTrue('minimal_has_no_store_wiper', typeof new DS3502Minimal(new I2CConnectionMock()).storeWiper === 'undefined');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
