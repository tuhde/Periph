import board
import busio
import digitalio
from periph.connection.display_circuitpython import DisplayConnection
from periph.connection.output_pin import CircuitPythonOutputPin
from periph.chips.display.ssd1331 import SSD1331Full


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
connection = DisplayConnection(spi, cs, dc, res, None)
chip = SSD1331Full(connection)

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
    """Smoke checks; the panel picture is verified by eye (see the demo example)."""
    check('geometry_96x64', chip.width == 96 and chip.height == 64)
    chip.clear(SSD1331Full.BLUE)
    check('clear_readback', chip.get_pixel(0, 0) == SSD1331Full.BLUE and chip.get_pixel(95, 63) == SSD1331Full.BLUE)
    chip.set_pixel(5, 6, SSD1331Full.RED)
    check('pixel_readback', chip.get_pixel(5, 6) == SSD1331Full.RED)
    chip.draw_rect(10, 10, 20, 10, SSD1331Full.GREEN, True)
    check('draw_rect_fill', chip.get_pixel(15, 15) == SSD1331Full.GREEN)
    chip.draw_line(0, 0, 95, 63, SSD1331Full.WHITE)
    check('draw_line_endpoints', chip.get_pixel(0, 0) == SSD1331Full.WHITE and chip.get_pixel(95, 63) == SSD1331Full.WHITE)
    try:
        chip.show()
        chip.show_region(8, 8, 32, 16)
        chip.hw_clear(0, 0, 95, 63)
        chip.hw_rect(10, 10, 40, 30, SSD1331Full.YELLOW, SSD1331Full.RED, True)
        chip.hw_line(0, 0, 95, 63, SSD1331Full.WHITE)
        chip.hw_copy(10, 10, 30, 30, 50, 20)
        chip.hw_dim(0, 0, 47, 63)
        chip.set_contrast(0x80, 0x80, 0x80)
        chip.set_master_current(7)
        chip.set_display_mode(SSD1331Full.MODE_INVERSE)
        chip.set_display_mode(SSD1331Full.MODE_NORMAL)
        chip.set_flip(False, False)
        chip.scroll(1, 0, 64, 0, 0)
        chip.scroll_stop()
        chip.display_off()
        chip.display_on()
        check('commands_accepted', True)
    except Exception as e:  # noqa: BLE001
        print('command failed:', e)
        check('commands_accepted', False)
    print('===DONE: %d passed, %d failed===' % (passed, failed))


run(chip)
