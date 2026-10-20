"""Unit test for the UC1611s driver — mocked I²C connections, no hardware."""

from periph.chips.display.uc1611s import UC1611sFull, UC1611sMinimal
from periph.connection.i2c_mock import I2CConnectionMock

passed = 0
failed = 0
DE = bytes((0xDA, 0x80, 0x10))      # Status1 with DE set


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def mock_pair(status=True):
    cmd, dat = I2CConnectionMock(), I2CConnectionMock()
    if status:
        cmd.queue_read(DE)          # Status1.DE set: init does not wait for the pump
    return cmd, dat


# ---- Minimal: initialization ------------------------------------------------------
cmd, dat = mock_pair()
lcd = UC1611sMinimal(cmd, dat)
check_true('geometry_240x128', lcd.width == 240 and lcd.height == 128)
# 1 System Reset + 1 configuration + 20 page addresses + 1 display enable + 1 NOP before the status poll
check_true('init_command_transactions', len(cmd.writes) == 24)
check_true('init_system_reset_own_transaction', cmd.writes[0] == b'\xE2')
check_true('init_configuration_stream', cmd.writes[1] == bytes((0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00,
                                                                0xF3, 0x7F, 0x81, 0x8F, 0x89, 0xD1)))
check_true('init_clear_page0_address', cmd.writes[2] == bytes((0x60, 0x70, 0x00, 0x10)))
check_true('init_clear_page19_address', cmd.writes[21] == bytes((0x63, 0x71, 0x00, 0x10)))
check_true('init_display_enable_then_status_poll', cmd.writes[22] == b'\xA9' and cmd.writes[23] == b'\xE3')
check_true('init_clear_data_chunks_20_pages_x_8', len(dat.writes) == 160)
check_true('init_data_chunks_32_and_16', all(len(w) == (16 if i % 8 == 7 else 32) for i, w in enumerate(dat.writes)))
check_true('init_data_all_zero', all(not any(w) for w in dat.writes))

# ---- Minimal: pixels and show -----------------------------------------------------
c0, d0 = len(cmd.writes), len(dat.writes)
lcd.set_pixel(3, 10)
lcd.set_pixel(239, 127)
lcd.set_pixel(240, 0)       # out of range, ignored
lcd.set_pixel(-1, 5)
check_true('set_pixel_framebuffer_only', len(cmd.writes) == c0 and len(dat.writes) == d0)
lcd.show()
check_true('show_16_pages_8_chunks', len(cmd.writes) - c0 == 16 and len(dat.writes) - d0 == 128)
check_true('show_page1_address', cmd.writes[c0 + 1] == bytes((0x61, 0x70, 0x00, 0x10)))
check_true('show_pixel_page1_col3_bit2', dat.writes[d0 + 8][3] == 0x04)
check_true('show_pixel_page15_col239_bit7', len(dat.writes[d0 + 127]) == 16 and dat.writes[d0 + 127][15] == 0x80)
lcd.set_pixel(3, 10, False)
lcd.clear()
d0 = len(dat.writes)
lcd.show()
check_true('clear_zeroes_framebuffer', not any(any(w) for w in dat.writes[d0:]))

# ---- Minimal: argument validation -------------------------------------------------
for label, args in (('invalid_width', (241, 128)), ('invalid_height_multiple', (240, 130)), ('invalid_height_max', (240, 136))):
    try:
        UC1611sMinimal(*mock_pair(False), *args)
        check_true(label, False)
    except ValueError:
        check_true(label, True)

# ---- Full -------------------------------------------------------------------------
fc, fd = mock_pair()
full = UC1611sFull(fc, fd)


def last(conn):
    return conn.writes[-1]


full.set_contrast(0x55)
check_true('set_contrast', last(fc) == bytes((0x81, 0x55)))
full.set_display(False)
check_true('display_off_sleep', last(fc) == b'\xA8')
fc.queue_read(DE)
full.wake()
check_true('wake_display_on', fc.writes[-2] == b'\xA9')
full.sleep()
check_true('sleep', last(fc) == b'\xA8')
full.set_invert(True)
check_true('invert_on', last(fc) == b'\xA7')
full.set_invert(False)
check_true('invert_off', last(fc) == b'\xA6')
full.set_all_on(True)
check_true('all_on', last(fc) == b'\xA5')
full.set_all_on(False)
check_true('all_on_off', last(fc) == b'\xA4')
full.set_flip(True, True)
check_true('flip_mx_my', last(fc) == bytes((0xC0, 0x06)))
full.set_flip(False, True)
check_true('flip_my_only', last(fc) == bytes((0xC0, 0x04)))
full.set_scroll_line(40)
check_true('scroll_line_40', last(fc) == bytes((0x48, 0x52)))
full.set_fixed_lines(3)
check_true('fixed_lines', last(fc) == b'\x93')
full.set_bias_ratio(3)
check_true('bias_ratio', last(fc) == b'\xEB')
full.set_temperature_compensation(2)
check_true('temperature_compensation', last(fc) == b'\x26')
full.set_line_rate(1)
check_true('line_rate', last(fc) == b'\xA1')
full.set_pump(False)
check_true('pump_external', last(fc) == b'\x2C')
full.set_pump(True)
check_true('pump_internal', last(fc) == b'\x2F')
full.set_panel_loading(True)
check_true('panel_loading_high', last(fc) == b'\x2B')
full.set_panel_loading(False)
check_true('panel_loading_low', last(fc) == b'\x28')
full.set_partial_display(True, 10, 100)
check_true('partial_display_on', last(fc) == bytes((0xF2, 10, 0xF3, 100, 0x87)))
full.set_partial_display(True, 4)
check_true('partial_display_default_end', last(fc) == bytes((0xF2, 4, 0xF3, 127, 0x87)))
full.set_partial_display(False)
check_true('partial_display_off', last(fc) == b'\x84')
full.set_nline_inversion(5, True)
check_true('nline_inversion_xor', last(fc) == bytes((0xC8, 0x45)))
k = len(fc.writes)
full.set_isolation_clock(2, 3)
check_true('isolation_clock_two_triple_bytes', fc.writes[k:] == [bytes((0x82, 0x13, 0x02)), bytes((0x82, 0x14, 0x03))])
full.set_display_pattern(2)
check_true('display_pattern_2', last(fc) == b'\xD5')
full.set_display_pattern(0)
check_true('display_pattern_0', last(fc) == b'\xD1')

fd0 = len(fd.writes)
full.set_write_pattern(1)
full.show_page(0)
check_true('write_pattern_1_page0_address', last(fc) == bytes((0x60, 0x72, 0x00, 0x10)))
check_true('show_page_8_chunks', len(fd.writes) - fd0 == 8)
full.set_write_pattern(0)
before = len(fd.writes)
full.show_page(16)
check_true('show_page_out_of_range_ignored', len(fd.writes) == before)

full.send_command(0xE3, 0xE3)
check_true('send_command_raw', last(fc) == b'\xE3\xE3')
db = len(fd.writes)
full.send_data(bytes(70))
check_true('send_data_chunked_32_32_6', len(fd.writes) - db == 3 and len(last(fd)) == 6)

k = len(fc.writes)
fc.queue_read(DE)
full.reset()
check_true('reset_sequence', len(fc.writes) - k == 4 and fc.writes[k] == b'\xE2' and fc.writes[k + 2] == b'\xA9')
check_true('reset_keeps_contrast_and_mapping', fc.writes[k + 1] == bytes((0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00,
                                                                          0xF3, 0x7F, 0x81, 0x55, 0x89, 0xD1)))

# ---- framebuffer drawing ----------------------------------------------------------
full.fill(True)
check_true('fill_on', full.get_pixel(0, 0) and full.get_pixel(239, 127))
full.fill(False)
check_true('fill_off', not full.get_pixel(0, 0) and not full.get_pixel(239, 127))
check_true('get_pixel_out_of_range_false', not full.get_pixel(-1, 0) and not full.get_pixel(0, 128))
full.draw_line(0, 0, 9, 9)
check_true('draw_line_diagonal', full.get_pixel(0, 0) and full.get_pixel(5, 5) and full.get_pixel(9, 9) and not full.get_pixel(5, 6))
full.fill(False)
full.draw_rect(10, 20, 5, 4)
check_true('draw_rect_outline', full.get_pixel(10, 20) and full.get_pixel(14, 23) and full.get_pixel(12, 20) and not full.get_pixel(12, 21))
full.fill(False)
full.draw_rect(10, 20, 3, 3, True, True)
check_true('draw_rect_filled', full.get_pixel(11, 21) and full.get_pixel(12, 22) and not full.get_pixel(13, 22))
full.fill(False)
full.blit(4, 8, bytes((0xFF, 0x0F, 0x01, 0x80)), 2)
check_true('blit_two_page_rows', full.get_pixel(4, 8) and full.get_pixel(4, 15) and full.get_pixel(5, 8) and not full.get_pixel(5, 12)
           and full.get_pixel(5, 11) and full.get_pixel(4, 16) and full.get_pixel(5, 23) and not full.get_pixel(4, 17))
try:
    full.blit(4, 3, bytes((1, 2)))
    check_true('blit_unaligned_y_raises', False)
except ValueError:
    check_true('blit_unaligned_y_raises', True)

# ---- reads ------------------------------------------------------------------------
rc, rd = mock_pair()
chip = UC1611sFull(rc, rd)
rc.queue_read(bytes((0xDA, 0x80, 0x10)))
check_true('read_status_three_bytes', chip.read_status() == bytes((0xDA, 0x80, 0x10)))
check_true('read_status_sends_nop_first_to_restart_status_cycle', rc.writes[-1] == b'\xE3')
rd.queue_read(bytes((0xEE, 0x11, 0x22, 0x33)))
k = len(rc.writes)
check_true('read_ram_discards_dummy', chip.read_ram(2, 5, 3) == bytes((0x11, 0x22, 0x33)))
check_true('read_ram_sets_address', rc.writes[k] == bytes((0x62, 0x70, 0x05, 0x10)))

# top view and contrast options, smaller panel
tc, td = mock_pair()
UC1611sFull(tc, td, 240, 128, True, 0x60)
check_true('top_view_and_contrast_option', tc.writes[1] == bytes((0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00,
                                                                  0xF3, 0x7F, 0x81, 0x60, 0x89, 0xD1)))
sc, sd = mock_pair()
UC1611sMinimal(sc, sd, 100, 64)
check_true('custom_height_com_end', sc.writes[1] == bytes((0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x3F, 0xF2, 0x00,
                                                          0xF3, 0x3F, 0x81, 0x8F, 0x89, 0xD1)))

# ---- error handling: NACK detection and retry -------------------------------------
ec, ed = mock_pair()
err = UC1611sFull(ec, ed)
check_true('clean_start_no_errors', err.ok() and err.retry_count() == 0 and err.error_count() == 0)
e0 = len(ec.writes)
ec.fail_next_writes(2)
err.set_contrast(0x66)
check_true('command_retried_until_acknowledged', len(ec.writes) - e0 == 3 and ec.writes[e0] == ec.writes[-1] == bytes((0x81, 0x66)))
check_true('retries_counted_and_ok_after_recovery', err.retry_count() == 2 and err.ok())
ec.fail_next_writes(100)
e0 = len(ec.writes)
try:
    err.set_invert(True)
    check_true('command_raises_after_max_attempts', False)
except OSError:
    check_true('command_raises_after_max_attempts', True)
check_true('command_gives_up_after_max_attempts', len(ec.writes) - e0 == UC1611sMinimal.MAX_ATTEMPTS)
check_true('unrecovered_error_counted', not err.ok() and err.error_count() == 1 and err.retry_count() == 2 + UC1611sMinimal.MAX_ATTEMPTS)
ec.fail_next_writes(0)
err.clear_errors()
check_true('clear_errors', err.ok() and err.retry_count() == 0)

e0, ed0 = len(ec.writes), len(ed.writes)
ed.fail_next_writes(1)
err.show_page(1)
check_true('page_retry_resends_address', len(ec.writes) - e0 == 2 and ec.writes[e0] == ec.writes[e0 + 1] == bytes((0x61, 0x70, 0x00, 0x10)))
check_true('page_retry_rewrites_all_chunks', len(ed.writes) - ed0 == 1 + 8 and err.retry_count() == 1 and err.ok())
e0, ed0 = len(ec.writes), len(ed.writes)
ed.fail_next_writes(1, 2)
err.show_page(2)
check_true('page_retry_after_partial_write', len(ec.writes) - e0 == 2 and len(ed.writes) - ed0 == 3 + 8)

dc, dd = mock_pair(False)
dc.fail_next_writes(1000)
try:
    UC1611sMinimal(dc, dd)
    check_true('dead_chip_raises_at_init', False)
except OSError:
    check_true('dead_chip_raises_at_init', len(dc.writes) == UC1611sMinimal.MAX_ATTEMPTS and not dd.writes)

qc, qd = mock_pair()
rq = UC1611sFull(qc, qd)
qc.fail_next_reads(2)
qc.queue_read(bytes((0xDA, 0x80, 0x10)))
check_true('read_status_retried', rq.read_status() == bytes((0xDA, 0x80, 0x10)) and rq.retry_count() >= 2)
qc.fail_next_reads(100)
try:
    rq.read_status()
    check_true('read_status_raises_after_max_attempts', False)
except OSError:
    check_true('read_status_raises_after_max_attempts', not rq.ok())
rq.clear_errors()
qc.fail_next_reads(0)
qd.fail_next_reads(1)
qd.queue_read(bytes((0xEE, 0x0A, 0x0B)))
q0 = len(qc.writes)
check_true('read_ram_retry_resets_address', rq.read_ram(1, 4, 2) == bytes((0x0A, 0x0B)) and len(qc.writes) - q0 == 2)
rq.clear_errors()
qc.queue_read(bytes((0xD2, 0x80, 0x10)))
qc.queue_read(bytes((0xDA, 0x80, 0x10)))
rq.wake()
check_true('wake_polls_display_enable', rq.ok() and qc.writes[-3] == b'\xA9' and qc.writes[-1] == b'\xE3')

print('===DONE: {} passed, {} failed==='.format(passed, failed))
