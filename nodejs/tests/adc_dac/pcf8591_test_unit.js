'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { PCF8591Full } = require('../../packages/periph/src/chips/adc_dac/pcf8591');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function eq(a, b) {
    return Buffer.isBuffer(a) && Buffer.isBuffer(b) && Buffer.compare(a, b) === 0;
}

function last(connection) {
    return connection.writes[connection.writes.length - 1];
}

async function main() {
    const connection = new I2CConnectionMock();
    const adc = new PCF8591Full(connection);
    checkTrue('init', true);

    // read_channel(2): writes control byte CHN=2, reads 2 bytes; byte0 stale, byte1 fresh.
    connection.queueRead(Buffer.from([0x11, 0x7F]));
    checkTrue('read_channel', (await adc.read_channel(2)) === 0x7F);
    checkTrue('read_channel_writes_control', eq(last(connection), Buffer.from([0x02])));

    // read_channel clamps an out-of-range channel to 0.
    connection.queueRead(Buffer.from([0x00, 0x55]));
    checkTrue('read_channel_invalid_clamps_to_0', (await adc.read_channel(9)) === 0x55);
    checkTrue('read_channel_invalid_writes_ctrl_0', eq(last(connection), Buffer.from([0x00])));

    // read_all(): writes control with AI=1 (0x04), reads 5 bytes, discards stale byte.
    connection.queueRead(Buffer.from([0x00, 0x10, 0x20, 0x30, 0x40]));
    const all = await adc.read_all();
    checkTrue('read_all', all[0] === 0x10 && all[1] === 0x20 && all[2] === 0x30 && all[3] === 0x40);
    checkTrue('read_all_writes_ctrl', eq(last(connection), Buffer.from([0x04])));

    // configure(input_mode=3, auto_increment=true, dac_enabled=true) -> 0x74.
    await adc.configure(3, true, true);
    checkTrue('configure', eq(last(connection), Buffer.from([0x74])));

    // read_channel_voltage(0, vref=3.3, vagnd=0.0): raw=128.
    connection.queueRead(Buffer.from([0x00, 128]));
    const v = await adc.read_channel_voltage(0, 3.3, 0.0);
    checkTrue('read_channel_voltage', Math.abs(v - (128 * 3.3 / 256.0)) < 1e-9);

    // read_all_voltage(vref=3.3, vagnd=0.0): raws [0, 64, 128, 255].
    connection.queueRead(Buffer.from([0x00, 0, 64, 128, 255]));
    const voltages = await adc.read_all_voltage(3.3, 0.0);
    const raws = [0, 64, 128, 255];
    const voltageOk = voltages.every((val, i) => Math.abs(val - (raws[i] * 3.3 / 256.0)) < 1e-9);
    checkTrue('read_all_voltage', voltageOk);

    // read_differential(1): raw byte 200 -> signed two's complement = -56.
    connection.queueRead(Buffer.from([0x00, 200]));
    checkTrue('read_differential_negative', (await adc.read_differential(1)) === -56);

    // raw byte 100 (< 128) stays positive.
    connection.queueRead(Buffer.from([0x00, 100]));
    checkTrue('read_differential_positive', (await adc.read_differential(1)) === 100);

    // set_dac(200): sets AOE=1, AI=0, writes [ctrl, value].
    await adc.set_dac(200);
    let lastWrite = last(connection);
    checkTrue('set_dac_value', lastWrite[1] === 200);
    checkTrue('set_dac_sets_aoe', (lastWrite[0] & 0x40) !== 0);
    checkTrue('set_dac_clears_ai', (lastWrite[0] & 0x04) === 0);

    // set_dac_voltage(0.5) -> value = round(0.5*255) = 128.
    await adc.set_dac_voltage(0.5);
    checkTrue('set_dac_voltage', last(connection)[1] === 128);

    // disable_dac(): clears AOE bit.
    await adc.disable_dac();
    checkTrue('disable_dac', (last(connection)[0] & 0x40) === 0);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
