"""
file     Ms5611
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.ms5611 import MS5611Full


class Ms5611:
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

    def __init__(self, bus: int = 0, address: int = 119):
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
                default: '119'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = MS5611Full(connection)
        self._driver.init()

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def pressure(self) -> float:
        """
        label:
            en: '%1 pressure (Pa)'
        """
        return self._driver.read_pressure()

    def altitude(self) -> float:
        """
        label:
            en: '%1 altitude vs 101325 Pa (m)'
        """
        return self._driver.altitude()

    def set_oversampling(self, osr: int = 4096):
        """
        label:
            en: '%1 oversampling ratio (256-4096) %2'
        params:
            osr:
                name: osr
                type: int
                default: '4096'
                field: number
                max: '4096'
                min: '256'
        """
        self._driver.set_oversampling(osr)

    def serial_code(self) -> int:
        """
        label:
            en: '%1 serial code'
        """
        return self._driver.serial_code()

    def prom_crc_ok(self) -> int:
        """
        label:
            en: '%1 PROM CRC ok (1=yes 0=no)'
        """
        return int(self._driver.prom_crc_ok())

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
