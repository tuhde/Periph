//! HDC1008 — low-power digital humidity and temperature sensor (Texas Instruments).
//!
//! I²C addresses `0x40`–`0x43` (ADR0/ADR1). Registers are 16 bit, MSB first, addressed through a
//! persistent pointer. A measurement is triggered by writing pointer `0x00` (temperature, or
//! temperature then humidity in sequence mode) or `0x01` (humidity) and read in a *separate*
//! transaction: the read header is NACKed until the result is ready, so the driver polls it
//! (50 attempts 1 ms apart). Construction does no bus access; `init()` writes the configuration
//! `0x1000` (14-bit, sequence mode, heater off).
//!
//! ## Interrupts
//!
//! Rust exposes only [`HDC1008Full::poll_interrupt`] (no callback subscription): the caller wires
//! the DRDYn pin (or this poll) into its own ISR or loop.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const REG_TEMPERATURE: u8 = 0x00;
const REG_HUMIDITY: u8 = 0x01;
const REG_CONFIG: u8 = 0x02;
const REG_SERIAL1: u8 = 0xFB;

const CFG_RST: u16 = 0x8000;
const CFG_HEAT: u16 = 0x2000;
const CFG_MODE: u16 = 0x1000;
const CFG_BTST: u16 = 0x0800;
const CFG_TRES: u16 = 0x0400;
const CFG_HRES: u16 = 0x0300;
const CONFIG_DEFAULT: u16 = 0x1000;
const POLL_ATTEMPTS: u32 = 50;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// The result was not ready within the polling window (50 ms).
    Timeout,
    /// Unsupported resolution setting.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

fn to_temperature(raw: u16) -> f32 {
    raw as f32 / 65536.0 * 165.0 - 40.0
}

fn to_humidity(raw: u16) -> f32 {
    raw as f32 / 65536.0 * 100.0
}

/// HDC1008 minimal driver — one sequence measurement (temperature then humidity) per `read()`.
pub struct HDC1008Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    config: u16,
    pending: Option<([u8; 4], usize)>,
}

impl<I2C: I2c> HDC1008Minimal<I2C> {
    /// Create a new `HDC1008Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x40`–`0x43`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, config: CONFIG_DEFAULT, pending: None }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn write_config(&mut self) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[REG_CONFIG, (self.config >> 8) as u8, (self.config & 0xFF) as u8])?;
        Ok(())
    }

    /// Write the default configuration `0x1000` (14-bit T and RH, sequence mode, heater off).
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.config = CONFIG_DEFAULT;
        self.pending = None;
        self.write_config()
    }

    fn trigger_read<D: DelayNs>(&mut self, pointer: u8, buf: &mut [u8], delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.pending = None;
        self.i2c.write(self.addr, &[pointer])?;
        for _ in 0..POLL_ATTEMPTS {
            if self.i2c.read(self.addr, buf).is_ok() {
                return Ok(());
            }
            delay.delay_ms(1); // read header NACKed: not ready yet
        }
        Err(Error::Timeout)
    }

    fn set_mode(&mut self, sequence: bool) -> Result<(), Error<I2C::Error>> {
        let want = if sequence { self.config | CFG_MODE } else { self.config & !CFG_MODE };
        if want != self.config {
            self.config = want;
            self.write_config()?;
        }
        Ok(())
    }

    fn measure_pair<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        self.set_mode(true)?;
        let mut d = [0u8; 4];
        self.trigger_read(REG_TEMPERATURE, &mut d, delay)?;
        Ok((u16::from_be_bytes([d[0], d[1]]), u16::from_be_bytes([d[2], d[3]])))
    }

    /// Measure temperature and humidity (sequence mode, temperature first).
    ///
    /// Returns `(temperature_c, humidity_pct)`; [`Error::Timeout`] if the chip does not answer
    /// within 50 ms.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let (t, h) = self.measure_pair(delay)?;
        Ok((to_temperature(t), to_humidity(h)))
    }
}

/// HDC1008 full driver — single values, resolution, heater, battery flag, serial ID and DRDYn poll.
///
/// Owns a [`HDC1008Minimal`] and delegates `init` and `read`.
pub struct HDC1008Full<I2C> {
    inner: HDC1008Minimal<I2C>,
}

impl<I2C: I2c> HDC1008Full<I2C> {
    /// Create a new `HDC1008Full`; same arguments as [`HDC1008Minimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: HDC1008Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Write the default configuration; see [`HDC1008Minimal::init`].
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.init()
    }

    /// Measure temperature and humidity; see [`HDC1008Minimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Measure and return the raw `(temperature, humidity)` words (bits 1:0 zero).
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        self.inner.measure_pair(delay)
    }

    /// Run one temperature-only measurement (single mode) in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        self.inner.set_mode(false)?;
        let mut d = [0u8; 2];
        self.inner.trigger_read(REG_TEMPERATURE, &mut d, delay)?;
        Ok(to_temperature(u16::from_be_bytes(d)))
    }

    /// Run one humidity-only measurement (single mode) in %RH.
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        self.inner.set_mode(false)?;
        let mut d = [0u8; 2];
        self.inner.trigger_read(REG_HUMIDITY, &mut d, delay)?;
        Ok(to_humidity(u16::from_be_bytes(d)))
    }

    /// Select the resolutions: temperature 14 or 11 bit, humidity 14, 11 or 8 bit.
    ///
    /// [`Error::InvalidArgument`] for any other value (nothing is written).
    pub fn set_resolution(&mut self, temperature_bits: u8, humidity_bits: u8) -> Result<(), Error<I2C::Error>> {
        let t = match temperature_bits {
            14 => 0,
            11 => CFG_TRES,
            _ => return Err(Error::InvalidArgument),
        };
        let h = match humidity_bits {
            14 => 0x0000,
            11 => 0x0100,
            8 => 0x0200,
            _ => return Err(Error::InvalidArgument),
        };
        self.inner.config = (self.inner.config & !(CFG_TRES | CFG_HRES)) | t | h;
        self.inner.write_config()
    }

    /// Enable or disable the heater.
    pub fn set_heater(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.config = if on { self.inner.config | CFG_HEAT } else { self.inner.config & !CFG_HEAT };
        self.inner.write_config()
    }

    /// Read the configuration register.
    pub fn read_config(&mut self) -> Result<u16, Error<I2C::Error>> {
        let mut d = [0u8; 2];
        self.inner.i2c.write(self.inner.addr, &[REG_CONFIG])?;
        self.inner.i2c.read(self.inner.addr, &mut d)?;
        Ok(u16::from_be_bytes(d))
    }

    /// `true` if the heater bit is set in the configuration register.
    pub fn is_heater_on(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.read_config()? & CFG_HEAT != 0)
    }

    /// `true` if VDD is below 2.8 V (BTST).
    pub fn battery_low(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.read_config()? & CFG_BTST != 0)
    }

    /// Set RST, wait 15 ms, then rewrite the stored configuration.
    pub fn software_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[REG_CONFIG, (CFG_RST >> 8) as u8, 0x00])?;
        delay.delay_ms(15);
        self.inner.write_config()
    }

    /// Read the 40-bit serial number from registers `0xFB`–`0xFD`.
    pub fn serial_id(&mut self) -> Result<u64, Error<I2C::Error>> {
        let mut w = [0u64; 3];
        for (i, slot) in w.iter_mut().enumerate() {
            let mut d = [0u8; 2];
            self.inner.i2c.write(self.inner.addr, &[REG_SERIAL1 + i as u8])?;
            self.inner.i2c.read(self.inner.addr, &mut d)?;
            *slot = u16::from_be_bytes(d) as u64;
        }
        Ok((w[0] << 24) | (w[1] << 8) | (w[2] >> 7))
    }

    /// Typical conversion time in seconds for the current resolutions (temperature + humidity).
    pub fn conversion_time(&self) -> f32 {
        let c = self.inner.config;
        let t = if c & CFG_TRES != 0 { 0.00365 } else { 0.00635 };
        let h = match c & CFG_HRES {
            0x0100 => 0.00385,
            0x0200 => 0.0025,
            _ => 0.0065,
        };
        t + h
    }

    /// Check whether a triggered measurement is ready: an ACKed read header means ready (the bytes
    /// are read and cached, see [`Self::pending_result`]), a NACK means not ready. The caller
    /// wires this into its ISR or polling loop (the DRDYn pin is active low).
    pub fn poll_interrupt(&mut self) -> Result<bool, Error<I2C::Error>> {
        if self.inner.pending.is_some() {
            return Ok(true);
        }
        let n = if self.inner.config & CFG_MODE != 0 { 4 } else { 2 };
        let mut d = [0u8; 4];
        if self.inner.i2c.read(self.inner.addr, &mut d[..n]).is_err() {
            return Ok(false);
        }
        self.inner.pending = Some((d, n));
        Ok(true)
    }

    /// Take the result cached by [`Self::poll_interrupt`]: `(temperature_c, humidity_pct)`, with
    /// humidity `NaN` after a single-mode temperature trigger.
    pub fn pending_result(&mut self) -> Option<(f32, f32)> {
        self.inner.pending.take().map(|(d, n)| {
            let t = to_temperature(u16::from_be_bytes([d[0], d[1]]));
            let h = if n >= 4 { to_humidity(u16::from_be_bytes([d[2], d[3]])) } else { f32::NAN };
            (t, h)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x40;

    fn w(bytes: &[u8]) -> I2cTransaction {
        I2cTransaction::write(ADDR, bytes.to_vec())
    }

    fn r(bytes: &[u8]) -> I2cTransaction {
        I2cTransaction::read(ADDR, bytes.to_vec())
    }

    fn nack(n: usize) -> I2cTransaction {
        I2cTransaction::read(ADDR, vec![0; n]).with_error(ErrorKind::Other)
    }

    fn close(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() <= tol
    }

    #[test]
    fn init_writes_default_config() {
        let t = vec![w(&[0x02, 0x10, 0x00])];
        let mut s = HDC1008Minimal::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        s.i2c.done();
    }

    #[test]
    fn read_sequence() {
        let t = vec![w(&[0x00]), r(&[0x66, 0x66, 0x80, 0x00])];
        let mut s = HDC1008Minimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, 0x6666 as f32 / 65536.0 * 165.0 - 40.0, 1e-3));
        assert!(close(rh, 50.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn read_polls_while_nacked() {
        let t = vec![w(&[0x00]), nack(4), nack(4), nack(4), r(&[0x66, 0x66, 0x80, 0x00])];
        let mut s = HDC1008Minimal::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read(&mut NoopDelay).unwrap().1, 50.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn timeout_when_never_ready() {
        let mut t = vec![w(&[0x00])];
        for _ in 0..50 {
            t.push(nack(4));
        }
        let mut s = HDC1008Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Timeout));
        s.i2c.done();
    }

    #[test]
    fn single_measurements_switch_mode() {
        let t = vec![
            w(&[0x02, 0x00, 0x00]),
            w(&[0x00]),
            r(&[0x80, 0x00]),
            w(&[0x01]),
            r(&[0x80, 0x00]),
        ];
        let mut s = HDC1008Full::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), 42.5, 1e-3));
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 50.0, 1e-3));
        s.inner.i2c.done();
    }

    #[test]
    fn read_raw_words() {
        let t = vec![w(&[0x00]), r(&[0x12, 0x34, 0x56, 0x78])];
        let mut s = HDC1008Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), (0x1234, 0x5678));
        s.inner.i2c.done();
    }

    #[test]
    fn resolution_and_conversion_time() {
        let t = vec![w(&[0x02, 0x16, 0x00]), w(&[0x02, 0x10, 0x00])];
        let mut s = HDC1008Full::new(I2cMock::new(&t), ADDR);
        s.set_resolution(11, 8).unwrap();
        assert!(close(s.conversion_time(), 0.00365 + 0.0025, 1e-6));
        s.set_resolution(14, 14).unwrap();
        assert!(close(s.conversion_time(), 0.01285, 1e-6));
        assert_eq!(s.set_resolution(8, 14), Err(Error::InvalidArgument));
        assert_eq!(s.set_resolution(14, 12), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn heater_battery_config() {
        let t = vec![
            w(&[0x02, 0x30, 0x00]),
            w(&[0x02]), r(&[0x30, 0x00]),
            w(&[0x02]), r(&[0x18, 0x00]),
            w(&[0x02]), r(&[0x10, 0x00]),
        ];
        let mut s = HDC1008Full::new(I2cMock::new(&t), ADDR);
        s.set_heater(true).unwrap();
        assert!(s.is_heater_on().unwrap());
        assert!(s.battery_low().unwrap());
        assert_eq!(s.read_config().unwrap(), 0x1000);
        s.inner.i2c.done();
    }

    #[test]
    fn software_reset_rewrites_config() {
        let t = vec![w(&[0x02, 0x30, 0x00]), w(&[0x02, 0x80, 0x00]), w(&[0x02, 0x30, 0x00])];
        let mut s = HDC1008Full::new(I2cMock::new(&t), ADDR);
        s.set_heater(true).unwrap();
        s.software_reset(&mut NoopDelay).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn serial_id_combines_words() {
        let t = vec![w(&[0xFB]), r(&[0x00, 0x12]), w(&[0xFC]), r(&[0x34, 0x56]), w(&[0xFD]), r(&[0x80, 0x00])];
        let mut s = HDC1008Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.serial_id().unwrap(), (0x12u64 << 24) | (0x3456u64 << 8) | 0x100);
        s.inner.i2c.done();
    }

    #[test]
    fn poll_interrupt_caches_result() {
        let t = vec![nack(4), r(&[0x80, 0x00, 0x80, 0x00])];
        let mut s = HDC1008Full::new(I2cMock::new(&t), ADDR);
        assert!(!s.poll_interrupt().unwrap());
        assert!(s.poll_interrupt().unwrap());
        let (temp, rh) = s.pending_result().unwrap();
        assert!(close(temp, 42.5, 1e-3) && close(rh, 50.0, 1e-3));
        assert!(s.pending_result().is_none());
        s.inner.i2c.done();
    }

    #[test]
    fn minimal_class_read() {
        let t = vec![w(&[0x00]), r(&[0x66, 0x66, 0x80, 0x00])];
        let mut s = HDC1008Minimal::new(I2cMock::new(&t), ADDR);
        assert!(s.read(&mut NoopDelay).is_ok());
        s.i2c.done();
    }
}
