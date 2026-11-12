"""Unit test for the SSD1306 driver — mocked I²C and display connections, no hardware."""

from periph.chips.display.ssd1306 import SSD1306Full, SSD1306Minimal, SSD1306SpiFull, SSD1306SpiMinimal
from periph.connection.display_mock import DisplayConnectionMock
from periph.connection.i2c_mock import I2CConnectionMock

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def raises(exc, fn):
    try:
        fn()
    except exc:
        return True
    return False


# ---- Minimal: initialization (I²C) ------------------------------------------------
c = I2CConnectionMock()
lcd = SSD1306Minimal(c)
check_true('geometry_128x64', lcd.width == 128 and lcd.height == 64)
SETUP = bytes((0x00, 0xAE, 0xD5, 0x80, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0x8D, 0x14, 0x20, 0x02, 0xA0, 0xC0,
               0xDA, 0x12, 0x81, 0x7F, 0xD9, 0x22, 0xDB, 0x20, 0xA4, 0xA6))
check_true('init_setup_stream_charge_pump_on', c.writes[0] == SETUP)
check_true('init_clear_page0_address', c.writes[1] == bytes((0x00, 0xB0, 0x00, 0x10)))
check_true('init_clear_chunk_sizes_31x4_4', [len(w) - 1 for w in c.writes[2:7]] == [31, 31, 31, 31, 4])
check_true('init_clear_data_zero', all(w[0] == 0x40 and not any(w[1:]) for w in c.writes[2:7]))
check_true('init_clears_8_pages', len(c.writes) == 1 + 8 * 6 + 1)
check_true('init_page7_address', c.writes[1 + 7 * 6] == bytes((0x00, 0xB7, 0x00, 0x10)))
check_true('init_display_on_last', c.writes[-1] == bytes((0x00, 0xAF)))

# ---- Minimal: pixels and show -----------------------------------------------------
n0 = len(c.writes)
lcd.set_pixel(3, 10)
lcd.set_pixel(127, 63)
lcd.set_pixel(128, 0)       # out of range, ignored
lcd.set_pixel(-1, 5)
check_true('set_pixel_framebuffer_only', len(c.writes) == n0)
lcd.show()
check_true('show_8_pages_6_writes', len(c.writes) - n0 == 48)
check_true('show_page1_address_column0', c.writes[n0 + 6] == bytes((0x00, 0xB1, 0x00, 0x10)))
check_true('show_pixel_page1_col3_bit2', c.writes[n0 + 6 + 1][1 + 3] == 0x04)
check_true('show_pixel_page7_col127_bit7', c.writes[n0 + 42 + 5][-1] == 0x80)
lcd.set_pixel(3, 10, False)
lcd.clear()
n0 = len(c.writes)
lcd.show()
check_true('clear_zeroes_framebuffer', not any(any(w[1:]) for w in c.writes[n0:] if w[0] == 0x40))

# ---- Minimal: options and validation ----------------------------------------------
o = I2CConnectionMock()
SSD1306Minimal(o, 128, 32, True)
check_true('external_vcc_pump_off', o.writes[0][9:11] == bytes((0x8D, 0x10)))
check_true('height_32_sequential_com', o.writes[0][15] == 0xDA and o.writes[0][16] == 0x02)
check_true('custom_height_multiplex', o.writes[0][4] == 0xA8 and o.writes[0][5] == 0x1F)
o2 = I2CConnectionMock()
narrow = SSD1306Minimal(o2, 100, 64)
n0 = len(o2.writes)
narrow.show()
check_true('narrow_page_data_100_bytes', sum(len(w) - 1 for w in o2.writes[n0 + 1:n0 + 5]) == 100)
for label, args in (('invalid_width', (129, 64)), ('invalid_width_zero', (0, 64)), ('invalid_height_multiple', (128, 60)),
                    ('invalid_height_min', (128, 8)), ('invalid_height_max', (128, 72))):
    check_true(label, raises(ValueError, lambda: SSD1306Minimal(I2CConnectionMock(), *args)))

# ---- Full -------------------------------------------------------------------------
fc = I2CConnectionMock()
full = SSD1306Full(fc)


def last(conn):
    return conn.writes[-1]


def cmd(label, call, expected):
    call()
    check_true(label, last(fc) == bytes((0x00,) + expected))


cmd('set_contrast', lambda: full.set_contrast(0x55), (0x81, 0x55))
cmd('display_off', lambda: full.set_display(False), (0xAE,))
cmd('display_on', lambda: full.set_display(True), (0xAF,))
cmd('sleep', full.sleep, (0xAE,))
cmd('wake', full.wake, (0xAF,))
cmd('invert_on', lambda: full.set_invert(True), (0xA7,))
cmd('invert_off', lambda: full.set_invert(False), (0xA6,))
cmd('all_on', lambda: full.set_all_on(True), (0xA5,))
cmd('all_on_off', lambda: full.set_all_on(False), (0xA4,))
cmd('flip_both', lambda: full.set_flip(True, True), (0xA1, 0xC8))
cmd('flip_vertical_only', lambda: full.set_flip(False, True), (0xA0, 0xC8))
cmd('flip_none', lambda: full.set_flip(False, False), (0xA0, 0xC0))
cmd('start_line_40', lambda: full.set_start_line(40), (0x68,))
cmd('display_offset', lambda: full.set_display_offset(12), (0xD3, 12))
cmd('multiplex_48', lambda: full.set_multiplex(48), (0xA8, 47))
cmd('clock', lambda: full.set_clock(3, 8), (0xD5, 0x82))
cmd('precharge', lambda: full.set_precharge(2, 5), (0xD9, 0x52))
cmd('com_sequential', lambda: full.set_com_config(False), (0xDA, 0x02))
cmd('com_alternative', lambda: full.set_com_config(True), (0xDA, 0x12))
cmd('com_alternative_remap', lambda: full.set_com_config(True, True), (0xDA, 0x32))
cmd('vcomh_masked', lambda: full.set_vcomh(0xFF), (0xDB, 0x70))
cmd('charge_pump_on', lambda: full.set_charge_pump(True), (0x8D, 0x14))
cmd('charge_pump_off', lambda: full.set_charge_pump(False), (0x8D, 0x10))
cmd('scroll_h_right', lambda: full.set_scroll_horizontal(True, 0, 7, 7), (0x26, 0x00, 0, 7, 7, 0x00, 0xFF))
cmd('scroll_h_left', lambda: full.set_scroll_horizontal(False, 1, 3, 4), (0x27, 0x00, 1, 4, 3, 0x00, 0xFF))
cmd('scroll_diagonal', lambda: full.set_scroll_vertical(True, 0, 7, 6, 5), (0x29, 0x00, 0, 6, 7, 5))
cmd('scroll_diagonal_left', lambda: full.set_scroll_vertical(False, 2, 4, 0, 1), (0x2A, 0x00, 2, 0, 4, 1))
cmd('scroll_area', lambda: full.set_scroll_area(8, 40), (0xA3, 8, 40))
cmd('send_command_raw', lambda: full.send_command(0xE3, 0xE3), (0xE3, 0xE3))

# ---- scrolling blocks RAM access --------------------------------------------------
cmd('scroll_start', full.scroll_start, (0x2F,))
n0 = len(fc.writes)
check_true('show_raises_while_scrolling', raises(RuntimeError, full.show))
check_true('show_page_raises_while_scrolling', raises(RuntimeError, lambda: full.show_page(0)))
check_true('no_bus_traffic_when_refused', len(fc.writes) == n0)
full.scroll_stop()
check_true('scroll_stop_deactivates_and_rewrites_ram', fc.writes[n0] == bytes((0x00, 0x2E)) and len(fc.writes) - n0 == 1 + 48)
n0 = len(fc.writes)
full.show()
check_true('show_works_after_scroll_stop', len(fc.writes) - n0 == 48)

# ---- addressing modes -------------------------------------------------------------
cmd('addr_mode_horizontal', lambda: full.set_addressing_mode('horizontal'), (0x20, 0x00))
n0 = len(fc.writes)
full.show()
check_true('show_horizontal_ranges', fc.writes[n0] == bytes((0x00, 0x21, 0, 127, 0x22, 0, 7)))
check_true('show_horizontal_data_stream', sum(len(w) - 1 for w in fc.writes[n0 + 1:]) == 128 * 8)
cmd('addr_mode_vertical', lambda: full.set_addressing_mode('vertical'), (0x20, 0x01))
full.set_pixel(0, 9)
n0 = len(fc.writes)
full.show()
check_true('show_vertical_column_major', fc.writes[n0 + 1][1] == 0x00 and fc.writes[n0 + 1][2] == 0x02)
full.set_pixel(0, 9, False)
n0 = len(fc.writes)
full.show_page(2)
check_true('show_page_vertical_range', fc.writes[n0] == bytes((0x00, 0x21, 0, 127, 0x22, 2, 2)))
cmd('addr_mode_page', lambda: full.set_addressing_mode('page'), (0x20, 0x02))
check_true('addr_mode_invalid_raises', raises(ValueError, lambda: full.set_addressing_mode('bogus')))
n0 = len(fc.writes)
full.show_page(2)
check_true('show_page_2', fc.writes[n0] == bytes((0x00, 0xB2, 0x00, 0x10)) and len(fc.writes) - n0 == 6)
full.show_page(8)
check_true('show_page_out_of_range_ignored', len(fc.writes) - n0 == 6)

# ---- framebuffer drawing ----------------------------------------------------------
full.fill(True)
check_true('fill_on', full.get_pixel(0, 0) and full.get_pixel(127, 63))
full.fill(False)
check_true('fill_off', not full.get_pixel(0, 0) and not full.get_pixel(127, 63))
check_true('get_pixel_out_of_range_false', not full.get_pixel(-1, 0) and not full.get_pixel(0, 64))
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
check_true('blit_unaligned_y_raises', raises(ValueError, lambda: full.blit(4, 3, bytes((1, 2)))))

# ---- SPI --------------------------------------------------------------------------
sm = DisplayConnectionMock()
spi = SSD1306SpiMinimal(sm)
cmds = [e[1] for e in sm.events if e[0] == 'command']
check_true('spi_reset_first', sm.events[0] == ('reset',))
check_true('spi_setup_stream_bytes', bytes(cmds[:24]) == SETUP[1:])
check_true('spi_display_on_last_command', cmds[-1] == 0xAF)
datas = [e[1] for e in sm.events if e[0] == 'data']
check_true('spi_init_clear_8_pages_128_bytes', len(datas) == 8 and all(len(d) == 128 and not any(d) for d in datas))
n0 = len(sm.events)
spi.set_pixel(5, 0)
spi.show()
shown = sm.events[n0:]
check_true('spi_show_page0_address', [e[1] for e in shown[:3]] == [0xB0, 0x00, 0x10])
check_true('spi_show_page_data_128_bytes', shown[3][0] == 'data' and len(shown[3][1]) == 128 and shown[3][1][5] == 1)
sm2 = DisplayConnectionMock()
sf2 = SSD1306SpiFull(sm2)
sf2.set_contrast(0x20)
check_true('spi_full_contrast', [e[1] for e in sm2.events[-2:]] == [0x81, 0x20])
sf2.scroll_start()
check_true('spi_show_raises_while_scrolling', raises(RuntimeError, sf2.show))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
