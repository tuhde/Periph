//! SHT31 — digital temperature and humidity sensor (Sensirion SHT3x-DIS family).
//!
//! Communicates over I²C (up to 1 MHz) at `0x44` (ADDR low) or `0x45` (ADDR high). The sensor
//! uses 16-bit commands (MSB first) rather than a register map, and every 16-bit result word is
//! followed by a CRC-8 (polynomial `0x31`, init `0xFF`). Both [`Sht31Minimal::new`] and
//! [`Sht31Full::new`] perform a soft reset (`0x30A2`) with 1 ms waits.
//!
//! Single-shot reads use clock stretching **disabled** by default: the driver waits the
//! conversion time (4 / 6 / 15 ms for low / medium / high repeatability) and keeps polling while
//! the read header is NACKed. Any bus error on the result read is treated as "not ready".
//!
//! Heater, periodic mode (0.5-10 mps plus ART), the status register and the dew point are
//! provided by [`Sht31Full`]. The ALERT pin is not part of this driver.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_SOFT_RESET: u16 = 0x30A2;
const CMD_HEATER_ON: u16 = 0x306D;
const CMD_HEATER_OFF: u16 = 0x3066;
const CMD_READ_STATUS: u16 = 0xF32D;
const CMD_CLEAR_STATUS: u16 = 0x3041;
const CMD_FETCH: u16 = 0xE000;
const CMD_BREAK: u16 = 0x3093;
const CMD_ART: u16 = 0x2B32;

/// Measurement repeatability (and therefore conversion time: 4 / 6 / 15 ms).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Repeatability {
    /// Low repeatability, ≤ 4 ms.
    Low,
    /// Medium repeatability, ≤ 6 ms.
    Medium,
    /// High repeatability, ≤ 15 ms (default).
    High,
}

impl Repeatability {
    fn index(self) -> usize {
        match self {
            Repeatability::Low => 0,
            Repeatability::Medium => 1,
            Repeatability::High => 2,
        }
    }
}

// Single-shot command codes (index 0 low, 1 medium, 2 high).
const SINGLE_NO_STRETCH: [u16; 3] = [0x2416, 0x240B, 0x2400];
const SINGLE_STRETCH: [u16; 3] = [0x2C10, 0x2C0D, 0x2C06];
const SINGLE_WAIT_MS: [u32; 3] = [4, 6, 15];

/// Periodic command codes: rows 0.5 / 1 / 2 / 4 / 10 mps, columns low / medium / high.
const PERIODIC: [[u16; 3]; 5] = [
    [0x202F, 0x2024, 0x2032],
    [0x212D, 0x2126, 0x2130],
    [0x222B, 0x2220, 0x2236],
    [0x2329, 0x2322, 0x2334],
    [0x272A, 0x2721, 0x2737],
];

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// A CRC-8 check failed (persisting after one retry for single-shot reads).
    Crc,
    /// The result was not ready within the polling window.
    Timeout,
    /// Unsupported rate or argument.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// CRC-8 used by the SHT3x: polynomial `0x31`, init `0xFF`, no reflection, no final XOR.
pub fn crc8(data: &[u8]) -> u8 {
    let mut crc = 0xFFu8;
    for &byte in data {
        crc ^= byte;
        for _ in 0..8 {
            crc = if crc & 0x80 != 0 { (crc << 1) ^ 0x31 } else { crc << 1 };
        }
    }
    crc
}

fn decode<E>(data: &[u8; 6]) -> Result<(u16, u16), Error<E>> {
    if crc8(&data[0..2]) != data[2] || crc8(&data[3..5]) != data[5] {
        return Err(Error::Crc);
    }
    Ok((((data[0] as u16) << 8) | data[1] as u16, ((data[3] as u16) << 8) | data[4] as u16))
}

fn convert(raw_t: u16, raw_rh: u16) -> (f32, f32) {
    (-45.0 + 175.0 * raw_t as f32 / 65535.0, 100.0 * raw_rh as f32 / 65535.0)
}

/// SHT31 minimal driver — one single-shot measurement per `read()`.
///
/// Default configuration: high repeatability (`0x2400`), clock stretching disabled, fixed 15 ms
/// wait followed by polling for up to 20 ms, both CRC bytes verified with one retry of the whole
/// measurement, heater off.
pub struct Sht31Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    repeatability: Repeatability,
    stretch: bool,
}

impl<I2C: I2c> Sht31Minimal<I2C> {
    /// Create a new `Sht31Minimal` and soft-reset the sensor (1 ms waits before and after).
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (`0x44` with ADDR low, `0x45` with ADDR high).
    /// * `delay` — Delay provider for the conversion and reset times.
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Result<Self, Error<I2C::Error>> {
        let mut s = Self { i2c, addr, repeatability: Repeatability::High, stretch: false };
        delay.delay_ms(1);
        s.command(CMD_SOFT_RESET)?;
        delay.delay_ms(1);
        Ok(s)
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn command(&mut self, code: u16) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &code.to_be_bytes())?;
        Ok(())
    }

    fn read_result<D: DelayNs>(&mut self, delay: &mut D) -> Result<[u8; 6], Error<I2C::Error>> {
        let mut buf = [0u8; 6];
        for _ in 0..21 {
            if self.i2c.read(self.addr, &mut buf).is_ok() {
                return Ok(buf);
            }
            delay.delay_ms(1); // read header NACKed: not ready yet
        }
        Err(Error::Timeout)
    }

    fn single_shot<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        let idx = self.repeatability.index();
        for attempt in 0..2 {
            let code = if self.stretch { SINGLE_STRETCH[idx] } else { SINGLE_NO_STRETCH[idx] };
            self.command(code)?;
            if !self.stretch {
                delay.delay_ms(SINGLE_WAIT_MS[idx]);
            }
            let buf = self.read_result(delay)?;
            match decode::<I2C::Error>(&buf) {
                Ok(v) => return Ok(v),
                Err(e) if attempt == 1 => return Err(e),
                Err(_) => {}
            }
        }
        Err(Error::Crc)
    }

    /// Run one single-shot measurement.
    ///
    /// Returns `(temperature_c, humidity_pct)` in degrees Celsius and %RH.
    /// [`Error::Crc`] if a CRC mismatch persists after one retry.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let (t, rh) = self.single_shot(delay)?;
        Ok(convert(t, rh))
    }
}

/// SHT31 full driver — repeatability, clock stretching, periodic mode, heater and status.
///
/// Owns a [`Sht31Minimal`] and delegates `read`.
pub struct Sht31Full<I2C> {
    inner: Sht31Minimal<I2C>,
}

impl<I2C: I2c> Sht31Full<I2C> {
    /// Create a new `Sht31Full`; same arguments as [`Sht31Minimal::new`].
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Result<Self, Error<I2C::Error>> {
        Ok(Self { inner: Sht31Minimal::new(i2c, addr, delay)? })
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Run one single-shot measurement; see [`Sht31Minimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Run one measurement and return the temperature in °C only.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.inner.read(delay)?.0)
    }

    /// Run one measurement and return the relative humidity in %RH only.
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.inner.read(delay)?.1)
    }

    /// Run one measurement and return the raw 16-bit words `(temperature, humidity)`.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        self.inner.single_shot(delay)
    }

    /// Select the repeatability for later single-shot reads.
    pub fn set_repeatability(&mut self, level: Repeatability) {
        self.inner.repeatability = level;
    }

    /// Use the clock-stretching command set for single-shot reads (the bus layer must support it);
    /// the fixed wait is skipped.
    pub fn set_clock_stretching(&mut self, on: bool) {
        self.inner.stretch = on;
    }

    /// Start periodic measurements; read results with [`Sht31Full::fetch`].
    ///
    /// `rate` ∈ {0.5, 1, 2, 4, 10} measurements per second (ignored when `art` is set);
    /// `art` selects the accelerated response time mode (4 mps, command `0x2B32`).
    /// [`Error::InvalidArgument`] for an unsupported rate.
    pub fn start_periodic(&mut self, rate: f32, repeatability: Repeatability, art: bool) -> Result<(), Error<I2C::Error>> {
        if art {
            return self.inner.command(CMD_ART);
        }
        let row = if rate == 0.5 {
            0
        } else if rate == 1.0 {
            1
        } else if rate == 2.0 {
            2
        } else if rate == 4.0 {
            3
        } else if rate == 10.0 {
            4
        } else {
            return Err(Error::InvalidArgument);
        };
        self.inner.command(PERIODIC[row][repeatability.index()])
    }

    /// Read the latest periodic result (command `0xE000`).
    ///
    /// Returns `Ok(None)` when no new data is available (read header NACKed) and
    /// [`Error::Crc`] if a CRC check fails.
    pub fn fetch(&mut self) -> Result<Option<(f32, f32)>, Error<I2C::Error>> {
        self.inner.command(CMD_FETCH)?;
        let mut buf = [0u8; 6];
        if self.inner.i2c.read(self.inner.addr, &mut buf).is_err() {
            return Ok(None);
        }
        let (t, rh) = decode::<I2C::Error>(&buf)?;
        Ok(Some(convert(t, rh)))
    }

    /// Stop periodic mode (break command `0x3093`) and wait 1 ms.
    pub fn stop_periodic<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(CMD_BREAK)?;
        delay.delay_ms(1);
        Ok(())
    }

    /// Switch the on-chip heater (about 5-25 mW) on or off.
    pub fn set_heater(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.command(if on { CMD_HEATER_ON } else { CMD_HEATER_OFF })
    }

    /// Read the status register (command `0xF32D`, CRC verified).
    ///
    /// E.g. `0x8010` after reset; bit 13 = heater on.
    pub fn read_status(&mut self) -> Result<u16, Error<I2C::Error>> {
        self.inner.command(CMD_READ_STATUS)?;
        let mut buf = [0u8; 3];
        self.inner.i2c.read(self.inner.addr, &mut buf)?;
        if crc8(&buf[0..2]) != buf[2] {
            return Err(Error::Crc);
        }
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    /// Clear the status register flags (command `0x3041`).
    pub fn clear_status(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.command(CMD_CLEAR_STATUS)
    }

    /// Soft reset (`0x30A2`), wait 1 ms; leaves periodic mode and returns to single shot.
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(CMD_SOFT_RESET)?;
        delay.delay_ms(1);
        Ok(())
    }

    /// Run one measurement and compute the dew point in °C (Magnus formula).
    pub fn dew_point<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (t, mut rh) = self.inner.read(delay)?;
        if rh <= 0.0 {
            rh = 0.01;
        }
        let gamma = ln_f32(rh / 100.0) + (17.62 * t) / (243.12 + t);
        Ok(243.12 * gamma / (17.62 - gamma))
    }
}

/// Natural logarithm that also works without `std` (`core` has no `ln`; a short series after range reduction is used).
#[cfg(feature = "std")]
fn ln_f32(x: f32) -> f32 {
    x.ln()
}

#[cfg(not(feature = "std"))]
fn ln_f32(x: f32) -> f32 {
    // ln(x) = 2 * atanh((x-1)/(x+1)) after range reduction by powers of two.
    let mut m = x;
    let mut e = 0i32;
    while m > 1.5 {
        m /= 2.0;
        e += 1;
    }
    while m < 0.75 {
        m *= 2.0;
        e -= 1;
    }
    let y = (m - 1.0) / (m + 1.0);
    let y2 = y * y;
    let series = 2.0 * y * (1.0 + y2 / 3.0 + y2 * y2 / 5.0 + y2 * y2 * y2 / 7.0 + y2 * y2 * y2 * y2 / 9.0);
    series + e as f32 * core::f32::consts::LN_2
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x44;

    fn cmd(code: u16) -> I2cTransaction {
        I2cTransaction::write(ADDR, code.to_be_bytes().to_vec())
    }

    fn frame(raw_t: u16, raw_rh: u16) -> Vec<u8> {
        let t = raw_t.to_be_bytes();
        let h = raw_rh.to_be_bytes();
        vec![t[0], t[1], crc8(&t), h[0], h[1], crc8(&h)]
    }

    fn nack() -> I2cTransaction {
        I2cTransaction::read(ADDR, vec![0; 6]).with_error(ErrorKind::Other)
    }

    fn close(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() <= tol
    }

    #[test]
    fn crc_reference() {
        assert_eq!(crc8(&[0xBE, 0xEF]), 0x92);
    }

    #[test]
    fn init_and_read() {
        let t = vec![cmd(0x30A2), cmd(0x2400), I2cTransaction::read(ADDR, frame(0x6666, 0x8000))];
        let mut s = Sht31Minimal::new(I2cMock::new(&t), ADDR, &mut NoopDelay).unwrap();
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, -45.0 + 175.0 * 0x6666 as f32 / 65535.0, 1e-3));
        assert!(close(rh, 100.0 * 0x8000 as f32 / 65535.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn read_polls_while_nacked() {
        let t = vec![cmd(0x30A2), cmd(0x2400), nack(), nack(), I2cTransaction::read(ADDR, frame(0, 65535))];
        let mut s = Sht31Minimal::new(I2cMock::new(&t), ADDR, &mut NoopDelay).unwrap();
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, -45.0, 1e-3) && close(rh, 100.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn crc_retry_then_error() {
        let mut bad = frame(0x1234, 0x5678);
        bad[2] ^= 0xFF;
        let t = vec![
            cmd(0x30A2),
            cmd(0x2400),
            I2cTransaction::read(ADDR, bad.clone()),
            cmd(0x2400),
            I2cTransaction::read(ADDR, frame(0x1234, 0x5678)),
            cmd(0x2400),
            I2cTransaction::read(ADDR, bad.clone()),
            cmd(0x2400),
            I2cTransaction::read(ADDR, bad),
        ];
        let mut s = Sht31Minimal::new(I2cMock::new(&t), ADDR, &mut NoopDelay).unwrap();
        assert!(s.read(&mut NoopDelay).is_ok());
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Crc));
        s.i2c.done();
    }

    #[test]
    fn timeout_when_never_ready() {
        let mut t = vec![cmd(0x30A2), cmd(0x2400)];
        for _ in 0..21 {
            t.push(nack());
        }
        let mut s = Sht31Minimal::new(I2cMock::new(&t), ADDR, &mut NoopDelay).unwrap();
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Timeout));
        s.i2c.done();
    }

    #[test]
    fn full_reads_repeatability_and_stretching() {
        let t = vec![
            cmd(0x30A2),
            cmd(0x2416),
            I2cTransaction::read(ADDR, frame(1000, 2000)),
            cmd(0x240B),
            I2cTransaction::read(ADDR, frame(0, 0)),
            cmd(0x2C0D),
            I2cTransaction::read(ADDR, frame(0, 0)),
            cmd(0x2C06),
            I2cTransaction::read(ADDR, frame(0, 0)),
            cmd(0x2400),
            I2cTransaction::read(ADDR, frame(0, 65535)),
        ];
        let mut s = Sht31Full::new(I2cMock::new(&t), ADDR, &mut NoopDelay).unwrap();
        s.set_repeatability(Repeatability::Low);
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), (1000, 2000));
        s.set_repeatability(Repeatability::Medium);
        s.read(&mut NoopDelay).unwrap();
        s.set_clock_stretching(true);
        s.read_temperature(&mut NoopDelay).unwrap();
        s.set_repeatability(Repeatability::High);
        s.read(&mut NoopDelay).unwrap();
        s.set_clock_stretching(false);
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 100.0, 1e-3));
        s.inner.i2c.done();
    }

    #[test]
    fn periodic_fetch_and_stop() {
        let t = vec![
            cmd(0x30A2),
            cmd(0x2236),
            cmd(0x202F),
            cmd(0x2721),
            cmd(0x2B32),
            cmd(0xE000),
            I2cTransaction::read(ADDR, frame(0x4000, 0x4000)),
            cmd(0xE000),
            nack(),
            cmd(0x3093),
        ];
        let mut s = Sht31Full::new(I2cMock::new(&t), ADDR, &mut NoopDelay).unwrap();
        s.start_periodic(2.0, Repeatability::High, false).unwrap();
        s.start_periodic(0.5, Repeatability::Low, false).unwrap();
        s.start_periodic(10.0, Repeatability::Medium, false).unwrap();
        s.start_periodic(1.0, Repeatability::High, true).unwrap();
        let (temp, _) = s.fetch().unwrap().unwrap();
        assert!(close(temp, -45.0 + 175.0 * 0x4000 as f32 / 65535.0, 1e-3));
        assert_eq!(s.fetch().unwrap(), None);
        s.stop_periodic(&mut NoopDelay).unwrap();
        assert_eq!(s.start_periodic(3.0, Repeatability::High, false), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn heater_status_reset() {
        let t = vec![
            cmd(0x30A2),
            cmd(0x306D),
            cmd(0x3066),
            cmd(0x3041),
            cmd(0xF32D),
            I2cTransaction::read(ADDR, vec![0x80, 0x10, crc8(&[0x80, 0x10])]),
            cmd(0xF32D),
            I2cTransaction::read(ADDR, vec![0x80, 0x10, 0x00]),
            cmd(0x30A2),
        ];
        let mut s = Sht31Full::new(I2cMock::new(&t), ADDR, &mut NoopDelay).unwrap();
        s.set_heater(true).unwrap();
        s.set_heater(false).unwrap();
        s.clear_status().unwrap();
        assert_eq!(s.read_status().unwrap(), 0x8010);
        assert_eq!(s.read_status(), Err(Error::Crc));
        s.soft_reset(&mut NoopDelay).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn dew_point_value() {
        let raw_t = ((25.0f32 + 45.0) * 65535.0 / 175.0).round() as u16;
        let raw_rh = (50.0f32 * 65535.0 / 100.0).round() as u16;
        let t = vec![cmd(0x30A2), cmd(0x2400), I2cTransaction::read(ADDR, frame(raw_t, raw_rh))];
        let mut s = Sht31Full::new(I2cMock::new(&t), ADDR, &mut NoopDelay).unwrap();
        assert!(close(s.dew_point(&mut NoopDelay).unwrap(), 13.86, 0.05));
        s.inner.i2c.done();
    }
}
