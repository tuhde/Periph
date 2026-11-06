"""
file     FXOS8700
time     2026-11-05
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.accelerometer.fxos8700 import FXOS8700Full


class FXOS8700:
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

    def __init__(self, bus: int = 0, address: int = 30):
        """
        label:
            en: '%1 init I2C bus %2 address %3 (0x1C-0x1F; default 0x1E = 30)'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
                max: '7'
                min: '0'
            address:
                name: address
                type: int
                default: '30'
                field: number
                max: '31'
                min: '28'
        """
        self._driver = FXOS8700Full(I2CConnection(address, bus=bus))

    def accel_x(self) -> float:
        """
        label:
            en: '%1 acceleration X (g)'
        """
        return self._driver.read_accel()[0]

    def accel_y(self) -> float:
        """
        label:
            en: '%1 acceleration Y (g)'
        """
        return self._driver.read_accel()[1]

    def accel_z(self) -> float:
        """
        label:
            en: '%1 acceleration Z (g)'
        """
        return self._driver.read_accel()[2]

    def mag_x(self) -> float:
        """
        label:
            en: '%1 magnetic field X (µT)'
        """
        return self._driver.read_mag()[0]

    def mag_y(self) -> float:
        """
        label:
            en: '%1 magnetic field Y (µT)'
        """
        return self._driver.read_mag()[1]

    def mag_z(self) -> float:
        """
        label:
            en: '%1 magnetic field Z (µT)'
        """
        return self._driver.read_mag()[2]

    def heading(self, declination_deg: float = 0) -> float:
        """
        label:
            en: '%1 tilt-compensated heading (deg), declination %2 deg'
        params:
            declination_deg:
                name: declination_deg
                type: float
                default: '0'
                field: number
                max: '180'
                min: '-180'
        """
        return self._driver.heading(float(declination_deg))

    def temperature(self) -> float:
        """
        label:
            en: '%1 die temperature (°C, relative)'
        """
        return self._driver.read_temperature()

    def new_accel_data(self) -> bool:
        """
        label:
            en: '%1 new accelerometer data?'
        """
        return self._driver.new_accel_data()

    def new_mag_data(self) -> bool:
        """
        label:
            en: '%1 new magnetometer data?'
        """
        return self._driver.new_mag_data()

    def system_mode(self) -> int:
        """
        label:
            en: '%1 system mode (0=standby, 1=wake, 2=sleep)'
        """
        return self._driver.read_system_mode()

    def set_mode(self, mode: int = 2):
        """
        label:
            en: '%1 set mode %2 (0=accel only, 1=mag only, 2=hybrid)'
        params:
            mode:
                name: mode
                type: int
                default: '2'
                field: number
                max: '2'
                min: '0'
        """
        self._driver.set_mode(int(mode))

    def set_range(self, range_g: int = 2):
        """
        label:
            en: '%1 set accelerometer range %2 g (2, 4 or 8)'
        params:
            range_g:
                name: range_g
                type: int
                default: '2'
                field: number
                max: '8'
                min: '2'
        """
        self._driver.set_range(int(range_g))

    def set_data_rate(self, rate_hz: float = 200):
        """
        label:
            en: '%1 set system data rate %2 Hz'
        params:
            rate_hz:
                name: rate_hz
                type: float
                default: '200'
                field: number
                max: '800'
                min: '1.5625'
        """
        self._driver.set_data_rate(float(rate_hz))

    def set_accel_oversampling(self, mode: int = 0):
        """
        label:
            en: '%1 set accelerometer oversampling %2 (0=normal, 1=low-noise, 2=high-res, 3=low-power)'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                max: '3'
                min: '0'
        """
        self._driver.set_accel_oversampling(int(mode))

    def set_mag_oversampling(self, ratio: int = 7):
        """
        label:
            en: '%1 set magnetometer oversampling ratio code %2 (0-7)'
        params:
            ratio:
                name: ratio
                type: int
                default: '7'
                field: number
                max: '7'
                min: '0'
        """
        self._driver.set_mag_oversampling(int(ratio))

    def set_low_noise(self, enabled: int = 0):
        """
        label:
            en: '%1 set low-noise mode %2 (1=on, 0=off)'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_low_noise(bool(enabled))

    def standby(self):
        """
        label:
            en: '%1 enter standby'
        """
        self._driver.standby()

    def activate(self):
        """
        label:
            en: '%1 activate (leave standby)'
        """
        self._driver.activate()

    def reset(self):
        """
        label:
            en: '%1 software reset (call init afterwards)'
        """
        self._driver.reset()

    def set_accel_offset(self, x_g: float = 0, y_g: float = 0, z_g: float = 0):
        """
        label:
            en: '%1 set accelerometer offset X %2 Y %3 Z %4 (g)'
        params:
            x_g:
                name: x_g
                type: float
                default: '0'
                field: number
                max: '0.25'
                min: '-0.25'
            y_g:
                name: y_g
                type: float
                default: '0'
                field: number
                max: '0.25'
                min: '-0.25'
            z_g:
                name: z_g
                type: float
                default: '0'
                field: number
                max: '0.25'
                min: '-0.25'
        """
        self._driver.set_accel_offset(float(x_g), float(y_g), float(z_g))

    def set_mag_offset(self, x_ut: float = 0, y_ut: float = 0, z_ut: float = 0):
        """
        label:
            en: '%1 set hard-iron offset X %2 Y %3 Z %4 (µT)'
        params:
            x_ut:
                name: x_ut
                type: float
                default: '0'
                field: number
                max: '1600'
                min: '-1600'
            y_ut:
                name: y_ut
                type: float
                default: '0'
                field: number
                max: '1600'
                min: '-1600'
            z_ut:
                name: z_ut
                type: float
                default: '0'
                field: number
                max: '1600'
                min: '-1600'
        """
        self._driver.set_mag_offset(float(x_ut), float(y_ut), float(z_ut))

    def set_mag_auto_calibration(self, enabled: int = 1):
        """
        label:
            en: '%1 set hard-iron auto-calibration %2 (1=on, 0=off)'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_mag_auto_calibration(bool(enabled))

    def reset_mag_min_max(self):
        """
        label:
            en: '%1 reset magnetic min/max tracker'
        """
        self._driver.reset_mag_min_max()

    def trigger_mag_reset(self):
        """
        label:
            en: '%1 trigger one-shot magnetic reset'
        """
        self._driver.trigger_mag_reset()

    def set_fifo(self, mode: int = 0, watermark: int = 0):
        """
        label:
            en: '%1 set FIFO mode %2 (0=off, 1=circular, 2=fill, 3=trigger) watermark %3'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                max: '3'
                min: '0'
            watermark:
                name: watermark
                type: int
                default: '0'
                field: number
                max: '32'
                min: '0'
        """
        self._driver.set_fifo(int(mode), int(watermark))

    def fifo_level(self) -> int:
        """
        label:
            en: '%1 FIFO level (samples)'
        """
        return self._driver.fifo_level()

    def set_freefall_motion(self, motion: int = 1, threshold_g: float = 0.3, debounce_ms: float = 0, latch: int = 1):
        """
        label:
            en: '%1 set freefall/motion: motion %2 (1=motion, 0=freefall) threshold %3 g debounce %4 ms latch %5'
        params:
            motion:
                name: motion
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            threshold_g:
                name: threshold_g
                type: float
                default: '0.3'
                field: number
                max: '8'
                min: '0'
            debounce_ms:
                name: debounce_ms
                type: float
                default: '0'
                field: number
                max: '1000'
                min: '0'
            latch:
                name: latch
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_freefall_motion(bool(motion), threshold_g=float(threshold_g), debounce_ms=float(debounce_ms), latch=bool(latch))

    def read_motion_source(self) -> int:
        """
        label:
            en: '%1 freefall/motion source register'
        """
        return self._driver.read_motion_source()

    def set_transient(self, threshold_g: float = 0.3, debounce_ms: float = 0):
        """
        label:
            en: '%1 set transient detector threshold %2 g debounce %3 ms'
        params:
            threshold_g:
                name: threshold_g
                type: float
                default: '0.3'
                field: number
                max: '8'
                min: '0'
            debounce_ms:
                name: debounce_ms
                type: float
                default: '0'
                field: number
                max: '1000'
                min: '0'
        """
        self._driver.set_transient(threshold_g=float(threshold_g), debounce_ms=float(debounce_ms))

    def read_transient_source(self) -> int:
        """
        label:
            en: '%1 transient source register (clears interrupt)'
        """
        return self._driver.read_transient_source()

    def set_pulse(self, single: int = 4, double: int = 0):
        """
        label:
            en: '%1 set tap detection single axes %2 double axes %3 (mask X=1 Y=2 Z=4)'
        params:
            single:
                name: single
                type: int
                default: '4'
                field: number
                max: '7'
                min: '0'
            double:
                name: double
                type: int
                default: '0'
                field: number
                max: '7'
                min: '0'
        """
        self._driver.set_pulse(single=int(single), double=int(double))

    def read_pulse_source(self) -> int:
        """
        label:
            en: '%1 tap source register (clears interrupt)'
        """
        return self._driver.read_pulse_source()

    def set_orientation(self, debounce_ms: float = 100):
        """
        label:
            en: '%1 set orientation detection debounce %2 ms'
        params:
            debounce_ms:
                name: debounce_ms
                type: float
                default: '100'
                field: number
                max: '1000'
                min: '0'
        """
        self._driver.set_orientation(debounce_ms=float(debounce_ms))

    def orientation(self) -> int:
        """
        label:
            en: '%1 orientation (0=portrait up, 1=down, 2=landscape right, 3=left)'
        """
        return self._driver.read_orientation()[2]

    def set_auto_sleep(self, enabled: int = 1, sleep_rate_hz: float = 12.5, timeout_s: float = 10, wake_sources: int = 4):
        """
        label:
            en: '%1 set auto-sleep %2 (1=on) rate %3 Hz timeout %4 s wake sources %5'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            sleep_rate_hz:
                name: sleep_rate_hz
                type: float
                default: '12.5'
                field: number
                max: '50'
                min: '1.56'
            timeout_s:
                name: timeout_s
                type: float
                default: '10'
                field: number
                max: '80'
                min: '0'
            wake_sources:
                name: wake_sources
                type: int
                default: '4'
                field: number
                max: '62'
                min: '0'
        """
        self._driver.set_auto_sleep(bool(enabled), sleep_rate_hz=float(sleep_rate_hz), timeout_s=float(timeout_s), wake_sources=int(wake_sources))

    def set_interrupt_pins(self, active_high: int = 0, open_drain: int = 0):
        """
        label:
            en: '%1 set INT pins active-high %2 open-drain %3 (1=yes)'
        params:
            active_high:
                name: active_high
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            open_drain:
                name: open_drain
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_interrupt_pins(bool(active_high), bool(open_drain))

    def enable_interrupt(self, source: int = 4, pin: int = 1):
        """
        label:
            en: '%1 enable interrupt source %2 on INT pin %3'
        params:
            source:
                name: source
                type: int
                default: '4'
                field: number
                max: '255'
                min: '1'
            pin:
                name: pin
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
        """
        self._driver.enable_interrupt(int(source), int(pin))

    def disable_interrupt(self, source: int = 4):
        """
        label:
            en: '%1 disable interrupt source %2'
        params:
            source:
                name: source
                type: int
                default: '4'
                field: number
                max: '255'
                min: '1'
        """
        self._driver.disable_interrupt(int(source))

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status (clears pending sources)'
        """
        return self._driver.poll_interrupt()

    def self_test(self) -> bool:
        """
        label:
            en: '%1 accelerometer self-test passed?'
        """
        return self._driver.self_test()[0]
