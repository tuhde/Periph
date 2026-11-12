import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.display.ssd1306 import SSD1306Full
from machine import Pin

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


def run(chip, check):
    """Hardware checks; the picture is verified by eye (see the demo example)."""
    check('geometry_128x64', chip.width == 128 and chip.height == 64)

    # The SSD1306 cannot be read over its serial interfaces: the picture is checked by eye.
    chip.fill(False)
    chip.draw_line(0, 0, 31, 7)
    chip.draw_line(0, 31, 31, 24)
    chip.draw_rect(40, 4, 20, 20)
    chip.show()
    check('framebuffer_pixels', chip.get_pixel(0, 0) and chip.get_pixel(40, 4))

    chip.set_contrast(0x40)
    chip.set_contrast(0x80)
    chip.set_invert(True)
    chip.set_invert(False)
    chip.set_all_on(True)
    chip.set_all_on(False)
    chip.set_flip(True, True)
    chip.set_flip(False, False)
    chip.set_start_line(16)
    chip.set_start_line(0)
    chip.set_display_offset(0)
    chip.set_multiplex(64)
    chip.set_clock(1, 8)
    chip.set_precharge(2, 2)
    chip.set_com_config(True, False)
    chip.set_vcomh(0x20)
    chip.set_charge_pump(True)
    chip.set_scroll_horizontal(True, 0, 7, 7)
    chip.scroll_start()
    try:
        chip.show()
        raised = False
    except RuntimeError:
        raised = True
    check('show_refused_while_scrolling', raised)
    chip.scroll_stop()
    chip.set_addressing_mode('horizontal')
    chip.show()
    chip.set_addressing_mode('page')
    chip.show_page(0)
    chip.show()
    check('commands_accepted', True)


try:
    i2c = machine.I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
    chip = SSD1306Full(I2CConnection(i2c, cfg.ADDR))
    run(chip, check)
except Exception as e:
    print('FAIL exception: {}'.format(e))
    failed += 1
print('===DONE: {} passed, {} failed==='.format(passed, failed))
