import os
from periph.connection.display_linux import DisplayConnection
from periph.connection.input_pin import LinuxSysfsPin
from periph.connection.output_pin import LinuxOutputPin
from periph.chips.display.il0373 import IL0373Full

GPIO_CHIP = os.environ.get('GPIO_CHIP', '/dev/gpiochip0')
dc = LinuxOutputPin(GPIO_CHIP, int(os.environ.get('DISPLAY_DC', '25')))
res = LinuxOutputPin(GPIO_CHIP, int(os.environ.get('DISPLAY_RES', '24')), True)
busy = LinuxSysfsPin(GPIO_CHIP, int(os.environ.get('DISPLAY_BUSY', '23')))
connection = DisplayConnection(dc, res, busy,
                               bus_num=int(os.environ.get('LINUX_SPI_BUS', '0')),
                               device_num=int(os.environ.get('LINUX_SPI_DEVICE', '0')))
chip = IL0373Full(connection)

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
    """Smoke checks; needs a panel: show() waits for the real until BUSY is high refresh."""
    chip.clear()
    chip.set_pixel(1, 1, IL0373Full.BLACK)
    check('pixel_black_readback', chip.get_pixel(1, 1) == IL0373Full.BLACK)
    chip.set_pixel(3, 1, IL0373Full.WHITE)
    check('pixel_white_readback', chip.get_pixel(3, 1) == IL0373Full.WHITE)
    chip.draw_rect(0, 0, chip.width, chip.height, IL0373Full.BLACK)
    chip.draw_line(0, 0, chip.width - 1, chip.height - 1, IL0373Full.BLACK)
    check('drawing_accepted', chip.get_pixel(0, 0) == IL0373Full.BLACK)
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
