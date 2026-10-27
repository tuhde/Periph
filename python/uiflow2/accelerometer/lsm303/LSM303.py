"""
file     LSM303
time     2026-10-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Full


class LSM303:
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

    def __init__(self, bus: int = 0, accel_address: int = 25, mag_address: int = 30):
        """
        label:
            en: '%1 init I2C bus %2 accelerometer address %3 magnetometer address %4'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
                max: '7'
                min: '0'
            accel_address:
                name: accel_address
                type: int
                default: '25'
                field: number
                max: '127'
                min: '0'
            mag_address:
                name: mag_address
                type: int
                default: '30'
                field: number
                max: '127'
                min: '0'
        """
        self._driver = LSM303Full(I2CConnection(accel_address, bus=bus),
                                  I2CConnection(mag_address, bus=bus))

    def x(self) -> float:
        """
        label:
            en: '%1 acceleration X (g)'
        """
        return self._driver.read_acceleration()[0]

    def y(self) -> float:
        """
        label:
            en: '%1 acceleration Y (g)'
        """
        return self._driver.read_acceleration()[1]

    def z(self) -> float:
        """
        label:
            en: '%1 acceleration Z (g)'
        """
        return self._driver.read_acceleration()[2]

    def mx(self) -> float:
        """
        label:
            en: '%1 magnetic X (uT)'
        """
        return self._driver.read_magnetic()[0]

    def my(self) -> float:
        """
        label:
            en: '%1 magnetic Y (uT)'
        """
        return self._driver.read_magnetic()[1]

    def mz(self) -> float:
        """
        label:
            en: '%1 magnetic Z (uT)'
        """
        return self._driver.read_magnetic()[2]

    def heading(self, tilt_compensate: int = 1) -> float:
        """
        label:
            en: '%1 heading (degrees), tilt compensated %2'
        params:
            tilt_compensate:
                name: tilt_compensate
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.heading(tilt_compensate=bool(tilt_compensate))

    def set_range(self, range_g: int = 2):
        """
        label:
            en: '%1 set accelerometer range %2 g'
        params:
            range_g:
                name: range_g
                type: int
                default: '2'
                field: number
                max: '16'
                min: '2'
        """
        self._driver.set_range(int(range_g))

    def set_data_rate(self, rate_hz: int = 100):
        """
        label:
            en: '%1 set accelerometer data rate %2 Hz'
        params:
            rate_hz:
                name: rate_hz
                type: int
                default: '100'
                field: number
                max: '5376'
                min: '0'
        """
        self._driver.set_data_rate(int(rate_hz))

    def set_mode(self, mode: int = 2):
        """
        label:
            en: '%1 set accelerometer mode %2 (0=low-power, 1=normal, 2=high-res)'
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

    def set_axes(self, x: int = 1, y: int = 1, z: int = 1):
        """
        label:
            en: '%1 set accelerometer axes X %2 Y %3 Z %4'
        params:
            x:
                name: x
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            y:
                name: y
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            z:
                name: z
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_axes(x=bool(x), y=bool(y), z=bool(z))

    def power_down(self):
        """
        label:
            en: '%1 power down accelerometer'
        """
        self._driver.power_down()

    def read_acceleration_raw(self) -> tuple:
        """
        label:
            en: '%1 raw acceleration (x, y, z counts)'
        """
        return self._driver.read_acceleration_raw()

    def new_data_available(self) -> bool:
        """
        label:
            en: '%1 new acceleration data available?'
        """
        return self._driver.new_data_available()

    def read_status(self) -> int:
        """
        label:
            en: '%1 STATUS_REG_A (overrun and data-ready flags)'
        """
        return self._driver.read_status()

    def reset_high_pass(self) -> int:
        """
        label:
            en: '%1 reset high-pass filter (returns REFERENCE_A)'
        """
        return self._driver.reset_high_pass()

    def set_reference(self, value: int = 0):
        """
        label:
            en: '%1 set high-pass reference %2'
        params:
            value:
                name: value
                type: int
                default: '0'
                field: number
                max: '255'
                min: '0'
        """
        self._driver.set_reference(int(value))

    def fifo_level(self) -> int:
        """
        label:
            en: '%1 FIFO level (unread sample sets)'
        """
        return self._driver.fifo_level()

    def read_fifo(self, count: int = 0) -> list:
        """
        label:
            en: '%1 read FIFO samples (x, y, z in g), count 0 = all'
        params:
            count:
                name: count
                type: int
                default: '0'
                field: number
                max: '32'
                min: '0'
        """
        return self._driver.read_fifo(int(count)) if int(count) > 0 else self._driver.read_fifo()

    def read_fifo_status(self) -> tuple:
        """
        label:
            en: '%1 FIFO status (watermark, overrun, empty, level)'
        """
        return self._driver.read_fifo_status()

    def read_interrupt_source(self, generator: int = 1) -> int:
        """
        label:
            en: '%1 read INTx_SRC generator %2 (reading clears the interrupt)'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
        """
        return self._driver.read_interrupt_source(int(generator))

    def read_click_source(self) -> int:
        """
        label:
            en: '%1 read CLICK_SRC (reading clears the interrupt)'
        """
        return self._driver.read_click_source()

    def read_magnetic_raw(self) -> tuple:
        """
        label:
            en: '%1 raw magnetic (x, y, z counts)'
        """
        return self._driver.read_magnetic_raw()

    def mag_data_ready(self) -> bool:
        """
        label:
            en: '%1 new magnetometer data available?'
        """
        return self._driver.mag_data_ready()

    def read_mag_status(self) -> tuple:
        """
        label:
            en: '%1 magnetometer status (drdy, lock)'
        """
        return self._driver.read_mag_status()

    def read_mag_identity(self) -> bytes:
        """
        label:
            en: '%1 magnetometer identity'
        """
        return self._driver.read_mag_identity()

    def read_temperature_delta(self) -> float:
        """
        label:
            en: '%1 relative temperature (C)'
        """
        return self._driver.read_temperature_delta()

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 poll interrupt status (bitmask)'
        """
        return self._driver.poll_interrupt()

    def set_high_pass(self, mode: int = 0, cutoff: int = 0, filter_output: int = 0, on_ia1: int = 0, on_ia2: int = 0, on_click: int = 0):
        """
        label:
            en: '%1 set high-pass mode %2 cutoff %3 output %4 generator1 %5 generator2 %6 click %7'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                max: '3'
                min: '0'
            cutoff:
                name: cutoff
                type: int
                default: '0'
                field: number
                max: '3'
                min: '0'
            filter_output:
                name: filter_output
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            on_ia1:
                name: on_ia1
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            on_ia2:
                name: on_ia2
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            on_click:
                name: on_click
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_high_pass(mode=int(mode), cutoff=int(cutoff), filter_output=bool(filter_output), on_ia1=bool(on_ia1), on_ia2=bool(on_ia2), on_click=bool(on_click))

    def set_fifo(self, mode: int = 0, watermark: int = 0, trigger_int2: int = 0):
        """
        label:
            en: '%1 set FIFO mode %2 (0=bypass, 1=FIFO, 2=stream, 3=trigger) watermark %3 trigger INT2 %4'
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
                max: '31'
                min: '0'
            trigger_int2:
                name: trigger_int2
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_fifo(mode=int(mode), watermark=int(watermark), trigger_int2=bool(trigger_int2))

    def set_motion_interrupt(self, generator: int = 1, events: int = 0, threshold_g: float = 0.5, duration_ms: float = 0, and_mode: int = 0):
        """
        label:
            en: '%1 set motion interrupt generator %2 events %3 threshold %4 g duration %5 ms AND %6'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
            events:
                name: events
                type: int
                default: '0'
                field: number
                max: '63'
                min: '0'
            threshold_g:
                name: threshold_g
                type: float
                default: '0.5'
                field: number
                max: '20'
                min: '0'
            duration_ms:
                name: duration_ms
                type: float
                default: '0'
                field: number
                max: '10000'
                min: '0'
            and_mode:
                name: and_mode
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_motion_interrupt(generator=int(generator), events=int(events), threshold_g=float(threshold_g), duration_ms=float(duration_ms), and_mode=bool(and_mode))

    def set_free_fall(self, generator: int = 1, threshold_g: float = 0.35, duration_ms: float = 30):
        """
        label:
            en: '%1 set free-fall generator %2 threshold %3 g duration %4 ms'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
            threshold_g:
                name: threshold_g
                type: float
                default: '0.35'
                field: number
                max: '20'
                min: '0'
            duration_ms:
                name: duration_ms
                type: float
                default: '30'
                field: number
                max: '10000'
                min: '0'
        """
        self._driver.set_free_fall(generator=int(generator), threshold_g=float(threshold_g), duration_ms=float(duration_ms))

    def set_orientation(self, generator: int = 1, position: int = 0, four_d: int = 0):
        """
        label:
            en: '%1 set orientation generator %2 position %3 4D %4'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
            position:
                name: position
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            four_d:
                name: four_d
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_orientation(generator=int(generator), position=bool(position), four_d=bool(four_d))

    def set_latch(self, generator: int = 1, enabled: int = 1):
        """
        label:
            en: '%1 latch generator %2 interrupt %3 (1=on, 0=off)'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_latch(generator=int(generator), enabled=bool(enabled))

    def set_click(self, single: int = 0, double: int = 0, threshold_g: float = 0.5, time_limit_ms: float = 10, latency_ms: float = 20, window_ms: float = 50):
        """
        label:
            en: '%1 set click single %2 double %3 threshold %4 g limit %5 ms latency %6 ms window %7 ms'
        params:
            single:
                name: single
                type: int
                default: '0'
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
            threshold_g:
                name: threshold_g
                type: float
                default: '0.5'
                field: number
                max: '20'
                min: '0'
            time_limit_ms:
                name: time_limit_ms
                type: float
                default: '10'
                field: number
                max: '10000'
                min: '0'
            latency_ms:
                name: latency_ms
                type: float
                default: '20'
                field: number
                max: '10000'
                min: '0'
            window_ms:
                name: window_ms
                type: float
                default: '50'
                field: number
                max: '10000'
                min: '0'
        """
        self._driver.set_click(single=int(single), double=int(double), threshold_g=float(threshold_g), time_limit_ms=float(time_limit_ms), latency_ms=float(latency_ms), window_ms=float(window_ms))

    def set_interrupt_polarity(self, active_low: int = 0):
        """
        label:
            en: '%1 interrupt polarity active-low %2 (1=active-low, 0=active-high)'
        params:
            active_low:
                name: active_low
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_interrupt_polarity(bool(active_low))

    def set_mag_range(self, range_ut: int = 130):
        """
        label:
            en: '%1 set magnetometer range %2 uT'
        params:
            range_ut:
                name: range_ut
                type: int
                default: '130'
                field: number
                max: '810'
                min: '130'
        """
        self._driver.set_mag_range(int(range_ut))

    def set_mag_data_rate(self, rate_hz: float = 15):
        """
        label:
            en: '%1 set magnetometer data rate %2 Hz'
        params:
            rate_hz:
                name: rate_hz
                type: float
                default: '15'
                field: number
                max: '220'
                min: '0.75'
        """
        self._driver.set_mag_data_rate(float(rate_hz))

    def set_mag_mode(self, mode: int = 0):
        """
        label:
            en: '%1 set magnetometer mode %2 (0=continuous, 1=single, 2=sleep)'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                max: '2'
                min: '0'
        """
        self._driver.set_mag_mode(int(mode))

    def enable_temperature(self, enabled: int = 1):
        """
        label:
            en: '%1 enable temperature sensor %2 (1=on, 0=off)'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.enable_temperature(bool(enabled))

    def enable_interrupt(self, source: int = 1, pin: int = 1):
        """
        label:
            en: '%1 enable interrupt source %2 on INT%3'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
                max: '255'
                min: '0'
            pin:
                name: pin
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
        """
        self._driver.enable_interrupt(int(source), pin=int(pin))

    def disable_interrupt(self, source: int = 1, pin: int = 1):
        """
        label:
            en: '%1 disable interrupt source %2 on INT%3'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
                max: '255'
                min: '0'
            pin:
                name: pin
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
        """
        self._driver.disable_interrupt(int(source), pin=int(pin))

    def off_interrupt(self, pin: int = 1):
        """
        label:
            en: '%1 unsubscribe from INT%2 interrupts'
        params:
            pin:
                name: pin
                type: int
                default: '1'
                field: number
                max: '2'
                min: '1'
        """
        self._driver.off_interrupt(pin=int(pin))

    def reboot(self):
        """
        label:
            en: '%1 reboot (reload factory trim)'
        """
        self._driver.reboot()
