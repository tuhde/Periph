"""
file     AD5252
time     2026-11-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ad5252 import AD5252Full


class AD5252:
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

    def __init__(self, bus: int = 0, r_ab: float = 10000.0):
        """
        label:
            en: '%1 init bus %2 RAB (ohm) %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            r_ab:
                name: r_ab
                type: float
                default: '10000.0'
                field: number
        """
        connection = I2CConnection(0x2C, bus=bus)
        self._driver = AD5252Full(connection, r_ab)

    def set_wiper(self, channel: int, code: int):
        """
        label:
            en: '%1 set wiper channel (0-1) %2 code (0-255) %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            code:
                name: code
                type: int
                default: '128'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.set_wiper(channel, code)

    def get_wiper(self, channel: int) -> int:
        """
        label:
            en: '%1 wiper code channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.get_wiper(channel)

    def set_resistance(self, channel: int, ohms: float):
        """
        label:
            en: '%1 set W-B resistance channel (0-1) %2 ohms %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            ohms:
                name: ohms
                type: float
                default: '5000.0'
                field: number
        """
        self._driver.set_resistance(channel, ohms)

    def get_resistance(self, channel: int) -> float:
        """
        label:
            en: '%1 W-B resistance (ohm) channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.get_resistance(channel)

    def set_both(self, code0: int, code1: int):
        """
        label:
            en: '%1 set both wipers channel 0 code %2 channel 1 code %3'
        params:
            code0:
                name: code0
                type: int
                default: '128'
                field: number
                min: '0'
                max: '255'
            code1:
                name: code1
                type: int
                default: '128'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.set_both(code0, code1)

    def set_ratio(self, channel: int, ratio: float):
        """
        label:
            en: '%1 set ratio channel (0-1) %2 ratio (0-1) %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            ratio:
                name: ratio
                type: float
                default: '0.5'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_ratio(channel, ratio)

    def get_ratio(self, channel: int) -> float:
        """
        label:
            en: '%1 ratio channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.get_ratio(channel)

    def set_resistance_wa(self, channel: int, ohms: float):
        """
        label:
            en: '%1 set W-A resistance channel (0-1) %2 ohms %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            ohms:
                name: ohms
                type: float
                default: '5000.0'
                field: number
        """
        self._driver.set_resistance_wa(channel, ohms)

    def get_resistance_wa(self, channel: int) -> float:
        """
        label:
            en: '%1 W-A resistance (ohm) channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.get_resistance_wa(channel)

    def increment(self, channel: int):
        """
        label:
            en: '%1 step up channel (0-1, -1 = both) %2'
        params:
            channel:
                name: channel
                type: int
                default: '-1'
                field: number
                min: '-1'
                max: '1'
        """
        self._driver.increment(None if channel < 0 else channel)

    def decrement(self, channel: int):
        """
        label:
            en: '%1 step down channel (0-1, -1 = both) %2'
        params:
            channel:
                name: channel
                type: int
                default: '-1'
                field: number
                min: '-1'
                max: '1'
        """
        self._driver.decrement(None if channel < 0 else channel)

    def double(self, channel: int):
        """
        label:
            en: '%1 double (+6 dB) channel (0-1, -1 = both) %2'
        params:
            channel:
                name: channel
                type: int
                default: '-1'
                field: number
                min: '-1'
                max: '1'
        """
        self._driver.double(None if channel < 0 else channel)

    def halve(self, channel: int):
        """
        label:
            en: '%1 halve (-6 dB) channel (0-1, -1 = both) %2'
        params:
            channel:
                name: channel
                type: int
                default: '-1'
                field: number
                min: '-1'
                max: '1'
        """
        self._driver.halve(None if channel < 0 else channel)

    def store(self, channel: int):
        """
        label:
            en: '%1 store to EEMEM channel (0-1, -1 = both) %2'
        params:
            channel:
                name: channel
                type: int
                default: '-1'
                field: number
                min: '-1'
                max: '1'
        """
        self._driver.store(None if channel < 0 else channel)

    def restore(self, channel: int):
        """
        label:
            en: '%1 restore from EEMEM channel (0-1, -1 = both) %2'
        params:
            channel:
                name: channel
                type: int
                default: '-1'
                field: number
                min: '-1'
                max: '1'
        """
        self._driver.restore(None if channel < 0 else channel)

    def reset(self):
        """
        label:
            en: '%1 reset from EEMEM'
        """
        self._driver.reset()

    def read_eemem(self, index: int) -> int:
        """
        label:
            en: '%1 read user EEMEM index (4-15) %2'
        params:
            index:
                name: index
                type: int
                default: '4'
                field: number
                min: '4'
                max: '15'
        """
        return self._driver.read_eemem(index)

    def write_eemem(self, index: int, value: int):
        """
        label:
            en: '%1 write user EEMEM index (4-15) %2 value (0-255) %3'
        params:
            index:
                name: index
                type: int
                default: '4'
                field: number
                min: '4'
                max: '15'
            value:
                name: value
                type: int
                default: '0'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.write_eemem(index, value)

    def read_tolerance(self, channel: int) -> float:
        """
        label:
            en: '%1 RAB tolerance (percent) channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.read_tolerance(channel)

    def rab_actual(self, channel: int) -> float:
        """
        label:
            en: '%1 actual RAB (ohm) channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.rab_actual(channel)

    def nop(self):
        """
        label:
            en: '%1 NOP'
        """
        self._driver.nop()

    def set_rab(self, r_ab: float, r_wiper: float):
        """
        label:
            en: '%1 set RAB (ohm) %2 RW (ohm) %3'
        params:
            r_ab:
                name: r_ab
                type: float
                default: '10000.0'
                field: number
            r_wiper:
                name: r_wiper
                type: float
                default: '75.0'
                field: number
        """
        self._driver.set_rab(r_ab, r_wiper)
