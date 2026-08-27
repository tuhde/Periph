"""
file     AS5600
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.magnetometer.as5600 import AS5600Full


class AS5600:
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

    def __init__(self, bus: int = 0, address: int = 54):
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
                default: '54'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = AS5600Full(connection)

    def angle(self) -> float:
        """
        label:
            en: '%1 angle (°)'
        """
        return self._driver.angle()

    def angle_raw(self) -> int:
        """
        label:
            en: '%1 raw angle count'
        """
        return self._driver.angle_raw()

    def is_magnet_detected(self) -> bool:
        """
        label:
            en: '%1 magnet detected?'
        """
        return self._driver.is_magnet_detected()

    def is_magnet_too_strong(self) -> bool:
        """
        label:
            en: '%1 magnet too strong?'
        """
        return self._driver.is_magnet_too_strong()

    def is_magnet_too_weak(self) -> bool:
        """
        label:
            en: '%1 magnet too weak?'
        """
        return self._driver.is_magnet_too_weak()
