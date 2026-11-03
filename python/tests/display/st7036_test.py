import _testconfig as cfg
from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.st7036 import ST7036Full

spi = SPI(getattr(cfg, 'SPI_ID', 0), baudrate=1000000, polarity=0, phase=0)
cs = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_CS', 17))
dc = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_DC', 20))
res = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_RES', 21))
connection = DisplayConnection(spi, cs, dc, res, None)
chip = ST7036Full(connection)

passed = 0
failed = 0


def check(label, ok):
    global passed, failed
    if ok:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def run(chip):
    """Smoke checks; the picture is verified by eye (see the demo example)."""
    check('geometry_16x2', chip.columns == 16 and chip.rows == 2)
    try:
        chip.clear()
        chip.print_at(0, 0, 'Periph ST7036')
        chip.print_at(0, 1, 'smoke test')
        check('text_accepted', True)
        chip.create_char(0, [0x1F, 0x11, 0x15, 0x11, 0x1F, 0x00, 0x1F, 0x00])
        chip.set_cursor(15, 1)
        chip.write_char(0)
        chip.cursor(True, True)
        chip.cursor(False, False)
        chip.set_entry_mode(True, False)
        chip.shift_display(True)
        chip.shift_display(False)
        chip.shift_cursor(True)
        chip.return_home()
        chip.set_ddram_address(0x40)
        chip.set_contrast(40)
        chip.set_booster(True)
        chip.set_follower(True, 5)
        chip.set_bias(False)
        chip.set_contrast(24)
        chip.display(False)
        chip.display(True)
        chip.home()
        check('commands_accepted', True)
    except Exception as e:  # noqa: BLE001
        print('command failed:', e)
        check('commands_accepted', False)
    print('===DONE: %d passed, %d failed===' % (passed, failed))


run(chip)
