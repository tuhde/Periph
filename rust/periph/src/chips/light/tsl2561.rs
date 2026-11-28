//! TSL2561 — light-to-digital converter with two photodiode channels (TAOS / ams).
//!
//! Channel 0 responds to visible and infrared light, channel 1 mainly to
//! infrared; the two 16-bit integrating ADC counts are combined with the
//! datasheet's empirical formula into lux. Gain is 1× or 16×, the integration
//! time 13.7 ms, 101 ms or 402 ms (or host-timed manual integration), and a
//! channel-0 threshold window drives the open-drain INT pin. The I²C address is
//! 0x29 (ADDR SEL = GND), 0x39 (floating) or 0x49 (VDD).
//!
//! Every register access starts with a command byte (`0x80 | addr` for a byte,
//! `0xA0 | addr` for a little-endian word); both channels are read in one
//! 4-byte block read (`0x9C`) so the pair belongs to one integration.
//!
//! Interrupt delivery is left to the application (an INT pin edge or a timer):
//! [`Tsl2561Full::poll_interrupt`] checks channel 0 against the window and
//! [`Tsl2561Full::clear_interrupt`] releases a level interrupt.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

/// 7-bit I²C address with ADDR SEL floating (default of most breakout boards).
pub const TSL2561_I2C_ADDRESS: u8 = 0x39;
/// 7-bit I²C address with ADDR SEL = GND.
pub const TSL2561_I2C_ADDRESS_GND: u8 = 0x29;
/// 7-bit I²C address with ADDR SEL = VDD.
pub const TSL2561_I2C_ADDRESS_VDD: u8 = 0x49;

const CMD: u8 = 0x80;
const CMD_CLEAR: u8 = 0xC0;
const CMD_WORD: u8 = 0xA0;
const CMD_DATA_BLOCK: u8 = 0x9C;

const REG_CONTROL: u8 = 0x0;
const REG_TIMING: u8 = 0x1;
const REG_THRESH_LOW: u8 = 0x2;
const REG_THRESH_HIGH: u8 = 0x4;
const REG_INTERRUPT: u8 = 0x6;
const REG_ID: u8 = 0xA;

const POWER_ON: u8 = 0x03;
const INTEG_MANUAL: u8 = 0x03;

// Per INTEG code 0..2: nominal ms, scale, full-scale count, settle wait (one integration + ±6 % + margin).
const INTEG_MS: [u16; 3] = [14, 101, 402];
static INTEG_SCALE: [f32; 3] = [0.034, 0.252, 1.0];
// 16 / gain for gain 1 (index 0) and 16 (index 1): the formula assumes 16× gain.
static GAIN_NORMALISE: [f32; 2] = [16.0, 1.0];
const INTEG_FULL_SCALE: [u16; 3] = [5047, 37177, 65535];
const INTEG_SETTLE_MS: [u32; 3] = [20, 120, 450];

// (gain, INTEG) from most to least sensitive, used by auto_range().
const RANGES: [(u8, u8); 4] = [(16, 2), (1, 2), (1, 1), (1, 0)];
const AUTO_MIN_COUNTS: u16 = 100;

// Segments (ratio limit, a, b): first a*ch0n - b*ch0n*r^1.4, others a*ch0n - b*ch1n; r > 1.30 gives 0.
const COEFF_TFNCL: [(f32, f32, f32); 4] =
    [(0.50, 0.0304, 0.062), (0.61, 0.0224, 0.031), (0.80, 0.0128, 0.0153), (1.30, 0.00146, 0.00112)];
const COEFF_CS: [(f32, f32, f32); 4] =
    [(0.52, 0.0315, 0.0593), (0.65, 0.0229, 0.0291), (0.80, 0.0157, 0.0180), (1.30, 0.00338, 0.00260)];

/// Lux coefficient set (T, FN and CL share one set).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tsl2561Package {
    /// TMB package.
    T,
    /// Dual flat no-lead package.
    FN,
    /// ChipLED package.
    CL,
    /// Chipscale package (its own coefficients).
    CS,
}

/// Errors of the TSL2561 driver.
#[derive(Debug)]
pub enum Tsl2561Error<E> {
    /// Bus error.
    I2c(E),
    /// CONTROL did not read back as powered up during `init`.
    Init,
    /// A channel count is at or above the full-scale limit of the integration time.
    Saturated {
        /// Channel 0 count.
        ch0: u16,
        /// Channel 1 count.
        ch1: u16,
    },
    /// An argument was out of range.
    InvalidArgument,
}

impl<E> From<E> for Tsl2561Error<E> {
    fn from(e: E) -> Self {
        Tsl2561Error::I2c(e)
    }
}

/// Convert raw channel counts into lux with the datasheet's empirical formula.
///
/// `integration_ms` is 14, 101 or 402 (any other value is treated as 402 ms);
/// returns 0 for `ch0 == 0` or a ratio above 1.30.
pub fn calculate_lux(ch0: u16, ch1: u16, gain: u8, integration_ms: u16, package: Tsl2561Package) -> f32 {
    let code = match integration_ms {
        14 => 0,
        101 => 1,
        _ => 2,
    };
    // Table lookup from a static: the Xtensa LLVM backend cannot lower a select
    // between two float constants (it emits an unsupported constant-pool access).
    let factor = GAIN_NORMALISE[(gain == 16) as usize] / INTEG_SCALE[code];
    let ch0n = ch0 as f32 * factor;
    let ch1n = ch1 as f32 * factor;
    if ch0n <= 0.0 {
        return 0.0;
    }
    let r = ch1n / ch0n;
    let c = if package == Tsl2561Package::CS { &COEFF_CS } else { &COEFF_TFNCL };
    let lux = if r <= c[0].0 {
        c[0].1 * ch0n - c[0].2 * ch0n * libm::powf(r, 1.4)
    } else if r <= c[1].0 {
        c[1].1 * ch0n - c[1].2 * ch1n
    } else if r <= c[2].0 {
        c[2].1 * ch0n - c[2].2 * ch1n
    } else if r <= c[3].0 {
        c[3].1 * ch0n - c[3].2 * ch1n
    } else {
        0.0
    };
    if lux > 0.0 { lux } else { 0.0 }
}

/// TSL2561 two-channel ambient light sensor — minimal interface.
///
/// Powers the chip up with 1× gain and 402 ms integration and reads the
/// illuminance in lux with the T/FN/CL package coefficients.
pub struct Tsl2561Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    gain: u8,
    integ: u8,
    package: Tsl2561Package,
}

impl<I2C: I2c> Tsl2561Minimal<I2C> {
    /// Create the driver; no bus traffic until [`init`](Self::init).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, gain: 1, integ: 2, package: Tsl2561Package::T }
    }

    fn read_byte(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, (CMD | reg).into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    fn write_byte(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, (CMD | reg).into(), 1, &[value])
    }

    fn read_word(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, (CMD_WORD | reg).into(), 1, &mut buf)?;
        Ok(u16::from_le_bytes(buf))
    }

    fn write_word(&mut self, reg: u8, value: u16) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, (CMD_WORD | reg).into(), 1, &value.to_le_bytes())
    }

    fn write_timing(&mut self, manual: bool) -> Result<(), I2C::Error> {
        let value = if self.gain == 16 { 0x10 } else { 0x00 } | self.integ | if manual { 0x08 } else { 0x00 };
        self.write_byte(REG_TIMING, value)
    }

    fn read_channels(&mut self) -> Result<(u16, u16), I2C::Error> {
        let mut buf = [0u8; 4];
        register::read_register(&mut self.i2c, self.addr, CMD_DATA_BLOCK.into(), 1, &mut buf)?;
        Ok((u16::from_le_bytes([buf[0], buf[1]]), u16::from_le_bytes([buf[2], buf[3]])))
    }

    fn full_scale(&self) -> u16 {
        INTEG_FULL_SCALE.get(self.integ as usize).copied().unwrap_or(65535)
    }

    fn integration_ms(&self) -> u16 {
        INTEG_MS.get(self.integ as usize).copied().unwrap_or(0)
    }

    fn settle_ms(&self) -> u32 {
        INTEG_SETTLE_MS.get(self.integ as usize).copied().unwrap_or(0)
    }

    fn lux_from(&self, ch0: u16, ch1: u16) -> Result<f32, Tsl2561Error<I2C::Error>> {
        let limit = self.full_scale();
        if ch0 >= limit || ch1 >= limit {
            return Err(Tsl2561Error::Saturated { ch0, ch1 });
        }
        Ok(calculate_lux(ch0, ch1, self.gain, self.integration_ms(), self.package))
    }

    /// Run the initialization sequence: CONTROL ← 0x03, read it back, TIMING ←
    /// 0x02 (1× gain, 402 ms) and wait one integration (450 ms).
    ///
    /// Returns [`Tsl2561Error::Init`] if CONTROL does not read back as powered up.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Tsl2561Error<I2C::Error>> {
        self.gain = 1;
        self.integ = 2;
        self.write_byte(REG_CONTROL, POWER_ON)?;
        if self.read_byte(REG_CONTROL)? & 0x03 != POWER_ON {
            return Err(Tsl2561Error::Init);
        }
        self.write_timing(false)?;
        delay.delay_ms(self.settle_ms());
        Ok(())
    }

    /// Read the illuminance in lux (T/FN/CL coefficients unless changed); 0 for
    /// a channel ratio above 1.30.
    ///
    /// Returns [`Tsl2561Error::Saturated`] if a channel is at or above the full-scale limit.
    pub fn read_lux(&mut self) -> Result<f32, Tsl2561Error<I2C::Error>> {
        let (ch0, ch1) = self.read_channels()?;
        self.lux_from(ch0, ch1)
    }
}

/// TSL2561 full interface — extends Minimal with raw counts, gain and
/// integration time, package coefficients, auto-ranging, power control, manual
/// integration and the interrupt window.
pub struct Tsl2561Full<I2C> {
    inner: Tsl2561Minimal<I2C>,
}

impl<I2C: I2c> Tsl2561Full<I2C> {
    /// Create the driver; no bus traffic until [`init`](Self::init).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Tsl2561Minimal::new(i2c, addr) }
    }

    /// Run the initialization sequence (see [`Tsl2561Minimal::init`]).
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Tsl2561Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Read the illuminance in lux (see [`Tsl2561Minimal::read_lux`]).
    pub fn read_lux(&mut self) -> Result<f32, Tsl2561Error<I2C::Error>> {
        self.inner.read_lux()
    }

    /// Read both ADC channels in one block read: `(ch0, ch1)` — channel 0
    /// (visible + IR) and channel 1 (IR) counts.
    pub fn read_raw(&mut self) -> Result<(u16, u16), I2C::Error> {
        self.inner.read_channels()
    }

    /// Set the ADC gain (1 or 16); wait one integration before trusting the next reading.
    pub fn set_gain(&mut self, gain: u8) -> Result<(), Tsl2561Error<I2C::Error>> {
        if gain != 1 && gain != 16 {
            return Err(Tsl2561Error::InvalidArgument);
        }
        self.inner.gain = gain;
        Ok(self.inner.write_timing(false)?)
    }

    /// Current ADC gain (1 or 16).
    pub fn get_gain(&self) -> u8 {
        self.inner.gain
    }

    /// Set the integration time (14 = 13.7 ms, 101 or 402); wait one
    /// integration before trusting the next reading.
    pub fn set_integration_time(&mut self, ms: u16) -> Result<(), Tsl2561Error<I2C::Error>> {
        self.inner.integ = match ms {
            14 => 0,
            101 => 1,
            402 => 2,
            _ => return Err(Tsl2561Error::InvalidArgument),
        };
        Ok(self.inner.write_timing(false)?)
    }

    /// Current integration time in ms (14, 101 or 402; 0 in manual timing mode).
    pub fn get_integration_time(&self) -> u16 {
        self.inner.integration_ms()
    }

    /// Select the lux coefficient set.
    pub fn set_package(&mut self, package: Tsl2561Package) {
        self.inner.package = package;
    }

    /// Read lux and both channel counts from one sample: `(lux, ch0, ch1)`.
    pub fn read_lux_raw(&mut self) -> Result<(f32, u16, u16), Tsl2561Error<I2C::Error>> {
        let (ch0, ch1) = self.inner.read_channels()?;
        Ok((self.inner.lux_from(ch0, ch1)?, ch0, ch1))
    }

    fn apply_range<D: DelayNs>(&mut self, index: usize, delay: &mut D) -> Result<(), I2C::Error> {
        let old_wait = self.inner.settle_ms();
        (self.inner.gain, self.inner.integ) = RANGES[index];
        self.inner.write_timing(false)?;
        delay.delay_ms(old_wait + self.inner.settle_ms());
        Ok(())
    }

    /// Pick gain and integration time for the current light level and read lux.
    ///
    /// Steps through 16×/402 ms, 1×/402 ms, 1×/101 ms and 1×/13.7 ms until
    /// neither channel saturates and channel 0 is above 100 counts, waiting for
    /// a fresh integration after every change; the chosen setting stays active.
    /// Returns [`Tsl2561Error::Saturated`] if a channel still saturates at 1×/13.7 ms.
    pub fn auto_range<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Tsl2561Error<I2C::Error>> {
        let current = (self.inner.gain, self.inner.integ);
        let mut index = match RANGES.iter().position(|&r| r == current) {
            Some(i) => i,
            None => {
                self.apply_range(1, delay)?;
                1
            }
        };
        for _ in 0..2 * RANGES.len() {
            let (ch0, ch1) = self.inner.read_channels()?;
            let limit = self.inner.full_scale();
            let saturated = ch0 >= limit || ch1 >= limit;
            if saturated && index < RANGES.len() - 1 {
                index += 1;
            } else if !saturated && ch0 < AUTO_MIN_COUNTS && index > 0 {
                index -= 1;
            } else {
                return self.inner.lux_from(ch0, ch1);
            }
            self.apply_range(index, delay)?;
        }
        let (ch0, ch1) = self.inner.read_channels()?;
        self.inner.lux_from(ch0, ch1)
    }

    /// Power up (CONTROL ← 0x03); integration restarts.
    pub fn power_up(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_byte(REG_CONTROL, POWER_ON)
    }

    /// Power down (CONTROL ← 0x00); the registers keep their values.
    pub fn power_down(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_byte(REG_CONTROL, 0x00)
    }

    /// True if CONTROL reads 0x03.
    pub fn is_powered(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_byte(REG_CONTROL)? & 0x03 == POWER_ON)
    }

    /// Start a host-timed integration (INTEG = 11, MANUAL = 1); `read_lux` then
    /// uses the 402 ms scale.
    pub fn start_manual(&mut self) -> Result<(), I2C::Error> {
        self.inner.integ = INTEG_MANUAL;
        self.inner.write_timing(true)
    }

    /// Stop the host-timed integration (INTEG = 11, MANUAL = 0); read the counts next.
    pub fn stop_manual(&mut self) -> Result<(), I2C::Error> {
        self.inner.integ = INTEG_MANUAL;
        self.inner.write_timing(false)
    }

    /// Set the channel-0 interrupt window in counts (word writes of 0x2–0x3 and 0x4–0x5).
    pub fn set_thresholds(&mut self, low: u16, high: u16) -> Result<(), I2C::Error> {
        self.inner.write_word(REG_THRESH_LOW, low)?;
        self.inner.write_word(REG_THRESH_HIGH, high)
    }

    /// Read the channel-0 interrupt window: `(low, high)` in counts.
    pub fn get_thresholds(&mut self) -> Result<(u16, u16), I2C::Error> {
        Ok((self.inner.read_word(REG_THRESH_LOW)?, self.inner.read_word(REG_THRESH_HIGH)?))
    }

    /// Configure the INTERRUPT register: `mode` 0 disabled, 1 level, 2
    /// SMBAlert; `persist` 0 every cycle, 1 any value outside, N (2–15) N
    /// consecutive cycles outside.
    pub fn configure_interrupt(&mut self, mode: u8, persist: u8) -> Result<(), Tsl2561Error<I2C::Error>> {
        if mode > 2 || persist > 15 {
            return Err(Tsl2561Error::InvalidArgument);
        }
        Ok(self.inner.write_byte(REG_INTERRUPT, (mode << 4) | persist)?)
    }

    /// Read the ID register; `(id >> 4) == 1` for the TSL2561, bits 3:0 the revision.
    pub fn read_id(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_byte(REG_ID)
    }

    /// True if channel 0 is ≤ the low or > the high threshold (the persistence
    /// filter is not modelled and the chip's interrupt is not cleared).
    pub fn poll_interrupt(&mut self) -> Result<bool, I2C::Error> {
        let (ch0, _) = self.inner.read_channels()?;
        let (low, high) = self.get_thresholds()?;
        Ok(ch0 <= low || ch0 > high)
    }

    /// Send the command byte 0xC0 (CMD + CLEAR) — releases a level interrupt.
    pub fn clear_interrupt(&mut self) -> Result<(), I2C::Error> {
        let addr = self.inner.addr;
        self.inner.i2c.write(addr, &[CMD_CLEAR])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = TSL2561_I2C_ADDRESS;

    fn w(cmd: u8, data: &[u8]) -> T {
        let mut v = vec![cmd];
        v.extend_from_slice(data);
        T::write(ADDR, v)
    }

    fn r(cmd: u8, data: &[u8]) -> T {
        T::write_read(ADDR, vec![cmd], data.to_vec())
    }

    fn counts(ch0: u16, ch1: u16) -> T {
        let a = ch0.to_le_bytes();
        let b = ch1.to_le_bytes();
        r(0x9C, &[a[0], a[1], b[0], b[1]])
    }

    fn init_t() -> Vec<T> {
        vec![w(0x80, &[0x03]), r(0x80, &[0x03]), w(0x81, &[0x02])]
    }

    fn full(extra: Vec<T>) -> (I2cMock, Tsl2561Full<I2cMock>) {
        let mut t = init_t();
        t.extend(extra);
        let i2c = I2cMock::new(&t);
        let mut chip = Tsl2561Full::new(i2c.clone(), ADDR);
        chip.init(&mut NoopDelay::new()).unwrap();
        (i2c, chip)
    }

    fn reference(ch0: f32, ch1: f32, gain: f32, scale: f32, k: &[(f32, f32, f32); 4]) -> f32 {
        let c0 = ch0 * (16.0 / gain) / scale;
        let c1 = ch1 * (16.0 / gain) / scale;
        let r = c1 / c0;
        if r <= k[0].0 {
            return k[0].1 * c0 - k[0].2 * c0 * libm::powf(r, 1.4);
        }
        for &(lim, a, b) in &k[1..] {
            if r <= lim {
                return a * c0 - b * c1;
            }
        }
        0.0
    }

    fn near(a: f32, b: f32) -> bool {
        (a - b).abs() <= 1e-4 * b.abs().max(1.0)
    }

    #[test]
    fn init_sequence() {
        let (mut i2c, _chip) = full(vec![]);
        i2c.done();
    }

    #[test]
    fn init_rejects_bad_control() {
        let mut i2c = I2cMock::new(&[w(0x80, &[0x03]), r(0x80, &[0x00])]);
        let mut chip = Tsl2561Minimal::new(i2c.clone(), ADDR);
        assert!(matches!(chip.init(&mut NoopDelay::new()), Err(Tsl2561Error::Init)));
        i2c.done();
    }

    #[test]
    fn lux_formula_all_segments() {
        let (mut i2c, mut chip) = full(vec![
            counts(1000, 200), counts(1000, 550), counts(1000, 700), counts(1000, 1000), counts(1000, 1400), counts(0, 0),
        ]);
        let lx = chip.read_lux().unwrap();
        assert!(near(lx, reference(1000.0, 200.0, 1.0, 1.0, &COEFF_TFNCL)) && (lx - 382.2).abs() < 0.5);
        for c1 in [550.0, 700.0, 1000.0] {
            assert!(near(chip.read_lux().unwrap(), reference(1000.0, c1, 1.0, 1.0, &COEFF_TFNCL)));
        }
        assert_eq!(chip.read_lux().unwrap(), 0.0);
        assert_eq!(chip.read_lux().unwrap(), 0.0);
        assert!(near(calculate_lux(500, 100, 16, 101, Tsl2561Package::T), reference(500.0, 100.0, 16.0, 0.252, &COEFF_TFNCL)));
        assert!(near(calculate_lux(500, 300, 1, 14, Tsl2561Package::FN), reference(500.0, 300.0, 1.0, 0.034, &COEFF_TFNCL)));
        for c1 in [100u16, 600, 700, 1000] {
            assert!(near(calculate_lux(1000, c1, 1, 402, Tsl2561Package::CS), reference(1000.0, c1 as f32, 1.0, 1.0, &COEFF_CS)));
        }
        i2c.done();
    }

    #[test]
    fn raw_and_saturation() {
        let (mut i2c, mut chip) = full(vec![
            counts(0x1234, 0x0567), counts(0x1234, 0x0567), counts(65535, 100),
            w(0x81, &[0x01]), counts(37177, 10), counts(37176, 10),
        ]);
        assert_eq!(chip.read_raw().unwrap(), (0x1234, 0x0567));
        let (lx, c0, c1) = chip.read_lux_raw().unwrap();
        assert!(c0 == 0x1234 && c1 == 0x0567 && near(lx, reference(4660.0, 1383.0, 1.0, 1.0, &COEFF_TFNCL)));
        assert!(matches!(chip.read_lux(), Err(Tsl2561Error::Saturated { ch0: 65535, ch1: 100 })));
        chip.set_integration_time(101).unwrap();
        assert!(matches!(chip.read_lux(), Err(Tsl2561Error::Saturated { .. })));
        assert!(chip.read_lux().unwrap() > 0.0);
        i2c.done();
    }

    #[test]
    fn gain_integration_package_manual() {
        let (mut i2c, mut chip) = full(vec![
            w(0x81, &[0x12]), w(0x81, &[0x10]), w(0x81, &[0x00]), w(0x81, &[0x01]),
            w(0x81, &[0x02]), counts(1000, 100), w(0x81, &[0x12]), w(0x81, &[0x1B]), w(0x81, &[0x13]),
        ]);
        chip.set_gain(16).unwrap();
        assert_eq!(chip.get_gain(), 16);
        chip.set_integration_time(14).unwrap();
        assert_eq!(chip.get_integration_time(), 14);
        chip.set_gain(1).unwrap();
        chip.set_integration_time(101).unwrap();
        assert_eq!(chip.get_integration_time(), 101);
        assert!(matches!(chip.set_gain(4), Err(Tsl2561Error::InvalidArgument)));
        assert!(matches!(chip.set_integration_time(50), Err(Tsl2561Error::InvalidArgument)));
        chip.set_integration_time(402).unwrap();
        chip.set_package(Tsl2561Package::CS);
        assert!(near(chip.read_lux().unwrap(), reference(1000.0, 100.0, 1.0, 1.0, &COEFF_CS)));
        chip.set_gain(16).unwrap();
        chip.start_manual().unwrap();
        chip.stop_manual().unwrap();
        assert_eq!(chip.get_integration_time(), 0);
        i2c.done();
    }

    #[test]
    fn auto_range_dim_and_bright() {
        // Dim: 1x/402 reads 20 counts -> step up to 16x/402 (320 counts).
        let (mut i2c, mut chip) = full(vec![counts(20, 4), w(0x81, &[0x12]), counts(320, 64)]);
        let lx = chip.auto_range(&mut NoopDelay::new()).unwrap();
        assert!(chip.get_gain() == 16 && chip.get_integration_time() == 402 && lx > 0.0);
        i2c.done();
        // Bright: saturates at 402 and 101 ms, settles at 13.7 ms.
        let (mut i2c, mut chip) = full(vec![
            counts(65535, 29600), w(0x81, &[0x01]), counts(37177, 7459), w(0x81, &[0x00]), counts(5032, 1006),
        ]);
        let lx = chip.auto_range(&mut NoopDelay::new()).unwrap();
        assert!(chip.get_gain() == 1 && chip.get_integration_time() == 14);
        assert!(near(lx, calculate_lux(5032, 1006, 1, 14, Tsl2561Package::T)));
        i2c.done();
        // Mid: no change.
        let (mut i2c, mut chip) = full(vec![counts(5000, 1000)]);
        let lx = chip.auto_range(&mut NoopDelay::new()).unwrap();
        assert!(chip.get_gain() == 1 && chip.get_integration_time() == 402);
        assert!(near(lx, calculate_lux(5000, 1000, 1, 402, Tsl2561Package::T)));
        i2c.done();
    }

    #[test]
    fn power_thresholds_interrupt() {
        let (mut i2c, mut chip) = full(vec![
            r(0x80, &[0x03]), w(0x80, &[0x00]), r(0x80, &[0x00]), w(0x80, &[0x03]),
            w(0xA2, &[0x02, 0x01]), w(0xA4, &[0x0B, 0x0A]), r(0xA2, &[0x02, 0x01]), r(0xA4, &[0x0B, 0x0A]),
            w(0x86, &[0x12]), r(0x8A, &[0x15]),
            counts(500, 0), r(0xA2, &[100, 0]), r(0xA4, &[0xE8, 0x03]),
            counts(100, 0), r(0xA2, &[100, 0]), r(0xA4, &[0xE8, 0x03]),
            counts(1001, 0), r(0xA2, &[100, 0]), r(0xA4, &[0xE8, 0x03]),
            T::write(ADDR, vec![0xC0]),
        ]);
        assert!(chip.is_powered().unwrap());
        chip.power_down().unwrap();
        assert!(!chip.is_powered().unwrap());
        chip.power_up().unwrap();
        chip.set_thresholds(0x0102, 0x0A0B).unwrap();
        assert_eq!(chip.get_thresholds().unwrap(), (0x0102, 0x0A0B));
        chip.configure_interrupt(1, 2).unwrap();
        assert!(matches!(chip.configure_interrupt(3, 0), Err(Tsl2561Error::InvalidArgument)));
        assert!(matches!(chip.configure_interrupt(1, 16), Err(Tsl2561Error::InvalidArgument)));
        assert_eq!(chip.read_id().unwrap() >> 4, 1);
        assert!(!chip.poll_interrupt().unwrap());
        assert!(chip.poll_interrupt().unwrap());
        assert!(chip.poll_interrupt().unwrap());
        chip.clear_interrupt().unwrap();
        i2c.done();
    }
}
