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
            en: '%1 init bus %2'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
        """
        connection = I2CConnection(0x19, bus=bus)      # accelerometer, fixed address
        mag_connection = I2CConnection(0x1E, bus=bus)  # magnetometer, fixed address, same bus
        self._driver = LSM303Full(connection, mag_connection)

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
            en: '%1 magnetic field X (µT)'
        """
        return self._driver.read_magnetic()[0]

    def mag_y(self) -> float:
        """
        label:
            en: '%1 magnetic field Y (µT)'
        """
        return self._driver.read_magnetic()[1]

    def mag_z(self) -> float:
        """
        label:
            en: '%1 magnetic field Z (µT)'
        """
        return self._driver.read_magnetic()[2]

    def heading(self) -> float:
        """
        label:
            en: '%1 tilt-compensated heading (°)'
        """
        return self._driver.heading()

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature delta (°C, relative)'
        """
        return self._driver.read_temperature_delta()

    def set_range(self, range_g: int):
        """
        label:
            en: '%1 set accelerometer range (g) range_g %2'
        params:
            range_g:
                name: range_g
                type: int
                default: '2'
                field: number
                min: '2'
                max: '16'
        """
        self._driver.set_range(range_g)

    def set_data_rate(self, rate_hz: int):
        """
        label:
            en: '%1 set accelerometer data rate (Hz) rate_hz %2'
        params:
            rate_hz:
                name: rate_hz
                type: int
                default: '100'
                field: number
        """
        self._driver.set_data_rate(rate_hz)

    def set_mag_range(self, range_ut: int):
        """
        label:
            en: '%1 set magnetometer range (µT) range_ut %2'
        params:
            range_ut:
                name: range_ut
                type: int
                default: '130'
                field: number
        """
        self._driver.set_mag_range(range_ut)

    def set_mag_data_rate(self, rate_hz: float):
        """
        label:
            en: '%1 set magnetometer data rate (Hz) rate_hz %2'
        params:
            rate_hz:
                name: rate_hz
                type: float
                default: '15'
                field: number
        """
        self._driver.set_mag_data_rate(rate_hz)
