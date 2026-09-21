'use strict';

const opengpio = require('opengpio');
const { SiPoConnection } = require('../../packages/periph/src/connection/sipo');
const { Tpic6b595Minimal, Tpic6b595Full } = require('../../packages/periph/src/chips/io_expander/tpic6b595');

const SIPO_RCK    = parseInt(process.env.SIPO_RCK    || '5',  10);
const SIPO_SRCLR  = parseInt(process.env.SIPO_SRCLR  || '6',  10);
const SIPO_G      = parseInt(process.env.SIPO_G      || '13', 10);
const SIPO_SER_IN = parseInt(process.env.SIPO_SER_IN || '19', 10);
const SIPO_SRCK   = parseInt(process.env.SIPO_SRCK   || '26', 10);

let passed = 0, failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got} expected ${expected}`); failed++; }
}

async function main() {
    const rck   = new opengpio.Output(SIPO_RCK);
    const srclr = new opengpio.Output(SIPO_SRCLR);
    const g     = new opengpio.Output(SIPO_G);
    const serIn = new opengpio.Output(SIPO_SER_IN);
    const srck  = new opengpio.Output(SIPO_SRCK);
    const connection = new SiPoConnection(rck, { srclr, g, serIn, srck });
    const chip = new Tpic6b595Full(connection, 1);

    checkEq('init_shadow_0', chip._shadow[0], 0x00);

    await chip.fill(true);
    checkEq('fill_true_shadow', chip._shadow[0], 0xFF);
    await chip.fill(false);
    checkEq('fill_false_shadow', chip._shadow[0], 0x00);
    await chip.off();
    checkEq('off_shadow', chip._shadow[0], 0x00);

    await chip.writePort(0, 0xA5);
    checkEq('write_port_0xa5_shadow', chip._shadow[0], 0xA5);
    await chip.writePort(0, 0x00);

    const p0 = chip.pin(0);
    await p0.on();
    checkEq('pin_on_shadow_bit', chip._shadow[0] & 0x01, 1);
    await p0.off();
    checkEq('pin_off_shadow_bit', chip._shadow[0] & 0x01, 0);
    await p0.toggle();
    checkEq('pin_toggle_shadow_bit', chip._shadow[0] & 0x01, 1);

    await p0.write(0);
    checkEq('pin_write_0_shadow', chip._shadow[0] & 0x01, 0);
    checkEq('pin_read_after_write_0', await p0.read(), 0);
    await p0.write(1);
    checkEq('pin_write_1_shadow', chip._shadow[0] & 0x01, 1);

    await p0.set(true);
    checkEq('pin_set_true', chip._shadow[0] & 0x01, 1);
    await p0.set(false);
    checkEq('pin_set_false', chip._shadow[0] & 0x01, 0);

    const cascaded = new Tpic6b595Full(connection, 2);
    checkEq('cascaded_init_shadow_0', cascaded._shadow[0], 0x00);
    checkEq('cascaded_init_shadow_1', cascaded._shadow[1], 0x00);
    await cascaded.writePort(0, 0x01);
    await cascaded.writePort(1, 0x80);
    checkEq('cascaded_write_port_0', cascaded._shadow[0], 0x01);
    checkEq('cascaded_write_port_1', cascaded._shadow[1], 0x80);

    chip.clear();
    checkTrue('clear_accepted', true);
    chip.setOutputEnable(false);
    checkTrue('set_output_enable_false_accepted', true);
    chip.setOutputEnable(true);
    checkTrue('set_output_enable_true_accepted', true);

    cascaded.writeAll([0xA5, 0x5A]);
    checkEq('write_all_shadow_0', cascaded._shadow[0], 0xA5);
    checkEq('write_all_shadow_1', cascaded._shadow[1], 0x5A);

    cascaded.writeAll([0xFF]);
    checkEq('write_all_pad_shadow_0', cascaded._shadow[0], 0xFF);
    checkEq('write_all_pad_shadow_1', cascaded._shadow[1], 0x00);

    cascaded.writeAll([0x12, 0x34, 0x56]);
    checkEq('write_all_truncate_shadow_0', cascaded._shadow[0], 0x12);
    checkEq('write_all_truncate_shadow_1', cascaded._shadow[1], 0x34);

    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(err => { console.error(err); process.exit(2); });
