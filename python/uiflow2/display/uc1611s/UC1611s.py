"""
file     UC1611s
time     2026-10-20
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.uc1611s import UC1611sFull


class UC1611s:
    """
    note:
        en: ''
    details:
        color: '#C084FC'
        link: https://github.com/tuhde/Periph
        image: ''
        category: Custom
    example: ''
    """

    def __init__(self, bus: int = 0, address: int = 56):
        """
        label:
            en: '%1 init bus %2 command address (56 = 0x38) %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '56'
                field: number
        """
        # EA DOGXL240-7 (240 x 128, 3.3 V): command address `address`, data address `address + 1`.
        command = I2CConnection(address, bus=bus)
        data = I2CConnection(address + 1, bus=bus)
        self._driver = UC1611sFull(command, data)

    def clear(self):
        """
        label:
            en: '%1 clear framebuffer'
        """
        self._driver.clear()

    def fill(self, state: int = 1):
        """
        label:
            en: '%1 fill all pixels on (1) / off (0) %2'
        params:
            state:
                name: state
                type: int
                default: '1'
                field: number
        """
        self._driver.fill(bool(state))

    def set_pixel(self, x: int = 0, y: int = 0, state: int = 1):
        """
        label:
            en: '%1 set pixel x %2 y %3 on (1/0) %4'
        params:
            x:
                name: x
                type: int
                default: '0'
                field: number
            y:
                name: y
                type: int
                default: '0'
                field: number
            state:
                name: state
                type: int
                default: '1'
                field: number
        """
        self._driver.set_pixel(x, y, bool(state))

    def draw_line(self, x0: int = 0, y0: int = 0, x1: int = 239, y1: int = 127, state: int = 1):
        """
        label:
            en: '%1 draw line x0 %2 y0 %3 x1 %4 y1 %5 on (1/0) %6'
        params:
            x0:
                name: x0
                type: int
                default: '0'
                field: number
            y0:
                name: y0
                type: int
                default: '0'
                field: number
            x1:
                name: x1
                type: int
                default: '239'
                field: number
            y1:
                name: y1
                type: int
                default: '127'
                field: number
            state:
                name: state
                type: int
                default: '1'
                field: number
        """
        self._driver.draw_line(x0, y0, x1, y1, bool(state))

    def draw_rect(self, x: int = 0, y: int = 0, w: int = 240, h: int = 128, state: int = 1, fill: int = 0):
        """
        label:
            en: '%1 draw rectangle x %2 y %3 w %4 h %5 on (1/0) %6 filled (1/0) %7'
        params:
            x:
                name: x
                type: int
                default: '0'
                field: number
            y:
                name: y
                type: int
                default: '0'
                field: number
            w:
                name: w
                type: int
                default: '240'
                field: number
            h:
                name: h
                type: int
                default: '128'
                field: number
            state:
                name: state
                type: int
                default: '1'
                field: number
            fill:
                name: fill
                type: int
                default: '0'
                field: number
        """
        self._driver.draw_rect(x, y, w, h, bool(state), bool(fill))

    def show(self):
        """
        label:
            en: '%1 show framebuffer'
        """
        self._driver.show()

    def show_page(self, page: int = 0):
        """
        label:
            en: '%1 show page (0..15) %2'
        params:
            page:
                name: page
                type: int
                default: '0'
                field: number
        """
        self._driver.show_page(page)

    def set_contrast(self, value: int = 143):
        """
        label:
            en: '%1 set contrast (0..255) %2'
        params:
            value:
                name: value
                type: int
                default: '143'
                field: number
        """
        self._driver.set_contrast(value)

    def set_display(self, state: int = 1):
        """
        label:
            en: '%1 display on (1) / sleep (0) %2'
        params:
            state:
                name: state
                type: int
                default: '1'
                field: number
        """
        self._driver.set_display(bool(state))

    def set_invert(self, state: int = 0):
        """
        label:
            en: '%1 invert on (1) / off (0) %2'
        params:
            state:
                name: state
                type: int
                default: '0'
                field: number
        """
        self._driver.set_invert(bool(state))

    def set_flip(self, mirror_x: int = 1, mirror_y: int = 0):
        """
        label:
            en: '%1 mirror x (1/0) %2 mirror y (1/0) %3'
        params:
            mirror_x:
                name: mirror_x
                type: int
                default: '1'
                field: number
            mirror_y:
                name: mirror_y
                type: int
                default: '0'
                field: number
        """
        self._driver.set_flip(bool(mirror_x), bool(mirror_y))

    def set_scroll_line(self, line: int = 0):
        """
        label:
            en: '%1 scroll line (0..159) %2'
        params:
            line:
                name: line
                type: int
                default: '0'
                field: number
        """
        self._driver.set_scroll_line(line)

    def sleep(self):
        """
        label:
            en: '%1 sleep'
        """
        self._driver.sleep()

    def wake(self):
        """
        label:
            en: '%1 wake'
        """
        self._driver.wake()
