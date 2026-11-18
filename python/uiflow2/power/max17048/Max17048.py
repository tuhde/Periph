"""
file     Max17048
time     2026-11-18
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.max17048 import MAX17048Full


class Max17048:
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

    def __init__(self, bus: int = 0, address: int = 54, cells: int = 1):
        """
        label:
            en: '%1 init bus %2 address %3 cells (1 = MAX17048, 2 = MAX17049) %4'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '54'
                field: number
            cells:
                name: cells
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = MAX17048Full(connection, cells)

    def voltage(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.voltage()

    def percent(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.percent()

    def raw_voltage(self) -> int:
        """
        label:
            en: '{l}'
        """
        return self._driver.raw_voltage()

    def raw_soc(self) -> int:
        """
        label:
            en: '{l}'
        """
        return self._driver.raw_soc()

    def charge_rate(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.charge_rate()

    def version(self) -> int:
        """
        label:
            en: '{l}'
        """
        return self._driver.version()

    def chip_id(self) -> int:
        """
        label:
            en: '{l}'
        """
        return self._driver.chip_id()

    def set_rcomp(self, value: int = 151):
        """
        label:
            en: '{l}'
        params:
            value:
                name: value
                type: int
                default: '151'
                field: number
                max: '255'
                min: '0'
        """
        self._driver.set_rcomp(value)

    def get_rcomp(self) -> int:
        """
        label:
            en: '{l}'
        """
        return self._driver.get_rcomp()

    def compensate_temperature(self, celsius: float = 25):
        """
        label:
            en: '{l}'
        params:
            celsius:
                name: celsius
                type: float
                default: '25'
                field: number
        """
        self._driver.compensate_temperature(celsius)

    def set_alert_soc(self, percent: int = 4):
        """
        label:
            en: '{l}'
        params:
            percent:
                name: percent
                type: int
                default: '4'
                field: number
                max: '32'
                min: '1'
        """
        self._driver.set_alert_soc(percent)

    def get_alert_soc(self) -> int:
        """
        label:
            en: '{l}'
        """
        return self._driver.get_alert_soc()

    def set_soc_change_alert(self, enabled: int = 1):
        """
        label:
            en: '{l}'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_soc_change_alert(bool(enabled))

    def set_voltage_alert(self, min_v: float = 3.3, max_v: float = 4.25):
        """
        label:
            en: '{l}'
        params:
            min_v:
                name: min_v
                type: float
                default: '3.3'
                field: number
            max_v:
                name: max_v
                type: float
                default: '4.25'
                field: number
        """
        self._driver.set_voltage_alert(min_v, max_v)

    def set_reset_voltage(self, volts: float = 3):
        """
        label:
            en: '{l}'
        params:
            volts:
                name: volts
                type: float
                default: '3'
                field: number
        """
        self._driver.set_reset_voltage(volts)

    def get_reset_voltage(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.get_reset_voltage()

    def enable_voltage_reset_alert(self, enabled: int = 1):
        """
        label:
            en: '{l}'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.enable_voltage_reset_alert(bool(enabled))

    def set_comparator_disabled(self, disabled: int = 0):
        """
        label:
            en: '{l}'
        params:
            disabled:
                name: disabled
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_comparator_disabled(bool(disabled))

    def set_hibernate_thresholds(self, hib_thr_pct_per_h: float = 26.624, act_thr_v: float = 0.06):
        """
        label:
            en: '{l}'
        params:
            hib_thr_pct_per_h:
                name: hib_thr_pct_per_h
                type: float
                default: '26.624'
                field: number
            act_thr_v:
                name: act_thr_v
                type: float
                default: '0.06'
                field: number
        """
        self._driver.set_hibernate_thresholds(hib_thr_pct_per_h, act_thr_v)

    def disable_hibernate(self):
        """
        label:
            en: '{l}'
        """
        self._driver.disable_hibernate()

    def force_hibernate(self):
        """
        label:
            en: '{l}'
        """
        self._driver.force_hibernate()

    def is_hibernating(self) -> int:
        """
        label:
            en: '{l}'
        """
        return int(self._driver.is_hibernating())

    def enable_sleep(self, enabled: int = 1):
        """
        label:
            en: '{l}'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.enable_sleep(bool(enabled))

    def sleep(self):
        """
        label:
            en: '{l}'
        """
        self._driver.sleep()

    def wake(self):
        """
        label:
            en: '{l}'
        """
        self._driver.wake()

    def read_status(self) -> int:
        """
        label:
            en: '{l}'
        """
        return self._driver.read_status()

    def clear_status(self, mask: int = 256):
        """
        label:
            en: '{l}'
        params:
            mask:
                name: mask
                type: int
                default: '256'
                field: number
                max: '65535'
                min: '0'
        """
        self._driver.clear_status(mask)

    def poll_interrupt(self) -> int:
        """
        label:
            en: '{l}'
        """
        return self._driver.poll_interrupt()

    def quick_start(self):
        """
        label:
            en: '{l}'
        """
        self._driver.quick_start()

    def reset(self):
        """
        label:
            en: '{l}'
        """
        self._driver.reset()
