"""DRV2605 — Haptic driver for ERM and LRA actuators with I2C interface (Texas Instruments).

Drives eccentric-rotating-mass (ERM) and linear-resonant-actuator (LRA) vibration
motors. A 123-effect licensed TS2200 ROM library is played by an eight-slot waveform
sequencer, with a real-time-playback (RTP) register, PWM / analog / audio-to-vibe
inputs, smart-loop back-EMF feedback, auto-calibration and actuator diagnostics.
The I2C address is fixed at 0x5A. The EN pin must be high for register access.
The same driver file is used on MicroPython, CircuitPython, and Linux hosts.

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device (0x5A).
"""

try:
    from time import sleep_ms as _sleep_ms
except ImportError:
    from time import sleep as _sleep

    def _sleep_ms(ms):
        _sleep(ms / 1000.0)

try:
    from time import ticks_ms as _ticks_ms, ticks_diff as _ticks_diff
except ImportError:
    from time import monotonic as _monotonic

    def _ticks_ms():
        return int(_monotonic() * 1000)

    def _ticks_diff(a, b):
        return a - b


I2C_ADDRESS = 0x5A
DEVICE_ID = 3

# Named ROM effect identifiers (TS2200 library, datasheet §11.2).
EFFECT_STRONG_CLICK = 1
EFFECT_SHARP_CLICK = 4
EFFECT_SOFT_BUMP = 7
EFFECT_DOUBLE_CLICK = 10
EFFECT_TRIPLE_CLICK = 12
EFFECT_STRONG_BUZZ = 14
EFFECT_ALERT_750MS = 15
EFFECT_ALERT_1000MS = 16
EFFECT_BUZZ = 47
EFFECT_PULSING_STRONG = 52
EFFECT_LONG_BUZZ = 118

# MODE register values.
MODE_INTERNAL_TRIGGER = 0
MODE_EXTERNAL_EDGE = 1
MODE_EXTERNAL_LEVEL = 2
MODE_PWM_ANALOG = 3
MODE_AUDIO_TO_VIBE = 4
MODE_RTP = 5
MODE_DIAGNOSTICS = 6
MODE_AUTO_CALIBRATION = 7

_SAMPLE_TIME_S = (150e-6, 200e-6, 250e-6, 300e-6)
_PEAK_TIMES_MS = (10, 20, 30, 40)
_FILTERS_HZ = (100, 125, 150, 200)


def _clamp_code(value):
    code = int(value + 0.5)
    if code < 0:
        return 0
    if code > 255:
        return 255
    return code


class DRV2605Minimal:
    """DRV2605 haptic driver — minimal interface.

    Runs the initialization sequence (reset, device-id check, leave standby,
    actuator selection) and plays ROM effects.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
        lra: False for an ERM (library A, open loop); True for an LRA (LRA
            library, closed loop with auto-resonance).

    Raises:
        RuntimeError: If the device-id check fails or the reset does not complete.
    """

    _REG_STATUS = 0x00
    _REG_MODE = 0x01
    _REG_RTP_INPUT = 0x02
    _REG_LIBRARY = 0x03
    _REG_SEQ1 = 0x04
    _REG_GO = 0x0C
    _REG_ODT = 0x0D
    _REG_ATH_CTRL = 0x11
    _REG_ATH_MIN_INPUT = 0x12
    _REG_RATED_VOLTAGE = 0x16
    _REG_OD_CLAMP = 0x17
    _REG_A_CAL_COMP = 0x18
    _REG_A_CAL_BEMF = 0x19
    _REG_FEEDBACK = 0x1A
    _REG_CONTROL1 = 0x1B
    _REG_CONTROL2 = 0x1C
    _REG_CONTROL3 = 0x1D
    _REG_CONTROL4 = 0x1E
    _REG_VBAT = 0x21
    _REG_LRA_PERIOD = 0x22

    _MODE_DEV_RESET = 0x80
    _MODE_STANDBY = 0x40

    def __init__(self, connection, lra=False):
        self._connection = connection
        self._mode = MODE_INTERNAL_TRIGGER
        self._standby = False
        self._reset_chip()
        if self._read_reg(self._REG_STATUS) >> 5 != DEVICE_ID:
            raise RuntimeError('DRV2605 not found (DEVICE_ID != 3)')
        self._write_mode(MODE_INTERNAL_TRIGGER)
        if lra:
            self._write_reg(self._REG_FEEDBACK, 0xB6)
            self._write_reg(self._REG_LIBRARY, 0x06)
        else:
            self._write_reg(self._REG_FEEDBACK, 0x36)
            self._write_reg(self._REG_LIBRARY, 0x01)

    # -- register helpers ---------------------------------------------------

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, value & 0xFF)

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _update_reg(self, reg, mask, value):
        """Read-modify-write: replace the bits in ``mask`` with ``value`` (already shifted)."""
        old = self._read_reg(reg)
        self._write_reg(reg, (old & ~mask) | (value & mask))

    def _write_mode(self, mode, standby=False):
        self._write_reg(self._REG_MODE, (self._MODE_STANDBY if standby else 0) | mode)
        self._mode = mode
        self._standby = standby

    def _reset_chip(self):
        self._write_reg(self._REG_MODE, self._MODE_DEV_RESET)
        self._mode = MODE_INTERNAL_TRIGGER
        self._standby = True
        _sleep_ms(2)
        for _ in range(100):
            if not self._read_reg(self._REG_MODE) & self._MODE_DEV_RESET:
                return
            _sleep_ms(1)
        raise RuntimeError('DRV2605 reset did not complete')

    def _ensure_internal(self):
        if self._mode != MODE_INTERNAL_TRIGGER or self._standby:
            self._write_mode(MODE_INTERNAL_TRIGGER)

    # -- public API ---------------------------------------------------------

    def play_effect(self, effect_id):
        """Play one ROM effect and return immediately.

        Writes the effect to sequencer slot 1 and ``0`` (end of sequence) to
        slot 2, switches to internal-trigger mode if needed, then sets ``GO``.

        Args:
            effect_id: Effect identifier 1–123 (see the ``EFFECT_*`` constants).

        Raises:
            ValueError: If ``effect_id`` is outside 1–123.
        """
        if not 1 <= effect_id <= 123:
            raise ValueError('effect_id must be 1-123')
        self._ensure_internal()
        self._connection.write_reg(self._REG_SEQ1, bytes([effect_id, 0]))
        self._write_reg(self._REG_GO, 1)

    def stop(self):
        """Cancel playback by clearing the ``GO`` bit."""
        self._write_reg(self._REG_GO, 0)

    def is_playing(self):
        """Return True while a sequence is playing (the ``GO`` bit is set)."""
        return bool(self._read_reg(self._REG_GO) & 0x01)


class DRV2605Full(DRV2605Minimal):
    """DRV2605 full interface — extends Minimal with the complete chip functionality.

    Adds the waveform sequencer, real-time playback, mode and library control,
    drive configuration, auto-calibration, diagnostics, audio-to-vibe, status
    readout and one-time OTP programming.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
        lra: False for an ERM; True for an LRA.
    """

    # -- playback -----------------------------------------------------------

    def play_sequence(self, items):
        """Play a sequence of up to 8 effects and delays.

        Args:
            items: List of up to 8 steps. An ``int`` 1–123 is a ROM effect; a
                ``("wait", ms)`` tuple is a delay of 0–1270 ms (10 ms steps).

        Raises:
            ValueError: On an empty or oversized list or an out-of-range step.
        """
        if not 1 <= len(items) <= 8:
            raise ValueError('items must hold 1-8 steps')
        data = bytearray()
        for item in items:
            if isinstance(item, int):
                if not 1 <= item <= 123:
                    raise ValueError('effect must be 1-123')
                data.append(item)
            else:
                if item[0] != 'wait' or not 0 <= item[1] <= 1270:
                    raise ValueError('wait step must be ("wait", 0-1270 ms)')
                data.append(0x80 | int(item[1] / 10.0 + 0.5))
        if len(data) < 8:
            data.append(0)
        self._ensure_internal()
        self._connection.write_reg(self._REG_SEQ1, bytes(data))
        self._write_reg(self._REG_GO, 1)

    def wait_done(self, timeout_ms=5000):
        """Poll ``GO`` until playback finishes.

        Args:
            timeout_ms: Give-up time in milliseconds.

        Returns:
            True if playback finished, False on timeout.
        """
        start = _ticks_ms()
        while self.is_playing():
            if _ticks_diff(_ticks_ms(), start) >= timeout_ms:
                return False
            _sleep_ms(2)
        return True

    def stop(self):
        """Cancel playback; also leaves real-time-playback mode (output to zero)."""
        DRV2605Minimal.stop(self)
        if self._mode == MODE_RTP:
            self._write_reg(self._REG_RTP_INPUT, 0)
            self._write_mode(MODE_INTERNAL_TRIGGER, self._standby)

    # -- mode / library / power ---------------------------------------------

    def set_library(self, library):
        """Select the ROM library: 0 empty, 1–5 ERM libraries A–E, 6 LRA library."""
        if not 0 <= library <= 6:
            raise ValueError('library must be 0-6')
        self._update_reg(self._REG_LIBRARY, 0x07, library)

    def set_mode(self, mode):
        """Set ``MODE<2:0>`` (0–7) and leave standby."""
        if not 0 <= mode <= 7:
            raise ValueError('mode must be 0-7')
        self._write_mode(mode)

    def standby(self):
        """Enter software standby (also stops playback)."""
        self._write_mode(self._mode, True)

    def wake(self):
        """Leave software standby."""
        self._write_mode(self._mode, False)

    def reset(self):
        """Reset all registers to defaults and wait for completion; re-run ``init`` logic afterwards."""
        self._reset_chip()

    def set_hi_z(self, enabled):
        """Put the outputs in true high-impedance state (True) or normal drive (False)."""
        self._update_reg(self._REG_LIBRARY, 0x10, 0x10 if enabled else 0)

    # -- actuator / loop configuration --------------------------------------

    def _is_lra(self):
        return bool(self._read_reg(self._REG_FEEDBACK) & 0x80)

    def _is_open_loop(self, lra):
        control3 = self._read_reg(self._REG_CONTROL3)
        return bool(control3 & (0x01 if lra else 0x20))

    def set_actuator(self, lra):
        """Select ERM (False) or LRA (True) in ``N_ERM_LRA``; run ``calibrate`` afterwards."""
        self._update_reg(self._REG_FEEDBACK, 0x80, 0x80 if lra else 0)

    def set_open_loop(self, enabled):
        """Enable open-loop drive (``ERM_OPEN_LOOP`` or ``LRA_OPEN_LOOP`` per actuator type)."""
        mask = 0x01 if self._is_lra() else 0x20
        self._update_reg(self._REG_CONTROL3, mask, mask if enabled else 0)

    def set_bidirectional(self, enabled):
        """Select bidirectional (True) or unidirectional (False) input.

        Raises:
            ValueError: If unidirectional is requested while the loop is open.
        """
        if not enabled and self._is_open_loop(self._is_lra()):
            raise ValueError('unidirectional input requires closed-loop operation')
        self._update_reg(self._REG_CONTROL2, 0x80, 0x80 if enabled else 0)

    # -- real-time playback / inputs ----------------------------------------

    def _enter_rtp(self):
        if self._mode != MODE_RTP or self._standby:
            self._write_mode(MODE_RTP)

    def rtp_level(self, level):
        """Stream a signed real-time-playback amplitude.

        Enters RTP mode (signed data format) if not already there; leave with
        ``stop``, ``set_mode`` or ``standby``.

        Args:
            level: Amplitude 0.0–1.0 (mapped to 0–127).
        """
        if not 0.0 <= level <= 1.0:
            raise ValueError('level must be 0.0-1.0')
        if self._mode != MODE_RTP:
            self._update_reg(self._REG_CONTROL3, 0x08, 0)
        self._enter_rtp()
        self._write_reg(self._REG_RTP_INPUT, int(level * 127 + 0.5))

    def rtp_write(self, value):
        """Write a raw ``RTP_INPUT`` byte (0–255), interpreted per the RTP data format."""
        if not 0 <= value <= 255:
            raise ValueError('value must be 0-255')
        self._enter_rtp()
        self._write_reg(self._REG_RTP_INPUT, value)

    def set_rtp_format(self, unsigned):
        """Select unsigned (True) or signed (False) RTP data (``DATA_FORMAT_RTP``)."""
        self._update_reg(self._REG_CONTROL3, 0x08, 0x08 if unsigned else 0)

    def set_input(self, analog):
        """Select analog (True) or PWM (False) input for mode 3 (``N_PWM_ANALOG``)."""
        self._update_reg(self._REG_CONTROL3, 0x02, 0x02 if analog else 0)

    # -- voltage / timing configuration -------------------------------------

    def _volts_to_rated_code(self, volts, lra_freq_hz):
        if not self._is_lra():
            return _clamp_code(volts / 21.33e-3)
        if lra_freq_hz is None:
            raise ValueError('lra_freq_hz is required to convert volts for an LRA')
        t_sample = _SAMPLE_TIME_S[(self._read_reg(self._REG_CONTROL2) >> 4) & 0x03]
        factor = 1.0 - (4 * t_sample + 300e-6) * lra_freq_hz
        if factor <= 0:
            raise ValueError('lra_freq_hz too high for the sample time')
        return _clamp_code(volts * (factor ** 0.5) / 20.71e-3)

    def set_rated_voltage(self, volts=None, code=None, lra_freq_hz=None):
        """Set ``RATED_VOLTAGE`` from volts or a raw code.

        Args:
            volts: Rated voltage in V (RMS for an LRA).
            code: Raw register code 0–255 (alternative to ``volts``).
            lra_freq_hz: LRA resonance frequency in Hz; required with ``volts`` for an LRA.
        """
        if (volts is None) == (code is None):
            raise ValueError('give exactly one of volts or code')
        if code is None:
            code = self._volts_to_rated_code(volts, lra_freq_hz)
        elif not 0 <= code <= 255:
            raise ValueError('code must be 0-255')
        self._write_reg(self._REG_RATED_VOLTAGE, code)

    def _volts_to_clamp_code(self, volts, lra_freq_hz):
        lra = self._is_lra()
        open_loop = self._is_open_loop(lra)
        if lra and open_loop:
            if lra_freq_hz is None:
                raise ValueError('lra_freq_hz is required for LRA open loop')
            return _clamp_code(volts / (21.33e-3 * (1.0 - lra_freq_hz * 800e-6) ** 0.5))
        if not lra and not open_loop:
            raise ValueError('ERM closed-loop clamp in volts needs undocumented timing values; pass code')
        return _clamp_code(volts / 21.96e-3)

    def set_overdrive_clamp(self, volts=None, code=None, lra_freq_hz=None):
        """Set ``OD_CLAMP`` from volts or a raw code.

        Args:
            volts: Clamp voltage in V (RMS for LRA open loop).
            code: Raw register code 0–255 (alternative to ``volts``).
            lra_freq_hz: LRA frequency in Hz; required with ``volts`` for LRA open loop.

        Raises:
            ValueError: For ERM closed loop with ``volts`` (pass ``code`` instead).
        """
        if (volts is None) == (code is None):
            raise ValueError('give exactly one of volts or code')
        if code is None:
            code = self._volts_to_clamp_code(volts, lra_freq_hz)
        elif not 0 <= code <= 255:
            raise ValueError('code must be 0-255')
        self._write_reg(self._REG_OD_CLAMP, code)

    def _drive_time_code(self, ms, lra):
        code = int((ms - 0.5) / 0.1 + 0.5) if lra else int((ms - 1.0) / 0.2 + 0.5)
        return max(0, min(31, code))

    def set_drive_time(self, ms):
        """Set ``DRIVE_TIME`` in ms (LRA: half the resonance period; ERM: back-EMF sample period)."""
        self._update_reg(self._REG_CONTROL1, 0x1F, self._drive_time_code(ms, self._is_lra()))

    def set_feedback(self, brake_factor=3, loop_gain=1, bemf_gain=2):
        """Set the ``FEEDBACK_CONTROL`` fields (actuator-type bit preserved).

        Args:
            brake_factor: ``FB_BRAKE_FACTOR`` 0–7 (0 1x … 6 16x, 7 braking disabled).
            loop_gain: ``LOOP_GAIN`` 0–3.
            bemf_gain: ``BEMF_GAIN`` 0–3.
        """
        if not (0 <= brake_factor <= 7 and 0 <= loop_gain <= 3 and 0 <= bemf_gain <= 3):
            raise ValueError('brake_factor 0-7, loop_gain 0-3, bemf_gain 0-3')
        self._update_reg(self._REG_FEEDBACK, 0x7F, (brake_factor << 4) | (loop_gain << 2) | bemf_gain)

    def set_advanced(self, startup_boost=True, brake_stabilizer=True, sample_time=3,
                     blanking_time=1, idiss_time=1, lra_drive_twice=False,
                     supply_comp=True, noise_gate=2):
        """Set the ``CONTROL1``–``CONTROL3`` fields not covered by other setters."""
        if not (0 <= sample_time <= 3 and 0 <= blanking_time <= 3 and 0 <= idiss_time <= 3
                and 0 <= noise_gate <= 3):
            raise ValueError('sample_time, blanking_time, idiss_time, noise_gate must be 0-3')
        self._update_reg(self._REG_CONTROL1, 0x80, 0x80 if startup_boost else 0)
        self._update_reg(self._REG_CONTROL2, 0x7F,
                         (0x40 if brake_stabilizer else 0) | (sample_time << 4)
                         | (blanking_time << 2) | idiss_time)
        self._update_reg(self._REG_CONTROL3, 0xD4,
                         (noise_gate << 6) | (0 if supply_comp else 0x10)
                         | (0x04 if lra_drive_twice else 0))

    def set_time_offsets(self, overdrive_ms=0, sustain_pos_ms=0, sustain_neg_ms=0, brake_ms=0):
        """Set the ``ODT``, ``SPT``, ``SNT``, ``BRT`` offsets (5 ms steps, signed 8-bit)."""
        data = bytearray()
        for ms in (overdrive_ms, sustain_pos_ms, sustain_neg_ms, brake_ms):
            steps = int(ms / 5.0 + 0.5) if ms >= 0 else -int(-ms / 5.0 + 0.5)
            if not -128 <= steps <= 127:
                raise ValueError('time offset out of range (-640..635 ms)')
            data.append(steps & 0xFF)
        self._connection.write_reg(self._REG_ODT, bytes(data))

    # -- calibration / diagnostics ------------------------------------------

    def calibrate(self, lra, rated_voltage, overdrive_voltage, lra_freq_hz=None):
        """Run the auto-calibration procedure.

        Args:
            lra: True for an LRA, False for an ERM.
            rated_voltage: Rated voltage in V (RMS for an LRA).
            overdrive_voltage: Overdrive clamp voltage in V. For an ERM the
                open-loop conversion (V / 21.96 mV) is used, since the
                closed-loop correction needs undocumented timing values.
            lra_freq_hz: LRA resonance frequency in Hz (required for an LRA); also
                sets ``DRIVE_TIME`` to half the period.

        Returns:
            Tuple ``(ok, comp, bemf, bemf_gain)`` — ``ok`` is True if the
            calibration converged; the rest are the engine's results.
        """
        if lra and lra_freq_hz is None:
            raise ValueError('lra_freq_hz is required for an LRA')
        was_standby = self._standby
        self._write_mode(MODE_AUTO_CALIBRATION)
        bemf_gain = self._read_reg(self._REG_FEEDBACK) & 0x03
        self._write_reg(self._REG_FEEDBACK, (0x80 if lra else 0) | (2 << 4) | (2 << 2) | bemf_gain)
        # SAMPLE_TIME = 3, BLANKING_TIME = 1, IDISS_TIME = 1
        self._update_reg(self._REG_CONTROL2, 0x3F, (3 << 4) | (1 << 2) | 1)
        if lra:
            self._update_reg(self._REG_CONTROL1, 0x1F, self._drive_time_code(500.0 / lra_freq_hz, True))
        self._write_reg(self._REG_RATED_VOLTAGE, self._volts_to_rated_code(rated_voltage, lra_freq_hz))
        self._write_reg(self._REG_OD_CLAMP, _clamp_code(overdrive_voltage / 21.96e-3))
        self._update_reg(self._REG_CONTROL4, 0x31, 3 << 4)  # AUTO_CAL_TIME = 3; OTP_PROGRAM stays 0
        self._write_reg(self._REG_GO, 1)
        finished = self.wait_done(3000)
        diag_failed = bool(self._read_reg(self._REG_STATUS) & 0x08)
        comp, bemf, gain = self.get_calibration()
        self._write_mode(MODE_INTERNAL_TRIGGER, was_standby)
        return (finished and not diag_failed, comp, bemf, gain)

    def get_calibration(self):
        """Return the stored calibration ``(comp, bemf, bemf_gain)``."""
        data = self._connection.read_reg(self._REG_A_CAL_COMP, 2)
        return (data[0], data[1], self._read_reg(self._REG_FEEDBACK) & 0x03)

    def set_calibration(self, comp, bemf, bemf_gain):
        """Restore ``A_CAL_COMP``, ``A_CAL_BEMF`` and ``BEMF_GAIN`` from an earlier calibration."""
        if not (0 <= comp <= 255 and 0 <= bemf <= 255 and 0 <= bemf_gain <= 3):
            raise ValueError('comp/bemf 0-255, bemf_gain 0-3')
        self._connection.write_reg(self._REG_A_CAL_COMP, bytes([comp, bemf]))
        self._update_reg(self._REG_FEEDBACK, 0x03, bemf_gain)

    def diagnose(self):
        """Run the actuator diagnostics.

        Returns:
            True if the actuator is OK (``DIAG_RESULT`` = 0).
        """
        was_standby = self._standby
        self._write_mode(MODE_DIAGNOSTICS)
        self._write_reg(self._REG_GO, 1)
        finished = self.wait_done(3000)
        diag_failed = bool(self._read_reg(self._REG_STATUS) & 0x08)
        self._write_mode(MODE_INTERNAL_TRIGGER, was_standby)
        return finished and not diag_failed

    def set_audio_to_vibe(self, ac_couple=True, peak_time_ms=20, filter_hz=125,
                          min_input_v=0.1, max_input_v=1.8, min_drive=0.1, max_drive=1.0):
        """Configure the audio-to-vibe engine and enter audio-to-vibe mode.

        Args:
            ac_couple: Enable the 0.9 V common-mode for AC-coupled input.
            peak_time_ms: Peak-detection time, one of 10, 20, 30, 40.
            filter_hz: Low-pass filter corner, one of 100, 125, 150, 200.
            min_input_v: Minimum input level in V peak-to-peak (0–1.8).
            max_input_v: Maximum input level in V peak-to-peak (0–1.8).
            min_drive: Minimum output drive fraction (0.0–1.0).
            max_drive: Maximum output drive fraction (0.0–1.0).
        """
        if peak_time_ms not in _PEAK_TIMES_MS or filter_hz not in _FILTERS_HZ:
            raise ValueError('peak_time_ms in 10/20/30/40, filter_hz in 100/125/150/200')
        if not (0.0 <= min_input_v <= 1.8 and 0.0 <= max_input_v <= 1.8
                and 0.0 <= min_drive <= 1.0 and 0.0 <= max_drive <= 1.0):
            raise ValueError('input levels 0-1.8 V, drive 0.0-1.0')
        ath = (_PEAK_TIMES_MS.index(peak_time_ms) << 2) | _FILTERS_HZ.index(filter_hz)
        self._connection.write_reg(self._REG_ATH_CTRL, bytes([
            ath,
            _clamp_code(min_input_v * 255 / 1.8), _clamp_code(max_input_v * 255 / 1.8),
            _clamp_code(min_drive * 255), _clamp_code(max_drive * 255)]))
        self._update_reg(self._REG_CONTROL1, 0x20, 0x20 if ac_couple else 0)
        self._update_reg(self._REG_CONTROL3, 0x02, 0x02)
        self._write_mode(MODE_AUDIO_TO_VIBE)

    # -- status / readout ---------------------------------------------------

    def read_status(self):
        """Read ``STATUS`` (clears the latched flags).

        Returns:
            Tuple ``(diag_failed, fb_timeout, over_temp, over_current)`` of bools.
        """
        status = self._read_reg(self._REG_STATUS)
        return (bool(status & 0x08), bool(status & 0x04), bool(status & 0x02), bool(status & 0x01))

    def read_device_id(self):
        """Return ``DEVICE_ID`` (3 for the DRV2605). Clears the latched status flags."""
        return self._read_reg(self._REG_STATUS) >> 5

    def read_supply_voltage(self):
        """Return VDD in volts from ``VBAT``; valid only during playback."""
        return self._read_reg(self._REG_VBAT) * 5.6 / 255.0

    def read_lra_frequency(self):
        """Return the LRA resonance frequency in Hz (valid only during playback), or None if 0."""
        raw = self._read_reg(self._REG_LRA_PERIOD)
        if raw == 0:
            return None
        return 1e6 / (raw * 98.46)

    def program_otp(self, confirm=False):
        """Burn registers 0x16–0x1A into OTP memory. **Irreversible, one time only.**

        VDD must be 4.0–4.4 V; the driver cannot measure it before playback.

        Args:
            confirm: Must be True to proceed.

        Raises:
            ValueError: If ``confirm`` is not True.
            RuntimeError: If the OTP is already programmed or programming fails.
        """
        if confirm is not True:
            raise ValueError('program_otp is irreversible; pass confirm=True')
        if self.otp_programmed():
            raise RuntimeError('OTP already programmed')
        self._update_reg(self._REG_CONTROL4, 0x01, 0x01)
        for _ in range(100):
            _sleep_ms(10)
            if self.otp_programmed():
                return
        raise RuntimeError('OTP programming did not complete')

    def otp_programmed(self):
        """Return True if the OTP has already been programmed (``OTP_STATUS``)."""
        return bool(self._read_reg(self._REG_CONTROL4) & 0x04)
