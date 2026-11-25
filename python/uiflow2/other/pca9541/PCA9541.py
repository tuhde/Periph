"""
file     PCA9541
time     2026-11-13
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.other.pca9541 import PCA9541Full


class PCA9541:
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

    def __init__(self, bus: int = 0, address: int = 112):
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
                default: '112'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = PCA9541Full(connection)

    def has_control(self) -> bool:
        """
        label:
            en: '%1 this master has the bus'
        """
        return self._driver.has_control()

    def acquire(self, bus_init: int, timeout_ms: float) -> bool:
        """
        label:
            en: '%1 take the bus (1 = with bus init) %2 timeout (ms) %3'
        params:
            bus_init:
                name: bus_init
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            timeout_ms:
                name: timeout_ms
                type: float
                default: '20.0'
                field: number
        """
        return self._driver.acquire(bool(bus_init), timeout_ms)

    def release(self):
        """
        label:
            en: '%1 give the bus to the other master'
        """
        self._driver.release()

    def recover_bus(self, timeout_ms: float) -> bool:
        """
        label:
            en: '%1 recover and take the bus timeout (ms) %2'
        params:
            timeout_ms:
                name: timeout_ms
                type: float
                default: '20.0'
                field: number
        """
        return self._driver.recover_bus(timeout_ms)

    def is_connected(self) -> bool:
        """
        label:
            en: '%1 downstream bus connected'
        """
        return self._driver.is_connected()

    def connect(self):
        """
        label:
            en: '%1 connect downstream bus'
        """
        self._driver.connect()

    def disconnect(self):
        """
        label:
            en: '%1 disconnect downstream bus'
        """
        self._driver.disconnect()

    def get_control(self) -> int:
        """
        label:
            en: '%1 CONTROL register'
        """
        return self._driver.get_control()

    def set_interrupt_mask(self, bus_lost: int, bus_not_idle: int, bus_init: int, int_in: int):
        """
        label:
            en: '%1 mask interrupts (1 = masked) bus lost %2 bus not idle %3 bus init %4 INT_IN %5'
        params:
            bus_lost:
                name: bus_lost
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            bus_not_idle:
                name: bus_not_idle
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            bus_init:
                name: bus_init
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            int_in:
                name: int_in
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_interrupt_mask(bool(bus_lost), bool(bus_not_idle), bool(bus_init), bool(int_in))

    def get_interrupt_mask(self) -> int:
        """
        label:
            en: '%1 interrupt mask'
        """
        return self._driver.get_interrupt_mask()

    def test_interrupt(self, own: int, other: int):
        """
        label:
            en: '%1 INT test (1 = drive low) own %2 other %3'
        params:
            own:
                name: own
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            other:
                name: other
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.test_interrupt(bool(own), bool(other))

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status'
        """
        return self._driver.poll_interrupt()
