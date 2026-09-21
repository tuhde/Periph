"""
file     ADXL362
time     2026-09-21
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.chips.accelerometer.adxl362 import ADXL362Full


class ADXL362:
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

    def __init__(self, bus: int = 0, cs_pin: int = 5):
        """
        label:
            en: '%1 init bus %2 CS pin %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            cs_pin:
                name: cs_pin
                type: int
                default: '5'
                field: number
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin)
        self._driver = ADXL362Full(connection)

    def x(self) -> float:
        """
        label:
            en: '%1 acceleration X (g)'
        """
        return self._driver.read()[0]

    def y(self) -> float:
        """
        label:
            en: '%1 acceleration Y (g)'
        """
        return self._driver.read()[1]

    def z(self) -> float:
        """
        label:
            en: '%1 acceleration Z (g)'
        """
        return self._driver.read()[2]

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.temperature()

    def awake(self) -> int:
        """
        label:
            en: '%1 awake (1/0)'
        """
        return int(self._driver.awake())

    def set_range(self, range_g: int = 2):
        """
        label:
            en: '%1 set range %2 g'
        params:
            range_g:
                name: range_g
                type: int
                default: '2'
                field: number
        """
        self._driver.set_range(int(range_g))

    def soft_reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.soft_reset()