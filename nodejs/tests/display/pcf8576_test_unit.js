'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { PCF8576Full } = require('../../packages/periph/src/chips/display/pcf8576');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    const connection = new I2CConnectionMock();
    const sensor = new PCF8576Full(connection);
    await flushMicrotasks(); // let the fire-and-forget constructor's _clear() finish

    // init: mode-set (E=1, bias=1/3, mode=1:4 -> 0x40|0x08|0x00|0x00 = 0x48),
    // then load-ptr(0) + 20 zero bytes to blank all RAM.
    checkTrue('init_mode_write', connection.writes[0].equals(Buffer.from([0x48])));
    const zeros21 = Buffer.from([0x00, ...new Array(20).fill(0)]);
    checkTrue('init_clear_write', connection.writes[1].equals(zeros21));

    // clear()
    await sensor.clear();
    const w = connection.writes;
    checkTrue('clear_mode_write', w[w.length - 2].equals(Buffer.from([0x48])));
    checkTrue('clear_data_write', w[w.length - 1].equals(zeros21));

    // writeRaw()
    await sensor.writeRaw(5, [0xAB, 0xCD]);
    checkTrue('write_raw', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x05, 0xAB, 0xCD])));

    const nBefore = connection.writes.length;
    await sensor.writeRaw(3, []);
    checkTrue('write_raw_empty_is_noop', connection.writes.length === nBefore);

    let threw = false;
    try {
        await sensor.writeRaw(-1, [0x01]);
    } catch (e) {
        threw = e instanceof RangeError;
    }
    checkTrue('write_raw_negative_throws', threw);

    threw = false;
    try {
        await sensor.writeRaw(40, [0x01]);
    } catch (e) {
        threw = e instanceof RangeError;
    }
    checkTrue('write_raw_too_high_throws', threw);

    // setDigit7seg(): digit '7' -> 0xE0, at RAM address 3*2=6.
    await sensor.setDigit7seg(3, PCF8576Full.SEVEN_SEG[7]);
    checkTrue('set_digit_7seg', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x06, 0xE0])));

    threw = false;
    try {
        await sensor.setDigit7seg(20, 0x00);
    } catch (e) {
        threw = e instanceof RangeError;
    }
    checkTrue('set_digit_7seg_too_high_throws', threw);

    // Full: enable/disable
    await sensor.disable();
    checkTrue('disable_writes_mode', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x40])));
    await sensor.enable();
    checkTrue('enable_writes_mode', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x48])));

    // setMode(): mode-set byte = 0x40 | E(0x08) | bias | mode.
    await sensor.setMode(PCF8576Full.BACKPLANES_1, PCF8576Full.BIAS_1_2);
    checkTrue('set_mode_static_bias_1_2', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x4D])));  // 0x40|8|4|1

    await sensor.setMode(PCF8576Full.BACKPLANES_2, PCF8576Full.BIAS_1_3);
    checkTrue('set_mode_1_2', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x4A])));  // 0x40|8|0|2

    await sensor.setMode(PCF8576Full.BACKPLANES_3, PCF8576Full.BIAS_1_3);
    checkTrue('set_mode_1_3', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x4B])));  // 0x40|8|0|3

    await sensor.setMode(PCF8576Full.BACKPLANES_4, PCF8576Full.BIAS_1_3);
    checkTrue('set_mode_1_4', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x48])));  // 0x40|8|0|0

    // setBlink()
    await sensor.setBlink(PCF8576Full.BLINK_1_HZ);
    checkTrue('set_blink', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x72])));  // 0x70|0|2

    await sensor.setBlink(PCF8576Full.BLINK_2_HZ, true);
    checkTrue('set_blink_alternate_bank', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x75])));  // 0x70|4|1

    threw = false;
    try {
        await sensor.setBlink(4);
    } catch (e) {
        threw = e instanceof RangeError;
    }
    checkTrue('set_blink_invalid_throws', threw);

    // setBank()
    await sensor.setBank(1, 0);
    checkTrue('set_bank', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x7A])));  // 0x78|(1<<1)|0

    // deviceSelect()
    await sensor.deviceSelect(5);
    checkTrue('device_select', connection.writes[connection.writes.length - 1].equals(Buffer.from([0x65])));  // 0x60|5

    threw = false;
    try {
        await sensor.deviceSelect(8);
    } catch (e) {
        threw = e instanceof RangeError;
    }
    checkTrue('device_select_invalid_throws', threw);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
