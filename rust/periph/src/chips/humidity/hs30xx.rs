//! HS30xx — relative humidity and temperature sensors (Renesas HS3001, HS3002, HS3003).
//!
//! The three parts share one protocol and differ only in accuracy class and housing, so
//! `HS3001Minimal` / `HS3002Minimal` / `HS3003Minimal` and the `Full` variants are plain type
//! aliases of [`HS30xxMinimal`] / [`HS30xxFull`].
//!
//! Communicates over I²C (up to 400 kHz) at the fixed address `0x44`. The chip has no register
//! pointer: an **address-only write** (measurement request, MR) starts a conversion and a read of
//! up to four bytes (data fetch, DF) returns the result — two status bits plus 14-bit humidity,
//! then 14-bit temperature. Resolution settings and the 32-bit ID live in NVM, reachable only in a
//! programming mode that must be entered within 10 ms after power-up; the NVM methods on
//! [`HS30xxFull`] therefore take a `power_cycle` callback that cuts and restores the sensor supply.
//!
//! Construction does no bus access: bytes written within 10 ms of power-up could be taken as NVM
//! programming commands.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_ENTER_PROG: [u8; 3] = [0xA0, 0x00, 0x00];
const CMD_EXIT_PROG: [u8; 3] = [0x80, 0x00, 0x00];

const REG_RH_READ: u8 = 0x06;
const REG_RH_WRITE: u8 = 0x46;
const REG_T_READ: u8 = 0x11;
const REG_T_WRITE: u8 = 0x51;
const REG_ID_HIGH: u8 = 0x1E;
const REG_ID_LOW: u8 = 0x1F;

const NVM_OK: u8 = 0x81;
const RES_MASK: u16 = 0x0C00;
const BITS: [u8; 4] = [8, 10, 12, 14];
const FULL_SCALE: f32 = 16383.0;
const DEFAULT_WAIT_US: u32 = 40_000;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// The data were still flagged stale after one retry.
    Stale,
    /// An NVM read returned a status byte other than `0x81`.
    Nvm(u8),
    /// An NVM method was called without a `power_cycle` callback.
    PowerCycleRequired,
    /// Unsupported argument (resolution other than 8, 10, 12 or 14 bit; wait of 0).
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

fn raw_humidity(d: &[u8; 4]) -> u16 {
    (((d[0] & 0x3F) as u16) << 8) | d[1] as u16
}

fn raw_temperature(d: &[u8; 4]) -> u16 {
    ((d[2] as u16) << 6) | (d[3] >> 2) as u16
}

fn to_humidity(raw: u16) -> f32 {
    raw as f32 / FULL_SCALE * 100.0
}

fn to_temperature(raw: u16) -> f32 {
    raw as f32 / FULL_SCALE * 165.0 - 40.0
}

/// HS30xx minimal driver — one request, 40 ms wait and four-byte fetch per `read()`.
///
/// Default configuration (factory): 14-bit humidity and temperature resolution, fixed 40 ms
/// wait after the request, no NVM access.
pub struct HS30xxMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    wait_us: u32,
}

impl<I2C: I2c> HS30xxMinimal<I2C> {
    /// Create a new `HS30xxMinimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x44`, fixed).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, wait_us: DEFAULT_WAIT_US }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Send the measurement request (an address-only write), wait and read `buf`.
    fn fetch_raw<D: DelayNs>(&mut self, buf: &mut [u8], delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[])?;
        delay.delay_us(self.wait_us);
        self.i2c.read(self.addr, buf)?;
        Ok(())
    }

    /// Request, wait and fetch `len` bytes, retrying once after 10 ms if the status says stale.
    fn fetch<D: DelayNs>(&mut self, len: usize, delay: &mut D) -> Result<[u8; 4], Error<I2C::Error>> {
        let mut buf = [0u8; 4];
        self.fetch_raw(&mut buf[..len], delay)?;
        if buf[0] >> 6 != 0 {
            delay.delay_ms(10);
            self.i2c.read(self.addr, &mut buf[..len])?;
            if buf[0] >> 6 != 0 {
                return Err(Error::Stale);
            }
        }
        Ok(buf)
    }

    /// Measure temperature and humidity.
    ///
    /// Returns `(temperature_c, humidity_pct)` in degrees Celsius (−40…125) and %RH (0…100).
    /// [`Error::Stale`] if the data are still flagged stale after one retry.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let d = self.fetch(4, delay)?;
        Ok((to_temperature(raw_temperature(&d)), to_humidity(raw_humidity(&d))))
    }
}

/// HS30xx full driver — raw values, wait override, NVM resolution and ID, dew point.
///
/// Owns a [`HS30xxMinimal`] and delegates `read`.
pub struct HS30xxFull<I2C> {
    inner: HS30xxMinimal<I2C>,
}

impl<I2C: I2c> HS30xxFull<I2C> {
    /// Create a new `HS30xxFull`; same arguments as [`HS30xxMinimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: HS30xxMinimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Measure temperature and humidity; see [`HS30xxMinimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Measure temperature only (four-byte fetch, humidity discarded) in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(to_temperature(raw_temperature(&self.inner.fetch(4, delay)?)))
    }

    /// Measure humidity only (two-byte fetch) in %RH.
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(to_humidity(raw_humidity(&self.inner.fetch(2, delay)?)))
    }

    /// Measure and return `(raw_humidity, raw_temperature, status)` without a stale check.
    ///
    /// The values are 14 bit; `status` is 0 for valid data and 1 for stale data.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16, u8), Error<I2C::Error>> {
        let mut buf = [0u8; 4];
        self.inner.fetch_raw(&mut buf, delay)?;
        Ok((raw_humidity(&buf), raw_temperature(&buf), buf[0] >> 6))
    }

    /// Override the wait between measurement request and data fetch (default 40 ms).
    ///
    /// Lower resolutions convert faster: `0.1 ms + RH_time/2 + T_time/2` (datasheet).
    /// [`Error::InvalidArgument`] for 0.
    pub fn set_measurement_wait_us(&mut self, us: u32) -> Result<(), Error<I2C::Error>> {
        if us == 0 {
            return Err(Error::InvalidArgument);
        }
        self.inner.wait_us = us;
        Ok(())
    }

    /// Send the enter-programming-mode command (`0xA0 0x00 0x00`) and wait 120 µs.
    ///
    /// Only valid within 10 ms after power-up.
    pub fn enter_programming_mode<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &CMD_ENTER_PROG)?;
        delay.delay_us(120);
        Ok(())
    }

    /// Send the leave-programming-mode command (`0x80 0x00 0x00`).
    pub fn exit_programming_mode(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &CMD_EXIT_PROG)?;
        Ok(())
    }

    /// Read one 16-bit NVM register (programming mode only).
    ///
    /// [`Error::Nvm`] if the response status byte is not `0x81`.
    pub fn read_nvm<D: DelayNs>(&mut self, reg: u8, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[reg, 0x00, 0x00])?;
        delay.delay_us(120);
        let mut buf = [0u8; 3];
        self.inner.i2c.read(self.inner.addr, &mut buf)?;
        if buf[0] != NVM_OK {
            return Err(Error::Nvm(buf[0]));
        }
        Ok(((buf[1] as u16) << 8) | buf[2] as u16)
    }

    fn write_nvm<D: DelayNs>(&mut self, reg: u8, value: u16, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[reg, (value >> 8) as u8, value as u8])?;
        delay.delay_ms(14);
        Ok(())
    }

    fn program<D: DelayNs>(&mut self, power_cycle: Option<&mut dyn FnMut()>, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let cycle = power_cycle.ok_or(Error::PowerCycleRequired)?;
        cycle();
        self.enter_programming_mode(delay)
    }

    /// Run `body` in programming mode and always leave it afterwards.
    fn in_programming<D: DelayNs, T>(
        &mut self,
        power_cycle: Option<&mut dyn FnMut()>,
        delay: &mut D,
        body: impl FnOnce(&mut Self, &mut D) -> Result<T, Error<I2C::Error>>,
    ) -> Result<T, Error<I2C::Error>> {
        self.program(power_cycle, delay)?;
        let result = body(self, delay);
        let exit = self.exit_programming_mode();
        let value = result?;
        exit?;
        Ok(value)
    }

    /// Read the NVM resolution settings (power-cycles the sensor).
    ///
    /// Returns `(humidity_bits, temperature_bits)`, each 8, 10, 12 or 14.
    /// [`Error::PowerCycleRequired`] if `power_cycle` is `None`.
    pub fn get_resolution<D: DelayNs>(&mut self, power_cycle: Option<&mut dyn FnMut()>, delay: &mut D) -> Result<(u8, u8), Error<I2C::Error>> {
        self.in_programming(power_cycle, delay, |s, d| {
            let rh = s.read_nvm(REG_RH_READ, d)?;
            let t = s.read_nvm(REG_T_READ, d)?;
            Ok((BITS[((rh >> 10) & 3) as usize], BITS[((t >> 10) & 3) as usize]))
        })
    }

    /// Write the NVM resolution settings (power-cycles the sensor; bits 11:10 only).
    ///
    /// `None` leaves a channel unchanged; other values must be 8, 10, 12 or 14
    /// ([`Error::InvalidArgument`]). Each register is read, bits 11:10 replaced and the whole word
    /// written back, then the driver waits 14 ms for the NVM commit.
    pub fn set_resolution<D: DelayNs>(
        &mut self,
        humidity_bits: Option<u8>,
        temperature_bits: Option<u8>,
        power_cycle: Option<&mut dyn FnMut()>,
        delay: &mut D,
    ) -> Result<(), Error<I2C::Error>> {
        let code = |bits: Option<u8>| -> Result<Option<u16>, Error<I2C::Error>> {
            match bits {
                None => Ok(None),
                Some(b) => BITS.iter().position(|&x| x == b).map(|i| Some(i as u16)).ok_or(Error::InvalidArgument),
            }
        };
        let rh = code(humidity_bits)?;
        let t = code(temperature_bits)?;
        self.in_programming(power_cycle, delay, |s, d| {
            for (code, rreg, wreg) in [(rh, REG_RH_READ, REG_RH_WRITE), (t, REG_T_READ, REG_T_WRITE)] {
                if let Some(c) = code {
                    let value = s.read_nvm(rreg, d)?;
                    s.write_nvm(wreg, (value & !RES_MASK) | (c << 10), d)?;
                }
            }
            Ok(())
        })
    }

    /// Read the 32-bit sensor ID from NVM (power-cycles the sensor).
    pub fn read_id<D: DelayNs>(&mut self, power_cycle: Option<&mut dyn FnMut()>, delay: &mut D) -> Result<u32, Error<I2C::Error>> {
        self.in_programming(power_cycle, delay, |s, d| {
            let high = s.read_nvm(REG_ID_HIGH, d)?;
            let low = s.read_nvm(REG_ID_LOW, d)?;
            Ok(((high as u32) << 16) | low as u32)
        })
    }

    /// Measure and return the dew point in °C (Magnus formula); −273.15 if humidity is 0 %RH.
    pub fn dew_point<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (t, rh) = self.inner.read(delay)?;
        if rh <= 0.0 {
            return Ok(-273.15);
        }
        let gamma = libm::logf(rh / 100.0) + 17.62 * t / (243.12 + t);
        Ok(243.12 * gamma / (17.62 - gamma))
    }
}

/// HS3001 minimal driver — alias of [`HS30xxMinimal`] (same protocol, different accuracy class).
pub type HS3001Minimal<I2C> = HS30xxMinimal<I2C>;
/// HS3002 minimal driver — alias of [`HS30xxMinimal`].
pub type HS3002Minimal<I2C> = HS30xxMinimal<I2C>;
/// HS3003 minimal driver — alias of [`HS30xxMinimal`].
pub type HS3003Minimal<I2C> = HS30xxMinimal<I2C>;
/// HS3001 full driver — alias of [`HS30xxFull`].
pub type HS3001Full<I2C> = HS30xxFull<I2C>;
/// HS3002 full driver — alias of [`HS30xxFull`].
pub type HS3002Full<I2C> = HS30xxFull<I2C>;
/// HS3003 full driver — alias of [`HS30xxFull`].
pub type HS3003Full<I2C> = HS30xxFull<I2C>;

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x44;

    fn mr() -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![])
    }

    fn frame(rh_raw: u16, t_raw: u16, status: u8) -> Vec<u8> {
        vec![(status << 6) | (rh_raw >> 8) as u8, rh_raw as u8, (t_raw >> 6) as u8, ((t_raw & 0x3F) << 2) as u8]
    }

    fn close(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() <= tol
    }

    fn nvm_read(reg: u8, hi: u8, lo: u8) -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write(ADDR, vec![reg, 0, 0]),
            I2cTransaction::read(ADDR, vec![0x81, hi, lo]),
        ]
    }

    fn enter() -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![0xA0, 0, 0])
    }

    fn exit() -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![0x80, 0, 0])
    }

    #[test]
    fn conversions() {
        let t = vec![mr(), I2cTransaction::read(ADDR, frame(0x2000, 0x1000, 0))];
        let mut s = HS30xxMinimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 50.0, 0.01));
        assert!(close(temp, 1.25, 0.01));
        s.release().done();
    }

    #[test]
    fn full_scale_and_zero() {
        let t = vec![
            mr(), I2cTransaction::read(ADDR, frame(0x3FFF, 0x3FFF, 0)),
            mr(), I2cTransaction::read(ADDR, frame(0, 0, 0)),
        ];
        let mut s = HS30xxMinimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 100.0, 1e-3) && close(temp, 125.0, 1e-3));
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 0.0, 1e-3) && close(temp, -40.0, 1e-3));
        s.release().done();
    }

    #[test]
    fn masks_undetermined_bits() {
        let t = vec![mr(), I2cTransaction::read(ADDR, vec![0x20, 0x00, 0x10, 0x03])];
        let mut s = HS30xxMinimal::new(I2cMock::new(&t), ADDR);
        let (temp, _) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, 0x400 as f32 / 16383.0 * 165.0 - 40.0, 1e-3));
        s.release().done();
    }

    #[test]
    fn constructor_no_bus_access() {
        let t: Vec<I2cTransaction> = vec![];
        let s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        s.release().done();
    }

    #[test]
    fn stale_retry_then_ok() {
        let t = vec![
            mr(),
            I2cTransaction::read(ADDR, frame(0x2000, 0x1000, 1)),
            I2cTransaction::read(ADDR, frame(0x2000, 0x1000, 0)),
        ];
        let mut s = HS30xxMinimal::new(I2cMock::new(&t), ADDR);
        let (_, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 50.0, 0.01));
        s.release().done();
    }

    #[test]
    fn stale_twice_errors() {
        let t = vec![
            mr(),
            I2cTransaction::read(ADDR, frame(0, 0, 1)),
            I2cTransaction::read(ADDR, frame(0, 0, 1)),
        ];
        let mut s = HS30xxMinimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Stale));
        s.release().done();
    }

    #[test]
    fn bus_error_propagates() {
        let t = vec![mr().with_error(ErrorKind::Other)];
        let mut s = HS30xxMinimal::new(I2cMock::new(&t), ADDR);
        assert!(matches!(s.read(&mut NoopDelay), Err(Error::Bus(_))));
        s.release().done();
    }

    #[test]
    fn single_values_and_raw() {
        let t = vec![
            mr(), I2cTransaction::read(ADDR, frame(0x2000, 0x1000, 0)),
            mr(), I2cTransaction::read(ADDR, vec![0x20, 0x00]),
            mr(), I2cTransaction::read(ADDR, frame(0x1234, 0x0ABC, 0)),
            mr(), I2cTransaction::read(ADDR, frame(0x1234, 0x0ABC, 1)),
        ];
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), 1.25, 0.01));
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 50.0, 0.01));
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), (0x1234, 0x0ABC, 0));
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap().2, 1);
        s.release().done();
    }

    #[test]
    fn measurement_wait_validation() {
        let t: Vec<I2cTransaction> = vec![];
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.set_measurement_wait_us(0), Err(Error::InvalidArgument));
        assert!(s.set_measurement_wait_us(5000).is_ok());
        s.release().done();
    }

    #[test]
    fn programming_commands() {
        let t = vec![enter(), exit()];
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        s.enter_programming_mode(&mut NoopDelay).unwrap();
        s.exit_programming_mode().unwrap();
        s.release().done();
    }

    #[test]
    fn read_nvm_value_and_bad_status() {
        let mut t = nvm_read(0x1E, 0x12, 0x34);
        t.push(I2cTransaction::write(ADDR, vec![0x1E, 0, 0]));
        t.push(I2cTransaction::read(ADDR, vec![0x80, 0x12, 0x34]));
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read_nvm(0x1E, &mut NoopDelay).unwrap(), 0x1234);
        assert_eq!(s.read_nvm(0x1E, &mut NoopDelay), Err(Error::Nvm(0x80)));
        s.release().done();
    }

    #[test]
    fn nvm_methods_need_power_cycle() {
        let t: Vec<I2cTransaction> = vec![];
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.get_resolution(None, &mut NoopDelay), Err(Error::PowerCycleRequired));
        assert_eq!(s.set_resolution(Some(12), Some(12), None, &mut NoopDelay), Err(Error::PowerCycleRequired));
        assert_eq!(s.read_id(None, &mut NoopDelay), Err(Error::PowerCycleRequired));
        s.release().done();
    }

    #[test]
    fn get_resolution_sequence() {
        let mut t = vec![enter()];
        t.extend(nvm_read(0x06, 0x0C, 0x00));
        t.extend(nvm_read(0x11, 0x08, 0x05));
        t.push(exit());
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        let mut cycles = 0;
        let mut cycle = || cycles += 1;
        assert_eq!(s.get_resolution(Some(&mut cycle), &mut NoopDelay).unwrap(), (14, 12));
        assert_eq!(cycles, 1);
        s.release().done();
    }

    #[test]
    fn set_resolution_preserves_other_bits() {
        let mut t = vec![enter()];
        t.extend(nvm_read(0x06, 0x0C, 0x07));
        t.push(I2cTransaction::write(ADDR, vec![0x46, 0x00, 0x07]));
        t.extend(nvm_read(0x11, 0x0C, 0x21));
        t.push(I2cTransaction::write(ADDR, vec![0x51, 0x08, 0x21]));
        t.push(exit());
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        let mut cycle = || {};
        s.set_resolution(Some(8), Some(12), Some(&mut cycle), &mut NoopDelay).unwrap();
        s.release().done();
    }

    #[test]
    fn set_resolution_one_channel_and_invalid() {
        let mut t = vec![enter()];
        t.extend(nvm_read(0x11, 0x00, 0x00));
        t.push(I2cTransaction::write(ADDR, vec![0x51, 0x04, 0x00]));
        t.push(exit());
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        let mut cycle = || {};
        s.set_resolution(None, Some(10), Some(&mut cycle), &mut NoopDelay).unwrap();
        // invalid values fail before any bus traffic
        assert_eq!(s.set_resolution(Some(9), None, Some(&mut cycle), &mut NoopDelay), Err(Error::InvalidArgument));
        s.release().done();
    }

    #[test]
    fn failed_nvm_read_still_exits_programming() {
        let t = vec![
            enter(),
            I2cTransaction::write(ADDR, vec![0x06, 0, 0]),
            I2cTransaction::read(ADDR, vec![0x00, 0, 0]),
            exit(),
        ];
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        let mut cycle = || {};
        assert_eq!(s.get_resolution(Some(&mut cycle), &mut NoopDelay), Err(Error::Nvm(0)));
        s.release().done();
    }

    #[test]
    fn read_id_combines_words() {
        let mut t = vec![enter()];
        t.extend(nvm_read(0x1E, 0xDE, 0xAD));
        t.extend(nvm_read(0x1F, 0xBE, 0xEF));
        t.push(exit());
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        let mut cycle = || {};
        assert_eq!(s.read_id(Some(&mut cycle), &mut NoopDelay).unwrap(), 0xDEAD_BEEF);
        s.release().done();
    }

    #[test]
    fn dew_point_values() {
        let rh_raw = (0.5f32 * 16383.0) as u16;
        let t_raw = ((25.0f32 + 40.0) / 165.0 * 16383.0) as u16;
        let t = vec![
            mr(), I2cTransaction::read(ADDR, frame(rh_raw, t_raw, 0)),
            mr(), I2cTransaction::read(ADDR, frame(0, 0x1000, 0)),
        ];
        let mut s = HS30xxFull::new(I2cMock::new(&t), ADDR);
        assert!(close(s.dew_point(&mut NoopDelay).unwrap(), 13.86, 0.1));
        assert!(close(s.dew_point(&mut NoopDelay).unwrap(), -273.15, 1e-3));
        s.release().done();
    }

    #[test]
    fn aliases_are_the_same_types() {
        let t: Vec<I2cTransaction> = vec![];
        let s: HS3001Full<_> = HS30xxFull::new(I2cMock::new(&t), ADDR);
        let _: &HS3002Full<_> = &s;
        s.release().done();
        let m: HS3003Minimal<_> = HS30xxMinimal::new(I2cMock::new(&t), ADDR);
        let _: &HS3001Minimal<_> = &m;
        m.release().done();
    }
}
