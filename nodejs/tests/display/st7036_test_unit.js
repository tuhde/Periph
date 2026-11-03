'use strict';
// Unit test for the ST7036 driver — mocked display connection, no hardware.
const { DisplayConnectionMock } = require('../../packages/periph/src/connection/display_mock');
const S = require('../../packages/periph/src/chips/display/st7036');

let passed = 0;
let failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const hex2 = (n) => n.toString(16).toUpperCase().padStart(2, '0');
// R reset, I<hh> instruction, D<hh> data byte, d<ms> delay, u<us> microsecond delay.
function trace(conn, start = 0) {
    return conn.events.slice(start).map((e) => {
        if (e.type === 'reset') return 'R';
        if (e.type === 'command') return `I${hex2(e.cmd)}`;
        if (e.type === 'data') return `D${hex2(e.data[0])}${e.data.length !== 1 ? '!' : ''}`;
        if (e.type === 'delay') return `d${e.ms}`;
        if (e.type === 'delayUs') return `u${e.us}`;
        return '?';
    }).join(' ');
}
const mark = (conn) => conn.events.length;

async function full(...args) {
    const conn = new DisplayConnectionMock();
    const lcd = new S.ST7036Full(conn, ...args);
    await lcd.ready();
    return [conn, lcd];
}

async function main() {
    // ---- Minimal: DOGM162 3.3 V sequence ----
    let conn = new DisplayConnectionMock();
    let lcd = new S.ST7036Minimal(conn);
    await lcd.ready();
    checkTrue('init_sequence_dogm162_3v3', trace(conn) ===
        'R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30');
    checkTrue('instructions_are_single_bytes', conn.events.every((e) => e.type !== 'command' || e.data.length === 0));
    checkTrue('geometry_16x2', lcd.columns === 16 && lcd.rows === 2 && S.COLUMNS === 16 && S.ROWS === 2);

    let n;
    n = mark(conn); await lcd.clear();
    checkTrue('clear_waits_2ms', trace(conn, n) === 'I01 d2');
    n = mark(conn); await lcd.home();
    checkTrue('home_waits_2ms', trace(conn, n) === 'I02 d2');
    n = mark(conn); await lcd.setCursor(3, 1);
    checkTrue('set_cursor_row1_col3', trace(conn, n) === 'IC3 u30');
    n = mark(conn); await lcd.setCursor(0, 0);
    checkTrue('set_cursor_origin', trace(conn, n) === 'I80 u30');
    n = mark(conn); await lcd.setCursor(16, 0); await lcd.setCursor(0, 2); await lcd.setCursor(-1, 0);
    checkTrue('set_cursor_out_of_range_ignored', mark(conn) === n);
    n = mark(conn); await lcd.write('Hi');
    checkTrue('write_one_byte_per_transfer_with_gap', trace(conn, n) === 'D48 u30 D69 u30');
    n = mark(conn); await lcd.write('aäz~');
    checkTrue('write_non_ascii_becomes_question_mark', trace(conn, n) === 'D61 u30 D3F u30 D7A u30 D3F u30');
    n = mark(conn); await lcd.writeChar(0x05);
    checkTrue('write_char_raw', trace(conn, n) === 'D05 u30');
    n = mark(conn); await lcd.printAt(2, 1, 'x');
    checkTrue('print_at', trace(conn, n) === 'IC2 u30 D78 u30');
    n = mark(conn); await lcd.write(null);
    checkTrue('write_null_ignored', mark(conn) === n);

    // ---- Full: presets ----
    const cases = [
        [1, S.SUPPLY_3V3, 'R d40 I31 u30 I14 u30 I7C u30 I55 u30 I6D u30 d200 I30 u30 I0C u30 I01 d2 I06 u30', 'dogm081_3v3'],
        [1, S.SUPPLY_5V, 'R d40 I31 u30 I1C u30 I74 u30 I51 u30 I6A u30 d200 I30 u30 I0C u30 I01 d2 I06 u30', 'dogm081_5v'],
        [2, S.SUPPLY_3V3, 'R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30', 'dogm162_3v3'],
        [2, S.SUPPLY_5V, 'R d40 I39 u30 I1C u30 I74 u30 I52 u30 I69 u30 d200 I38 u30 I0C u30 I01 d2 I06 u30', 'dogm162_5v'],
        [3, S.SUPPLY_3V3, 'R d40 I39 u30 I15 u30 I72 u30 I55 u30 I6E u30 d200 I38 u30 I0C u30 I01 d2 I06 u30', 'dogm163_3v3'],
        [3, S.SUPPLY_5V, 'R d40 I39 u30 I1D u30 I7C u30 I50 u30 I6C u30 d200 I38 u30 I0C u30 I01 d2 I06 u30', 'dogm163_5v'],
    ];
    for (const [lines, supply, want, label] of cases) {
        [conn] = await full(lines, null, supply);
        checkTrue(label, trace(conn) === want);
    }
    [conn, lcd] = await full(3);
    checkTrue('dogm163_geometry', lcd.columns === 16 && lcd.rows === 3);
    n = mark(conn); await lcd.setCursor(0, 2);
    checkTrue('dogm163_row2_address_0x20', trace(conn, n) === 'IA0 u30');
    [conn, lcd] = await full(1);
    checkTrue('dogm081_geometry', lcd.columns === 8 && lcd.rows === 1);
    [conn] = await full(2, null, S.SUPPLY_3V3, 0x2A);
    checkTrue('contrast_override_0x2a', trace(conn) ===
        'R d40 I39 u30 I14 u30 I7A u30 I56 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30');
    [conn, lcd] = await full(2, 20);
    checkTrue('columns_override', lcd.columns === 20);
    let threw = false;
    try { new S.ST7036Full(new DisplayConnectionMock(), 9); } catch (e) { threw = e instanceof RangeError; }
    checkTrue('invalid_lines_throws', threw);

    // ---- Full: control instructions ----
    [conn, lcd] = await full();
    n = mark(conn); await lcd.display(false);
    checkTrue('display_off', trace(conn, n) === 'I08 u30');
    n = mark(conn); await lcd.display(true);
    checkTrue('display_on', trace(conn, n) === 'I0C u30');
    n = mark(conn); await lcd.cursor(true, true);
    checkTrue('cursor_and_blink', trace(conn, n) === 'I0E u30 I0F u30');
    n = mark(conn); await lcd.cursor(false, false);
    checkTrue('cursor_off', trace(conn, n) === 'I0D u30 I0C u30');
    n = mark(conn); await lcd.setEntryMode(false, true);
    checkTrue('entry_mode_decrement_shift', trace(conn, n) === 'I05 u30');
    n = mark(conn); await lcd.setEntryMode(true, false);
    checkTrue('entry_mode_increment', trace(conn, n) === 'I06 u30');
    n = mark(conn); await lcd.shiftCursor(true); await lcd.shiftCursor(false);
    checkTrue('shift_cursor', trace(conn, n) === 'I14 u30 I10 u30');
    n = mark(conn); await lcd.shiftDisplay(true); await lcd.shiftDisplay(false);
    checkTrue('shift_display', trace(conn, n) === 'I1C u30 I18 u30');
    n = mark(conn); await lcd.returnHome();
    checkTrue('return_home', trace(conn, n) === 'I02 d2');
    n = mark(conn); await lcd.setDdramAddress(0x45);
    checkTrue('set_ddram_address', trace(conn, n) === 'IC5 u30');
    n = mark(conn); await lcd.sendCommand(0x1F); await lcd.sendData(0x41);
    checkTrue('raw_command_and_data', trace(conn, n) === 'I1F u30 D41 u30');

    // ---- Full: extension table instructions ----
    [conn, lcd] = await full();
    n = mark(conn); await lcd.setContrast(0x2D);
    checkTrue('set_contrast_0x2d', trace(conn, n) === 'I39 u30 I56 u30 I7D u30 I38 u30');
    n = mark(conn); await lcd.setBooster(false);
    checkTrue('booster_off_keeps_contrast_high_bits', trace(conn, n) === 'I39 u30 I52 u30 I38 u30');
    n = mark(conn); await lcd.setBooster(true);
    checkTrue('booster_on', trace(conn, n) === 'I39 u30 I56 u30 I38 u30');
    n = mark(conn); await lcd.setFollower(true, 3);
    checkTrue('follower_on_ratio_3', trace(conn, n) === 'I39 u30 I6B u30 I38 u30');
    n = mark(conn); await lcd.setFollower(false, 7);
    checkTrue('follower_off_ratio_7', trace(conn, n) === 'I39 u30 I67 u30 I38 u30');
    n = mark(conn); await lcd.setBias(true);
    checkTrue('bias_quarter', trace(conn, n) === 'I39 u30 I1C u30 I38 u30');
    n = mark(conn); await lcd.setBias(false);
    checkTrue('bias_fifth', trace(conn, n) === 'I39 u30 I14 u30 I38 u30');
    n = mark(conn); await lcd.icon(true);
    checkTrue('icon_on', trace(conn, n) === 'I39 u30 I5E u30 I38 u30');
    n = mark(conn); await lcd.writeIcon(3, 0x15);
    checkTrue('write_icon_restores_ddram', trace(conn, n) === 'I39 u30 I43 u30 D15 u30 I38 u30 I80 u30');
    let c3; let three;
    [c3, three] = await full(3);
    n = mark(c3); await three.setBias(false);
    checkTrue('bias_keeps_fx_on_3_line_module', trace(c3, n) === 'I39 u30 I15 u30 I38 u30');

    // ---- Full: custom characters and double height ----
    [conn, lcd] = await full();
    await lcd.setCursor(5, 1);
    n = mark(conn);
    const glyph = [0x1F, 0x00, 0xFF, 0x01, 0x02, 0x04, 0x08, 0x10];
    await lcd.createChar(2, glyph);
    checkTrue('create_char_masks_rows_and_restores_address', trace(conn, n) ===
        'I50 u30 D1F u30 D00 u30 D1F u30 D01 u30 D02 u30 D04 u30 D08 u30 D10 u30 IC5 u30');
    n = mark(conn); await lcd.createChar(9, glyph);
    checkTrue('create_char_index_masked_to_0_7', trace(conn, n).startsWith('I48 u30'));
    n = mark(conn); await lcd.setDoubleHeight(true);
    checkTrue('double_height_ignored_on_2_line', mark(conn) === n);
    let c1; let one;
    [c1, one] = await full(1);
    n = mark(c1); await one.setDoubleHeight(true);
    checkTrue('double_height_1_line', trace(c1, n) === 'I34 u30 I34 u30');
    n = mark(c1); await one.setDoubleHeight(false);
    checkTrue('double_height_off_1_line', trace(c1, n) === 'I30 u30 I30 u30');
    [c3, three] = await full(3);
    n = mark(c3); await three.setDoubleHeight(true, true);
    checkTrue('double_height_3_line_upper', trace(c3, n) === 'I3E u30 I18 u30 I3C u30');
    n = mark(c3); await three.setDoubleHeight(true, false);
    checkTrue('double_height_3_line_lower', trace(c3, n) === 'I3E u30 I10 u30 I3C u30');

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
}
main().catch((e) => { console.error(e); process.exit(1); });
