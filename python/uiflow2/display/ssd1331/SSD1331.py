"""
file     SSD1331
time     2026-10-16
author
email
license  Apache License 2.0
"""

from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.ssd1331 import SSD1331Full


class SSD1331:
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

    def __init__(self, sck: int = 18, mosi: int = 19, cs: int = 17, dc: int = 20, res: int = 21):
        """
        label:
            en: '%1 init SCK %2 MOSI %3 CS %4 DC %5 RES %6'
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
        """
        # Colours are 24-bit 0xRRGGBB values (the UIFlow colour picker) converted to RGB565.
        spi = SPI(2, baudrate=4000000, polarity=0, phase=0, sck=Pin(sck), mosi=Pin(mosi))
        connection = DisplayConnection(spi, MicroPythonOutputPin(cs), MicroPythonOutputPin(dc),
                                       MicroPythonOutputPin(res), None)
        self._driver = SSD1331Full(connection)

    def clear(self, color: int):
        """
        label:
            en: '%1 clear framebuffer color (0xRRGGBB) %2'
        params:
            color:
                name: color
                type: int
                default: '0'
                field: number
        """
        self._driver.clear(SSD1331Full.rgb(color >> 16, color >> 8, color & 0xFF))

    def set_pixel(self, x: int, y: int, color: int):
        """
        label:
            en: '%1 set pixel x %2 y %3 color (0xRRGGBB) %4'
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
                default: '16777215'
                field: number
        """
        self._driver.set_pixel(x, y, SSD1331Full.rgb(color >> 16, color >> 8, color & 0xFF))

    def draw_line(self, x0: int, y0: int, x1: int, y1: int, color: int):
        """
        label:
            en: '%1 draw line x0 %2 y0 %3 x1 %4 y1 %5 color (0xRRGGBB) %6'
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
                default: '95'
                field: number
            y1:
                name: y1
                type: int
                default: '63'
                field: number
            color:
                name: color
                type: int
                default: '16777215'
                field: number
        """
        self._driver.draw_line(x0, y0, x1, y1, SSD1331Full.rgb(color >> 16, color >> 8, color & 0xFF))

    def draw_rect(self, x: int, y: int, w: int, h: int, color: int, fill: int):
        """
        label:
            en: '%1 draw rectangle x %2 y %3 width %4 height %5 color (0xRRGGBB) %6 fill (0/1) %7'
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
                default: '20'
                field: number
            h:
                name: h
                type: int
                default: '10'
                field: number
            color:
                name: color
                type: int
                default: '16777215'
                field: number
            fill:
                name: fill
                type: int
                default: '1'
                field: number
        """
        self._driver.draw_rect(x, y, w, h, SSD1331Full.rgb(color >> 16, color >> 8, color & 0xFF), bool(fill))

    def show(self):
        """
        label:
            en: '%1 show framebuffer'
        """
        self._driver.show()

    def display(self, state: int):
        """
        label:
            en: '%1 display on (1) / off (0) %2'
        params:
            state:
                name: state
                type: int
                default: '1'
                field: number
        """
        self._driver.display_on() if state else self._driver.display_off()
