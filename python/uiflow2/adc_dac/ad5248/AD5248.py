"""
file     AD5248
time     2026-11-05
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ad5248 import AD5248Full


class AD5248:
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

    def __init__(self, bus: int = 0, address: int = 44, r_ab: float = 10000.0):
        """
        label:
            en: '%1 init bus %2 address (44-47) %3 RAB ohms %4'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '44'
                field: number
                min: '44'
                max: '47'
            r_ab:
                name: r_ab
                type: float
                default: '10000.0'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = AD5248Full(connection, r_ab=r_ab)

    def set_wiper(self, channel: int, code: int):
        """
        label:
            en: '%1 set wiper (channel 0-1) %2 code (0-255) %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
            code:
                name: code
                type: int
                default: '128'
                field: number
        """
        self._driver.set_wiper(channel, code)

    def get_wiper(self, channel: int) -> int:
        """
        label:
            en: '%1 wiper code (channel 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
        """
        return self._driver.get_wiper(channel)

    def set_resistance(self, channel: int, ohms: float):
        """
        label:
            en: '%1 set W-B resistance (channel 0-1) %2 ohms %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
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
            en: '%1 W-B resistance in ohms (channel 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
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
            code2:
                name: code2
                type: int
                default: '128'
                field: number
        """
        self._driver.set_both(code1, code2)

    def set_ratio(self, channel: int, ratio: float):
        """
        label:
            en: '%1 set wiper ratio (channel 0-1) %2 ratio (0-1) %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
            ratio:
                name: ratio
                type: float
                default: '0.5'
                field: number
        """
        self._driver.set_ratio(channel, ratio)

    def get_ratio(self, channel: int) -> float:
        """
        label:
            en: '%1 wiper ratio (channel 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
        """
        return self._driver.get_ratio(channel)

    def increment(self, channel: int, steps: int) -> int:
        """
        label:
            en: '%1 increment wiper, returns new code (channel 0-1) %2 steps %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
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
        """
        self._driver.resume(channel)

    def is_shutdown(self, channel: int) -> bool:
        """
        label:
            en: '%1 channel in shutdown? (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
        """
        return self._driver.is_shutdown(channel)

    def reset(self):
        """
        label:
            en: '%1 reset both wipers to midscale'
        """
        self._driver.reset()
