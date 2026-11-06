import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.display.pcf8579 import PCF8579Minimal, PCF8579Full

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x3C'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

passed = 0
failed = 0


def check(label, condition):
    global passed, failed
    if condition:
        print('PASS ' + label)
        passed += 1
    else:
        print('FAIL ' + label)
        failed += 1


# Minimal: construct (clears RAM, display on), pixels, columns.
lcd = PCF8579Minimal(connection, 0, 16)
try:
    lcd.clear()
    lcd.set_pixel(0, 0)
    lcd.set_pixel(39, 15)
    lcd.set_pixel(45, 30)  # out of range, ignored
    lcd.write_columns(2, 1, bytes([0xFF, 0x81, 0xFF]))
    check('minimal_draw', True)
except Exception as e:
    print('FAIL minimal_draw: {}'.format(e))
    failed += 1

# Full: exercise every method; read_ram verifies the bytes really landed.
full = PCF8579Full(connection, 0, 16)
try:
    full.write_ram(16, 0, bytes([0xAA, 0x55, 0x0F]))
    got = full.read_ram(16, 0, 3)
    check('ram_roundtrip', bytes(got) == bytes([0xAA, 0x55, 0x0F]))
except Exception as e:
    print('FAIL ram_roundtrip: {}'.format(e))
    failed += 1

try:
    full.fill(True)
    full.fill(False)
    full.draw_line(0, 0, 39, 15)
    full.draw_rect(0, 0, 40, 16)
    full.draw_rect(4, 4, 8, 8, True, True)
    full.show_bitmap(0, 0, bytes([0x18, 0x24, 0x42, 0x81]))
    check('graphics', full.get_pixel(0, 0))
except Exception as e:
    print('FAIL graphics: {}'.format(e))
    failed += 1

try:
    full.set_display(PCF8579Full.DISPLAY_INVERSE)
    full.set_display(PCF8579Full.DISPLAY_ALL_ON)
    full.set_display(PCF8579Full.DISPLAY_NORMAL)
    full.set_start_bank(1)
    full.scroll(1)
    full.scroll(-2)
    full.write_ram(16, 0, bytes(8), PCF8579Full.ACCESS_HALF_GRAPHIC)
    full.write_ram(16, 0, bytes(8), PCF8579Full.ACCESS_FULL_GRAPHIC)
    full.set_mode(24, True)
    full.set_mode(16, False)
    full.write_ram_cascade(bytes(40 * 4))
    full.reset()
    check('configuration', True)
except Exception as e:
    print('FAIL configuration: {}'.format(e))
    failed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))
