"""
file     TMC5160
time     2026-11-09
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.motor.tmc5160 import TMC5160Full


class TMC5160:
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

    def __init__(self, bus: int = 1, cs_pin: int = 5, en_pin: int = 4, r_sense: float = 0.075):
        """
        label:
            en: '%1 init SPI bus %2 CS pin %3 ENN pin %4 sense resistor (ohm) %5'
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
            en_pin:
                name: en_pin
                type: int
                default: '4'
                field: number
            r_sense:
                name: r_sense
                type: float
                default: '0.075'
                field: number
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin, polarity=1, phase=1)
        self._driver = TMC5160Full(connection, r_sense_ohm=r_sense, en_pin=MicroPythonOutputPin(en_pin))

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

    def set_speed(self, speed: float):
        """
        label:
            en: '%1 set speed (full steps/s) %2'
        params:
            speed:
                name: speed
                type: float
                default: '200'
                field: number
        """
        self._driver.set_speed(speed)

    def set_acceleration(self, accel: float):
        """
        label:
            en: '%1 set acceleration (full steps/s2) %2'
        params:
            accel:
                name: accel
                type: float
                default: '400'
                field: number
        """
        self._driver.set_acceleration(accel)

    def move_to(self, position: float):
        """
        label:
            en: '%1 move to position (full steps) %2'
        params:
            position:
                name: position
                type: float
                default: '0'
                field: number
        """
        self._driver.move_to(position)

    def move(self, steps: float):
        """
        label:
            en: '%1 move relative and wait (full steps) %2'
        params:
            steps:
                name: steps
                type: float
                default: '400'
                field: number
        """
        self._driver.move(steps)

    def run(self, velocity: float):
        """
        label:
            en: '%1 run at velocity (full steps/s) %2'
        params:
            velocity:
                name: velocity
                type: float
                default: '200'
                field: number
        """
        self._driver.run(velocity)

    def stop(self):
        """
        label:
            en: '%1 stop'
        """
        self._driver.stop()

    def set_position(self, position: float):
        """
        label:
            en: '%1 define position (full steps) %2'
        params:
            position:
                name: position
                type: float
                default: '0'
                field: number
        """
        self._driver.set_position(position)

    def set_microsteps(self, n: int):
        """
        label:
            en: '%1 set microsteps (1-256) %2'
        params:
            n:
                name: n
                type: int
                default: '256'
                field: number
                min: '1'
                max: '256'
        """
        self._driver.set_microsteps(n)

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

    def enable_stall_stop(self, enabled: int):
        """
        label:
            en: '%1 stop on stall (0/1) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.enable_stall_stop(bool(enabled))

    def clear_ramp_events(self):
        """
        label:
            en: '%1 clear ramp events'
        """
        self._driver.clear_ramp_events()

    def position(self) -> float:
        """
        label:
            en: '%1 position (full steps)'
        """

        return self._driver.position()

    def velocity(self) -> float:
        """
        label:
            en: '%1 velocity (full steps/s)'
        """

        return self._driver.velocity()

    def is_position_reached(self) -> bool:
        """
        label:
            en: '%1 position reached'
        """

        return self._driver.is_position_reached()

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

    def read_current_scale(self) -> int:
        """
        label:
            en: '%1 current scale'
        """

        return self._driver.read_current_scale()
