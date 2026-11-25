'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { PCF8579Minimal, PCF8579Full } = require('../../packages/periph/src/chips/display/pcf8579');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function throwsRange(fn) {
    try { await fn(); } catch (e) { return e instanceof RangeError; }
    return false;
}

const B = (...a) => Buffer.from(a);
const zeros = (n) => new Array(n).fill(0);

async function main() {
    const connection = new I2CConnectionMock();
    const lcd = new PCF8579Full(connection);
    await lcd.ready();
    const last = () => connection.writes[connection.writes.length - 1];
    const prev = () => connection.writes[connection.writes.length - 2];

    // init (row mode, 1:16, subaddress 0): clear-RAM transaction, then display on (normal).
    checkTrue('init_clear_write', connection.writes[0].equals(Buffer.from([0xE0, 0xC2, 0xFC, 0xF8, 0x00, ...zeros(160)])));
    checkTrue('init_display_on', connection.writes[1].equals(B(0x46)));

    {
        const c = new I2CConnectionMock();
        const l = new PCF8579Minimal(c, 3, 16, true);
        await l.ready();
        checkTrue('init_subaddress_mixed', c.writes[0].equals(Buffer.from([0xE3, 0xD2, 0xFC, 0xF8, 0x00, ...zeros(160)])));
        checkTrue('init_mixed_on', c.writes[1].equals(B(0x56)));
    }

    for (const [rows, byte] of [[8, 0x45], [24, 0x47], [32, 0x44]]) {
        const c = new I2CConnectionMock();
        const l = new PCF8579Minimal(c, 0, rows);
        await l.ready();
        checkTrue('init_on_rows_' + rows, c.writes[1].equals(B(byte)));
    }
    let threw = false;
    try { new PCF8579Minimal(new I2CConnectionMock(), 0, 12); } catch (e) { threw = e instanceof RangeError; }
    checkTrue('rows_invalid_throws', threw);
    threw = false;
    try { new PCF8579Minimal(new I2CConnectionMock(), 16); } catch (e) { threw = e instanceof RangeError; }
    checkTrue('subaddress_invalid_throws', threw);

    // clear()
    await lcd.clear();
    checkTrue('clear', last().equals(Buffer.from([0xE0, 0xF8, 0x00, ...zeros(160)])));

    // setPixel(): (0,0) -> RAM X=0, bank 0, bit 0.
    await lcd.setPixel(0, 0);
    checkTrue('set_pixel_write', last().equals(B(0xE0, 0xF0, 0, 0x01)));
    await lcd.setPixel(3, 9);
    checkTrue('set_pixel_bank1', last().equals(B(0xE0, 0xF1, 3, 0x02)));
    await lcd.setPixel(3, 10);
    checkTrue('set_pixel_shadow_or', last().equals(B(0xE0, 0xF1, 3, 0x06)));
    await lcd.setPixel(3, 9, false);
    checkTrue('set_pixel_clear', last().equals(B(0xE0, 0xF1, 3, 0x04)));
    await lcd.setPixel(39, 15);
    checkTrue('set_pixel_last_column', last().equals(B(0xE0, 0xF1, 39, 0x80)));
    let n = connection.writes.length;
    await lcd.setPixel(40, 0);
    await lcd.setPixel(0, 16);
    await lcd.setPixel(-1, 0);
    checkTrue('set_pixel_out_of_range_ignored', connection.writes.length === n);

    // getPixel() from the shadow, no bus traffic
    checkTrue('get_pixel_set', (await lcd.getPixel(0, 0)) === true);
    checkTrue('get_pixel_clear', (await lcd.getPixel(3, 9)) === false);
    checkTrue('get_pixel_no_traffic', connection.writes.length === n);

    // writeColumns(): truncated at column 39
    await lcd.writeColumns(38, 0, [1, 2, 3, 4]);
    checkTrue('write_columns_truncated', last().equals(B(0xE0, 0xF0, 38, 1, 2)));
    checkTrue('write_columns_bad_bank_throws', await throwsRange(() => lcd.writeColumns(0, 2, [1])));

    // fill()
    await lcd.fill(true);
    checkTrue('fill_bank0', prev().equals(Buffer.from([0xE0, 0xF0, 0, ...new Array(40).fill(0xFF)])));
    checkTrue('fill_bank1', last().equals(Buffer.from([0xE0, 0xF1, 0, ...new Array(40).fill(0xFF)])));
    await lcd.fill(false);
    checkTrue('fill_off_shadow', (await lcd.getPixel(5, 5)) === false);

    // drawLine(): horizontal line on row 0 -> one batched write
    await lcd.clear();
    n = connection.writes.length;
    await lcd.drawLine(0, 0, 3, 0);
    checkTrue('draw_line_one_write', connection.writes.length === n + 1);
    checkTrue('draw_line_bytes', last().equals(B(0xE0, 0xF0, 0, 1, 1, 1, 1)));

    // drawRect()
    await lcd.clear();
    await lcd.drawRect(0, 0, 3, 3);
    checkTrue('draw_rect_outline', (await lcd.getPixel(0, 0)) && (await lcd.getPixel(2, 2)) && !(await lcd.getPixel(1, 1)));
    await lcd.drawRect(0, 0, 3, 3, true, true);
    checkTrue('draw_rect_filled', await lcd.getPixel(1, 1));

    // showBitmap()
    await lcd.showBitmap(2, 8, [0xAA, 0xBB]);
    checkTrue('show_bitmap', last().equals(B(0xE0, 0xF1, 2, 0xAA, 0xBB)));
    checkTrue('show_bitmap_unaligned_throws', await throwsRange(() => lcd.showBitmap(0, 3, [1])));

    // setDisplay()
    await lcd.setDisplay(PCF8579Full.DISPLAY_BLANK);
    checkTrue('set_display_blank', last().equals(B(0x42)));
    await lcd.setDisplay(PCF8579Full.DISPLAY_INVERSE);
    checkTrue('set_display_inverse', last().equals(B(0x4E)));
    await lcd.setDisplay(PCF8579Full.DISPLAY_ALL_ON);
    checkTrue('set_display_all_on', last().equals(B(0x4A)));
    await lcd.setDisplay(PCF8579Full.DISPLAY_NORMAL);
    checkTrue('set_display_normal', last().equals(B(0x46)));

    // setStartBank() / scroll()
    await lcd.setStartBank(1);
    checkTrue('set_start_bank', last().equals(B(0x7D)));
    await lcd.scroll(1);
    checkTrue('scroll_wraps', last().equals(B(0x7C)));
    await lcd.scroll(-1);
    checkTrue('scroll_negative', last().equals(B(0x7D)));
    checkTrue('start_bank_invalid_throws', await throwsRange(() => lcd.setStartBank(4)));

    // writeRam(): modes
    await lcd.writeRam(10, 0, [1, 2]);
    checkTrue('write_ram_character', last().equals(B(0xE0, 0xF0, 10, 1, 2)));
    await lcd.writeRam(10, 2, [1, 2, 3, 4], PCF8579Full.ACCESS_HALF_GRAPHIC);
    checkTrue('write_ram_half', last().equals(B(0xE0, 0xF6, 10, 1, 2, 3, 4)));
    await lcd.writeRam(10, 0, [1, 2, 3, 4, 5, 6, 7, 8], PCF8579Full.ACCESS_FULL_GRAPHIC);
    checkTrue('write_ram_full', last().equals(B(0xE0, 0xF8, 10, 1, 2, 3, 4, 5, 6, 7, 8)));
    checkTrue('write_ram_shadow_order', lcd._shadow[3][10] === 4 && lcd._shadow[0][11] === 5);
    checkTrue('write_ram_past_39_throws', await throwsRange(() => lcd.writeRam(39, 0, [0, 0])));
    checkTrue('write_ram_bad_bank_throws', await throwsRange(() => lcd.writeRam(0, 4, [1])));
    checkTrue('write_ram_half_odd_bank_throws', await throwsRange(() => lcd.writeRam(0, 1, [1], 1)));

    // readRam(): address-set commands then a repeated-start read
    const data = await lcd.readRam(16, 0, 4);
    checkTrue('read_ram_commands', last().equals(B(0xE0, 0xF0, 16)));
    checkTrue('read_ram_length', data.length === 4);
    await lcd.readRam(5, 2, 3, PCF8579Full.ACCESS_HALF_GRAPHIC);
    checkTrue('read_ram_half_commands', last().equals(B(0xE0, 0xF6, 5)));

    // writeRamCascade(): runs past column 39, shadow only tracks this device
    await lcd.writeRamCascade(new Array(200).fill(7));
    checkTrue('cascade_write', last().equals(Buffer.from([0xE0, 0xF8, 0, ...new Array(200).fill(7)])));
    checkTrue('cascade_shadow', lcd._shadow[0][39] === 7);

    // setMode(): rows and T bit
    await lcd.setMode(24, true);
    checkTrue('set_mode_24_mixed', last().equals(B(0x57)));
    await lcd.setMode(16, false);
    checkTrue('set_mode_row', last().equals(B(0x46)));
    checkTrue('set_mode_invalid_throws', await throwsRange(() => lcd.setMode(12)));

    // subaddress is re-sent before every RAM access
    {
        const c = new I2CConnectionMock();
        const l = new PCF8579Full(c, 5);
        await l.setPixel(0, 0);
        checkTrue('pixel_uses_subaddress', c.writes[c.writes.length - 1][0] === 0xE5);
    }

    // reset(): re-runs init
    await lcd.reset();
    checkTrue('reset_clear', prev().equals(Buffer.from([0xE0, 0xC2, 0xFC, 0xF8, 0x00, ...zeros(160)])));
    checkTrue('reset_on', last().equals(B(0x46)));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
