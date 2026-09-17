"""
file     HMC5883L
time     2026-09-16
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.magnetometer.hmc5883l import HMC5883LFull


class HMC5883L:
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

    def __init__(self, bus: int = 0, address: int = 30):
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
                default: '30'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = HMC5883LFull(connection)

    def field_x(self) -> float:
        """
        label:
            en: '%1 magnetic field X (T)'
        """
        return self._driver.magnetic_field()[0]

    def field_y(self) -> float:
        """
        label:
            en: '%1 magnetic field Y (T)'
        """
        return self._driver.magnetic_field()[1]

    def field_z(self) -> float:
        """
        label:
            en: '%1 magnetic field Z (T)'
        """
        return self._driver.magnetic_field()[2]

    def configure(self, odr: float = 15, averaging: int = 8, gain: int = 1):
        """
        label:
            en: '%1 configure ODR %2 averaging %3 gain %4'
        params:
            odr:
                name: odr
                type: float
                default: '15'
                field: number
            averaging:
                name: averaging
                type: int
                default: '8'
                field: number
            gain:
                name: gain
                type: int
                default: '1'
                field: number
        """
        self._driver.configure(odr, averaging, gain)

    def set_gain(self, gain: int = 1):
        """
        label:
            en: '%1 set gain %2'
        params:
            gain:
                name: gain
                type: int
                default: '1'
                field: number
        """
        self._driver.set_gain(gain)

    def set_mode(self, mode: int = 0):
        """
        label:
            en: '%1 set mode %2'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
        """
        self._driver.set_mode(('continuous', 'single', 'idle')[mode])

    def data_ready(self) -> bool:
        """
        label:
            en: '%1 data ready?'
        """
        return self._driver.data_ready()

    def status(self) -> int:
        """
        label:
            en: '%1 status register (raw)'
        """
        return self._driver.status()

    def single_measurement_x(self) -> float:
        """
        label:
            en: '%1 single measurement X (T)'
        """
        return self._driver.single_measurement()[0]

    def single_measurement_y(self) -> float:
        """
        label:
            en: '%1 single measurement Y (T)'
        """
        return self._driver.single_measurement()[1]

    def single_measurement_z(self) -> float:
        """
        label:
            en: '%1 single measurement Z (T)'
        """
        return self._driver.single_measurement()[2]

    def identify_id_a(self) -> int:
        """
        label:
            en: '%1 identification byte A'
        """
        return self._driver.identify()[0]

    def identify_id_b(self) -> int:
        """
        label:
            en: '%1 identification byte B'
        """
        return self._driver.identify()[1]

    def identify_id_c(self) -> int:
        """
        label:
            en: '%1 identification byte C'
        """
        return self._driver.identify()[2]

    def self_test_x(self, positive: int = 1) -> float:
        """
        label:
            en: '%1 self-test X (T) positive bias %2'
        params:
            positive:
                name: positive
                type: int
                default: '1'
                field: number
        """
        return self._driver.self_test(bool(positive))[0]

    def self_test_y(self, positive: int = 1) -> float:
        """
        label:
            en: '%1 self-test Y (T) positive bias %2'
        params:
            positive:
                name: positive
                type: int
                default: '1'
                field: number
        """
        return self._driver.self_test(bool(positive))[1]

    def self_test_z(self, positive: int = 1) -> float:
        """
        label:
            en: '%1 self-test Z (T) positive bias %2'
        params:
            positive:
                name: positive
                type: int
                default: '1'
                field: number
        """
        return self._driver.self_test(bool(positive))[2]
