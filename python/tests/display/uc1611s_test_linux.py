import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.display.uc1611s import UC1611sFull

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x38'), 16)

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
    check('geometry_240x128', chip.width == 240 and chip.height == 128)

    s = chip.read_status()
    check('status_version_bit', bool(s[0] & 0x80))
    check('status_product_code_1', (s[2] >> 4) == 1)
    check('status_display_enabled', bool(s[0] & 0x08))
    check('status_mirror_x_bottom_view', bool(s[0] & 0x40) and not s[0] & 0x20)

    chip.sleep()
    check('sleep_clears_display_enable', not chip.read_status()[0] & 0x08)
    chip.wake()
    check('wake_sets_display_enable', bool(chip.read_status()[0] & 0x08))

    chip.set_flip(False, True)
    s = chip.read_status()
    check('flip_my_status', not s[0] & 0x40 and bool(s[0] & 0x20))
    chip.set_flip(True, False)
    s = chip.read_status()
    check('flip_mx_status', bool(s[0] & 0x40) and not s[0] & 0x20)

    # RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
    chip.fill(False)
    chip.draw_line(0, 0, 31, 7)
    chip.draw_line(0, 31, 31, 24)
    chip.draw_rect(40, 4, 20, 20)
    chip.show()
    match0 = match3 = True
    for half in range(2):
        ram = chip.read_ram(0, half * 32, 32)
        for x in range(32):
            expected = sum(1 << b for b in range(8) if chip.get_pixel(half * 32 + x, b))
            match0 = match0 and ram[x] == expected
        ram = chip.read_ram(3, half * 32, 32)
        for x in range(32):
            expected = sum(1 << b for b in range(8) if chip.get_pixel(half * 32 + x, 24 + b))
            match3 = match3 and ram[x] == expected
    check('ram_readback_page0', match0)
    check('ram_readback_page3', match3)

    chip.set_contrast(0x70)
    chip.set_contrast(0x8F)
    chip.set_invert(True)
    chip.set_invert(False)
    chip.set_all_on(True)
    chip.set_all_on(False)
    chip.set_scroll_line(16)
    chip.set_scroll_line(0)
    chip.set_fixed_lines(0)
    chip.set_bias_ratio(2)
    chip.set_temperature_compensation(1)
    chip.set_line_rate(3)
    chip.set_pump(True)
    chip.set_panel_loading(True)
    chip.set_partial_display(True, 0, 127)
    chip.set_partial_display(False)
    chip.set_nline_inversion(0)
    chip.set_isolation_clock(1, 0)
    chip.set_write_pattern(1)
    chip.show_page(0)
    chip.set_write_pattern(0)
    chip.set_display_pattern(1)
    chip.set_display_pattern(0)
    chip.show()
    check('commands_accepted', True)

    chip.reset()
    check('reset_restores_display_enable', bool(chip.read_status()[0] & 0x08))
    print('INFO driver retries {}, unrecovered {}'.format(chip.retry_count(), chip.error_count()))
    check('no_unrecovered_transfer_errors', chip.ok())


try:
    command = I2CConnection(I2C_BUS, I2C_ADDR)
    data = I2CConnection(I2C_BUS, I2C_ADDR + 1)
    chip = UC1611sFull(command, data)
    run(chip, check)
except Exception as e:
    print('FAIL exception: {}'.format(e))
    failed += 1
print('===DONE: {} passed, {} failed==='.format(passed, failed))
