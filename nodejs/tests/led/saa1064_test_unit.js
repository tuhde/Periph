'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { SAA1064Full } = require('../../packages/periph/src/chips/led/saa1064');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const b = (...bytes) => Buffer.from(bytes);

async function throwsRange(fn) {
    try { await fn(); } catch (e) { return e instanceof RangeError; }
    return false;
}

async function main() {
    const c = new I2CConnectionMock();
    const d = new SAA1064Full(c);
    await d.ready;
    const last = () => c.writes[c.writes.length - 1];

    checkTrue('init', c.writes[0].equals(b(0x00, 0x47, 0, 0, 0, 0)));

    await d.showDigits([1, 2, 3, 4]);
    checkTrue('show_digits', last().equals(b(0x01, 0x06, 0x5B, 0x4F, 0x66)));
    await d.showDigits([null, 0, 9, 8]);
    checkTrue('show_digits_blank', last().equals(b(0x01, 0x00, 0x3F, 0x6F, 0x7F)));
    checkTrue('show_digits_range', await throwsRange(() => d.showDigits([10, 0, 0, 0])));
    checkTrue('show_digits_len', await throwsRange(() => d.showDigits([1, 2, 3])));

    await d.showNumber(42);
    checkTrue('show_number', last().equals(b(0x01, 0x00, 0x00, 0x66, 0x5B)));
    await d.showNumber(0);
    checkTrue('show_number_zero', last().equals(b(0x01, 0x00, 0x00, 0x00, 0x3F)));
    await d.showNumber(9999);
    checkTrue('show_number_max', last().equals(b(0x01, 0x6F, 0x6F, 0x6F, 0x6F)));
    checkTrue('show_number_range', (await throwsRange(() => d.showNumber(10000))) && (await throwsRange(() => d.showNumber(-1))));

    await d.clear();
    checkTrue('clear', last().equals(b(0x01, 0, 0, 0, 0)));
    await d.writeRaw([0xAA, 0x55], 2);
    checkTrue('write_raw', last().equals(b(0x02, 0xAA, 0x55)));
    checkTrue('write_raw_overflow', await throwsRange(() => d.writeRaw([1, 2, 3], 3)));
    checkTrue('write_raw_first', (await throwsRange(() => d.writeRaw([1], 0))) && (await throwsRange(() => d.writeRaw([1], 5))));
    checkTrue('write_raw_empty', await throwsRange(() => d.writeRaw([])));

    await d.setMode(false);
    checkTrue('mode_static', last().equals(b(0x00, 0x46)));
    await d.setMode(true);
    checkTrue('mode_dynamic', last().equals(b(0x00, 0x47)));
    await d.blank(true, false);
    checkTrue('blank_13', last().equals(b(0x00, 0x45)));
    await d.blank(true, true);
    checkTrue('blank_both', last().equals(b(0x00, 0x41)));
    await d.blank(false, false);
    checkTrue('blank_none', last().equals(b(0x00, 0x47)));
    await d.setCurrent(21);
    checkTrue('current_21', last().equals(b(0x00, 0x77)));
    await d.setCurrent(0);
    checkTrue('current_0', last().equals(b(0x00, 0x07)));
    await d.setCurrent(12);
    checkTrue('current_12', last().equals(b(0x00, 0x47)));
    checkTrue('current_invalid', await throwsRange(() => d.setCurrent(10)));
    await d.segmentTest(true);
    checkTrue('test_on', last().equals(b(0x00, 0x4F)));
    await d.segmentTest(false);
    checkTrue('test_off', last().equals(b(0x00, 0x47)));
    await d.writeControl(0xFF);
    checkTrue('write_control_bit7', last().equals(b(0x00, 0x7F)));
    await d.writeControl(0x47);

    await d.showDigit(2, 7, true);
    checkTrue('show_digit_dp', last().equals(b(0x02, 0x87)));
    await d.setDecimalPoint(2, false);
    checkTrue('dp_off', last().equals(b(0x02, 0x07)));
    await d.setDecimalPoint(3, true);
    checkTrue('dp_on', last().equals(b(0x03, 0xD5)));
    checkTrue('show_digit_position', await throwsRange(() => d.showDigit(5, 1)));
    checkTrue('dp_position', await throwsRange(() => d.setDecimalPoint(0, true)));

    d.setSegmentMap({ a: 7, b: 6, c: 5, d: 4, e: 3, f: 2, g: 1, dp: 0 });
    await d.showDigits([1, 1, 1, 1]);
    checkTrue('segment_map', last().equals(b(0x01, 0x60, 0x60, 0x60, 0x60)));
    checkTrue('segment_map_missing', await throwsRange(async () => d.setSegmentMap({ a: 0 })));
    checkTrue('segment_map_range', await throwsRange(async () => d.setSegmentMap({ a: 8, b: 1, c: 2, d: 3, e: 4, f: 5, g: 6, dp: 7 })));

    c.queueRead(b(0x80));
    checkTrue('read_status', (await d.readStatus()) === 0x80);
    c.queueRead(b(0x80));
    checkTrue('power_reset_flag_set', (await d.powerResetFlag()) === true);
    c.queueRead(b(0x00));
    checkTrue('power_reset_flag_clear', (await d.powerResetFlag()) === false);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
