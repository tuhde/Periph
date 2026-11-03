"""
file     DRV2605
time     2026-11-03
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.motor.drv2605 import DRV2605Full


class DRV2605:
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

    def __init__(self, bus: int = 0, lra: int = 0):
        """
        label:
            en: '%1 init bus %2 actuator (0 = ERM, 1 = LRA) %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            lra:
                name: lra
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        connection = I2CConnection(0x5A, bus=bus)
        self._driver = DRV2605Full(connection, lra=bool(lra))

    def play_effect(self, effect: int):
        """
        label:
            en: '%1 play effect (1-123) %2'
        params:
            effect:
                name: effect
                type: int
                default: '1'
                field: number
                min: '1'
                max: '123'
        """
        self._driver.play_effect(effect)

    def play_sequence(self, steps: str):
        """
        label:
            en: '%1 play sequence (e.g. 1,w100,10; wN = wait N ms) %2'
        params:
            steps:
                name: steps
                type: str
                default: '1,w100,1'
        """
        self._driver.play_sequence([('wait', int(s[1:])) if s[0] == 'w' else int(s) for s in steps.replace(' ', '').split(',') if s])

    def stop(self):
        """
        label:
            en: '%1 stop'
        """
        self._driver.stop()

    def is_playing(self) -> bool:
        """
        label:
            en: '%1 is playing'
        """
        return self._driver.is_playing()

    def wait_done(self, timeout_ms: int) -> bool:
        """
        label:
            en: '%1 wait until done, timeout (ms) %2'
        params:
            timeout_ms:
                name: timeout_ms
                type: int
                default: '5000'
                field: number
                min: '0'
        """
        return self._driver.wait_done(timeout_ms)

    def set_library(self, library: int):
        """
        label:
            en: '%1 set library (0-6) %2'
        params:
            library:
                name: library
                type: int
                default: '1'
                field: number
                min: '0'
                max: '6'
        """
        self._driver.set_library(library)

    def set_mode(self, mode: int):
        """
        label:
            en: '%1 set mode (0-7) %2'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
        """
        self._driver.set_mode(mode)

    def standby(self):
        """
        label:
            en: '%1 standby'
        """
        self._driver.standby()

    def wake(self):
        """
        label:
            en: '%1 wake'
        """
        self._driver.wake()

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()

    def set_hi_z(self, enabled: int):
        """
        label:
            en: '%1 set high-impedance (0/1) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_hi_z(bool(enabled))

    def set_actuator(self, lra: int):
        """
        label:
            en: '%1 set actuator (0 = ERM, 1 = LRA) %2'
        params:
            lra:
                name: lra
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_actuator(bool(lra))

    def set_open_loop(self, enabled: int):
        """
        label:
            en: '%1 set open loop (0/1) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_open_loop(bool(enabled))

    def set_bidirectional(self, enabled: int):
        """
        label:
            en: '%1 set bidirectional input (0/1) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_bidirectional(bool(enabled))

    def rtp_level(self, level: float):
        """
        label:
            en: '%1 RTP level (0.0-1.0) %2'
        params:
            level:
                name: level
                type: float
                default: '0.5'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.rtp_level(level)

    def rtp_write(self, value: int):
        """
        label:
            en: '%1 RTP raw value (0-255) %2'
        params:
            value:
                name: value
                type: int
                default: '64'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.rtp_write(value)

    def set_rtp_format(self, unsigned: int):
        """
        label:
            en: '%1 set RTP unsigned (0/1) %2'
        params:
            unsigned:
                name: unsigned
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_rtp_format(bool(unsigned))

    def set_input(self, analog: int):
        """
        label:
            en: '%1 set input (0 = PWM, 1 = analog) %2'
        params:
            analog:
                name: analog
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_input(bool(analog))

    def set_rated_voltage(self, volts: float, lra_freq_hz: float):
        """
        label:
            en: '%1 set rated voltage (V) %2 LRA frequency (Hz, 0 = ERM) %3'
        params:
            volts:
                name: volts
                type: float
                default: '3.0'
                field: number
                min: '0'
            lra_freq_hz:
                name: lra_freq_hz
                type: float
                default: '0.0'
                field: number
                min: '0'
        """
        self._driver.set_rated_voltage(volts=volts, lra_freq_hz=lra_freq_hz or None)

    def set_overdrive_clamp(self, volts: float, lra_freq_hz: float):
        """
        label:
            en: '%1 set overdrive clamp (V) %2 LRA frequency (Hz, 0 = not needed) %3'
        params:
            volts:
                name: volts
                type: float
                default: '3.0'
                field: number
                min: '0'
            lra_freq_hz:
                name: lra_freq_hz
                type: float
                default: '0.0'
                field: number
                min: '0'
        """
        self._driver.set_overdrive_clamp(volts=volts, lra_freq_hz=lra_freq_hz or None)

    def set_drive_time(self, ms: float):
        """
        label:
            en: '%1 set drive time (ms) %2'
        params:
            ms:
                name: ms
                type: float
                default: '2.0'
                field: number
                min: '0'
        """
        self._driver.set_drive_time(ms)

    def set_feedback(self, brake_factor: int, loop_gain: int, bemf_gain: int):
        """
        label:
            en: '%1 set feedback brake factor (0-7) %2 loop gain (0-3) %3 BEMF gain (0-3) %4'
        params:
            brake_factor:
                name: brake_factor
                type: int
                default: '3'
                field: number
                min: '0'
                max: '7'
            loop_gain:
                name: loop_gain
                type: int
                default: '1'
                field: number
                min: '0'
                max: '3'
            bemf_gain:
                name: bemf_gain
                type: int
                default: '2'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_feedback(brake_factor, loop_gain, bemf_gain)

    def set_advanced(self, startup_boost: int, brake_stabilizer: int, sample_time: int, blanking_time: int, idiss_time: int, lra_drive_twice: int, supply_comp: int, noise_gate: int):
        """
        label:
            en: '%1 set advanced startup boost (0/1) %2 brake stabilizer (0/1) %3 sample time (0-3) %4 blanking time (0-3) %5 idiss time (0-3) %6 LRA drive twice (0/1) %7 supply compensation (0/1) %8 noise gate (0-3) %9'
        params:
            startup_boost:
                name: startup_boost
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
            brake_stabilizer:
                name: brake_stabilizer
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
            sample_time:
                name: sample_time
                type: int
                default: '3'
                field: number
                min: '0'
                max: '3'
            blanking_time:
                name: blanking_time
                type: int
                default: '1'
                field: number
                min: '0'
                max: '3'
            idiss_time:
                name: idiss_time
                type: int
                default: '1'
                field: number
                min: '0'
                max: '3'
            lra_drive_twice:
                name: lra_drive_twice
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            supply_comp:
                name: supply_comp
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
            noise_gate:
                name: noise_gate
                type: int
                default: '2'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_advanced(bool(startup_boost), bool(brake_stabilizer), sample_time, blanking_time, idiss_time, bool(lra_drive_twice), bool(supply_comp), noise_gate)

    def set_time_offsets(self, overdrive_ms: int, sustain_pos_ms: int, sustain_neg_ms: int, brake_ms: int):
        """
        label:
            en: '%1 set time offsets overdrive (ms) %2 sustain + (ms) %3 sustain - (ms) %4 brake (ms) %5'
        params:
            overdrive_ms:
                name: overdrive_ms
                type: int
                default: '0'
                field: number
            sustain_pos_ms:
                name: sustain_pos_ms
                type: int
                default: '0'
                field: number
            sustain_neg_ms:
                name: sustain_neg_ms
                type: int
                default: '0'
                field: number
            brake_ms:
                name: brake_ms
                type: int
                default: '0'
                field: number
        """
        self._driver.set_time_offsets(overdrive_ms, sustain_pos_ms, sustain_neg_ms, brake_ms)

    def calibrate(self, lra: int, rated_voltage: float, overdrive_voltage: float, lra_freq_hz: float) -> bool:
        """
        label:
            en: '%1 calibrate, LRA (0/1) %2 rated voltage (V) %3 overdrive voltage (V) %4 LRA frequency (Hz, 0 = ERM) %5 (1 = OK)'
        params:
            lra:
                name: lra
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            rated_voltage:
                name: rated_voltage
                type: float
                default: '3.0'
                field: number
                min: '0'
            overdrive_voltage:
                name: overdrive_voltage
                type: float
                default: '3.0'
                field: number
                min: '0'
            lra_freq_hz:
                name: lra_freq_hz
                type: float
                default: '0.0'
                field: number
                min: '0'
        """
        return self._driver.calibrate(bool(lra), rated_voltage, overdrive_voltage, lra_freq_hz or None)[0]

    def calibration_comp(self) -> int:
        """
        label:
            en: '%1 calibration compensation'
        """
        return self._driver.get_calibration()[0]

    def calibration_bemf(self) -> int:
        """
        label:
            en: '%1 calibration back-EMF'
        """
        return self._driver.get_calibration()[1]

    def calibration_bemf_gain(self) -> int:
        """
        label:
            en: '%1 calibration BEMF gain'
        """
        return self._driver.get_calibration()[2]

    def set_calibration(self, comp: int, bemf: int, bemf_gain: int):
        """
        label:
            en: '%1 set calibration compensation (0-255) %2 back-EMF (0-255) %3 BEMF gain (0-3) %4'
        params:
            comp:
                name: comp
                type: int
                default: '13'
                field: number
                min: '0'
                max: '255'
            bemf:
                name: bemf
                type: int
                default: '109'
                field: number
                min: '0'
                max: '255'
            bemf_gain:
                name: bemf_gain
                type: int
                default: '2'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_calibration(comp, bemf, bemf_gain)

    def diagnose(self) -> bool:
        """
        label:
            en: '%1 diagnose actuator (OK = true)'
        """
        return self._driver.diagnose()

    def set_audio_to_vibe(self, ac_couple: int, peak_time_ms: int, filter_hz: int, min_input_v: float, max_input_v: float, min_drive: float, max_drive: float):
        """
        label:
            en: '%1 audio to vibe AC coupled (0/1) %2 peak time (10/20/30/40 ms) %3 filter (100/125/150/200 Hz) %4 min input (V) %5 max input (V) %6 min drive (0.0-1.0) %7 max drive (0.0-1.0) %8'
        params:
            ac_couple:
                name: ac_couple
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
            peak_time_ms:
                name: peak_time_ms
                type: int
                default: '20'
                field: number
                min: '10'
                max: '40'
            filter_hz:
                name: filter_hz
                type: int
                default: '125'
                field: number
                min: '100'
                max: '200'
            min_input_v:
                name: min_input_v
                type: float
                default: '0.1'
                field: number
                min: '0'
                max: '1.8'
            max_input_v:
                name: max_input_v
                type: float
                default: '1.8'
                field: number
                min: '0'
                max: '1.8'
            min_drive:
                name: min_drive
                type: float
                default: '0.1'
                field: number
                min: '0'
                max: '1'
            max_drive:
                name: max_drive
                type: float
                default: '1.0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_audio_to_vibe(bool(ac_couple), peak_time_ms, filter_hz, min_input_v, max_input_v, min_drive, max_drive)

    def diag_failed(self) -> bool:
        """
        label:
            en: '%1 diagnostics failed (clears flags)'
        """
        return self._driver.read_status()[0]

    def fb_timeout(self) -> bool:
        """
        label:
            en: '%1 feedback timeout (clears flags)'
        """
        return self._driver.read_status()[1]

    def over_temp(self) -> bool:
        """
        label:
            en: '%1 over-temperature (clears flags)'
        """
        return self._driver.read_status()[2]

    def over_current(self) -> bool:
        """
        label:
            en: '%1 over-current'
        """
        return self._driver.read_status()[3]

    def device_id(self) -> int:
        """
        label:
            en: '%1 device id'
        """
        return self._driver.read_device_id()

    def supply_voltage(self) -> float:
        """
        label:
            en: '%1 supply voltage (V)'
        """
        return self._driver.read_supply_voltage()

    def lra_frequency(self) -> float:
        """
        label:
            en: '%1 LRA frequency (Hz, 0 = n/a)'
        """
        return self._driver.read_lra_frequency() or 0.0

    def program_otp(self, confirm: int):
        """
        label:
            en: '%1 program OTP, IRREVERSIBLE (1 to burn) %2'
        params:
            confirm:
                name: confirm
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.program_otp(confirm=(confirm == 1))

    def otp_programmed(self) -> bool:
        """
        label:
            en: '%1 OTP programmed'
        """
        return self._driver.otp_programmed()
