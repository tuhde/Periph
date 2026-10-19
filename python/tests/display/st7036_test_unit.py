"""Unit test for the ST7036 driver — mocked display connection, no hardware."""

from periph.chips.display.st7036 import ST7036Full, ST7036Minimal
from periph.connection.display_mock import DisplayConnectionMock

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


def trace(conn, start=0):
    """R reset, I<hh> instruction, D<hh> data byte, d<ms> delay, u<us> microsecond delay."""
    out = []
    for e in conn.events[start:]:
        if e[0] == 'reset':
            out.append('R')
        elif e[0] == 'command':
            out.append('I%02X' % e[1])
        elif e[0] == 'data':
            out.append('D%02X' % e[1][0] + ('!' if len(e[1]) != 1 else ''))
        elif e[0] == 'delay':
            out.append('d%d' % e[1])
        elif e[0] == 'delay_us':
            out.append('u%d' % e[1])
        else:
            out.append('?')
    return ' '.join(out)


def mark(conn):
    return len(conn.events)


# ---- Minimal: DOGM162 3.3 V sequence ----------------------------------------
conn = DisplayConnectionMock()
lcd = ST7036Minimal(conn)
check_true('init_sequence_dogm162_3v3', trace(conn) ==
           'R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30')
check_true('instructions_are_single_bytes', all(e[2] == b'' for e in conn.events if e[0] == 'command'))
check_true('geometry_16x2', lcd.columns == 16 and lcd.rows == 2 and
           ST7036Minimal.COLUMNS == 16 and ST7036Minimal.ROWS == 2)

conn = DisplayConnectionMock()
lcd = ST7036Minimal(conn)
n = mark(conn); lcd.clear()
check_true('clear_waits_2ms', trace(conn, n) == 'I01 d2')
n = mark(conn); lcd.home()
check_true('home_waits_2ms', trace(conn, n) == 'I02 d2')
n = mark(conn); lcd.set_cursor(3, 1)
check_true('set_cursor_row1_col3', trace(conn, n) == 'IC3 u30')
n = mark(conn); lcd.set_cursor(0, 0)
check_true('set_cursor_origin', trace(conn, n) == 'I80 u30')
n = mark(conn); lcd.set_cursor(16, 0); lcd.set_cursor(0, 2); lcd.set_cursor(-1, 0)
check_true('set_cursor_out_of_range_ignored', mark(conn) == n)
n = mark(conn); lcd.write('Hi')
check_true('write_one_byte_per_transfer_with_gap', trace(conn, n) == 'D48 u30 D69 u30')
n = mark(conn); lcd.write('aäz~')
check_true('write_non_ascii_becomes_question_mark', trace(conn, n) == 'D61 u30 D3F u30 D7A u30 D3F u30')
n = mark(conn); lcd.write(b'AB')
check_true('write_accepts_bytes', trace(conn, n) == 'D41 u30 D42 u30')
n = mark(conn); lcd.write_char(0x05)
check_true('write_char_raw', trace(conn, n) == 'D05 u30')
n = mark(conn); lcd.print_at(2, 1, 'x')
check_true('print_at', trace(conn, n) == 'IC2 u30 D78 u30')

# ---- Full: presets ------------------------------------------------------------
cases = [
    (1, ST7036Full.SUPPLY_3V3, 'R d40 I31 u30 I14 u30 I7C u30 I55 u30 I6D u30 d200 I30 u30 I0C u30 I01 d2 I06 u30', 'dogm081_3v3'),
    (1, ST7036Full.SUPPLY_5V, 'R d40 I31 u30 I1C u30 I74 u30 I51 u30 I6A u30 d200 I30 u30 I0C u30 I01 d2 I06 u30', 'dogm081_5v'),
    (2, ST7036Full.SUPPLY_3V3, 'R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30', 'dogm162_3v3'),
    (2, ST7036Full.SUPPLY_5V, 'R d40 I39 u30 I1C u30 I74 u30 I52 u30 I69 u30 d200 I38 u30 I0C u30 I01 d2 I06 u30', 'dogm162_5v'),
    (3, ST7036Full.SUPPLY_3V3, 'R d40 I39 u30 I15 u30 I72 u30 I55 u30 I6E u30 d200 I38 u30 I0C u30 I01 d2 I06 u30', 'dogm163_3v3'),
    (3, ST7036Full.SUPPLY_5V, 'R d40 I39 u30 I1D u30 I7C u30 I50 u30 I6C u30 d200 I38 u30 I0C u30 I01 d2 I06 u30', 'dogm163_5v'),
]
for lines, supply, want, label in cases:
    conn = DisplayConnectionMock()
    ST7036Full(conn, lines, supply=supply)
    check_true(label, trace(conn) == want)

conn = DisplayConnectionMock()
lcd = ST7036Full(conn, 3)
check_true('dogm163_geometry', lcd.columns == 16 and lcd.rows == 3)
n = mark(conn); lcd.set_cursor(0, 2)
check_true('dogm163_row2_address_0x20', trace(conn, n) == 'IA0 u30')
conn = DisplayConnectionMock()
one = ST7036Full(conn, 1)
check_true('dogm081_geometry', one.columns == 8 and one.rows == 1)
conn = DisplayConnectionMock()
ST7036Full(conn, 2, supply=ST7036Full.SUPPLY_3V3, contrast=0x2A)
check_true('contrast_override_0x2a', trace(conn) ==
           'R d40 I39 u30 I14 u30 I7A u30 I56 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30')
conn = DisplayConnectionMock()
wide = ST7036Full(conn, 2, columns=20)
check_true('columns_override', wide.columns == 20)
try:
    ST7036Full(DisplayConnectionMock(), 9)
    check_true('invalid_lines_raises', False)
except ValueError:
    check_true('invalid_lines_raises', True)

# ---- Full: control instructions -----------------------------------------------
conn = DisplayConnectionMock()
lcd = ST7036Full(conn)
n = mark(conn); lcd.display(False)
check_true('display_off', trace(conn, n) == 'I08 u30')
n = mark(conn); lcd.display(True)
check_true('display_on', trace(conn, n) == 'I0C u30')
n = mark(conn); lcd.cursor(True, True)
check_true('cursor_and_blink', trace(conn, n) == 'I0E u30 I0F u30')
n = mark(conn); lcd.cursor(False, False)
check_true('cursor_off', trace(conn, n) == 'I0D u30 I0C u30')
n = mark(conn); lcd.set_entry_mode(False, True)
check_true('entry_mode_decrement_shift', trace(conn, n) == 'I05 u30')
n = mark(conn); lcd.set_entry_mode(True, False)
check_true('entry_mode_increment', trace(conn, n) == 'I06 u30')
n = mark(conn); lcd.shift_cursor(True); lcd.shift_cursor(False)
check_true('shift_cursor', trace(conn, n) == 'I14 u30 I10 u30')
n = mark(conn); lcd.shift_display(True); lcd.shift_display(False)
check_true('shift_display', trace(conn, n) == 'I1C u30 I18 u30')
n = mark(conn); lcd.return_home()
check_true('return_home', trace(conn, n) == 'I02 d2')
n = mark(conn); lcd.set_ddram_address(0x45)
check_true('set_ddram_address', trace(conn, n) == 'IC5 u30')
n = mark(conn); lcd.send_command(0x1F); lcd.send_data(0x41)
check_true('raw_command_and_data', trace(conn, n) == 'I1F u30 D41 u30')

# ---- Full: extension table instructions ---------------------------------------
conn = DisplayConnectionMock()
lcd = ST7036Full(conn)
n = mark(conn); lcd.set_contrast(0x2D)
check_true('set_contrast_0x2d', trace(conn, n) == 'I39 u30 I56 u30 I7D u30 I38 u30')
n = mark(conn); lcd.set_booster(False)
check_true('booster_off_keeps_contrast_high_bits', trace(conn, n) == 'I39 u30 I52 u30 I38 u30')
n = mark(conn); lcd.set_booster(True)
check_true('booster_on', trace(conn, n) == 'I39 u30 I56 u30 I38 u30')
n = mark(conn); lcd.set_follower(True, 3)
check_true('follower_on_ratio_3', trace(conn, n) == 'I39 u30 I6B u30 I38 u30')
n = mark(conn); lcd.set_follower(False, 7)
check_true('follower_off_ratio_7', trace(conn, n) == 'I39 u30 I67 u30 I38 u30')
n = mark(conn); lcd.set_bias(True)
check_true('bias_quarter', trace(conn, n) == 'I39 u30 I1C u30 I38 u30')
n = mark(conn); lcd.set_bias(False)
check_true('bias_fifth', trace(conn, n) == 'I39 u30 I14 u30 I38 u30')
n = mark(conn); lcd.icon(True)
check_true('icon_on', trace(conn, n) == 'I39 u30 I5E u30 I38 u30')
n = mark(conn); lcd.write_icon(3, 0x15)
check_true('write_icon_restores_ddram', trace(conn, n) == 'I39 u30 I43 u30 D15 u30 I38 u30 I80 u30')
conn3 = DisplayConnectionMock()
three = ST7036Full(conn3, 3)
n = mark(conn3); three.set_bias(False)
check_true('bias_keeps_fx_on_3_line_module', trace(conn3, n) == 'I39 u30 I15 u30 I38 u30')

# ---- Full: custom characters and double height --------------------------------
conn = DisplayConnectionMock()
lcd = ST7036Full(conn)
lcd.set_cursor(5, 1)
n = mark(conn)
glyph = [0x1F, 0x00, 0xFF, 0x01, 0x02, 0x04, 0x08, 0x10]
lcd.create_char(2, glyph)
check_true('create_char_masks_rows_and_restores_address', trace(conn, n) ==
           'I50 u30 D1F u30 D00 u30 D1F u30 D01 u30 D02 u30 D04 u30 D08 u30 D10 u30 IC5 u30')
n = mark(conn); lcd.create_char(9, glyph)
check_true('create_char_index_masked_to_0_7', trace(conn, n).startswith('I48 u30'))
n = mark(conn); lcd.set_double_height(True)
check_true('double_height_ignored_on_2_line', mark(conn) == n)
conn1 = DisplayConnectionMock()
one = ST7036Full(conn1, 1)
n = mark(conn1); one.set_double_height(True)
check_true('double_height_1_line', trace(conn1, n) == 'I34 u30 I34 u30')
n = mark(conn1); one.set_double_height(False)
check_true('double_height_off_1_line', trace(conn1, n) == 'I30 u30 I30 u30')
conn3 = DisplayConnectionMock()
three = ST7036Full(conn3, 3)
n = mark(conn3); three.set_double_height(True, True)
check_true('double_height_3_line_upper', trace(conn3, n) == 'I3E u30 I18 u30 I3C u30')
n = mark(conn3); three.set_double_height(True, False)
check_true('double_height_3_line_lower', trace(conn3, n) == 'I3E u30 I10 u30 I3C u30')

print('%d passed, %d failed' % (passed, failed))
raise SystemExit(1 if failed else 0)
