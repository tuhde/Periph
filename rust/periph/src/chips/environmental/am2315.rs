//! AM2315 — humidity and temperature sensor probe (Aosong).
//!
//! Communicates over standard-mode I²C (at most 100 kHz) at the fixed address `0x5C` with a
//! Modbus-style protocol: function `0x03` reads registers, `0x10` writes them, and every response
//! carries a CRC-16 (init `0xFFFF`, polynomial `0xA001`, low byte first). The sensor sleeps
//! between transactions: each transaction starts with a wake-up write that the sensor does not
//! acknowledge (the error is ignored), then the command, a 2 ms wait and the read. The sensor
//! measures after each communication and returns that result on the next one, so a read returns
//! the **previous** measurement.
//!
//! The sensor needs at least 2 s between transactions. Without a clock the driver cannot measure
//! elapsed time, so it delays [`DEFAULT_MIN_INTERVAL_MS`] before **every** transaction (the first
//! one doubles as the power-up settling time); change this with `set_min_interval_ms` if the
//! caller guarantees the spacing itself. Construction does no bus access.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const FUNC_READ: u8 = 0x03;
const FUNC_WRITE: u8 = 0x10;

/// Delay before every transaction in milliseconds (the sensor's minimum interval and power-up settling time).
pub const DEFAULT_MIN_INTERVAL_MS: u32 = 2000;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// A CRC-16 check, function code or byte count failed twice.
    Crc,
    /// The sensor answered with an error response; the code is `0x80`-`0x84`.
    Sensor(u8),
    /// Argument outside the permitted range.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// CRC-16 (Modbus) used by the AM2315: init `0xFFFF`, polynomial `0xA001` (reflected).
pub fn crc16(data: &[u8]) -> u16 {
    let mut crc = 0xFFFFu16;
    for &byte in data {
        crc ^= byte as u16;
        for _ in 0..8 {
            crc = if crc & 1 != 0 { (crc >> 1) ^ 0xA001 } else { crc >> 1 };
        }
    }
    crc
}

fn to_humidity(raw: u16) -> f32 {
    raw as f32 / 10.0
}

fn to_temperature(raw: u16) -> f32 {
    let t = (raw & 0x7FFF) as f32 / 10.0;
    if raw & 0x8000 != 0 { -t } else { t }
}

/// AM2315 minimal driver — temperature and humidity in one `read()`.
///
/// Default configuration: constructor does no bus access; a delay of
/// [`DEFAULT_MIN_INTERVAL_MS`] before every transaction; one retry after a new wake on a CRC
/// mismatch or bus error.
pub struct AM2315Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    min_interval_ms: u32,
}

impl<I2C: I2c> AM2315Minimal<I2C> {
    /// Create a new `AM2315Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus (standard mode) implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x5C`, fixed).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, min_interval_ms: DEFAULT_MIN_INTERVAL_MS }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Change the delay before every transaction (default [`DEFAULT_MIN_INTERVAL_MS`]); `0` disables it.
    pub fn set_min_interval_ms(&mut self, ms: u32) {
        self.min_interval_ms = ms;
    }

    fn wake<D: DelayNs>(&mut self, delay: &mut D) {
        let _ = self.i2c.write(self.addr, &[0x00]); // the sleeping sensor NACKs this
        delay.delay_ms(1);
    }

    fn exchange<D: DelayNs>(&mut self, frame: &[u8], resp: &mut [u8], delay: &mut D) -> Result<(), Error<I2C::Error>> {
        if self.min_interval_ms > 0 {
            delay.delay_ms(self.min_interval_ms);
        }
        self.wake(delay);
        self.i2c.write(self.addr, frame)?;
        delay.delay_ms(2);
        self.i2c.read(self.addr, resp)?;
        Ok(())
    }

    fn check(resp: &[u8], function: u8) -> Result<(), Error<I2C::Error>> {
        if resp[0] & 0x80 != 0 {
            return Err(Error::Sensor(resp[0]));
        }
        let n = resp.len();
        let crc = resp[n - 2] as u16 | ((resp[n - 1] as u16) << 8);
        if resp[0] != function || crc16(&resp[..n - 2]) != crc {
            return Err(Error::Crc);
        }
        Ok(())
    }

    /// Read `count` (1-10) registers from `start`; fills `out[..count]`. One retry on a bus or CRC error.
    fn read_registers_raw<D: DelayNs>(&mut self, start: u8, count: u8, out: &mut [u8], delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let frame = [FUNC_READ, start, count];
        let len = count as usize + 4;
        let mut buf = [0u8; 14];
        let mut last = Error::Crc;
        for _ in 0..2 {
            match self.exchange(&frame, &mut buf[..len], delay) {
                Ok(()) => match Self::check(&buf[..len], FUNC_READ) {
                    Ok(()) if buf[1] == count => {
                        out[..count as usize].copy_from_slice(&buf[2..2 + count as usize]);
                        return Ok(());
                    }
                    Ok(()) => last = Error::Crc,
                    Err(Error::Sensor(c)) => return Err(Error::Sensor(c)),
                    Err(e) => last = e,
                },
                Err(e) => last = e,
            }
        }
        Err(last)
    }

    /// Read temperature and humidity (registers `0x00`-`0x03`).
    ///
    /// Returns `(temperature_c, humidity_pct)` — the **previous** measurement. [`Error::Crc`] if the
    /// CRC fails twice, [`Error::Sensor`] on an error response.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let mut d = [0u8; 4];
        self.read_registers_raw(0x00, 4, &mut d, delay)?;
        Ok((
            to_temperature(((d[2] as u16) << 8) | d[3] as u16),
            to_humidity(((d[0] as u16) << 8) | d[1] as u16),
        ))
    }
}

/// AM2315 full driver — single values, raw words, fresh read, registers, user registers.
///
/// Owns an [`AM2315Minimal`] and delegates `read`.
pub struct AM2315Full<I2C> {
    inner: AM2315Minimal<I2C>,
}

impl<I2C: I2c> AM2315Full<I2C> {
    /// Create a new `AM2315Full`; same arguments as [`AM2315Minimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: AM2315Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Change the delay before every transaction; see [`AM2315Minimal::set_min_interval_ms`].
    pub fn set_min_interval_ms(&mut self, ms: u32) {
        self.inner.set_min_interval_ms(ms);
    }

    /// Read temperature and humidity; see [`AM2315Minimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Read the temperature only (registers `0x02`-`0x03`) in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let mut d = [0u8; 2];
        self.inner.read_registers_raw(0x02, 2, &mut d, delay)?;
        Ok(to_temperature(((d[0] as u16) << 8) | d[1] as u16))
    }

    /// Read the humidity only (registers `0x00`-`0x01`) in %RH.
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let mut d = [0u8; 2];
        self.inner.read_registers_raw(0x00, 2, &mut d, delay)?;
        Ok(to_humidity(((d[0] as u16) << 8) | d[1] as u16))
    }

    /// Read the raw words `(raw_humidity, raw_temperature)`; the temperature word is sign + magnitude.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        let mut d = [0u8; 4];
        self.inner.read_registers_raw(0x00, 4, &mut d, delay)?;
        Ok((((d[0] as u16) << 8) | d[1] as u16, ((d[2] as u16) << 8) | d[3] as u16))
    }

    /// Read twice (at least 2 s apart) and return the second, real-time `(temperature_c, humidity_pct)`.
    pub fn read_fresh<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)?;
        self.inner.read(delay)
    }

    /// Read `out.len()` (1-10) registers from `start` (`0x00`-`0x1F`), CRC verified.
    pub fn read_registers<D: DelayNs>(&mut self, start: u8, out: &mut [u8], delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let count = out.len();
        if count < 1 || count > 10 || start > 0x1F || start as usize + count > 0x20 {
            return Err(Error::InvalidArgument);
        }
        self.inner.read_registers_raw(start, count as u8, out, delay)
    }

    /// Write the user registers `0x10`-`0x13` (function `0x10` with CRC); `data` is 1-4 bytes.
    pub fn write_registers<D: DelayNs>(&mut self, start: u8, data: &[u8], delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let n = data.len();
        if n < 1 || n > 4 || start < 0x10 || start as usize + n > 0x14 {
            return Err(Error::InvalidArgument);
        }
        let mut frame = [0u8; 9];
        frame[0] = FUNC_WRITE;
        frame[1] = start;
        frame[2] = n as u8;
        frame[3..3 + n].copy_from_slice(data);
        let crc = crc16(&frame[..3 + n]);
        frame[3 + n] = (crc & 0xFF) as u8;
        frame[4 + n] = (crc >> 8) as u8;
        let mut last = Error::Crc;
        for _ in 0..2 {
            let mut resp = [0u8; 5];
            match self.inner.exchange(&frame[..5 + n], &mut resp, delay) {
                Ok(()) => match AM2315Minimal::<I2C>::check(&resp, FUNC_WRITE) {
                    Ok(()) if resp[1] == start && resp[2] == n as u8 => return Ok(()),
                    Ok(()) => last = Error::Crc,
                    Err(Error::Sensor(c)) => return Err(Error::Sensor(c)),
                    Err(e) => last = e,
                },
                Err(e) => last = e,
            }
        }
        Err(last)
    }

    /// Read the device information (registers `0x08`-`0x0E`): `(model, version, device_id)`.
    pub fn read_device_info<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u8, u32), Error<I2C::Error>> {
        let mut d = [0u8; 7];
        self.inner.read_registers_raw(0x08, 7, &mut d, delay)?;
        Ok((
            ((d[0] as u16) << 8) | d[1] as u16,
            d[2],
            u32::from_be_bytes([d[3], d[4], d[5], d[6]]),
        ))
    }

    /// Read the two 16-bit user registers (`0x10`-`0x13`).
    pub fn read_user_registers<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        let mut d = [0u8; 4];
        self.inner.read_registers_raw(0x10, 4, &mut d, delay)?;
        Ok((((d[0] as u16) << 8) | d[1] as u16, ((d[2] as u16) << 8) | d[3] as u16))
    }

    /// Write the two 16-bit user registers (`0x10`-`0x13`).
    pub fn write_user_registers<D: DelayNs>(&mut self, user1: u16, user2: u16, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let a = user1.to_be_bytes();
        let b = user2.to_be_bytes();
        self.write_registers(0x10, &[a[0], a[1], b[0], b[1]], delay)
    }

    /// Wake the sensor: a dummy write (the NACK is ignored) and a 1 ms wait.
    pub fn wake<D: DelayNs>(&mut self, delay: &mut D) {
        self.inner.wake(delay);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x5C;

    fn wake() -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![0x00])
    }

    fn cmd(d: &[u8]) -> I2cTransaction {
        I2cTransaction::write(ADDR, d.to_vec())
    }

    fn resp(body: &[u8]) -> Vec<u8> {
        let crc = crc16(body);
        let mut v = body.to_vec();
        v.push((crc & 0xFF) as u8);
        v.push((crc >> 8) as u8);
        v
    }

    fn read_resp(data: &[u8]) -> Vec<u8> {
        let mut body = vec![0x03, data.len() as u8];
        body.extend_from_slice(data);
        resp(&body)
    }

    /// wake + read command + read response
    fn txn(start: u8, data: &[u8]) -> Vec<I2cTransaction> {
        vec![
            wake(),
            cmd(&[0x03, start, data.len() as u8]),
            I2cTransaction::read(ADDR, read_resp(data)),
        ]
    }

    fn full(t: &[I2cTransaction]) -> AM2315Full<I2cMock> {
        let mut s = AM2315Full::new(I2cMock::new(t), ADDR);
        s.set_min_interval_ms(0);
        s
    }

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-4
    }

    #[test]
    fn crc_reference() {
        assert_eq!(crc16(&[0x03, 0x04, 0x01, 0xF4, 0x00, 0xFA]), 0xA531);
        assert_eq!(crc16(&[0x03, 0x04, 0x03, 0x39, 0x01, 0x15]), 0xFEE1);
        assert_eq!(crc16(&[0x10, 0x10, 0x02, 0x01, 0x02]), 0x92C0);
    }

    #[test]
    fn constructor_no_bus_access() {
        let mut s = full(&[]);
        s.inner.i2c.done();
    }

    #[test]
    fn read_decodes_both_values() {
        let mut s = full(&txn(0x00, &[0x01, 0xF4, 0x00, 0xFA]));
        let (t, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 50.0) && close(t, 25.0));
        s.inner.i2c.done();
    }

    #[test]
    fn datasheet_frame() {
        let t = vec![
            wake(),
            cmd(&[0x03, 0x00, 0x04]),
            I2cTransaction::read(ADDR, vec![0x03, 0x04, 0x03, 0x39, 0x01, 0x15, 0xE1, 0xFE]),
        ];
        let mut s = full(&t);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 82.5) && close(temp, 27.7));
        s.inner.i2c.done();
    }

    #[test]
    fn negative_temperature_is_sign_magnitude() {
        let mut s = full(&txn(0x00, &[0x01, 0xF4, 0x80, 0x65]));
        assert!(close(s.read(&mut NoopDelay).unwrap().0, -10.1));
        s.inner.i2c.done();
    }

    #[test]
    fn wake_nack_is_ignored() {
        let t = vec![
            wake().with_error(ErrorKind::Other),
            cmd(&[0x03, 0x00, 0x04]),
            I2cTransaction::read(ADDR, read_resp(&[0x01, 0xF4, 0x00, 0xFA])),
        ];
        let mut s = full(&t);
        assert!(close(s.read(&mut NoopDelay).unwrap().1, 50.0));
        s.inner.i2c.done();
    }

    #[test]
    fn crc_retry_recovers() {
        let mut bad = read_resp(&[0x01, 0xF4, 0x00, 0xFA]);
        let n = bad.len();
        bad[n - 1] ^= 0xFF;
        let mut t = vec![wake(), cmd(&[0x03, 0x00, 0x04]), I2cTransaction::read(ADDR, bad)];
        t.extend(txn(0x00, &[0x01, 0xF4, 0x00, 0xFA]));
        let mut s = full(&t);
        assert!(close(s.read(&mut NoopDelay).unwrap().1, 50.0));
        s.inner.i2c.done();
    }

    #[test]
    fn crc_mismatch_twice_fails() {
        let mut bad = read_resp(&[0x01, 0xF4, 0x00, 0xFA]);
        let n = bad.len();
        bad[n - 1] ^= 0xFF;
        let mut t = Vec::new();
        for _ in 0..2 {
            t.push(wake());
            t.push(cmd(&[0x03, 0x00, 0x04]));
            t.push(I2cTransaction::read(ADDR, bad.clone()));
        }
        let mut s = full(&t);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Crc));
        s.inner.i2c.done();
    }

    #[test]
    fn bus_error_retried_once() {
        let mut t = vec![
            wake(),
            cmd(&[0x03, 0x00, 0x04]),
            I2cTransaction::read(ADDR, vec![0; 8]).with_error(ErrorKind::Other),
        ];
        t.extend(txn(0x00, &[0x01, 0xF4, 0x00, 0xFA]));
        let mut s = full(&t);
        assert!(close(s.read(&mut NoopDelay).unwrap().0, 25.0));
        s.inner.i2c.done();
    }

    #[test]
    fn error_response_is_reported_without_retry() {
        let t = vec![
            wake(),
            cmd(&[0x03, 0x00, 0x04]),
            I2cTransaction::read(ADDR, vec![0x83, 0, 0, 0, 0, 0, 0, 0]),
        ];
        let mut s = full(&t);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Sensor(0x83)));
        s.inner.i2c.done();
    }

    #[test]
    fn single_values_and_raw() {
        let mut t = txn(0x00, &[0x00, 0x7B]);
        t.extend(txn(0x02, &[0x80, 0x65]));
        t.extend(txn(0x00, &[0x01, 0xF4, 0x80, 0x65]));
        let mut s = full(&t);
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 12.3));
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), -10.1));
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), (0x01F4, 0x8065));
        s.inner.i2c.done();
    }

    #[test]
    fn read_fresh_returns_second() {
        let mut t = txn(0x00, &[0x01, 0xF4, 0x00, 0xFA]);
        t.extend(txn(0x00, &[0x01, 0xF9, 0x01, 0x00]));
        let mut s = full(&t);
        let (temp, rh) = s.read_fresh(&mut NoopDelay).unwrap();
        assert!(close(rh, 50.5) && close(temp, 25.6));
        s.inner.i2c.done();
    }

    #[test]
    fn register_read_and_limits() {
        let mut s = full(&txn(0x08, &[1, 2, 3]));
        let mut out = [0u8; 3];
        s.read_registers(0x08, &mut out, &mut NoopDelay).unwrap();
        assert_eq!(out, [1, 2, 3]);
        assert_eq!(s.read_registers(0, &mut [], &mut NoopDelay), Err(Error::InvalidArgument));
        assert_eq!(s.read_registers(0, &mut [0u8; 11], &mut NoopDelay), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn device_info() {
        let mut s = full(&txn(0x08, &[0x09, 0x0F, 0x10, 0x12, 0x34, 0x56, 0x78]));
        assert_eq!(s.read_device_info(&mut NoopDelay).unwrap(), (0x090F, 0x10, 0x12345678));
        s.inner.i2c.done();
    }

    #[test]
    fn user_registers() {
        let body = [0x10, 0x10, 0x04, 0x12, 0x34, 0xAB, 0xCD];
        let t = vec![wake(), cmd(&resp(&body)), I2cTransaction::read(ADDR, resp(&[0x10, 0x10, 0x04]))];
        let mut u = txn(0x10, &[0x12, 0x34, 0xAB, 0xCD]);
        let mut all = t;
        all.append(&mut u);
        let mut s = full(&all);
        s.write_user_registers(0x1234, 0xABCD, &mut NoopDelay).unwrap();
        assert_eq!(s.read_user_registers(&mut NoopDelay).unwrap(), (0x1234, 0xABCD));
        s.inner.i2c.done();
    }

    #[test]
    fn write_registers_frame_and_limits() {
        let t = vec![wake(), cmd(&[0x10, 0x10, 0x02, 1, 2, 0xC0, 0x92]), I2cTransaction::read(ADDR, resp(&[0x10, 0x10, 0x02]))];
        let mut s = full(&t);
        s.write_registers(0x10, &[1, 2], &mut NoopDelay).unwrap();
        assert_eq!(s.write_registers(0x0F, &[1], &mut NoopDelay), Err(Error::InvalidArgument));
        assert_eq!(s.write_registers(0x13, &[1, 2], &mut NoopDelay), Err(Error::InvalidArgument));
        assert_eq!(s.write_registers(0x10, &[], &mut NoopDelay), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn write_error_response() {
        let body = [0x10, 0x10, 0x01, 1];
        let crc = crc16(&body);
        let t = vec![
            wake(),
            cmd(&[0x10, 0x10, 0x01, 1, (crc & 0xFF) as u8, (crc >> 8) as u8]),
            I2cTransaction::read(ADDR, vec![0x82, 0, 0, 0, 0]),
        ];
        let mut s = full(&t);
        assert_eq!(s.write_registers(0x10, &[1], &mut NoopDelay), Err(Error::Sensor(0x82)));
        s.inner.i2c.done();
    }

    #[test]
    fn wake_only() {
        let mut s = full(&[wake()]);
        s.wake(&mut NoopDelay);
        s.inner.i2c.done();
    }
}
