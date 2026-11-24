//! BH1750FVI — ambient light sensor (ROHM).
//!
//! Communicates over I²C at `0x23` (`ADDR` low) or `0x5C` (`ADDR` high). The chip is command
//! driven (no register pointer): single-byte opcodes select power state, reset, the measurement
//! mode and the measurement-time register (MTreg); the 16-bit result is read back with a plain
//! two-byte read. Illuminance in lux is `raw / 1.2` at the default MTreg of 69. Construction does
//! no bus access; `init()` powers the chip on.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const OP_POWER_DOWN: u8 = 0x00;
const OP_POWER_ON: u8 = 0x01;
const OP_RESET: u8 = 0x07;
const OP_MT_HIGH: u8 = 0x40;
const OP_MT_LOW: u8 = 0x60;
const DEFAULT_MTREG: u8 = 69;

/// Default I²C address (`ADDR` pin low).
pub const BH1750_I2C_ADDRESS: u8 = 0x23;
/// Alternate I²C address (`ADDR` pin high).
pub const BH1750_I2C_ADDRESS_ADDR_HIGH: u8 = 0x5C;

/// Measurement mode.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    /// H-resolution, 1 lx steps, 180 ms maximum.
    H,
    /// H-resolution mode 2, 0.5 lx steps, 180 ms maximum.
    H2,
    /// L-resolution, 4 lx steps, 24 ms maximum.
    L,
}

impl Mode {
    fn one_time(self) -> u8 {
        match self {
            Mode::H => 0x20,
            Mode::H2 => 0x21,
            Mode::L => 0x23,
        }
    }

    fn continuous(self) -> u8 {
        match self {
            Mode::H => 0x10,
            Mode::H2 => 0x11,
            Mode::L => 0x13,
        }
    }

    /// Maximum measurement time in µs at MTreg = 69.
    fn max_us(self) -> u32 {
        match self {
            Mode::H | Mode::H2 => 180_000,
            Mode::L => 24_000,
        }
    }
}

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// Measurement-time register outside 31–254.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

fn wait_us(mode: Mode, mtreg: u8) -> u32 {
    mode.max_us() * mtreg as u32 / DEFAULT_MTREG as u32
}

fn to_lux(raw: u16, mode: Mode, mtreg: u8) -> f32 {
    let lux = raw as f32 / 1.2 * (DEFAULT_MTREG as f32 / mtreg as f32);
    if mode == Mode::H2 { lux / 2.0 } else { lux }
}

/// BH1750FVI minimal driver — one one-time H-resolution measurement per `read()`.
///
/// Default configuration: H-resolution (opcode `0x20`, 1 lx), MTreg 69, 180 ms wait; the chip
/// powers down by itself after each measurement.
pub struct BH1750Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    mtreg: u8,
    mode: Mode,
}

impl<I2C: I2c> BH1750Minimal<I2C> {
    /// Create a new `BH1750Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x23` or `0x5C`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, mtreg: DEFAULT_MTREG, mode: Mode::H }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Power the chip on (opcode `0x01`).
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.command(OP_POWER_ON)
    }

    fn command(&mut self, code: u8) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[code])?;
        Ok(())
    }

    fn read_word(&mut self) -> Result<u16, Error<I2C::Error>> {
        let mut buf = [0u8; 2];
        self.i2c.read(self.addr, &mut buf)?;
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    fn measure_raw<D: DelayNs>(&mut self, mode: Mode, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.command(mode.one_time())?;
        delay.delay_us(wait_us(mode, self.mtreg));
        self.read_word()
    }

    /// Measure the illuminance once.
    ///
    /// Returns lux (one-time measurement in the current mode, H-resolution by default).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let mode = self.mode;
        let raw = self.measure_raw(mode, delay)?;
        Ok(to_lux(raw, mode, self.mtreg))
    }
}

/// BH1750FVI full driver — all measurement modes, continuous mode, sensitivity, power control.
///
/// Owns a [`BH1750Minimal`] and delegates `init` and `read`. `read()` uses the mode of the last
/// `measure_once()` or `start_continuous()` call (H-resolution by default).
pub struct BH1750Full<I2C> {
    inner: BH1750Minimal<I2C>,
}

impl<I2C: I2c> BH1750Full<I2C> {
    /// Create a new `BH1750Full`; same arguments as [`BH1750Minimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: BH1750Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Power the chip on (see [`BH1750Minimal::init`]).
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.init()
    }

    /// Measure the illuminance once (see [`BH1750Minimal::read`]).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Run one one-time measurement in the current mode and return the raw 16-bit count.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        let mode = self.inner.mode;
        self.inner.measure_raw(mode, delay)
    }

    /// Run one one-time measurement in `mode`; returns lux.
    pub fn measure_once<D: DelayNs>(&mut self, mode: Mode, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        self.inner.mode = mode;
        let raw = self.inner.measure_raw(mode, delay)?;
        Ok(to_lux(raw, mode, self.inner.mtreg))
    }

    /// Start continuous measurements; the first result is ready after [`measurement_time`](Self::measurement_time).
    pub fn start_continuous(&mut self, mode: Mode) -> Result<(), Error<I2C::Error>> {
        self.inner.mode = mode;
        self.inner.command(mode.continuous())
    }

    /// Read the latest continuous-mode result without waiting; lux.
    pub fn read_continuous(&mut self) -> Result<f32, Error<I2C::Error>> {
        let raw = self.inner.read_word()?;
        Ok(to_lux(raw, self.inner.mode, self.inner.mtreg))
    }

    /// Enter power-down (opcode `0x00`).
    pub fn power_down(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.command(OP_POWER_DOWN)
    }

    /// Leave power-down and wait for a measurement command (opcode `0x01`).
    pub fn power_on(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.command(OP_POWER_ON)
    }

    /// Clear the illuminance data register (opcode `0x07`; powers on first, as reset is ignored in power-down).
    pub fn reset(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.command(OP_POWER_ON)?;
        self.inner.command(OP_RESET)
    }

    /// Write the measurement-time register (two opcodes, high bits then low bits).
    ///
    /// [`Error::InvalidArgument`] if `mtreg` is outside 31–254 (nothing is written).
    pub fn set_measurement_time(&mut self, mtreg: u8) -> Result<(), Error<I2C::Error>> {
        if !(31..=254).contains(&mtreg) {
            return Err(Error::InvalidArgument);
        }
        self.inner.command(OP_MT_HIGH | (mtreg >> 5))?;
        self.inner.command(OP_MT_LOW | (mtreg & 0x1F))?;
        self.inner.mtreg = mtreg;
        Ok(())
    }

    /// The last written MTreg (the chip has no read-back; 69 after init).
    pub fn get_measurement_time_register(&self) -> u8 {
        self.inner.mtreg
    }

    /// Set the sensitivity relative to the default (MTreg = round(69 × `factor`), factor 0.45–3.68).
    ///
    /// [`Error::InvalidArgument`] if the resulting MTreg is outside 31–254.
    pub fn set_sensitivity(&mut self, factor: f32) -> Result<(), Error<I2C::Error>> {
        // round half up without `f32::round` (not available in no_std)
        let mt = (DEFAULT_MTREG as f32 * factor + 0.5) as i32;
        if !(31..=254).contains(&mt) {
            return Err(Error::InvalidArgument);
        }
        self.set_measurement_time(mt as u8)
    }

    /// Maximum measurement time of `mode` at the current MTreg, in seconds.
    pub fn measurement_time(&self, mode: Mode) -> f32 {
        wait_us(mode, self.inner.mtreg) as f32 / 1_000_000.0
    }

    /// Illuminance represented by one raw count at the current MTreg, in lux.
    pub fn lux_per_count(&self, mode: Mode) -> f32 {
        to_lux(1, mode, self.inner.mtreg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x23;

    fn cmd(code: u8) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![code])
    }

    fn word(raw: u16) -> I2cTransaction {
        I2cTransaction::read(ADDR, raw.to_be_bytes().to_vec())
    }

    fn close(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() <= tol
    }

    #[test]
    fn init_powers_on() {
        let mut s = BH1750Minimal::new(I2cMock::new(&[cmd(0x01)]), ADDR);
        s.init().unwrap();
        s.i2c.done();
    }

    #[test]
    fn constructor_does_no_bus_access() {
        let s = BH1750Full::new(I2cMock::new(&[]), ADDR);
        s.release().done();
    }

    #[test]
    fn read_converts_datasheet_example() {
        let t = [cmd(0x20), word(0x8342)];
        let mut s = BH1750Minimal::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read(&mut NoopDelay).unwrap(), 0x8342 as f32 / 1.2, 0.01));
        s.i2c.done();
    }

    #[test]
    fn measure_once_modes() {
        let t = [cmd(0x20), word(0x12), cmd(0x21), word(0x12), cmd(0x23), word(0x12)];
        let mut s = BH1750Full::new(I2cMock::new(&t), ADDR);
        assert!(close(s.measure_once(Mode::H, &mut NoopDelay).unwrap(), 15.0, 1e-3));
        assert!(close(s.measure_once(Mode::H2, &mut NoopDelay).unwrap(), 7.5, 1e-3));
        assert!(close(s.measure_once(Mode::L, &mut NoopDelay).unwrap(), 15.0, 1e-3));
        s.release().done();
    }

    #[test]
    fn read_follows_current_mode() {
        let t = [cmd(0x21), word(0x12), cmd(0x21), word(0x12), cmd(0x21), word(0x12)];
        let mut s = BH1750Full::new(I2cMock::new(&t), ADDR);
        s.measure_once(Mode::H2, &mut NoopDelay).unwrap();
        assert!(close(s.read(&mut NoopDelay).unwrap(), 7.5, 1e-3));
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), 0x12);
        s.release().done();
    }

    #[test]
    fn continuous_mode() {
        let t = [cmd(0x10), cmd(0x11), cmd(0x13), cmd(0x11), word(0x12)];
        let mut s = BH1750Full::new(I2cMock::new(&t), ADDR);
        s.start_continuous(Mode::H).unwrap();
        s.start_continuous(Mode::H2).unwrap();
        s.start_continuous(Mode::L).unwrap();
        s.start_continuous(Mode::H2).unwrap();
        assert!(close(s.read_continuous().unwrap(), 7.5, 1e-3));
        s.release().done();
    }

    #[test]
    fn power_and_reset() {
        let t = [cmd(0x00), cmd(0x01), cmd(0x01), cmd(0x07)];
        let mut s = BH1750Full::new(I2cMock::new(&t), ADDR);
        s.power_down().unwrap();
        s.power_on().unwrap();
        s.reset().unwrap();
        s.release().done();
    }

    #[test]
    fn measurement_time_register_opcodes() {
        let t = [cmd(0x42), cmd(0x65), cmd(0x47), cmd(0x7E), cmd(0x40), cmd(0x7F)];
        let mut s = BH1750Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.get_measurement_time_register(), 69);
        s.set_measurement_time(69).unwrap();
        s.set_measurement_time(254).unwrap();
        assert_eq!(s.get_measurement_time_register(), 254);
        s.set_measurement_time(31).unwrap();
        assert_eq!(s.get_measurement_time_register(), 31);
        s.release().done();
    }

    #[test]
    fn measurement_time_register_rejected_without_write() {
        let mut s = BH1750Full::new(I2cMock::new(&[]), ADDR);
        assert_eq!(s.set_measurement_time(30), Err(Error::InvalidArgument));
        assert_eq!(s.set_measurement_time(255), Err(Error::InvalidArgument));
        assert_eq!(s.get_measurement_time_register(), 69);
        s.release().done();
    }

    #[test]
    fn sensitivity_scales_everything() {
        let t = [cmd(0x44), cmd(0x6A), cmd(0x20), word(0x12)];
        let mut s = BH1750Full::new(I2cMock::new(&t), ADDR);
        assert!(close(s.lux_per_count(Mode::H), 1.0 / 1.2, 1e-5));
        assert!(close(s.lux_per_count(Mode::H2), 0.5 / 1.2, 1e-5));
        assert!(close(s.measurement_time(Mode::H), 0.180, 1e-5));
        assert!(close(s.measurement_time(Mode::L), 0.024, 1e-5));
        s.set_sensitivity(2.0).unwrap();
        assert_eq!(s.get_measurement_time_register(), 138);
        assert!(close(s.lux_per_count(Mode::H), 1.0 / 1.2 / 2.0, 1e-5));
        assert!(close(s.measurement_time(Mode::H), 0.360, 1e-5));
        assert!(close(s.measure_once(Mode::H, &mut NoopDelay).unwrap(), 7.5, 1e-3));
        s.release().done();
    }

    #[test]
    fn sensitivity_limits() {
        let t = [cmd(0x40), cmd(0x7F), cmd(0x47), cmd(0x7E)];
        let mut s = BH1750Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.set_sensitivity(0.4), Err(Error::InvalidArgument));
        assert_eq!(s.set_sensitivity(3.7), Err(Error::InvalidArgument));
        s.set_sensitivity(0.45).unwrap();
        assert_eq!(s.get_measurement_time_register(), 31);
        s.set_sensitivity(3.68).unwrap();
        assert_eq!(s.get_measurement_time_register(), 254);
        s.release().done();
    }

    #[test]
    fn bus_error_propagates() {
        let t = [cmd(0x20).with_error(embedded_hal::i2c::ErrorKind::Other)];
        let mut s = BH1750Minimal::new(I2cMock::new(&t), ADDR);
        assert!(matches!(s.read(&mut NoopDelay), Err(Error::Bus(_))));
        s.i2c.done();
    }
}
