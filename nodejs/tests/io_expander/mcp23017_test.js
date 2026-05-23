'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
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

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const chip      = new Mcp23017Minimal(transport);

checkTrue('shadow_array', Array.isArray(chip._shadow) && chip._shadow.length === 2);

const porta = chip.readPort(0);
checkTrue('read_porta_range', porta >= 0 && porta <= 0xFF);

const portb = chip.readPort(1);
checkTrue('read_portb_range', portb >= 0 && portb <= 0xFF);

chip.writePort(0, 0x55);
checkEq('write_porta_shadow', chip._shadow[0], 0x55);
chip.writePort(1, 0xAA);
checkEq('write_portb_shadow', chip._shadow[1], 0xAA);

const p0 = chip.pin(0, 'out');
p0.writeSync(0);
checkEq('write_low_shadow', chip._shadow[0] & 0x01, 0);
p0.writeSync(1);
checkEq('write_high_shadow', chip._shadow[0] & 0x01, 1);

const v = p0.readSync();
checkTrue('read_range', v === 0 || v === 1);

p0.read((err, val) => {
    checkTrue('read_async_no_error', !err);
    checkTrue('read_async_range', val === 0 || val === 1);
});

p0.write(0, (err) => checkTrue('write_async_no_error', !err));
checkEq('direction_prop', p0.direction, 'out');

const p8 = chip.pin(8, 'in');
checkEq('input_direction_portb', p8.direction, 'in');

const p15 = chip.pin(15, 'out');
checkEq('input_direction_gpb7', p15.direction, 'out');

// --- Loopback: PA (outputs) → PB (inputs); PA[n]↔PB[7-n] ---
chip.configureDirection(0, 0x00);     // PA all outputs

chip.writePort(0, 0xAA);              // PA0=0, avoids contention with PB7 output
let pb = chip.readPort(1);
checkEq('loopback_0xAA', pb & 0x7F, 0x55);

chip.writePort(0, 0xFE);              // PA0=0, PA1–PA7=1
pb = chip.readPort(1);
checkEq('loopback_0xFE', pb & 0x7F, 0x7F);

chip.writePort(0, 0x00);
pb = chip.readPort(1);
checkEq('loopback_0x00', pb & 0x7F, 0x00);

// Full
const full = new Mcp23017Full(transport);
checkTrue('full_init_shadow', Array.isArray(full._shadow) && full._shadow.length === 2);

full.configurePullup(0, 0x55);
full.configurePullup(1, 0xAA);

full.configurePolarity(0, 0x0F);
full.configurePolarity(1, 0xF0);

full.configureInterrupt(0, null, () => {});
checkTrue('poll_timer_set', full._pollTimer !== null);
clearInterval(full._pollTimer);
full._pollTimer = null;

full.configureInterrupt(1, null, () => {});
checkTrue('poll_timer_set_portb', full._pollTimer !== null);
clearInterval(full._pollTimer);
full._pollTimer = null;

const flags = full.readInterruptFlags(0);
checkTrue('interrupt_flags_range', flags >= 0 && flags <= 0xFF);

const changed = full.clearInterrupt(0);
checkTrue('clear_interrupt_range', changed >= 0 && changed <= 0xFF);

const p1 = full.pin(1, 'in');
const watcher = () => {};
p1.watch(watcher);
checkTrue('watcher_registered', Array.isArray(full._watchers[1]) && full._watchers[1].length > 0);
p1.unwatch(watcher);
checkTrue('watcher_unregistered', !full._watchers[1] || full._watchers[1].length === 0);
p1.unwatchAll();
checkTrue('watchers_cleared', !full._watchers[1] || full._watchers[1].length === 0);

full.stopInterrupt(0);
checkTrue('stop_interrupt', full._pollTimer === null);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed ? 1 : 0);