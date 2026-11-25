"""
file     IL0373
time     2026-10-15
author
email
license  Apache License 2.0
"""

from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.connection.input_pin import MicroPythonPin
from periph.chips.display.il0373 import IL0373Full


class IL0373:
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

    def __init__(self, sck: int = 18, mosi: int = 19, cs: int = 17, dc: int = 20, res: int = 21, busy: int = 22):
        """
        label:
            en: '%1 init SCK %2 MOSI %3 CS %4 DC %5 RES %6 BUSY %7'
        params:
            sck:
                name: sck
                type: int
                default: '18'
                field: number
            mosi:
                name: mosi
                type: int
                default: '19'
                field: number
            cs:
                name: cs
                type: int
                default: '17'
                field: number
            dc:
                name: dc
                type: int
                default: '20'
                field: number
            res:
                name: res
                type: int
                default: '21'
                field: number
            busy:
                name: busy
                type: int
                default: '22'
                field: number
        """
        spi = SPI(2, baudrate=4000000, polarity=0, phase=0, sck=Pin(sck), mosi=Pin(mosi))
        connection = DisplayConnection(spi, MicroPythonOutputPin(cs), MicroPythonOutputPin(dc),
                                       MicroPythonOutputPin(res), MicroPythonPin(Pin(busy, Pin.IN)))
        self._driver = IL0373Full(connection)

    def clear(self, color: int):
        """
        label:
            en: '%1 clear framebuffer color (0 black, 1 white) %2'
        params:
            color:
                name: color
                type: int
                default: '1'
                field: number
        """
        self._driver.clear(color)

    def set_pixel(self, x: int, y: int, color: int):
        """
        label:
            en: '%1 set pixel x %2 y %3 color (0 black, 1 white) %4'
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
            color:
                name: color
                type: int
                default: '0'
                field: number
        """
        self._driver.set_pixel(x, y, color)

    def draw_line(self, x0: int, y0: int, x1: int, y1: int, color: int):
        """
        label:
            en: '%1 draw line x0 %2 y0 %3 x1 %4 y1 %5 color (0 black, 1 white) %6'
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
                default: '10'
                field: number
            y1:
                name: y1
                type: int
                default: '10'
                field: number
            color:
                name: color
                type: int
                default: '0'
                field: number
        """
        self._driver.draw_line(x0, y0, x1, y1, color)

    def draw_rect(self, x: int, y: int, w: int, h: int, color: int, fill: int):
        """
        label:
            en: '%1 draw rectangle x %2 y %3 width %4 height %5 color (0 black, 1 white) %6 fill (0/1) %7'
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
                default: '10'
                field: number
            h:
                name: h
                type: int
                default: '10'
                field: number
            color:
                name: color
                type: int
                default: '0'
                field: number
            fill:
                name: fill
                type: int
                default: '1'
                field: number
        """
        self._driver.draw_rect(x, y, w, h, color, bool(fill))

    def show(self):
        """
        label:
            en: '%1 refresh panel (blocks until BUSY is high)'
        """
        self._driver.show()

    def sleep(self):
        """
        label:
            en: '%1 deep sleep'
        """
        self._driver.sleep()
