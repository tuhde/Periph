"""Unit test for the SH1106 driver — mocked I²C and display connections, no hardware."""

from periph.chips.display.sh1106 import SH1106Full, SH1106Minimal, SH1106SpiFull, SH1106SpiMinimal
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


# ---- Minimal: initialization (I²C) ------------------------------------------------
c = I2CConnectionMock()
lcd = SH1106Minimal(c)
check_true('geometry_128x64', lcd.width == 128 and lcd.height == 64)
SETUP = bytes((0x00, 0xAE, 0xD5, 0x50, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0xA0, 0xC0, 0xDA, 0x12, 0x81, 0x80,
               0xD9, 0x22, 0xDB, 0x35, 0x30, 0xA4, 0xA6))
check_true('init_setup_stream', c.writes[0] == SETUP)
check_true('init_dcdc_on', c.writes[1] == bytes((0x00, 0xAD, 0x8B)))
check_true('init_clear_page0_address', c.writes[2] == bytes((0x00, 0xB0, 0x00, 0x10)))
check_true('init_clear_data_chunks', all(w[0] == 0x40 and not any(w[1:]) for w in c.writes[3:8]))
check_true('init_clear_chunk_sizes_31x4_8', [len(w) - 1 for w in c.writes[3:8]] == [31, 31, 31, 31, 8])
check_true('init_clears_8_pages', len(c.writes) == 2 + 8 * 6 + 1)
check_true('init_display_on_last', c.writes[-1] == bytes((0x00, 0xAF)))
check_true('init_page7_address', c.writes[2 + 7 * 6] == bytes((0x00, 0xB7, 0x00, 0x10)))

# ---- Minimal: pixels and show -----------------------------------------------------
n0 = len(c.writes)
lcd.set_pixel(3, 10)
lcd.set_pixel(127, 63)
lcd.set_pixel(128, 0)       # out of range, ignored
lcd.set_pixel(-1, 5)
check_true('set_pixel_framebuffer_only', len(c.writes) == n0)
lcd.show()
check_true('show_8_pages_6_writes', len(c.writes) - n0 == 48)
check_true('show_page1_address_with_offset_2', c.writes[n0 + 6] == bytes((0x00, 0xB1, 0x02, 0x10)))
check_true('show_pixel_page1_col3_bit2', c.writes[n0 + 6 + 1][1 + 3] == 0x04)
check_true('show_pixel_page7_col127_bit7', c.writes[n0 + 42 + 5][-1] == 0x80)
lcd.set_pixel(3, 10, False)
lcd.clear()
n0 = len(c.writes)
lcd.show()
check_true('clear_zeroes_framebuffer', not any(any(w[1:]) for w in c.writes[n0:] if w[0] == 0x40))

# ---- Minimal: options and validation ----------------------------------------------
o = I2CConnectionMock()
SH1106Minimal(o, 128, 32, 0, False)
check_true('external_dcdc_stream', o.writes[1] == bytes((0x00, 0xAD, 0x8A)))
check_true('custom_height_multiplex', o.writes[0][4] == 0xA8 and o.writes[0][5] == 0x1F)
check_true('height_32_clears_all_8_ram_pages', len(o.writes) == 2 + 8 * 6 + 1)
o = I2CConnectionMock()
lcd2 = SH1106Minimal(o, 100, 64, 16)
n0 = len(o.writes)
lcd2.show()
check_true('column_offset_16_address', o.writes[n0] == bytes((0x00, 0xB0, 0x00, 0x11)))
for label, args in (('invalid_width', (133, 64, 0)), ('invalid_height_multiple', (128, 60, 0)),
                    ('invalid_height_max', (128, 72, 0)), ('invalid_offset', (128, 64, 5))):
    try:
        SH1106Minimal(I2CConnectionMock(), *args)
        check_true(label, False)
    except ValueError:
        check_true(label, True)

# ---- Full -------------------------------------------------------------------------
fc = I2CConnectionMock()
full = SH1106Full(fc)


def last(conn):
    return conn.writes[-1]


full.set_contrast(0x55)
check_true('set_contrast', last(fc) == bytes((0x00, 0x81, 0x55)))
full.set_display(False)
check_true('display_off', last(fc) == bytes((0x00, 0xAE)))
full.set_display(True)
check_true('display_on', last(fc) == bytes((0x00, 0xAF)))
full.sleep()
check_true('sleep', last(fc) == bytes((0x00, 0xAE)))
full.wake()
check_true('wake', last(fc) == bytes((0x00, 0xAF)))
full.set_invert(True)
check_true('invert_on', last(fc) == bytes((0x00, 0xA7)))
full.set_invert(False)
check_true('invert_off', last(fc) == bytes((0x00, 0xA6)))
full.set_all_on(True)
check_true('all_on', last(fc) == bytes((0x00, 0xA5)))
full.set_all_on(False)
check_true('all_on_off', last(fc) == bytes((0x00, 0xA4)))
full.set_flip(True, True)
check_true('flip_both', last(fc) == bytes((0x00, 0xA1, 0xC8)))
full.set_flip(False, True)
check_true('flip_vertical_only', last(fc) == bytes((0x00, 0xA0, 0xC8)))
full.set_flip(False, False)
check_true('flip_none', last(fc) == bytes((0x00, 0xA0, 0xC0)))
full.set_start_line(40)
check_true('start_line_40', last(fc) == bytes((0x00, 0x68)))
full.set_display_offset(12)
check_true('display_offset', last(fc) == bytes((0x00, 0xD3, 12)))
full.set_multiplex(48)
check_true('multiplex_48', last(fc) == bytes((0x00, 0xA8, 47)))
full.set_clock(3, 8)
check_true('clock', last(fc) == bytes((0x00, 0xD5, 0x82)))
full.set_precharge(2, 5)
check_true('precharge', last(fc) == bytes((0x00, 0xD9, 0x52)))
full.set_com_config(False)
check_true('com_sequential', last(fc) == bytes((0x00, 0xDA, 0x02)))
full.set_com_config(True)
check_true('com_alternative', last(fc) == bytes((0x00, 0xDA, 0x12)))
full.set_vcom_deselect(0x40)
check_true('vcom', last(fc) == bytes((0x00, 0xDB, 0x40)))
full.set_pump_voltage(3)
check_true('pump_9v', last(fc) == bytes((0x00, 0x33)))
full.set_dcdc(False)
check_true('dcdc_off', last(fc) == bytes((0x00, 0xAD, 0x8A)))
full.set_dcdc(True)
check_true('dcdc_on', last(fc) == bytes((0x00, 0xAD, 0x8B)))
full.send_command(0xE3, 0xE3)
check_true('send_command_raw', last(fc) == bytes((0x00, 0xE3, 0xE3)))

n0 = len(fc.writes)
full.show_page(2)
check_true('show_page_2', fc.writes[n0] == bytes((0x00, 0xB2, 0x02, 0x10)) and len(fc.writes) - n0 == 6)
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
try:
    full.blit(4, 3, bytes((1, 2)))
    check_true('blit_unaligned_y_raises', False)
except ValueError:
    check_true('blit_unaligned_y_raises', True)

# ---- reads (I²C) ------------------------------------------------------------------
rc = I2CConnectionMock()
chip = SH1106Full(rc)
rc.queue_read(bytes((0x40,)))
check_true('read_status_value', chip.read_status() == 0x40)
check_true('read_status_writes_command_control_byte', rc.writes[-1] == b'\x00')
rc.queue_read(bytes((0xEE, 0x11, 0x22, 0x33)))
k = len(rc.writes)
check_true('read_page_discards_dummy', chip.read_page(2, 5, 3) == bytes((0x11, 0x22, 0x33)))
check_true('read_page_sets_address', rc.writes[k] == bytes((0x00, 0xB2, 0x05, 0x10)))
check_true('read_page_data_control_byte', rc.writes[k + 1] == b'\x40')

# ---- SPI --------------------------------------------------------------------------
sm = DisplayConnectionMock()
spi = SH1106SpiMinimal(sm)
cmds = [e[1] for e in sm.events if e[0] == 'command']
check_true('spi_reset_first', sm.events[0] == ('reset',))
check_true('spi_setup_stream_bytes', bytes(cmds[:21]) == SETUP[1:])
check_true('spi_dcdc_on', bytes(cmds[21:23]) == b'\xAD\x8B')
check_true('spi_display_on_last_command', cmds[-1] == 0xAF)
datas = [e[1] for e in sm.events if e[0] == 'data']
check_true('spi_init_clear_8_pages_132_bytes', len(datas) == 8 and all(len(d) == 132 and not any(d) for d in datas))
n0 = len(sm.events)
spi.set_pixel(5, 0)
spi.show()
shown = sm.events[n0:]
check_true('spi_show_page0_address', [e[1] for e in shown[:3]] == [0xB0, 0x02, 0x10])
check_true('spi_show_page_data_128_bytes', shown[3][0] == 'data' and len(shown[3][1]) == 128 and shown[3][1][5] == 1)
sf = SH1106SpiFull(DisplayConnectionMock())
for name, call in (('spi_read_status_raises', lambda: sf.read_status()), ('spi_read_page_raises', lambda: sf.read_page(0, 0, 1))):
    try:
        call()
        check_true(name, False)
    except NotImplementedError:
        check_true(name, True)
sm2 = DisplayConnectionMock()
sf2 = SH1106SpiFull(sm2)
sf2.set_contrast(0x20)
check_true('spi_full_contrast', [e[1] for e in sm2.events[-2:]] == [0x81, 0x20])

print('===DONE: {} passed, {} failed==='.format(passed, failed))
