//! Shared implementation for the ADS1113 / ADS1114 / ADS1115 16-bit ΔΣ ADCs.
//!
//! The three chips share one register map and one Config-register layout and
//! differ only in which Config fields the silicon honours (ADS1113: fixed
//! ±2.048 V, no comparator, no mux; ADS1114: PGA + comparator; ADS1115: PGA +
//! comparator + 4-input mux). [`Ads111x`] holds the whole-word Config
//! builder, the single-shot sequence and every feature; the per-chip modules
//! wrap it and publish exactly the subset their silicon supports.
//!
//! Registers are 16-bit big-endian behind a write-only Address Pointer. Every
//! Config write is a whole word — never read-modify-write.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

pub(crate) const REG_CONVERSION: u8 = 0x00;
pub(crate) const REG_CONFIG: u8 = 0x01;
pub(crate) const REG_LO_THRESH: u8 = 0x02;
pub(crate) const REG_HI_THRESH: u8 = 0x03;

pub(crate) const CONFIG_RESET: u16 = 0x8583;
const LO_RESET: u16 = 0x8000;
const HI_RESET: u16 = 0x7FFF;
const CFG_OS: u16 = 0x8000;
const CFG_MODE_SHOT: u16 = 0x0100;
const QUE_DISABLED: u8 = 3;

/// Interrupt source: a conversion finished (`ALERT/RDY` in conversion-ready mode).
pub const SOURCE_CONVERSION_READY: u8 = 0x01;
/// Interrupt source: result beyond the comparator thresholds.
pub const SOURCE_COMPARATOR: u8 = 0x02;

/// Full-scale range in volts, indexed by `PGA[2:0]` (codes 6 and 7 alias ±0.256 V).
const FSR: [f32; 6] = [6.144, 4.096, 2.048, 1.024, 0.512, 0.256];
/// Samples per second, indexed by `DR[2:0]`.
const RATES: [u16; 8] = [8, 16, 32, 64, 128, 250, 475, 860];

/// Errors from the ADS111x drivers' checked operations.
#[derive(Debug)]
pub enum Ads111xError<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// An argument outside the supported range (data rate, gain, mux, comparator
    /// setting) or thresholds with `high <= low`.
    InvalidArgument,
    /// `OS` never returned to 1 after a single-shot conversion.
    Timeout,
}

impl<E> From<E> for Ads111xError<E> {
    fn from(e: E) -> Self {
        Ads111xError::Bus(e)
    }
}

/// Shared ADS111x state and operations; wrapped by the per-chip drivers.
pub struct Ads111x<I2C> {
    i2c: I2C,
    addr: u8,
    mux: u8,
    pga: u8,
    dr: u8,
    comp_mode: u8,
    comp_pol: u8,
    comp_lat: u8,
    comp_que: u8,
    queue: u8,
    continuous: bool,
    lo_raw: i16,
    hi_raw: i16,
    source: u8,
}

impl<I2C: I2c> Ads111x<I2C> {
    /// Wrap `i2c` at `addr` (`0x48`–`0x4B`). Makes no bus traffic.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self {
            i2c, addr, mux: 0, pga: 2, dr: 4, comp_mode: 0, comp_pol: 0, comp_lat: 0,
            comp_que: QUE_DISABLED, queue: 0, continuous: false,
            lo_raw: i16::MIN, hi_raw: i16::MAX, source: 0,
        }
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn reset_state(&mut self) {
        self.mux = 0;
        self.pga = 2;
        self.dr = 4;
        self.comp_mode = 0;
        self.comp_pol = 0;
        self.comp_lat = 0;
        self.comp_que = QUE_DISABLED;
        self.queue = 0;
        self.continuous = false;
        self.lo_raw = i16::MIN;
        self.hi_raw = i16::MAX;
        self.source = 0;
    }

    pub(crate) fn read_reg(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    pub(crate) fn write_reg(&mut self, reg: u8, value: u16) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[(value >> 8) as u8, value as u8])
    }

    fn config_word(&self, os_bit: bool, mux: u8) -> u16 {
        (if os_bit { CFG_OS } else { 0 })
            | ((mux as u16) << 12)
            | ((self.pga as u16) << 9)
            | (if self.continuous { 0 } else { CFG_MODE_SHOT })
            | ((self.dr as u16) << 5)
            | ((self.comp_mode as u16) << 4)
            | ((self.comp_pol as u16) << 3)
            | ((self.comp_lat as u16) << 2)
            | self.comp_que as u16
    }

    fn write_config(&mut self, os_bit: bool) -> Result<(), I2C::Error> {
        let word = self.config_word(os_bit, self.mux);
        self.write_reg(REG_CONFIG, word)
    }

    /// Single-shot conversion on `mux`; returns the signed 16-bit code.
    pub fn convert<D: DelayNs>(&mut self, mux: u8, delay: &mut D) -> Result<i16, Ads111xError<I2C::Error>> {
        self.continuous = false;
        let word = self.config_word(true, mux);
        self.write_reg(REG_CONFIG, word)?;
        // Conversion time 1/DR, rounded up to whole milliseconds.
        delay.delay_ms((1000 + RATES[self.dr as usize] as u32 - 1) / RATES[self.dr as usize] as u32);
        for _ in 0..200 {
            if self.read_reg(REG_CONFIG)? & CFG_OS != 0 {
                return Ok(self.read_reg(REG_CONVERSION)? as i16);
            }
            delay.delay_ms(1);
        }
        Err(Ads111xError::Timeout)
    }

    /// Set the data rate (`DR[2:0]`, 0–7).
    pub fn set_data_rate(&mut self, dr: u8) -> Result<(), Ads111xError<I2C::Error>> {
        if dr > 7 {
            return Err(Ads111xError::InvalidArgument);
        }
        self.dr = dr;
        if self.continuous {
            self.write_config(false)?;
        }
        Ok(())
    }

    /// The configured `DR` code.
    pub fn get_data_rate(&self) -> u8 {
        self.dr
    }

    /// The configured data rate in samples per second.
    pub fn data_rate_sps(&self) -> u16 {
        RATES[self.dr as usize]
    }

    /// Set the programmable-gain-amplifier range (`PGA[2:0]`, 0–5).
    pub fn set_gain(&mut self, pga: u8) -> Result<(), Ads111xError<I2C::Error>> {
        if pga > 5 {
            return Err(Ads111xError::InvalidArgument);
        }
        self.pga = pga;
        if self.continuous {
            self.write_config(false)?;
        }
        Ok(())
    }

    /// The configured `PGA` code.
    pub fn get_gain(&self) -> u8 {
        self.pga
    }

    /// ±FSR of the current gain in volts.
    pub fn full_scale(&self) -> f32 {
        FSR[self.pga as usize]
    }

    /// Size of one code in volts (`full_scale() / 32768`).
    pub fn lsb(&self) -> f32 {
        FSR[self.pga as usize] / 32768.0
    }

    /// Start continuous conversion on `mux` (0–7).
    pub fn start_continuous(&mut self, mux: u8) -> Result<(), Ads111xError<I2C::Error>> {
        if mux > 7 {
            return Err(Ads111xError::InvalidArgument);
        }
        self.mux = mux;
        self.continuous = true;
        self.write_config(false)?;
        Ok(())
    }

    /// Read `CONVERSION` without touching Config.
    pub fn read_continuous_raw(&mut self) -> Result<i16, I2C::Error> {
        Ok(self.read_reg(REG_CONVERSION)? as i16)
    }

    /// Return to single-shot / power-down mode.
    pub fn stop_continuous(&mut self) -> Result<(), I2C::Error> {
        self.continuous = false;
        self.write_config(false)
    }

    /// `true` while `CONFIG.OS` reads 0 (single-shot mode only).
    pub fn is_converting(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.read_reg(REG_CONFIG)? & CFG_OS == 0)
    }

    /// The raw 16-bit `CONFIG` register.
    pub fn read_config(&mut self) -> Result<u16, I2C::Error> {
        self.read_reg(REG_CONFIG)
    }

    /// Write the reset values back and clear the cached settings.
    pub fn reset(&mut self, with_thresholds: bool) -> Result<(), I2C::Error> {
        self.write_reg(REG_CONFIG, CONFIG_RESET)?;
        if with_thresholds {
            self.write_reg(REG_LO_THRESH, LO_RESET)?;
            self.write_reg(REG_HI_THRESH, HI_RESET)?;
        }
        self.reset_state();
        Ok(())
    }

    /// Configure the comparator and route it to `ALERT/RDY`.
    pub fn configure_comparator(&mut self, mode: u8, polarity: u8, latch: bool, queue: u8) -> Result<(), Ads111xError<I2C::Error>> {
        if mode > 1 || polarity > 1 || queue > 2 || self.hi_raw <= self.lo_raw {
            return Err(Ads111xError::InvalidArgument);
        }
        self.comp_mode = mode;
        self.comp_pol = polarity;
        self.comp_lat = latch as u8;
        self.queue = queue;
        self.comp_que = queue;
        self.source = SOURCE_COMPARATOR;
        self.write_config(false)?;
        Ok(())
    }

    /// Set `COMP_QUE` to 11; `ALERT/RDY` becomes high-impedance.
    pub fn disable_comparator(&mut self) -> Result<(), I2C::Error> {
        self.comp_que = QUE_DISABLED;
        self.source = 0;
        self.write_config(false)
    }

    /// Write `LO_THRESH` (signed code).
    pub fn set_low_threshold_raw(&mut self, raw: i16) -> Result<(), I2C::Error> {
        self.lo_raw = raw;
        self.write_reg(REG_LO_THRESH, raw as u16)
    }

    /// Write `HI_THRESH` (signed code).
    pub fn set_high_threshold_raw(&mut self, raw: i16) -> Result<(), I2C::Error> {
        self.hi_raw = raw;
        self.write_reg(REG_HI_THRESH, raw as u16)
    }

    fn volts_to_code(&self, volts: f32) -> i16 {
        let steps = volts / self.lsb();
        let code = if steps >= 0.0 { (steps + 0.5) as i32 } else { -((-steps + 0.5) as i32) };
        code.clamp(-32768, 32767) as i16
    }

    /// Write both thresholds from volts at the current gain.
    pub fn set_thresholds(&mut self, low: f32, high: f32) -> Result<(), Ads111xError<I2C::Error>> {
        if high <= low {
            return Err(Ads111xError::InvalidArgument);
        }
        let (lo, hi) = (self.volts_to_code(low), self.volts_to_code(high));
        self.set_low_threshold_raw(lo)?;
        self.set_high_threshold_raw(hi)?;
        Ok(())
    }

    /// `(low, high)` thresholds read back, sign-extended.
    pub fn get_thresholds_raw(&mut self) -> Result<(i16, i16), I2C::Error> {
        Ok((self.read_reg(REG_LO_THRESH)? as i16, self.read_reg(REG_HI_THRESH)? as i16))
    }

    /// Read `CONVERSION` to release a latched comparator output.
    pub fn clear_alert(&mut self) -> Result<(), I2C::Error> {
        self.read_reg(REG_CONVERSION)?;
        Ok(())
    }

    /// Route one source to `ALERT/RDY` (the other is disabled).
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Ads111xError<I2C::Error>> {
        match source {
            SOURCE_CONVERSION_READY => {
                self.write_reg(REG_HI_THRESH, 0x8000)?;
                self.write_reg(REG_LO_THRESH, 0x0000)?;
                self.comp_que = 0;
                self.source = SOURCE_CONVERSION_READY;
                self.write_config(false)?;
                Ok(())
            }
            SOURCE_COMPARATOR => {
                let (lo, hi) = (self.lo_raw, self.hi_raw);
                self.set_low_threshold_raw(lo)?;
                self.set_high_threshold_raw(hi)?;
                self.configure_comparator(self.comp_mode, self.comp_pol, self.comp_lat == 1, self.queue)
            }
            _ => Err(Ads111xError::InvalidArgument),
        }
    }

    /// The enabled source constant if `ALERT/RDY` is asserted, else 0.
    ///
    /// Rust has no interrupt-pin connection: pass the sampled `ALERT/RDY`
    /// level as `alert_pin_high`, or `None` to fall back to `CONFIG.OS`
    /// (`SOURCE_CONVERSION_READY`, single-shot only; the comparator source
    /// then reads 0). A latched comparator alert is cleared by reading
    /// `CONVERSION`.
    pub fn poll_interrupt(&mut self, alert_pin_high: Option<bool>) -> Result<u8, I2C::Error> {
        if self.source == 0 {
            return Ok(0);
        }
        let asserted = match alert_pin_high {
            Some(high) => high == (self.comp_pol == 1),
            None if self.source == SOURCE_CONVERSION_READY => self.read_reg(REG_CONFIG)? & CFG_OS != 0,
            None => false,
        };
        if !asserted {
            return Ok(0);
        }
        if self.source == SOURCE_COMPARATOR && self.comp_lat == 1 {
            self.clear_alert()?;
        }
        Ok(self.source)
    }
}
