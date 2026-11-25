"""
file     SSD1305
time     2026-11-11
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.ssd1305 import SSD1305Full


class SSD1305:
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

    def __init__(self, bus: int = 0, address: int = 60, column_offset: int = 4):
        """
        label:
            en: '%1 init bus %2 address (0x3C=60, 0x3D=61) %3 column offset (4 for 128 px modules) %4'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '60'
                field: number
            column_offset:
                name: column_offset
                type: int
                default: '4'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = SSD1305Full(connection, column_offset=column_offset)

    def clear(self):
        """
        label:
            en: '%1 clear framebuffer'
        """
        self._driver.clear()

    def set_pixel(self, x: int, y: int, enabled: int):
        """
        label:
            en: '%1 set pixel x %2 y %3 on (1/0) %4'
        params:
            x:
                name: x
                type: int
                field: number
            y:
                name: y
                type: int
                field: number
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.set_pixel(x, y, bool(enabled))

    def show(self):
        """
        label:
            en: '%1 show (send framebuffer)'
        """
        self._driver.show()

    def show_page(self, page: int):
        """
        label:
            en: '%1 show page (0..7) %2'
        params:
            page:
                name: page
                type: int
                field: number
        """
        self._driver.show_page(page)

    def fill(self, enabled: int):
        """
        label:
            en: '%1 fill framebuffer on (1/0) %2'
        params:
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.fill(bool(enabled))

    def draw_line(self, x0: int, y0: int, x1: int, y1: int, enabled: int):
        """
        label:
            en: '%1 line x0 %2 y0 %3 x1 %4 y1 %5 on (1/0) %6'
        params:
            x0:
                name: x0
                type: int
                field: number
            y0:
                name: y0
                type: int
                field: number
            x1:
                name: x1
                type: int
                field: number
            y1:
                name: y1
                type: int
                field: number
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.draw_line(x0, y0, x1, y1, bool(enabled))

    def draw_rect(self, x: int, y: int, w: int, h: int, enabled: int, filled: int):
        """
        label:
            en: '%1 rectangle x %2 y %3 w %4 h %5 on (1/0) %6 filled (1/0) %7'
        params:
            x:
                name: x
                type: int
                field: number
            y:
                name: y
                type: int
                field: number
            w:
                name: w
                type: int
                field: number
            h:
                name: h
                type: int
                field: number
            enabled:
                name: enabled
                type: int
                field: number
            filled:
                name: filled
                type: int
                field: number
        """
        self._driver.draw_rect(x, y, w, h, bool(enabled), bool(filled))

    def set_contrast(self, value: int):
        """
        label:
            en: '%1 set contrast (0..255) %2'
        params:
            value:
                name: value
                type: int
                field: number
        """
        self._driver.set_contrast(value)

    def set_display(self, enabled: int):
        """
        label:
            en: '%1 display on (1/0) %2'
        params:
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.set_display(bool(enabled))

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

    def set_invert(self, enabled: int):
        """
        label:
            en: '%1 invert (1/0) %2'
        params:
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.set_invert(bool(enabled))

    def set_flip(self, horizontal: int, vertical: int):
        """
        label:
            en: '%1 flip horizontal (1/0) %2 vertical (1/0) %3'
        params:
            horizontal:
                name: horizontal
                type: int
                field: number
            vertical:
                name: vertical
                type: int
                field: number
        """
        self._driver.set_flip(bool(horizontal), bool(vertical))

    def set_start_line(self, line: int):
        """
        label:
            en: '%1 scroll: start line (0..63) %2'
        params:
            line:
                name: line
                type: int
                field: number
        """
        self._driver.set_start_line(line)

    def read_status(self) -> int:
        """
        label:
            en: '%1 status byte (bit 7 BUSY, bit 6 display off)'
        """
        return self._driver.read_status()
