"""
file     TMC2209
time     2026-11-09
author
email
license  Apache License 2.0
"""

from periph.connection.uart_auto import UARTConnection
from periph.chips.motor.tmc2209 import TMC2209Full


class TMC2209:
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

    def __init__(self, port: int = 1, baudrate: int = 115200, tx: int = 17, rx: int = 16, r_sense: float = 0.11, address: int = 0):
        """
        label:
            en: '%1 init UART port %2 baudrate %3 TX pin %4 RX pin %5 sense resistor (ohm) %6 node address %7'
        params:
            port:
                name: port
                type: int
                default: '1'
                field: number
            baudrate:
                name: baudrate
                type: int
                default: '115200'
                field: number
            tx:
                name: tx
                type: int
                default: '17'
                field: number
            rx:
                name: rx
                type: int
                default: '16'
                field: number
            r_sense:
                name: r_sense
                type: float
                default: '0.11'
                field: number
            address:
                name: address
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        connection = UARTConnection(port=port, baudrate=baudrate, tx=tx, rx=rx)
        self._driver = TMC2209Full(connection, r_sense_ohm=r_sense, address=address)

    def enable(self):
        """
        label:
            en: '%1 enable driver'
        """
        self._driver.enable()

    def disable(self):
        """
        label:
            en: '%1 disable driver'
        """
        self._driver.disable()

    def set_current(self, current: float):
        """
        label:
            en: '%1 set run current (A RMS) %2'
        params:
            current:
                name: current
                type: float
                default: '0.5'
                field: number
        """
        self._driver.set_current(current)

    def set_hold_current(self, current: float):
        """
        label:
            en: '%1 set hold current (A RMS) %2'
        params:
            current:
                name: current
                type: float
                default: '0.15'
                field: number
        """
        self._driver.set_hold_current(current)

    def run(self, speed: float):
        """
        label:
            en: '%1 run (full steps/s, negative = reverse) %2'
        params:
            speed:
                name: speed
                type: float
                default: '200'
                field: number
        """
        self._driver.run(speed)

    def stop(self):
        """
        label:
            en: '%1 stop'
        """
        self._driver.stop()

    def set_microsteps(self, n: int):
        """
        label:
            en: '%1 set microsteps (1-256) %2'
        params:
            n:
                name: n
                type: int
                default: '16'
                field: number
                min: '1'
                max: '256'
        """
        self._driver.set_microsteps(n)

    def set_interpolation(self, enabled: int):
        """
        label:
            en: '%1 set interpolation (0/1) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_interpolation(bool(enabled))

    def set_chopper_mode(self, spreadcycle: int):
        """
        label:
            en: '%1 set SpreadCycle (0 = StealthChop, 1 = SpreadCycle) %2'
        params:
            spreadcycle:
                name: spreadcycle
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_chopper_mode(bool(spreadcycle))

    def set_stealthchop_threshold(self, tpwmthrs: int):
        """
        label:
            en: '%1 set StealthChop threshold (TPWMTHRS) %2'
        params:
            tpwmthrs:
                name: tpwmthrs
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1048575'
        """
        self._driver.set_stealthchop_threshold(tpwmthrs)

    def set_direction_inverted(self, inverted: int):
        """
        label:
            en: '%1 invert direction (0/1) %2'
        params:
            inverted:
                name: inverted
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_direction_inverted(bool(inverted))

    def set_freewheel(self, mode: int):
        """
        label:
            en: '%1 set freewheel mode (0-3) %2'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_freewheel(mode)

    def standstill(self) -> bool:
        """
        label:
            en: '%1 standstill'
        """
        return self._driver.is_standstill()

    def fault(self) -> int:
        """
        label:
            en: '%1 fault bitmask'
        """
        return self._driver.read_fault()

    def microstep_position(self) -> int:
        """
        label:
            en: '%1 microstep position (0-1023)'
        """
        return self._driver.microstep_position()

    def current_scale(self) -> int:
        """
        label:
            en: '%1 current scale (0-31)'
        """
        return self._driver.read_current_scale()

    def tstep(self) -> int:
        """
        label:
            en: '%1 TSTEP'
        """
        return self._driver.read_tstep()

    def temperature_flags(self) -> int:
        """
        label:
            en: '%1 temperature flags (0-15)'
        """
        return self._driver.read_temperature_flags()

    def ifcnt(self) -> int:
        """
        label:
            en: '%1 write counter (IFCNT)'
        """
        return self._driver.read_ifcnt()

    def set_stall_threshold(self, sgthrs: int):
        """
        label:
            en: '%1 set StallGuard threshold (SGTHRS) %2'
        params:
            sgthrs:
                name: sgthrs
                type: int
                default: '60'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.set_stall_threshold(sgthrs)

    def set_coolstep_threshold(self, tcoolthrs: int):
        """
        label:
            en: '%1 set CoolStep/stall threshold (TCOOLTHRS) %2'
        params:
            tcoolthrs:
                name: tcoolthrs
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1048575'
        """
        self._driver.set_coolstep_threshold(tcoolthrs)

    def read_stallguard(self) -> int:
        """
        label:
            en: '%1 StallGuard result (SG_RESULT)'
        """

        return self._driver.read_stallguard()
