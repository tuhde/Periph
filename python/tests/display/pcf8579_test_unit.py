import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.display.pcf8579 import PCF8579Minimal, PCF8579Full

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


def raises(fn, exc):
    try:
        fn()
    except exc:
        return True
    return False


connection = I2CConnectionMock()
lcd = PCF8579Full(connection)

# init (row mode, 1:16, subaddress 0): clear-RAM transaction, then display on (normal).
check_true('init_clear_write', connection.writes[0] == bytes([0xE0, 0xC2, 0xFC, 0xF8, 0x00]) + bytes(160))
check_true('init_display_on', connection.writes[1] == bytes([0x46]))

# subaddress + mixed mode (T = 1)
c = I2CConnectionMock()
PCF8579Minimal(c, 3, 16, True)
check_true('init_subaddress_mixed', c.writes[0] == bytes([0xE3, 0xD2, 0xFC, 0xF8, 0x00]) + bytes(160))
check_true('init_mixed_on', c.writes[1] == bytes([0x56]))

# other row counts: set-mode M bits 8->01, 24->11, 32->00
for rows, byte in ((8, 0x45), (24, 0x47), (32, 0x44)):
    c = I2CConnectionMock()
    PCF8579Minimal(c, 0, rows)
    check_true('init_on_rows_%d' % rows, c.writes[1] == bytes([byte]))
check_true('rows_invalid_raises', raises(lambda: PCF8579Minimal(I2CConnectionMock(), 0, 12), ValueError))
check_true('subaddress_invalid_raises', raises(lambda: PCF8579Minimal(I2CConnectionMock(), 16), ValueError))

# clear()
lcd.clear()
check_true('clear', connection.writes[-1] == bytes([0xE0, 0xF8, 0x00]) + bytes(160))

# set_pixel(): (0,0) -> RAM X=0, bank 0, bit 0.
lcd.set_pixel(0, 0)
check_true('set_pixel_write', connection.writes[-1] == bytes([0xE0, 0xF0, 0, 0x01]))
lcd.set_pixel(3, 9)  # X=3, bank 1, bit 1
check_true('set_pixel_bank1', connection.writes[-1] == bytes([0xE0, 0xF1, 3, 0x02]))
lcd.set_pixel(3, 10)  # same byte, bit 2 -> 0x06 via shadow
check_true('set_pixel_shadow_or', connection.writes[-1] == bytes([0xE0, 0xF1, 3, 0x06]))
lcd.set_pixel(3, 9, False)
check_true('set_pixel_clear', connection.writes[-1] == bytes([0xE0, 0xF1, 3, 0x04]))
lcd.set_pixel(39, 15)
check_true('set_pixel_last_column', connection.writes[-1] == bytes([0xE0, 0xF1, 39, 0x80]))
n = len(connection.writes)
lcd.set_pixel(40, 0)
lcd.set_pixel(0, 16)
lcd.set_pixel(-1, 0)
check_true('set_pixel_out_of_range_ignored', len(connection.writes) == n)

# get_pixel() from shadow, no bus traffic
check_true('get_pixel_set', lcd.get_pixel(0, 0) is True)
check_true('get_pixel_clear', lcd.get_pixel(3, 9) is False)
check_true('get_pixel_no_traffic', len(connection.writes) == n)

# write_columns(): truncated at column 39
lcd.write_columns(38, 0, bytes([1, 2, 3, 4]))
check_true('write_columns_truncated', connection.writes[-1] == bytes([0xE0, 0xF0, 38, 1, 2]))
check_true('write_columns_bad_bank_raises', raises(lambda: lcd.write_columns(0, 2, b'\x01'), ValueError))

# fill() writes every column of each bank in use
lcd.fill(True)
check_true('fill_bank0', connection.writes[-2] == bytes([0xE0, 0xF0, 0]) + bytes([0xFF] * 40))
check_true('fill_bank1', connection.writes[-1] == bytes([0xE0, 0xF1, 0]) + bytes([0xFF] * 40))
lcd.fill(False)
check_true('fill_off_shadow', lcd.get_pixel(5, 5) is False)

# draw_line(): horizontal line on row 0, columns 0..3 -> one batched write
lcd.clear()
n = len(connection.writes)
lcd.draw_line(0, 0, 3, 0)
check_true('draw_line_one_write', len(connection.writes) == n + 1)
check_true('draw_line_bytes', connection.writes[-1] == bytes([0xE0, 0xF0, 0, 1, 1, 1, 1]))

# draw_rect(): 3x3 outline => centre clear
lcd.clear()
lcd.draw_rect(0, 0, 3, 3)
check_true('draw_rect_outline', lcd.get_pixel(0, 0) and lcd.get_pixel(2, 2) and not lcd.get_pixel(1, 1))
lcd.draw_rect(0, 0, 3, 3, True, True)
check_true('draw_rect_filled', lcd.get_pixel(1, 1))

# show_bitmap(): bank-aligned write
lcd.show_bitmap(2, 8, bytes([0xAA, 0xBB]))
check_true('show_bitmap', connection.writes[-1] == bytes([0xE0, 0xF1, 2, 0xAA, 0xBB]))
check_true('show_bitmap_unaligned_raises', raises(lambda: lcd.show_bitmap(0, 3, b'\x01'), ValueError))

# set_display(): set-mode C=0, T=0, E field
lcd.set_display(PCF8579Full.DISPLAY_BLANK)
check_true('set_display_blank', connection.writes[-1] == bytes([0x42]))
lcd.set_display(PCF8579Full.DISPLAY_INVERSE)
check_true('set_display_inverse', connection.writes[-1] == bytes([0x4E]))
lcd.set_display(PCF8579Full.DISPLAY_ALL_ON)
check_true('set_display_all_on', connection.writes[-1] == bytes([0x4A]))
lcd.set_display(PCF8579Full.DISPLAY_NORMAL)
check_true('set_display_normal', connection.writes[-1] == bytes([0x46]))

# set_start_bank() / scroll()
lcd.set_start_bank(1)
check_true('set_start_bank', connection.writes[-1] == bytes([0x7D]))
lcd.scroll(1)  # 2 banks in use at 1:16 -> wraps to 0
check_true('scroll_wraps', connection.writes[-1] == bytes([0x7C]))
lcd.scroll(-1)
check_true('scroll_negative', connection.writes[-1] == bytes([0x7D]))
check_true('start_bank_invalid_raises', raises(lambda: lcd.set_start_bank(4), ValueError))

# write_ram(): modes
lcd.write_ram(10, 0, bytes([1, 2]))
check_true('write_ram_character', connection.writes[-1] == bytes([0xE0, 0xF0, 10, 1, 2]))
lcd.write_ram(10, 2, bytes([1, 2, 3, 4]), PCF8579Full.ACCESS_HALF_GRAPHIC)
check_true('write_ram_half', connection.writes[-1] == bytes([0xE0, 0xF6, 10, 1, 2, 3, 4]))
lcd.write_ram(10, 0, bytes(range(1, 9)), PCF8579Full.ACCESS_FULL_GRAPHIC)
check_true('write_ram_full', connection.writes[-1] == bytes([0xE0, 0xF8, 10]) + bytes(range(1, 9)))
check_true('write_ram_shadow_order', lcd._shadow[3][10] == 4 and lcd._shadow[0][11] == 5)
check_true('write_ram_past_39_raises', raises(lambda: lcd.write_ram(39, 0, bytes(2)), ValueError))
check_true('write_ram_bad_bank_raises', raises(lambda: lcd.write_ram(0, 4, b'\x01'), ValueError))
check_true('write_ram_half_odd_bank_raises', raises(lambda: lcd.write_ram(0, 1, b'\x01', 1), ValueError))

# write_ram_cascade(): runs past column 39, shadow only tracks this device
lcd.write_ram_cascade(bytes([7] * 200))
check_true('cascade_write', connection.writes[-1] == bytes([0xE0, 0xF8, 0]) + bytes([7] * 200))
check_true('cascade_shadow', lcd._shadow[0][39] == 7)

# read_ram(): address-set commands then a repeated-start read
data = lcd.read_ram(16, 0, 4)
check_true('read_ram_commands', connection.writes[-1] == bytes([0xE0, 0xF0, 16]))
check_true('read_ram_length', len(data) == 4)
lcd.read_ram(5, 2, 3, PCF8579Full.ACCESS_HALF_GRAPHIC)
check_true('read_ram_half_commands', connection.writes[-1] == bytes([0xE0, 0xF6, 5]))

# set_mode(): rows and T bit
lcd.set_mode(24, True)
check_true('set_mode_24_mixed', connection.writes[-1] == bytes([0x57]))
lcd.set_mode(16, False)
check_true('set_mode_row', connection.writes[-1] == bytes([0x46]))
check_true('set_mode_invalid_raises', raises(lambda: lcd.set_mode(12), ValueError))

# subaddress is re-sent before every RAM access
sub = PCF8579Full(I2CConnectionMock(), 5)
sub.set_pixel(0, 0)
check_true('pixel_uses_subaddress', sub._connection.writes[-1][0] == 0xE5)

# reset(): re-runs init
lcd.reset()
check_true('reset_clear', connection.writes[-2] == bytes([0xE0, 0xC2, 0xFC, 0xF8, 0x00]) + bytes(160))
check_true('reset_on', connection.writes[-1] == bytes([0x46]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
