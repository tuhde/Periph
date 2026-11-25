//! HTU31D — relative humidity and temperature sensor (TE Connectivity).
//!
//! Communicates over I²C at `0x40` (`IC_Add` low) or `0x41` (`IC_Add` high). The chip is command
//! driven (no register pointer): a conversion command with selectable oversampling (OSR 0–3 for
//! humidity and temperature) loads a result into the sensor memory and a separate read command
//! returns it. Each 16-bit result word is followed by a CRC-8 (polynomial `0x31`, init `0x00`).
//! Construction does no bus access; `init()` performs the soft reset.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_RESET: u8 = 0x1E;
const CMD_HEATER_ON: u8 = 0x04;
const CMD_HEATER_OFF: u8 = 0x02;
const CMD_CONVERT: u8 = 0x40;
const CMD_READ_TRH: u8 = 0x00;
const CMD_READ_RH: u8 = 0x10;
const CMD_READ_DIAG: u8 = 0x08;
const CMD_READ_SERIAL: u8 = 0x0A;

/// Typical humidity conversion time in µs for OSR 0..3.
const T_RH_US: [u32; 4] = [1110, 2140, 4210, 8340];
/// Typical temperature conversion time in µs for OSR 0..3.
const T_T_US: [u32; 4] = [1570, 3060, 6030, 11980];

/// Diagnostic bit: NVM CRC failed.
pub const DIAG_NVM_ERR: u8 = 0x80;
/// Diagnostic bit: humidity truncated.
pub const DIAG_RH_OVERRUN: u8 = 0x40;
/// Diagnostic bit: humidity calculation above 120 %RH.
pub const DIAG_RH_HIGH_ERR: u8 = 0x20;
/// Diagnostic bit: humidity calculation below −10 %RH.
pub const DIAG_RH_LOW_ERR: u8 = 0x10;
/// Diagnostic bit: temperature truncated.
pub const DIAG_T_OVERRUN: u8 = 0x08;
/// Diagnostic bit: temperature calculation above 150 °C.
pub const DIAG_T_HIGH_ERR: u8 = 0x04;
/// Diagnostic bit: temperature calculation below −50 °C.
pub const DIAG_T_LOW_ERR: u8 = 0x02;
/// Diagnostic bit: the heater is on.
pub const DIAG_HEATER_ON: u8 = 0x01;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// A CRC-8 check failed.
    Crc,
    /// Unsupported oversampling setting (must be 0–3).
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// CRC-8 used by the HTU31D: polynomial `0x31` (x⁸ + x⁵ + x⁴ + 1), init `0x00`, no final XOR.
pub fn crc8(data: &[u8]) -> u8 {
    let mut crc = 0x00u8;
    for &byte in data {
        crc ^= byte;
        for _ in 0..8 {
            crc = if crc & 0x80 != 0 { (crc << 1) ^ 0x31 } else { crc << 1 };
        }
    }
    crc
}

fn to_humidity(code: u16) -> f32 {
    (100.0 * code as f32 / 65535.0).clamp(0.0, 100.0)
}

fn to_temperature(code: u16) -> f32 {
    -40.0 + 165.0 * code as f32 / 65535.0
}

/// HTU31D minimal driver — one conversion at the highest resolution per `read()`.
///
/// Default configuration: OSR 3 / 3 (command `0x5E`), 25 ms wait, both CRCs verified, humidity
/// clamped to 0–100 %RH.
pub struct HTU31DMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    osr_rh: u8,
    osr_t: u8,
}

impl<I2C: I2c> HTU31DMinimal<I2C> {
    /// Create a new `HTU31DMinimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x40` or `0x41`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, osr_rh: 3, osr_t: 3 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Soft reset (`0x1E`) and wait 15 ms; heater off, highest resolution.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.command(CMD_RESET)?;
        delay.delay_ms(15);
        Ok(())
    }

    fn command(&mut self, code: u8) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[code])?;
        Ok(())
    }

    fn start(&mut self) -> Result<(), Error<I2C::Error>> {
        self.command(CMD_CONVERT | (self.osr_rh << 3) | (self.osr_t << 1))
    }

    fn wait_us(&self) -> u32 {
        if self.osr_rh == 3 && self.osr_t == 3 {
            25_000
        } else {
            (T_RH_US[self.osr_rh as usize] + T_T_US[self.osr_t as usize]) * 5 / 4
        }
    }

    /// Write a read command and return the CRC-verified 16-bit words (`N` words).
    fn read_words<const N: usize>(&mut self, command: u8) -> Result<[u16; N], Error<I2C::Error>> {
        self.command(command)?;
        let mut buf = [0u8; 6];
        self.i2c.read(self.addr, &mut buf[..3 * N])?;
        let mut out = [0u16; N];
        for (i, word) in out.iter_mut().enumerate() {
            let c = &buf[3 * i..3 * i + 3];
            if crc8(&c[0..2]) != c[2] {
                return Err(Error::Crc);
            }
            *word = ((c[0] as u16) << 8) | c[1] as u16;
        }
        Ok(out)
    }

    fn measure<const N: usize, D: DelayNs>(&mut self, command: u8, delay: &mut D) -> Result<[u16; N], Error<I2C::Error>> {
        self.start()?;
        delay.delay_us(self.wait_us());
        self.read_words::<N>(command)
    }

    /// Measure temperature and humidity.
    ///
    /// Returns `(temperature_c, humidity_pct)` in degrees Celsius and %RH (0–100).
    /// [`Error::Crc`] if either CRC does not match.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let [t, rh] = self.measure::<2, D>(CMD_READ_TRH, delay)?;
        Ok((to_temperature(t), to_humidity(rh)))
    }
}

/// HTU31D full driver — oversampling, non-blocking conversion, heater, diagnostics, dew point.
///
/// Owns a [`HTU31DMinimal`] and delegates `init` and `read`.
pub struct HTU31DFull<I2C> {
    inner: HTU31DMinimal<I2C>,
}

impl<I2C: I2c> HTU31DFull<I2C> {
    /// Create a new `HTU31DFull`; same arguments as [`HTU31DMinimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: HTU31DMinimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Soft reset and wait 15 ms (see [`HTU31DMinimal::init`]).
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Measure temperature and humidity (see [`HTU31DMinimal::read`]).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Run one humidity measurement (read command `0x10`); %RH, clamped to 0–100.
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let [rh] = self.inner.measure::<1, D>(CMD_READ_RH, delay)?;
        Ok(to_humidity(rh))
    }

    /// Run one temperature measurement (the read stops after the temperature word); °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let [t] = self.inner.measure::<1, D>(CMD_READ_TRH, delay)?;
        Ok(to_temperature(t))
    }

    /// Measure and return the raw 16-bit codes `(t_raw, rh_raw)`.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        let [t, rh] = self.inner.measure::<2, D>(CMD_READ_TRH, delay)?;
        Ok((t, rh))
    }

    /// Select the oversampling of the following measurements (0–3 each).
    ///
    /// [`Error::InvalidArgument`] for other values.
    pub fn set_resolution(&mut self, osr_rh: u8, osr_t: u8) -> Result<(), Error<I2C::Error>> {
        if osr_rh > 3 || osr_t > 3 {
            return Err(Error::InvalidArgument);
        }
        self.inner.osr_rh = osr_rh;
        self.inner.osr_t = osr_t;
        Ok(())
    }

    /// Return the oversampling setting `(osr_rh, osr_t)`.
    pub fn get_resolution(&self) -> (u8, u8) {
        (self.inner.osr_rh, self.inner.osr_t)
    }

    /// Typical conversion time of one measurement at the current oversampling, in seconds.
    pub fn conversion_time(&self) -> f32 {
        (T_RH_US[self.inner.osr_rh as usize] + T_T_US[self.inner.osr_t as usize]) as f32 / 1.0e6
    }

    /// Write the conversion command only (non-blocking use).
    pub fn start_conversion(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.start()
    }

    /// Read the result of the last conversion (command `0x00`); `(temperature_c, humidity_pct)`.
    pub fn read_result(&mut self) -> Result<(f32, f32), Error<I2C::Error>> {
        let [t, rh] = self.inner.read_words::<2>(CMD_READ_TRH)?;
        Ok((to_temperature(t), to_humidity(rh)))
    }

    /// Switch the on-chip heater on (`0x04`) or off (`0x02`).
    pub fn set_heater(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.command(if on { CMD_HEATER_ON } else { CMD_HEATER_OFF })
    }

    /// Read the diagnostic byte (command `0x08`); test it against the `DIAG_*` masks.
    pub fn diagnostic(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.command(CMD_READ_DIAG)?;
        let mut b = [0u8; 1];
        self.inner.i2c.read(self.inner.addr, &mut b)?;
        Ok(b[0])
    }

    /// True if the diagnostic byte reports the heater on.
    pub fn is_heater_on(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.diagnostic()? & DIAG_HEATER_ON != 0)
    }

    /// Read the 24-bit serial number (command `0x0A`, CRC verified).
    pub fn serial_number(&mut self) -> Result<u32, Error<I2C::Error>> {
        self.inner.command(CMD_READ_SERIAL)?;
        let mut b = [0u8; 4];
        self.inner.i2c.read(self.inner.addr, &mut b)?;
        if crc8(&b[0..3]) != b[3] {
            return Err(Error::Crc);
        }
        Ok(((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32)
    }

    /// Soft reset (`0x1E`) and wait 15 ms; heater off, oversampling back to OSR 3 / 3.
    pub fn software_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(CMD_RESET)?;
        delay.delay_ms(15);
        self.inner.osr_rh = 3;
        self.inner.osr_t = 3;
        Ok(())
    }

    /// Run one measurement and compute the dew point in °C (Magnus formula over water).
    pub fn dew_point<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (t, mut rh) = self.read(delay)?;
        if rh <= 0.0 {
            rh = 0.01;
        }
        let g = libm::logf(rh / 100.0) + 17.62 * t / (243.12 + t);
        Ok(243.12 * g / (17.62 - g))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x40;

    fn cmd(code: u8) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![code])
    }

    fn word(code: u16) -> Vec<u8> {
        let d = code.to_be_bytes();
        vec![d[0], d[1], crc8(&d)]
    }

    fn trh(t: u16, rh: u16) -> Vec<u8> {
        let mut v = word(t);
        v.extend(word(rh));
        v
    }

    fn close(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() <= tol
    }

    #[test]
    fn crc_reference() {
        assert_eq!(crc8(&[0xDC]), 0x79);
        assert_eq!(crc8(&[0x68, 0x3A]), 0x7C);
        assert_eq!(crc8(&[0x4E, 0x85]), 0x6B);
    }

    #[test]
    fn init_resets() {
        let mut s = HTU31DMinimal::new(I2cMock::new(&[cmd(0x1E)]), ADDR);
        s.init(&mut NoopDelay).unwrap();
        s.i2c.done();
    }

    #[test]
    fn read_converts() {
        let t = [cmd(0x5E), cmd(0x00), I2cTransaction::read(ADDR, trh(0x683A, 0x8000))];
        let mut s = HTU31DMinimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, -40.0 + 165.0 * 0x683A as f32 / 65535.0, 1e-3));
        assert!(close(rh, 100.0 * 0x8000 as f32 / 65535.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn read_full_scale() {
        let t = [cmd(0x5E), cmd(0x00), I2cTransaction::read(ADDR, trh(0xFFFF, 0xFFFF))];
        let mut s = HTU31DMinimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, 125.0, 1e-3) && close(rh, 100.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn crc_mismatch_rejected() {
        let mut bad = trh(0x683A, 0x8000);
        bad[5] ^= 0xFF;
        let t = [cmd(0x5E), cmd(0x00), I2cTransaction::read(ADDR, bad)];
        let mut s = HTU31DMinimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Crc));
        s.i2c.done();
    }

    #[test]
    fn single_values() {
        let t = [
            cmd(0x5E), cmd(0x10), I2cTransaction::read(ADDR, word(0x4000)),
            cmd(0x5E), cmd(0x00), I2cTransaction::read(ADDR, word(0x4000)),
            cmd(0x5E), cmd(0x00), I2cTransaction::read(ADDR, trh(0x1234, 0x5678)),
        ];
        let mut s = HTU31DFull::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 100.0 * 0x4000 as f32 / 65535.0, 1e-3));
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), -40.0 + 165.0 * 0x4000 as f32 / 65535.0, 1e-3));
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), (0x1234, 0x5678));
        s.inner.i2c.done();
    }

    #[test]
    fn resolution_changes_command() {
        let t = [cmd(0x40 | (2 << 3) | (1 << 1)), cmd(0x00), I2cTransaction::read(ADDR, trh(0x4000, 0x4000))];
        let mut s = HTU31DFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.get_resolution(), (3, 3));
        assert!(close(s.conversion_time(), 0.02032, 1e-6));
        s.set_resolution(2, 1).unwrap();
        assert_eq!(s.get_resolution(), (2, 1));
        s.read(&mut NoopDelay).unwrap();
        assert!(s.set_resolution(4, 0).is_err() && s.set_resolution(0, 4).is_err());
        s.inner.i2c.done();
    }

    #[test]
    fn start_and_read_result() {
        let t = [cmd(0x40 | (1 << 3) | (2 << 1)), cmd(0x00), I2cTransaction::read(ADDR, trh(0x2000, 0x6000))];
        let mut s = HTU31DFull::new(I2cMock::new(&t), ADDR);
        s.set_resolution(1, 2).unwrap();
        s.start_conversion().unwrap();
        let (_, rh) = s.read_result().unwrap();
        assert!(close(rh, 100.0 * 0x6000 as f32 / 65535.0, 1e-3));
        s.inner.i2c.done();
    }

    #[test]
    fn heater_and_diagnostic() {
        let t = [
            cmd(0x04), cmd(0x02),
            cmd(0x08), I2cTransaction::read(ADDR, vec![0x01]),
            cmd(0x08), I2cTransaction::read(ADDR, vec![0xA5]),
        ];
        let mut s = HTU31DFull::new(I2cMock::new(&t), ADDR);
        s.set_heater(true).unwrap();
        s.set_heater(false).unwrap();
        assert!(s.is_heater_on().unwrap());
        let d = s.diagnostic().unwrap();
        assert!(d & DIAG_NVM_ERR != 0 && d & DIAG_RH_HIGH_ERR != 0 && d & DIAG_RH_OVERRUN == 0);
        s.inner.i2c.done();
    }

    #[test]
    fn serial_number_crc() {
        let d = [0xAB, 0xCD, 0xEF];
        let t = [
            cmd(0x0A), I2cTransaction::read(ADDR, vec![d[0], d[1], d[2], crc8(&d)]),
            cmd(0x0A), I2cTransaction::read(ADDR, vec![d[0], d[1], d[2], crc8(&d) ^ 0x55]),
        ];
        let mut s = HTU31DFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.serial_number().unwrap(), 0xABCDEF);
        assert_eq!(s.serial_number(), Err(Error::Crc));
        s.inner.i2c.done();
    }

    #[test]
    fn software_reset_restores_defaults() {
        let mut s = HTU31DFull::new(I2cMock::new(&[cmd(0x1E)]), ADDR);
        s.set_resolution(0, 1).unwrap();
        s.software_reset(&mut NoopDelay).unwrap();
        assert_eq!(s.get_resolution(), (3, 3));
        s.inner.i2c.done();
    }

    #[test]
    fn dew_point_value() {
        let t_code = ((65.0f32 * 65535.0 / 165.0).round()) as u16;
        let rh_code = ((0.5f32 * 65535.0).round()) as u16;
        let t = [cmd(0x5E), cmd(0x00), I2cTransaction::read(ADDR, trh(t_code, rh_code))];
        let mut s = HTU31DFull::new(I2cMock::new(&t), ADDR);
        assert!(close(s.dew_point(&mut NoopDelay).unwrap(), 13.86, 0.15));
        s.inner.i2c.done();
    }
}
