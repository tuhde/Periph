//! RRH62000 — all-in-one air-quality module (Renesas).
//!
//! The module combines a laser-scattering particulate-matter sensor, an HS40xx temperature and
//! humidity sensor and a ZMOD4410 TVOC/IAQ sensor behind a fan. Over I²C (fixed address
//! [`ADDRESS`] `0x69`, `SEL` pin to GND) it is register addressed: a one-byte command code is
//! the register pointer; reading `0x00` returns the 37-byte measurement block with a CRC-8
//! (polynomial `0x31`, init `0xFF`).
//!
//! The module must not be accessed more often than once per second (`tRD`). Without a clock
//! in `embedded-hal` the driver owns a [`DelayNs`] provider and waits the access interval
//! (1 s) before every bus access; the first measurement read after construction,
//! [`Rrh62000Full::wake`] or [`Rrh62000Full::soft_reset`] waits a further 2.3 s so valid data
//! (3.3 s after start-up) is available. The module defaults (10 moving averages, fan 86 %,
//! cleaning every 7 days for 10 s) are kept.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// Fixed 7-bit I²C address.
pub const ADDRESS: u8 = 0x69;
/// Length of the measurement block.
pub const BLOCK_LENGTH: usize = 37;

const CMD_READ: u8 = 0x00;
const CMD_DATA: u8 = 0x40;
const CMD_SLEEP: u8 = 0x50;
const CMD_CLEAN: u8 = 0x51;
const CMD_RESET: u8 = 0x52;
const CMD_MAVE: u8 = 0x53;
const CMD_TINTC_H: u8 = 0x5A;
const CMD_TINTC_L: u8 = 0x5B;
const CMD_TCLEAN: u8 = 0x5C;
const CMD_SPEEDFAN: u8 = 0x63;
const CMD_RMOX: u8 = 0x71;
const CMD_UID: u8 = 0x72;
const CMD_ARGVER: u8 = 0x73;
const CMD_CSTATUS: u8 = 0x74;
const CMD_FWVER: u8 = 0x75;

const ACCESS_MS: u32 = 1000;
const VALID_EXTRA_MS: u32 = 2300;
const DATA_POLLS: u32 = 4;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// I²C transfer failed.
    Bus(E),
    /// CRC-8 of the measurement block wrong.
    Crc,
    /// All-`0xFF` block (read before the start-up time).
    Invalid,
    /// No new data within 4 s.
    Timeout,
    /// Argument out of range.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// Main air-quality values of one sample ([`Rrh62000Minimal::read`]).
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub struct Measurement {
    /// PM1, KCl reference, µg/m³.
    pub pm1_ug_m3: f32,
    /// PM2.5, KCl reference, µg/m³.
    pub pm2_5_ug_m3: f32,
    /// PM10, KCl reference, µg/m³.
    pub pm10_ug_m3: f32,
    /// Temperature, °C.
    pub temperature_c: f32,
    /// Relative humidity, %RH.
    pub humidity_rh: f32,
    /// TVOC, µg/m³ (LSB 10 µg/m³).
    pub tvoc: u32,
    /// Estimated CO₂, ppm (400–5000).
    pub eco2_ppm: u16,
    /// UBA indoor air quality index, 1.00–5.00.
    pub iaq: f32,
}

/// The decoded status word of the measurement block.
#[derive(Debug, PartialEq, Eq, Clone, Copy, Default)]
pub struct Status {
    /// Status word (`byte0 | byte1 << 8`).
    pub raw: u16,
    /// Bit 0: dust concentration extremely high (not measurable).
    pub high_concentration: bool,
    /// Bit 1: dust accumulated inside the sensor (not measurable).
    pub dust_accumulation: bool,
    /// Bit 2: fan speed out of the set range.
    pub fan_speed: bool,
    /// Bit 3: fan malfunction or breakdown.
    pub fan: bool,
}

/// Particle values of the measurement block.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub struct Particles {
    /// PM1, KCl reference, µg/m³.
    pub pm1_ug_m3: f32,
    /// PM2.5, KCl reference, µg/m³.
    pub pm2_5_ug_m3: f32,
    /// PM10, KCl reference, µg/m³.
    pub pm10_ug_m3: f32,
    /// PM1, cigarette-smoke reference, µg/m³.
    pub pm1_smoke_ug_m3: f32,
    /// PM2.5, cigarette-smoke reference, µg/m³.
    pub pm2_5_smoke_ug_m3: f32,
    /// PM10, cigarette-smoke reference, µg/m³.
    pub pm10_smoke_ug_m3: f32,
    /// Particles 0.3–10 µm per cm³.
    pub nc_0_3: f32,
    /// Particles 0.5–10 µm per cm³.
    pub nc_0_5: f32,
    /// Particles 1–10 µm per cm³.
    pub nc_1: f32,
    /// Particles 2.5–10 µm per cm³.
    pub nc_2_5: f32,
    /// Particles 4–10 µm per cm³.
    pub nc_4: f32,
}

/// The complete decoded measurement block ([`Rrh62000Full::read_all`]).
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub struct Data {
    /// Status word and flags.
    pub status: Status,
    /// PM values (both references) and number concentrations.
    pub particles: Particles,
    /// Temperature, °C.
    pub temperature_c: f32,
    /// Relative humidity, %RH.
    pub humidity_rh: f32,
    /// TVOC, µg/m³.
    pub tvoc: u32,
    /// Estimated CO₂, ppm.
    pub eco2_ppm: u16,
    /// UBA IAQ index, 1.00–5.00.
    pub iaq: f32,
}

/// CRC-8 of the measurement block (polynomial `0x31`, init `0xFF`; `crc8(&[0xBE, 0xEF]) == 0x92`).
pub fn crc8(data: &[u8]) -> u8 {
    let mut crc = 0xFFu8;
    for &b in data {
        crc ^= b;
        for _ in 0..8 {
            crc = if crc & 0x80 != 0 { (crc << 1) ^ 0x31 } else { crc << 1 };
        }
    }
    crc
}

/// Decode a 37-byte measurement block; rejects an all-`0xFF` block and a CRC mismatch.
pub fn decode<E>(b: &[u8; BLOCK_LENGTH]) -> Result<Data, Error<E>> {
    if b[..36].iter().all(|&x| x == 0xFF) {
        return Err(Error::Invalid);
    }
    if crc8(&b[..36]) != b[36] {
        return Err(Error::Crc);
    }
    let w = |i: usize| u16::from_be_bytes([b[i], b[i + 1]]);
    let raw = u16::from_le_bytes([b[0], b[1]]);
    Ok(Data {
        status: Status {
            raw,
            high_concentration: raw & 0x01 != 0,
            dust_accumulation: raw & 0x02 != 0,
            fan_speed: raw & 0x04 != 0,
            fan: raw & 0x08 != 0,
        },
        particles: Particles {
            nc_0_3: w(2) as f32 / 10.0,
            nc_0_5: w(4) as f32 / 10.0,
            nc_1: w(6) as f32 / 10.0,
            nc_2_5: w(8) as f32 / 10.0,
            nc_4: w(10) as f32 / 10.0,
            pm1_ug_m3: w(12) as f32 / 10.0,
            pm2_5_ug_m3: w(14) as f32 / 10.0,
            pm10_ug_m3: w(16) as f32 / 10.0,
            pm1_smoke_ug_m3: w(18) as f32 / 10.0,
            pm2_5_smoke_ug_m3: w(20) as f32 / 10.0,
            pm10_smoke_ug_m3: w(22) as f32 / 10.0,
        },
        temperature_c: w(24) as i16 as f32 / 100.0,
        humidity_rh: w(26) as f32 / 100.0,
        tvoc: w(28) as u32 * 10,
        eco2_ppm: w(30),
        iaq: w(32) as f32 / 100.0,
    })
}

/// RRH62000 minimal driver — read the main air-quality values.
pub struct Rrh62000Minimal<I2C, D> {
    i2c: I2C,
    delay: D,
    addr: u8,
    startup: bool,
}

impl<I2C: I2c, D: DelayNs> Rrh62000Minimal<I2C, D> {
    /// Create a new `Rrh62000Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `delay` — Delay provider for the 1 s access interval and the start-up time.
    /// * `addr`  — 7-bit device address ([`ADDRESS`], `0x69`).
    pub fn new(i2c: I2C, delay: D, addr: u8) -> Self {
        Self { i2c, delay, addr, startup: true }
    }

    /// Release the I²C bus and the delay provider.
    pub fn release(self) -> (I2C, D) {
        (self.i2c, self.delay)
    }

    fn read_reg(&mut self, cmd: u8, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        self.delay.delay_ms(ACCESS_MS);
        self.i2c.write_read(self.addr, &[cmd], buf)?;
        Ok(())
    }

    fn read_byte(&mut self, cmd: u8) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        self.read_reg(cmd, &mut b)?;
        Ok(b[0])
    }

    fn write_reg(&mut self, cmd: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        self.delay.delay_ms(ACCESS_MS);
        self.i2c.write(self.addr, &[cmd, value])?;
        Ok(())
    }

    fn read_block(&mut self, wait: bool) -> Result<Data, Error<I2C::Error>> {
        if self.startup {
            self.delay.delay_ms(VALID_EXTRA_MS);
            self.startup = false;
        }
        if wait {
            let mut ready = false;
            for _ in 0..DATA_POLLS {
                if self.read_byte(CMD_DATA)? == 0x01 {
                    ready = true;
                    break;
                }
            }
            if !ready {
                return Err(Error::Timeout);
            }
        }
        let mut block = [0u8; BLOCK_LENGTH];
        self.read_reg(CMD_READ, &mut block)?;
        decode(&block)
    }

    /// Wait for the start-up time (1 s) and read the firmware version as a presence check.
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        let mut fw = [0u8; 2];
        self.read_reg(CMD_FWVER, &mut fw)
    }

    /// Wait for a new sample (DATA polled once per second, up to 4 s) and return the main values.
    pub fn read(&mut self) -> Result<Measurement, Error<I2C::Error>> {
        let d = self.read_block(true)?;
        Ok(Measurement {
            pm1_ug_m3: d.particles.pm1_ug_m3,
            pm2_5_ug_m3: d.particles.pm2_5_ug_m3,
            pm10_ug_m3: d.particles.pm10_ug_m3,
            temperature_c: d.temperature_c,
            humidity_rh: d.humidity_rh,
            tvoc: d.tvoc,
            eco2_ppm: d.eco2_ppm,
            iaq: d.iaq,
        })
    }
}

/// RRH62000 full driver — extends [`Rrh62000Minimal`] with the complete block, status flags,
/// particle bins, sleep, fan cleaning, reset, settings, MOX resistance and identification.
pub struct Rrh62000Full<I2C, D> {
    inner: Rrh62000Minimal<I2C, D>,
}

impl<I2C: I2c, D: DelayNs> Rrh62000Full<I2C, D> {
    /// Create the driver; same arguments as [`Rrh62000Minimal::new`].
    pub fn new(i2c: I2C, delay: D, addr: u8) -> Self {
        Self { inner: Rrh62000Minimal::new(i2c, delay, addr) }
    }

    /// Release the I²C bus and the delay provider.
    pub fn release(self) -> (I2C, D) {
        self.inner.release()
    }

    /// Wait for the start-up time and check presence. See [`Rrh62000Minimal::init`].
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.init()
    }

    /// Main values of a new sample. See [`Rrh62000Minimal::read`].
    pub fn read(&mut self) -> Result<Measurement, Error<I2C::Error>> {
        self.inner.read()
    }

    /// Wait for a new sample and decode the complete block.
    pub fn read_all(&mut self) -> Result<Data, Error<I2C::Error>> {
        self.inner.read_block(true)
    }

    /// Read the current block (without waiting for new data) and decode its status word.
    pub fn read_status(&mut self) -> Result<Status, Error<I2C::Error>> {
        Ok(self.inner.read_block(false)?.status)
    }

    /// Read the current block (without waiting for new data) and return the particle values.
    pub fn read_particles(&mut self) -> Result<Particles, Error<I2C::Error>> {
        Ok(self.inner.read_block(false)?.particles)
    }

    /// `true` when new data has arrived (`DATA`, `0x40`).
    pub fn data_ready(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.inner.read_byte(CMD_DATA)? == 0x01)
    }

    /// Put the module to sleep (`0x50` ← `0x00`).
    pub fn sleep(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.write_reg(CMD_SLEEP, 0x00)
    }

    /// Wake the module up (`0x50` ← `0x80`); the next measurement read waits for valid data.
    pub fn wake(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.write_reg(CMD_SLEEP, 0x80)?;
        self.inner.startup = true;
        Ok(())
    }

    /// Start the fan dust-cleaning (`0x51` ← `0x01`).
    pub fn start_cleaning(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.write_reg(CMD_CLEAN, 0x01)
    }

    /// `true` when the cleaning is completed (`CSTATUS`, `0x74`).
    pub fn cleaning_done(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.inner.read_byte(CMD_CSTATUS)? == 0x01)
    }

    /// Reset the module (`0x52` ← `0x81`, same as power-on); a missing acknowledge is ignored
    /// and the next measurement read waits for valid data.
    pub fn soft_reset(&mut self) -> Result<(), Error<I2C::Error>> {
        let _ = self.inner.write_reg(CMD_RESET, 0x81);
        self.inner.startup = true;
        Ok(())
    }

    /// Set the number of moving averages (`0x53`, 1–60, default 10).
    pub fn set_moving_average(&mut self, count: u8) -> Result<(), Error<I2C::Error>> {
        if !(1..=60).contains(&count) {
            return Err(Error::InvalidArgument);
        }
        self.inner.write_reg(CMD_MAVE, count)
    }

    /// Read the number of moving averages (1–60).
    pub fn get_moving_average(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_byte(CMD_MAVE)
    }

    /// Set the fan speed (`0x63`, 60–100 %, default 86).
    pub fn set_fan_speed(&mut self, percent: u8) -> Result<(), Error<I2C::Error>> {
        if !(60..=100).contains(&percent) {
            return Err(Error::InvalidArgument);
        }
        self.inner.write_reg(CMD_SPEEDFAN, percent)
    }

    /// Read the fan speed in percent (60–100).
    pub fn get_fan_speed(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_byte(CMD_SPEEDFAN)
    }

    /// Set the automatic cleaning interval (`0x5A`/`0x5B`, 16-bit count of 30 s units).
    ///
    /// # Arguments
    /// * `seconds` — 0–1 814 400 s, a multiple of 30 (default 604 800 = 7 days).
    pub fn set_cleaning_interval(&mut self, seconds: u32) -> Result<(), Error<I2C::Error>> {
        if seconds > 1_814_400 || seconds % 30 != 0 {
            return Err(Error::InvalidArgument);
        }
        let units = (seconds / 30) as u16;
        self.inner.write_reg(CMD_TINTC_H, (units >> 8) as u8)?;
        self.inner.write_reg(CMD_TINTC_L, units as u8)
    }

    /// Read the automatic cleaning interval in seconds.
    pub fn get_cleaning_interval(&mut self) -> Result<u32, Error<I2C::Error>> {
        let hi = self.inner.read_byte(CMD_TINTC_H)?;
        let lo = self.inner.read_byte(CMD_TINTC_L)?;
        Ok(u16::from_be_bytes([hi, lo]) as u32 * 30)
    }

    /// Set the fan auto-cleaning time (`0x5C`, 0–60 s, default 10).
    pub fn set_cleaning_time(&mut self, seconds: u8) -> Result<(), Error<I2C::Error>> {
        if seconds > 60 {
            return Err(Error::InvalidArgument);
        }
        self.inner.write_reg(CMD_TCLEAN, seconds)
    }

    /// Read the fan auto-cleaning time in seconds (0–60).
    pub fn get_cleaning_time(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_byte(CMD_TCLEAN)
    }

    /// Read the MOX sensor resistance in Ω (`0x71`, 4 bytes big-endian).
    pub fn read_mox_resistance(&mut self) -> Result<u32, Error<I2C::Error>> {
        let mut d = [0u8; 4];
        self.inner.read_reg(CMD_RMOX, &mut d)?;
        Ok(u32::from_be_bytes(d))
    }

    /// Read the 6-byte unique ID (`0x72`).
    pub fn unique_id(&mut self) -> Result<[u8; 6], Error<I2C::Error>> {
        let mut d = [0u8; 6];
        self.inner.read_reg(CMD_UID, &mut d)?;
        Ok(d)
    }

    /// Read the TVOC algorithm version `(major, minor, patch)` (`0x73`).
    pub fn algorithm_version(&mut self) -> Result<(u8, u8, u8), Error<I2C::Error>> {
        let mut d = [0u8; 3];
        self.inner.read_reg(CMD_ARGVER, &mut d)?;
        Ok((d[0], d[1], d[2]))
    }

    /// Read the firmware version `(major, minor)` (`0x75`).
    pub fn firmware_version(&mut self) -> Result<(u8, u8), Error<I2C::Error>> {
        let mut d = [0u8; 2];
        self.inner.read_reg(CMD_FWVER, &mut d)?;
        Ok((d[0], d[1]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};
    use std::vec;
    use std::vec::Vec as StdVec;

    const A: u8 = ADDRESS;
    const WORDS: [u16; 17] = [1234, 1000, 500, 100, 20, 52, 125, 203, 61, 140, 230, 2345, 4567, 30, 812, 215, 0];

    fn block(status: u16, words: &[u16; 17]) -> StdVec<u8> {
        let mut b = vec![status as u8, (status >> 8) as u8];
        for w in words {
            b.extend_from_slice(&w.to_be_bytes());
        }
        let c = crc8(&b);
        b.push(c);
        b
    }

    fn rd(cmd: u8, resp: &[u8]) -> T {
        T::write_read(A, vec![cmd], resp.to_vec())
    }

    fn near(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-3
    }

    #[test]
    fn crc_matches_datasheet() {
        assert_eq!(crc8(&[0xBE, 0xEF]), 0x92);
        assert_eq!(crc8(&[0x00, 0x00]), 0x81);
    }

    #[test]
    fn init_and_read() {
        let t = [rd(0x75, &[2, 7]), rd(0x40, &[0]), rd(0x40, &[1]), rd(0x00, &block(5, &WORDS))];
        let mut m = Rrh62000Minimal::new(I2cMock::new(&t), NoopDelay, A);
        m.init().unwrap();
        let r = m.read().unwrap();
        assert!(near(r.pm1_ug_m3, 5.2) && near(r.pm2_5_ug_m3, 12.5) && near(r.pm10_ug_m3, 20.3));
        assert!(near(r.temperature_c, 23.45) && near(r.humidity_rh, 45.67));
        assert_eq!((r.tvoc, r.eco2_ppm), (300, 812));
        assert!(near(r.iaq, 2.15));
        m.release().0.done();
    }

    #[test]
    fn read_timeout() {
        let t = [rd(0x40, &[0]), rd(0x40, &[0]), rd(0x40, &[0]), rd(0x40, &[0])];
        let mut m = Rrh62000Minimal::new(I2cMock::new(&t), NoopDelay, A);
        assert_eq!(m.read(), Err(Error::Timeout));
        m.release().0.done();
    }

    #[test]
    fn crc_and_startup_block_rejected() {
        let mut bad = block(0, &WORDS);
        bad[36] ^= 0xFF;
        let t = [rd(0x40, &[1]), rd(0x00, &bad), rd(0x40, &[1]), rd(0x00, &[0xFF; 37])];
        let mut m = Rrh62000Minimal::new(I2cMock::new(&t), NoopDelay, A);
        assert_eq!(m.read(), Err(Error::Crc));
        assert_eq!(m.read(), Err(Error::Invalid));
        m.release().0.done();
    }

    #[test]
    fn bus_error() {
        let t = [rd(0x75, &[0, 0]).with_error(ErrorKind::Other)];
        let mut m = Rrh62000Minimal::new(I2cMock::new(&t), NoopDelay, A);
        assert_eq!(m.init(), Err(Error::Bus(ErrorKind::Other)));
        m.release().0.done();
    }

    #[test]
    fn full_block_status_particles() {
        let mut neg = [0u16; 17];
        neg[11] = (-512i16) as u16;
        let t = [
            rd(0x40, &[1]),
            rd(0x00, &block(5, &WORDS)),
            rd(0x00, &block(2, &neg)),
            rd(0x00, &block(0, &WORDS)),
            rd(0x40, &[1]),
            rd(0x00, &block(0, &neg)),
        ];
        let mut f = Rrh62000Full::new(I2cMock::new(&t), NoopDelay, A);
        let d = f.read_all().unwrap();
        assert_eq!(d.status, Status { raw: 5, high_concentration: true, dust_accumulation: false, fan_speed: true, fan: false });
        assert!(near(d.particles.nc_0_3, 123.4) && near(d.particles.nc_4, 2.0));
        assert!(near(d.particles.pm1_smoke_ug_m3, 6.1) && near(d.particles.pm10_smoke_ug_m3, 23.0));
        let s = f.read_status().unwrap();
        assert!(s.dust_accumulation && !s.high_concentration && s.raw == 2);
        let p = f.read_particles().unwrap();
        assert!(near(p.pm2_5_smoke_ug_m3, 14.0) && near(p.nc_1, 50.0));
        assert!(near(f.read_all().unwrap().temperature_c, -5.12));
        f.release().0.done();
    }

    #[test]
    fn commands_and_settings() {
        let t = [
            rd(0x40, &[0]),
            T::write(A, vec![0x50, 0x00]),
            T::write(A, vec![0x50, 0x80]),
            T::write(A, vec![0x51, 0x01]),
            rd(0x74, &[1]),
            T::write(A, vec![0x52, 0x81]).with_error(ErrorKind::Other),
            T::write(A, vec![0x53, 30]),
            rd(0x53, &[30]),
            T::write(A, vec![0x63, 70]),
            rd(0x63, &[70]),
            T::write(A, vec![0x5A, 0x0B]),
            T::write(A, vec![0x5B, 0x40]),
            rd(0x5A, &[0x0B]),
            rd(0x5B, &[0x40]),
            T::write(A, vec![0x5C, 20]),
            rd(0x5C, &[20]),
            rd(0x71, &[0x00, 0x01, 0xE2, 0x40]),
            rd(0x72, &[1, 2, 3, 4, 5, 6]),
            rd(0x73, &[3, 1, 4]),
            rd(0x75, &[2, 7]),
        ];
        let mut f = Rrh62000Full::new(I2cMock::new(&t), NoopDelay, A);
        assert!(!f.data_ready().unwrap());
        f.sleep().unwrap();
        f.wake().unwrap();
        f.start_cleaning().unwrap();
        assert!(f.cleaning_done().unwrap());
        f.soft_reset().unwrap();
        f.set_moving_average(30).unwrap();
        assert_eq!(f.get_moving_average().unwrap(), 30);
        f.set_fan_speed(70).unwrap();
        assert_eq!(f.get_fan_speed().unwrap(), 70);
        f.set_cleaning_interval(86400).unwrap();
        assert_eq!(f.get_cleaning_interval().unwrap(), 86400);
        f.set_cleaning_time(20).unwrap();
        assert_eq!(f.get_cleaning_time().unwrap(), 20);
        assert_eq!(f.read_mox_resistance().unwrap(), 123456);
        assert_eq!(f.unique_id().unwrap(), [1, 2, 3, 4, 5, 6]);
        assert_eq!(f.algorithm_version().unwrap(), (3, 1, 4));
        assert_eq!(f.firmware_version().unwrap(), (2, 7));
        assert_eq!(f.set_moving_average(0), Err(Error::InvalidArgument));
        assert_eq!(f.set_moving_average(61), Err(Error::InvalidArgument));
        assert_eq!(f.set_fan_speed(59), Err(Error::InvalidArgument));
        assert_eq!(f.set_fan_speed(101), Err(Error::InvalidArgument));
        assert_eq!(f.set_cleaning_interval(31), Err(Error::InvalidArgument));
        assert_eq!(f.set_cleaning_interval(1_814_430), Err(Error::InvalidArgument));
        assert_eq!(f.set_cleaning_time(61), Err(Error::InvalidArgument));
        f.release().0.done();
    }
}
