"""
file     LSM303
time     2026-10-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Full


class LSM303:
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

    def __init__(self, bus: int = 0):
        """
        label:
            en: '%1 init I2C bus %2 (accel 0x19 + mag 0x1E)'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
        """
        self._driver = LSM303Full(I2CConnection(0x19, bus=bus), I2CConnection(0x1E, bus=bus))

    def accel_x(self) -> float:
        """
        label:
            en: '%1 acceleration X (g)'
        """
        return self._driver.read_acceleration()[0]

    def accel_y(self) -> float:
        """
        label:
            en: '%1 acceleration Y (g)'
        """
        return self._driver.read_acceleration()[1]

    def accel_z(self) -> float:
        """
        label:
            en: '%1 acceleration Z (g)'
        """
        return self._driver.read_acceleration()[2]

    def mag_x(self) -> float:
        """
        label:
            en: '%1 magnetic X (uT)'
        """
        return self._driver.read_magnetic()[0]

    def mag_y(self) -> float:
        """
        label:
            en: '%1 magnetic Y (uT)'
        """
        return self._driver.read_magnetic()[1]

    def mag_z(self) -> float:
        """
        label:
            en: '%1 magnetic Z (uT)'
        """
        return self._driver.read_magnetic()[2]

    def heading(self) -> float:
        """
        label:
            en: '%1 compass heading (deg)'
        """
        return self._driver.heading()

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature delta (C)'
        """
        return self._driver.read_temperature_delta()
