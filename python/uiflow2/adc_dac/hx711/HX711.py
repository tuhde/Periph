"""
file     HX711
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.hx711_auto import HX711Connection
from periph.chips.adc_dac.hx711 import HX711Full


class HX711:
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

    def __init__(self, dout: int = 0, pd_sck: int = 0):
        """
        label:
            en: '%1 init dout %2 pd_sck %3'
        params:
            dout:
                name: dout
                type: int
                default: '0'
                field: number
            pd_sck:
                name: pd_sck
                type: int
                default: '0'
                field: number
        """
        connection = HX711Connection(dout, pd_sck)
        self._driver = HX711Full(connection)

    def tare(self):
        """
        label:
            en: '%1 tare (zero the scale)'
        """
        self._driver.tare()

    def set_scale(self, factor: float):
        """
        label:
            en: '%1 set scale factor factor %2'
        params:
            factor:
                name: factor
                type: float
                field: number
        """
        self._driver.set_scale(factor)

    def read_weight(self) -> float:
        """
        label:
            en: '%1 weight'
        """
        return self._driver.read_weight()

    def read_raw(self) -> int:
        """
        label:
            en: '%1 raw ADC value'
        """
        return self._driver.read_raw()
