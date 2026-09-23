"""
file     VL53L0X
time     2026-09-23
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.tof.vl53l0x import VL53L0XFull


class VL53L0X:
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

    def __init__(self, bus: int = 0, address: int = 41):
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
                default: '41'
                field: number
                min: '8'
                max: '119'
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = VL53L0XFull(connection)

    def distance(self) -> int:
        """
        label:
            en: '%1 distance (mm)'
        """
        return self._driver.distance()

    def range_valid(self) -> bool:
        """
        label:
            en: '%1 last range valid?'
        """
        return self._driver.range_valid()

    def start_continuous(self, period_ms: int):
        """
        label:
            en: '%1 start continuous, period (ms, 0=back-to-back) %2'
        params:
            period_ms:
                name: period_ms
                type: int
                default: '0'
                field: number
                min: '0'
        """
        self._driver.start_continuous(period_ms)

    def stop_continuous(self):
        """
        label:
            en: '%1 stop continuous'
        """
        self._driver.stop_continuous()

    def read_continuous(self) -> int:
        """
        label:
            en: '%1 continuous distance (mm)'
        """
        return self._driver.read_continuous()

    def data_ready(self) -> bool:
        """
        label:
            en: '%1 data ready?'
        """
        return self._driver.data_ready()

    def read_measurement(self) -> list:
        """
        label:
            en: '%1 measurement [distance mm, status, signal MCPS, ambient MCPS, SPADs]'
        """
        m = self._driver.read_measurement()
        return [m['distance_mm'], m['range_status'], m['signal_rate_mcps'], m['ambient_rate_mcps'], m['effective_spad_count']]

    def range_status(self) -> int:
        """
        label:
            en: '%1 range status (11=valid)'
        """
        return self._driver.range_status()

    def set_timing_budget(self, budget_us: int):
        """
        label:
            en: '%1 set timing budget (µs) %2'
        params:
            budget_us:
                name: budget_us
                type: int
                default: '33000'
                field: number
                min: '20000'
        """
        self._driver.set_timing_budget(budget_us)

    def timing_budget(self) -> int:
        """
        label:
            en: '%1 timing budget (µs)'
        """
        return self._driver.timing_budget()

    def set_signal_rate_limit(self, limit_mcps: float):
        """
        label:
            en: '%1 set signal rate limit (MCPS) %2'
        params:
            limit_mcps:
                name: limit_mcps
                type: float
                default: '0.25'
                field: number
                min: '0'
                max: '511'
        """
        self._driver.set_signal_rate_limit(limit_mcps)

    def signal_rate_limit(self) -> float:
        """
        label:
            en: '%1 signal rate limit (MCPS)'
        """
        return self._driver.signal_rate_limit()

    def set_vcsel_pulse_period(self, period_type: int, pclks: int):
        """
        label:
            en: '%1 set VCSEL period, type (0=pre-range 1=final-range) %2 PCLKs %3'
        params:
            period_type:
                name: period_type
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            pclks:
                name: pclks
                type: int
                default: '14'
                field: number
                min: '8'
                max: '18'
        """
        self._driver.set_vcsel_pulse_period(('pre_range', 'final_range')[period_type], pclks)

    def vcsel_pulse_period(self, period_type: int) -> int:
        """
        label:
            en: '%1 VCSEL period (PCLKs), type (0=pre-range 1=final-range) %2'
        params:
            period_type:
                name: period_type
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.vcsel_pulse_period(('pre_range', 'final_range')[period_type])

    def set_profile(self, profile: int):
        """
        label:
            en: '%1 set profile (0=default 1=long range 2=high speed 3=high accuracy) %2'
        params:
            profile:
                name: profile
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_profile(('default', 'long_range', 'high_speed', 'high_accuracy')[profile])

    def set_offset(self, offset_mm: float):
        """
        label:
            en: '%1 set offset (mm) %2'
        params:
            offset_mm:
                name: offset_mm
                type: float
                default: '0.0'
                field: number
                min: '-512'
                max: '511'
        """
        self._driver.set_offset(offset_mm)

    def offset(self) -> float:
        """
        label:
            en: '%1 offset (mm)'
        """
        return self._driver.offset()

    def set_crosstalk_compensation(self, rate_mcps: float):
        """
        label:
            en: '%1 set crosstalk compensation (MCPS, 0=off) %2'
        params:
            rate_mcps:
                name: rate_mcps
                type: float
                default: '0.0'
                field: number
                min: '0'
                max: '7'
        """
        self._driver.set_crosstalk_compensation(rate_mcps)

    def recalibrate(self):
        """
        label:
            en: '%1 recalibrate'
        """
        self._driver.recalibrate()

    def set_address(self, address: int):
        """
        label:
            en: '%1 set I2C address %2'
        params:
            address:
                name: address
                type: int
                default: '48'
                field: number
                min: '8'
                max: '119'
        """
        self._driver.set_address(address)

    def set_interrupt_thresholds(self, low_mm: int, high_mm: int):
        """
        label:
            en: '%1 set thresholds low (mm) %2 high (mm) %3'
        params:
            low_mm:
                name: low_mm
                type: int
                default: '100'
                field: number
                min: '0'
                max: '8190'
            high_mm:
                name: high_mm
                type: int
                default: '800'
                field: number
                min: '0'
                max: '8190'
        """
        self._driver.set_interrupt_thresholds(low_mm, high_mm)

    def interrupt_thresholds(self) -> list:
        """
        label:
            en: '%1 thresholds [low mm, high mm]'
        """
        return list(self._driver.interrupt_thresholds())

    def model_id(self) -> int:
        """
        label:
            en: '%1 model ID'
        """
        return self._driver.model_id()

    def revision_id(self) -> int:
        """
        label:
            en: '%1 revision ID'
        """
        return self._driver.revision_id()

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status (1=low 2=high 3=window 4=sample)'
        """
        return self._driver.poll_interrupt()

    def enable_interrupt(self, source: int):
        """
        label:
            en: '%1 enable interrupt source (1=low 2=high 3=window 4=sample) %2'
        params:
            source:
                name: source
                type: int
                default: '4'
                field: number
                min: '1'
                max: '4'
        """
        self._driver.enable_interrupt(source)

    def disable_interrupt(self, source: int):
        """
        label:
            en: '%1 disable interrupt source (1=low 2=high 3=window 4=sample) %2'
        params:
            source:
                name: source
                type: int
                default: '4'
                field: number
                min: '1'
                max: '4'
        """
        self._driver.disable_interrupt(source)
