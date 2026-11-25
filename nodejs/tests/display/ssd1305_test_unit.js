'use strict';
// Unit test for the SSD1305 driver — mocked I²C and display connections, no hardware.
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const S = require('../../packages/periph/src/chips/display/ssd1305');
const { DisplayConnectionMock } = require('../../packages/periph/src/connection/display_mock');

let passed = 0;
let failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const eq = (buf, arr) => Buffer.compare(Buffer.from(buf), Buffer.from(arr)) === 0;
const last = (conn) => conn.writes[conn.writes.length - 1];
async function build(Cls, ...args) {
    const c = new I2CConnectionMock();
    const chip = new Cls(c, ...args);
    await chip.ready();
    return [c, chip];
}
async function rejects(fn) {
    try { await fn(); return false; } catch (e) { return true; }
}
const SETUP = [0x00, 0xAE, 0xD5, 0x70, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0xA0, 0xC0, 0xDA, 0x12, 0x81, 0x80,
    0xD9, 0x22, 0xDB, 0x34, 0xAD, 0x8E, 0xA4, 0xA6, 0x20, 0x02];

async function main() {
    // ---- Minimal: initialization ----
    let [c, lcd] = await build(S.SSD1305Minimal);
    checkTrue('geometry_128x64', lcd.width === 128 && lcd.height === 64);
    checkTrue('init_setup_stream', eq(c.writes[0], SETUP));
    checkTrue('init_clear_page0_address', eq(c.writes[1], [0x00, 0xB0, 0x00, 0x10]));
    checkTrue('init_clear_chunk_sizes_31x4_8', JSON.stringify(c.writes.slice(2, 7).map((w) => w.length - 1)) === '[31,31,31,31,8]'
        && c.writes.slice(2, 7).every((w) => w[0] === 0x40 && w.subarray(1).every((b) => b === 0)));
    checkTrue('init_clears_8_pages', c.writes.length === 1 + 8 * 6 + 1);
    checkTrue('init_page7_address', eq(c.writes[1 + 7 * 6], [0x00, 0xB7, 0x00, 0x10]));
    checkTrue('init_display_on_last', eq(last(c), [0x00, 0xAF]));

    // ---- Minimal: pixels and show ----
    let n0 = c.writes.length;
    lcd.setPixel(3, 10);
    lcd.setPixel(127, 63);
    lcd.setPixel(128, 0);
    lcd.setPixel(-1, 5);
    checkTrue('set_pixel_framebuffer_only', c.writes.length === n0);
    await lcd.show();
    checkTrue('show_8_pages_6_writes', c.writes.length - n0 === 48);
    checkTrue('show_page1_address_with_offset_4', eq(c.writes[n0 + 6], [0x00, 0xB1, 0x04, 0x10]));
    checkTrue('show_pixel_page1_col3_bit2', c.writes[n0 + 7][1 + 3] === 0x04);
    checkTrue('show_pixel_page7_col127_bit7', c.writes[n0 + 47][c.writes[n0 + 47].length - 1] === 0x80);
    lcd.clear();
    n0 = c.writes.length;
    await lcd.show();
    checkTrue('clear_zeroes_framebuffer', c.writes.slice(n0).filter((w) => w[0] === 0x40).every((w) => w.subarray(1).every((b) => b === 0)));

    // ---- options and validation ----
    let [o] = await build(S.SSD1305Minimal, 128, 32, 0);
    checkTrue('height_32_sequential_com', o.writes[0][11] === 0xDA && o.writes[0][12] === 0x02);
    checkTrue('custom_height_multiplex', o.writes[0][4] === 0xA8 && o.writes[0][5] === 0x1F);
    let [o2, narrow] = await build(S.SSD1305Minimal, 100, 64, 16);
    n0 = o2.writes.length;
    await narrow.show();
    checkTrue('column_offset_16_address', eq(o2.writes[n0], [0x00, 0xB0, 0x00, 0x11]));
    for (const [label, args] of [['invalid_width', [133, 64, 0]], ['invalid_height_multiple', [128, 60, 0]],
        ['invalid_height_min', [128, 8, 0]], ['invalid_height_max', [128, 72, 0]], ['invalid_offset', [128, 64, 5]]]) {
        checkTrue(label, await rejects(async () => new S.SSD1305Minimal(new I2CConnectionMock(), ...args)));
    }

    // ---- Full ----
    let [fc, full] = await build(S.SSD1305Full);
    const cases = [
        ['set_contrast', () => full.setContrast(0x55), [0x00, 0x81, 0x55]],
        ['display_off', () => full.setDisplay(false), [0x00, 0xAE]],
        ['display_on', () => full.setDisplay(true), [0x00, 0xAF]],
        ['sleep', () => full.sleep(), [0x00, 0xAE]],
        ['wake', () => full.wake(), [0x00, 0xAF]],
        ['invert_on', () => full.setInvert(true), [0x00, 0xA7]],
        ['invert_off', () => full.setInvert(false), [0x00, 0xA6]],
        ['all_on', () => full.setAllOn(true), [0x00, 0xA5]],
        ['all_on_off', () => full.setAllOn(false), [0x00, 0xA4]],
        ['flip_both', () => full.setFlip(true, true), [0x00, 0xA1, 0xC8]],
        ['flip_vertical_only', () => full.setFlip(false, true), [0x00, 0xA0, 0xC8]],
        ['flip_none', () => full.setFlip(false, false), [0x00, 0xA0, 0xC0]],
        ['start_line_40', () => full.setStartLine(40), [0x00, 0x68]],
        ['display_offset', () => full.setDisplayOffset(12), [0x00, 0xD3, 12]],
        ['multiplex_48', () => full.setMultiplex(48), [0x00, 0xA8, 47]],
        ['clock', () => full.setClock(3, 8), [0x00, 0xD5, 0x82]],
        ['precharge', () => full.setPrecharge(2, 5), [0x00, 0xD9, 0x52]],
        ['com_sequential', () => full.setComConfig(false), [0x00, 0xDA, 0x02]],
        ['com_alternative', () => full.setComConfig(true), [0x00, 0xDA, 0x12]],
        ['com_alternative_remap', () => full.setComConfig(true, true), [0x00, 0xDA, 0x32]],
        ['vcomh_masked', () => full.setVcomh(0xFF), [0x00, 0xDB, 0x3C]],
        ['dim_mode', () => full.setDimMode(0x40, 0x20), [0x00, 0xAB, 0x00, 0x40, 0x20]],
        ['dim_on', () => full.setDim(true), [0x00, 0xAC]],
        ['dim_off', () => full.setDim(false), [0x00, 0xAF]],
        ['brightness', () => full.setBrightness(0x33), [0x00, 0x82, 0x33]],
        ['lut', () => full.setLut(49, 63, 40, 31), [0x00, 0x91, 49, 63, 40, 31]],
        ['bank_colors_1_16', () => full.setBankColors([1, 2, 3, 4]), [0x00, 0x92, 1, 2, 3, 4]],
        ['bank_colors_17_32', () => full.setBankColors([1, 2, 3, 4], [5, 6, 7, 8]), [0x00, 0x93, 5, 6, 7, 8]],
        ['area_color', () => full.setAreaColor(true, true), [0x00, 0xD8, 0x35]],
        ['scroll_h_right', () => full.setScrollHorizontal(true, 0, 7, 0), [0x00, 0x26, 1, 0, 0, 7]],
        ['scroll_h_left_interval_3_frames', () => full.setScrollHorizontal(false, 1, 3, 4, 2), [0x00, 0x27, 2, 1, 4, 3]],
        ['scroll_v', () => full.setScrollVertical(true, 0, 7, 6, 1, 5), [0x00, 0x29, 1, 0, 6, 7, 5]],
        ['scroll_area', () => full.setScrollArea(8, 40), [0x00, 0xA3, 8, 40]],
        ['scroll_start', () => full.scrollStart(), [0x00, 0x2F]],
        ['send_command_raw', () => full.sendCommand(0xE3, 0xE3), [0x00, 0xE3, 0xE3]],
    ];
    for (const [label, fn, expected] of cases) {
        await fn();
        checkTrue(label, eq(last(fc), expected));
    }
    n0 = fc.writes.length;
    await full.scrollStop();
    checkTrue('scroll_stop_rewrites_ram', eq(fc.writes[n0], [0x00, 0x2E]) && fc.writes.length - n0 === 1 + 48);
    await full.setAddressingMode('horizontal');
    checkTrue('addr_mode_horizontal', eq(last(fc), [0x00, 0x20, 0x00]));
    n0 = fc.writes.length;
    await full.show();
    checkTrue('show_horizontal_ranges', eq(fc.writes[n0], [0x00, 0x21, 4, 131, 0x22, 0, 7]));
    checkTrue('show_horizontal_data_stream', fc.writes.slice(n0 + 1).reduce((a, w) => a + w.length - 1, 0) === 128 * 8);
    await full.setAddressingMode('vertical');
    checkTrue('addr_mode_vertical', eq(last(fc), [0x00, 0x20, 0x01]));
    full.setPixel(0, 9);
    n0 = fc.writes.length;
    await full.show();
    checkTrue('show_vertical_column_major', fc.writes[n0 + 1][1] === 0x00 && fc.writes[n0 + 1][2] === 0x02);
    full.setPixel(0, 9, false);
    await full.setAddressingMode('page');
    checkTrue('addr_mode_page', eq(last(fc), [0x00, 0x20, 0x02]));
    checkTrue('addr_mode_invalid_rejects', await rejects(() => full.setAddressingMode('bogus')));
    n0 = fc.writes.length;
    await full.showPage(2);
    checkTrue('show_page_2', eq(fc.writes[n0], [0x00, 0xB2, 0x04, 0x10]) && fc.writes.length - n0 === 6);
    await full.showPage(8);
    checkTrue('show_page_out_of_range_ignored', fc.writes.length - n0 === 6);

    // ---- framebuffer drawing ----
    full.fill(true);
    checkTrue('fill_on', full.getPixel(0, 0) && full.getPixel(127, 63));
    full.fill(false);
    checkTrue('fill_off', !full.getPixel(0, 0) && !full.getPixel(127, 63));
    checkTrue('get_pixel_out_of_range_false', !full.getPixel(-1, 0) && !full.getPixel(0, 64));
    full.drawLine(0, 0, 9, 9);
    checkTrue('draw_line_diagonal', full.getPixel(0, 0) && full.getPixel(5, 5) && full.getPixel(9, 9) && !full.getPixel(5, 6));
    full.fill(false);
    full.drawRect(10, 20, 5, 4);
    checkTrue('draw_rect_outline', full.getPixel(10, 20) && full.getPixel(14, 23) && full.getPixel(12, 20) && !full.getPixel(12, 21));
    full.fill(false);
    full.drawRect(10, 20, 3, 3, true, true);
    checkTrue('draw_rect_filled', full.getPixel(11, 21) && full.getPixel(12, 22) && !full.getPixel(13, 22));
    full.fill(false);
    full.blit(4, 8, [0xFF, 0x0F, 0x01, 0x80], 2);
    checkTrue('blit_two_page_rows', full.getPixel(4, 8) && full.getPixel(4, 15) && full.getPixel(5, 8) && !full.getPixel(5, 12)
        && full.getPixel(5, 11) && full.getPixel(4, 16) && full.getPixel(5, 23) && !full.getPixel(4, 17));
    let threw = false;
    try { full.blit(4, 3, [1, 2]); } catch (e) { threw = true; }
    checkTrue('blit_unaligned_y_raises', threw);

    // ---- reads (I²C) ----
    let [rc, chip] = await build(S.SSD1305Full);
    rc.queueRead([0x40]);
    checkTrue('read_status_value', (await chip.readStatus()) === 0x40);
    checkTrue('read_status_writes_command_control_byte', eq(last(rc), [0x00]));
    rc.queueRead([0xEE, 0x11, 0x22, 0x33]);
    const k = rc.writes.length;
    checkTrue('read_page_discards_dummy', eq(await chip.readPage(2, 5, 3), [0x11, 0x22, 0x33]));
    checkTrue('read_page_sets_address', eq(rc.writes[k], [0x00, 0xB2, 0x05, 0x10]));
    checkTrue('read_page_data_control_byte', eq(rc.writes[k + 1], [0x40]));

    // ---- SPI ----
    const dc = new DisplayConnectionMock();
    const spi = new S.SSD1305SpiMinimal(dc);
    await spi.ready();
    const cmds = dc.events.filter((e) => e.type === 'command').map((e) => e.cmd);
    const datas = dc.events.filter((e) => e.type === 'data');
    checkTrue('spi_reset_first', dc.events[0].type === 'reset');
    checkTrue('spi_setup_stream_bytes', JSON.stringify(cmds.slice(0, 24)) === JSON.stringify(SETUP.slice(1)));
    checkTrue('spi_display_on_last_command', cmds[cmds.length - 1] === 0xAF);
    checkTrue('spi_init_clear_8_pages_132_bytes', datas.length === 8 && datas.every((e) => e.data.length === 132));
    const n = dc.events.length;
    spi.setPixel(5, 0);
    await spi.show();
    const shown = dc.events.slice(n);
    checkTrue('spi_show_page0_address', JSON.stringify(shown.slice(0, 3).map((e) => e.cmd)) === '[176,4,16]');
    checkTrue('spi_show_page_data_128_bytes', shown[3].type === 'data' && shown[3].data.length === 128 && shown[3].data[5] === 1);
    const dc2 = new DisplayConnectionMock();
    const sf = new S.SSD1305SpiFull(dc2);
    await sf.ready();
    checkTrue('spi_read_status_rejects', await rejects(() => sf.readStatus()));
    checkTrue('spi_read_page_rejects', await rejects(() => sf.readPage(0, 0, 1)));
    const m = dc2.events.length;
    await sf.setContrast(0x20);
    checkTrue('spi_full_contrast', JSON.stringify(dc2.events.slice(m).map((e) => e.cmd)) === '[129,32]');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
