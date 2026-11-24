//! MPL3115A2 — barometric pressure, altitude and temperature sensor (NXP).
//!
//! Communicates over I²C at the fixed address `0x60`. Pressure is a 20-bit Q18.2 value in pascals,
//! altitude a 20-bit Q16.4 value in metres, temperature a 12-bit Q8.4 value in °C. Construction does
//! no bus access; `init()` checks `WHO_AM_I` (`0xC4`), resets the chip, enables the data-ready flags
//! (`PT_DATA_CFG = 0x07`) and selects barometer mode with oversample ratio 128 in STANDBY.
//!
//! The Rust driver exposes [`MPL3115A2Full::poll_interrupt`] (no callback subscription): the caller
//! wires the INT pin itself and calls `poll_interrupt` when it fires.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const REG_DR_STATUS: u8 = 0x06;
const REG_OUT_P_MSB: u8 = 0x01;
const REG_OUT_P_DELTA: u8 = 0x07;
const REG_WHO_AM_I: u8 = 0x0C;
const REG_F_STATUS: u8 = 0x0D;
const REG_F_DATA: u8 = 0x0E;
const REG_F_SETUP: u8 = 0x0F;
const REG_SYSMOD: u8 = 0x11;
const REG_INT_SOURCE: u8 = 0x12;
const REG_PT_DATA_CFG: u8 = 0x13;
const REG_BAR_IN_MSB: u8 = 0x14;
const REG_P_TGT_MSB: u8 = 0x16;
const REG_T_TGT: u8 = 0x18;
const REG_P_WND_MSB: u8 = 0x19;
const REG_T_WND: u8 = 0x1B;
const REG_P_MIN: u8 = 0x1C;
const REG_T_MIN: u8 = 0x1F;
const REG_P_MAX: u8 = 0x21;
const REG_T_MAX: u8 = 0x24;
const REG_CTRL_REG1: u8 = 0x26;
const REG_CTRL_REG2: u8 = 0x27;
const REG_CTRL_REG3: u8 = 0x28;
const REG_CTRL_REG4: u8 = 0x29;
const REG_CTRL_REG5: u8 = 0x2A;
const REG_OFF_P: u8 = 0x2B;
const REG_OFF_T: u8 = 0x2C;
const REG_OFF_H: u8 = 0x2D;

const CHIP_ID: u8 = 0xC4;
const CTRL1_ALT: u8 = 0x80;
const CTRL1_RST: u8 = 0x04;
const CTRL1_OST: u8 = 0x02;
const CTRL1_SBYB: u8 = 0x01;
const DR_PTDR: u8 = 0x08;

/// Minimum time between samples in ms for OS = 0..7 (oversample ratio 1..128).
const OSR_TIME_MS: [u32; 8] = [6, 10, 18, 34, 66, 130, 258, 512];

/// Interrupt source: new pressure/altitude or temperature data.
pub const SOURCE_DRDY: u8 = 0x80;
/// Interrupt source: FIFO overflow or watermark.
pub const SOURCE_FIFO: u8 = 0x40;
/// Interrupt source: pressure/altitude window.
pub const SOURCE_PW: u8 = 0x20;
/// Interrupt source: temperature window.
pub const SOURCE_TW: u8 = 0x10;
/// Interrupt source: pressure/altitude threshold.
pub const SOURCE_PTH: u8 = 0x08;
/// Interrupt source: temperature threshold.
pub const SOURCE_TTH: u8 = 0x04;
/// Interrupt source: pressure/altitude change.
pub const SOURCE_PCHG: u8 = 0x02;
/// Interrupt source: temperature change.
pub const SOURCE_TCHG: u8 = 0x01;

/// FIFO disabled.
pub const FIFO_OFF: u8 = 0;
/// FIFO circular buffer (keeps the most recent samples).
pub const FIFO_CIRCULAR: u8 = 1;
/// FIFO stops when full.
pub const FIFO_STOP: u8 = 2;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// `WHO_AM_I` did not return `0xC4`.
    InvalidChip(u8),
    /// The chip did not finish a reset or conversion in time.
    Timeout,
    /// An argument or configuration is outside the supported range.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

fn pressure_from(b: &[u8]) -> f32 {
    (((b[0] as u32) << 12) | ((b[1] as u32) << 4) | ((b[2] >> 4) as u32)) as f32 / 4.0
}

fn altitude_from(b: &[u8]) -> f32 {
    (((b[0] as u16) << 8) | b[1] as u16) as i16 as f32 + (b[2] >> 4) as f32 / 16.0
}

fn temperature_from(b: &[u8]) -> f32 {
    (b[0] as i8) as f32 + (b[1] >> 4) as f32 / 16.0
}

/// Captured minimum and maximum values.
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct MinMax {
    /// Minimum pressure (Pa) or altitude (m).
    pub p_min: f32,
    /// Maximum pressure (Pa) or altitude (m).
    pub p_max: f32,
    /// Minimum temperature in °C.
    pub t_min: f32,
    /// Maximum temperature in °C.
    pub t_max: f32,
}

/// MPL3115A2 minimal driver — one-shot barometer readings.
///
/// Default configuration (written by `init`): barometer mode, oversample ratio 128 (about 512 ms
/// per sample), data-ready flags enabled, STANDBY between readings.
pub struct MPL3115A2Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    os: u8,
    alt: bool,
}

impl<I2C: I2c> MPL3115A2Minimal<I2C> {
    /// Create a new `MPL3115A2Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x60`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, os: 7, alt: false }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[reg, value])?;
        Ok(())
    }

    fn write_regs(&mut self, reg: u8, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        let mut buf = [0u8; 4];
        buf[0] = reg;
        buf[1..1 + data.len()].copy_from_slice(data);
        self.i2c.write(self.addr, &buf[..1 + data.len()])?;
        Ok(())
    }

    fn read_regs(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        self.i2c.write_read(self.addr, &[reg], buf)?;
        Ok(())
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        self.read_regs(reg, &mut b)?;
        Ok(b[0])
    }

    fn ctrl1(&self) -> u8 {
        (if self.alt { CTRL1_ALT } else { 0 }) | (self.os << 3)
    }

    fn timeout_ms(&self) -> u32 {
        OSR_TIME_MS[self.os as usize] * 2 + 50
    }

    fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let _ = self.write_reg(REG_CTRL_REG1, CTRL1_RST); // the chip may not ACK while rebooting
        for _ in 0..100 {
            delay.delay_ms(2);
            if let Ok(c1) = self.read_reg(REG_CTRL_REG1) {
                if c1 & CTRL1_RST == 0 {
                    return Ok(());
                }
            }
        }
        Err(Error::Timeout)
    }

    fn apply_config(&mut self) -> Result<(), Error<I2C::Error>> {
        self.write_reg(REG_PT_DATA_CFG, 0x07)?;
        let c1 = self.ctrl1();
        self.write_reg(REG_CTRL_REG1, c1)
    }

    fn wait_ptdr<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let mut waited = 0u32;
        let limit = self.timeout_ms();
        loop {
            delay.delay_ms(3);
            waited += 3;
            if self.read_reg(REG_DR_STATUS)? & DR_PTDR != 0 {
                return Ok(());
            }
            if waited > limit {
                return Err(Error::Timeout);
            }
        }
    }

    fn one_shot<D: DelayNs>(&mut self, delay: &mut D) -> Result<[u8; 5], Error<I2C::Error>> {
        let c1 = self.ctrl1();
        self.write_reg(REG_CTRL_REG1, c1)?; // OST must be 0 before it is set
        self.write_reg(REG_CTRL_REG1, c1 | CTRL1_OST)?;
        self.wait_ptdr(delay)?;
        let mut raw = [0u8; 5];
        self.read_regs(REG_OUT_P_MSB, &mut raw)?;
        Ok(raw)
    }

    /// Check `WHO_AM_I` (`0xC4`), soft reset and apply the default configuration.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let id = self.read_reg(REG_WHO_AM_I)?;
        if id != CHIP_ID {
            return Err(Error::InvalidChip(id));
        }
        self.soft_reset(delay)?;
        self.os = 7;
        self.alt = false;
        self.apply_config()
    }

    /// Run one conversion; returns `(pressure in Pa, temperature in °C)`.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let raw = self.one_shot(delay)?;
        Ok((pressure_from(&raw[0..3]), temperature_from(&raw[3..5])))
    }

    /// Run one conversion and return the pressure in Pa.
    pub fn read_pressure<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.read(delay)?.0)
    }

    /// Run one conversion and return the temperature in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.read(delay)?.1)
    }
}

/// MPL3115A2 full driver — altimeter mode, oversampling, continuous acquisition, offsets,
/// sea-level pressure, targets and windows, min/max capture, FIFO and interrupt registers.
///
/// Owns a [`MPL3115A2Minimal`] and delegates `init`.
pub struct MPL3115A2Full<I2C> {
    inner: MPL3115A2Minimal<I2C>,
    fifo_mode: u8,
}

impl<I2C: I2c> MPL3115A2Full<I2C> {
    /// Create a new `MPL3115A2Full`; same arguments as [`MPL3115A2Minimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: MPL3115A2Minimal::new(i2c, addr), fifo_mode: 0 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Check `WHO_AM_I`, reset and apply the default configuration (see [`MPL3115A2Minimal::init`]).
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.fifo_mode = 0;
        self.inner.init(delay)
    }

    fn standby(&mut self) -> Result<bool, Error<I2C::Error>> {
        let c1 = self.inner.read_reg(REG_CTRL_REG1)?;
        let was_active = c1 & CTRL1_SBYB != 0;
        if was_active {
            self.inner.write_reg(REG_CTRL_REG1, c1 & !CTRL1_SBYB)?;
        }
        Ok(was_active)
    }

    fn restore(&mut self, was_active: bool) -> Result<(), Error<I2C::Error>> {
        if was_active {
            let c1 = self.inner.ctrl1() | CTRL1_SBYB;
            self.inner.write_reg(REG_CTRL_REG1, c1)?;
        }
        Ok(())
    }

    /// Select barometer (`false`) or altimeter (`true`) mode; drops to STANDBY and restores.
    pub fn set_mode(&mut self, altimeter: bool) -> Result<(), Error<I2C::Error>> {
        let was = self.standby()?;
        self.inner.alt = altimeter;
        let c1 = self.inner.ctrl1();
        self.inner.write_reg(REG_CTRL_REG1, c1)?;
        self.restore(was)
    }

    /// Run one barometer conversion (switches back from altimeter mode if needed); `(Pa, °C)`.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        if self.inner.alt {
            self.set_mode(false)?;
        }
        self.inner.read(delay)
    }

    /// Run one barometer conversion and return the pressure in Pa.
    pub fn read_pressure<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.read(delay)?.0)
    }

    /// Run one conversion and return the temperature in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.read(delay)?.1)
    }

    /// Switch to altimeter mode if needed and run one conversion; altitude in metres relative to
    /// the sea-level pressure (`BAR_IN`).
    pub fn read_altitude<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        if !self.inner.alt {
            self.set_mode(true)?;
        }
        let raw = self.inner.one_shot(delay)?;
        Ok(altitude_from(&raw[0..3]))
    }

    /// Set the oversample ratio (1, 2, 4, 8, 16, 32, 64 or 128).
    pub fn set_oversample(&mut self, ratio: u8) -> Result<(), Error<I2C::Error>> {
        let os = match ratio {
            1 => 0,
            2 => 1,
            4 => 2,
            8 => 3,
            16 => 4,
            32 => 5,
            64 => 6,
            128 => 7,
            _ => return Err(Error::InvalidArgument),
        };
        let was = self.standby()?;
        self.inner.os = os;
        let c1 = self.inner.ctrl1();
        self.inner.write_reg(REG_CTRL_REG1, c1)?;
        self.restore(was)
    }

    /// Current oversample ratio (1..128).
    pub fn get_oversample(&self) -> u8 {
        1 << self.inner.os
    }

    /// Start periodic acquisition (ACTIVE mode) with a step of `2^step_exp` seconds (0–15).
    pub fn start_continuous(&mut self, step_exp: u8) -> Result<(), Error<I2C::Error>> {
        if step_exp > 15 {
            return Err(Error::InvalidArgument);
        }
        self.standby()?;
        let c2 = self.inner.read_reg(REG_CTRL_REG2)?;
        self.inner.write_reg(REG_CTRL_REG2, (c2 & 0xF0) | step_exp)?;
        let c1 = self.inner.ctrl1() | CTRL1_SBYB;
        self.inner.write_reg(REG_CTRL_REG1, c1)
    }

    /// Stop periodic acquisition (back to STANDBY).
    pub fn stop_continuous(&mut self) -> Result<(), Error<I2C::Error>> {
        let c1 = self.inner.ctrl1();
        self.inner.write_reg(REG_CTRL_REG1, c1)
    }

    /// `true` if new pressure/altitude or temperature data is ready (`DR_STATUS.PTDR`).
    pub fn data_ready(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.inner.read_reg(REG_DR_STATUS)? & DR_PTDR != 0)
    }

    /// Wait for PTDR and read the latest sample; `(value, temperature °C)` where the value is
    /// pascals (barometer) or metres (altimeter).
    pub fn read_continuous<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.wait_ptdr(delay)?;
        let mut raw = [0u8; 5];
        self.inner.read_regs(REG_OUT_P_MSB, &mut raw)?;
        let v = if self.inner.alt { altitude_from(&raw[0..3]) } else { pressure_from(&raw[0..3]) };
        Ok((v, temperature_from(&raw[3..5])))
    }

    /// Change since the previous sample; `(delta pressure Pa or altitude m, delta temperature °C)`.
    pub fn read_delta(&mut self) -> Result<(f32, f32), Error<I2C::Error>> {
        let mut raw = [0u8; 5];
        self.inner.read_regs(REG_OUT_P_DELTA, &mut raw)?;
        let dv = if self.inner.alt {
            altitude_from(&raw[0..3])
        } else {
            let p = pressure_from(&raw[0..3]);
            if p >= 262144.0 { p - 524288.0 } else { p }
        };
        Ok((dv, temperature_from(&raw[3..5])))
    }

    /// Write `BAR_IN`, the altitude reference (2 Pa resolution, default 101 326 Pa).
    pub fn set_sea_level_pressure(&mut self, pa: f32) -> Result<(), Error<I2C::Error>> {
        let raw = libm::roundf(pa / 2.0) as u16;
        self.inner.write_regs(REG_BAR_IN_MSB, &[(raw >> 8) as u8, raw as u8])
    }

    /// Write the offset trims: pressure (4 Pa/LSB), temperature (0.0625 °C/LSB), altitude (m).
    pub fn set_offsets(&mut self, pressure_pa: f32, temperature_c: f32, altitude_m: i8) -> Result<(), Error<I2C::Error>> {
        let op = libm::roundf(pressure_pa / 4.0);
        let ot = libm::roundf(temperature_c / 0.0625);
        if !(-128.0..=127.0).contains(&op) || !(-128.0..=127.0).contains(&ot) {
            return Err(Error::InvalidArgument);
        }
        self.inner.write_reg(REG_OFF_P, op as i8 as u8)?;
        self.inner.write_reg(REG_OFF_T, ot as i8 as u8)?;
        self.inner.write_reg(REG_OFF_H, altitude_m as u8)
    }

    /// Program the threshold/window targets (pascals or metres per mode; temperature in °C).
    pub fn set_target(&mut self, value: f32, window: f32, temperature_c: i8, temperature_window: u8) -> Result<(), Error<I2C::Error>> {
        let (tgt, wnd) = if self.inner.alt {
            (libm::roundf(value) as i16 as u16, libm::roundf(window) as u16)
        } else {
            (libm::roundf(value / 2.0) as u16, libm::roundf(window / 2.0) as u16)
        };
        let was = self.standby()?;
        self.inner.write_regs(REG_P_TGT_MSB, &[(tgt >> 8) as u8, tgt as u8])?;
        self.inner.write_reg(REG_T_TGT, temperature_c as u8)?;
        self.inner.write_regs(REG_P_WND_MSB, &[(wnd >> 8) as u8, wnd as u8])?;
        self.inner.write_reg(REG_T_WND, temperature_window)?;
        self.restore(was)
    }

    /// Use the current output as the threshold target (`ALARM_SEL` = 1, pulse `LOAD_OUTPUT`).
    pub fn load_current_as_target(&mut self) -> Result<(), Error<I2C::Error>> {
        let c2 = self.inner.read_reg(REG_CTRL_REG2)?;
        self.inner.write_reg(REG_CTRL_REG2, (c2 | 0x10) & !0x20)?;
        self.inner.write_reg(REG_CTRL_REG2, c2 | 0x30)
    }

    /// Read the captured minimum and maximum values.
    pub fn read_min_max(&mut self) -> Result<MinMax, Error<I2C::Error>> {
        let (mut pm, mut px, mut tm, mut tx) = ([0u8; 3], [0u8; 3], [0u8; 2], [0u8; 2]);
        self.inner.read_regs(REG_P_MIN, &mut pm)?;
        self.inner.read_regs(REG_P_MAX, &mut px)?;
        self.inner.read_regs(REG_T_MIN, &mut tm)?;
        self.inner.read_regs(REG_T_MAX, &mut tx)?;
        let conv = if self.inner.alt { altitude_from } else { pressure_from };
        Ok(MinMax { p_min: conv(&pm), p_max: conv(&px), t_min: temperature_from(&tm), t_max: temperature_from(&tx) })
    }

    /// Clear the min/max registers.
    pub fn clear_min_max(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.write_regs(REG_P_MIN, &[0, 0, 0])?;
        self.inner.write_regs(REG_T_MIN, &[0, 0])?;
        self.inner.write_regs(REG_P_MAX, &[0, 0, 0])?;
        self.inner.write_regs(REG_T_MAX, &[0, 0])
    }

    /// Configure the FIFO: `mode` 0 off, 1 circular, 2 stop when full; `watermark` 0–63.
    pub fn fifo_setup(&mut self, mode: u8, watermark: u8) -> Result<(), Error<I2C::Error>> {
        if mode > 2 || (self.fifo_mode != 0 && mode != 0 && mode != self.fifo_mode) {
            return Err(Error::InvalidArgument);
        }
        let was = self.standby()?;
        self.inner.write_reg(REG_F_SETUP, (mode << 6) | (watermark & 0x3F))?;
        self.fifo_mode = mode;
        self.restore(was)
    }

    /// Read `F_STATUS`: `(overflow, watermark, count)`.
    pub fn fifo_status(&mut self) -> Result<(bool, bool, u8), Error<I2C::Error>> {
        let s = self.inner.read_reg(REG_F_STATUS)?;
        Ok((s & 0x80 != 0, s & 0x40 != 0, s & 0x3F))
    }

    /// Drain up to `out.len()` FIFO samples into `out` as `(value, temperature °C)`; returns the count.
    pub fn fifo_read(&mut self, out: &mut [(f32, f32)]) -> Result<usize, Error<I2C::Error>> {
        let (_, _, count) = self.fifo_status()?;
        let n = (count as usize).min(out.len());
        for slot in out.iter_mut().take(n) {
            let mut raw = [0u8; 5];
            self.inner.read_regs(REG_F_DATA, &mut raw)?;
            let v = if self.inner.alt { altitude_from(&raw[0..3]) } else { pressure_from(&raw[0..3]) };
            *slot = (v, temperature_from(&raw[3..5]));
        }
        Ok(n)
    }

    /// Software reset, then restore the default configuration (as [`init`](Self::init)).
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.soft_reset(delay)?;
        self.inner.os = 7;
        self.inner.alt = false;
        self.fifo_mode = 0;
        self.inner.apply_config()
    }

    /// `WHO_AM_I` register (`0xC4`).
    pub fn who_am_i(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_reg(REG_WHO_AM_I)
    }

    /// `true` if `SYSMOD` reports ACTIVE mode.
    pub fn is_active(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.inner.read_reg(REG_SYSMOD)? & 0x01 != 0)
    }

    /// Write `CTRL_REG3`: INT polarity (`true` = active high) and open-drain per pin.
    pub fn set_interrupt_pins(&mut self, polarity1: bool, open_drain1: bool, polarity2: bool, open_drain2: bool) -> Result<(), Error<I2C::Error>> {
        let was = self.standby()?;
        let v = (if polarity1 { 0x20 } else { 0 }) | (if open_drain1 { 0x10 } else { 0 })
            | (if polarity2 { 0x02 } else { 0 }) | (if open_drain2 { 0x01 } else { 0 });
        self.inner.write_reg(REG_CTRL_REG3, v)?;
        self.restore(was)
    }

    /// Enable an interrupt source (`SOURCE_*` mask) and route it to INT1 (`pin` 1) or INT2 (`pin` 2).
    pub fn enable_interrupt(&mut self, source: u8, pin: u8) -> Result<(), Error<I2C::Error>> {
        if pin != 1 && pin != 2 {
            return Err(Error::InvalidArgument);
        }
        let was = self.standby()?;
        let c4 = self.inner.read_reg(REG_CTRL_REG4)?;
        let c5 = self.inner.read_reg(REG_CTRL_REG5)?;
        self.inner.write_reg(REG_CTRL_REG4, c4 | source)?;
        self.inner.write_reg(REG_CTRL_REG5, if pin == 1 { c5 | source } else { c5 & !source })?;
        self.restore(was)
    }

    /// Disable an interrupt source (clears it in `CTRL_REG4`).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        let was = self.standby()?;
        let c4 = self.inner.read_reg(REG_CTRL_REG4)?;
        self.inner.write_reg(REG_CTRL_REG4, c4 & !source)?;
        self.restore(was)
    }

    /// Read `INT_SOURCE` (bitmask of `SOURCE_*` flags).
    pub fn poll_interrupt(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_reg(REG_INT_SOURCE)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const A: u8 = 0x60;

    fn w(reg: u8, v: u8) -> T {
        T::write(A, vec![reg, v])
    }
    fn wr(reg: u8, data: Vec<u8>) -> T {
        T::write_read(A, vec![reg], data)
    }
    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-3
    }
    /// One-shot sequence at the given CTRL_REG1 value.
    fn shot(c1: u8, data: [u8; 5]) -> Vec<T> {
        vec![w(0x26, c1), w(0x26, c1 | 0x02), wr(0x06, vec![0x0E]), wr(0x01, data.to_vec())]
    }
    const P: [u8; 5] = [0x62, 0xF3, 0x80, 0x19, 0x80]; // 101326.0 Pa, 25.5 °C

    #[test]
    fn conversions() {
        assert!(close(pressure_from(&[0x62, 0xF3, 0x80]), 101326.0));
        assert!(close(altitude_from(&[0x00, 0x7B, 0x30]), 123.1875));
        assert!(close(altitude_from(&[0xFF, 0x9C, 0x00]), -100.0));
        assert!(close(temperature_from(&[0x19, 0x80]), 25.5));
        assert!(close(temperature_from(&[0xE7, 0x00]), -25.0));
    }

    #[test]
    fn init_resets_and_configures() {
        let t = [
            wr(0x0C, vec![0xC4]),
            w(0x26, 0x04),
            wr(0x26, vec![0x00]),
            w(0x13, 0x07),
            w(0x26, 0x38),
        ];
        let mut s = MPL3115A2Minimal::new(I2cMock::new(&t), A);
        s.init(&mut NoopDelay).unwrap();
        s.i2c.done();
    }

    #[test]
    fn init_rejects_wrong_chip() {
        let mut s = MPL3115A2Minimal::new(I2cMock::new(&[wr(0x0C, vec![0x00])]), A);
        assert_eq!(s.init(&mut NoopDelay), Err(Error::InvalidChip(0x00)));
        s.i2c.done();
    }

    #[test]
    fn read_returns_pressure_and_temperature() {
        let mut s = MPL3115A2Minimal::new(I2cMock::new(&shot(0x38, P)), A);
        let (p, t) = s.read(&mut NoopDelay).unwrap();
        assert!(close(p, 101326.0) && close(t, 25.5));
        s.i2c.done();
    }

    #[test]
    fn altitude_switches_mode_and_read_switches_back() {
        let mut t = vec![wr(0x26, vec![0x38]), w(0x26, 0xB8)];
        t.extend(shot(0xB8, [0x00, 0x7B, 0x30, 0x19, 0x80]));
        t.extend([wr(0x26, vec![0xB8]), w(0x26, 0x38)]);
        t.extend(shot(0x38, P));
        let mut s = MPL3115A2Full::new(I2cMock::new(&t), A);
        assert!(close(s.read_altitude(&mut NoopDelay).unwrap(), 123.1875));
        let (p, _) = s.read(&mut NoopDelay).unwrap();
        assert!(close(p, 101326.0));
        s.inner.i2c.done();
    }

    #[test]
    fn oversample_writes_ctrl1() {
        let t = [wr(0x26, vec![0x38]), w(0x26, 0x20)];
        let mut s = MPL3115A2Full::new(I2cMock::new(&t), A);
        s.set_oversample(16).unwrap();
        assert_eq!(s.get_oversample(), 16);
        assert_eq!(s.set_oversample(3), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn continuous_start_and_stop() {
        let t = [
            wr(0x26, vec![0x38]), wr(0x27, vec![0x00]), w(0x27, 0x02), w(0x26, 0x39),
            w(0x26, 0x38),
        ];
        let mut s = MPL3115A2Full::new(I2cMock::new(&t), A);
        s.start_continuous(2).unwrap();
        s.stop_continuous().unwrap();
        assert_eq!(s.start_continuous(16), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn read_continuous_waits_for_ptdr() {
        let t = [wr(0x06, vec![0x0E]), wr(0x01, P.to_vec())];
        let mut s = MPL3115A2Full::new(I2cMock::new(&t), A);
        let (v, temp) = s.read_continuous(&mut NoopDelay).unwrap();
        assert!(close(v, 101326.0) && close(temp, 25.5));
        s.inner.i2c.done();
    }

    #[test]
    fn delta_reads_change() {
        let mut s = MPL3115A2Full::new(I2cMock::new(&[wr(0x07, vec![0x00, 0x00, 0x40, 0x00, 0x10])]), A);
        let (dv, dt) = s.read_delta().unwrap();
        assert!(close(dv, 1.0) && close(dt, 0.0625));
        s.inner.i2c.done();
    }

    #[test]
    fn sea_level_offsets_and_targets() {
        let t = [
            T::write(A, vec![0x14, 0xC5, 0xE7]),
            w(0x2B, 0xFE), w(0x2C, 16), w(0x2D, 0xFD),
            wr(0x26, vec![0x38]),
            T::write(A, vec![0x16, 0xC3, 0x50]), w(0x18, 0xFB), T::write(A, vec![0x19, 0x00, 0x64]), w(0x1B, 3),
        ];
        let mut s = MPL3115A2Full::new(I2cMock::new(&t), A);
        s.set_sea_level_pressure(101326.0).unwrap();
        s.set_offsets(-8.0, 1.0, -3).unwrap();
        assert_eq!(s.set_offsets(1000.0, 0.0, 0), Err(Error::InvalidArgument));
        s.set_target(100000.0, 200.0, -5, 3).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn min_max_and_clear() {
        let t = [
            wr(0x1C, vec![0x62, 0xF3, 0x80]), wr(0x21, vec![0x62, 0xF3, 0x80]),
            wr(0x1F, vec![0x19, 0x80]), wr(0x24, vec![0x19, 0x80]),
            T::write(A, vec![0x1C, 0, 0, 0]), T::write(A, vec![0x1F, 0, 0]),
            T::write(A, vec![0x21, 0, 0, 0]), T::write(A, vec![0x24, 0, 0]),
        ];
        let mut s = MPL3115A2Full::new(I2cMock::new(&t), A);
        let mm = s.read_min_max().unwrap();
        assert!(close(mm.p_min, 101326.0) && close(mm.t_max, 25.5));
        s.clear_min_max().unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn fifo_setup_status_read() {
        let t = [
            wr(0x26, vec![0x38]), w(0x0F, 0x50),
            wr(0x0D, vec![0xC2]),
            wr(0x0D, vec![0x02]), wr(0x0E, P.to_vec()), wr(0x0E, P.to_vec()),
            wr(0x26, vec![0x38]), w(0x0F, 0x00),
        ];
        let mut s = MPL3115A2Full::new(I2cMock::new(&t), A);
        s.fifo_setup(FIFO_CIRCULAR, 16).unwrap();
        assert_eq!(s.fifo_setup(FIFO_STOP, 0), Err(Error::InvalidArgument));
        assert_eq!(s.fifo_status().unwrap(), (true, true, 2));
        let mut out = [(0.0f32, 0.0f32); 4];
        assert_eq!(s.fifo_read(&mut out).unwrap(), 2);
        assert!(close(out[0].0, 101326.0));
        s.fifo_setup(FIFO_OFF, 0).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn interrupt_routing() {
        let t = [
            wr(0x26, vec![0x38]), w(0x28, 0x30),
            wr(0x26, vec![0x38]), wr(0x29, vec![0x00]), wr(0x2A, vec![0x00]), w(0x29, 0x04), w(0x2A, 0x04),
            wr(0x26, vec![0x38]), wr(0x29, vec![0x04]), w(0x29, 0x00),
            wr(0x12, vec![0x84]),
        ];
        let mut s = MPL3115A2Full::new(I2cMock::new(&t), A);
        s.set_interrupt_pins(true, true, false, false).unwrap();
        s.enable_interrupt(SOURCE_TTH, 1).unwrap();
        assert_eq!(s.enable_interrupt(1, 3), Err(Error::InvalidArgument));
        s.disable_interrupt(SOURCE_TTH).unwrap();
        assert_eq!(s.poll_interrupt().unwrap(), 0x84);
        s.inner.i2c.done();
    }

    #[test]
    fn who_am_i_and_active() {
        let t = [wr(0x0C, vec![0xC4]), wr(0x11, vec![0x01])];
        let mut s = MPL3115A2Full::new(I2cMock::new(&t), A);
        assert_eq!(s.who_am_i().unwrap(), 0xC4);
        assert!(s.is_active().unwrap());
        s.inner.i2c.done();
    }
}
