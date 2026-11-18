"""
file     Is31fl3731
time     2026-11-18
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.is31fl3731 import IS31FL3731Full


class Is31fl3731:
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

    def __init__(self, bus: int = 0, address: int = 116):
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
                default: '116'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = IS31FL3731Full(connection)

    def set_pixel(self, row: int, col: int, brightness: int):
        """
        label:
            en: '%1 set pixel row %2 col %3 brightness %4'
        params:
            row:
                name: row
                type: int
                field: number
            col:
                name: col
                type: int
                field: number
            brightness:
                name: brightness
                type: int
                field: number
        """
        self._driver.set_pixel(row, col, brightness)

    def clear(self):
        """
        label:
            en: '%1 clear'
        """
        self._driver.clear()

    def fill(self, brightness: int):
        """
        label:
            en: '%1 fill brightness %2'
        params:
            brightness:
                name: brightness
                type: int
                field: number
        """
        self._driver.fill(brightness)

    def get_pixel(self, row: int, col: int) -> int:
        """
        label:
            en: '%1 get pixel row %2 col %3'
        params:
            row:
                name: row
                type: int
                field: number
            col:
                name: col
                type: int
                field: number
        """

        return self._driver.get_pixel(row, col)

    def set_led_enabled(self, row: int, col: int, on: int):
        """
        label:
            en: '%1 set LED enabled row %2 col %3 on %4'
        params:
            row:
                name: row
                type: int
                field: number
            col:
                name: col
                type: int
                field: number
            on:
                name: on
                type: int
                field: number
        """
        self._driver.set_led_enabled(row, col, bool(on))

    def set_blink(self, row: int, col: int, on: int):
        """
        label:
            en: '%1 set blink row %2 col %3 on %4'
        params:
            row:
                name: row
                type: int
                field: number
            col:
                name: col
                type: int
                field: number
            on:
                name: on
                type: int
                field: number
        """
        self._driver.set_blink(row, col, bool(on))

    def set_blink_all(self, on: int):
        """
        label:
            en: '%1 set blink all on %2'
        params:
            on:
                name: on
                type: int
                field: number
        """
        self._driver.set_blink_all(bool(on))

    def enable_blink(self, enabled: int, period: int):
        """
        label:
            en: '%1 enable blink enabled %2 period %3'
        params:
            enabled:
                name: enabled
                type: int
                field: number
            period:
                name: period
                type: int
                field: number
        """
        self._driver.enable_blink(bool(enabled), period)

    def select_frame(self, frame: int):
        """
        label:
            en: '%1 select frame frame %2'
        params:
            frame:
                name: frame
                type: int
                field: number
        """
        self._driver.select_frame(frame)

    def show_frame(self, frame: int):
        """
        label:
            en: '%1 show frame frame %2'
        params:
            frame:
                name: frame
                type: int
                field: number
        """
        self._driver.show_frame(frame)

    def clear_frame(self, frame: int):
        """
        label:
            en: '%1 clear frame frame %2'
        params:
            frame:
                name: frame
                type: int
                field: number
        """
        self._driver.clear_frame(frame)

    def clear_all_frames(self):
        """
        label:
            en: '%1 clear all frames'
        """
        self._driver.clear_all_frames()

    def copy_frame(self, src: int, dst: int):
        """
        label:
            en: '%1 copy frame source %2 destination %3'
        params:
            src:
                name: src
                type: int
                field: number
            dst:
                name: dst
                type: int
                field: number
        """
        self._driver.copy_frame(src, dst)

    def set_picture_mode(self):
        """
        label:
            en: '%1 set picture mode'
        """
        self._driver.set_picture_mode()

    def start_auto_play(self, frames: int, loops: int, delay_ms: int, start_frame: int):
        """
        label:
            en: '%1 start auto play frames %2 loops %3 delay ms %4 start frame %5'
        params:
            frames:
                name: frames
                type: int
                field: number
            loops:
                name: loops
                type: int
                field: number
            delay_ms:
                name: delay_ms
                type: int
                field: number
            start_frame:
                name: start_frame
                type: int
                field: number
        """
        self._driver.start_auto_play(frames, loops, delay_ms, start_frame)

    def stop_auto_play(self):
        """
        label:
            en: '%1 stop auto play'
        """
        self._driver.stop_auto_play()

    def set_audio_mode(self, enabled: int):
        """
        label:
            en: '%1 set audio mode enabled %2'
        params:
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.set_audio_mode(bool(enabled))

    def set_audio_sync(self, enabled: int):
        """
        label:
            en: '%1 set audio sync enabled %2'
        params:
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.set_audio_sync(bool(enabled))

    def set_audio_gain(self, gain_db: int, agc: int, fast: int):
        """
        label:
            en: '%1 set audio gain gain dB %2 AGC %3 fast AGC %4'
        params:
            gain_db:
                name: gain_db
                type: int
                field: number
            agc:
                name: agc
                type: int
                field: number
            fast:
                name: fast
                type: int
                field: number
        """
        self._driver.set_audio_gain(gain_db, bool(agc), bool(fast))

    def set_audio_rate(self, a: int):
        """
        label:
            en: '%1 set audio rate rate %2'
        params:
            a:
                name: a
                type: int
                field: number
        """
        self._driver.set_audio_rate(a)

    def set_breath(self, enabled: int, fade_in: int, fade_out: int, extinguish: int):
        """
        label:
            en: '%1 set breath enabled %2 fade in %3 fade out %4 extinguish %5'
        params:
            enabled:
                name: enabled
                type: int
                field: number
            fade_in:
                name: fade_in
                type: int
                field: number
            fade_out:
                name: fade_out
                type: int
                field: number
            extinguish:
                name: extinguish
                type: int
                field: number
        """
        self._driver.set_breath(bool(enabled), fade_in, fade_out, extinguish)

    def set_intensity_control(self, use_frame1: int):
        """
        label:
            en: '%1 set intensity control use frame 1 %2'
        params:
            use_frame1:
                name: use_frame1
                type: int
                field: number
        """
        self._driver.set_intensity_control(bool(use_frame1))

    def shutdown(self, on: int):
        """
        label:
            en: '%1 shutdown on %2'
        params:
            on:
                name: on
                type: int
                field: number
        """
        self._driver.shutdown(bool(on))

    def frame_state(self) -> int:
        """
        label:
            en: '%1 frame state'
        """

        return self._driver.frame_state()

    def current_frame(self) -> int:
        """
        label:
            en: '%1 current frame'
        """

        return self._driver.current_frame()

    def movie_finished(self) -> bool:
        """
        label:
            en: '%1 movie finished'
        """

        return self._driver.movie_finished()

    def read_function_register(self, reg: int) -> int:
        """
        label:
            en: '%1 read function register register %2'
        params:
            reg:
                name: reg
                type: int
                field: number
        """

        return self._driver.read_function_register(reg)

    def write_function_register(self, reg: int, value: int):
        """
        label:
            en: '%1 write function register register %2 value %3'
        params:
            reg:
                name: reg
                type: int
                field: number
            value:
                name: value
                type: int
                field: number
        """
        self._driver.write_function_register(reg, value)

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 poll interrupt'
        """

        return self._driver.poll_interrupt()
