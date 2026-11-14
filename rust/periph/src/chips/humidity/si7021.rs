//! Si7021 — relative humidity and temperature sensor (Silicon Labs).
//!
//! Communicates over I²C (up to 400 kHz) at the fixed address `0x40`. The chip has no register
//! pointer: single-byte commands start a measurement, read or write the one user register, read
//! the electronic ID or reset the chip. A humidity result is three bytes (MSB, LSB, CRC-8 with
//! polynomial `0x31` and init `0x00`); the temperature of the same conversion is fetched with
//! `0xE0` without a new conversion.
//!
//! Measurements use no-hold-master mode by default: the driver sends `0xF5` and polls the read
//! header (NACKed until the result is ready, 50 attempts 1 ms apart). Hold master (clock
//! stretching) can be enabled on [`Si7021Full`]. Construction does no bus access.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_RH_HOLD: u8 = 0xE5;
const CMD_RH_POLL: u8 = 0xF5;
const CMD_T_HOLD: u8 = 0xE3;
const CMD_T_POLL: u8 = 0xF3;
const CMD_T_PREVIOUS: u8 = 0xE0;
const CMD_RESET: u8 = 0xFE;
const CMD_WRITE_USER: u8 = 0xE6;
const CMD_READ_USER: u8 = 0xE7;
const POLL_ATTEMPTS: u32 = 50;

const USER_RESERVED: u8 = 0x3A;
const USER_VDDS: u8 = 0x40;
const USER_HTRE: u8 = 0x04;
const USER_RES_MASK: u8 = 0x81;

/// (rh_bits, temperature_bits) indexed by RES1:RES0.
const RESOLUTIONS: [(u8, u8); 4] = [(12, 14), (8, 12), (10, 13), (11, 11)];
/// Typical conversion times in seconds, indexed by RES1:RES0.
const RH_TIME: [f32; 4] = [0.010, 0.0026, 0.0037, 0.0058];
const T_TIME: [f32; 4] = [0.007, 0.0024, 0.004, 0.0015];

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// A CRC-8 check failed.
    Crc,
    /// The result was not ready within the polling window (50 ms).
    Timeout,
    /// Unsupported resolution pair.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// CRC-8 used by the Si7021: polynomial `0x31`, init `0x00`, no reflection, no final XOR.
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
    let rh = 125.0 * code as f32 / 65536.0 - 6.0;
    if rh < 0.0 {
        0.0
    } else if rh > 100.0 {
        100.0
    } else {
        rh
    }
}

fn to_temperature(code: u16) -> f32 {
    175.72 * code as f32 / 65536.0 - 46.85
}

/// Si7021 minimal driver — one relative-humidity measurement plus the temperature of the same
/// conversion per `read()`.
///
/// Default configuration (power-on, nothing is written at construction): 12-bit RH / 14-bit
/// temperature, heater off, no-hold-master measurement (`0xF5`), CRC verified on the RH result,
/// relative humidity clamped to 0–100 %RH.
pub struct Si7021Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    stretch: bool,
}

impl<I2C: I2c> Si7021Minimal<I2C> {
    /// Create a new `Si7021Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x40`, fixed).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, stretch: false }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn command(&mut self, code: u8) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[code])?;
        Ok(())
    }

    /// Run one measurement command and return the CRC-verified raw 16-bit code.
    fn measure<D: DelayNs>(&mut self, hold_cmd: u8, poll_cmd: u8, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        let mut buf = [0u8; 3];
        if self.stretch {
            self.command(hold_cmd)?;
            self.i2c.read(self.addr, &mut buf)?;
        } else {
            self.command(poll_cmd)?;
            let mut got = false;
            for _ in 0..POLL_ATTEMPTS {
                if self.i2c.read(self.addr, &mut buf).is_ok() {
                    got = true;
                    break;
                }
                delay.delay_ms(1); // read header NACKed: not ready yet
            }
            if !got {
                return Err(Error::Timeout);
            }
        }
        if crc8(&buf[0..2]) != buf[2] {
            return Err(Error::Crc);
        }
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    fn previous_code(&mut self) -> Result<u16, Error<I2C::Error>> {
        self.command(CMD_T_PREVIOUS)?;
        let mut buf = [0u8; 2];
        self.i2c.read(self.addr, &mut buf)?;
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    /// Run one RH measurement and read the temperature of the same conversion.
    ///
    /// Returns `(temperature_c, humidity_pct)` in degrees Celsius and %RH (0–100).
    /// [`Error::Crc`] if the RH result fails its CRC, [`Error::Timeout`] if the chip does not
    /// answer within 50 ms.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let rh = self.measure(CMD_RH_HOLD, CMD_RH_POLL, delay)?;
        let t = self.previous_code()?;
        Ok((to_temperature(t), to_humidity(rh)))
    }
}

/// Si7021 full driver — resolution, heater, hold master, ID and dew point.
///
/// Owns a [`Si7021Minimal`] and delegates `read`.
pub struct Si7021Full<I2C> {
    inner: Si7021Minimal<I2C>,
}

impl<I2C: I2c> Si7021Full<I2C> {
    /// Create a new `Si7021Full`; same arguments as [`Si7021Minimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Si7021Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Run one RH measurement plus the temperature of the same conversion; see [`Si7021Minimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Run one RH measurement and return the raw 16-bit code (status bits included).
    pub fn read_raw_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.inner.measure(CMD_RH_HOLD, CMD_RH_POLL, delay)
    }

    /// Run one temperature measurement and return the raw 16-bit code.
    pub fn read_raw_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.inner.measure(CMD_T_HOLD, CMD_T_POLL, delay)
    }

    /// Run one RH measurement and return the relative humidity in %RH (0–100).
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(to_humidity(self.read_raw_humidity(delay)?))
    }

    /// Run a fresh temperature measurement (`0xF3`, or `0xE3` with clock stretching) in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(to_temperature(self.read_raw_temperature(delay)?))
    }

    /// Read the temperature of the last RH measurement (`0xE0`, no new conversion, no CRC) in °C.
    pub fn read_temperature_previous(&mut self) -> Result<f32, Error<I2C::Error>> {
        Ok(to_temperature(self.inner.previous_code()?))
    }

    /// Read USER_REG_1 (command `0xE7`); `0x3A` after reset.
    pub fn read_user_register(&mut self) -> Result<u8, Error<I2C::Error>> {
        let mut buf = [0u8; 1];
        self.inner.i2c.write_read(self.inner.addr, &[CMD_READ_USER], &mut buf)?;
        Ok(buf[0])
    }

    /// Write USER_REG_1 (command `0xE6`); the reserved bits and VDDS keep their current value.
    pub fn write_user_register(&mut self, value: u8) -> Result<(), Error<I2C::Error>> {
        let keep = USER_RESERVED | USER_VDDS;
        let merged = (self.read_user_register()? & keep) | (value & !keep);
        self.inner.i2c.write(self.inner.addr, &[CMD_WRITE_USER, merged])?;
        Ok(())
    }

    /// Select the measurement resolution (read-modify-write of `RES1:RES0`).
    ///
    /// Allowed `(rh_bits, temperature_bits)` pairs: (12, 14), (8, 12), (10, 13), (11, 11);
    /// [`Error::InvalidArgument`] otherwise.
    pub fn set_resolution(&mut self, rh_bits: u8, temperature_bits: u8) -> Result<(), Error<I2C::Error>> {
        let res = RESOLUTIONS
            .iter()
            .position(|&(r, t)| r == rh_bits && t == temperature_bits)
            .ok_or(Error::InvalidArgument)? as u8;
        let bits = ((res >> 1) << 7) | (res & 1);
        let reg = self.read_user_register()?;
        self.write_user_register((reg & !USER_RES_MASK) | bits)
    }

    /// Return the configured resolution as `(rh_bits, temperature_bits)`.
    pub fn get_resolution(&mut self) -> Result<(u8, u8), Error<I2C::Error>> {
        let reg = self.read_user_register()?;
        Ok(RESOLUTIONS[((((reg >> 7) & 1) << 1) | (reg & 1)) as usize])
    }

    /// Switch the on-chip heater (`HTRE`, about 3.1 mA) on or off.
    pub fn set_heater(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        let reg = self.read_user_register()?;
        self.write_user_register(if on { reg | USER_HTRE } else { reg & !USER_HTRE })
    }

    /// `true` if the on-chip heater is enabled.
    pub fn is_heater_on(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.read_user_register()? & USER_HTRE != 0)
    }

    /// `true` if the supply voltage is low (`VDDS` set, VDD below about 1.9 V).
    pub fn vdd_low(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.read_user_register()? & USER_VDDS != 0)
    }

    /// Use the hold-master commands (`0xE5` / `0xE3`) instead of polling; the bus layer must
    /// support clock stretching.
    pub fn set_clock_stretching(&mut self, on: bool) {
        self.inner.stretch = on;
    }

    /// Software reset (`0xFE`) and wait 15 ms; the user register returns to `0x3A`.
    pub fn software_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(CMD_RESET)?;
        delay.delay_ms(15);
        Ok(())
    }

    /// Read the 64-bit electronic serial number (SNA_3..SNA_0, SNB_3..SNB_0).
    pub fn serial_number(&mut self) -> Result<u64, Error<I2C::Error>> {
        let mut a = [0u8; 8];
        let mut b = [0u8; 6];
        self.inner.i2c.write_read(self.inner.addr, &[0xFA, 0x0F], &mut a)?;
        self.inner.i2c.write_read(self.inner.addr, &[0xFC, 0xC9], &mut b)?;
        let parts = [a[0], a[2], a[4], a[6], b[0], b[1], b[3], b[4]];
        Ok(u64::from_be_bytes(parts))
    }

    /// Read SNB_3, the device type byte (`0x15` = Si7021, `0x14` = Si7020, `0x0D` = Si7013).
    pub fn device_type(&mut self) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 6];
        self.inner.i2c.write_read(self.inner.addr, &[0xFC, 0xC9], &mut b)?;
        Ok(b[0])
    }

    /// Read the firmware revision (command `0x84 0xB8`); `0xFF` = firmware 1.0.
    pub fn firmware_revision(&mut self) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        self.inner.i2c.write_read(self.inner.addr, &[0x84, 0xB8], &mut b)?;
        Ok(b[0])
    }

    /// Typical RH + temperature conversion time at the current resolution, in seconds.
    pub fn conversion_time(&mut self) -> Result<f32, Error<I2C::Error>> {
        let reg = self.read_user_register()?;
        let res = ((((reg >> 7) & 1) << 1) | (reg & 1)) as usize;
        Ok(RH_TIME[res] + T_TIME[res])
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
        ((rh + 6.0) * 65536.0 / 125.0).round() as u16
    }

    fn t_code(t: f32) -> u16 {
        ((t + 46.85) * 65536.0 / 175.72).round() as u16
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

    #[test]
    fn crc_reference() {
        assert_eq!(crc8(&[0x00, 0x00]), 0x00);
        assert_eq!(crc8(&[0x01]), 0x31);
    }

    #[test]
    fn read_humidity_then_previous_temperature() {
        let t = vec![
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(rh_code(50.0))),
            cmd(0xE0),
            I2cTransaction::read(ADDR, t_code(25.0).to_be_bytes().to_vec()),
        ];
        let mut s = Si7021Minimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 50.0, 0.02) && close(temp, 25.0, 0.02));
        s.i2c.done();
    }

    #[test]
    fn read_polls_while_nacked() {
        let t = vec![
            cmd(0xF5),
            nack(),
            nack(),
            I2cTransaction::read(ADDR, frame(rh_code(40.0))),
            cmd(0xE0),
            I2cTransaction::read(ADDR, vec![0x66, 0x66]),
        ];
        let mut s = Si7021Minimal::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read(&mut NoopDelay).unwrap().1, 40.0, 0.02));
        s.i2c.done();
    }

    #[test]
    fn timeout_when_never_ready() {
        let mut t = vec![cmd(0xF5)];
        for _ in 0..50 {
            t.push(nack());
        }
        let mut s = Si7021Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Timeout));
        s.i2c.done();
    }

    #[test]
    fn crc_mismatch() {
        let mut bad = frame(0x6000);
        bad[2] ^= 0xFF;
        let t = vec![cmd(0xF5), I2cTransaction::read(ADDR, bad)];
        let mut s = Si7021Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Crc));
        s.i2c.done();
    }

    #[test]
    fn humidity_is_clamped() {
        let t = vec![
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(0x0000)),
            cmd(0xE0),
            I2cTransaction::read(ADDR, vec![0, 0]),
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(0xFFFC)),
            cmd(0xE0),
            I2cTransaction::read(ADDR, vec![0, 0]),
        ];
        let mut s = Si7021Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay).unwrap().1, 0.0);
        assert_eq!(s.read(&mut NoopDelay).unwrap().1, 100.0);
        s.i2c.done();
    }

    #[test]
    fn single_values_and_raw() {
        let t = vec![
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(rh_code(30.0))),
            cmd(0xF3),
            I2cTransaction::read(ADDR, frame(t_code(-10.0))),
            cmd(0xE0),
            I2cTransaction::read(ADDR, t_code(20.0).to_be_bytes().to_vec()),
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(0x1234)),
            cmd(0xF3),
            I2cTransaction::read(ADDR, frame(0x4321)),
        ];
        let mut s = Si7021Full::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 30.0, 0.02));
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), -10.0, 0.02));
        assert!(close(s.read_temperature_previous().unwrap(), 20.0, 0.02));
        assert_eq!(s.read_raw_humidity(&mut NoopDelay).unwrap(), 0x1234);
        assert_eq!(s.read_raw_temperature(&mut NoopDelay).unwrap(), 0x4321);
        s.inner.i2c.done();
    }

    #[test]
    fn hold_master_commands() {
        let t = vec![
            cmd(0xE5),
            I2cTransaction::read(ADDR, frame(rh_code(50.0))),
            cmd(0xE0),
            I2cTransaction::read(ADDR, vec![0x66, 0x66]),
            cmd(0xE3),
            I2cTransaction::read(ADDR, frame(0x6666)),
        ];
        let mut s = Si7021Full::new(I2cMock::new(&t), ADDR);
        s.set_clock_stretching(true);
        s.read(&mut NoopDelay).unwrap();
        s.read_temperature(&mut NoopDelay).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn set_resolution_writes_bits() {
        let t = vec![
            read_user(0x3A), read_user(0x3A), write_user(0xBB), // 11/11
            read_user(0x3A), read_user(0x3A), write_user(0x3B), // 8/12
            read_user(0x3A), read_user(0x3A), write_user(0xBA), // 10/13
        ];
        let mut s = Si7021Full::new(I2cMock::new(&t), ADDR);
        s.set_resolution(11, 11).unwrap();
        s.set_resolution(8, 12).unwrap();
        s.set_resolution(10, 13).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn set_resolution_invalid() {
        let mut s = Si7021Full::new(I2cMock::new(&[]), ADDR);
        assert_eq!(s.set_resolution(12, 12), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn get_resolution_decodes() {
        let t = vec![read_user(0xBB), read_user(0x3B), read_user(0xBA), read_user(0x3A)];
        let mut s = Si7021Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.get_resolution().unwrap(), (11, 11));
        assert_eq!(s.get_resolution().unwrap(), (8, 12));
        assert_eq!(s.get_resolution().unwrap(), (10, 13));
        assert_eq!(s.get_resolution().unwrap(), (12, 14));
        s.inner.i2c.done();
    }

    #[test]
    fn heater_and_vdd() {
        let t = vec![
            read_user(0x3A), read_user(0x3A), write_user(0x3E), // heater on
            read_user(0x3E), read_user(0x3E), write_user(0x3A), // heater off
            read_user(0x3E),
            read_user(0x3A),
            read_user(0x7A),
        ];
        let mut s = Si7021Full::new(I2cMock::new(&t), ADDR);
        s.set_heater(true).unwrap();
        s.set_heater(false).unwrap();
        assert!(s.is_heater_on().unwrap());
        assert!(!s.vdd_low().unwrap());
        assert!(s.vdd_low().unwrap());
        s.inner.i2c.done();
    }

    #[test]
    fn write_user_register_keeps_reserved() {
        let t = vec![read_user(0x3A), write_user(0x3A)];
        let mut s = Si7021Full::new(I2cMock::new(&t), ADDR);
        s.write_user_register(0x00).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn conversion_time_default() {
        let t = vec![read_user(0x3A)];
        let mut s = Si7021Full::new(I2cMock::new(&t), ADDR);
        assert!(close(s.conversion_time().unwrap(), 0.017, 1e-6));
        s.inner.i2c.done();
    }

    #[test]
    fn reset_and_identification() {
        let t = vec![
            cmd(0xFE),
            I2cTransaction::write_read(ADDR, vec![0xFA, 0x0F], vec![0x11, 0, 0x22, 0, 0x33, 0, 0x44, 0]),
            I2cTransaction::write_read(ADDR, vec![0xFC, 0xC9], vec![0x15, 0x66, 0, 0x77, 0x88, 0]),
            I2cTransaction::write_read(ADDR, vec![0xFC, 0xC9], vec![0x15, 0x66, 0, 0x77, 0x88, 0]),
            I2cTransaction::write_read(ADDR, vec![0x84, 0xB8], vec![0xFF]),
        ];
        let mut s = Si7021Full::new(I2cMock::new(&t), ADDR);
        s.software_reset(&mut NoopDelay).unwrap();
        assert_eq!(s.serial_number().unwrap(), 0x1122334415667788);
        assert_eq!(s.device_type().unwrap(), 0x15);
        assert_eq!(s.firmware_revision().unwrap(), 0xFF);
        s.inner.i2c.done();
    }

    #[test]
    fn dew_point_25c_50pct() {
        let t = vec![
            cmd(0xF5),
            I2cTransaction::read(ADDR, frame(rh_code(50.0))),
            cmd(0xE0),
            I2cTransaction::read(ADDR, t_code(25.0).to_be_bytes().to_vec()),
        ];
        let mut s = Si7021Full::new(I2cMock::new(&t), ADDR);
        assert!(close(s.dew_point(&mut NoopDelay).unwrap(), 13.86, 0.05));
        s.inner.i2c.done();
    }
}
