//! HTU21D-F — relative humidity and temperature sensor (Measurement Specialties).
//!
//! Communicates over I²C (up to 400 kHz) at the fixed address `0x40`. The chip has no register
//! pointer: single-byte commands start a measurement, read or write the one user register or
//! reset the chip. A result is three bytes (MSB, LSB, CRC-8 with polynomial `0x131` and init
//! `0x00`); the two LSBs of the 16-bit value are status bits and are cleared before conversion.
//!
//! Measurements use no-hold-master mode by default: the driver sends `0xF3` / `0xF5` and polls
//! the read header (NACKed until the result is ready, 100 attempts 1 ms apart). Hold master
//! (clock stretching) can be enabled on [`HTU21DFFull`]. Construction does no bus access;
//! `init()` performs the soft reset.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_T_HOLD: u8 = 0xE3;
const CMD_RH_HOLD: u8 = 0xE5;
const CMD_T_POLL: u8 = 0xF3;
const CMD_RH_POLL: u8 = 0xF5;
const CMD_WRITE_USER: u8 = 0xE6;
const CMD_READ_USER: u8 = 0xE7;
const CMD_RESET: u8 = 0xFE;
const POLL_ATTEMPTS: u32 = 100;

const USER_HEATER: u8 = 0x04;
const USER_VDD_LOW: u8 = 0x40;
const USER_RES_MASK: u8 = 0x81;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// A CRC-8 check failed twice.
    Crc,
    /// The result was not ready within the polling window (100 ms).
    Timeout,
    /// Unsupported resolution setting.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// CRC-8 used by the HTU21D-F: polynomial `0x131` (x⁸ + x⁵ + x⁴ + 1), init `0x00`, no final XOR.
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
    -6.0 + 125.0 * code as f32 / 65536.0
}

fn to_temperature(code: u16) -> f32 {
    -46.85 + 175.72 * code as f32 / 65536.0
}

/// HTU21D-F minimal driver — one temperature and one humidity measurement per `read()`.
///
/// Default configuration (power-on): 12-bit RH / 14-bit temperature, heater off, no-hold-master
/// measurement, CRC verified (one retry on a mismatch).
pub struct HTU21DFMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    hold: bool,
}

impl<I2C: I2c> HTU21DFMinimal<I2C> {
    /// Create a new `HTU21DFMinimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x40`, fixed).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, hold: false }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Wait 15 ms for power-up, soft reset (`0xFE`) and wait 15 ms.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        delay.delay_ms(15);
        self.command(CMD_RESET)?;
        delay.delay_ms(15);
        Ok(())
    }

    fn command(&mut self, code: u8) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[code])?;
        Ok(())
    }

    fn measure_once<D: DelayNs>(&mut self, hold_cmd: u8, poll_cmd: u8, delay: &mut D) -> Result<[u8; 3], Error<I2C::Error>> {
        let mut buf = [0u8; 3];
        if self.hold {
            self.command(hold_cmd)?;
            self.i2c.read(self.addr, &mut buf)?;
            return Ok(buf);
        }
        self.command(poll_cmd)?;
        for _ in 0..POLL_ATTEMPTS {
            if self.i2c.read(self.addr, &mut buf).is_ok() {
                return Ok(buf);
            }
            delay.delay_ms(1); // read header NACKed: not done yet
        }
        Err(Error::Timeout)
    }

    /// Run one measurement command and return the CRC-verified raw 16-bit code (status bits cleared).
    fn measure<D: DelayNs>(&mut self, hold_cmd: u8, poll_cmd: u8, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        for _ in 0..2 {
            let buf = self.measure_once(hold_cmd, poll_cmd, delay)?;
            if crc8(&buf[0..2]) == buf[2] {
                return Ok((((buf[0] as u16) << 8) | buf[1] as u16) & 0xFFFC);
            }
        }
        Err(Error::Crc)
    }

    /// Measure temperature and humidity.
    ///
    /// Returns `(temperature_c, humidity_pct)` in degrees Celsius and %RH (not clamped).
    /// [`Error::Crc`] if a result fails its CRC twice, [`Error::Timeout`] if the chip does not
    /// answer within 100 ms.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let t = self.measure(CMD_T_HOLD, CMD_T_POLL, delay)?;
        let rh = self.measure(CMD_RH_HOLD, CMD_RH_POLL, delay)?;
        Ok((to_temperature(t), to_humidity(rh)))
    }
}

/// HTU21D-F full driver — resolution, heater, hold master, compensation and dew point.
///
/// Owns a [`HTU21DFMinimal`] and delegates `init` and `read`.
pub struct HTU21DFFull<I2C> {
    inner: HTU21DFMinimal<I2C>,
}

impl<I2C: I2c> HTU21DFFull<I2C> {
    /// Create a new `HTU21DFFull`; same arguments as [`HTU21DFMinimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: HTU21DFMinimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Wait 15 ms, soft reset and wait 15 ms; see [`HTU21DFMinimal::init`].
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Measure temperature and humidity; see [`HTU21DFMinimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Run one temperature measurement in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(to_temperature(self.inner.measure(CMD_T_HOLD, CMD_T_POLL, delay)?))
    }

    /// Run one humidity measurement in %RH (not clamped; the ASIC range is −6…118 %RH).
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(to_humidity(self.inner.measure(CMD_RH_HOLD, CMD_RH_POLL, delay)?))
    }

    /// Measure and correct the humidity for temperature (−0.15 %RH/°C relative to 25 °C).
    pub fn read_humidity_compensated<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (t, rh) = self.inner.read(delay)?;
        Ok(rh + (25.0 - t) * (-0.15))
    }

    /// Measure and return the raw `(temperature, humidity)` codes (16 bit, status bits cleared).
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        let t = self.inner.measure(CMD_T_HOLD, CMD_T_POLL, delay)?;
        let rh = self.inner.measure(CMD_RH_HOLD, CMD_RH_POLL, delay)?;
        Ok((t, rh))
    }

    /// Read the user register (command `0xE7`); `0x02` after reset.
    pub fn read_user_register(&mut self) -> Result<u8, Error<I2C::Error>> {
        let mut buf = [0u8; 1];
        self.inner.i2c.write_read(self.inner.addr, &[CMD_READ_USER], &mut buf)?;
        Ok(buf[0])
    }

    fn write_user(&mut self, value: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[CMD_WRITE_USER, value])?;
        Ok(())
    }

    /// Select the resolution (read-modify-write of the `RES` bits 7 and 0).
    ///
    /// `res`: 0 = 12-bit RH / 14-bit T, 1 = 8 / 12, 2 = 10 / 13, 3 = 11 / 11;
    /// [`Error::InvalidArgument`] otherwise.
    pub fn set_resolution(&mut self, res: u8) -> Result<(), Error<I2C::Error>> {
        if res > 3 {
            return Err(Error::InvalidArgument);
        }
        let bits = ((res >> 1) << 7) | (res & 1);
        let reg = self.read_user_register()?;
        self.write_user((reg & !USER_RES_MASK) | bits)
    }

    /// Return the resolution setting (0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11).
    pub fn get_resolution(&mut self) -> Result<u8, Error<I2C::Error>> {
        let reg = self.read_user_register()?;
        Ok((((reg >> 7) & 1) << 1) | (reg & 1))
    }

    /// Switch the on-chip heater on or off (read-modify-write).
    pub fn set_heater(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        let reg = self.read_user_register()?;
        self.write_user(if on { reg | USER_HEATER } else { reg & !USER_HEATER })
    }

    /// `true` if the on-chip heater is enabled.
    pub fn heater(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.read_user_register()? & USER_HEATER != 0)
    }

    /// `true` if the end-of-battery flag is set (VDD below about 2.25 V).
    pub fn battery_low(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.read_user_register()? & USER_VDD_LOW != 0)
    }

    /// Soft reset (`0xFE`) and wait 15 ms; the heater bit survives.
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(CMD_RESET)?;
        delay.delay_ms(15);
        Ok(())
    }

    /// Use the hold-master commands (`0xE3` / `0xE5`) instead of polling; the bus layer must
    /// support clock stretching.
    pub fn set_hold_master(&mut self, on: bool) {
        self.inner.hold = on;
    }

    /// Run one measurement and compute the dew point in °C.
    pub fn dew_point<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (t, mut rh) = self.inner.read(delay)?;
        if rh <= 0.0 {
            rh = 0.01;
        }
        // Td = -B / (log10(RH/100) - B/(T+C)) - C  (the datasheet formula with PP substituted)
        let log10_rh = ln_f32(rh / 100.0) / core::f32::consts::LN_10;
        Ok(-1762.39 / (log10_rh - 1762.39 / (t + 235.66)) - 235.66)
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

    const ADDR: u8 = 0x40;

    fn cmd(code: u8) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![code])
    }

    fn frame(code: u16) -> Vec<u8> {
        let d = code.to_be_bytes();
        vec![d[0], d[1], crc8(&d)]
    }

    fn nack() -> I2cTransaction {
        I2cTransaction::read(ADDR, vec![0; 3]).with_error(ErrorKind::Other)
    }

    fn rh_code(rh: f32) -> u16 {
        ((((rh + 6.0) * 65536.0 / 125.0).round() as u32 & 0xFFFC) | 0x02) as u16
    }

    fn t_code(t: f32) -> u16 {
        (((t + 46.85) * 65536.0 / 175.72).round() as u32 & 0xFFFC) as u16
    }

    fn read_user(v: u8) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![0xE7], vec![v])
    }

    fn write_user(v: u8) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![0xE6, v])
    }

    fn close(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() <= tol
    }

    fn measurement(t: f32, rh: f32) -> Vec<I2cTransaction> {
        vec![
            cmd(0xF3),
            I2cTransaction::read(ADDR, frame(t_code(t))),
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(rh_code(rh))),
        ]
    }

    #[test]
    fn crc_reference() {
        assert_eq!(crc8(&[0x68, 0x3A]), 0x7C);
        assert_eq!(crc8(&[0x4E, 0x85]), 0x6B);
        assert_eq!(crc8(&[0xDC]), 0x79);
    }

    #[test]
    fn init_soft_resets() {
        let t = vec![cmd(0xFE)];
        let mut s = HTU21DFMinimal::new(I2cMock::new(&t), ADDR);
        s.init(&mut NoopDelay).unwrap();
        s.i2c.done();
    }

    #[test]
    fn read_temperature_then_humidity() {
        let mut s = HTU21DFMinimal::new(I2cMock::new(&measurement(25.0, 50.0)), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 50.0, 0.05) && close(temp, 25.0, 0.05));
        s.i2c.done();
    }

    #[test]
    fn datasheet_conversion() {
        let t = vec![
            cmd(0xF3),
            I2cTransaction::read(ADDR, frame(0x683A)),
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(0x683A)),
        ];
        let mut s = HTU21DFMinimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, -46.85 + 175.72 * 0x6838 as f32 / 65536.0, 1e-3));
        assert!(close(rh, -6.0 + 125.0 * 0x6838 as f32 / 65536.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn read_polls_while_nacked() {
        let t = vec![
            cmd(0xF3),
            nack(),
            nack(),
            I2cTransaction::read(ADDR, frame(t_code(20.0))),
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(rh_code(40.0))),
        ];
        let mut s = HTU21DFMinimal::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read(&mut NoopDelay).unwrap().1, 40.0, 0.05));
        s.i2c.done();
    }

    #[test]
    fn timeout_when_never_ready() {
        let mut t = vec![cmd(0xF3)];
        for _ in 0..100 {
            t.push(nack());
        }
        let mut s = HTU21DFMinimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Timeout));
        s.i2c.done();
    }

    #[test]
    fn crc_mismatch_twice_fails() {
        let mut bad = frame(0x6000);
        bad[2] ^= 0xFF;
        let t = vec![
            cmd(0xF3),
            I2cTransaction::read(ADDR, bad.clone()),
            cmd(0xF3),
            I2cTransaction::read(ADDR, bad),
        ];
        let mut s = HTU21DFMinimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Crc));
        s.i2c.done();
    }

    #[test]
    fn crc_retry_recovers() {
        let mut bad = frame(0x6000);
        bad[2] ^= 0xFF;
        let mut t = vec![cmd(0xF3), I2cTransaction::read(ADDR, bad), cmd(0xF3), I2cTransaction::read(ADDR, frame(t_code(25.0)))];
        t.push(cmd(0xF5));
        t.push(I2cTransaction::read(ADDR, frame(rh_code(50.0))));
        let mut s = HTU21DFMinimal::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read(&mut NoopDelay).unwrap().0, 25.0, 0.05));
        s.i2c.done();
    }

    #[test]
    fn humidity_is_not_clamped() {
        let t = vec![
            cmd(0xF3),
            I2cTransaction::read(ADDR, frame(0)),
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(0)),
        ];
        let mut s = HTU21DFMinimal::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read(&mut NoopDelay).unwrap().1, -6.0, 1e-6));
        s.i2c.done();
    }

    #[test]
    fn single_values_and_raw() {
        let t = vec![
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(rh_code(30.0))),
            cmd(0xF3),
            I2cTransaction::read(ADDR, frame(t_code(-10.0))),
            cmd(0xF3),
            I2cTransaction::read(ADDR, frame(0x1237)),
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(0x4321)),
        ];
        let mut s = HTU21DFFull::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 30.0, 0.05));
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), -10.0, 0.05));
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), (0x1234, 0x4320));
        s.inner.i2c.done();
    }

    #[test]
    fn compensated_humidity() {
        let mut s = HTU21DFFull::new(I2cMock::new(&measurement(35.0, 50.0)), ADDR);
        assert!(close(s.read_humidity_compensated(&mut NoopDelay).unwrap(), 51.5, 0.1));
        s.inner.i2c.done();
    }

    #[test]
    fn hold_master_commands() {
        let t = vec![
            cmd(0xE3),
            I2cTransaction::read(ADDR, frame(t_code(25.0))),
            cmd(0xE5),
            I2cTransaction::read(ADDR, frame(rh_code(50.0))),
        ];
        let mut s = HTU21DFFull::new(I2cMock::new(&t), ADDR);
        s.set_hold_master(true);
        s.read(&mut NoopDelay).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn set_resolution_writes_bits() {
        let t = vec![
            read_user(0x02), write_user(0x83), // 11/11
            read_user(0x02), write_user(0x03), // 8/12
            read_user(0x02), write_user(0x82), // 10/13
        ];
        let mut s = HTU21DFFull::new(I2cMock::new(&t), ADDR);
        s.set_resolution(3).unwrap();
        s.set_resolution(1).unwrap();
        s.set_resolution(2).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn set_resolution_invalid() {
        let mut s = HTU21DFFull::new(I2cMock::new(&[]), ADDR);
        assert_eq!(s.set_resolution(4), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn get_resolution_decodes() {
        let t = vec![read_user(0x83), read_user(0x03), read_user(0x82), read_user(0x02)];
        let mut s = HTU21DFFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.get_resolution().unwrap(), 3);
        assert_eq!(s.get_resolution().unwrap(), 1);
        assert_eq!(s.get_resolution().unwrap(), 2);
        assert_eq!(s.get_resolution().unwrap(), 0);
        s.inner.i2c.done();
    }

    #[test]
    fn heater_battery_and_user_register() {
        let t = vec![
            read_user(0x02), write_user(0x06), // heater on
            read_user(0x06), write_user(0x02), // heater off
            read_user(0x06),
            read_user(0x02),
            read_user(0x42),
            read_user(0x42),
        ];
        let mut s = HTU21DFFull::new(I2cMock::new(&t), ADDR);
        s.set_heater(true).unwrap();
        s.set_heater(false).unwrap();
        assert!(s.heater().unwrap());
        assert!(!s.battery_low().unwrap());
        assert!(s.battery_low().unwrap());
        assert_eq!(s.read_user_register().unwrap(), 0x42);
        s.inner.i2c.done();
    }

    #[test]
    fn soft_reset_command() {
        let t = vec![cmd(0xFE)];
        let mut s = HTU21DFFull::new(I2cMock::new(&t), ADDR);
        s.soft_reset(&mut NoopDelay).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn dew_point_25c_50pct() {
        let mut s = HTU21DFFull::new(I2cMock::new(&measurement(25.0, 50.0)), ADDR);
        assert!(close(s.dew_point(&mut NoopDelay).unwrap(), 13.86, 0.1));
        s.inner.i2c.done();
    }
}
