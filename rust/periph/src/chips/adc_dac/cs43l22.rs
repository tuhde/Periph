//! CS43L22 — low-power stereo DAC with headphone and Class-D speaker amplifiers (Cirrus Logic).
//!
//! Configured entirely through the I²C control port (7-bit address `0x4A` with AD0 = DGND,
//! `0x4B` with AD0 = VL; 100 kHz maximum). Audio data travels over a separate I²S / PCM port that
//! this driver does not touch: the application supplies MCLK and the stream, calls
//! [`CS43L22Minimal::start`] once the clocks run and [`CS43L22Minimal::stop`] before they go away.
//!
//! Registers have a one-byte address and are written one at a time with
//! [`register::write_register`]. The constructors make no bus access; [`CS43L22Minimal::init`]
//! (or [`CS43L22Minimal::init_with_reset`] with the active-low RESET pin) verifies the chip ID,
//! loads the defaults while the chip is powered down and runs the required initialization block.
//!
//! Methods that wait take an `embedded_hal::delay::DelayNs`: `init*` (RESET pulse) and `stop`
//! (100 µs before MCLK may be removed).
//!
//! See `specs/adc_dac/cs43l22.md`.

use embedded_hal::delay::DelayNs;
use embedded_hal::digital::OutputPin;
use embedded_hal::i2c::I2c;

use crate::connection::register;

/// 7-bit I²C address with AD0 = DGND (`0x4B` with AD0 = VL).
pub const CS43L22_I2C_ADDRESS: u8 = 0x4A;

const REG_ID: u8 = 0x01;
const REG_POWER_CTL_1: u8 = 0x02;
const REG_POWER_CTL_2: u8 = 0x04;
const REG_CLOCKING_CTL: u8 = 0x05;
const REG_INTERFACE_CTL_1: u8 = 0x06;
const REG_INTERFACE_CTL_2: u8 = 0x07;
const REG_PASSTHRU_A_SEL: u8 = 0x08;
const REG_PASSTHRU_B_SEL: u8 = 0x09;
const REG_ANALOG_ZC_SR: u8 = 0x0A;
const REG_PLAYBACK_CTL_1: u8 = 0x0D;
const REG_MISC_CTL: u8 = 0x0E;
const REG_PLAYBACK_CTL_2: u8 = 0x0F;
const REG_PASSTHRU_A_VOL: u8 = 0x14;
const REG_PASSTHRU_B_VOL: u8 = 0x15;
const REG_PCM_A_VOL: u8 = 0x1A;
const REG_PCM_B_VOL: u8 = 0x1B;
const REG_BEEP_FREQ_ON: u8 = 0x1C;
const REG_BEEP_VOL_OFF: u8 = 0x1D;
const REG_BEEP_TONE_CFG: u8 = 0x1E;
const REG_TONE_CTL: u8 = 0x1F;
const REG_MASTER_A_VOL: u8 = 0x20;
const REG_MASTER_B_VOL: u8 = 0x21;
const REG_HP_A_VOL: u8 = 0x22;
const REG_HP_B_VOL: u8 = 0x23;
const REG_SPK_A_VOL: u8 = 0x24;
const REG_SPK_B_VOL: u8 = 0x25;
const REG_PCM_MIXER_SWAP: u8 = 0x26;
const REG_LIMIT_CTL_1: u8 = 0x27;
const REG_LIMIT_CTL_2: u8 = 0x28;
const REG_LIMIT_ATTACK: u8 = 0x29;
const REG_STATUS: u8 = 0x2E;
const REG_BATTERY_COMP: u8 = 0x2F;
const REG_VP_LEVEL: u8 = 0x30;
const REG_SPEAKER_STATUS: u8 = 0x31;
const REG_CHARGE_PUMP_FREQ: u8 = 0x34;
const REG_INIT_00: u8 = 0x00;
const REG_INIT_32: u8 = 0x32;
const REG_INIT_47: u8 = 0x47;

/// Output power mode: on when the SPKR/HP_SW pin is low.
pub const OUT_FOLLOW_LOW: u8 = 0;
/// Output power mode: on when the SPKR/HP_SW pin is high.
pub const OUT_FOLLOW_HIGH: u8 = 1;
/// Output power mode: always on.
pub const OUT_ON: u8 = 2;
/// Output power mode: always off.
pub const OUT_OFF: u8 = 3;
/// Serial format: left-justified.
pub const FMT_LJ: u8 = 0;
/// Serial format: I²S.
pub const FMT_I2S: u8 = 1;
/// Serial format: right-justified.
pub const FMT_RJ: u8 = 2;
/// Beep mode: off.
pub const BEEP_OFF: u8 = 0;
/// Beep mode: a single beep.
pub const BEEP_SINGLE: u8 = 1;
/// Beep mode: repeated beeps.
pub const BEEP_MULTIPLE: u8 = 2;
/// Beep mode: continuous tone.
pub const BEEP_CONTINUOUS: u8 = 3;
/// PCM source: channel as is.
pub const SRC_NORMAL: u8 = 0;
/// PCM source: (L+R)/2.
pub const SRC_MIX: u8 = 1;
/// PCM source: swapped channel.
pub const SRC_SWAPPED: u8 = 3;
/// Headphone gain index for G = 0.3959 … 1.143.
pub const GAIN_0_3959: u8 = 0;
/// Headphone gain index for G = 0.4571.
pub const GAIN_0_4571: u8 = 1;
/// Headphone gain index for G = 0.5111.
pub const GAIN_0_5111: u8 = 2;
/// Headphone gain index for G = 0.6047 (default).
pub const GAIN_0_6047: u8 = 3;
/// Headphone gain index for G = 0.7099.
pub const GAIN_0_7099: u8 = 4;
/// Headphone gain index for G = 0.8399.
pub const GAIN_0_8399: u8 = 5;
/// Headphone gain index for G = 1.000.
pub const GAIN_1_000: u8 = 6;
/// Headphone gain index for G = 1.143.
pub const GAIN_1_143: u8 = 7;

const HP_GAINS: [f32; 8] = [0.3959, 0.4571, 0.5111, 0.6047, 0.7099, 0.8399, 1.000, 1.1430];
const LIMIT_DB: [i32; 8] = [0, -3, -6, -9, -12, -18, -24, -30];

/// Errors returned by the CS43L22 drivers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cs43l22Error<E> {
    /// The underlying I²C bus failed.
    I2c(E),
    /// The RESET pin could not be driven.
    Pin,
    /// The chip ID register did not read `0x1C` in bits 7:3.
    NotFound,
    /// An argument is out of range, or the register is reserved / read-only.
    InvalidArgument,
    /// A beep is active; stop it before changing the beep timing.
    BeepActive,
}

impl<E> From<E> for Cs43l22Error<E> {
    fn from(e: E) -> Self {
        Cs43l22Error::I2c(e)
    }
}

/// Clocking settings (arguments of [`CS43L22Full::set_clocking`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Clocking {
    /// Auto-detect the speed mode from MCLK / LRCK (slave mode).
    pub auto: bool,
    /// Speed mode 0–3 (double, single, half, quarter).
    pub speed: u8,
    /// True for 8, 16 and 32 kHz sample rates.
    pub group_32k: bool,
    /// True if MCLK is 27 MHz.
    pub video_clk: bool,
    /// Internal MCLK / LRCK ratio code 0–3 (128, 125, 132, 136).
    pub ratio: u8,
    /// Divide MCLK by 2.
    pub mclk_div2: bool,
}

impl Default for Clocking {
    fn default() -> Self {
        Self { auto: true, speed: 1, group_32k: false, video_clk: false, ratio: 0, mclk_div2: false }
    }
}

/// Serial audio port configuration for [`CS43L22Full::set_interface`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Interface {
    /// Master mode (clocks are outputs).
    pub master: bool,
    /// [`FMT_LJ`], [`FMT_I2S`] or [`FMT_RJ`].
    pub fmt: u8,
    /// AWL code 0–3.
    pub word_length: u8,
    /// DSP (data-packed) mode; requires [`FMT_LJ`].
    pub dsp: bool,
    /// Invert the SCLK polarity.
    pub invert_sclk: bool,
    /// SCLK = MCLK (master mode).
    pub sclk_eq_mclk: bool,
    /// Invert the SPKR/HP_SW pin sense.
    pub invert_switch: bool,
}

impl Default for Interface {
    fn default() -> Self {
        Self { master: false, fmt: FMT_I2S, word_length: 0, dsp: false, invert_sclk: false, sclk_eq_mclk: false, invert_switch: false }
    }
}

/// Peak limiter configuration for [`CS43L22Full::set_limiter`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limiter {
    /// Enable the limiter.
    pub enabled: bool,
    /// Maximum threshold, nearest of 0, −3, −6, −9, −12, −18, −24, −30 dB.
    pub max_db: i32,
    /// Cushion threshold, same steps.
    pub cushion_db: i32,
    /// Release rate 0 (fastest) … 63.
    pub release: u8,
    /// Attack rate 0 … 63.
    pub attack: u8,
    /// Limit both channels together.
    pub limit_all: bool,
    /// Disable soft ramp while limiting.
    pub soft_ramp_disable: bool,
    /// Disable zero-cross while limiting.
    pub zero_cross_disable: bool,
}

impl Default for Limiter {
    fn default() -> Self {
        Self {
            enabled: false, max_db: 0, cushion_db: 0, release: 0x3F, attack: 0, limit_all: true,
            soft_ramp_disable: false, zero_cross_disable: false,
        }
    }
}

/// Decoded `STATUS` register (reading it clears the flags).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Status {
    /// MCLK / LRCK ratio invalid (reads 1 right after the clocks start).
    pub clock_error: bool,
    /// DSP channel A overflow.
    pub dsp_a_overflow: bool,
    /// DSP channel B overflow.
    pub dsp_b_overflow: bool,
    /// PCM channel A overflow.
    pub pcm_a_overflow: bool,
    /// PCM channel B overflow.
    pub pcm_b_overflow: bool,
}

/// Decoded `SPEAKER_STATUS` register.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SpeakerStatus {
    /// Speaker A output shorted to ground.
    pub speaker_a_short: bool,
    /// Speaker B output shorted to ground.
    pub speaker_b_short: bool,
    /// Level of the SPKR/HP_SW pin.
    pub spkr_hp_pin: bool,
}

fn floor_i32(x: f32) -> i32 {
    let i = x as i32;
    if x < i as f32 { i - 1 } else { i }
}

fn round_half_up(x: f32) -> i32 {
    floor_i32(x + 0.5)
}

fn clampf(x: f32, lo: f32, hi: f32) -> f32 {
    if x < lo { lo } else if x > hi { hi } else { x }
}

fn half_db_code(db: f32, lo: f32, hi: f32) -> u8 {
    (round_half_up(clampf(db, lo, hi) * 2.0) & 0xFF) as u8
}

fn limited_volume_code(db: f32) -> u8 {
    if db < -96.0 {
        return 0x01;
    }
    (round_half_up(clampf(db, -96.0, 0.0) * 2.0) & 0xFF) as u8
}

fn nearest_limit(db: i32) -> u8 {
    let mut best = 0usize;
    let mut best_err = (db - LIMIT_DB[0]).abs();
    for i in 1..8 {
        let err = (db - LIMIT_DB[i]).abs();
        if err < best_err {
            best = i;
            best_err = err;
        }
    }
    best as u8
}

fn is_near(v: f32, c: f32) -> bool {
    let d = if v > c { v - c } else { c - v };
    d <= c * 1e-4
}

fn near_any(v: f32, list: &[f32]) -> bool {
    list.iter().any(|c| is_near(v, *c))
}

fn documented(reg: u8) -> bool {
    matches!(
        reg,
        0x00 | 0x01 | 0x02 | 0x04..=0x0A | 0x0C..=0x0F | 0x14 | 0x15 | 0x1A..=0x29 | 0x2E..=0x32 | 0x34 | 0x47
    )
}

/// Datasheet Table 1 clocking settings for an MCLK / sample-rate pair.
///
/// `mclk_hz` is one of 12.288, 11.2896, 18.432, 16.9344, 12.0, 24.0 or 27.0 MHz and `fs_hz` a
/// sample rate listed for it. Returns `None` for combinations not in the table. The result has
/// `auto = false` and is meant for [`CS43L22Full::set_clocking`].
pub fn clocking_for(mclk_hz: u32, fs_hz: f32) -> Option<Clocking> {
    let mclk = mclk_hz as f32;
    let round: [f32; 7] = [8000.0, 12000.0, 16000.0, 24000.0, 32000.0, 48000.0, 96000.0];
    let k44: [f32; 4] = [11025.0, 22050.0, 44100.0, 88200.0];
    let frac: [f32; 4] = [12e6 / 1088.0, 12e6 / 544.0, 12e6 / 272.0, 12e6 / 136.0];
    let m27: [f32; 6] = [8000.0, 12000.0, 16000.0, 24000.0, 32000.0, 48000.0];
    let mut video = false;
    let mut div2 = false;
    let mut ratio: Option<u8> = None;
    if is_near(mclk, 12_288_000.0) || is_near(mclk, 18_432_000.0) {
        if near_any(fs_hz, &round) {
            ratio = Some(0);
        }
    } else if is_near(mclk, 11_289_600.0) {
        if near_any(fs_hz, &k44) {
            ratio = Some(0);
        }
    } else if is_near(mclk, 16_934_400.0) {
        if near_any(fs_hz, &k44) {
            ratio = Some(0);
        } else if is_near(fs_hz, 16_934_400.0 / 2112.0) {
            ratio = Some(2);
        }
    } else if is_near(mclk, 12_000_000.0) || is_near(mclk, 24_000_000.0) {
        div2 = mclk > 18_000_000.0;
        if near_any(fs_hz, &round) {
            ratio = Some(1);
        } else if near_any(fs_hz, &frac) {
            ratio = Some(3);
        }
    } else if is_near(mclk, 27_000_000.0) {
        video = true;
        if near_any(fs_hz, &m27) {
            ratio = Some(1);
        } else if near_any(fs_hz, &frac[..3]) {
            ratio = Some(3);
        }
    }
    let ratio = ratio?;
    let group_32k = near_any(fs_hz, &[8000.0, 16000.0, 32000.0]);
    let speed = if fs_hz <= 12100.0 { 3 } else if fs_hz <= 24100.0 { 2 } else if fs_hz <= 48100.0 { 1 } else { 0 };
    Some(Clocking { auto: false, speed, group_32k, video_clk: video, ratio, mclk_div2: div2 })
}

/// CS43L22 minimal driver — bring the DAC to a playing state and control volume and mute of the
/// headphone output.
///
/// Defaults: I²S slave, 24-bit, clock speed auto-detected from the MCLK / LRCK ratio, headphone
/// amplifiers always on, speaker amplifiers off, headphone gain 0.6047, master volume 0 dB,
/// digital soft ramp on. The chip stays powered down until [`start`](Self::start).
pub struct CS43L22Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> CS43L22Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic. `addr` is `0x4A` or `0x4B`.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    fn w(&mut self, reg: u8, value: u8) -> Result<(), Cs43l22Error<I2C::Error>> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value])?;
        Ok(())
    }

    fn r(&mut self, reg: u8) -> Result<u8, Cs43l22Error<I2C::Error>> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    fn upd(&mut self, reg: u8, mask: u8, value: u8) -> Result<(), Cs43l22Error<I2C::Error>> {
        let v = (self.r(reg)? & !mask) | (value & mask);
        self.w(reg, v)
    }

    fn set_bit(&mut self, reg: u8, bit: u8, on: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.upd(reg, 1 << bit, if on { 1 << bit } else { 0 })
    }

    /// Verify the chip ID, load the defaults and run the required initialization. The chip stays
    /// powered down.
    ///
    /// # Errors
    /// [`Cs43l22Error::NotFound`] if register `0x01` does not read `0x1C` in bits 7:3.
    pub fn init(&mut self) -> Result<(), Cs43l22Error<I2C::Error>> {
        if self.r(REG_ID)? & 0xF8 != 0xE0 {
            return Err(Cs43l22Error::NotFound);
        }
        self.w(REG_POWER_CTL_1, 0x01)?;
        self.w(REG_POWER_CTL_2, 0xAF)?;
        self.w(REG_CLOCKING_CTL, 0xA0)?;
        self.w(REG_INTERFACE_CTL_1, 0x04)?;
        self.w(REG_PLAYBACK_CTL_1, 0x60)?;
        self.w(REG_MASTER_A_VOL, 0x00)?;
        self.w(REG_MASTER_B_VOL, 0x00)?;
        // Required initialization settings (datasheet §4.11).
        self.w(REG_INIT_00, 0x99)?;
        self.w(REG_INIT_47, 0x80)?;
        let v = self.r(REG_INIT_32)?;
        self.w(REG_INIT_32, v | 0x80)?;
        self.w(REG_INIT_32, v & 0x7F)?;
        self.w(REG_INIT_00, 0x00)
    }

    /// Pulse the active-low RESET pin (low ≥ 1 ms, then high), then run [`init`](Self::init).
    pub fn init_with_reset<P: OutputPin, D: DelayNs>(
        &mut self, res_pin: &mut P, delay: &mut D,
    ) -> Result<(), Cs43l22Error<I2C::Error>> {
        res_pin.set_low().map_err(|_| Cs43l22Error::Pin)?;
        delay.delay_ms(2);
        res_pin.set_high().map_err(|_| Cs43l22Error::Pin)?;
        delay.delay_ms(2);
        self.init()
    }

    /// Power the chip up (`POWER_CTL_1` = `0x9E`). Call after MCLK, SCLK and LRCK are running.
    pub fn start(&mut self) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.w(REG_POWER_CTL_1, 0x9E)
    }

    /// Power-down sequence: mute, disable soft ramp / zero-cross, `POWER_CTL_1` = `0x9F`, wait
    /// 100 µs. MCLK may be removed after it returns.
    pub fn stop<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.upd(REG_PLAYBACK_CTL_2, 0xF0, 0xF0)?;
        self.upd(REG_MISC_CTL, 0x03, 0x00)?;
        self.upd(REG_ANALOG_ZC_SR, 0x0F, 0x00)?;
        self.w(REG_POWER_CTL_1, 0x9F)?;
        delay.delay_us(200);
        Ok(())
    }

    /// Set the master volume of both channels: −102 … +12 dB, nearest 0.5 dB step (clamped).
    pub fn set_volume(&mut self, db: f32) -> Result<(), Cs43l22Error<I2C::Error>> {
        let code = half_db_code(db, -102.0, 12.0);
        self.w(REG_MASTER_A_VOL, code)?;
        self.w(REG_MASTER_B_VOL, code)
    }

    /// Master volume of channel A in dB.
    pub fn get_volume(&mut self) -> Result<f32, Cs43l22Error<I2C::Error>> {
        Ok((self.r(REG_MASTER_A_VOL)? as i8) as f32 / 2.0)
    }

    /// Mute (`true`) or unmute (`false`) both headphone channels.
    pub fn mute(&mut self, on: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.upd(REG_PLAYBACK_CTL_2, 0xC0, if on { 0xC0 } else { 0 })
    }
}

/// CS43L22 full driver — adds per-channel volumes, output power, clocking and serial interface,
/// tone control, beep generator, peak limiter, soft ramp / zero-cross, analog passthrough, battery
/// compensation, status reads and raw register access. Wraps [`CS43L22Minimal`].
pub struct CS43L22Full<I2C> {
    inner: CS43L22Minimal<I2C>,
}

impl<I2C: I2c> CS43L22Full<I2C> {
    /// Construct the driver; performs no I²C traffic.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: CS43L22Minimal::new(i2c, addr) }
    }

    /// See [`CS43L22Minimal::init`].
    pub fn init(&mut self) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.init()
    }

    /// See [`CS43L22Minimal::init_with_reset`].
    pub fn init_with_reset<P: OutputPin, D: DelayNs>(
        &mut self, res_pin: &mut P, delay: &mut D,
    ) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.init_with_reset(res_pin, delay)
    }

    /// See [`CS43L22Minimal::start`].
    pub fn start(&mut self) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.start()
    }

    /// See [`CS43L22Minimal::stop`].
    pub fn stop<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.stop(delay)
    }

    /// See [`CS43L22Minimal::set_volume`].
    pub fn set_volume(&mut self, db: f32) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.set_volume(db)
    }

    /// See [`CS43L22Minimal::get_volume`].
    pub fn get_volume(&mut self) -> Result<f32, Cs43l22Error<I2C::Error>> {
        self.inner.get_volume()
    }

    /// See [`CS43L22Minimal::mute`].
    pub fn mute(&mut self, on: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.mute(on)
    }

    /// Read the chip ID and revision: `(0x1C, revision)`.
    pub fn read_id(&mut self) -> Result<(u8, u8), Cs43l22Error<I2C::Error>> {
        let v = self.inner.r(REG_ID)?;
        Ok((v >> 3, v & 0x07))
    }

    /// Pulse RESET and re-run the Minimal initialization (alias of
    /// [`CS43L22Minimal::init_with_reset`]).
    pub fn reset<P: OutputPin, D: DelayNs>(
        &mut self, res_pin: &mut P, delay: &mut D,
    ) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.init_with_reset(res_pin, delay)
    }

    /// Power mode of the headphone and speaker amplifiers ([`OUT_ON`] …), applied to both channels.
    pub fn set_outputs(&mut self, headphone: u8, speaker: u8) -> Result<(), Cs43l22Error<I2C::Error>> {
        if headphone > 3 || speaker > 3 {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.w(REG_POWER_CTL_2, (headphone << 6) | (headphone << 4) | (speaker << 2) | speaker)
    }

    /// Independent master volume of channel A (left) and B (right), −102 … +12 dB.
    pub fn set_volume_channels(&mut self, left_db: f32, right_db: f32) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.w(REG_MASTER_A_VOL, half_db_code(left_db, -102.0, 12.0))?;
        self.inner.w(REG_MASTER_B_VOL, half_db_code(right_db, -102.0, 12.0))
    }

    /// Headphone volume 0 … −96 dB; below −96 dB the channel is muted (code `0x01`).
    pub fn set_headphone_volume(&mut self, left_db: f32, right_db: Option<f32>) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.w(REG_HP_A_VOL, limited_volume_code(left_db))?;
        self.inner.w(REG_HP_B_VOL, limited_volume_code(right_db.unwrap_or(left_db)))
    }

    /// Speaker volume 0 … −96 dB; below −96 dB the channel is muted.
    pub fn set_speaker_volume(&mut self, left_db: f32, right_db: Option<f32>) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.w(REG_SPK_A_VOL, limited_volume_code(left_db))?;
        self.inner.w(REG_SPK_B_VOL, limited_volume_code(right_db.unwrap_or(left_db)))
    }

    /// PCM mixer volume −51.5 … +12 dB (the mute bits are kept).
    pub fn set_pcm_volume(&mut self, left_db: f32, right_db: Option<f32>) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.upd(REG_PCM_A_VOL, 0x7F, half_db_code(left_db, -51.5, 12.0))?;
        self.inner.upd(REG_PCM_B_VOL, 0x7F, half_db_code(right_db.unwrap_or(left_db), -51.5, 12.0))
    }

    /// Mute or unmute the PCM channels (`right` `None` = same as `left`).
    pub fn mute_pcm(&mut self, left: bool, right: Option<bool>) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.set_bit(REG_PCM_A_VOL, 7, left)?;
        self.inner.set_bit(REG_PCM_B_VOL, 7, right.unwrap_or(left))
    }

    /// Headphone / line analog gain index 0–7 ([`GAIN_0_3959`] … [`GAIN_1_143`]).
    pub fn set_headphone_gain(&mut self, index: u8) -> Result<(), Cs43l22Error<I2C::Error>> {
        if index > 7 {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.upd(REG_PLAYBACK_CTL_1, 0xE0, index << 5)
    }

    /// Headphone analog gain multiplier (0.3959 … 1.143).
    pub fn headphone_gain(&mut self) -> Result<f32, Cs43l22Error<I2C::Error>> {
        Ok(HP_GAINS[(self.inner.r(REG_PLAYBACK_CTL_1)? >> 5) as usize])
    }

    /// Mute (`Some(true)`) / unmute (`Some(false)`) / keep (`None`) the outputs (both channels).
    pub fn mute_outputs(&mut self, headphone: Option<bool>, speaker: Option<bool>) -> Result<(), Cs43l22Error<I2C::Error>> {
        if let Some(h) = headphone {
            self.inner.upd(REG_PLAYBACK_CTL_2, 0xC0, if h { 0xC0 } else { 0 })?;
        }
        if let Some(s) = speaker {
            self.inner.upd(REG_PLAYBACK_CTL_2, 0x30, if s { 0x30 } else { 0 })?;
        }
        Ok(())
    }

    /// Speaker mono / channel swap / exact-50 % mute.
    pub fn set_speaker_mode(&mut self, mono: bool, swap: bool, mute_50_50: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.upd(
            REG_PLAYBACK_CTL_2,
            0x07,
            (if mono { 0x02 } else { 0 }) | (if swap { 0x04 } else { 0 }) | (if mute_50_50 { 0x01 } else { 0 }),
        )
    }

    /// Write `CLOCKING_CTL`.
    pub fn set_clocking(&mut self, c: &Clocking) -> Result<(), Cs43l22Error<I2C::Error>> {
        if c.speed > 3 || c.ratio > 3 {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.w(
            REG_CLOCKING_CTL,
            (if c.auto { 0x80 } else { 0 }) | (c.speed << 5) | (if c.group_32k { 0x10 } else { 0 })
                | (if c.video_clk { 0x08 } else { 0 }) | (c.ratio << 1) | (if c.mclk_div2 { 1 } else { 0 }),
        )
    }

    /// Serial audio port configuration (`INTERFACE_CTL_1` / `INTERFACE_CTL_2`).
    pub fn set_interface(&mut self, i: &Interface) -> Result<(), Cs43l22Error<I2C::Error>> {
        if i.fmt > 2 || i.word_length > 3 || (i.dsp && i.fmt != FMT_LJ) {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.w(
            REG_INTERFACE_CTL_1,
            (if i.master { 0x80 } else { 0 }) | (if i.invert_sclk { 0x40 } else { 0 }) | (if i.dsp { 0x10 } else { 0 })
                | (i.fmt << 2) | i.word_length,
        )?;
        self.inner.w(REG_INTERFACE_CTL_2, (if i.sclk_eq_mclk { 0x40 } else { 0 }) | (if i.invert_switch { 0x08 } else { 0 }))
    }

    /// Treble / bass shelves (−10.5 … +12 dB in 1.5 dB steps); corners 0–3.
    pub fn set_tone(
        &mut self, treble_db: f32, bass_db: f32, treble_corner: u8, bass_corner: u8, enabled: bool,
    ) -> Result<(), Cs43l22Error<I2C::Error>> {
        if treble_corner > 3 || bass_corner > 3 {
            return Err(Cs43l22Error::InvalidArgument);
        }
        let treb = (8 - round_half_up(treble_db / 1.5)).clamp(0, 15) as u8;
        let bass = (8 - round_half_up(bass_db / 1.5)).clamp(0, 15) as u8;
        self.inner.w(REG_TONE_CTL, (treb << 4) | bass)?;
        self.inner.upd(REG_BEEP_TONE_CFG, 0x1F, (treble_corner << 3) | (bass_corner << 1) | (if enabled { 1 } else { 0 }))
    }

    /// Program the beep generator (timing first, mode last). `mode` = [`BEEP_OFF`] only stops it.
    ///
    /// # Errors
    /// [`Cs43l22Error::BeepActive`] if a beep is running and `mode` is not [`BEEP_OFF`].
    pub fn set_beep(
        &mut self, frequency: u8, on_time: u8, off_time: u8, volume_db: f32, mode: u8, mix: bool,
    ) -> Result<(), Cs43l22Error<I2C::Error>> {
        if frequency > 15 || on_time > 15 || off_time > 7 || mode > 3 {
            return Err(Cs43l22Error::InvalidArgument);
        }
        if mode == BEEP_OFF {
            return self.stop_beep();
        }
        if self.inner.r(REG_BEEP_TONE_CFG)? & 0xC0 != 0 {
            return Err(Cs43l22Error::BeepActive);
        }
        let n = round_half_up((6.0 - volume_db) / 2.0).clamp(0, 31);
        self.inner.w(REG_BEEP_FREQ_ON, (frequency << 4) | on_time)?;
        self.inner.w(REG_BEEP_VOL_OFF, (off_time << 5) | (((6 - n) & 0x1F) as u8))?;
        self.inner.upd(REG_BEEP_TONE_CFG, 0xE0, (mode << 6) | (if mix { 0 } else { 0x20 }))
    }

    /// Stop the beep generator (`BEEP[1:0]` = 0).
    pub fn stop_beep(&mut self) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.upd(REG_BEEP_TONE_CFG, 0xC0, 0)
    }

    /// Configure the peak limiter.
    pub fn set_limiter(&mut self, l: &Limiter) -> Result<(), Cs43l22Error<I2C::Error>> {
        if l.release > 63 || l.attack > 63 {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.w(
            REG_LIMIT_CTL_1,
            (nearest_limit(l.max_db) << 5) | (nearest_limit(l.cushion_db) << 2)
                | (if l.soft_ramp_disable { 0x02 } else { 0 }) | (if l.zero_cross_disable { 1 } else { 0 }),
        )?;
        self.inner.w(REG_LIMIT_CTL_2, (if l.enabled { 0x80 } else { 0 }) | (if l.limit_all { 0x40 } else { 0 }) | l.release)?;
        self.inner.w(REG_LIMIT_ATTACK, l.attack)
    }

    /// Soft-ramp volume transitions (`DIGSFT`, `ANLGSFTA`, `ANLGSFTB`).
    pub fn set_soft_ramp(&mut self, digital: bool, analog_left: bool, analog_right: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.set_bit(REG_MISC_CTL, 1, digital)?;
        self.inner.upd(REG_ANALOG_ZC_SR, 0x0A, (if analog_left { 0x02 } else { 0 }) | (if analog_right { 0x08 } else { 0 }))
    }

    /// Zero-cross volume transitions (`DIGZC`, `ANLGZCA`, `ANLGZCB`).
    pub fn set_zero_cross(&mut self, digital: bool, analog_left: bool, analog_right: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.set_bit(REG_MISC_CTL, 0, digital)?;
        self.inner.upd(REG_ANALOG_ZC_SR, 0x05, (if analog_left { 0x01 } else { 0 }) | (if analog_right { 0x04 } else { 0 }))
    }

    /// Digital de-emphasis filter.
    pub fn set_deemphasis(&mut self, enabled: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.set_bit(REG_MISC_CTL, 2, enabled)
    }

    /// Source of each PCM channel ([`SRC_NORMAL`], [`SRC_MIX`], [`SRC_SWAPPED`]).
    pub fn set_pcm_swap(&mut self, left_source: u8, right_source: u8) -> Result<(), Cs43l22Error<I2C::Error>> {
        if left_source > 3 || right_source > 3 {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.w(REG_PCM_MIXER_SWAP, (left_source << 6) | (right_source << 4))
    }

    /// Invert the PCM polarity of each channel.
    pub fn invert_pcm(&mut self, left: bool, right: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.upd(REG_PLAYBACK_CTL_1, 0x0C, (if left { 0x04 } else { 0 }) | (if right { 0x08 } else { 0 }))
    }

    /// Route analog inputs AIN1 … AIN4 (1–4) to the headphone outputs.
    pub fn set_passthrough(&mut self, enabled: bool, left_input: u8, right_input: u8) -> Result<(), Cs43l22Error<I2C::Error>> {
        if !(1..=4).contains(&left_input) || !(1..=4).contains(&right_input) {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.w(REG_PASSTHRU_A_SEL, 0x80 | (1 << (left_input - 1)))?;
        self.inner.w(REG_PASSTHRU_B_SEL, 0x80 | (1 << (right_input - 1)))?;
        self.inner.upd(REG_MISC_CTL, 0xC0, if enabled { 0xC0 } else { 0 })
    }

    /// Passthrough volume −60 … +12 dB.
    pub fn set_passthrough_volume(&mut self, left_db: f32, right_db: Option<f32>) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.w(REG_PASSTHRU_A_VOL, half_db_code(left_db, -60.0, 12.0))?;
        self.inner.w(REG_PASSTHRU_B_VOL, half_db_code(right_db.unwrap_or(left_db), -60.0, 12.0))
    }

    /// Mute or unmute the passthrough channels.
    pub fn mute_passthrough(&mut self, left: bool, right: Option<bool>) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.set_bit(REG_MISC_CTL, 4, left)?;
        self.inner.set_bit(REG_MISC_CTL, 5, right.unwrap_or(left))
    }

    /// Hold (`true`) or apply (`false`) register changes (`FREEZE`).
    pub fn freeze(&mut self, on: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        self.inner.set_bit(REG_MISC_CTL, 3, on)
    }

    /// Battery compensation for the speaker supply VP (`vp_ref_index` 0–15).
    pub fn set_battery_compensation(&mut self, enabled: bool, vp_ref_index: u8, monitor: bool) -> Result<(), Cs43l22Error<I2C::Error>> {
        if vp_ref_index > 15 {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.w(REG_BATTERY_COMP, (if enabled { 0x80 } else { 0 }) | (if monitor { 0x40 } else { 0 }) | vp_ref_index)
    }

    /// VP supply voltage in volts: `VP_LEVEL × va_volts / 63.3`.
    pub fn read_vp_voltage(&mut self, va_volts: f32) -> Result<f32, Cs43l22Error<I2C::Error>> {
        Ok(self.inner.r(REG_VP_LEVEL)? as f32 * va_volts / 63.3)
    }

    /// Read the status register — **reading clears the flags**.
    pub fn read_status(&mut self) -> Result<Status, Cs43l22Error<I2C::Error>> {
        let v = self.inner.r(REG_STATUS)?;
        Ok(Status {
            clock_error: v & 0x40 != 0,
            dsp_a_overflow: v & 0x20 != 0,
            dsp_b_overflow: v & 0x10 != 0,
            pcm_a_overflow: v & 0x08 != 0,
            pcm_b_overflow: v & 0x04 != 0,
        })
    }

    /// Read the speaker status register.
    pub fn read_speaker_status(&mut self) -> Result<SpeakerStatus, Cs43l22Error<I2C::Error>> {
        let v = self.inner.r(REG_SPEAKER_STATUS)?;
        Ok(SpeakerStatus { speaker_a_short: v & 0x20 != 0, speaker_b_short: v & 0x10 != 0, spkr_hp_pin: v & 0x08 != 0 })
    }

    /// Charge-pump divider N (0–15); frequency = 64·Fs / (N + 2).
    pub fn set_charge_pump_frequency(&mut self, n: u8) -> Result<(), Cs43l22Error<I2C::Error>> {
        if n > 15 {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.upd(REG_CHARGE_PUMP_FREQ, 0xF0, n << 4)
    }

    /// Read any documented register (reading `STATUS` clears it).
    pub fn read_register(&mut self, reg: u8) -> Result<u8, Cs43l22Error<I2C::Error>> {
        if !documented(reg) {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.r(reg)
    }

    /// Write any documented writable register.
    pub fn write_register(&mut self, reg: u8, value: u8) -> Result<(), Cs43l22Error<I2C::Error>> {
        if !documented(reg) || matches!(reg, 0x01 | 0x2E | 0x30 | 0x31) {
            return Err(Cs43l22Error::InvalidArgument);
        }
        self.inner.w(reg, value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::digital::ErrorType as PinErrorType;
    use embedded_hal::i2c::{ErrorType, Operation};
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};
    use std::cell::RefCell;
    use std::rc::Rc;

    const ADDR: u8 = CS43L22_I2C_ADDRESS;

    /// Register-file fake: single-byte register writes and write-read reads.
    #[derive(Clone)]
    struct Regs(Rc<RefCell<[u8; 256]>>);

    impl Regs {
        fn new() -> Self {
            let regs = Regs(Rc::new(RefCell::new([0u8; 256])));
            regs.0.borrow_mut()[0x01] = 0xE1;
            regs.0.borrow_mut()[0x32] = 0x3B;
            regs
        }
        fn get(&self, r: u8) -> u8 {
            self.0.borrow()[r as usize]
        }
        fn set(&self, r: u8, v: u8) {
            self.0.borrow_mut()[r as usize] = v;
        }
    }

    impl ErrorType for Regs {
        type Error = core::convert::Infallible;
    }

    impl I2c for Regs {
        fn transaction(&mut self, _address: u8, ops: &mut [Operation<'_>]) -> Result<(), Self::Error> {
            let mut reg: Option<u8> = None;
            for op in ops.iter_mut() {
                match op {
                    Operation::Write(data) => {
                        reg = Some(data[0]);
                        if data.len() == 2 {
                            self.0.borrow_mut()[data[0] as usize] = data[1];
                        }
                    }
                    Operation::Read(buf) => {
                        buf[0] = self.0.borrow()[reg.unwrap() as usize];
                    }
                }
            }
            Ok(())
        }
    }

    struct Pin(Rc<RefCell<Vec<bool>>>);

    impl PinErrorType for Pin {
        type Error = core::convert::Infallible;
    }

    impl OutputPin for Pin {
        fn set_low(&mut self) -> Result<(), Self::Error> {
            self.0.borrow_mut().push(false);
            Ok(())
        }
        fn set_high(&mut self) -> Result<(), Self::Error> {
            self.0.borrow_mut().push(true);
            Ok(())
        }
    }

    fn chip() -> (CS43L22Full<Regs>, Regs) {
        let regs = Regs::new();
        let mut dac = CS43L22Full::new(regs.clone(), ADDR);
        dac.init().unwrap();
        (dac, regs)
    }

    #[test]
    fn init_exact_transactions() {
        let t = vec![
            T::write_read(ADDR, vec![0x01], vec![0xE1]),
            T::write(ADDR, vec![0x02, 0x01]),
            T::write(ADDR, vec![0x04, 0xAF]),
            T::write(ADDR, vec![0x05, 0xA0]),
            T::write(ADDR, vec![0x06, 0x04]),
            T::write(ADDR, vec![0x0D, 0x60]),
            T::write(ADDR, vec![0x20, 0x00]),
            T::write(ADDR, vec![0x21, 0x00]),
            T::write(ADDR, vec![0x00, 0x99]),
            T::write(ADDR, vec![0x47, 0x80]),
            T::write_read(ADDR, vec![0x32], vec![0x3B]),
            T::write(ADDR, vec![0x32, 0xBB]),
            T::write(ADDR, vec![0x32, 0x3B]),
            T::write(ADDR, vec![0x00, 0x00]),
            T::write(ADDR, vec![0x02, 0x9E]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut dac = CS43L22Minimal::new(i2c.clone(), ADDR);
        dac.init().unwrap();
        dac.start().unwrap();
        i2c.done();
    }

    #[test]
    fn init_bad_id_and_reset_pulse() {
        let regs = Regs::new();
        regs.set(0x01, 0x00);
        let mut dac = CS43L22Minimal::new(regs.clone(), ADDR);
        assert_eq!(dac.init(), Err(Cs43l22Error::NotFound));
        regs.set(0x01, 0xE1);
        let levels = Rc::new(RefCell::new(Vec::new()));
        let mut pin = Pin(levels.clone());
        dac.init_with_reset(&mut pin, &mut NoopDelay::new()).unwrap();
        assert_eq!(*levels.borrow(), vec![false, true]);
        assert_eq!(regs.get(0x02), 0x01);
    }

    #[test]
    fn stop_sequence_and_volume() {
        let (mut dac, r) = chip();
        dac.start().unwrap();
        assert_eq!(r.get(0x02), 0x9E);
        r.set(0x0E, 0x03);
        r.set(0x0A, 0x0F);
        dac.stop(&mut NoopDelay::new()).unwrap();
        assert_eq!(r.get(0x02), 0x9F);
        assert_eq!(r.get(0x0F) & 0xF0, 0xF0);
        assert_eq!(r.get(0x0E) & 3, 0);
        assert_eq!(r.get(0x0A) & 0x0F, 0);
        dac.set_volume(12.0).unwrap();
        assert_eq!(r.get(0x20), 0x18);
        dac.set_volume(-102.0).unwrap();
        assert_eq!(r.get(0x20), 0x34);
        dac.set_volume(-500.0).unwrap();
        assert_eq!(r.get(0x21), 0x34);
        dac.set_volume(-0.5).unwrap();
        assert_eq!(r.get(0x20), 0xFF);
        assert_eq!(dac.get_volume().unwrap(), -0.5);
        dac.set_volume_channels(-10.0, 3.0).unwrap();
        assert_eq!((r.get(0x20), r.get(0x21)), (0xEC, 0x06));
    }

    #[test]
    fn channel_volumes_and_mutes() {
        let (mut dac, r) = chip();
        dac.set_headphone_volume(-20.0, None).unwrap();
        assert_eq!((r.get(0x22), r.get(0x23)), (0xD8, 0xD8));
        dac.set_headphone_volume(-96.0, Some(-100.0)).unwrap();
        assert_eq!((r.get(0x22), r.get(0x23)), (0x40, 0x01));
        dac.set_speaker_volume(5.0, Some(-1.0)).unwrap();
        assert_eq!((r.get(0x24), r.get(0x25)), (0x00, 0xFE));
        dac.set_pcm_volume(-51.5, Some(12.0)).unwrap();
        assert_eq!((r.get(0x1A), r.get(0x1B)), (0x19, 0x18));
        dac.mute_pcm(true, Some(false)).unwrap();
        assert_eq!((r.get(0x1A), r.get(0x1B)), (0x99, 0x18));
        dac.set_pcm_volume(0.0, None).unwrap();
        assert_eq!(r.get(0x1A), 0x80);
        dac.set_passthrough_volume(-60.0, Some(12.0)).unwrap();
        assert_eq!((r.get(0x14), r.get(0x15)), (0x88, 0x18));
        r.set(0x0F, 0);
        dac.mute(true).unwrap();
        assert_eq!(r.get(0x0F), 0xC0);
        dac.mute(false).unwrap();
        assert_eq!(r.get(0x0F), 0x00);
        dac.mute_outputs(None, Some(true)).unwrap();
        assert_eq!(r.get(0x0F), 0x30);
        dac.mute_outputs(Some(true), Some(false)).unwrap();
        assert_eq!(r.get(0x0F), 0xC0);
        dac.set_speaker_mode(true, false, true).unwrap();
        assert_eq!(r.get(0x0F) & 7, 3);
    }

    #[test]
    fn outputs_gain_clocking_interface() {
        let (mut dac, r) = chip();
        dac.set_outputs(OUT_ON, OUT_OFF).unwrap();
        assert_eq!(r.get(0x04), 0xAF);
        dac.set_outputs(0, 1).unwrap();
        assert_eq!(r.get(0x04), 0x05);
        assert_eq!(dac.set_outputs(4, 0), Err(Cs43l22Error::InvalidArgument));
        dac.set_headphone_gain(GAIN_1_000).unwrap();
        assert_eq!(r.get(0x0D), 0xC0);
        assert_eq!(dac.headphone_gain().unwrap(), 1.0);
        assert_eq!(dac.set_headphone_gain(8), Err(Cs43l22Error::InvalidArgument));
        dac.set_clocking(&Clocking::default()).unwrap();
        assert_eq!(r.get(0x05), 0xA0);
        dac.set_clocking(&Clocking { auto: false, speed: 3, group_32k: true, video_clk: true, ratio: 3, mclk_div2: true }).unwrap();
        assert_eq!(r.get(0x05), 0x7F);
        assert!(dac.set_clocking(&Clocking { speed: 4, ..Clocking::default() }).is_err());
        dac.set_interface(&Interface::default()).unwrap();
        assert_eq!((r.get(0x06), r.get(0x07)), (0x04, 0x00));
        dac.set_interface(&Interface {
            master: true, fmt: FMT_RJ, word_length: 2, dsp: false, invert_sclk: true, sclk_eq_mclk: true, invert_switch: true,
        }).unwrap();
        assert_eq!((r.get(0x06), r.get(0x07)), (0xCA, 0x48));
        dac.set_interface(&Interface { fmt: FMT_LJ, word_length: 1, dsp: true, ..Interface::default() }).unwrap();
        assert_eq!(r.get(0x06), 0x11);
        assert!(dac.set_interface(&Interface { dsp: true, ..Interface::default() }).is_err());
    }

    #[test]
    fn clocking_table() {
        assert_eq!(clocking_for(12_288_000, 48000.0), Some(Clocking { auto: false, speed: 1, group_32k: false, video_clk: false, ratio: 0, mclk_div2: false }));
        let c = clocking_for(12_288_000, 8000.0).unwrap();
        assert_eq!((c.speed, c.group_32k), (3, true));
        assert_eq!(clocking_for(11_289_600, 44100.0).unwrap().ratio, 0);
        assert_eq!(clocking_for(12_000_000, 48000.0).unwrap().ratio, 1);
        let c = clocking_for(24_000_000, 44117.647).unwrap();
        assert_eq!((c.ratio, c.mclk_div2, c.speed), (3, true, 1));
        let c = clocking_for(27_000_000, 16000.0).unwrap();
        assert_eq!((c.speed, c.group_32k, c.video_clk, c.ratio), (2, true, true, 1));
        assert_eq!(clocking_for(16_934_400, 8018.18).unwrap().ratio, 2);
        assert_eq!(clocking_for(12_288_000, 44100.0), None);
    }

    #[test]
    fn tone_beep_limiter() {
        let (mut dac, r) = chip();
        dac.set_tone(12.0, -10.5, 1, 2, true).unwrap();
        assert_eq!(r.get(0x1F), 0x0F);
        assert_eq!(r.get(0x1E) & 0x1F, 0x0D);
        dac.set_tone(0.0, 0.0, 0, 0, false).unwrap();
        assert_eq!(r.get(0x1F), 0x88);
        assert_eq!(r.get(0x1E) & 1, 0);
        dac.set_beep(0xC, 2, 3, 6.0, BEEP_SINGLE, true).unwrap();
        assert_eq!((r.get(0x1C), r.get(0x1D), r.get(0x1E) >> 6), (0xC2, 0x66, 1));
        assert_eq!(dac.set_beep(1, 1, 1, 0.0, 1, true), Err(Cs43l22Error::BeepActive));
        dac.stop_beep().unwrap();
        assert_eq!(r.get(0x1E) >> 6, 0);
        dac.set_beep(1, 1, 0, -56.0, 3, false).unwrap();
        assert_eq!(r.get(0x1D) & 0x1F, 0x07);
        assert_eq!(r.get(0x1E) & 0xE0, 0xE0);
        dac.set_beep(1, 1, 0, 0.0, BEEP_OFF, true).unwrap();
        assert_eq!(r.get(0x1E) >> 6, 0);
        dac.set_beep(1, 1, 0, 0.0, 2, true).unwrap();
        assert_eq!(r.get(0x1D) & 0x1F, 0x03);
        dac.stop_beep().unwrap();
        assert_eq!(dac.set_beep(16, 0, 0, 0.0, 1, true), Err(Cs43l22Error::InvalidArgument));
        dac.set_limiter(&Limiter {
            enabled: true, max_db: -6, cushion_db: -12, release: 0x20, attack: 5, limit_all: false,
            soft_ramp_disable: true, zero_cross_disable: true,
        }).unwrap();
        assert_eq!((r.get(0x27), r.get(0x28), r.get(0x29)), (0x53, 0xA0, 5));
        dac.set_limiter(&Limiter { max_db: -29, ..Limiter::default() }).unwrap();
        assert_eq!((r.get(0x27) >> 5, r.get(0x28)), (7, 0x7F));
        assert!(dac.set_limiter(&Limiter { release: 64, ..Limiter::default() }).is_err());
    }

    #[test]
    fn dsp_passthrough_status_raw() {
        let (mut dac, r) = chip();
        r.set(0x0E, 0);
        r.set(0x0A, 0xA5);
        dac.set_soft_ramp(true, true, false).unwrap();
        assert_eq!((r.get(0x0E), r.get(0x0A)), (0x02, 0xA7));
        dac.set_zero_cross(true, false, true).unwrap();
        assert_eq!((r.get(0x0E), r.get(0x0A)), (0x03, 0xA6));
        dac.set_deemphasis(true).unwrap();
        assert_eq!(r.get(0x0E) & 4, 4);
        dac.freeze(true).unwrap();
        assert_eq!(r.get(0x0E) & 8, 8);
        dac.freeze(false).unwrap();
        assert_eq!(r.get(0x0E) & 8, 0);
        dac.set_pcm_swap(SRC_SWAPPED, SRC_MIX).unwrap();
        assert_eq!(r.get(0x26), 0xD0);
        dac.invert_pcm(true, false).unwrap();
        assert_eq!(r.get(0x0D) & 0x0C, 0x04);
        dac.set_passthrough(true, 2, 4).unwrap();
        assert_eq!((r.get(0x08), r.get(0x09), r.get(0x0E) & 0xC0), (0x82, 0x88, 0xC0));
        assert!(dac.set_passthrough(true, 0, 1).is_err());
        dac.mute_passthrough(true, Some(false)).unwrap();
        assert_eq!(r.get(0x0E) & 0x30, 0x10);
        dac.set_battery_compensation(true, 9, true).unwrap();
        assert_eq!(r.get(0x2F), 0xC9);
        r.set(0x30, 0x40);
        assert!((dac.read_vp_voltage(2.5).unwrap() - 64.0 * 2.5 / 63.3).abs() < 1e-4);
        r.set(0x2E, 0x4C);
        let s = dac.read_status().unwrap();
        assert!(s.clock_error && s.pcm_a_overflow && s.pcm_b_overflow && !s.dsp_a_overflow && !s.dsp_b_overflow);
        r.set(0x31, 0x28);
        let sp = dac.read_speaker_status().unwrap();
        assert!(sp.speaker_a_short && sp.spkr_hp_pin && !sp.speaker_b_short);
        r.set(0x34, 0x5F);
        dac.set_charge_pump_frequency(7).unwrap();
        assert_eq!(r.get(0x34), 0x7F);
        assert!(dac.set_charge_pump_frequency(16).is_err());
        assert_eq!(dac.read_id().unwrap(), (0x1C, 1));
        assert_eq!(dac.read_register(0x01).unwrap(), 0xE1);
        assert!(dac.read_register(0x03).is_err());
        dac.write_register(0x20, 0x10).unwrap();
        assert_eq!(r.get(0x20), 0x10);
        assert!(dac.write_register(0x2E, 0).is_err());
        assert!(dac.write_register(0x33, 0).is_err());
    }
}
