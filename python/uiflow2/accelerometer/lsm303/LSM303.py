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

    def __init__(bus: int = 0):
        """
        label:
            en: '%1 init I2C bus %2 (accel 0x19, mag 0x1E)'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
        """
        self._driver = LSM303Full(I2CConnection(0x19, bus=bus), I2CConnection(0x1E, bus=bus))

    def accel_x() -> float:
        """
        label:
            en: '%1 acceleration X (g)'
        """
        return self._driver.read_acceleration()[0]

    def accel_y() -> float:
        """
        label:
            en: '%1 acceleration Y (g)'
        """
        return self._driver.read_acceleration()[1]

    def accel_z() -> float:
        """
        label:
            en: '%1 acceleration Z (g)'
        """
        return self._driver.read_acceleration()[2]

    def mag_x() -> float:
        """
        label:
            en: '%1 magnetic X (µT)'
        """
        return self._driver.read_magnetic()[0]

    def mag_y() -> float:
        """
        label:
            en: '%1 magnetic Y (µT)'
        """
        return self._driver.read_magnetic()[1]

    def mag_z() -> float:
        """
        label:
            en: '%1 magnetic Z (µT)'
        """
        return self._driver.read_magnetic()[2]

    def read_acceleration():
        """
        label:
            en: '%1 read acceleration (x, y, z)'
        """
        self._driver.read_acceleration()

    def read_magnetic():
        """
        label:
            en: '%1 read magnetic field (x, y, z)'
        """
        self._driver.read_magnetic()

    def read_acceleration_raw():
        """
        label:
            en: '%1 read raw acceleration (x, y, z)'
        """
        self._driver.read_acceleration_raw()

    def read_magnetic_raw():
        """
        label:
            en: '%1 read raw magnetic field (x, y, z)'
        """
        self._driver.read_magnetic_raw()

    def set_range(range_g: int = 2):
        """
        label:
            en: '%1 set range (g: 2, 4, 8 or 16) %2'
        params:
            range_g:
                name: range_g
                type: int
                default: '2'
                field: number
                min: '2'
                max: '16'
        """
        self._driver.set_range(int(range_g))

    def set_data_rate(rate_hz: int = 100):
        """
        label:
            en: '%1 set data rate (Hz) %2'
        params:
            rate_hz:
                name: rate_hz
                type: int
                default: '100'
                field: number
                min: '0'
                max: '5376'
        """
        self._driver.set_data_rate(int(rate_hz))

    def set_mode(mode: int = 2):
        """
        label:
            en: '%1 set mode (0 low-power, 1 normal, 2 high-res) %2'
        params:
            mode:
                name: mode
                type: int
                default: '2'
                field: number
                min: '0'
                max: '2'
        """
        self._driver.set_mode(int(mode))

    def set_axes(x: int = 1, y: int = 1, z: int = 1):
        """
        label:
            en: '%1 enable axes (x %2, y %3, z %4)'
        params:
            x:
                name: x
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
            y:
                name: y
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
            z:
                name: z
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_axes(bool(x), bool(y), bool(z))

    def power_down():
        """
        label:
            en: '%1 power down the accelerometer'
        """
        self._driver.power_down()

    def new_data_available() -> int:
        """
        label:
            en: '%1 new data available (1/0)'
        """
        return int(self._driver.new_data_available())

    def read_status() -> int:
        """
        label:
            en: '%1 status register (raw)'
        """
        return self._driver.read_status()

    def set_high_pass(mode: int = 0, cutoff: int = 0):
        """
        label:
            en: '%1 set high-pass (mode %2, cutoff %3)'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
            cutoff:
                name: cutoff
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_high_pass(int(mode), int(cutoff))

    def reset_high_pass() -> int:
        """
        label:
            en: '%1 reset high-pass, returns the reference'
        """
        return self._driver.reset_high_pass()

    def set_reference(value: int = 0):
        """
        label:
            en: '%1 set the high-pass reference %2'
        params:
            value:
                name: value
                type: int
                default: '0'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.set_reference(int(value))

    def set_fifo(mode: int = 0, watermark: int = 0):
        """
        label:
            en: '%1 set FIFO (mode %2, watermark %3)'
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
                max: '31'
        """
        self._driver.set_fifo(int(mode), int(watermark))

    def fifo_level() -> int:
        """
        label:
            en: '%1 FIFO level (sample sets)'
        """
        return self._driver.fifo_level()

    def read_fifo():
        """
        label:
            en: '%1 read the FIFO samples'
        """
        self._driver.read_fifo()

    def read_fifo_status():
        """
        label:
            en: '%1 read the FIFO status'
        """
        self._driver.read_fifo_status()

    def set_motion_interrupt(generator: int = 1, events: int = 0, threshold_g: float = 0.5, duration_ms: float = 0):
        """
        label:
            en: '%1 set motion interrupt (generator %2, events %3, threshold g %4, duration ms %5)'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
            events:
                name: events
                type: int
                default: '0'
                field: number
                min: '0'
                max: '63'
            threshold_g:
                name: threshold_g
                type: float
                default: '0.5'
                field: number
            duration_ms:
                name: duration_ms
                type: float
                default: '0'
                field: number
        """
        self._driver.set_motion_interrupt(int(generator), int(events), float(threshold_g), float(duration_ms))

    def set_free_fall(generator: int = 1, threshold_g: float = 0.35, duration_ms: float = 30):
        """
        label:
            en: '%1 set free-fall (generator %2, threshold g %3, duration ms %4)'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
            threshold_g:
                name: threshold_g
                type: float
                default: '0.35'
                field: number
            duration_ms:
                name: duration_ms
                type: float
                default: '30'
                field: number
        """
        self._driver.set_free_fall(int(generator), float(threshold_g), float(duration_ms))

    def set_orientation(generator: int = 1, position: int = 0, four_d: int = 0):
        """
        label:
            en: '%1 set orientation (generator %2, position %3, four_d %4)'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
            position:
                name: position
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            four_d:
                name: four_d
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_orientation(int(generator), bool(position), bool(four_d))

    def set_latch(generator: int = 1, enabled: int = 0):
        """
        label:
            en: '%1 latch the interrupt (generator %2, enabled %3)'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_latch(int(generator), bool(enabled))

    def read_interrupt_source(generator: int = 1) -> int:
        """
        label:
            en: '%1 read the interrupt source (generator %2)'
        params:
            generator:
                name: generator
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        return self._driver.read_interrupt_source(int(generator))

    def set_click(single: int = 0, double: int = 0, threshold_g: float = 0.5):
        """
        label:
            en: '%1 set click (single mask %2, double mask %3, threshold g %4)'
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
            threshold_g:
                name: threshold_g
                type: float
                default: '0.5'
                field: number
        """
        self._driver.set_click(int(single), int(double), float(threshold_g))

    def read_click_source() -> int:
        """
        label:
            en: '%1 read the click source (raw)'
        """
        return self._driver.read_click_source()

    def set_interrupt_polarity(active_low: int = 0):
        """
        label:
            en: '%1 set interrupt polarity (1 = active low) %2'
        params:
            active_low:
                name: active_low
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_interrupt_polarity(bool(active_low))

    def set_mag_range(range_ut: int = 130):
        """
        label:
            en: '%1 set magnetometer range (µT) %2'
        params:
            range_ut:
                name: range_ut
                type: int
                default: '130'
                field: number
                min: '130'
                max: '810'
        """
        self._driver.set_mag_range(int(range_ut))

    def set_mag_data_rate(rate_hz: float = 15.0):
        """
        label:
            en: '%1 set magnetometer data rate (Hz) %2'
        params:
            rate_hz:
                name: rate_hz
                type: float
                default: '15.0'
                field: number
        """
        self._driver.set_mag_data_rate(float(rate_hz))

    def set_mag_mode(mode: int = 0):
        """
        label:
            en: '%1 set magnetometer mode (0 continuous, 1 single, 2 sleep) %2'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '2'
        """
        self._driver.set_mag_mode(int(mode))

    def mag_data_ready() -> int:
        """
        label:
            en: '%1 magnetometer data ready (1/0)'
        """
        return int(self._driver.mag_data_ready())

    def read_mag_status():
        """
        label:
            en: '%1 read the magnetometer status'
        """
        self._driver.read_mag_status()

    def read_mag_identity() -> str:
        """
        label:
            en: '%1 magnetometer identity (hex)'
        """
        return self._driver.read_mag_identity().hex()

    def enable_temperature(enabled: int = 1):
        """
        label:
            en: '%1 enable the temperature sensor %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.enable_temperature(bool(enabled))

    def read_temperature_delta() -> float:
        """
        label:
            en: '%1 temperature delta (°C)'
        """
        return self._driver.read_temperature_delta()

    def heading(tilt_compensate: int = 1) -> float:
        """
        label:
            en: '%1 compass heading (deg) %2'
        params:
            tilt_compensate:
                name: tilt_compensate
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.heading(bool(tilt_compensate))

    def enable_interrupt(source: int = 1, pin: int = 1):
        """
        label:
            en: '%1 enable an interrupt source (source %2, pin %3)'
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
        self._driver.enable_interrupt(int(source), int(pin))

    def disable_interrupt(source: int = 1, pin: int = 1):
        """
        label:
            en: '%1 disable an interrupt source (source %2, pin %3)'
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
        self._driver.disable_interrupt(int(source), int(pin))

    def poll_interrupt() -> int:
        """
        label:
            en: '%1 interrupt status (mask)'
        """
        return self._driver.poll_interrupt()

    def reboot():
        """
        label:
            en: '%1 reboot the accelerometer'
        """
        self._driver.reboot()
