"""
file     QMI8658A
time     2026-11-08
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.qmi8658a import QMI8658AFull


class QMI8658A:
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

    def __init__(self, bus: int = 0, address: int = 107):
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
                default: '107'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = QMI8658AFull(connection)

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

    def timestamp(self) -> int:
        """
        label:
            en: '%1 sample counter'
        """
        return self._driver.timestamp()

    def configure_accel(self, full_scale: int = 0, odr: int = 5):
        """
        label:
            en: '%1 set accel range (0=±2 1=±4 2=±8 3=±16 g) %2 ODR code (5=224 Hz) %3'
        params:
            full_scale:
                name: full_scale
                type: int
                default: '0'
                field: number
                max: '3'
                min: '0'
            odr:
                name: odr
                type: int
                default: '5'
                field: number
                max: '15'
                min: '0'
        """
        self._driver.configure_accel(full_scale, odr)

    def configure_gyro(self, full_scale: int = 4, odr: int = 5):
        """
        label:
            en: '%1 set gyro range (0=±16 … 7=±2048 dps) %2 ODR code (5=224 Hz) %3'
        params:
            full_scale:
                name: full_scale
                type: int
                default: '4'
                field: number
                max: '7'
                min: '0'
            odr:
                name: odr
                type: int
                default: '5'
                field: number
                max: '8'
                min: '0'
        """
        self._driver.configure_gyro(full_scale, odr)

    def set_sensors(self, accel: int = 1, gyro: int = 1):
        """
        label:
            en: '%1 enable accel (0/1) %2 gyro (0/1) %3'
        params:
            accel:
                name: accel
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            gyro:
                name: gyro
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_sensors(bool(accel), bool(gyro))

    def set_power_down(self, down: int = 1):
        """
        label:
            en: '%1 power down (1) or wake (0) %2'
        params:
            down:
                name: down
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_power_down(bool(down))

    def fifo_count(self) -> int:
        """
        label:
            en: '%1 FIFO bytes available'
        """
        return self._driver.fifo_count()

    def reset_fifo(self):
        """
        label:
            en: '%1 reset FIFO'
        """
        self._driver.reset_fifo()

    def enable_pedometer(self, enabled: int = 1):
        """
        label:
            en: '%1 pedometer on (1) or off (0) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.enable_pedometer(bool(enabled))

    def step_count(self) -> int:
        """
        label:
            en: '%1 step count'
        """
        return self._driver.step_count()

    def reset_step_count(self):
        """
        label:
            en: '%1 reset step count'
        """
        self._driver.reset_step_count()

    def enable_tap(self, enabled: int = 1):
        """
        label:
            en: '%1 tap detection on (1) or off (0) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.enable_tap(bool(enabled))

    def tap_count(self) -> int:
        """
        label:
            en: '%1 last tap count (0 none, 1 single, 2 double)'
        """
        return self._driver.tap_status()[0]

    def tap_axis(self) -> int:
        """
        label:
            en: '%1 last tap axis (0 none, 1 X, 2 Y, 3 Z)'
        """
        return self._driver.tap_status()[1]

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status flags'
        """
        return self._driver.poll_interrupt()

    def set_wake_on_motion(self, threshold_mg: int = 100):
        """
        label:
            en: '%1 wake-on-motion threshold mg (0 = off) %2'
        params:
            threshold_mg:
                name: threshold_mg
                type: int
                default: '100'
                field: number
                max: '255'
                min: '0'
        """
        self._driver.set_wake_on_motion(threshold_mg)

    def reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.reset()
