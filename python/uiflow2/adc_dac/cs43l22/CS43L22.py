"""
file     CS43L22
time     2026-11-12
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.cs43l22 import CS43L22Full


class CS43L22:
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

    def __init__(self, bus: int = 0, address: int = 74):
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
                default: '74'
                field: number
                min: '74'
                max: '75'
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = CS43L22Full(connection)

    def start(self):
        """
        label:
            en: '{lab}'
        """
        self._driver.start()

    def stop(self):
        """
        label:
            en: '{lab}'
        """
        self._driver.stop()

    def set_volume(self, db: float):
        """
        label:
            en: '{lab}'
        params:
            db:
                name: db
                type: float
                default: '-10.0'
                field: number
        """
        self._driver.set_volume(db)

    def get_volume(self) -> float:
        """
        label:
            en: '{lab}'
        """
        return self._driver.get_volume()

    def mute(self, on: int):
        """
        label:
            en: '{lab}'
        params:
            on:
                name: on
                type: int
                default: '1'
                field: number
        """
        self._driver.mute(bool(on))

    def reset(self):
        """
        label:
            en: '{lab}'
        """
        self._driver.reset()

    def set_outputs(self, headphone: int, speaker: int):
        """
        label:
            en: '{lab}'
        params:
            headphone:
                name: headphone
                type: int
                default: '2'
                field: number
            speaker:
                name: speaker
                type: int
                default: '3'
                field: number
        """
        self._driver.set_outputs(headphone, speaker)

    def set_volume_channels(self, left_db: float, right_db: float):
        """
        label:
            en: '{lab}'
        params:
            left_db:
                name: left_db
                type: float
                default: '-10.0'
                field: number
            right_db:
                name: right_db
                type: float
                default: '-10.0'
                field: number
        """
        self._driver.set_volume_channels(left_db, right_db)

    def set_headphone_volume(self, left_db: float, right_db: float):
        """
        label:
            en: '{lab}'
        params:
            left_db:
                name: left_db
                type: float
                default: '-10.0'
                field: number
            right_db:
                name: right_db
                type: float
                default: '-10.0'
                field: number
        """
        self._driver.set_headphone_volume(left_db, right_db)

    def set_speaker_volume(self, left_db: float, right_db: float):
        """
        label:
            en: '{lab}'
        params:
            left_db:
                name: left_db
                type: float
                default: '-10.0'
                field: number
            right_db:
                name: right_db
                type: float
                default: '-10.0'
                field: number
        """
        self._driver.set_speaker_volume(left_db, right_db)

    def set_pcm_volume(self, left_db: float, right_db: float):
        """
        label:
            en: '{lab}'
        params:
            left_db:
                name: left_db
                type: float
                default: '0.0'
                field: number
            right_db:
                name: right_db
                type: float
                default: '0.0'
                field: number
        """
        self._driver.set_pcm_volume(left_db, right_db)

    def set_headphone_gain(self, index: int):
        """
        label:
            en: '{lab}'
        params:
            index:
                name: index
                type: int
                default: '3'
                field: number
        """
        self._driver.set_headphone_gain(index)

    def headphone_gain(self) -> float:
        """
        label:
            en: '{lab}'
        """
        return self._driver.headphone_gain()

    def mute_outputs(self, headphone: int, speaker: int):
        """
        label:
            en: '{lab}'
        params:
            headphone:
                name: headphone
                type: int
                default: '-1'
                field: number
            speaker:
                name: speaker
                type: int
                default: '-1'
                field: number
        """
        self._driver.mute_outputs((None if headphone < 0 else bool(headphone)), (None if speaker < 0 else bool(speaker)))

    def set_tone(self, treble_db: float, bass_db: float):
        """
        label:
            en: '{lab}'
        params:
            treble_db:
                name: treble_db
                type: float
                default: '0.0'
                field: number
            bass_db:
                name: bass_db
                type: float
                default: '0.0'
                field: number
        """
        self._driver.set_tone(treble_db, bass_db)

    def set_beep(self, frequency: int, on_time: int, off_time: int, volume_db: float, mode: int):
        """
        label:
            en: '{lab}'
        params:
            frequency:
                name: frequency
                type: int
                default: '12'
                field: number
            on_time:
                name: on_time
                type: int
                default: '2'
                field: number
            off_time:
                name: off_time
                type: int
                default: '0'
                field: number
            volume_db:
                name: volume_db
                type: float
                default: '-6.0'
                field: number
            mode:
                name: mode
                type: int
                default: '1'
                field: number
        """
        self._driver.set_beep(frequency, on_time, off_time, volume_db, mode)

    def stop_beep(self):
        """
        label:
            en: '{lab}'
        """
        self._driver.stop_beep()

    def set_limiter(self, enabled: int, max_db: int, cushion_db: int):
        """
        label:
            en: '{lab}'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
            max_db:
                name: max_db
                type: int
                default: '-6'
                field: number
            cushion_db:
                name: cushion_db
                type: int
                default: '-12'
                field: number
        """
        self._driver.set_limiter(bool(enabled), max_db, cushion_db)

    def set_soft_ramp(self, digital: int):
        """
        label:
            en: '{lab}'
        params:
            digital:
                name: digital
                type: int
                default: '1'
                field: number
        """
        self._driver.set_soft_ramp(bool(digital))

    def set_zero_cross(self, digital: int):
        """
        label:
            en: '{lab}'
        params:
            digital:
                name: digital
                type: int
                default: '0'
                field: number
        """
        self._driver.set_zero_cross(bool(digital))

    def set_deemphasis(self, enabled: int):
        """
        label:
            en: '{lab}'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
        """
        self._driver.set_deemphasis(bool(enabled))

    def invert_pcm(self, left: int, right: int):
        """
        label:
            en: '{lab}'
        params:
            left:
                name: left
                type: int
                default: '0'
                field: number
            right:
                name: right
                type: int
                default: '0'
                field: number
        """
        self._driver.invert_pcm(bool(left), bool(right))

    def set_passthrough(self, enabled: int, left_input: int, right_input: int):
        """
        label:
            en: '{lab}'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
            left_input:
                name: left_input
                type: int
                default: '1'
                field: number
            right_input:
                name: right_input
                type: int
                default: '1'
                field: number
        """
        self._driver.set_passthrough(bool(enabled), left_input, right_input)

    def set_passthrough_volume(self, left_db: float, right_db: float):
        """
        label:
            en: '{lab}'
        params:
            left_db:
                name: left_db
                type: float
                default: '0.0'
                field: number
            right_db:
                name: right_db
                type: float
                default: '0.0'
                field: number
        """
        self._driver.set_passthrough_volume(left_db, right_db)

    def freeze(self, on: int):
        """
        label:
            en: '{lab}'
        params:
            on:
                name: on
                type: int
                default: '0'
                field: number
        """
        self._driver.freeze(bool(on))

    def read_vp_voltage(self, va_volts: float) -> float:
        """
        label:
            en: '{lab}'
        params:
            va_volts:
                name: va_volts
                type: float
                default: '1.8'
                field: number
        """
        return self._driver.read_vp_voltage(va_volts)

    def clock_error(self) -> bool:
        """
        label:
            en: '{lab}'
        """
        return self._driver.read_status()['clock_error']

    def read_register(self, reg: int) -> int:
        """
        label:
            en: '{lab}'
        params:
            reg:
                name: reg
                type: int
                default: '2'
                field: number
        """
        return self._driver.read_register(reg)

    def write_register(self, reg: int, value: int):
        """
        label:
            en: '{lab}'
        params:
            reg:
                name: reg
                type: int
                default: '2'
                field: number
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.write_register(reg, value)

    def set_charge_pump_frequency(self, n: int):
        """
        label:
            en: '{lab}'
        params:
            n:
                name: n
                type: int
                default: '5'
                field: number
        """
        self._driver.set_charge_pump_frequency(n)
