'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Mcp23017Minimal, Mcp23017Full } = require('../../packages/periph/src/chips/io_expander/mcp23017');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',    10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x20', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const chip       = new Mcp23017Minimal(connection);

    checkTrue('shadow_array', Array.isArray(chip._shadow) && chip._shadow.length === 2);

    const porta = await chip.readPort(0);
    checkTrue('read_porta_range', porta >= 0 && porta <= 0xFF);

    const portb = await chip.readPort(1);
    checkTrue('read_portb_range', portb >= 0 && portb <= 0xFF);

    await chip.writePort(0, 0x55);
    checkEq('write_porta_shadow', chip._shadow[0], 0x55);
    await chip.writePort(1, 0xAA);
    checkEq('write_portb_shadow', chip._shadow[1], 0xAA);

    const p0 = chip.pin(0, 'out');
    await p0.write(0);
    checkEq('write_low_shadow', chip._shadow[0] & 0x01, 0);
    await p0.write(1);
    checkEq('write_high_shadow', chip._shadow[0] & 0x01, 1);

    const v = await p0.read();
    checkTrue('read_range', v === 0 || v === 1);
    checkEq('direction_prop', p0.direction, 'out');

    const p8 = chip.pin(8, 'in');
    checkEq('input_direction_portb', p8.direction, 'in');

    const p15 = chip.pin(15, 'out');
    checkEq('input_direction_gpb7', p15.direction, 'out');

    // --- Loopback: PA (outputs) → PB (inputs); PA[n]↔PB[7-n] ---
    await chip.configureDirection(0, 0x00);     // PA all outputs

    await chip.writePort(0, 0xAA);              // PA0=0, avoids contention with PB7 output
    let pb = await chip.readPort(1);
    checkEq('loopback_0xAA', pb & 0x7F, 0x55);

    await chip.writePort(0, 0xFE);              // PA0=0, PA1–PA7=1
    pb = await chip.readPort(1);
    checkEq('loopback_0xFE', pb & 0x7F, 0x7F);

    await chip.writePort(0, 0x00);
    pb = await chip.readPort(1);
    checkEq('loopback_0x00', pb & 0x7F, 0x00);

    // Full
    const full = new Mcp23017Full(connection);
    checkTrue('full_init_shadow', Array.isArray(full._shadow) && full._shadow.length === 2);

    await full.configurePullup(0, 0x55);
    await full.configurePullup(1, 0xAA);

    await full.configurePolarity(0, 0x0F);
    await full.configurePolarity(1, 0xF0);

    await full.onInterruptPort(0, () => {});
    checkTrue('poll_timer_set', full._pollTimer[0] !== null);
    await full.offInterruptPort(0);
    checkTrue('poll_timer_cleared', full._pollTimer[0] === null);

    await full.onInterruptPort(1, () => {});
    checkTrue('poll_timer_set_portb', full._pollTimer[1] !== null);
    await full.offInterruptPort(1);
    checkTrue('poll_timer_cleared_portb', full._pollTimer[1] === null);

    const captured = await full.readCapture(0);
    checkTrue('read_capture_range', captured >= 0 && captured <= 0xFF);

    const changed = await full.pollInterrupt(0);
    checkTrue('poll_interrupt_range', changed >= 0 && changed <= 0xFF);

    const p1 = full.pin(1, 'in');
    const watcher = () => {};
    p1.watch(watcher);
    checkTrue('watcher_registered', full._watchers[0][1] !== undefined);
    p1.unwatch();
    checkTrue('watcher_unregistered', full._watchers[0][1] === undefined);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed ? 1 : 0);
}

main();
