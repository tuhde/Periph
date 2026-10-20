'use strict';
// Unit test for the UC1611s driver — mocked I²C connections, no hardware.
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const U = require('../../packages/periph/src/chips/display/uc1611s');
const { DisplayConnectionMock } = require('../../packages/periph/src/connection/display_mock');

let passed = 0;
let failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const DE = [0xDA, 0x80, 0x10];      // Status1 with DE set
const eq = (buf, arr) => Buffer.compare(Buffer.from(buf), Buffer.from(arr)) === 0;
const last = (conn) => conn.writes[conn.writes.length - 1];

function pair(status = true) {
    const cmd = new I2CConnectionMock();
    const dat = new I2CConnectionMock();
    if (status) cmd.queueRead(DE);   // Status1.DE set: init does not wait for the pump
    return [cmd, dat];
}
async function build(Cls, ...args) {
    const [cmd, dat] = pair();
    const chip = new Cls(cmd, dat, ...args);
    await chip.ready();
    return [cmd, dat, chip];
}
async function rejects(fn) {
    try { await fn(); return false; } catch (e) { return true; }
}

async function main() {
    // ---- Minimal: initialization ----
    let [cmd, dat, lcd] = await build(U.UC1611sMinimal);
    checkTrue('geometry_240x128', lcd.width === 240 && lcd.height === 128);
    // 1 System Reset + 1 configuration + 20 page addresses + 1 display enable + 1 NOP before the status poll
    checkTrue('init_command_transactions', cmd.writes.length === 24);
    checkTrue('init_system_reset_own_transaction', eq(cmd.writes[0], [0xE2]));
    checkTrue('init_configuration_stream', eq(cmd.writes[1], [0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x8F, 0x89, 0xD1]));
    checkTrue('init_clear_page0_address', eq(cmd.writes[2], [0x60, 0x70, 0x00, 0x10]));
    checkTrue('init_clear_page19_address', eq(cmd.writes[21], [0x63, 0x71, 0x00, 0x10]));
    checkTrue('init_display_enable_then_status_poll', eq(cmd.writes[22], [0xA9]) && eq(cmd.writes[23], [0xE3]));
    checkTrue('init_clear_data_chunks_20_pages_x_8', dat.writes.length === 160);
    checkTrue('init_data_chunks_32_and_16', dat.writes.every((w, i) => w.length === (i % 8 === 7 ? 16 : 32)));
    checkTrue('init_data_all_zero', dat.writes.every((w) => w.every((b) => b === 0)));

    // ---- Minimal: pixels and show ----
    let c0 = cmd.writes.length;
    let d0 = dat.writes.length;
    lcd.setPixel(3, 10);
    lcd.setPixel(239, 127);
    lcd.setPixel(240, 0);       // out of range, ignored
    lcd.setPixel(-1, 5);
    checkTrue('set_pixel_framebuffer_only', cmd.writes.length === c0 && dat.writes.length === d0);
    await lcd.show();
    checkTrue('show_16_pages_8_chunks', cmd.writes.length - c0 === 16 && dat.writes.length - d0 === 128);
    checkTrue('show_page1_address', eq(cmd.writes[c0 + 1], [0x61, 0x70, 0x00, 0x10]));
    checkTrue('show_pixel_page1_col3_bit2', dat.writes[d0 + 8][3] === 0x04);
    checkTrue('show_pixel_page15_col239_bit7', dat.writes[d0 + 127].length === 16 && dat.writes[d0 + 127][15] === 0x80);
    lcd.setPixel(3, 10, false);
    lcd.clear();
    d0 = dat.writes.length;
    await lcd.show();
    checkTrue('clear_zeroes_framebuffer', dat.writes.slice(d0).every((w) => w.every((b) => b === 0)));

    // ---- Minimal: argument validation ----
    for (const [label, args] of [['invalid_width', [241, 128]], ['invalid_height_multiple', [240, 130]], ['invalid_height_max', [240, 136]]]) {
        let threw = false;
        try { new U.UC1611sMinimal(...pair(false), ...args); } catch (e) { threw = e instanceof RangeError; }
        checkTrue(label, threw);
    }

    // ---- Full ----
    let fc, fd, full;
    [fc, fd, full] = await build(U.UC1611sFull);
    await full.setContrast(0x55);
    checkTrue('set_contrast', eq(last(fc), [0x81, 0x55]));
    await full.setDisplay(false);
    checkTrue('display_off_sleep', eq(last(fc), [0xA8]));
    fc.queueRead(DE);
    await full.wake();
    checkTrue('wake_display_on', eq(fc.writes[fc.writes.length - 2], [0xA9]));
    await full.sleep();
    checkTrue('sleep', eq(last(fc), [0xA8]));
    await full.setInvert(true);
    checkTrue('invert_on', eq(last(fc), [0xA7]));
    await full.setInvert(false);
    checkTrue('invert_off', eq(last(fc), [0xA6]));
    await full.setAllOn(true);
    checkTrue('all_on', eq(last(fc), [0xA5]));
    await full.setAllOn(false);
    checkTrue('all_on_off', eq(last(fc), [0xA4]));
    await full.setFlip(true, true);
    checkTrue('flip_mx_my', eq(last(fc), [0xC0, 0x06]));
    await full.setFlip(false, true);
    checkTrue('flip_my_only', eq(last(fc), [0xC0, 0x04]));
    await full.setScrollLine(40);
    checkTrue('scroll_line_40', eq(last(fc), [0x48, 0x52]));
    await full.setFixedLines(3);
    checkTrue('fixed_lines', eq(last(fc), [0x93]));
    await full.setBiasRatio(3);
    checkTrue('bias_ratio', eq(last(fc), [0xEB]));
    await full.setTemperatureCompensation(2);
    checkTrue('temperature_compensation', eq(last(fc), [0x26]));
    await full.setLineRate(1);
    checkTrue('line_rate', eq(last(fc), [0xA1]));
    await full.setPump(false);
    checkTrue('pump_external', eq(last(fc), [0x2C]));
    await full.setPump(true);
    checkTrue('pump_internal', eq(last(fc), [0x2F]));
    await full.setPanelLoading(true);
    checkTrue('panel_loading_high', eq(last(fc), [0x2B]));
    await full.setPanelLoading(false);
    checkTrue('panel_loading_low', eq(last(fc), [0x28]));
    await full.setPartialDisplay(true, 10, 100);
    checkTrue('partial_display_on', eq(last(fc), [0xF2, 10, 0xF3, 100, 0x87]));
    await full.setPartialDisplay(true, 4);
    checkTrue('partial_display_default_end', eq(last(fc), [0xF2, 4, 0xF3, 127, 0x87]));
    await full.setPartialDisplay(false);
    checkTrue('partial_display_off', eq(last(fc), [0x84]));
    await full.setNLineInversion(5, true);
    checkTrue('nline_inversion_xor', eq(last(fc), [0xC8, 0x45]));
    let k = fc.writes.length;
    await full.setIsolationClock(2, 3);
    checkTrue('isolation_clock_two_triple_bytes', fc.writes.length - k === 2 && eq(fc.writes[k], [0x82, 0x13, 0x02]) && eq(fc.writes[k + 1], [0x82, 0x14, 0x03]));
    await full.setDisplayPattern(2);
    checkTrue('display_pattern_2', eq(last(fc), [0xD5]));
    await full.setDisplayPattern(0);
    checkTrue('display_pattern_0', eq(last(fc), [0xD1]));

    let fd0 = fd.writes.length;
    full.setWritePattern(1);
    await full.showPage(0);
    checkTrue('write_pattern_1_page0_address', eq(last(fc), [0x60, 0x72, 0x00, 0x10]));
    checkTrue('show_page_8_chunks', fd.writes.length - fd0 === 8);
    full.setWritePattern(0);
    let before = fd.writes.length;
    await full.showPage(16);
    checkTrue('show_page_out_of_range_ignored', fd.writes.length === before);

    await full.sendCommand(0xE3, 0xE3);
    checkTrue('send_command_raw', eq(last(fc), [0xE3, 0xE3]));
    const db = fd.writes.length;
    await full.sendData(Buffer.alloc(70));
    checkTrue('send_data_chunked_32_32_6', fd.writes.length - db === 3 && last(fd).length === 6);

    k = fc.writes.length;
    fc.queueRead(DE);
    await full.reset();
    checkTrue('reset_sequence', fc.writes.length - k === 4 && eq(fc.writes[k], [0xE2]) && eq(fc.writes[k + 2], [0xA9]));
    checkTrue('reset_keeps_contrast_and_mapping', eq(fc.writes[k + 1], [0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x55, 0x89, 0xD1]));

    // ---- framebuffer drawing ----
    full.fill(true);
    checkTrue('fill_on', full.getPixel(0, 0) && full.getPixel(239, 127));
    full.fill(false);
    checkTrue('fill_off', !full.getPixel(0, 0) && !full.getPixel(239, 127));
    checkTrue('get_pixel_out_of_range_false', !full.getPixel(-1, 0) && !full.getPixel(0, 128));
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
    checkTrue('blit_two_page_rows', full.getPixel(4, 8) && full.getPixel(4, 15) && full.getPixel(5, 8) && !full.getPixel(5, 12) &&
        full.getPixel(5, 11) && full.getPixel(4, 16) && full.getPixel(5, 23) && !full.getPixel(4, 17));
    let threw = false;
    try { full.blit(4, 3, [1, 2]); } catch (e) { threw = e instanceof RangeError; }
    checkTrue('blit_unaligned_y_raises', threw);

    // ---- reads ----
    let rc, rd, chip;
    [rc, rd, chip] = await build(U.UC1611sFull);
    rc.queueRead([0xDA, 0x80, 0x10]);
    checkTrue('read_status_three_bytes', eq(await chip.readStatus(), [0xDA, 0x80, 0x10]));
    checkTrue('read_status_sends_nop_first_to_restart_status_cycle', eq(last(rc), [0xE3]));
    rd.queueRead([0xEE, 0x11, 0x22, 0x33]);
    k = rc.writes.length;
    checkTrue('read_ram_discards_dummy', eq(await chip.readRam(2, 5, 3), [0x11, 0x22, 0x33]));
    checkTrue('read_ram_sets_address', eq(rc.writes[k], [0x62, 0x70, 0x05, 0x10]));

    // top view and contrast options, smaller panel
    const [tc] = await build(U.UC1611sFull, 240, 128, true, 0x60);
    checkTrue('top_view_and_contrast_option', eq(tc.writes[1], [0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x60, 0x89, 0xD1]));
    const [sc] = await build(U.UC1611sMinimal, 100, 64);
    checkTrue('custom_height_com_end', eq(sc.writes[1], [0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x3F, 0xF2, 0x00, 0xF3, 0x3F, 0x81, 0x8F, 0x89, 0xD1]));

    // ---- error handling: NACK detection and retry ----
    let ec, ed, err;
    [ec, ed, err] = await build(U.UC1611sFull);
    checkTrue('clean_start_no_errors', err.ok() && err.retryCount() === 0 && err.errorCount() === 0);
    let e0 = ec.writes.length;
    ec.failNextWrites(2);
    await err.setContrast(0x66);
    checkTrue('command_retried_until_acknowledged', ec.writes.length - e0 === 3 && eq(ec.writes[e0], [0x81, 0x66]) && eq(last(ec), [0x81, 0x66]));
    checkTrue('retries_counted_and_ok_after_recovery', err.retryCount() === 2 && err.ok());
    ec.failNextWrites(100);
    e0 = ec.writes.length;
    checkTrue('command_rejects_after_max_attempts', await rejects(() => err.setInvert(true)));
    checkTrue('command_gives_up_after_max_attempts', ec.writes.length - e0 === U.MAX_ATTEMPTS);
    checkTrue('unrecovered_error_counted', !err.ok() && err.errorCount() === 1 && err.retryCount() === 2 + U.MAX_ATTEMPTS);
    ec.failNextWrites(0);
    err.clearErrors();
    checkTrue('clear_errors', err.ok() && err.retryCount() === 0);

    e0 = ec.writes.length;
    let ed0 = ed.writes.length;
    ed.failNextWrites(1);
    await err.showPage(1);
    checkTrue('page_retry_resends_address', ec.writes.length - e0 === 2 && eq(ec.writes[e0], [0x61, 0x70, 0x00, 0x10]) && eq(ec.writes[e0 + 1], [0x61, 0x70, 0x00, 0x10]));
    checkTrue('page_retry_rewrites_all_chunks', ed.writes.length - ed0 === 1 + 8 && err.retryCount() === 1 && err.ok());
    e0 = ec.writes.length;
    ed0 = ed.writes.length;
    ed.failNextWrites(1, 2);
    await err.showPage(2);
    checkTrue('page_retry_after_partial_write', ec.writes.length - e0 === 2 && ed.writes.length - ed0 === 3 + 8);

    const [dc, dd] = pair(false);
    dc.failNextWrites(1000);
    const dead = new U.UC1611sMinimal(dc, dd);
    checkTrue('dead_chip_rejects_at_init', await rejects(() => dead.ready()) && dc.writes.length === U.MAX_ATTEMPTS && dd.writes.length === 0);

    let qc, qd, rq;
    [qc, qd, rq] = await build(U.UC1611sFull);
    qc.failNextReads(2);
    qc.queueRead([0xDA, 0x80, 0x10]);
    checkTrue('read_status_retried', eq(await rq.readStatus(), [0xDA, 0x80, 0x10]) && rq.retryCount() >= 2);
    qc.failNextReads(100);
    checkTrue('read_status_rejects_after_max_attempts', (await rejects(() => rq.readStatus())) && !rq.ok());
    rq.clearErrors();
    qc.failNextReads(0);
    qd.failNextReads(1);
    qd.queueRead([0xEE, 0x0A, 0x0B]);
    const q0 = qc.writes.length;
    checkTrue('read_ram_retry_resets_address', eq(await rq.readRam(1, 4, 2), [0x0A, 0x0B]) && qc.writes.length - q0 === 2);
    rq.clearErrors();
    qc.queueRead([0xD2, 0x80, 0x10]);
    qc.queueRead([0xDA, 0x80, 0x10]);
    await rq.wake();
    checkTrue('wake_polls_display_enable', rq.ok() && eq(qc.writes[qc.writes.length - 3], [0xA9]) && eq(last(qc), [0xE3]));

    // ---- SPI (4-wire) via the display connection ----------------------------------------
    {
        const dc = new DisplayConnectionMock();
        const sp = new U.UC1611sSpiMinimal(dc);
        await sp.ready();
        const cmds = dc.events.filter((e) => e.type === 'command').map((e) => e.cmd);
        const datas = dc.events.filter((e) => e.type === 'data');
        checkTrue('spi_geometry', sp.width === 240 && sp.height === 128);
        checkTrue('spi_hardware_reset_first', dc.events[0].type === 'reset' && dc.resetCount === 1);
        checkTrue('spi_init_stream_one_command_per_byte',
            JSON.stringify(cmds.slice(0, 13)) === JSON.stringify([0xE2, 0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x8F]));
        checkTrue('spi_arguments_sent_as_commands', dc.events.filter((e) => e.type === 'command').every((e) => e.data.length === 0));
        checkTrue('spi_init_clears_20_pages_one_transfer_each', datas.length === 20 && datas.every((e) => e.data.length === 240));
        checkTrue('spi_no_retries', sp.retryCount() === 0 && sp.ok());
        const n = dc.events.length;
        sp.setPixel(0, 0);
        await sp.show();
        const shown = dc.events.slice(n).filter((e) => e.type === 'data');
        checkTrue('spi_show_16_pages_of_240', shown.length === 16 && shown.every((e) => e.data.length === 240) && shown[0].data[0] === 1);

        const dc2 = new DisplayConnectionMock();
        const fu = new U.UC1611sSpiFull(dc2);
        await fu.ready();
        checkTrue('spi_read_status_unsupported', await rejects(() => fu.readStatus()));
        checkTrue('spi_read_ram_unsupported', await rejects(() => fu.readRam(0, 0, 2)));
        checkTrue('spi_unsupported_read_not_an_error', fu.ok() && fu.errorCount() === 0);
        let m = dc2.events.length;
        await fu.setContrast(0x50);
        checkTrue('spi_full_contrast_two_commands', JSON.stringify(dc2.events.slice(m).map((e) => e.cmd)) === JSON.stringify([0x81, 0x50]));
        m = dc2.events.length;
        await fu.wake();
        checkTrue('spi_wake_sends_display_on', JSON.stringify(dc2.events.slice(m).filter((e) => e.type === 'command').map((e) => e.cmd)) === JSON.stringify([0xA9]));
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
