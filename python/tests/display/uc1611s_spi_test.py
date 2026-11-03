import _testconfig as cfg
from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.uc1611s import UC1611sSpiFull

spi = SPI(getattr(cfg, 'SPI_ID', 0), baudrate=1000000, polarity=0, phase=0)
cs = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_CS', 17))
dc = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_DC', 20))
res = MicroPythonOutputPin(getattr(cfg, 'DISPLAY_RES', 21))
connection = DisplayConnection(spi, cs, dc, res, None)
chip = UC1611sSpiFull(connection)

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
    """Hardware checks; the SPI module is write-only, so the picture is verified by eye (see the demo example)."""
    check('init_valid', True)
    check('geometry_240x128', chip.width == 240 and chip.height == 128)
    try:
        chip.clear()
        chip.draw_rect(0, 0, 240, 128)
        chip.draw_line(0, 0, 239, 127)
        chip.draw_rect(20, 20, 40, 30, True, True)
        chip.show()
        check('show_accepted', chip.ok())
        chip.show_page(3)
        chip.set_contrast(0x70)
        chip.set_contrast(0x8F)
        chip.set_invert(True)
        chip.set_invert(False)
        chip.set_all_on(True)
        chip.set_all_on(False)
        chip.set_flip(False, True)
        chip.set_flip(True, False)
        chip.set_fixed_lines(2)
        chip.set_scroll_line(8)
        chip.set_scroll_line(0)
        chip.set_fixed_lines(0)
        chip.set_bias_ratio(2)
        chip.set_temperature_compensation(1)
        chip.set_line_rate(3)
        chip.set_pump(True)
        chip.set_panel_loading(True)
        chip.set_nline_inversion(0)
        chip.set_isolation_clock(1, 0)
        chip.set_partial_display(True, 32, 95)
        chip.set_partial_display(False)
        chip.set_write_pattern(1)
        chip.show()
        chip.set_display_pattern(1)
        chip.set_display_pattern(0)
        chip.set_write_pattern(0)
        check('commands_accepted', chip.ok())
        chip.sleep()
        chip.wake()
        chip.reset()
        chip.show()
        check('sleep_wake_reset_accepted', chip.ok())
    except Exception as e:  # noqa: BLE001
        print('command failed:', e)
        check('commands_accepted', False)
    try:
        chip.read_status()
        check('read_status_unsupported', False)
    except NotImplementedError:
        check('read_status_unsupported', True)
    try:
        chip.read_ram(0, 0, 4)
        check('read_ram_unsupported', False)
    except NotImplementedError:
        check('read_ram_unsupported', True)
    check('no_retries_or_errors', chip.retry_count() == 0 and chip.error_count() == 0)
    print('===DONE: %d passed, %d failed===' % (passed, failed))


run(chip)
