"""
file     ICM20948
time     2026-11-05
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.icm20948 import ICM20948Full


class ICM20948:
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
            en: '%1 init bus %2 address (104-105) %3'
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
                min: '104'
                max: '105'
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = ICM20948Full(connection)

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

    def enable_mag(self):
        """
        label:
            en: '%1 enable magnetometer'
        """
        self._driver.enable_mag()

    def mag_x(self) -> float:
        """
        label:
            en: '%1 magnetic field X (µT)'
        """
        return self._driver.mag()[0]

    def mag_y(self) -> float:
        """
        label:
            en: '%1 magnetic field Y (µT)'
        """
        return self._driver.mag()[1]

    def mag_z(self) -> float:
        """
        label:
            en: '%1 magnetic field Z (µT)'
        """
        return self._driver.mag()[2]

    def mag_data_ready(self) -> bool:
        """
        label:
            en: '%1 magnetometer data ready?'
        """
        return self._driver.mag_data_ready()

    def configure_gyro(self, full_scale: int):
        """
        label:
            en: '%1 gyro range (0=±250 1=±500 2=±1000 3=±2000 dps) %2'
        params:
            full_scale:
                name: full_scale
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.configure_gyro(full_scale)

    def configure_accel(self, full_scale: int):
        """
        label:
            en: '%1 accel range (0=±2 1=±4 2=±8 3=±16 g) %2'
        params:
            full_scale:
                name: full_scale
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.configure_accel(full_scale)

    def configure_filters(self, gyro_dlpf: int, accel_dlpf: int):
        """
        label:
            en: '%1 filters gyro DLPF (0-7) %2 accel DLPF (0-7) %3'
        params:
            gyro_dlpf:
                name: gyro_dlpf
                type: int
                default: '3'
                field: number
                min: '0'
                max: '7'
            accel_dlpf:
                name: accel_dlpf
                type: int
                default: '3'
                field: number
                min: '0'
                max: '7'
        """
        self._driver.configure_filters(gyro_dlpf, accel_dlpf)

    def configure_sample_rate(self, gyro_div: int, accel_div: int):
        """
        label:
            en: '%1 sample rate dividers gyro (0-255) %2 accel (0-4095) %3'
        params:
            gyro_div:
                name: gyro_div
                type: int
                default: '10'
                field: number
                min: '0'
                max: '255'
            accel_div:
                name: accel_div
                type: int
                default: '10'
                field: number
                min: '0'
                max: '4095'
        """
        self._driver.configure_sample_rate(gyro_div, accel_div)

    def set_sleep(self, sleep: int):
        """
        label:
            en: '%1 sleep (0=wake 1=sleep) %2'
        params:
            sleep:
                name: sleep
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_sleep(bool(sleep))

    def set_wake_on_motion(self, threshold_mg: float):
        """
        label:
            en: '%1 wake-on-motion threshold (mg) %2'
        params:
            threshold_mg:
                name: threshold_mg
                type: float
                default: '200.0'
                field: number
                min: '0'
                max: '1020'
        """
        self._driver.set_wake_on_motion(threshold_mg)

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status (1=data 2=motion 4=I2C master 8=PLL 16=FIFO overflow)'
        """
        return self._driver.poll_interrupt()

    def enable_fifo(self):
        """
        label:
            en: '%1 enable FIFO (accel + gyro)'
        """
        self._driver.enable_fifo(True, True, False)

    def fifo_count(self) -> int:
        """
        label:
            en: '%1 FIFO byte count'
        """
        return self._driver.fifo_count()

    def reset_fifo(self):
        """
        label:
            en: '%1 reset FIFO'
        """
        self._driver.reset_fifo()