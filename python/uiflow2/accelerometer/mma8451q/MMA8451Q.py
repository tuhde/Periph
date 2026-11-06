"""
file     MMA8451Q
time     2026-11-03
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.accelerometer.mma8451q import MMA8451QFull


class MMA8451Q:
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

    def __init__(self, bus: int = 0, address: int = 28):
        """
        label:
            en: '%1 init bus %2 address %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
            address:
                name: address
                type: int
                default: '28'
                field: number
                min: '0'
                max: '127'
        """
        self._driver = MMA8451QFull(I2CConnection(address, bus=bus))

    def x(self) -> float:
        """
        label:
            en: '%1 acceleration X (g)'
        """
        return self._driver.read()[0]

    def y(self) -> float:
        """
        label:
            en: '%1 acceleration Y (g)'
        """
        return self._driver.read()[1]

    def z(self) -> float:
        """
        label:
            en: '%1 acceleration Z (g)'
        """
        return self._driver.read()[2]

    def raw_x(self) -> int:
        """
        label:
            en: '%1 raw X (counts)'
        """
        return self._driver.read_raw()[0]

    def raw_y(self) -> int:
        """
        label:
            en: '%1 raw Y (counts)'
        """
        return self._driver.read_raw()[1]

    def raw_z(self) -> int:
        """
        label:
            en: '%1 raw Z (counts)'
        """
        return self._driver.read_raw()[2]

    def new_data_available(self) -> bool:
        """
        label:
            en: '%1 new data available'
        """
        return self._driver.new_data_available()

    def read_status(self) -> int:
        """
        label:
            en: '%1 STATUS byte'
        """
        return self._driver.read_status()

    def read_system_mode(self) -> int:
        """
        label:
            en: '%1 system mode (0=standby, 1=wake, 2=sleep)'
        """
        return self._driver.read_system_mode()

    def offset_x(self) -> float:
        """
        label:
            en: '%1 X offset (g)'
        """
        return self._driver.get_offset()[0]

    def offset_y(self) -> float:
        """
        label:
            en: '%1 Y offset (g)'
        """
        return self._driver.get_offset()[1]

    def offset_z(self) -> float:
        """
        label:
            en: '%1 Z offset (g)'
        """
        return self._driver.get_offset()[2]

    def fifo_level(self) -> int:
        """
        label:
            en: '%1 FIFO level (0-32)'
        """
        return self._driver.fifo_level()

    def fifo_overflow(self) -> bool:
        """
        label:
            en: '%1 FIFO overflow'
        """
        return self._driver.read_fifo_status()[0]

    def fifo_watermark(self) -> bool:
        """
        label:
            en: '%1 FIFO watermark reached'
        """
        return self._driver.read_fifo_status()[1]

    def fifo_count(self) -> int:
        """
        label:
            en: '%1 FIFO sample count (0-32)'
        """
        return self._driver.read_fifo_status()[2]

    def fifo_x(self) -> float:
        """
        label:
            en: '%1 FIFO acceleration X (g)'
        """
        return self._driver.read_fifo()[0][0]

    def fifo_y(self) -> float:
        """
        label:
            en: '%1 FIFO acceleration Y (g)'
        """
        return self._driver.read_fifo()[0][1]

    def fifo_z(self) -> float:
        """
        label:
            en: '%1 FIFO acceleration Z (g)'
        """
        return self._driver.read_fifo()[0][2]

    def motion_source(self) -> int:
        """
        label:
            en: '%1 freefall / motion source byte'
        """
        return self._driver.read_motion_source()

    def transient_source(self) -> int:
        """
        label:
            en: '%1 transient source byte'
        """
        return self._driver.read_transient_source()

    def pulse_source(self) -> int:
        """
        label:
            en: '%1 tap source byte'
        """
        return self._driver.read_pulse_source()

    def orientation_changed(self) -> bool:
        """
        label:
            en: '%1 orientation changed'
        """
        return self._driver.read_orientation()[0]

    def orientation_lockout(self) -> bool:
        """
        label:
            en: '%1 orientation lockout'
        """
        return self._driver.read_orientation()[1]

    def orientation_lapo(self) -> int:
        """
        label:
            en: '%1 portrait / landscape (0-3)'
        """
        return self._driver.read_orientation()[2]

    def orientation_back(self) -> bool:
        """
        label:
            en: '%1 back facing'
        """
        return self._driver.read_orientation()[3]

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status byte'
        """
        return self._driver.poll_interrupt()

    def self_test_ok(self) -> bool:
        """
        label:
            en: '%1 self-test passed'
        """
        return self._driver.self_test()[0]

    def self_test_dx(self) -> int:
        """
        label:
            en: '%1 self-test X delta (counts)'
        """
        return self._driver.self_test()[1]

    def self_test_dy(self) -> int:
        """
        label:
            en: '%1 self-test Y delta (counts)'
        """
        return self._driver.self_test()[2]

    def self_test_dz(self) -> int:
        """
        label:
            en: '%1 self-test Z delta (counts)'
        """
        return self._driver.self_test()[3]

    def set_range(self, range_g: int):
        """
        label:
            en: '%1 set range (g: 2, 4, 8) %2'
        params:
            range_g:
                name: range_g
                type: int
                default: '2'
                field: number
                min: '2'
                max: '8'
        """
        self._driver.set_range(range_g)

    def set_data_rate(self, rate_hz: float):
        """
        label:
            en: '%1 set data rate (Hz: 800, 400, 200, 100, 50, 12.5, 6.25, 1.56) %2'
        params:
            rate_hz:
                name: rate_hz
                type: float
                default: '100'
                field: number
                min: '1'
                max: '800'
        """
        self._driver.set_data_rate(rate_hz)

    def set_oversampling(self, mode: int, sleep_mode: int = 0):
        """
        label:
            en: '%1 set oversampling mode (0 normal, 1 low-noise, 2 high-res, 3 low-power) %2 sleep mode %3'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
            sleep_mode:
                name: sleep_mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_oversampling(mode, sleep_mode)

    def set_low_noise(self, enabled: int):
        """
        label:
            en: '%1 set low-noise mode (1/0) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_low_noise(bool(enabled))

    def set_resolution(self, bits: int):
        """
        label:
            en: '%1 set resolution (bits: 8 or 14) %2'
        params:
            bits:
                name: bits
                type: int
                default: '14'
                field: number
                min: '8'
                max: '14'
        """
        self._driver.set_resolution(bits)

    def set_high_pass_output(self, enabled: int, cutoff: int = 0):
        """
        label:
            en: '%1 set high-pass output (1/0) %2 cutoff (0-3) %3'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            cutoff:
                name: cutoff
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_high_pass_output(bool(enabled), cutoff)

    def set_offset(self, x_g: float, y_g: float, z_g: float):
        """
        label:
            en: '%1 set offsets X %2 g Y %3 g Z %4 g'
        params:
            x_g:
                name: x_g
                type: float
                default: '0'
                field: number
                min: '-0.256'
                max: '0.256'
            y_g:
                name: y_g
                type: float
                default: '0'
                field: number
                min: '-0.256'
                max: '0.256'
            z_g:
                name: z_g
                type: float
                default: '0'
                field: number
                min: '-0.256'
                max: '0.256'
        """
        self._driver.set_offset(x_g, y_g, z_g)

    def set_fifo(self, mode: int, watermark: int = 0):
        """
        label:
            en: '%1 set FIFO mode (0 off, 1 circular, 2 fill, 3 trigger) %2 watermark %3'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
            watermark:
                name: watermark
                type: int
                default: '0'
                field: number
                min: '0'
                max: '32'
        """
        self._driver.set_fifo(mode, watermark)

    def set_fifo_trigger(self, transient: int = 0, orientation: int = 0,
                         pulse: int = 0, motion: int = 0):
        """
        label:
            en: '%1 set FIFO trigger transient %2 orientation %3 pulse %4 motion %5'
        params:
            transient:
                name: transient
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            orientation:
                name: orientation
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            pulse:
                name: pulse
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            motion:
                name: motion
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_fifo_trigger(bool(transient), bool(orientation),
                                      bool(pulse), bool(motion))

    def set_freefall_motion(self, motion: int, axes: int = 7,
                            threshold_g: float = 0.0, debounce_ms: float = 0.0,
                            latch: int = 0):
        """
        label:
            en: '%1 set freefall / motion (1=motion) %2 axes %3 threshold %4 g debounce %5 ms latch %6'
        params:
            motion:
                name: motion
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            axes:
                name: axes
                type: int
                default: '7'
                field: number
                min: '0'
                max: '7'
            threshold_g:
                name: threshold_g
                type: float
                default: '0.2'
                field: number
                min: '0'
                max: '8'
            debounce_ms:
                name: debounce_ms
                type: float
                default: '0'
                field: number
                min: '0'
                max: '2550'
            latch:
                name: latch
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_freefall_motion(bool(motion), axes, threshold_g,
                                         debounce_ms, bool(latch))

    def set_transient(self, axes: int = 7, threshold_g: float = 0.0,
                      debounce_ms: float = 0.0, bypass_hpf: int = 0,
                      latch: int = 0):
        """
        label:
            en: '%1 set transient axes %2 threshold %3 g debounce %4 ms bypass HPF %5 latch %6'
        params:
            axes:
                name: axes
                type: int
                default: '7'
                field: number
                min: '0'
                max: '7'
            threshold_g:
                name: threshold_g
                type: float
                default: '0.3'
                field: number
                min: '0'
                max: '8'
            debounce_ms:
                name: debounce_ms
                type: float
                default: '0'
                field: number
                min: '0'
                max: '2550'
            bypass_hpf:
                name: bypass_hpf
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            latch:
                name: latch
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_transient(axes, threshold_g, debounce_ms,
                                   bool(bypass_hpf), bool(latch))

    def set_pulse(self, single: int = 0, double: int = 0,
                  time_limit_ms: float = 30.0, latency_ms: float = 100.0,
                  window_ms: float = 300.0, latch: int = 0):
        """
        label:
            en: '%1 set tap single %2 double %3 time limit %4 ms latency %5 ms window %6 ms latch %7'
        params:
            single:
                name: single
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
            double:
                name: double
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
            time_limit_ms:
                name: time_limit_ms
                type: float
                default: '30'
                field: number
                min: '0'
                max: '255'
            latency_ms:
                name: latency_ms
                type: float
                default: '100'
                field: number
                min: '0'
                max: '255'
            window_ms:
                name: window_ms
                type: float
                default: '300'
                field: number
                min: '0'
                max: '255'
            latch:
                name: latch
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_pulse(single, double, time_limit_ms=time_limit_ms,
                               latency_ms=latency_ms, window_ms=window_ms,
                               latch=bool(latch))

    def set_orientation(self, enabled: int = 1, debounce_ms: float = 0.0,
                        threshold_deg: int = 45, hysteresis: int = 4,
                        z_lock: int = 4, back_front: int = 1):
        """
        label:
            en: '%1 set orientation (1/0) %2 debounce %3 ms threshold %4 deg hysteresis %5 z-lock %6 back/front %7'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
            debounce_ms:
                name: debounce_ms
                type: float
                default: '0'
                field: number
                min: '0'
                max: '2550'
            threshold_deg:
                name: threshold_deg
                type: int
                default: '45'
                field: number
                min: '15'
                max: '75'
            hysteresis:
                name: hysteresis
                type: int
                default: '4'
                field: number
                min: '0'
                max: '7'
            z_lock:
                name: z_lock
                type: int
                default: '4'
                field: number
                min: '0'
                max: '7'
            back_front:
                name: back_front
                type: int
                default: '1'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_orientation(bool(enabled), debounce_ms, threshold_deg,
                                     hysteresis, z_lock, back_front)

    def set_auto_sleep(self, enabled: int, sleep_rate_hz: float = 50.0,
                       timeout_s: float = 10.0, wake_sources: int = 0):
        """
        label:
            en: '%1 set auto-sleep (1/0) %2 sleep rate %3 Hz timeout %4 s wake sources %5'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            sleep_rate_hz:
                name: sleep_rate_hz
                type: float
                default: '50'
                field: number
                min: '1'
                max: '50'
            timeout_s:
                name: timeout_s
                type: float
                default: '10'
                field: number
                min: '0'
                max: '162'
            wake_sources:
                name: wake_sources
                type: int
                default: '0'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.set_auto_sleep(bool(enabled), sleep_rate_hz, timeout_s,
                                    wake_sources)

    def set_fifo_gate(self, enabled: int):
        """
        label:
            en: '%1 set FIFO gate (1/0) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_fifo_gate(bool(enabled))

    def set_interrupt_pins(self, active_high: int = 0, open_drain: int = 0):
        """
        label:
            en: '%1 set interrupt pins active high %2 open drain %3'
        params:
            active_high:
                name: active_high
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            open_drain:
                name: open_drain
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_interrupt_pins(bool(active_high), bool(open_drain))

    def enable_interrupt(self, source: int, pin: int = 1):
        """
        label:
            en: '%1 enable interrupt source %2 pin %3'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
                min: '1'
                max: '128'
            pin:
                name: pin
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        self._driver.enable_interrupt(source, pin)

    def disable_interrupt(self, source: int):
        """
        label:
            en: '%1 disable interrupt source %2'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
                min: '1'
                max: '128'
        """
        self._driver.disable_interrupt(source)

    def standby(self):
        """
        label:
            en: '%1 standby'
        """
        self._driver.standby()

    def activate(self):
        """
        label:
            en: '%1 activate'
        """
        self._driver.activate()

    def reset(self):
        """
        label:
            en: '%1 software reset'
        """
        self._driver.reset()

    def off_interrupt(self, pin: int = 1):
        """
        label:
            en: '%1 stop interrupt subscription pin %2'
        params:
            pin:
                name: pin
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        self._driver.off_interrupt(pin)
