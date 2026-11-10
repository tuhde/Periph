"""
file     TMC2130
time     2026-11-09
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.motor.tmc2130 import TMC2130Full


class TMC2130:
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

    def __init__(self, bus: int = 1, cs_pin: int = 5, step_pin: int = 6, dir_pin: int = 9, en_pin: int = 4, r_sense: float = 0.11):
        """
        label:
            en: '%1 init SPI bus %2 CS pin %3 STEP pin %4 DIR pin %5 ENN pin %6 sense resistor (ohm) %7'
        params:
            bus:
                name: bus
                type: int
                default: '1'
                field: number
            cs_pin:
                name: cs_pin
                type: int
                default: '5'
                field: number
            step_pin:
                name: step_pin
                type: int
                default: '6'
                field: number
            dir_pin:
                name: dir_pin
                type: int
                default: '9'
                field: number
            en_pin:
                name: en_pin
                type: int
                default: '4'
                field: number
            r_sense:
                name: r_sense
                type: float
                default: '0.11'
                field: number
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin, polarity=1, phase=1)
        self._driver = TMC2130Full(connection, MicroPythonOutputPin(step_pin), MicroPythonOutputPin(dir_pin),
                                   r_sense_ohm=r_sense, en_pin=MicroPythonOutputPin(en_pin))

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
                default: '0.2'
                field: number
        """
        self._driver.set_hold_current(current)

    def move(self, steps: int, speed: float):
        """
        label:
            en: '%1 move full steps %2 at full steps/s %3'
        params:
            steps:
                name: steps
                type: int
                default: '400'
                field: number
                min: '-1000000'
                max: '1000000'
            speed:
                name: speed
                type: float
                default: '200'
                field: number
        """
        self._driver.move(steps, speed)

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
            en: '%1 set SpreadCycle (1) / StealthChop (0) %2'
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
                default: '500'
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
            en: '%1 set standstill mode (0-3) %2'
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

    def set_stall_threshold(self, sgt: int):
        """
        label:
            en: '%1 set StallGuard threshold (SGT) %2'
        params:
            sgt:
                name: sgt
                type: int
                default: '0'
                field: number
                min: '-64'
                max: '63'
        """
        self._driver.set_stall_threshold(sgt)

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

    def is_standstill(self) -> bool:
        """
        label:
            en: '%1 is standstill'
        """

        return self._driver.is_standstill()

    def read_fault(self) -> int:
        """
        label:
            en: '%1 fault flags'
        """

        return self._driver.read_fault()

    def read_stallguard(self) -> int:
        """
        label:
            en: '%1 StallGuard result (SG_RESULT)'
        """

        return self._driver.read_stallguard()

    def is_stalled(self) -> bool:
        """
        label:
            en: '%1 is stalled'
        """

        return self._driver.is_stalled()

    def microstep_position(self) -> int:
        """
        label:
            en: '%1 microstep position'
        """

        return self._driver.microstep_position()

    def read_current_scale(self) -> int:
        """
        label:
            en: '%1 current scale'
        """

        return self._driver.read_current_scale()

    def read_tstep(self) -> int:
        """
        label:
            en: '%1 TSTEP'
        """

        return self._driver.read_tstep()
