//! SHTC3 — relative humidity and temperature sensor (Sensirion).
//!
//! Communicates over I²C at the fixed address `0x70`. The chip is command driven (no register
//! pointer): 16-bit commands, then a separate read of two 16-bit words, each followed by a CRC-8
//! (polynomial `0x31`, init `0xFF`). The chip sleeps between measurements (0.3 µA) and must be
//! woken with a dedicated command; every `read()` runs the cycle wake-up → measure → read → sleep.
//! Construction does no bus access; `init()` performs the soft reset and the ID check.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_SLEEP: u16 = 0xB098;
const CMD_WAKE: u16 = 0x3517;
const CMD_RESET: u16 = 0x805D;
const CMD_READ_ID: u16 = 0xEFC8;
/// Measurement commands `[low_power][clock_stretching]`, temperature first.
const CMD_MEASURE: [[u16; 2]; 2] = [[0x7866, 0x7CA2], [0x609C, 0x6458]];
const ID_MASK: u16 = 0x083F;
const ID_VALUE: u16 = 0x0807;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// A CRC-8 check failed.
    Crc,
    /// The ID word does not carry the SHTC3 product code.
    WrongChip,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// CRC-8 used by the SHTC3: polynomial `0x31` (x⁸ + x⁵ + x⁴ + 1), init `0xFF`, no final XOR.
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

fn to_humidity(code: u16) -> f32 {
    (100.0 * code as f32 / 65536.0).clamp(0.0, 100.0)
}

fn to_temperature(code: u16) -> f32 {
    -45.0 + 175.0 * code as f32 / 65536.0
}

fn check_word(c: &[u8]) -> Result<u16, ()> {
    if crc8(&c[0..2]) != c[2] {
        return Err(());
    }
    Ok(((c[0] as u16) << 8) | c[1] as u16)
}

/// SHTC3 minimal driver — one full measurement cycle per `read()`.
///
/// Default configuration: normal mode, no clock stretching, 13 ms wait, both CRCs verified (one
/// retry), the sensor sleeps after every reading.
pub struct SHTC3Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    low_power: bool,
    stretching: bool,
    sleep_between: bool,
    asleep: bool,
}

impl<I2C: I2c> SHTC3Minimal<I2C> {
    /// Create a new `SHTC3Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x70`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, low_power: false, stretching: false, sleep_between: true, asleep: false }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn command(&mut self, code: u16) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &code.to_be_bytes())?;
        Ok(())
    }

    fn wake_if_asleep<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        if self.asleep {
            self.command(CMD_WAKE)?;
            delay.delay_ms(1);
            self.asleep = false;
        }
        Ok(())
    }

    fn go_to_sleep(&mut self) -> Result<(), Error<I2C::Error>> {
        self.command(CMD_SLEEP)?;
        self.asleep = true;
        Ok(())
    }

    fn read_id_word(&mut self) -> Result<u16, Error<I2C::Error>> {
        self.command(CMD_READ_ID)?;
        let mut buf = [0u8; 3];
        self.i2c.read(self.addr, &mut buf)?;
        check_word(&buf).map_err(|_| Error::Crc)
    }

    /// One measurement cycle; returns `(t_raw, rh_raw)`. A bad CRC is retried once.
    fn measure<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        self.wake_if_asleep(delay)?;
        let result = self.measure_inner(delay);
        if self.sleep_between {
            let slept = self.go_to_sleep();
            if result.is_ok() {
                slept?;
            }
        }
        result
    }

    fn measure_inner<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        let mut attempt = 0;
        loop {
            self.command(CMD_MEASURE[self.low_power as usize][self.stretching as usize])?;
            if !self.stretching {
                delay.delay_ms(if self.low_power { 1 } else { 13 });
            }
            let mut buf = [0u8; 6];
            self.i2c.read(self.addr, &mut buf)?;
            match (check_word(&buf[0..3]), check_word(&buf[3..6])) {
                (Ok(t), Ok(rh)) => return Ok((t, rh)),
                _ => {
                    attempt += 1;
                    if attempt == 2 {
                        return Err(Error::Crc);
                    }
                }
            }
        }
    }

    /// Wake, soft reset (`0x805D`), check the product ID, then sleep.
    ///
    /// [`Error::WrongChip`] if the ID word is not an SHTC3, [`Error::Crc`] if its CRC is bad.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.asleep = true;
        self.wake_if_asleep(delay)?;
        self.command(CMD_RESET)?;
        delay.delay_ms(1);
        let id = self.read_id_word();
        self.go_to_sleep()?;
        if id? & ID_MASK != ID_VALUE {
            return Err(Error::WrongChip);
        }
        Ok(())
    }

    /// Measure temperature and humidity.
    ///
    /// Returns `(temperature_c, humidity_pct)` in degrees Celsius and %RH (0–100).
    /// [`Error::Crc`] if either CRC does not match twice.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let (t, rh) = self.measure(delay)?;
        Ok((to_temperature(t), to_humidity(rh)))
    }
}

/// SHTC3 full driver — single values, raw codes, low-power mode, clock stretching, sleep control, ID, dew point.
///
/// Owns a [`SHTC3Minimal`] and delegates `init` and `read`.
pub struct SHTC3Full<I2C> {
    inner: SHTC3Minimal<I2C>,
}

impl<I2C: I2c> SHTC3Full<I2C> {
    /// Create a new `SHTC3Full`; same arguments as [`SHTC3Minimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: SHTC3Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Wake, soft reset, ID check, sleep (see [`SHTC3Minimal::init`]).
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Measure temperature and humidity (see [`SHTC3Minimal::read`]).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Run one measurement cycle and return the temperature in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(to_temperature(self.inner.measure(delay)?.0))
    }

    /// Run one measurement cycle and return the humidity in %RH, clamped to 0–100.
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(to_humidity(self.inner.measure(delay)?.1))
    }

    /// Run one measurement cycle and return the raw codes `(raw_temperature, raw_humidity)`.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        self.inner.measure(delay)
    }

    /// Use the low-power (0.7 ms, waits 1 ms) or the normal (10.8 ms, waits 13 ms) measurement commands.
    pub fn set_low_power(&mut self, on: bool) {
        self.inner.low_power = on;
    }

    /// Use the clock-stretching command set; the fixed wait is skipped (needs a controller that
    /// tolerates a slave holding SCL low).
    pub fn set_clock_stretching(&mut self, on: bool) {
        self.inner.stretching = on;
    }

    /// Choose whether a read ends with the sleep command (default on).
    pub fn set_sleep_between(&mut self, on: bool) {
        self.inner.sleep_between = on;
    }

    /// Send the wake-up command (`0x3517`) and wait 1 ms.
    pub fn wake<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(CMD_WAKE)?;
        delay.delay_ms(1);
        self.inner.asleep = false;
        Ok(())
    }

    /// Send the sleep command (`0xB098`).
    pub fn sleep(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.go_to_sleep()
    }

    /// Soft reset (`0x805D`), 1 ms wait; leaves the sensor idle.
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.wake_if_asleep(delay)?;
        self.inner.command(CMD_RESET)?;
        delay.delay_ms(1);
        Ok(())
    }

    /// Read the 16-bit ID word (`0xEFC8`, CRC verified); `(id & 0x083F) == 0x0807` for the SHTC3.
    ///
    /// A sleeping sensor is woken for the read and put back to sleep.
    pub fn read_id<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        let was_asleep = self.inner.asleep;
        self.inner.wake_if_asleep(delay)?;
        let id = self.inner.read_id_word();
        if was_asleep {
            self.inner.go_to_sleep()?;
        }
        id
    }

    /// Run one measurement and compute the dew point (Magnus formula over water) in °C.
    pub fn dew_point<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (t, rh) = self.read(delay)?;
        let rh = if rh <= 0.0 { 0.01 } else { rh };
        let g = libm::logf(rh / 100.0) + 17.62 * t / (243.12 + t);
        Ok(243.12 * g / (17.62 - g))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x70;

    fn cmd(code: u16) -> I2cTransaction {
        I2cTransaction::write(ADDR, code.to_be_bytes().to_vec())
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

    /// Transactions of one normal-mode cycle on an idle sensor, with sleep.
    fn cycle(t: u16, rh: u16) -> Vec<I2cTransaction> {
        vec![cmd(0x7866), I2cTransaction::read(ADDR, trh(t, rh)), cmd(0xB098)]
    }

    #[test]
    fn crc_reference() {
        assert_eq!(crc8(&[0xBE, 0xEF]), 0x92);
        assert_eq!(crc8(&[0x00]), 0xAC);
    }

    #[test]
    fn init_sequence() {
        let t = [cmd(0x3517), cmd(0x805D), cmd(0xEFC8), I2cTransaction::read(ADDR, word(0x0807)), cmd(0xB098)];
        let mut s = SHTC3Minimal::new(I2cMock::new(&t), ADDR);
        s.init(&mut NoopDelay).unwrap();
        s.i2c.done();
    }

    #[test]
    fn init_rejects_wrong_id() {
        let t = [cmd(0x3517), cmd(0x805D), cmd(0xEFC8), I2cTransaction::read(ADDR, word(0x0000)), cmd(0xB098)];
        let mut s = SHTC3Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.init(&mut NoopDelay), Err(Error::WrongChip));
        s.i2c.done();
    }

    #[test]
    fn read_converts() {
        let mut s = SHTC3Minimal::new(I2cMock::new(&cycle(0x6666, 0x6000)), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, -45.0 + 175.0 * 0x6666 as f32 / 65536.0, 1e-3));
        assert!(close(rh, 37.5, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn read_wakes_after_sleep() {
        let mut t = cycle(0x6666, 0x6000);
        t.push(cmd(0x3517));
        t.extend(cycle(0x6666, 0x6000));
        let mut s = SHTC3Minimal::new(I2cMock::new(&t), ADDR);
        s.read(&mut NoopDelay).unwrap();
        s.read(&mut NoopDelay).unwrap();
        s.i2c.done();
    }

    #[test]
    fn read_full_scale() {
        let mut t = cycle(0xFFFF, 0xFFFF);
        t.push(cmd(0x3517));
        t.extend(cycle(0x0000, 0x0000));
        let mut s = SHTC3Minimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, 130.0, 0.01) && rh <= 100.0 && rh > 99.9);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, -45.0, 1e-3) && close(rh, 0.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn crc_mismatch_retried_then_rejected() {
        let mut bad = trh(0x6666, 0x6000);
        bad[2] ^= 0xFF;
        let t = [
            cmd(0x7866), I2cTransaction::read(ADDR, bad.clone()),
            cmd(0x7866), I2cTransaction::read(ADDR, bad),
            cmd(0xB098),
        ];
        let mut s = SHTC3Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Crc));
        s.i2c.done();
    }

    #[test]
    fn crc_glitch_recovered() {
        let mut bad = trh(0x6666, 0x6000);
        bad[5] ^= 0xFF;
        let t = [
            cmd(0x7866), I2cTransaction::read(ADDR, bad),
            cmd(0x7866), I2cTransaction::read(ADDR, trh(0x6666, 0x6000)),
            cmd(0xB098),
        ];
        let mut s = SHTC3Minimal::new(I2cMock::new(&t), ADDR);
        let (_, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 37.5, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn single_values_and_raw() {
        let mut t = cycle(0x4000, 0x4000);
        t.push(cmd(0x3517));
        t.extend(cycle(0x4000, 0x4000));
        t.push(cmd(0x3517));
        t.extend(cycle(0x1234, 0x5678));
        let mut s = SHTC3Full::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 25.0, 1e-3));
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), -45.0 + 175.0 * 0.25, 1e-3));
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), (0x1234, 0x5678));
        s.release().done();
    }

    #[test]
    fn mode_commands() {
        let t = [
            cmd(0x609C), I2cTransaction::read(ADDR, trh(0x6666, 0x6000)), cmd(0xB098),
            cmd(0x3517), cmd(0x6458), I2cTransaction::read(ADDR, trh(0x6666, 0x6000)), cmd(0xB098),
            cmd(0x3517), cmd(0x7CA2), I2cTransaction::read(ADDR, trh(0x6666, 0x6000)), cmd(0xB098),
        ];
        let mut s = SHTC3Full::new(I2cMock::new(&t), ADDR);
        s.set_low_power(true);
        s.read(&mut NoopDelay).unwrap();
        s.set_clock_stretching(true);
        s.read(&mut NoopDelay).unwrap();
        s.set_low_power(false);
        s.read(&mut NoopDelay).unwrap();
        s.release().done();
    }

    #[test]
    fn sleep_control() {
        let t = [
            cmd(0x7866), I2cTransaction::read(ADDR, trh(0x6666, 0x6000)),
            cmd(0x7866), I2cTransaction::read(ADDR, trh(0x6666, 0x6000)),
            cmd(0xB098), cmd(0x3517),
        ];
        let mut s = SHTC3Full::new(I2cMock::new(&t), ADDR);
        s.set_sleep_between(false);
        s.read(&mut NoopDelay).unwrap();
        s.read(&mut NoopDelay).unwrap();
        s.sleep().unwrap();
        s.wake(&mut NoopDelay).unwrap();
        s.release().done();
    }

    #[test]
    fn soft_reset_command() {
        let mut s = SHTC3Full::new(I2cMock::new(&[cmd(0x805D)]), ADDR);
        s.soft_reset(&mut NoopDelay).unwrap();
        s.release().done();
    }

    #[test]
    fn read_id_values() {
        let t = [
            cmd(0xEFC8), I2cTransaction::read(ADDR, word(0xAB07)),
            cmd(0xB098),
            cmd(0x3517), cmd(0xEFC8), I2cTransaction::read(ADDR, word(0x0807)), cmd(0xB098),
        ];
        let mut s = SHTC3Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read_id(&mut NoopDelay).unwrap(), 0xAB07);
        s.sleep().unwrap();
        assert_eq!(s.read_id(&mut NoopDelay).unwrap() & 0x083F, 0x0807);
        s.release().done();
    }

    #[test]
    fn read_id_crc_mismatch() {
        let mut bad = word(0x0807);
        bad[2] ^= 0x55;
        let t = [cmd(0xEFC8), I2cTransaction::read(ADDR, bad)];
        let mut s = SHTC3Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read_id(&mut NoopDelay), Err(Error::Crc));
        s.release().done();
    }

    #[test]
    fn dew_point_value() {
        let t_raw = ((25.0f32 + 45.0) * 65536.0 / 175.0).round() as u16;
        let mut s = SHTC3Full::new(I2cMock::new(&cycle(t_raw, 0x8000)), ADDR);
        let d = s.dew_point(&mut NoopDelay).unwrap();
        assert!(close(d, 13.86, 0.15));
        s.release().done();
    }

    #[test]
    fn bus_error_propagates() {
        let t = [cmd(0x7866).with_error(embedded_hal::i2c::ErrorKind::Other), cmd(0xB098)];
        let mut s = SHTC3Minimal::new(I2cMock::new(&t), ADDR);
        assert!(matches!(s.read(&mut NoopDelay), Err(Error::Bus(_))));
        s.i2c.done();
    }
}
