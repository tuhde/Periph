"""
file     RDA5807M
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.comms.rda5807m import RDA5807MFull


class RDA5807M:
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

    def __init__(self, bus: int = 0, address: int = 0, frequency_mhz: float = 100.0, volume: int = 8):
        """
        label:
            en: '%1 init bus %2 address %3 frequency_mhz %4 volume %5'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '0'
                field: number
            frequency_mhz:
                name: frequency_mhz
                type: float
                default: '100'
                field: number
            volume:
                name: volume
                type: int
                default: '8'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = RDA5807MFull(connection, frequency_mhz, volume)

    def set_frequency(self, frequency_mhz: float):
        """
        label:
            en: '%1 tune to frequency (MHz) frequency_mhz %2'
        params:
            frequency_mhz:
                name: frequency_mhz
                type: float
                field: number
        """
        self._driver.set_frequency(frequency_mhz)

    def frequency(self) -> float:
        """
        label:
            en: '%1 current frequency (MHz)'
        """
        return self._driver.frequency()

    def set_volume(self, level: int):
        """
        label:
            en: '%1 set volume (0-15) level %2'
        params:
            level:
                name: level
                type: int
                field: number
        """
        self._driver.set_volume(level)

    def mute(self, enable: int):
        """
        label:
            en: '%1 mute (1) / unmute (0) enable %2'
        params:
            enable:
                name: enable
                type: int
                field: number
        """
        self._driver.mute(bool(enable))

    def seek(self, up: int) -> float:
        """
        label:
            en: '%1 seek next station (MHz), up (1) / down (0) up %2'
        params:
            up:
                name: up
                type: int
                field: number
        """
        return self._driver.seek(up=bool(up))
