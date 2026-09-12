"""
file     HX710A
time     2026-09-12
author
email
license  Apache License 2.0
"""

from periph.connection.hx711_auto import HX711Connection
from periph.chips.adc_dac.hx710a import HX710AFull


class HX710A:
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
        self._driver = HX710AFull(connection)

    def is_ready(self) -> bool:
        """
        label:
            en: '%1 ready?'
        """
        return self._driver.is_ready()

    def read_raw(self) -> int:
        """
        label:
            en: '%1 raw ADC value'
        """
        return self._driver.read_raw()

    def set_rate(self, rate: int = 10):
        """
        label:
            en: '%1 set rate rate %2'
        params:
            rate:
                name: rate
                type: int
                default: '10'
                field: number
                min: '10'
                max: '40'
        """
        self._driver.set_rate(rate)

    def read_average(self, times: int = 10) -> int:
        """
        label:
            en: '%1 average of %2 readings'
        params:
            times:
                name: times
                type: int
                default: '10'
                field: number
        """
        return self._driver.read_average(times)

    def tare(self):
        """
        label:
            en: '%1 tare (zero the scale)'
        """
        self._driver.tare()

    def get_offset(self) -> int:
        """
        label:
            en: '%1 tare offset'
        """
        return self._driver.get_offset()

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

    def get_scale(self) -> float:
        """
        label:
            en: '%1 scale factor'
        """
        return self._driver.get_scale()

    def read_weight(self) -> float:
        """
        label:
            en: '%1 weight'
        """
        return self._driver.read_weight()

    def read_temperature_raw(self) -> int:
        """
        label:
            en: '%1 raw temperature code'
        """
        return self._driver.read_temperature_raw()

    def power_down(self):
        """
        label:
            en: '%1 power down'
        """
        self._driver.power_down()

    def power_up(self):
        """
        label:
            en: '%1 power up'
        """
        self._driver.power_up()
