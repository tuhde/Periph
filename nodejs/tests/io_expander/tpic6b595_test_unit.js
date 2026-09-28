'use strict';
const { SiPoConnectionMock } = require('../../packages/periph/src/connection/sipo_mock');
const { Tpic6b595Minimal, Tpic6b595Full } = require('../../packages/periph/src/chips/io_expander/tpic6b595');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function bytesEqual(buf, arr) {
    if (buf.length !== arr.length) return false;
    for (let i = 0; i < arr.length; i++) if (buf[i] !== arr[i]) return false;
    return true;
}

async function main() {
    // --- Construction (single device, SRCLR wired) ---
    const connection = new SiPoConnectionMock();
    const chip = new Tpic6b595Minimal(connection);
    checkTrue('init_clears', connection.clearCount === 1);
    checkTrue('init_writes_all_zero', bytesEqual(connection.writes[connection.writes.length - 1], [0x00]));
    checkTrue('init_shadow_zero', chip._shadow[0] === 0x00);

    // --- Construction with SRCLR not wired: clear() throws, driver swallows it ---
    const noSrclr = new SiPoConnectionMock({ hasSrclr: false });
    new Tpic6b595Minimal(noSrclr);
    checkTrue('init_no_srclr_does_not_throw', true);
    checkTrue('init_no_srclr_still_flushes', bytesEqual(noSrclr.writes[noSrclr.writes.length - 1], [0x00]));
    checkTrue('init_no_srclr_clear_not_counted', noSrclr.clearCount === 0);

    // --- pin() proxy: on/off/toggle/read/write/set ---
    const pin3 = chip.pin(3);
    await pin3.on();
    checkTrue('pin3_on_shadow', chip._shadow[0] === 0x08);
    checkTrue('pin3_on_wire', bytesEqual(connection.writes[connection.writes.length - 1], [0x08]));
    checkTrue('pin3_read_after_on', (await pin3.read()) === 1);

    await pin3.off();
    checkTrue('pin3_off_shadow', chip._shadow[0] === 0x00);
    checkTrue('pin3_read_after_off', (await pin3.read()) === 0);

    await pin3.toggle();
    checkTrue('pin3_toggle_to_on', chip._shadow[0] === 0x08);
    await pin3.toggle();
    checkTrue('pin3_toggle_to_off', chip._shadow[0] === 0x00);

    const pin5 = chip.pin(5);
    await pin5.set(true);
    checkTrue('pin5_set_true', chip._shadow[0] === 0x20);
    await pin3.on();
    checkTrue('pin3_on_preserves_pin5', chip._shadow[0] === 0x28);
    await pin5.set(false);
    checkTrue('pin5_set_false_preserves_pin3', chip._shadow[0] === 0x08);

    // --- writePort(): direct port write, replaces the whole byte ---
    await chip.writePort(0, 0x3C);
    checkTrue('write_port_shadow', chip._shadow[0] === 0x3C);
    checkTrue('write_port_wire', bytesEqual(connection.writes[connection.writes.length - 1], [0x3C]));

    // --- fill()/off() ---
    await chip.fill(true);
    checkTrue('fill_true_shadow', chip._shadow[0] === 0xFF);
    checkTrue('fill_true_wire', bytesEqual(connection.writes[connection.writes.length - 1], [0xFF]));
    await chip.off();
    checkTrue('off_is_fill_false', chip._shadow[0] === 0x00);
    checkTrue('off_wire', bytesEqual(connection.writes[connection.writes.length - 1], [0x00]));

    // --- Cascading wire-order reversal (numDevices=3) ---
    const cascadeConn = new SiPoConnectionMock();
    const cascade = new Tpic6b595Minimal(cascadeConn, 3);
    await cascade.writePort(0, 0xAA);
    await cascade.writePort(1, 0xBB);
    await cascade.writePort(2, 0xCC);
    checkTrue('cascade_wire_order_reversed',
        bytesEqual(cascadeConn.writes[cascadeConn.writes.length - 1], [0xCC, 0xBB, 0xAA]));

    const pinFar = cascade.pin(16); // device 2, bit 0
    await pinFar.on();
    checkTrue('cascade_far_device_pin', cascade._shadow[2] === 0xCD);
    checkTrue('cascade_far_device_wire',
        bytesEqual(cascadeConn.writes[cascadeConn.writes.length - 1], [0xCD, 0xBB, 0xAA]));

    // --- Full.clear(): propagates to the connection, or throws if unwired ---
    const fullConn = new SiPoConnectionMock();
    const full = new Tpic6b595Full(fullConn);
    full.clear();
    checkTrue('full_clear_calls_through', fullConn.clearCount === 2); // +1 from construction

    const noSrclrFull = new Tpic6b595Full(new SiPoConnectionMock({ hasSrclr: false }));
    let threw = false;
    try { noSrclrFull.clear(); } catch (e) { threw = true; }
    checkTrue('full_clear_throws_when_unwired', threw);

    // --- Full.setOutputEnable(): propagates, or throws if G unwired ---
    full.setOutputEnable(true);
    checkTrue('full_output_enable_true', fullConn.outputEnableCalls[fullConn.outputEnableCalls.length - 1] === true);
    full.setOutputEnable(false);
    checkTrue('full_output_enable_false', fullConn.outputEnableCalls[fullConn.outputEnableCalls.length - 1] === false);

    const noGFull = new Tpic6b595Full(new SiPoConnectionMock({ hasG: false }));
    threw = false;
    try { noGFull.setOutputEnable(true); } catch (e) { threw = true; }
    checkTrue('full_output_enable_throws_when_unwired', threw);

    // --- Full.writeAll(): zero-extends and truncates to numDevices ---
    const cascadeFullConn = new SiPoConnectionMock();
    const cascadeFull = new Tpic6b595Full(cascadeFullConn, 3);
    cascadeFull.writeAll([0x11, 0x22]); // shorter than numDevices -> zero-extend
    checkTrue('write_all_zero_extends',
        cascadeFull._shadow[0] === 0x11 && cascadeFull._shadow[1] === 0x22 && cascadeFull._shadow[2] === 0x00);
    checkTrue('write_all_zero_extends_wire',
        bytesEqual(cascadeFullConn.writes[cascadeFullConn.writes.length - 1], [0x00, 0x22, 0x11]));

    cascadeFull.writeAll([0x44, 0x55, 0x66, 0x77]); // longer -> truncate
    checkTrue('write_all_truncates',
        cascadeFull._shadow[0] === 0x44 && cascadeFull._shadow[1] === 0x55 && cascadeFull._shadow[2] === 0x66);
    checkTrue('write_all_truncates_wire',
        bytesEqual(cascadeFullConn.writes[cascadeFullConn.writes.length - 1], [0x66, 0x55, 0x44]));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
