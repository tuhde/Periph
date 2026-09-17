"""
file     L3G4200D
time     2026-09-15
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.gyroscope.l3g4200d import L3G4200DFull


class L3G4200D:
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

    def __init__(self, bus: int = 0, address: int = 0x68):
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
                default: '104'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = L3G4200DFull(connection)

    def angular_rate(self) -> tuple:
        """
        label:
            en: '%1 angular rate (rad/s)'
        """
        return self._driver.angular_rate()

    def temperature(self) -> int:
        """
        label:
            en: '%1 temperature (counts)'
        """
        return self._driver.temperature()

    def who_am_i(self) -> int:
        """
        label:
            en: '%1 WHO_AM_I'
        """
        return self._driver.who_am_i()

    def data_ready(self) -> bool:
        """
        label:
            en: '%1 data ready'
        """
        return self._driver.data_ready()

    def status(self) -> int:
        """
        label:
            en: '%1 STATUS_REG'
        """
        return self._driver.status()

    def fifo_samples(self) -> int:
        """
        label:
            en: '%1 FIFO samples'
        """
        return self._driver.fifo_samples()
