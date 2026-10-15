import _testconfig as cfg
from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.uc8159 import UC8159Full

spi = SPI(getattr(cfg, 'SPI_ID', 0), baudrate=4000000, polarity=0, phase=0)
cs = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_CS', 17))
dc = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_DC', 20))
res = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_RES', 21))
busy = MicroPythonPin(Pin(getattr(cfg, 'DISPLAY_BUSY', 22), Pin.IN))
connection = DisplayConnection(spi, cs, dc, res, busy)
chip = UC8159Full(connection)

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
    """Smoke checks; needs a panel: show() waits for the real refresh (about 30 s)."""
    chip.clear()
    chip.set_pixel(1, 1, UC8159Full.BLACK)
    check('pixel_black_readback', chip.get_pixel(1, 1) == UC8159Full.BLACK)
    chip.set_pixel(3, 1, UC8159Full.RED)
    check('pixel_red_readback', chip.get_pixel(3, 1) == UC8159Full.RED)
    chip.set_pixel(4, 1, UC8159Full.ORANGE)
    check('pixel_orange_readback', chip.get_pixel(4, 1) == UC8159Full.ORANGE)
    chip.draw_rect(0, 0, 600, 448, UC8159Full.BLACK)
    chip.draw_line(0, 0, 599, 447, UC8159Full.BLACK)
    check('drawing_accepted', chip.get_pixel(0, 0) == UC8159Full.BLACK)
    try:
        chip.show()
        check('show_completes', True)
    except Exception as e:  # noqa: BLE001
        print('show failed:', e)
        check('show_completes', False)
    chip.sleep()
    check('sleep_accepted', True)
    print('===DONE: %d passed, %d failed===' % (passed, failed))


run(chip)
