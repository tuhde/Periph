"""
file     ST7036
time     2026-10-16
author
email
license  Apache License 2.0
"""

from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.st7036 import ST7036Full


class ST7036:
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

    def __init__(self, sck: int = 18, mosi: int = 19, cs: int = 17, rs: int = 20, res: int = 21):
        """
        label:
            en: '%1 init SCK %2 MOSI %3 CS %4 RS %5 RES %6'
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
            rs:
                name: rs
                type: int
                default: '20'
                field: number
            res:
                name: res
                type: int
                default: '21'
                field: number
        """
        # EA DOGM162W-A (2 x 16, 3.3 V), 4-wire serial, SPI mode 0 at 1 MHz.
        spi = SPI(2, baudrate=1000000, polarity=0, phase=0, sck=Pin(sck), mosi=Pin(mosi))
        connection = DisplayConnection(spi, MicroPythonOutputPin(cs), MicroPythonOutputPin(rs),
                                       MicroPythonOutputPin(res), None)
        self._driver = ST7036Full(connection)

    def clear(self):
        """
        label:
            en: '%1 clear display'
        """
        self._driver.clear()

    def home(self):
        """
        label:
            en: '%1 return home'
        """
        self._driver.home()

    def set_cursor(self, col: int = 0, row: int = 0):
        """
        label:
            en: '%1 set cursor col %2 row %3'
        params:
            col:
                name: col
                type: int
                default: '0'
                field: number
            row:
                name: row
                type: int
                default: '0'
                field: number
        """
        self._driver.set_cursor(col, row)

    def write(self, text: str):
        """
        label:
            en: '%1 write text %2'
        params:
            text:
                name: text
                type: str
        """
        self._driver.write(text)

    def print_at(self, text: str, col: int = 0, row: int = 0):
        """
        label:
            en: '%1 print text %2 at col %3 row %4'
        params:
            text:
                name: text
                type: str
            col:
                name: col
                type: int
                default: '0'
                field: number
            row:
                name: row
                type: int
                default: '0'
                field: number
        """
        self._driver.print_at(col, row, text)

    def set_contrast(self, value: int = 24):
        """
        label:
            en: '%1 set contrast (0..63) %2'
        params:
            value:
                name: value
                type: int
                default: '24'
                field: number
        """
        self._driver.set_contrast(value)

    def cursor(self, show: int = 0, blink: int = 0):
        """
        label:
            en: '%1 cursor on (1) / off (0) %2 blink (1/0) %3'
        params:
            show:
                name: show
                type: int
                default: '0'
                field: number
            blink:
                name: blink
                type: int
                default: '0'
                field: number
        """
        self._driver.cursor(bool(show), bool(blink))

    def display(self, enable: int = 1):
        """
        label:
            en: '%1 display on (1) / off (0) %2'
        params:
            enable:
                name: enable
                type: int
                default: '1'
                field: number
        """
        self._driver.display(bool(enable))
