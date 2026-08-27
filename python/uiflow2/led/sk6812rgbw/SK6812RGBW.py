"""
file     SK6812RGBW
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.neopixel_auto import NeoPixelConnection
from periph.chips.led.sk6812rgbw import SK6812RGBWFull


class SK6812RGBW:
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

    def __init__(self, mosi: int = 0, sck: int = 0, miso: int = 0, n: int = 1):
        """
        label:
            en: '%1 init mosi %2 sck %3 miso %4 n %5'
        params:
            mosi:
                name: mosi
                type: int
                default: '0'
                field: number
            sck:
                name: sck
                type: int
                default: '0'
                field: number
            miso:
                name: miso
                type: int
                default: '0'
                field: number
            n:
                name: n
                type: int
                default: '1'
                field: number
        """
        connection = NeoPixelConnection(mosi=mosi, sck=sck, miso=miso)
        self._driver = SK6812RGBWFull(connection, n)

    def fill(self, r: int, g: int, b: int, w: int):
        """
        label:
            en: '%1 fill all pixels r %2 g %3 b %4 w %5'
        params:
            r:
                name: r
                type: int
                field: number
            g:
                name: g
                type: int
                field: number
            b:
                name: b
                type: int
                field: number
            w:
                name: w
                type: int
                field: number
        """
        self._driver.fill(r, g, b, w)

    def set_pixel(self, index: int, r: int, g: int, b: int, w: int):
        """
        label:
            en: '%1 set pixel index %2 r %3 g %4 b %5 w %6'
        params:
            index:
                name: index
                type: int
                field: number
            r:
                name: r
                type: int
                field: number
            g:
                name: g
                type: int
                field: number
            b:
                name: b
                type: int
                field: number
            w:
                name: w
                type: int
                field: number
        """
        self._driver.set_pixel(index, r, g, b, w)

    def show(self):
        """
        label:
            en: '%1 show (transmit buffer)'
        """
        self._driver.show()

    def off(self):
        """
        label:
            en: '%1 off (all pixels)'
        """
        self._driver.off()
