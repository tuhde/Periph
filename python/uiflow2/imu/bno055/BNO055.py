"""
file     BNO055
time     2026-11-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.bno055 import BNO055Full


class BNO055:
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

    def __init__(self, bus: int = 0, address: int = 41):
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
                default: '41'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = BNO055Full(connection)

    def heading(self) -> float:
        """
        label:
            en: '%1 heading (rad)'
        """
        return self._driver.euler()[0]

    def roll(self) -> float:
        """
        label:
            en: '%1 roll (rad)'
        """
        return self._driver.euler()[1]

    def pitch(self) -> float:
        """
        label:
            en: '%1 pitch (rad)'
        """
        return self._driver.euler()[2]

    def quaternion_w(self) -> float:
        """
        label:
            en: '%1 quaternion W'
        """
        return self._driver.quaternion()[0]

    def quaternion_x(self) -> float:
        """
        label:
            en: '%1 quaternion X'
        """
        return self._driver.quaternion()[1]

    def quaternion_y(self) -> float:
        """
        label:
            en: '%1 quaternion Y'
        """
        return self._driver.quaternion()[2]

    def quaternion_z(self) -> float:
        """
        label:
            en: '%1 quaternion Z'
        """
        return self._driver.quaternion()[3]

    def linear_accel_x(self) -> float:
        """
        label:
            en: '%1 linear acceleration X (m/s²)'
        """
        return self._driver.linear_acceleration()[0]

    def linear_accel_y(self) -> float:
        """
        label:
            en: '%1 linear acceleration Y (m/s²)'
        """
        return self._driver.linear_acceleration()[1]

    def linear_accel_z(self) -> float:
        """
        label:
            en: '%1 linear acceleration Z (m/s²)'
        """
        return self._driver.linear_acceleration()[2]

    def gravity_x(self) -> float:
        """
        label:
            en: '%1 gravity X (m/s²)'
        """
        return self._driver.gravity()[0]

    def gravity_y(self) -> float:
        """
        label:
            en: '%1 gravity Y (m/s²)'
        """
        return self._driver.gravity()[1]

    def gravity_z(self) -> float:
        """
        label:
            en: '%1 gravity Z (m/s²)'
        """
        return self._driver.gravity()[2]

    def temperature(self) -> int:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.temperature()

    def calib_system(self) -> int:
        """
        label:
            en: '%1 system calibration (0-3)'
        """
        return self._driver.calibration_status()[0]

    def calib_gyroscope(self) -> int:
        """
        label:
            en: '%1 gyroscope calibration (0-3)'
        """
        return self._driver.calibration_status()[1]

    def calib_accelerometer(self) -> int:
        """
        label:
            en: '%1 accelerometer calibration (0-3)'
        """
        return self._driver.calibration_status()[2]

    def calib_magnetometer(self) -> int:
        """
        label:
            en: '%1 magnetometer calibration (0-3)'
        """
        return self._driver.calibration_status()[3]

    def is_calibrated(self) -> bool:
        """
        label:
            en: '%1 fully calibrated?'
        """
        return self._driver.is_calibrated()

    def get_mode(self) -> int:
        """
        label:
            en: '%1 operation mode'
        """
        return self._driver.get_mode()

    def set_mode(self, mode: int = 12):
        """
        label:
            en: '%1 set operation mode (0=config 8=IMU 12=NDOF) %2'
        params:
            mode:
                name: mode
                type: int
                default: '12'
                field: number
                max: '12'
                min: '0'
        """
        self._driver.set_mode(mode)

    def set_axis_remap(self, config: int = 36, sign: int = 0):
        """
        label:
            en: '%1 axis remap config %2 sign %3'
        params:
            config:
                name: config
                type: int
                default: '36'
                field: number
                max: '63'
                min: '0'
            sign:
                name: sign
                type: int
                default: '0'
                field: number
                max: '7'
                min: '0'
        """
        self._driver.set_axis_remap(config, sign)

    def set_power_mode(self, mode: int = 0):
        """
        label:
            en: '%1 power mode (0=normal 1=low power 2=suspend) %2'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                max: '2'
                min: '0'
        """
        self._driver.set_power_mode(['normal', 'low_power', 'suspend'][mode])

    def system_status(self) -> int:
        """
        label:
            en: '%1 system status'
        """
        return self._driver.system_status()[0]

    def system_error(self) -> int:
        """
        label:
            en: '%1 system error'
        """
        return self._driver.system_status()[1]

    def self_test_passed(self) -> bool:
        """
        label:
            en: '%1 self-test passed?'
        """
        return all(self._driver.self_test().values())

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status flags'
        """
        return self._driver.poll_interrupt()

    def reset(self):
        """
        label:
            en: '%1 reset chip'
        """
        self._driver.reset()
