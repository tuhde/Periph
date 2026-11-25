import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.display.sh1106 import SH1106Full

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x3C'), 16)

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

    s = chip.read_status()
    check('status_not_busy', not s & 0x80)
    check('status_display_on', not s & 0x40)

    chip.sleep()
    check('sleep_sets_off_bit', bool(chip.read_status() & 0x40))
    chip.wake()
    check('wake_clears_off_bit', not chip.read_status() & 0x40)

    # RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
    chip.fill(False)
    chip.draw_line(0, 0, 31, 7)
    chip.draw_line(0, 31, 31, 24)
    chip.draw_rect(40, 4, 20, 20)
    chip.show()
    match0 = match3 = True
    for half in range(2):
        ram = chip.read_page(0, 2 + half * 32, 32)
        for x in range(32):
            expected = sum(1 << b for b in range(8) if chip.get_pixel(half * 32 + x, b))
            match0 = match0 and ram[x] == expected
        ram = chip.read_page(3, 2 + half * 32, 32)
        for x in range(32):
            expected = sum(1 << b for b in range(8) if chip.get_pixel(half * 32 + x, 24 + b))
            match3 = match3 and ram[x] == expected
    check('ram_readback_page0', match0)
    check('ram_readback_page3', match3)

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
    chip.set_clock(1, 5)
    chip.set_precharge(2, 2)
    chip.set_com_config(True)
    chip.set_vcom_deselect(0x35)
    chip.set_pump_voltage(0)
    chip.show_page(0)
    chip.show()
    check('commands_accepted', not chip.read_status() & 0x80)


try:
    chip = SH1106Full(I2CConnection(I2C_BUS, I2C_ADDR))
    run(chip, check)
except Exception as e:
    print('FAIL exception: {}'.format(e))
    failed += 1
print('===DONE: {} passed, {} failed==='.format(passed, failed))
