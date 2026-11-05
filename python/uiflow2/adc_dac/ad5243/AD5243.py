"""
file     AD5243
time     2026-11-05
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ad5243 import AD5243Full


class AD5243:
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
        connection = I2CConnection(0x2F, bus=bus)
        self._driver = AD5243Full(connection, r_ab)

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

    def set_both(self, code1: int, code2: int):
        """
        label:
            en: '%1 set both wipers channel 0 code %2 channel 1 code %3'
        params:
            code1:
                name: code1
                type: int
                default: '128'
                field: number
                min: '0'
                max: '255'
            code2:
                name: code2
                type: int
                default: '128'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.set_both(code1, code2)

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

    def increment(self, channel: int, steps: int) -> int:
        """
        label:
            en: '%1 step wiper channel (0-1) %2 steps %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            steps:
                name: steps
                type: int
                default: '1'
                field: number
        """
        return self._driver.increment(channel, steps)

    def shutdown(self, channel: int):
        """
        label:
            en: '%1 shutdown channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.shutdown(channel)

    def resume(self, channel: int):
        """
        label:
            en: '%1 resume channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.resume(channel)

    def is_shutdown(self, channel: int) -> bool:
        """
        label:
            en: '%1 is shut down channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.is_shutdown(channel)

    def reset(self):
        """
        label:
            en: '%1 reset to midscale'
        """
        self._driver.reset()

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
                default: '160.0'
                field: number
        """
        self._driver.set_rab(r_ab, r_wiper)
