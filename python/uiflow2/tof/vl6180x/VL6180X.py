"""
file     VL6180X
time     2026-11-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.tof.vl6180x import VL6180XFull


class VL6180X:
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
        connection = I2CConnection(address, bus=bus, reg_bytes=2)
        self._driver = VL6180XFull(connection)

    def range(self) -> int:
        """
        label:
            en: '%1 range (mm)'
        """
        return self._driver.read_range()

    def range_error(self) -> int:
        """
        label:
            en: '%1 single-shot range error code (0=valid)'
        """
        return self._driver.read_range_status()[1]

    def lux(self) -> float:
        """
        label:
            en: '%1 ambient light (lux)'
        """
        return self._driver.read_lux()

    def als_raw(self) -> int:
        """
        label:
            en: '%1 ALS raw count'
        """
        return self._driver.read_als_raw()

    def start_continuous_range(self, period_ms: int = 50):
        """
        label:
            en: '%1 start continuous range, period (ms, 10-2550) %2'
        params:
            period_ms:
                name: period_ms
                type: int
                default: '50'
                field: number
                max: '2550'
                min: '10'
        """
        self._driver.start_continuous_range(period_ms)

    def stop_continuous_range(self):
        """
        label:
            en: '%1 stop continuous range'
        """
        self._driver.stop_continuous_range()

    def range_continuous(self) -> int:
        """
        label:
            en: '%1 next continuous range (mm)'
        """
        return self._driver.read_range_continuous()

    def start_continuous_als(self, period_ms: int = 100):
        """
        label:
            en: '%1 start continuous ALS, period (ms, 10-2550) %2'
        params:
            period_ms:
                name: period_ms
                type: int
                default: '100'
                field: number
                max: '2550'
                min: '10'
        """
        self._driver.start_continuous_als(period_ms)

    def stop_continuous_als(self):
        """
        label:
            en: '%1 stop continuous ALS'
        """
        self._driver.stop_continuous_als()

    def set_interleaved(self, enabled: int = 0):
        """
        label:
            en: '%1 interleaved ALS+range (1=on 0=off) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_interleaved(bool(enabled))

    def set_als_gain(self, code: int = 6):
        """
        label:
            en: '%1 ALS gain code (0=20 6=1 7=40) %2'
        params:
            code:
                name: code
                type: int
                default: '6'
                field: number
                max: '7'
                min: '0'
        """
        self._driver.set_als_gain(code)

    def set_als_integration_time(self, ms: int = 100):
        """
        label:
            en: '%1 ALS integration time (ms) %2'
        params:
            ms:
                name: ms
                type: int
                default: '100'
                field: number
                max: '512'
                min: '1'
        """
        self._driver.set_als_integration_time(ms)

    def set_als_scaler(self, scaler: int = 1):
        """
        label:
            en: '%1 ALS scaler %2'
        params:
            scaler:
                name: scaler
                type: int
                default: '1'
                field: number
                max: '16'
                min: '1'
        """
        self._driver.set_als_scaler(scaler)

    def set_range_thresholds(self, low: int = 30, high: int = 80):
        """
        label:
            en: '%1 range thresholds low (mm) %2 high (mm) %3'
        params:
            low:
                name: low
                type: int
                default: '30'
                field: number
                max: '255'
                min: '0'
            high:
                name: high
                type: int
                default: '80'
                field: number
                max: '255'
                min: '0'
        """
        self._driver.set_range_thresholds(low, high)

    def set_als_thresholds(self, low: int = 0, high: int = 65535):
        """
        label:
            en: '%1 ALS thresholds low %2 high %3'
        params:
            low:
                name: low
                type: int
                default: '0'
                field: number
                max: '65535'
                min: '0'
            high:
                name: high
                type: int
                default: '65535'
                field: number
                max: '65535'
                min: '0'
        """
        self._driver.set_als_thresholds(low, high)

    def set_max_convergence_time(self, ms: int = 49):
        """
        label:
            en: '%1 max convergence time (ms) %2'
        params:
            ms:
                name: ms
                type: int
                default: '49'
                field: number
                max: '63'
                min: '1'
        """
        self._driver.set_max_convergence_time(ms)

    def set_averaging_period(self, units: int = 48):
        """
        label:
            en: '%1 averaging period (units of 64.5 us) %2'
        params:
            units:
                name: units
                type: int
                default: '48'
                field: number
                max: '255'
                min: '0'
        """
        self._driver.set_averaging_period(units)

    def set_range_offset(self, mm: int = 0):
        """
        label:
            en: '%1 range offset (mm) %2'
        params:
            mm:
                name: mm
                type: int
                default: '0'
                field: number
                max: '127'
                min: '-128'
        """
        self._driver.set_range_offset(mm)

    def range_offset(self) -> int:
        """
        label:
            en: '%1 range offset (mm)'
        """
        return self._driver.get_range_offset()

    def set_crosstalk_rate(self, rate: float = 0):
        """
        label:
            en: '%1 crosstalk rate (Mcps) %2'
        params:
            rate:
                name: rate
                type: float
                default: '0'
                field: number
                max: '500'
                min: '0'
        """
        self._driver.set_crosstalk_rate(rate)

    def recalibrate_vhv(self):
        """
        label:
            en: '%1 recalibrate VHV'
        """
        self._driver.recalibrate_vhv()

    def set_address(self, address: int = 48):
        """
        label:
            en: '%1 device address (volatile) %2'
        params:
            address:
                name: address
                type: int
                default: '48'
                field: number
                max: '119'
                min: '8'
        """
        self._driver.set_address(address)

    def model_id(self) -> int:
        """
        label:
            en: '%1 model ID'
        """
        return self._driver.model_id()

    def revision(self) -> int:
        """
        label:
            en: '%1 revision (major*10+minor)'
        """
        return self._driver.revision()[0] * 10 + self._driver.revision()[1]

    def history_entry(self, index: int = 0) -> int:
        """
        label:
            en: '%1 history entry %2'
        params:
            index:
                name: index
                type: int
                default: '0'
                field: number
                max: '7'
                min: '0'
        """
        return self._driver.read_history()[index]

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 poll interrupt (1=range 2=ALS 4=error)'
        """
        return self._driver.poll_interrupt()

    def enable_interrupt(self, source: int = 1, mode: int = 4):
        """
        label:
            en: '%1 enable interrupt source (1=range 2=ALS) %2 mode (1=low 2=high 3=window 4=new sample) %3'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
            mode:
                name: mode
                type: int
                default: '4'
                field: number
                max: '4'
                min: '1'
        """
        self._driver.enable_interrupt(source, mode)

    def disable_interrupt(self, source: int = 1):
        """
        label:
            en: '%1 disable interrupt source (1=range 2=ALS) %2'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
        """
        self._driver.disable_interrupt(source)
