'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { HT16K33Full } = require('../../packages/periph/src/chips/led/ht16k33');

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
    const d = new HT16K33Full(c);
    await d.ready;
    const w = () => c.writes;
    const last = () => c.writes[c.writes.length - 1];

    checkTrue('init_osc', w()[0].equals(b(0x21)));
    checkTrue('init_row', w()[1].equals(b(0xA0)));
    checkTrue('init_dim', w()[2].equals(b(0xEF)));
    checkTrue('init_ram', w()[3].equals(Buffer.alloc(17)));
    checkTrue('init_display', w()[4].equals(b(0x81)));

    d.setPixel(1, 9);
    await d.show();
    const exp = Buffer.alloc(17);
    exp[1 + 3] = 0x02;
    checkTrue('set_pixel_show', last().equals(exp));
    checkTrue('get_pixel', d.getPixel(1, 9) && !d.getPixel(1, 8));
    d.setPixel(7, 15);
    checkTrue('set_pixel_last', d._ram[15] === 0x80);
    d.setPixel(1, 9, false);
    checkTrue('clear_pixel', !d.getPixel(1, 9));
    checkTrue('com_range', await throwsRange(() => d.setPixel(8, 0)));
    checkTrue('row_range', await throwsRange(() => d.setPixel(0, 16)));

    await d.clear();
    checkTrue('clear', last().equals(Buffer.alloc(17)));
    await d.writeRaw([0xAB, 0xCD], 4);
    checkTrue('write_raw', last().equals(b(0x04, 0xAB, 0xCD)) && d._ram[5] === 0xCD);
    checkTrue('write_raw_overflow', await throwsRange(() => d.writeRaw([0, 0, 0], 14)));
    checkTrue('write_raw_empty', await throwsRange(() => d.writeRaw([])));

    await d.setBrightness(7);
    checkTrue('brightness', last().equals(b(0xE7)));
    checkTrue('brightness_range', await throwsRange(() => d.setBrightness(16)));

    await d.setBlink(1);
    checkTrue('blink_1hz', last().equals(b(0x85)));
    await d.setBlink(0.5);
    checkTrue('blink_0_5hz', last().equals(b(0x87)));
    await d.setBlink(2);
    checkTrue('blink_2hz', last().equals(b(0x83)));
    checkTrue('blink_invalid', await throwsRange(() => d.setBlink(3)));
    await d.displayOff();
    checkTrue('display_off_keeps_blink', last().equals(b(0x82)));
    await d.displayOn();
    checkTrue('display_on', last().equals(b(0x83)));
    await d.setBlink(0);

    await d.setIntOutput(true);
    checkTrue('int_low', last().equals(b(0xA1)));
    await d.setIntOutput(true, true);
    checkTrue('int_high', last().equals(b(0xA3)));

    await d.standby();
    checkTrue('standby', last().equals(b(0x20)));
    const n = w().length;
    await d.wake();
    checkTrue('wake_sequence', w()[n].equals(b(0x21)) && w()[n + 1].equals(b(0xA3)) &&
        w()[n + 2].equals(b(0xE7)) && w()[n + 3].length === 17 && w()[n + 4].equals(b(0x81)));

    c.queueRead(b(0x01, 0x00, 0x00, 0x10, 0x00, 0x00));
    const keys = await d.readKeys();
    checkTrue('read_keys_ptr', last().equals(b(0x40)));
    checkTrue('read_keys_mask', keys === (1 + Math.pow(2, 25)));
    c.queueRead(b(0x01));
    checkTrue('key_pressed', (await d.keyPressed()) && last().equals(b(0x60)));
    c.queueRead(b(0x00));
    checkTrue('poll_none', (await d.pollInterrupt()) === 0);
    c.queueRead(b(0x01));
    c.queueRead(b(0, 0, 0, 0, 0x01, 0));
    checkTrue('poll_keys', (await d.pollInterrupt()) === Math.pow(2, 26));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
