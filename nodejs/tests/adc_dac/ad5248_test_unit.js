'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AD5248Full } = require('../../packages/periph/src/chips/adc_dac/ad5248');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const last = (c) => Array.from(c.writes[c.writes.length - 1]);
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

async function main() {
    const connection = new I2CConnectionMock();
    const chip = new AD5248Full(connection);
    checkTrue('init_no_traffic', connection.writes.length === 0);

    // set_wiper(0, 100) -> [0x00, 100]; set_wiper(1, 200) -> [0x80, 200].
    await chip.setWiper(0, 100);
    checkTrue('set_wiper_ch0', same(last(connection), [0x00, 100]));
    await chip.setWiper(1, 200);
    checkTrue('set_wiper_ch1', same(last(connection), [0x80, 200]));
    await chip.setWiper(0, 999);
    checkTrue('set_wiper_clamps_high', same(last(connection), [0x00, 255]));
    await chip.setWiper(0, -5);
    checkTrue('set_wiper_clamps_low', same(last(connection), [0x00, 0]));
    let threw = false;
    try { await chip.setWiper(2, 0); } catch (e) { threw = true; }
    checkTrue('invalid_channel_throws', threw);

    // get_wiper sends the instruction byte alone, then reads one byte.
    await chip.setWiper(0, 100);
    await chip.setWiper(1, 200);
    const before = connection.writes.length;
    checkTrue('get_wiper_ch0', (await chip.getWiper(0)) === 100);
    checkTrue('get_wiper_instruction_alone', connection.writes.length === before + 1 && same(last(connection), [0x00]));
    checkTrue('get_wiper_ch1', (await chip.getWiper(1)) === 200);

    // Resistance model: R = D/256*RAB + 2*RW (RAB = 10k, RW = 160).
    await chip.setWiper(0, 128);
    checkTrue('get_resistance', Math.abs((await chip.getResistance(0)) - 5320.0) < 1e-9);
    await chip.setWiper(0, 0);
    checkTrue('get_resistance_zero_scale', Math.abs((await chip.getResistance(0)) - 320.0) < 1e-9);
    await chip.setResistance(1, 5320.0);
    checkTrue('set_resistance', same(last(connection), [0x80, 128]));
    await chip.setResistance(1, 1.0);
    checkTrue('set_resistance_clamps_low', same(last(connection), [0x80, 0]));
    await chip.setResistance(1, 1e9);
    checkTrue('set_resistance_clamps_high', same(last(connection), [0x80, 255]));

    // Full: setBoth / ratio.
    await chip.setBoth(10, 20);
    const n = connection.writes.length;
    checkTrue('set_both', same(Array.from(connection.writes[n - 2]), [0x00, 10]) && same(last(connection), [0x80, 20]));
    await chip.setRatio(0, 0.5);
    checkTrue('set_ratio', same(last(connection), [0x00, 128]));
    await chip.setRatio(0, 1.0);
    checkTrue('set_ratio_clamps_255', same(last(connection), [0x00, 255]));
    await chip.setWiper(1, 64);
    checkTrue('get_ratio', Math.abs((await chip.getRatio(1)) - 0.25) < 1e-9);

    // increment reads, adds, clamps, writes.
    await chip.setWiper(0, 250);
    checkTrue('increment_clamps', (await chip.increment(0, 10)) === 255 && same(last(connection), [0x00, 255]));
    checkTrue('decrement', (await chip.increment(0, -5)) === 250 && same(last(connection), [0x00, 250]));

    // shutdown writes the instruction with SD=1 and the current code; resume clears SD.
    await chip.setWiper(1, 77);
    checkTrue('not_shutdown_initially', !chip.isShutdown(1));
    await chip.shutdown(1);
    checkTrue('shutdown_write', same(last(connection), [0xC0, 77]));
    checkTrue('is_shutdown', chip.isShutdown(1) && !chip.isShutdown(0));
    await chip.setWiper(1, 99);
    checkTrue('set_wiper_keeps_sd', same(last(connection), [0xC0, 99]));
    await chip.resume(1);
    checkTrue('resume_write', same(last(connection), [0x80, 99]));
    checkTrue('resume_clears_state', !chip.isShutdown(1));

    // reset writes midscale to both channels with SD=0.
    await chip.shutdown(0);
    await chip.reset();
    const m = connection.writes.length;
    checkTrue('reset_writes', same(Array.from(connection.writes[m - 2]), [0x00, 0x80]) && same(last(connection), [0x80, 0x80]));
    checkTrue('reset_clears_shutdown', !chip.isShutdown(0));

    // setRab changes the model without I2C traffic.
    const k = connection.writes.length;
    chip.setRab(50000.0, 100.0);
    checkTrue('set_rab_no_traffic', connection.writes.length === k);
    await chip.setWiper(0, 128);
    checkTrue('set_rab_resistance', Math.abs((await chip.getResistance(0)) - 25200.0) < 1e-9);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
