"""
file     MPU6050
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.mpu6050 import MPU6050Full


class MPU6050:
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

    def __init__(self, bus: int = 0, address: int = 104):
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
        self._driver = MPU6050Full(connection)

    def accel_x(self) -> float:
        """
        label:
            en: '%1 acceleration X (m/s²)'
        """
        return self._driver.accel()[0]

    def accel_y(self) -> float:
        """
        label:
            en: '%1 acceleration Y (m/s²)'
        """
        return self._driver.accel()[1]

    def accel_z(self) -> float:
        """
        label:
            en: '%1 acceleration Z (m/s²)'
        """
        return self._driver.accel()[2]

    def gyro_x(self) -> float:
        """
        label:
            en: '%1 angular rate X (rad/s)'
        """
        return self._driver.gyro()[0]

    def gyro_y(self) -> float:
        """
        label:
            en: '%1 angular rate Y (rad/s)'
        """
        return self._driver.gyro()[1]

    def gyro_z(self) -> float:
        """
        label:
            en: '%1 angular rate Z (rad/s)'
        """
        return self._driver.gyro()[2]

    def temperature(self) -> float:
        """
        label:
            en: '%1 die temperature (°C)'
        """
        return self._driver.temperature()

    def data_ready(self) -> bool:
        """
        label:
            en: '%1 new data ready?'
        """
        return self._driver.data_ready()
