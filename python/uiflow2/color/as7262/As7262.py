"""
file     As7262
time     2026-11-19
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.color.as7262 import AS7262Full


class As7262:
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

    def __init__(self, bus: int = 0, address: int = 73):
        """
        label:
            en: '%1 init bus %2 address %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '73'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = AS7262Full(connection)

    def measure(self):
        """
        label:
            en: '%1 measure (one-shot, wait for result)'
        """
        self._driver.read()

    def channel(self, index: int = 0) -> float:
        """
        label:
            en: '%1 channel %2 calibrated value (0=V 1=B 2=G 3=Y 4=O 5=R)'
        params:
            index:
                name: index
                type: int
                default: '0'
                field: number
                max: '5'
                min: '0'
        """
        return self._driver.read_channel(index, True)

    def raw_channel(self, index: int = 0) -> int:
        """
        label:
            en: '%1 channel %2 raw count (0=V 1=B 2=G 3=Y 4=O 5=R)'
        params:
            index:
                name: index
                type: int
                default: '0'
                field: number
                max: '5'
                min: '0'
        """
        return self._driver.read_channel(index, False)

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.temperature()

    def data_ready(self) -> bool:
        """
        label:
            en: '%1 data ready'
        """
        return self._driver.data_ready()

    def set_gain(self, gain: int = 2):
        """
        label:
            en: '%1 gain %2 (0=1x, 1=3.7x, 2=16x, 3=64x)'
        params:
            gain:
                name: gain
                type: int
                default: '2'
                field: number
                max: '3'
                min: '0'
        """
        self._driver.set_gain((0, 3.7, 16, 64)[gain])

    def set_integration_time(self, ms: int = 165):
        """
        label:
            en: '%1 integration time %2 ms (2.8 to 714)'
        params:
            ms:
                name: ms
                type: int
                default: '165'
                field: number
                max: '714'
                min: '3'
        """
        self._driver.set_integration_time(ms)

    def set_mode(self, mode: int = 3):
        """
        label:
            en: '%1 bank mode %2 (0-2 continuous, 3 one-shot)'
        params:
            mode:
                name: mode
                type: int
                default: '3'
                field: number
                max: '3'
                min: '0'
        """
        self._driver.set_mode(mode)

    def set_led(self, current_ma: int = 0):
        """
        label:
            en: '%1 driver LED current %2 mA (12.5/25/50/100, 0=off)'
        params:
            current_ma:
                name: current_ma
                type: int
                default: '0'
                field: number
                max: '100'
                min: '0'
        """
        self._driver.set_led(current_ma > 0, current_ma or 12.5)

    def set_indicator_led(self, current_ma: int = 0):
        """
        label:
            en: '%1 indicator LED current %2 mA (1/2/4/8, 0=off)'
        params:
            current_ma:
                name: current_ma
                type: int
                default: '0'
                field: number
                max: '8'
                min: '0'
        """
        self._driver.set_indicator_led(current_ma > 0, current_ma or 1)

    def soft_reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.soft_reset()
