//! MAX44009 — ultra-low-power ambient light sensor with ADC (Maxim / Analog Devices).
//!
//! An on-chip photodiode with automatic ranging reports the illuminance as a
//! 12-bit floating-point value (4-bit exponent, 8-bit mantissa):
//! `lux = 2^E · M · 0.045`, from 0.045 lx to 188 006.4 lx. The chip has a
//! programmable interrupt (upper / lower threshold window plus a delay timer,
//! open-drain INT pin), an automatic mode (current divider and 100–800 ms
//! integration time chosen by the chip) and a manual mode (6.25–800 ms
//! integration, current divider 1 or 1/8). The 7-bit I²C address is `0x4A`
//! (A0 = GND) or `0x4B` (A0 = VCC).
//!
//! The driver uses register access (1-byte address), one register per
//! transfer. A register read ends with a STOP, so the lux registers are read as
//! high, low, high and repeated once if the high byte changed in between.
//!
//! A delay provider is needed only for [`MAX44009Minimal::init`] (the 900 ms
//! wait for the first conversion).

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

/// 7-bit I²C address with A0 = GND.
pub const MAX44009_I2C_ADDRESS: u8 = 0x4A;
/// 7-bit I²C address with A0 = VCC.
pub const MAX44009_I2C_ADDRESS_A0_HIGH: u8 = 0x4B;
/// Status bit 0: light level outside the threshold window for longer than the delay.
pub const MAX44009_SOURCE_THRESHOLD: u8 = 0x01;

const REG_INT_STATUS: u8 = 0x00;
const REG_INT_ENABLE: u8 = 0x01;
const REG_CONFIG: u8 = 0x02;
const REG_LUX_HIGH: u8 = 0x03;
const REG_LUX_LOW: u8 = 0x04;
const REG_UPPER: u8 = 0x05;
const REG_LOWER: u8 = 0x06;
const REG_TIMER: u8 = 0x07;

const CFG_CONT: u8 = 0x80;
const CFG_MANUAL: u8 = 0x40;
const CFG_CDR: u8 = 0x08;
const CFG_TIM_MASK: u8 = 0x07;

const LUX_PER_LSB: f32 = 0.045;
const MAX_LUX: f32 = 188_006.4;
const OVERRANGE_EXP: u8 = 15;
const INTEGRATION_MS: [f32; 8] = [800.0, 400.0, 200.0, 100.0, 50.0, 25.0, 12.5, 6.25];

fn pow2(exponent: u8) -> f32 {
    (1u32 << exponent) as f32
}

fn lux_from(exponent: u8, mantissa: u8) -> f32 {
    if exponent >= OVERRANGE_EXP {
        return MAX_LUX;
    }
    pow2(exponent) * mantissa as f32 * LUX_PER_LSB
}

fn round_f32(x: f32) -> i32 {
    if x >= 0.0 { (x + 0.5) as i32 } else { (x - 0.5) as i32 }
}

fn encode_threshold(lux: f32) -> u8 {
    if lux < 11.5 {
        let m = round_f32(lux / (16.0 * LUX_PER_LSB));
        return m.clamp(0, 15) as u8;
    }
    let mut exponent = 1u8;
    let mut mantissa = 0.0f32;
    while exponent <= 14 {
        mantissa = lux / (pow2(exponent) * LUX_PER_LSB);
        if mantissa < 256.0 {
            break;
        }
        exponent += 1;
    }
    if exponent > 14 {
        exponent = 14;
    }
    let m = (mantissa as i32).clamp(128, 255) as u8;
    let code = (exponent << 4) | (m >> 4);
    code.min(0xEF)
}

fn decode_threshold(code: u8, upper: bool) -> f32 {
    let exponent = code >> 4;
    let nibble = code & 0x0F;
    let low_bits = if upper { 0x0F } else { 0x00 };
    pow2(exponent) * (((nibble << 4) | low_bits) as f32) * LUX_PER_LSB
}

/// Decoded CONFIG register.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MAX44009Config {
    /// Continuous mode (a new measurement as soon as one finishes).
    pub continuous: bool,
    /// Manual mode (the host chose current divider and integration time).
    pub manual: bool,
    /// Current division ratio 1/8.
    pub divide_by_8: bool,
    /// Integration time in ms.
    pub integration_ms: f32,
}

/// Errors returned by threshold setters.
#[derive(Debug)]
pub enum Max44009Error<E> {
    /// I²C bus error.
    I2c(E),
    /// `low_lux` is greater than `high_lux`.
    InvalidThresholds,
}

impl<E> From<E> for Max44009Error<E> {
    fn from(e: E) -> Self {
        Max44009Error::I2c(e)
    }
}

/// MAX44009 — minimal interface: read the ambient illuminance in lux.
pub struct MAX44009Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    last_exponent: u8,
}

impl<I2C: I2c> MAX44009Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once
    /// after power-up). `addr` is `0x4A` or `0x4B` ([`MAX44009_I2C_ADDRESS`],
    /// [`MAX44009_I2C_ADDRESS_A0_HIGH`]).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, last_exponent: 0 }
    }

    pub(crate) fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    pub(crate) fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value])
    }

    /// Run the initialization sequence: write the reset state (interrupt off,
    /// CONFIG `0x03`, thresholds fully open, timer `0xFF`), read INT_STATUS once
    /// to clear a stale interrupt and wait 900 ms so the first [`Self::lux`]
    /// returns a real measurement.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        self.write_reg(REG_INT_ENABLE, 0x00)?;
        self.write_reg(REG_CONFIG, 0x03)?;
        self.write_reg(REG_UPPER, 0xFF)?;
        self.write_reg(REG_LOWER, 0x00)?;
        self.write_reg(REG_TIMER, 0xFF)?;
        self.read_reg(REG_INT_STATUS)?;
        delay.delay_ms(900);
        Ok(())
    }

    /// Read LUX_HIGH, LUX_LOW and LUX_HIGH again; repeat once if the high byte changed.
    pub(crate) fn read_lux_regs(&mut self) -> Result<(u8, u8), I2C::Error> {
        let mut high = self.read_reg(REG_LUX_HIGH)?;
        let mut low = self.read_reg(REG_LUX_LOW)?;
        let high2 = self.read_reg(REG_LUX_HIGH)?;
        if high2 != high {
            high = high2;
            low = self.read_reg(REG_LUX_LOW)?;
        }
        let exponent = high >> 4;
        let mantissa = ((high & 0x0F) << 4) | (low & 0x0F);
        self.last_exponent = exponent;
        Ok((exponent, mantissa))
    }

    /// Read the ambient illuminance in lux (`2^E · M · 0.045`); the maximum
    /// 188006.4 lx on overrange (E = 15).
    pub fn lux(&mut self) -> Result<f32, I2C::Error> {
        let (exponent, mantissa) = self.read_lux_regs()?;
        Ok(lux_from(exponent, mantissa))
    }
}

/// MAX44009 — full interface: raw readings, measurement mode and range
/// configuration, interrupt thresholds and delay.
pub struct MAX44009Full<I2C> {
    inner: MAX44009Minimal<I2C>,
}

impl<I2C: I2c> MAX44009Full<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once
    /// after power-up). See [`MAX44009Minimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: MAX44009Minimal::new(i2c, addr) }
    }

    /// Run the initialization sequence. See [`MAX44009Minimal::init`].
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        self.inner.init(delay)
    }

    /// Read the ambient illuminance in lux; see [`MAX44009Minimal::lux`].
    pub fn lux(&mut self) -> Result<f32, I2C::Error> {
        self.inner.lux()
    }

    /// Read the lux registers as one consistent sample:
    /// `(exponent, mantissa, overrange)` — exponent 0–15, 8-bit mantissa,
    /// `overrange` true if the exponent field was 15.
    pub fn read_raw(&mut self) -> Result<(u8, u8, bool), I2C::Error> {
        let (exponent, mantissa) = self.inner.read_lux_regs()?;
        Ok((exponent, mantissa, exponent >= OVERRANGE_EXP))
    }

    /// Whether the last reading was overrange (E = 15).
    pub fn is_overrange(&self) -> bool {
        self.inner.last_exponent >= OVERRANGE_EXP
    }

    /// Lux per mantissa LSB for the last reading, `2^E · 0.045`.
    pub fn resolution(&self) -> f32 {
        pow2(self.inner.last_exponent.min(14)) * LUX_PER_LSB
    }

    fn update_config(&mut self, set_bits: u8, clear_bits: u8) -> Result<(), I2C::Error> {
        let cfg = self.inner.read_reg(REG_CONFIG)?;
        self.inner.write_reg(REG_CONFIG, (cfg & !clear_bits) | set_bits)
    }

    /// Select continuous (`true`: a new measurement as soon as one finishes) or
    /// low-power (`false`: one measurement every 800 ms) mode.
    pub fn set_continuous(&mut self, continuous: bool) -> Result<(), I2C::Error> {
        self.update_config(if continuous { CFG_CONT } else { 0 }, CFG_CONT)
    }

    /// Select automatic mode (the chip chooses current divider and integration time).
    pub fn set_automatic(&mut self) -> Result<(), I2C::Error> {
        self.update_config(0, CFG_MANUAL)
    }

    /// Select manual mode with the integration time nearest to `integration_ms`
    /// (800, 400, 200, 100, 50, 25, 12.5 or 6.25 ms) and an optional current
    /// divider of 8.
    pub fn set_manual(&mut self, integration_ms: f32, divide_by_8: bool) -> Result<(), I2C::Error> {
        let mut best = 0usize;
        let mut best_err = (INTEGRATION_MS[0] - integration_ms).abs();
        for (tim, &ms) in INTEGRATION_MS.iter().enumerate().skip(1) {
            let err = (ms - integration_ms).abs();
            if err < best_err {
                best = tim;
                best_err = err;
            }
        }
        let cfg = self.inner.read_reg(REG_CONFIG)?;
        let cfg = (cfg & CFG_CONT) | CFG_MANUAL | if divide_by_8 { CFG_CDR } else { 0 } | best as u8;
        self.inner.write_reg(REG_CONFIG, cfg)
    }

    /// Read the CONFIG register.
    pub fn get_config(&mut self) -> Result<MAX44009Config, I2C::Error> {
        let cfg = self.inner.read_reg(REG_CONFIG)?;
        Ok(MAX44009Config {
            continuous: cfg & CFG_CONT != 0,
            manual: cfg & CFG_MANUAL != 0,
            divide_by_8: cfg & CFG_CDR != 0,
            integration_ms: INTEGRATION_MS[(cfg & CFG_TIM_MASK) as usize],
        })
    }

    /// Set the interrupt threshold window; fails with
    /// [`Max44009Error::InvalidThresholds`] if `low_lux > high_lux`.
    pub fn set_thresholds(&mut self, low_lux: f32, high_lux: f32) -> Result<(), Max44009Error<I2C::Error>> {
        if low_lux > high_lux {
            return Err(Max44009Error::InvalidThresholds);
        }
        self.inner.write_reg(REG_LOWER, encode_threshold(low_lux))?;
        self.inner.write_reg(REG_UPPER, encode_threshold(high_lux))?;
        Ok(())
    }

    /// Read the interrupt threshold window `(low_lux, high_lux)`; the upper
    /// threshold carries the implied low nibble `1111`.
    pub fn get_thresholds(&mut self) -> Result<(f32, f32), I2C::Error> {
        let low = self.inner.read_reg(REG_LOWER)?;
        let high = self.inner.read_reg(REG_UPPER)?;
        Ok((decode_threshold(low, false), decode_threshold(high, true)))
    }

    /// Set how long the light must stay outside the window before the interrupt
    /// (0–25.5 s in 0.1 s steps).
    pub fn set_threshold_delay(&mut self, seconds: f32) -> Result<(), I2C::Error> {
        let v = round_f32(seconds / 0.1).clamp(0, 255) as u8;
        self.inner.write_reg(REG_TIMER, v)
    }

    /// Read the threshold delay in seconds.
    pub fn get_threshold_delay(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.read_reg(REG_TIMER)? as f32 * 0.1)
    }

    /// Enable the interrupt (INT_ENABLE = 1).
    pub fn enable_interrupt(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_INT_ENABLE, 0x01)
    }

    /// Disable the interrupt (INT_ENABLE = 0; also clears a pending status).
    pub fn disable_interrupt(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_INT_ENABLE, 0x00)
    }

    /// Read INT_STATUS (clearing it); bit 0 ([`MAX44009_SOURCE_THRESHOLD`]) is
    /// set if the light level was outside the window for longer than the delay.
    /// Wire your own GPIO to INT and call this from its falling-edge handler.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.inner.read_reg(REG_INT_STATUS)? & 0x01)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = MAX44009_I2C_ADDRESS;

    fn w(reg: u8, v: u8) -> T {
        T::write(ADDR, vec![reg, v])
    }

    fn r(reg: u8, v: u8) -> T {
        T::write_read(ADDR, vec![reg], vec![v])
    }

    fn init_t() -> Vec<T> {
        vec![w(1, 0x00), w(2, 0x03), w(5, 0xFF), w(6, 0x00), w(7, 0xFF), r(0, 0x00)]
    }

    fn full(extra: Vec<T>) -> (I2cMock, MAX44009Full<I2cMock>) {
        let mut t = init_t();
        t.extend(extra);
        let i2c = I2cMock::new(&t);
        let mut chip = MAX44009Full::new(i2c.clone(), ADDR);
        chip.init(&mut NoopDelay::new()).unwrap();
        (i2c, chip)
    }

    fn near(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-3
    }

    #[test]
    fn init_writes_reset_state_and_clears_status() {
        let (mut i2c, _chip) = full(vec![]);
        i2c.done();
    }

    #[test]
    fn lux_conversion_and_overrange() {
        let (mut i2c, mut chip) = full(vec![
            r(3, 0x00), r(4, 0x00), r(3, 0x00),    // zero
            r(3, 0x2A), r(4, 0x05), r(3, 0x2A),    // E = 2, M = 0xA5
            r(3, 0xEF), r(4, 0x0F), r(3, 0xEF),    // maximum valid code
            r(3, 0xF3), r(4, 0x00), r(3, 0xF3),    // overrange
            r(3, 0x01), r(4, 0xF7), r(3, 0x01),    // unused high nibble of LUX_LOW is ignored
        ]);
        assert_eq!(chip.lux().unwrap(), 0.0);
        assert!(near(chip.lux().unwrap(), 4.0 * 0xA5 as f32 * 0.045));
        assert!(near(chip.lux().unwrap(), 188_006.4));
        assert!(near(chip.lux().unwrap(), 188_006.4));
        assert!(chip.is_overrange());
        assert!(near(chip.lux().unwrap(), 0x17 as f32 * 0.045));
        i2c.done();
    }

    #[test]
    fn consistent_read_repeats_when_high_byte_changes() {
        let (mut i2c, mut chip) = full(vec![r(3, 0x10), r(4, 0x01), r(3, 0x20), r(4, 0x02)]);
        assert!(near(chip.lux().unwrap(), 4.0 * 2.0 * 0.045));
        i2c.done();
    }

    #[test]
    fn raw_and_resolution() {
        let (mut i2c, mut chip) = full(vec![r(3, 0x5B), r(4, 0x0C), r(3, 0x5B), r(3, 0xF0), r(4, 0x00), r(3, 0xF0)]);
        assert_eq!(chip.read_raw().unwrap(), (5, 0xBC, false));
        assert!(near(chip.resolution(), 32.0 * 0.045));
        assert!(!chip.is_overrange());
        assert_eq!(chip.read_raw().unwrap(), (15, 0x00, true));
        assert!(chip.is_overrange());
        i2c.done();
    }

    #[test]
    fn configuration() {
        let (mut i2c, mut chip) = full(vec![
            r(2, 0x03), w(2, 0x83),                      // set_continuous(true)
            r(2, 0x83), w(2, 0x03),                      // set_continuous(false)
            r(2, 0x03), w(2, 0x44),                      // set_manual(50, false)
            r(2, 0x44), w(2, 0x4E),                      // set_manual(12, true): nearest 12.5 ms, TIM = 6
            r(2, 0x4E), w(2, 0x0E),                      // set_automatic clears MANUAL only
            r(2, 0xC3), w(2, 0xC0 | 0x00),               // set_manual(1000, false) keeps CONT, TIM = 0
            r(2, 0xCD),                                  // get_config
        ]);
        chip.set_continuous(true).unwrap();
        chip.set_continuous(false).unwrap();
        chip.set_manual(50.0, false).unwrap();
        chip.set_manual(12.0, true).unwrap();
        chip.set_automatic().unwrap();
        chip.set_manual(1000.0, false).unwrap();
        let cfg = chip.get_config().unwrap();
        assert_eq!(cfg, MAX44009Config { continuous: true, manual: true, divide_by_8: true, integration_ms: 25.0 });
        i2c.done();
    }

    #[test]
    fn thresholds_and_delay() {
        let (mut i2c, mut chip) = full(vec![
            w(6, 0x00), w(5, 0xEF),                      // set_thresholds(0, 188006.4)
            w(6, 0x07), w(5, 0x48),                      // set_thresholds(5, 100): E = 0 / E = 4, M7 = 1
            r(6, 0x07), r(5, 0x48),                      // get_thresholds
            w(7, 20), r(7, 20),                          // delay 2.0 s
            w(7, 255), w(7, 0),                          // clamps
        ]);
        chip.set_thresholds(0.0, 188_006.4).unwrap();
        chip.set_thresholds(5.0, 100.0).unwrap();
        let (lo, hi) = chip.get_thresholds().unwrap();
        assert!((lo - 5.0).abs() < 0.5 && (hi - 100.0).abs() / 100.0 < 0.13);
        assert!(near(decode_threshold(0x25, true), 4.0 * 0x5F as f32 * 0.045));
        assert!(near(decode_threshold(0x25, false), 4.0 * 0x50 as f32 * 0.045));
        assert!(matches!(chip.set_thresholds(10.0, 5.0), Err(Max44009Error::InvalidThresholds)));
        chip.set_threshold_delay(2.0).unwrap();
        assert!(near(chip.get_threshold_delay().unwrap(), 2.0));
        chip.set_threshold_delay(99.0).unwrap();
        chip.set_threshold_delay(-1.0).unwrap();
        i2c.done();
    }

    #[test]
    fn interrupt_enable_and_status() {
        let (mut i2c, mut chip) = full(vec![w(1, 0x01), w(1, 0x00), r(0, 0x01), r(0, 0x00)]);
        chip.enable_interrupt().unwrap();
        chip.disable_interrupt().unwrap();
        assert_eq!(chip.poll_interrupt().unwrap(), MAX44009_SOURCE_THRESHOLD);
        assert_eq!(chip.poll_interrupt().unwrap(), 0);
        i2c.done();
    }
}
