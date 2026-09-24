"""
file     VL53L1X
time     2026-09-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.tof.vl53l1x import VL53L1XFull


class VL53L1X:
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
        self._driver = VL53L1XFull(connection)

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
            en: '%1 start continuous, period (ms, 0=as fast as budget) %2'
        params:
            period_ms:
                name: period_ms
                type: int
                default: '0'
                field: number
                min: '0'
                max: '60000'
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
            en: '%1 range status (0=valid)'
        """
        return self._driver.range_status()

    def set_timing_budget(self, budget_us: int):
        """
        label:
            en: '%1 set timing budget (µs: 15000 short only, 20000, 33000, 50000, 100000, 200000, 500000) %2'
        params:
            budget_us:
                name: budget_us
                type: int
                default: '100000'
                field: number
                min: '15000'
                max: '500000'
        """
        self._driver.set_timing_budget(budget_us)

    def timing_budget(self) -> int:
        """
        label:
            en: '%1 timing budget (µs)'
        """
        return self._driver.timing_budget()

    def set_distance_mode(self, mode: int):
        """
        label:
            en: '%1 set distance mode (0=short 1=long) %2'
        params:
            mode:
                name: mode
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_distance_mode(('short', 'long')[mode])

    def distance_mode(self) -> int:
        """
        label:
            en: '%1 distance mode (0=short 1=long)'
        """
        return ('short', 'long').index(self._driver.distance_mode())

    def set_inter_measurement(self, period_ms: int):
        """
        label:
            en: '%1 set inter-measurement period (ms) %2'
        params:
            period_ms:
                name: period_ms
                type: int
                default: '100'
                field: number
                min: '1'
                max: '60000'
        """
        self._driver.set_inter_measurement(period_ms)

    def inter_measurement(self) -> int:
        """
        label:
            en: '%1 inter-measurement period (ms)'
        """
        return self._driver.inter_measurement()

    def set_signal_rate_limit(self, limit_mcps: float):
        """
        label:
            en: '%1 set signal rate limit (MCPS) %2'
        params:
            limit_mcps:
                name: limit_mcps
                type: float
                default: '1.0'
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

    def set_sigma_threshold(self, sigma_mm: int):
        """
        label:
            en: '%1 set sigma threshold (mm) %2'
        params:
            sigma_mm:
                name: sigma_mm
                type: int
                default: '90'
                field: number
                min: '0'
                max: '16383'
        """
        self._driver.set_sigma_threshold(sigma_mm)

    def sigma_threshold(self) -> int:
        """
        label:
            en: '%1 sigma threshold (mm)'
        """
        return self._driver.sigma_threshold()

    def set_roi(self, width: int, height: int):
        """
        label:
            en: '%1 set ROI width (SPADs) %2 height (SPADs) %3'
        params:
            width:
                name: width
                type: int
                default: '16'
                field: number
                min: '4'
                max: '16'
            height:
                name: height
                type: int
                default: '16'
                field: number
                min: '4'
                max: '16'
        """
        self._driver.set_roi(width, height)

    def roi(self) -> list:
        """
        label:
            en: '%1 ROI [width, height]'
        """
        return list(self._driver.roi())

    def set_roi_center(self, spad: int):
        """
        label:
            en: '%1 set ROI centre SPAD (199=centre) %2'
        params:
            spad:
                name: spad
                type: int
                default: '199'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.set_roi_center(spad)

    def roi_center(self) -> int:
        """
        label:
            en: '%1 ROI centre SPAD'
        """
        return self._driver.roi_center()

    def optical_center(self) -> int:
        """
        label:
            en: '%1 optical centre SPAD'
        """
        return self._driver.optical_center()

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
                min: '-1024'
                max: '1023'
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
                max: '0.127'
        """
        self._driver.set_crosstalk_compensation(rate_mcps)

    def crosstalk_compensation(self) -> float:
        """
        label:
            en: '%1 crosstalk compensation (MCPS)'
        """
        return self._driver.crosstalk_compensation()

    def calibrate_offset(self, target_mm: int) -> float:
        """
        label:
            en: '%1 calibrate offset, target (mm) %2'
        params:
            target_mm:
                name: target_mm
                type: int
                default: '140'
                field: number
                min: '1'
                max: '4000'
        """
        return self._driver.calibrate_offset(target_mm)

    def calibrate_crosstalk(self, target_mm: int) -> float:
        """
        label:
            en: '%1 calibrate crosstalk, target (mm) %2'
        params:
            target_mm:
                name: target_mm
                type: int
                default: '600'
                field: number
                min: '1'
                max: '4000'
        """
        return self._driver.calibrate_crosstalk(target_mm)

    def recalibrate(self):
        """
        label:
            en: '%1 temperature update'
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
                max: '65535'
            high_mm:
                name: high_mm
                type: int
                default: '800'
                field: number
                min: '0'
                max: '65535'
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

    def module_type(self) -> int:
        """
        label:
            en: '%1 module type'
        """
        return self._driver.module_type()

    def revision_id(self) -> int:
        """
        label:
            en: '%1 revision ID'
        """
        return self._driver.revision_id()

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status (1=low 2=high 3=out 4=sample 5=in)'
        """
        return self._driver.poll_interrupt()

    def enable_interrupt(self, source: int):
        """
        label:
            en: '%1 enable interrupt source (1=low 2=high 3=out 4=sample 5=in) %2'
        params:
            source:
                name: source
                type: int
                default: '4'
                field: number
                min: '1'
                max: '5'
        """
        self._driver.enable_interrupt(source)

    def disable_interrupt(self, source: int):
        """
        label:
            en: '%1 disable interrupt source (1=low 2=high 3=out 4=sample 5=in) %2'
        params:
            source:
                name: source
                type: int
                default: '4'
                field: number
                min: '1'
                max: '5'
        """
        self._driver.disable_interrupt(source)
