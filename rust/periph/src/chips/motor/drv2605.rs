//! DRV2605 — Haptic driver for ERM and LRA actuators with I²C interface (Texas Instruments).
//!
//! Plays the built-in 123-effect TS2200 ROM library through an eight-slot
//! waveform sequencer, with real-time playback (RTP), PWM / analog /
//! audio-to-vibe inputs, smart-loop back-EMF feedback, auto-calibration and
//! actuator diagnostics. The I²C address is fixed at `0x5A`; the `EN` pin must
//! be high for any register access.
//!
//! Operations that must wait (reset, polling for the end of playback,
//! calibration, OTP) take an `embedded_hal::delay::DelayNs`. Argument errors
//! are reported as [`Drv2605Error::InvalidArgument`] without touching the bus.
//!
//! The driver follows `specs/motor/drv2605.md`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_STATUS: u8 = 0x00;
const REG_MODE: u8 = 0x01;
const REG_RTP_INPUT: u8 = 0x02;
const REG_LIBRARY: u8 = 0x03;
const REG_SEQ1: u8 = 0x04;
const REG_GO: u8 = 0x0C;
const REG_ODT: u8 = 0x0D;
const REG_ATH_CTRL: u8 = 0x11;
const REG_RATED_VOLTAGE: u8 = 0x16;
const REG_OD_CLAMP: u8 = 0x17;
const REG_A_CAL_COMP: u8 = 0x18;
const REG_FEEDBACK: u8 = 0x1A;
const REG_CONTROL1: u8 = 0x1B;
const REG_CONTROL2: u8 = 0x1C;
const REG_CONTROL3: u8 = 0x1D;
const REG_CONTROL4: u8 = 0x1E;
const REG_VBAT: u8 = 0x21;
const REG_LRA_PERIOD: u8 = 0x22;

const MODE_DEV_RESET: u8 = 0x80;
const MODE_STANDBY: u8 = 0x40;

/// Fixed 7-bit I²C address.
pub const DRV2605_I2C_ADDRESS: u8 = 0x5A;
/// Expected `DEVICE_ID` for the DRV2605.
pub const DRV2605_DEVICE_ID: u8 = 3;

/// Named ROM effect: Strong Click (100 %).
pub const DRV2605_EFFECT_STRONG_CLICK: u8 = 1;
/// Named ROM effect: Sharp Click (100 %).
pub const DRV2605_EFFECT_SHARP_CLICK: u8 = 4;
/// Named ROM effect: Soft Bump (100 %).
pub const DRV2605_EFFECT_SOFT_BUMP: u8 = 7;
/// Named ROM effect: Double Click (100 %).
pub const DRV2605_EFFECT_DOUBLE_CLICK: u8 = 10;
/// Named ROM effect: Triple Click (100 %).
pub const DRV2605_EFFECT_TRIPLE_CLICK: u8 = 12;
/// Named ROM effect: Strong Buzz (100 %).
pub const DRV2605_EFFECT_STRONG_BUZZ: u8 = 14;
/// Named ROM effect: 750 ms Alert (100 %).
pub const DRV2605_EFFECT_ALERT_750MS: u8 = 15;
/// Named ROM effect: 1000 ms Alert (100 %).
pub const DRV2605_EFFECT_ALERT_1000MS: u8 = 16;
/// Named ROM effect: Buzz 1 (100 %).
pub const DRV2605_EFFECT_BUZZ: u8 = 47;
/// Named ROM effect: Pulsing Strong 1 (100 %).
pub const DRV2605_EFFECT_PULSING_STRONG: u8 = 52;
/// Named ROM effect: Long Buzz for programmatic stopping (100 %).
pub const DRV2605_EFFECT_LONG_BUZZ: u8 = 118;

/// `MODE` value: internal trigger (`GO` bit).
pub const DRV2605_MODE_INTERNAL_TRIGGER: u8 = 0;
/// `MODE` value: external trigger, edge mode.
pub const DRV2605_MODE_EXTERNAL_EDGE: u8 = 1;
/// `MODE` value: external trigger, level mode.
pub const DRV2605_MODE_EXTERNAL_LEVEL: u8 = 2;
/// `MODE` value: PWM / analog input.
pub const DRV2605_MODE_PWM_ANALOG: u8 = 3;
/// `MODE` value: audio-to-vibe.
pub const DRV2605_MODE_AUDIO_TO_VIBE: u8 = 4;
/// `MODE` value: real-time playback.
pub const DRV2605_MODE_RTP: u8 = 5;
/// `MODE` value: diagnostics.
pub const DRV2605_MODE_DIAGNOSTICS: u8 = 6;
/// `MODE` value: auto-calibration.
pub const DRV2605_MODE_AUTO_CALIBRATION: u8 = 7;

const SAMPLE_TIME_S: [f32; 4] = [150e-6, 200e-6, 250e-6, 300e-6];
const PEAK_TIMES_MS: [u16; 4] = [10, 20, 30, 40];
const FILTERS_HZ: [u16; 4] = [100, 125, 150, 200];

/// Errors from the DRV2605 drivers.
#[derive(Debug)]
pub enum Drv2605Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// An argument was out of range or inconsistent (no bus access was made).
    InvalidArgument,
    /// `DEVICE_ID` was not 3 (the DRV2605).
    DeviceNotFound,
    /// A reset, playback, calibration or OTP operation did not finish in time.
    Timeout,
    /// The operation is not allowed in the chip's current configuration
    /// (e.g. unidirectional input while open-loop, or OTP already programmed).
    InvalidState,
}

impl<E> From<E> for Drv2605Error<E> {
    fn from(e: E) -> Self {
        Drv2605Error::Bus(e)
    }
}

/// One step of a waveform sequence for [`Drv2605Full::play_sequence`].
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum SequenceStep {
    /// A ROM effect, identifier 1–123.
    Effect(u8),
    /// A delay of 0–1270 ms (rounded to 10 ms steps).
    Wait(u16),
}

/// Decoded `STATUS` register, returned by [`Drv2605Full::read_status`].
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct Status {
    /// The last calibration or diagnostic run failed.
    pub diag_failed: bool,
    /// The feedback controller timed out.
    pub fb_timeout: bool,
    /// Overtemperature shutdown latched.
    pub over_temp: bool,
    /// Overcurrent latched.
    pub over_current: bool,
}

/// Auto-calibration result, returned by [`Drv2605Full::calibrate`] and [`Drv2605Full::get_calibration`].
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Calibration {
    /// The calibration converged (always `true` from `get_calibration`).
    pub ok: bool,
    /// `A_CAL_COMP`.
    pub comp: u8,
    /// `A_CAL_BEMF`.
    pub bemf: u8,
    /// `BEMF_GAIN` (0–3).
    pub bemf_gain: u8,
}

/// Fields of [`Drv2605Full::set_advanced`] (`CONTROL1`–`CONTROL3`).
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct AdvancedConfig {
    /// Higher loop gain during overdrive.
    pub startup_boost: bool,
    /// Reduce loop gain near the end of braking.
    pub brake_stabilizer: bool,
    /// LRA auto-resonance sample time, 0–3.
    pub sample_time: u8,
    /// Blanking time, 0–3.
    pub blanking_time: u8,
    /// Current-dissipation time, 0–3.
    pub idiss_time: u8,
    /// Update the LRA drive twice per cycle.
    pub lra_drive_twice: bool,
    /// Keep supply compensation enabled.
    pub supply_comp: bool,
    /// Noise gate threshold, 0–3.
    pub noise_gate: u8,
}

impl Default for AdvancedConfig {
    fn default() -> Self {
        Self {
            startup_boost: true,
            brake_stabilizer: true,
            sample_time: 3,
            blanking_time: 1,
            idiss_time: 1,
            lra_drive_twice: false,
            supply_comp: true,
            noise_gate: 2,
        }
    }
}

/// Fields of [`Drv2605Full::set_audio_to_vibe`].
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct AudioToVibeConfig {
    /// Enable the 0.9 V common-mode for AC-coupled input.
    pub ac_couple: bool,
    /// Peak-detection time in ms: 10, 20, 30 or 40.
    pub peak_time_ms: u16,
    /// Low-pass filter corner in Hz: 100, 125, 150 or 200.
    pub filter_hz: u16,
    /// Minimum input level, V peak-to-peak (0–1.8).
    pub min_input_v: f32,
    /// Maximum input level, V peak-to-peak (0–1.8).
    pub max_input_v: f32,
    /// Minimum output drive fraction (0.0–1.0).
    pub min_drive: f32,
    /// Maximum output drive fraction (0.0–1.0).
    pub max_drive: f32,
}

impl Default for AudioToVibeConfig {
    fn default() -> Self {
        Self {
            ac_couple: true,
            peak_time_ms: 20,
            filter_hz: 125,
            min_input_v: 0.1,
            max_input_v: 1.8,
            min_drive: 0.1,
            max_drive: 1.0,
        }
    }
}

/// Round to the nearest integer and clamp to a register code 0–255.
fn clamp_code(value: f32) -> u8 {
    let code = (value + 0.5) as i32;
    code.clamp(0, 255) as u8
}

/// `DRIVE_TIME` register code for a time in ms (LRA: 0.5 ms + 0.1 ms × code; ERM: 1 ms + 0.2 ms × code).
fn drive_time_code(ms: f32, lra: bool) -> u8 {
    let code = if lra { (ms - 0.5) / 0.1 + 0.5 } else { (ms - 1.0) / 0.2 + 0.5 };
    (code as i32).clamp(0, 31) as u8
}

/// DRV2605 — minimal interface: initialise and play ROM effects.
pub struct Drv2605Minimal<I2C> {
    i2c: I2C,
    mode: u8,
    standby: bool,
}

impl<I2C: I2c> Drv2605Minimal<I2C> {
    /// Run the initialization sequence: `DEV_RESET`, `DEVICE_ID` check, leave
    /// standby, actuator selection (ERM: library A, open loop; LRA: LRA
    /// library, closed loop with auto-resonance).
    ///
    /// Returns [`Drv2605Error::DeviceNotFound`] if `DEVICE_ID` is not 3 and
    /// [`Drv2605Error::Timeout`] if the reset does not complete.
    pub fn new<D: DelayNs>(i2c: I2C, delay: &mut D, lra: bool) -> Result<Self, Drv2605Error<I2C::Error>> {
        let mut s = Self { i2c, mode: DRV2605_MODE_INTERNAL_TRIGGER, standby: false };
        s.reset_chip(delay)?;
        if s.read_reg(REG_STATUS)? >> 5 != DRV2605_DEVICE_ID {
            return Err(Drv2605Error::DeviceNotFound);
        }
        s.write_mode(DRV2605_MODE_INTERNAL_TRIGGER, false)?;
        if lra {
            s.write_reg(REG_FEEDBACK, 0xB6)?;
            s.write_reg(REG_LIBRARY, 0x06)?;
        } else {
            s.write_reg(REG_FEEDBACK, 0x36)?;
            s.write_reg(REG_LIBRARY, 0x01)?;
        }
        Ok(s)
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, DRV2605_I2C_ADDRESS, reg.into(), 1, &[value])
    }

    fn write_regs(&mut self, reg: u8, data: &[u8]) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, DRV2605_I2C_ADDRESS, reg.into(), 1, data)
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, DRV2605_I2C_ADDRESS, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    fn read_regs(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        register::read_register(&mut self.i2c, DRV2605_I2C_ADDRESS, reg.into(), 1, buf)
    }

    /// Read-modify-write: replace the bits in `mask` with `value` (already shifted).
    fn update_reg(&mut self, reg: u8, mask: u8, value: u8) -> Result<(), I2C::Error> {
        let old = self.read_reg(reg)?;
        self.write_reg(reg, (old & !mask) | (value & mask))
    }

    fn write_mode(&mut self, mode: u8, standby: bool) -> Result<(), I2C::Error> {
        self.write_reg(REG_MODE, if standby { MODE_STANDBY } else { 0 } | mode)?;
        self.mode = mode;
        self.standby = standby;
        Ok(())
    }

    fn reset_chip<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Drv2605Error<I2C::Error>> {
        self.write_reg(REG_MODE, MODE_DEV_RESET)?;
        self.mode = DRV2605_MODE_INTERNAL_TRIGGER;
        self.standby = true;
        delay.delay_ms(2);
        for _ in 0..100 {
            if self.read_reg(REG_MODE)? & MODE_DEV_RESET == 0 {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Drv2605Error::Timeout)
    }

    fn ensure_internal(&mut self) -> Result<(), I2C::Error> {
        if self.mode != DRV2605_MODE_INTERNAL_TRIGGER || self.standby {
            self.write_mode(DRV2605_MODE_INTERNAL_TRIGGER, false)?;
        }
        Ok(())
    }

    /// Play one ROM effect and return immediately.
    ///
    /// Writes `effect_id` to sequencer slot 1 and `0` (end) to slot 2,
    /// switches to internal-trigger mode if needed, then sets `GO`.
    /// `effect_id` must be 1–123.
    pub fn play_effect(&mut self, effect_id: u8) -> Result<(), Drv2605Error<I2C::Error>> {
        if !(1..=123).contains(&effect_id) {
            return Err(Drv2605Error::InvalidArgument);
        }
        self.ensure_internal()?;
        self.write_regs(REG_SEQ1, &[effect_id, 0])?;
        self.write_reg(REG_GO, 1)?;
        Ok(())
    }

    /// Cancel playback by clearing the `GO` bit.
    pub fn stop(&mut self) -> Result<(), I2C::Error> {
        self.write_reg(REG_GO, 0)
    }

    /// `true` while a sequence is playing (`GO` bit set).
    pub fn is_playing(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.read_reg(REG_GO)? & 0x01 != 0)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// DRV2605 — full interface: extends [`Drv2605Minimal`] with the waveform
/// sequencer, real-time playback, mode / library control, drive
/// configuration, auto-calibration, diagnostics, audio-to-vibe, status
/// readout and one-time OTP programming.
pub struct Drv2605Full<I2C> {
    inner: Drv2605Minimal<I2C>,
}

impl<I2C: I2c> Drv2605Full<I2C> {
    /// Construct the driver; runs the same initialization as [`Drv2605Minimal::new`].
    pub fn new<D: DelayNs>(i2c: I2C, delay: &mut D, lra: bool) -> Result<Self, Drv2605Error<I2C::Error>> {
        Ok(Self { inner: Drv2605Minimal::new(i2c, delay, lra)? })
    }

    /// Play one ROM effect. Delegates to [`Drv2605Minimal::play_effect`].
    pub fn play_effect(&mut self, effect_id: u8) -> Result<(), Drv2605Error<I2C::Error>> {
        self.inner.play_effect(effect_id)
    }

    /// `true` while a sequence is playing. Delegates to [`Drv2605Minimal::is_playing`].
    pub fn is_playing(&mut self) -> Result<bool, I2C::Error> {
        self.inner.is_playing()
    }

    /// Cancel playback; also leaves real-time-playback mode (output to zero).
    pub fn stop(&mut self) -> Result<(), I2C::Error> {
        self.inner.stop()?;
        if self.inner.mode == DRV2605_MODE_RTP {
            self.inner.write_reg(REG_RTP_INPUT, 0)?;
            let standby = self.inner.standby;
            self.inner.write_mode(DRV2605_MODE_INTERNAL_TRIGGER, standby)?;
        }
        Ok(())
    }

    /// Play up to 8 effects and delays. A [`SequenceStep::Wait`] is 0–1270 ms
    /// in 10 ms steps; the list is terminated with `0` if shorter than 8.
    pub fn play_sequence(&mut self, items: &[SequenceStep]) -> Result<(), Drv2605Error<I2C::Error>> {
        if items.is_empty() || items.len() > 8 {
            return Err(Drv2605Error::InvalidArgument);
        }
        let mut data = [0u8; 8];
        let mut n = 0;
        for item in items {
            data[n] = match *item {
                SequenceStep::Effect(id) if (1..=123).contains(&id) => id,
                SequenceStep::Wait(ms) if ms <= 1270 => 0x80 | ((ms + 5) / 10) as u8,
                _ => return Err(Drv2605Error::InvalidArgument),
            };
            n += 1;
        }
        let len = if n < 8 { n + 1 } else { n };
        self.inner.ensure_internal()?;
        self.inner.write_regs(REG_SEQ1, &data[..len])?;
        self.inner.write_reg(REG_GO, 1)?;
        Ok(())
    }

    /// Poll `GO` until playback finishes; returns `false` on timeout.
    pub fn wait_done<D: DelayNs>(&mut self, delay: &mut D, timeout_ms: u32) -> Result<bool, I2C::Error> {
        let mut waited = 0;
        while self.inner.is_playing()? {
            if waited >= timeout_ms {
                return Ok(false);
            }
            delay.delay_ms(2);
            waited += 2;
        }
        Ok(true)
    }

    /// Select the ROM library: `0` empty, `1`–`5` ERM libraries A–E, `6` LRA library.
    pub fn set_library(&mut self, library: u8) -> Result<(), Drv2605Error<I2C::Error>> {
        if library > 6 {
            return Err(Drv2605Error::InvalidArgument);
        }
        self.inner.update_reg(REG_LIBRARY, 0x07, library)?;
        Ok(())
    }

    /// Set `MODE<2:0>` (0–7) and leave standby.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), Drv2605Error<I2C::Error>> {
        if mode > 7 {
            return Err(Drv2605Error::InvalidArgument);
        }
        self.inner.write_mode(mode, false)?;
        Ok(())
    }

    /// Enter software standby (also stops playback).
    pub fn standby(&mut self) -> Result<(), I2C::Error> {
        let mode = self.inner.mode;
        self.inner.write_mode(mode, true)
    }

    /// Leave software standby.
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        let mode = self.inner.mode;
        self.inner.write_mode(mode, false)
    }

    /// `DEV_RESET` and wait for completion; re-create the driver afterwards.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Drv2605Error<I2C::Error>> {
        self.inner.reset_chip(delay)
    }

    /// Put the outputs in true high-impedance state (`HI_Z`).
    pub fn set_hi_z(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_LIBRARY, 0x10, if enabled { 0x10 } else { 0 })
    }

    fn is_lra(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_FEEDBACK)? & 0x80 != 0)
    }

    fn is_open_loop(&mut self, lra: bool) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_CONTROL3)? & if lra { 0x01 } else { 0x20 } != 0)
    }

    /// Select ERM (`false`) or LRA (`true`) in `N_ERM_LRA`; run [`Self::calibrate`] afterwards.
    pub fn set_actuator(&mut self, lra: bool) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_FEEDBACK, 0x80, if lra { 0x80 } else { 0 })
    }

    /// Enable open-loop drive (`ERM_OPEN_LOOP` or `LRA_OPEN_LOOP` according to the actuator type).
    pub fn set_open_loop(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mask = if self.is_lra()? { 0x01 } else { 0x20 };
        self.inner.update_reg(REG_CONTROL3, mask, if enabled { mask } else { 0 })
    }

    /// Select bidirectional (`true`) or unidirectional (`false`) input.
    /// Unidirectional is closed-loop only: returns [`Drv2605Error::InvalidState`] while open-loop.
    pub fn set_bidirectional(&mut self, enabled: bool) -> Result<(), Drv2605Error<I2C::Error>> {
        if !enabled {
            let lra = self.is_lra()?;
            if self.is_open_loop(lra)? {
                return Err(Drv2605Error::InvalidState);
            }
        }
        self.inner.update_reg(REG_CONTROL2, 0x80, if enabled { 0x80 } else { 0 })?;
        Ok(())
    }

    fn enter_rtp(&mut self) -> Result<(), I2C::Error> {
        if self.inner.mode != DRV2605_MODE_RTP || self.inner.standby {
            self.inner.write_mode(DRV2605_MODE_RTP, false)?;
        }
        Ok(())
    }

    /// Stream a signed real-time-playback amplitude (`0.0`–`1.0` → 0–127).
    /// Enters RTP mode if not already there; leave with [`Self::stop`], [`Self::set_mode`] or [`Self::standby`].
    pub fn rtp_level(&mut self, level: f32) -> Result<(), Drv2605Error<I2C::Error>> {
        if !(0.0..=1.0).contains(&level) {
            return Err(Drv2605Error::InvalidArgument);
        }
        if self.inner.mode != DRV2605_MODE_RTP {
            self.inner.update_reg(REG_CONTROL3, 0x08, 0)?;
        }
        self.enter_rtp()?;
        self.inner.write_reg(REG_RTP_INPUT, (level * 127.0 + 0.5) as u8)?;
        Ok(())
    }

    /// Write a raw `RTP_INPUT` byte, interpreted per the RTP data format; enters RTP mode.
    pub fn rtp_write(&mut self, value: u8) -> Result<(), I2C::Error> {
        self.enter_rtp()?;
        self.inner.write_reg(REG_RTP_INPUT, value)
    }

    /// Select unsigned (`true`) or signed (`false`) RTP data (`DATA_FORMAT_RTP`).
    pub fn set_rtp_format(&mut self, unsigned: bool) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_CONTROL3, 0x08, if unsigned { 0x08 } else { 0 })
    }

    /// Select analog (`true`) or PWM (`false`) input for mode 3 (`N_PWM_ANALOG`).
    pub fn set_input(&mut self, analog: bool) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_CONTROL3, 0x02, if analog { 0x02 } else { 0 })
    }

    fn volts_to_rated_code(&mut self, volts: f32, lra_freq_hz: Option<f32>) -> Result<u8, Drv2605Error<I2C::Error>> {
        if !self.is_lra()? {
            return Ok(clamp_code(volts / 21.33e-3));
        }
        let freq = match lra_freq_hz {
            Some(f) if f > 0.0 => f,
            _ => return Err(Drv2605Error::InvalidArgument),
        };
        let t_sample = SAMPLE_TIME_S[((self.inner.read_reg(REG_CONTROL2)? >> 4) & 0x03) as usize];
        let factor = 1.0 - (4.0 * t_sample + 300e-6) * freq;
        if factor <= 0.0 {
            return Err(Drv2605Error::InvalidArgument);
        }
        Ok(clamp_code(volts * libm::sqrtf(factor) / 20.71e-3))
    }

    /// Set `RATED_VOLTAGE` from volts (RMS for an LRA). `lra_freq_hz` is required for an LRA.
    pub fn set_rated_voltage(&mut self, volts: f32, lra_freq_hz: Option<f32>) -> Result<(), Drv2605Error<I2C::Error>> {
        let code = self.volts_to_rated_code(volts, lra_freq_hz)?;
        self.inner.write_reg(REG_RATED_VOLTAGE, code)?;
        Ok(())
    }

    /// Set `RATED_VOLTAGE` from a raw code.
    pub fn set_rated_voltage_code(&mut self, code: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_RATED_VOLTAGE, code)
    }

    /// Set `OD_CLAMP` from volts (RMS for LRA open loop). `lra_freq_hz` is required for LRA open loop;
    /// ERM closed loop returns [`Drv2605Error::InvalidState`] (needs undocumented timing values — use
    /// [`Self::set_overdrive_clamp_code`]).
    pub fn set_overdrive_clamp(&mut self, volts: f32, lra_freq_hz: Option<f32>) -> Result<(), Drv2605Error<I2C::Error>> {
        let lra = self.is_lra()?;
        let open_loop = self.is_open_loop(lra)?;
        let code = if lra && open_loop {
            let freq = match lra_freq_hz {
                Some(f) if f > 0.0 => f,
                _ => return Err(Drv2605Error::InvalidArgument),
            };
            let factor = 1.0 - freq * 800e-6;
            if factor <= 0.0 {
                return Err(Drv2605Error::InvalidArgument);
            }
            clamp_code(volts / (21.33e-3 * libm::sqrtf(factor)))
        } else if !lra && !open_loop {
            return Err(Drv2605Error::InvalidState);
        } else {
            clamp_code(volts / 21.96e-3)
        };
        self.inner.write_reg(REG_OD_CLAMP, code)?;
        Ok(())
    }

    /// Set `OD_CLAMP` from a raw code.
    pub fn set_overdrive_clamp_code(&mut self, code: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_OD_CLAMP, code)
    }

    /// Set `DRIVE_TIME` in ms (LRA: half the resonance period; ERM: back-EMF sample period).
    pub fn set_drive_time(&mut self, ms: f32) -> Result<(), I2C::Error> {
        let lra = self.is_lra()?;
        self.inner.update_reg(REG_CONTROL1, 0x1F, drive_time_code(ms, lra))
    }

    /// Set the `FEEDBACK_CONTROL` fields (`brake_factor` 0–7, `loop_gain` 0–3, `bemf_gain` 0–3); the actuator-type bit is preserved.
    pub fn set_feedback(&mut self, brake_factor: u8, loop_gain: u8, bemf_gain: u8) -> Result<(), Drv2605Error<I2C::Error>> {
        if brake_factor > 7 || loop_gain > 3 || bemf_gain > 3 {
            return Err(Drv2605Error::InvalidArgument);
        }
        self.inner.update_reg(REG_FEEDBACK, 0x7F, (brake_factor << 4) | (loop_gain << 2) | bemf_gain)?;
        Ok(())
    }

    /// Set the `CONTROL1`–`CONTROL3` fields not covered by other setters.
    pub fn set_advanced(&mut self, cfg: &AdvancedConfig) -> Result<(), Drv2605Error<I2C::Error>> {
        if cfg.sample_time > 3 || cfg.blanking_time > 3 || cfg.idiss_time > 3 || cfg.noise_gate > 3 {
            return Err(Drv2605Error::InvalidArgument);
        }
        self.inner.update_reg(REG_CONTROL1, 0x80, if cfg.startup_boost { 0x80 } else { 0 })?;
        self.inner.update_reg(
            REG_CONTROL2,
            0x7F,
            (if cfg.brake_stabilizer { 0x40 } else { 0 }) | (cfg.sample_time << 4) | (cfg.blanking_time << 2) | cfg.idiss_time,
        )?;
        self.inner.update_reg(
            REG_CONTROL3,
            0xD4,
            (cfg.noise_gate << 6) | (if cfg.supply_comp { 0 } else { 0x10 }) | (if cfg.lra_drive_twice { 0x04 } else { 0 }),
        )?;
        Ok(())
    }

    /// Set the `ODT`, `SPT`, `SNT`, `BRT` time offsets in ms (5 ms steps, signed 8-bit: −640…635 ms).
    pub fn set_time_offsets(
        &mut self,
        overdrive_ms: i16,
        sustain_pos_ms: i16,
        sustain_neg_ms: i16,
        brake_ms: i16,
    ) -> Result<(), Drv2605Error<I2C::Error>> {
        let mut data = [0u8; 4];
        for (slot, ms) in data.iter_mut().zip([overdrive_ms, sustain_pos_ms, sustain_neg_ms, brake_ms]) {
            let steps = if ms >= 0 { (ms + 2) / 5 } else { -((-ms + 2) / 5) };
            if !(-128..=127).contains(&steps) {
                return Err(Drv2605Error::InvalidArgument);
            }
            *slot = steps as i8 as u8;
        }
        self.inner.write_regs(REG_ODT, &data)?;
        Ok(())
    }

    /// Run the auto-calibration procedure.
    ///
    /// `overdrive_voltage` uses the open-loop conversion (V / 21.96 mV) for an
    /// ERM, since the closed-loop correction needs undocumented timing values.
    /// `lra_freq_hz` is required for an LRA and also sets `DRIVE_TIME` to half
    /// the period. The standby state is restored afterwards.
    pub fn calibrate<D: DelayNs>(
        &mut self,
        delay: &mut D,
        lra: bool,
        rated_voltage: f32,
        overdrive_voltage: f32,
        lra_freq_hz: Option<f32>,
    ) -> Result<Calibration, Drv2605Error<I2C::Error>> {
        let freq = lra_freq_hz.filter(|f| *f > 0.0);
        if lra && freq.is_none() {
            return Err(Drv2605Error::InvalidArgument);
        }
        let was_standby = self.inner.standby;
        self.inner.write_mode(DRV2605_MODE_AUTO_CALIBRATION, false)?;
        let bemf_gain = self.inner.read_reg(REG_FEEDBACK)? & 0x03;
        self.inner.write_reg(REG_FEEDBACK, (if lra { 0x80 } else { 0 }) | (2 << 4) | (2 << 2) | bemf_gain)?;
        // SAMPLE_TIME = 3, BLANKING_TIME = 1, IDISS_TIME = 1
        self.inner.update_reg(REG_CONTROL2, 0x3F, (3 << 4) | (1 << 2) | 1)?;
        if let (true, Some(f)) = (lra, freq) {
            self.inner.update_reg(REG_CONTROL1, 0x1F, drive_time_code(500.0 / f, true))?;
        }
        let rated = self.volts_to_rated_code(rated_voltage, freq);
        let rated = match rated {
            Ok(c) => c,
            Err(e) => {
                self.inner.write_mode(DRV2605_MODE_INTERNAL_TRIGGER, was_standby)?;
                return Err(e);
            }
        };
        self.inner.write_reg(REG_RATED_VOLTAGE, rated)?;
        self.inner.write_reg(REG_OD_CLAMP, clamp_code(overdrive_voltage / 21.96e-3))?;
        // AUTO_CAL_TIME = 3; OTP_PROGRAM stays 0
        self.inner.update_reg(REG_CONTROL4, 0x31, 3 << 4)?;
        self.inner.write_reg(REG_GO, 1)?;
        let finished = self.wait_done(delay, 3000)?;
        let diag_failed = self.inner.read_reg(REG_STATUS)? & 0x08 != 0;
        let mut result = self.get_calibration()?;
        result.ok = finished && !diag_failed;
        self.inner.write_mode(DRV2605_MODE_INTERNAL_TRIGGER, was_standby)?;
        Ok(result)
    }

    /// Read the stored `A_CAL_COMP`, `A_CAL_BEMF` and `BEMF_GAIN` (`ok` is always `true`).
    pub fn get_calibration(&mut self) -> Result<Calibration, I2C::Error> {
        let mut buf = [0u8; 2];
        self.inner.read_regs(REG_A_CAL_COMP, &mut buf)?;
        let bemf_gain = self.inner.read_reg(REG_FEEDBACK)? & 0x03;
        Ok(Calibration { ok: true, comp: buf[0], bemf: buf[1], bemf_gain })
    }

    /// Restore `A_CAL_COMP`, `A_CAL_BEMF` and `BEMF_GAIN` (0–3) from an earlier calibration.
    pub fn set_calibration(&mut self, comp: u8, bemf: u8, bemf_gain: u8) -> Result<(), Drv2605Error<I2C::Error>> {
        if bemf_gain > 3 {
            return Err(Drv2605Error::InvalidArgument);
        }
        self.inner.write_regs(REG_A_CAL_COMP, &[comp, bemf])?;
        self.inner.update_reg(REG_FEEDBACK, 0x03, bemf_gain)?;
        Ok(())
    }

    /// Run the actuator diagnostics; `true` if the actuator is OK (`DIAG_RESULT` = 0).
    pub fn diagnose<D: DelayNs>(&mut self, delay: &mut D) -> Result<bool, I2C::Error> {
        let was_standby = self.inner.standby;
        self.inner.write_mode(DRV2605_MODE_DIAGNOSTICS, false)?;
        self.inner.write_reg(REG_GO, 1)?;
        let finished = self.wait_done(delay, 3000)?;
        let diag_failed = self.inner.read_reg(REG_STATUS)? & 0x08 != 0;
        self.inner.write_mode(DRV2605_MODE_INTERNAL_TRIGGER, was_standby)?;
        Ok(finished && !diag_failed)
    }

    /// Configure the audio-to-vibe engine and enter audio-to-vibe mode.
    pub fn set_audio_to_vibe(&mut self, cfg: &AudioToVibeConfig) -> Result<(), Drv2605Error<I2C::Error>> {
        let peak = PEAK_TIMES_MS.iter().position(|&p| p == cfg.peak_time_ms);
        let filter = FILTERS_HZ.iter().position(|&f| f == cfg.filter_hz);
        let (peak, filter) = match (peak, filter) {
            (Some(p), Some(f)) => (p as u8, f as u8),
            _ => return Err(Drv2605Error::InvalidArgument),
        };
        if !((0.0..=1.8).contains(&cfg.min_input_v)
            && (0.0..=1.8).contains(&cfg.max_input_v)
            && (0.0..=1.0).contains(&cfg.min_drive)
            && (0.0..=1.0).contains(&cfg.max_drive))
        {
            return Err(Drv2605Error::InvalidArgument);
        }
        self.inner.write_regs(
            REG_ATH_CTRL,
            &[
                (peak << 2) | filter,
                clamp_code(cfg.min_input_v * 255.0 / 1.8),
                clamp_code(cfg.max_input_v * 255.0 / 1.8),
                clamp_code(cfg.min_drive * 255.0),
                clamp_code(cfg.max_drive * 255.0),
            ],
        )?;
        self.inner.update_reg(REG_CONTROL1, 0x20, if cfg.ac_couple { 0x20 } else { 0 })?;
        self.inner.update_reg(REG_CONTROL3, 0x02, 0x02)?;
        self.inner.write_mode(DRV2605_MODE_AUDIO_TO_VIBE, false)?;
        Ok(())
    }

    /// Read `STATUS` (clears the latched `DIAG_RESULT`, `FB_STS` and `OVER_TEMP` flags).
    pub fn read_status(&mut self) -> Result<Status, I2C::Error> {
        let s = self.inner.read_reg(REG_STATUS)?;
        Ok(Status {
            diag_failed: s & 0x08 != 0,
            fb_timeout: s & 0x04 != 0,
            over_temp: s & 0x02 != 0,
            over_current: s & 0x01 != 0,
        })
    }

    /// `DEVICE_ID` (3 for the DRV2605). Clears the latched status flags.
    pub fn read_device_id(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.inner.read_reg(REG_STATUS)? >> 5)
    }

    /// VDD in V from `VBAT`; valid only during playback.
    pub fn read_supply_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.read_reg(REG_VBAT)? as f32 * 5.6 / 255.0)
    }

    /// LRA resonance frequency in Hz from `LRA_PERIOD`; valid only during LRA playback. `None` if the register is 0.
    pub fn read_lra_frequency(&mut self) -> Result<Option<f32>, I2C::Error> {
        let raw = self.inner.read_reg(REG_LRA_PERIOD)?;
        Ok(if raw == 0 { None } else { Some(1e6 / (raw as f32 * 98.46)) })
    }

    /// Burn registers `0x16`–`0x1A` into OTP. **Irreversible, one time only**; VDD must be 4.0–4.4 V.
    ///
    /// `confirm` must be `true`. Returns [`Drv2605Error::InvalidState`] if the OTP is
    /// already programmed and [`Drv2605Error::Timeout`] if programming does not complete.
    pub fn program_otp<D: DelayNs>(&mut self, delay: &mut D, confirm: bool) -> Result<(), Drv2605Error<I2C::Error>> {
        if !confirm {
            return Err(Drv2605Error::InvalidArgument);
        }
        if self.otp_programmed()? {
            return Err(Drv2605Error::InvalidState);
        }
        self.inner.update_reg(REG_CONTROL4, 0x01, 0x01)?;
        for _ in 0..100 {
            delay.delay_ms(10);
            if self.otp_programmed()? {
                return Ok(());
            }
        }
        Err(Drv2605Error::Timeout)
    }

    /// `OTP_STATUS`: `true` if the OTP has been programmed.
    pub fn otp_programmed(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_CONTROL4)? & 0x04 != 0)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const A: u8 = DRV2605_I2C_ADDRESS;

    fn rd(reg: u8, val: u8) -> T {
        T::write_read(A, vec![reg], vec![val])
    }
    fn wr(reg: u8, val: u8) -> T {
        T::write(A, vec![reg, val])
    }
    fn wrn(reg: u8, vals: &[u8]) -> T {
        let mut v = vec![reg];
        v.extend_from_slice(vals);
        T::write(A, v)
    }

    /// Transactions of a successful ERM `new()`.
    fn init_erm() -> Vec<T> {
        vec![wr(0x01, 0x80), rd(0x01, 0x00), rd(0x00, 0x60), wr(0x01, 0x00), wr(0x1A, 0x36), wr(0x03, 0x01)]
    }

    #[test]
    fn init_erm_and_lra() {
        let mut d = NoopDelay::new();
        let mut i2c = I2cMock::new(&init_erm());
        Drv2605Minimal::new(i2c.clone(), &mut d, false).expect("init erm");
        i2c.done();

        let tr = vec![wr(0x01, 0x80), rd(0x01, 0x00), rd(0x00, 0x60), wr(0x01, 0x00), wr(0x1A, 0xB6), wr(0x03, 0x06)];
        let mut i2c = I2cMock::new(&tr);
        Drv2605Minimal::new(i2c.clone(), &mut d, true).expect("init lra");
        i2c.done();
    }

    #[test]
    fn init_rejects_wrong_device_id() {
        let mut d = NoopDelay::new();
        let tr = vec![wr(0x01, 0x80), rd(0x01, 0x00), rd(0x00, 0xE0)];
        let mut i2c = I2cMock::new(&tr);
        let r = Drv2605Minimal::new(i2c.clone(), &mut d, false);
        assert!(matches!(r, Err(Drv2605Error::DeviceNotFound)));
        i2c.done();
    }

    #[test]
    fn minimal_playback() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([wrn(0x04, &[47, 0]), wr(0x0C, 1), wr(0x0C, 0), rd(0x0C, 1), rd(0x0C, 0)]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Minimal::new(i2c.clone(), &mut d, false).expect("init");
        h.play_effect(47).expect("play");
        assert!(matches!(h.play_effect(0), Err(Drv2605Error::InvalidArgument)));
        assert!(matches!(h.play_effect(124), Err(Drv2605Error::InvalidArgument)));
        h.stop().expect("stop");
        assert!(h.is_playing().expect("playing"));
        assert!(!h.is_playing().expect("idle"));
        i2c.done();
    }

    #[test]
    fn sequence_encoding() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([
            wrn(0x04, &[1, 0x8A, 10, 0]),
            wr(0x0C, 1),
            wrn(0x04, &[1, 2, 3, 4, 5, 6, 7, 8]),
            wr(0x0C, 1),
        ]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        h.play_sequence(&[SequenceStep::Effect(1), SequenceStep::Wait(100), SequenceStep::Effect(10)]).expect("seq");
        let eight: Vec<SequenceStep> = (1..=8).map(SequenceStep::Effect).collect();
        h.play_sequence(&eight).expect("eight");
        let nine: Vec<SequenceStep> = (1..=9).map(SequenceStep::Effect).collect();
        assert!(matches!(h.play_sequence(&nine), Err(Drv2605Error::InvalidArgument)));
        assert!(matches!(h.play_sequence(&[SequenceStep::Wait(2000)]), Err(Drv2605Error::InvalidArgument)));
        assert!(matches!(h.play_sequence(&[]), Err(Drv2605Error::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn wait_done_and_timeout() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([rd(0x0C, 1), rd(0x0C, 0), rd(0x0C, 1), rd(0x0C, 1), rd(0x0C, 1)]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        assert!(h.wait_done(&mut d, 50).expect("done"));
        assert!(!h.wait_done(&mut d, 4).expect("timeout"));
        i2c.done();
    }

    #[test]
    fn mode_standby_library_and_hi_z() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([
            wr(0x01, 0x05),
            wr(0x01, 0x45),
            wr(0x01, 0x05),
            rd(0x03, 0x01),
            wr(0x03, 0x11),
            rd(0x03, 0x11),
            wr(0x03, 0x16),
        ]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        h.set_mode(5).expect("mode");
        assert!(matches!(h.set_mode(8), Err(Drv2605Error::InvalidArgument)));
        h.standby().expect("standby");
        h.wake().expect("wake");
        h.set_hi_z(true).expect("hi_z");
        h.set_library(6).expect("library keeps HI_Z");
        assert!(matches!(h.set_library(7), Err(Drv2605Error::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn rtp_enter_and_leave() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([
            rd(0x1D, 0x28),
            wr(0x1D, 0x20),
            wr(0x01, 0x05),
            wr(0x02, 64),
            wr(0x02, 200),
            wr(0x0C, 0),
            wr(0x02, 0),
            wr(0x01, 0x00),
        ]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        h.rtp_level(0.5).expect("rtp level");
        h.rtp_write(200).expect("rtp write");
        assert!(matches!(h.rtp_level(1.5), Err(Drv2605Error::InvalidArgument)));
        h.stop().expect("stop leaves rtp");
        i2c.done();
    }

    #[test]
    fn open_loop_constraint() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([rd(0x1A, 0x36), rd(0x1D, 0x20), rd(0x1A, 0x36), rd(0x1D, 0x00), rd(0x1C, 0xF5), wr(0x1C, 0x75)]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        assert!(matches!(h.set_bidirectional(false), Err(Drv2605Error::InvalidState)));
        h.set_bidirectional(false).expect("closed loop ok");
        i2c.done();
    }

    #[test]
    fn rated_voltage_and_clamp() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([
            rd(0x1A, 0x36),
            wr(0x16, 141),
            wr(0x16, 77),
            rd(0x1A, 0x36),
            rd(0x1D, 0x20),
            wr(0x17, 137),
            rd(0x1A, 0x36),
            rd(0x1D, 0x00),
            rd(0x1A, 0xB6),
            rd(0x1C, 0x30),
            wr(0x16, 81),
            rd(0x1A, 0xB6),
        ]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        h.set_rated_voltage(3.0, None).expect("erm rated");
        h.set_rated_voltage_code(77).expect("raw code");
        h.set_overdrive_clamp(3.0, None).expect("erm open-loop clamp");
        assert!(matches!(h.set_overdrive_clamp(3.0, None), Err(Drv2605Error::InvalidState)));
        h.set_rated_voltage(2.0, Some(200.0)).expect("lra rated");
        assert!(matches!(h.set_rated_voltage(2.0, None), Err(Drv2605Error::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn drive_time_feedback_and_offsets() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([
            rd(0x1A, 0xB6),
            rd(0x1B, 0x93),
            wr(0x1B, 0x94),
            rd(0x1A, 0x80),
            wr(0x1A, 0x80 | (2 << 4) | (2 << 2) | 1),
            wrn(0x0D, &[2, 0xFF, 0, 0x80]),
        ]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        h.set_drive_time(2.5).expect("lra drive time -> code 20");
        h.set_feedback(2, 2, 1).expect("feedback keeps type");
        assert!(matches!(h.set_feedback(8, 0, 0), Err(Drv2605Error::InvalidArgument)));
        h.set_time_offsets(10, -5, 0, -640).expect("offsets");
        assert!(matches!(h.set_time_offsets(0, 0, 0, 700), Err(Drv2605Error::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn calibrate_erm() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([
            wr(0x01, 0x07),
            rd(0x1A, 0x36),
            wr(0x1A, (2 << 4) | (2 << 2) | 2),
            rd(0x1C, 0xF5),
            wr(0x1C, 0xF5 & 0xC0 | 0x35),
            rd(0x1A, 0x36),
            wr(0x16, 141),
            wr(0x17, 137),
            rd(0x1E, 0x20),
            wr(0x1E, 0x30),
            wr(0x0C, 1),
            rd(0x0C, 0),
            rd(0x00, 0x60),
            T::write_read(A, vec![0x18], vec![0x0D, 0x6D]),
            rd(0x1A, 0x36),
            wr(0x01, 0x00),
        ]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        let c = h.calibrate(&mut d, false, 3.0, 3.0, None).expect("calibrate");
        assert_eq!(c, Calibration { ok: true, comp: 0x0D, bemf: 0x6D, bemf_gain: 2 });
        assert!(matches!(h.calibrate(&mut d, true, 2.0, 2.5, None), Err(Drv2605Error::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn set_calibration_and_status() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([
            wrn(0x18, &[0x20, 0x80]),
            rd(0x1A, 0x36),
            wr(0x1A, 0x35),
            rd(0x00, 0x60 | 0x08 | 0x01),
            rd(0x00, 0x60),
            rd(0x21, 255),
            rd(0x22, 0),
            rd(0x22, 52),
        ]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        h.set_calibration(0x20, 0x80, 1).expect("set_calibration");
        assert!(matches!(h.set_calibration(0, 0, 4), Err(Drv2605Error::InvalidArgument)));
        let s = h.read_status().expect("status");
        assert!(s.diag_failed && s.over_current && !s.over_temp && !s.fb_timeout);
        assert_eq!(h.read_device_id().expect("id"), 3);
        assert!((h.read_supply_voltage().expect("vdd") - 5.6).abs() < 1e-4);
        assert!(h.read_lra_frequency().expect("none").is_none());
        let f = h.read_lra_frequency().expect("freq").expect("some");
        assert!((f - 195.3).abs() < 0.2);
        i2c.done();
    }

    #[test]
    fn audio_to_vibe_and_otp_guards() {
        let mut d = NoopDelay::new();
        let mut tr = init_erm();
        tr.extend([
            wrn(0x11, &[(2 << 2) | 2, 0, 255, 0, 255]),
            rd(0x1B, 0x93),
            wr(0x1B, 0xB3),
            rd(0x1D, 0xA0),
            wr(0x1D, 0xA2),
            wr(0x01, 0x04),
            rd(0x1E, 0x04),
        ]);
        let mut i2c = I2cMock::new(&tr);
        let mut h = Drv2605Full::new(i2c.clone(), &mut d, false).expect("init");
        let cfg = AudioToVibeConfig { peak_time_ms: 30, filter_hz: 150, min_input_v: 0.0, min_drive: 0.0, ..Default::default() };
        h.set_audio_to_vibe(&cfg).expect("atv");
        let bad = AudioToVibeConfig { filter_hz: 300, ..Default::default() };
        assert!(matches!(h.set_audio_to_vibe(&bad), Err(Drv2605Error::InvalidArgument)));
        assert!(matches!(h.program_otp(&mut d, false), Err(Drv2605Error::InvalidArgument)));
        assert!(matches!(h.program_otp(&mut d, true), Err(Drv2605Error::InvalidState)));
        i2c.done();
    }
}
