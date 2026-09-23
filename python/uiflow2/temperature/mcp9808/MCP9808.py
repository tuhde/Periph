"""
file     MCP9808
time     2026-09-23
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.temperature.mcp9808 import MCP9808Full


class MCP9808:
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

    def __init__(self, bus: int = 0, address: int = 24):
        """
        label:
            en: '%1 init bus %2 address (24-31) %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '24'
                field: number
                min: '24'
                max: '31'
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = MCP9808Full(connection)

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def set_resolution(self, celsius: float):
        """
        label:
            en: '%1 set resolution (°C: 0.5/0.25/0.125/0.0625) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '0.0625'
                field: number
        """
        self._driver.set_resolution(celsius)

    def get_resolution(self) -> float:
        """
        label:
            en: '%1 resolution (°C)'
        """
        return self._driver.get_resolution()

    def shutdown(self):
        """
        label:
            en: '%1 shutdown'
        """
        self._driver.shutdown()

    def wake(self):
        """
        label:
            en: '%1 wake'
        """
        self._driver.wake()

    def is_shutdown(self) -> bool:
        """
        label:
            en: '%1 is shut down?'
        """
        return self._driver.is_shutdown()

    def set_upper_limit(self, celsius: float):
        """
        label:
            en: '%1 set upper limit (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '30.0'
                field: number
        """
        self._driver.set_upper_limit(celsius)

    def get_upper_limit(self) -> float:
        """
        label:
            en: '%1 upper limit (°C)'
        """
        return self._driver.get_upper_limit()

    def set_lower_limit(self, celsius: float):
        """
        label:
            en: '%1 set lower limit (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '10.0'
                field: number
        """
        self._driver.set_lower_limit(celsius)

    def get_lower_limit(self) -> float:
        """
        label:
            en: '%1 lower limit (°C)'
        """
        return self._driver.get_lower_limit()

    def set_critical_limit(self, celsius: float):
        """
        label:
            en: '%1 set critical limit (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '45.0'
                field: number
        """
        self._driver.set_critical_limit(celsius)

    def get_critical_limit(self) -> float:
        """
        label:
            en: '%1 critical limit (°C)'
        """
        return self._driver.get_critical_limit()

    def set_hysteresis(self, celsius: float):
        """
        label:
            en: '%1 set hysteresis (°C: 0/1.5/3.0/6.0) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '0.0'
                field: number
        """
        self._driver.set_hysteresis(celsius)

    def get_hysteresis(self) -> float:
        """
        label:
            en: '%1 hysteresis (°C)'
        """
        return self._driver.get_hysteresis()

    def lock_critical_limit(self):
        """
        label:
            en: '%1 lock critical limit (until power cycle)'
        """
        self._driver.lock_critical_limit()

    def lock_window_limits(self):
        """
        label:
            en: '%1 lock window limits (until power cycle)'
        """
        self._driver.lock_window_limits()

    def is_critical_limit_locked(self) -> bool:
        """
        label:
            en: '%1 critical limit locked?'
        """
        return self._driver.is_critical_limit_locked()

    def is_window_limits_locked(self) -> bool:
        """
        label:
            en: '%1 window limits locked?'
        """
        return self._driver.is_window_limits_locked()

    def configure_alert(self, mode: int, output: int, polarity: int):
        """
        label:
            en: '%1 configure alert mode (0=all 1=critical only) %2 output (0=comparator 1=interrupt) %3 polarity (0=active low 1=active high) %4'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            output:
                name: output
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            polarity:
                name: polarity
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.configure_alert(('all', 'critical_only')[mode],
                                     ('comparator', 'interrupt')[output],
                                     ('active_low', 'active_high')[polarity])

    def enable_alert(self):
        """
        label:
            en: '%1 enable alert'
        """
        self._driver.enable_alert()

    def disable_alert(self):
        """
        label:
            en: '%1 disable alert'
        """
        self._driver.disable_alert()

    def is_alert_asserted(self) -> bool:
        """
        label:
            en: '%1 alert asserted?'
        """
        return self._driver.is_alert_asserted()

    def clear_interrupt(self):
        """
        label:
            en: '%1 clear interrupt'
        """
        self._driver.clear_interrupt()

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 boundary status (1=lower 2=upper 4=critical)'
        """
        return self._driver.poll_interrupt()
