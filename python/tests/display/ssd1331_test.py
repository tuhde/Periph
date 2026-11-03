import _testconfig as cfg
from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.ssd1331 import SSD1331Full

spi = SPI(getattr(cfg, 'SPI_ID', 0), baudrate=4000000, polarity=0, phase=0)
cs = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_CS', 17))
dc = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_DC', 20))
res = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_RES', 21))
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
