import board
import busio
import digitalio
from periph.connection.display_circuitpython import DisplayConnection
from periph.connection.input_pin import CircuitPythonPin
from periph.connection.output_pin import CircuitPythonOutputPin
from periph.chips.display.uc8159 import UC8159Full


def _out(pin):
    p = digitalio.DigitalInOut(pin)
    p.direction = digitalio.Direction.OUTPUT
    return p


def _in(pin):
    p = digitalio.DigitalInOut(pin)
    p.direction = digitalio.Direction.INPUT
    return p


spi = busio.SPI(board.SCK, MOSI=board.MOSI)
cs = CircuitPythonOutputPin(_out(board.D10))
dc = CircuitPythonOutputPin(_out(board.D9))
res = CircuitPythonOutputPin(_out(board.D8))
busy = CircuitPythonPin(_in(board.D7))
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
