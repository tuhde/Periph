"""
file     DRV8830
time     2026-09-23
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.motor.drv8830 import DRV8830Full


class DRV8830:
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

    def __init__(self, bus: int = 0, address: int = 96):
        """
        label:
            en: '%1 init bus %2 address (96-104) %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '96'
                field: number
                min: '96'
                max: '104'
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = DRV8830Full(connection)

    def drive(self, voltage: float):
        """
        label:
            en: '%1 drive voltage (V, negative = reverse) %2'
        params:
            voltage:
                name: voltage
                type: float
                default: '3.0'
                field: number
        """
        self._driver.drive(voltage)

    def brake(self):
        """
        label:
            en: '%1 brake'
        """
        self._driver.brake()

    def stop(self):
        """
        label:
            en: '%1 stop (coast)'
        """
        self._driver.stop()

    def set_output(self, vset: int, in1: int, in2: int):
        """
        label:
            en: '%1 set output VSET (6-63) %2 IN1 (0/1) %3 IN2 (0/1) %4'
        params:
            vset:
                name: vset
                type: int
                default: '37'
                field: number
                min: '6'
                max: '63'
            in1:
                name: in1
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
            in2:
                name: in2
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_output(vset, bool(in1), bool(in2))

    def output_voltage(self) -> float:
        """
        label:
            en: '%1 commanded voltage (V)'
        """
        return self._driver.read_output()[0]

    def output_direction(self) -> str:
        """
        label:
            en: '%1 direction'
        """
        return self._driver.read_output()[1]

    def fault(self) -> bool:
        """
        label:
            en: '%1 fault'
        """
        return self._driver.read_fault()[0]

    def ocp(self) -> bool:
        """
        label:
            en: '%1 overcurrent (OCP)'
        """
        return self._driver.read_fault()[1]

    def uvlo(self) -> bool:
        """
        label:
            en: '%1 undervoltage (UVLO)'
        """
        return self._driver.read_fault()[2]

    def ots(self) -> bool:
        """
        label:
            en: '%1 overtemperature (OTS)'
        """
        return self._driver.read_fault()[3]

    def ilimit(self) -> bool:
        """
        label:
            en: '%1 current limit (ILIMIT)'
        """
        return self._driver.read_fault()[4]

    def clear_fault(self):
        """
        label:
            en: '%1 clear fault'
        """
        self._driver.clear_fault()

    def poll_interrupt(self) -> bool:
        """
        label:
            en: '%1 poll fault'
        """
        return self._driver.poll_interrupt()[0]
