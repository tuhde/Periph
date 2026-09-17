"""
file     APA102
time     2026-09-16
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.chips.led.apa102 import APA102Full


class APA102:
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

    def __init__(self, bus: int = 0, device: int = 0, n: int = 1):
        """
        label:
            en: '%1 init SPI bus %2 device %3 n %4'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
            device:
                name: device
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
            n:
                name: n
                type: int
                default: '1'
                field: number
                min: '1'
                max: '1024'
        """
        self._driver = APA102Full(SPIConnection(bus=bus, device=device, polarity=0, phase=0, baudrate=1000000), n)

    def fill(self, r: int, g: int, b: int):
        """
        label:
            en: '%1 fill all pixels r %2 g %3 b %4'
        params:
            r:
                name: r
                type: int
                field: number
                min: '0'
                max: '255'
            g:
                name: g
                type: int
                field: number
                min: '0'
                max: '255'
            b:
                name: b
                type: int
                field: number
                min: '0'
                max: '255'
        """
        self._driver.fill(r, g, b)

    def set_pixel(self, index: int, r: int, g: int, b: int, pixel_brightness: int = 31):
        """
        label:
            en: '%1 set pixel index %2 r %3 g %4 b %5 brightness %6'
        params:
            index:
                name: index
                type: int
                field: number
                min: '0'
                max: '1023'
            r:
                name: r
                type: int
                field: number
                min: '0'
                max: '255'
            g:
                name: g
                type: int
                field: number
                min: '0'
                max: '255'
            b:
                name: b
                type: int
                field: number
                min: '0'
                max: '255'
            pixel_brightness:
                name: pixel_brightness
                type: int
                default: '31'
                field: number
                min: '0'
                max: '31'
        """
        self._driver.set_pixel(index, r, g, b, pixel_brightness)

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

    def brightness(self) -> int:
        """
        label:
            en: '%1 brightness (0–255)'
        """
        return self._driver.brightness

    def set_brightness(self, value: int):
        """
        label:
            en: '%1 set brightness %2'
        params:
            value:
                name: value
                type: int
                field: number
                min: '0'
                max: '255'
        """
        self._driver.brightness = value

    def set_pixels(self, colors):
        """
        label:
            en: '%1 set pixels from list %2'
        params:
            colors:
                name: colors
                type: list
        """
        self._driver.set_pixels(colors)

    def rotate(self, steps: int = 1):
        """
        label:
            en: '%1 rotate buffer steps %2'
        params:
            steps:
                name: steps
                type: int
                default: '1'
                field: number
        """
        self._driver.rotate(steps)

    def fill_hsv(self, h: float, s: float, v: float):
        """
        label:
            en: '%1 fill hsv h %2 s %3 v %4'
        params:
            h:
                name: h
                type: float
                field: number
                min: '0.0'
                max: '1.0'
            s:
                name: s
                type: float
                field: number
                min: '0.0'
                max: '1.0'
            v:
                name: v
                type: float
                field: number
                min: '0.0'
                max: '1.0'
        """
        self._driver.fill_hsv(h, s, v)