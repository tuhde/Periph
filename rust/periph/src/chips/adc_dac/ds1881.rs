//! DS1881 — dual nonvolatile audio log-taper digital potentiometer (Maxim).
//!
//! Two 45 kΩ potentiometers whose wiper positions are steps in an
//! attenuation table. Option 1 gives 64 positions (1 dB per step, 0 to −62 dB,
//! position 63 = mute); Option 2 (DS1808 compatible) gives 34 positions
//! (12 × 1 dB, 12 × 2 dB, 8 × 3 dB, position 33 = mute). The 7-bit I²C
//! address is `0x28` (A2 = A1 = A0 = 0) to `0x2F`.
//!
//! The chip has no register map: a write is one or more *command bytes*
//! (`00pppppp` wiper 0, `01pppppp` wiper 1, `10xxxVZC` configuration) and a
//! read returns wiper 0, wiper 1 and the configuration register round-robin.
//! The driver therefore uses plain `embedded-hal` I²C `write` / `read` calls,
//! not register access. Wipers and configuration live in EEPROM when
//! V/NV = 0 (50 000 writes); the drivers default to volatile operation. The
//! CE pin is active low and is left to the application.
//!
//! After a write that starts an EEPROM cycle the chip NACKs its address for up
//! to 10 ms; the driver acknowledge-polls by retrying a one-byte read in a
//! bounded loop before it returns, so no delay provider is needed except for
//! [`DS1881Minimal::init`] (1 ms power-up) and [`DS1881Full::wait_ready`].
//!
//! See `specs/adc_dac/ds1881.md`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// 7-bit I²C address with A2 = A1 = A0 = 0 (`0x28`–`0x2F` with the address pins).
pub const DS1881_I2C_ADDRESS: u8 = 0x28;

const CMD_WIPER1: u8 = 0x40;
const CMD_CONFIG: u8 = 0x80;
const CFG_VOLATILE: u8 = 0x04;
const CFG_ZC: u8 = 0x02;
const CFG_OPTION2: u8 = 0x01;
const CFG_MASK: u8 = 0x07;
/// Volatile, zero-crossing on, Option 1.
const CFG_DEFAULT: u8 = CFG_VOLATILE | CFG_ZC;
/// Attenuation reported for the mute position.
const MUTE_DB: f32 = 80.0;
/// Upper bound on acknowledge-polling attempts (a NACKed address costs ≥ 25 µs).
const POLL_ATTEMPTS: u32 = 5000;

/// Errors returned by the DS1881 drivers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ds1881Error<E> {
    /// The underlying I²C bus failed.
    I2c(E),
    /// `channel` is not 0 or 1, or `option` is not 1 or 2.
    InvalidArgument,
    /// The device did not acknowledge within the polling timeout (EEPROM write still running).
    Timeout,
}

impl<E> From<E> for Ds1881Error<E> {
    fn from(e: E) -> Self {
        Ds1881Error::I2c(e)
    }
}

/// Decoded configuration register, as returned by [`DS1881Full::get_config`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Ds1881Config {
    /// 1 (63 positions + mute, 1 dB steps) or 2 (33 positions + mute).
    pub option: u8,
    /// Zero-crossing detection enabled.
    pub zero_crossing: bool,
    /// Wiper writes are stored in EEPROM.
    pub nonvolatile: bool,
    /// Raw configuration byte as read.
    pub raw: u8,
}

fn mute_position(option: u8) -> u8 {
    if option == 1 { 63 } else { 33 }
}

fn position_to_db(position: u8, option: u8) -> f32 {
    if position >= mute_position(option) {
        return MUTE_DB;
    }
    if option == 1 || position <= 12 {
        return position as f32;
    }
    if position <= 24 {
        return (12 + 2 * (position as i32 - 12)) as f32;
    }
    (36 + 3 * (position as i32 - 24)) as f32
}

/// Nearest non-mute position for `db`, clamped to the table.
fn nearest_position(db: f32, option: u8) -> u8 {
    if db <= 0.0 {
        return 0;
    }
    if option == 1 {
        let p = (db + 0.5) as i32;
        return if p > 62 { 62 } else { p as u8 };
    }
    let mut best = 0u8;
    let mut best_err = db;
    for p in 1..=32u8 {
        let mut err = position_to_db(p, 2) - db;
        if err < 0.0 {
            err = -err;
        }
        if err < best_err {
            best = p;
            best_err = err;
        }
    }
    best
}

fn check_channel<E>(channel: u8) -> Result<(), Ds1881Error<E>> {
    if channel > 1 {
        Err(Ds1881Error::InvalidArgument)
    } else {
        Ok(())
    }
}

/// DS1881 — minimal interface: set and read back the attenuation of the two
/// channels in dB (Option 1, volatile wipers, zero-crossing on).
pub struct DS1881Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    config: u8,
    option: u8,
}

impl<I2C: I2c> DS1881Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once
    /// after power-up). `addr` is `0x28`–`0x2F` ([`DS1881_I2C_ADDRESS`]).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, config: CFG_DEFAULT, option: 1 }
    }

    /// Run the initialization sequence: wait the 1 ms power-up time, read the
    /// three bytes and rewrite the configuration (volatile, zero-crossing on,
    /// Option 1) only if it differs, waiting for the EEPROM write that this
    /// triggers. The wipers are not touched.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Ds1881Error<I2C::Error>> {
        delay.delay_ms(1);
        let mut raw = [0u8; 3];
        self.i2c.read(self.addr, &mut raw)?;
        if raw[2] & CFG_MASK != CFG_DEFAULT {
            self.i2c.write(self.addr, &[CMD_CONFIG | CFG_DEFAULT])?;
            self.wait_spin()?;
        }
        self.config = CFG_DEFAULT;
        self.option = 1;
        Ok(())
    }

    fn wait_spin(&mut self) -> Result<(), Ds1881Error<I2C::Error>> {
        let mut buf = [0u8; 1];
        let mut last = None;
        for _ in 0..POLL_ATTEMPTS {
            match self.i2c.read(self.addr, &mut buf) {
                Ok(()) => return Ok(()),
                Err(e) => last = Some(e),
            }
        }
        match last {
            Some(_) => Err(Ds1881Error::Timeout),
            None => Ok(()),
        }
    }

    fn write_commands(&mut self, data: &[u8]) -> Result<(), Ds1881Error<I2C::Error>> {
        self.i2c.write(self.addr, data)?;
        if self.config & CFG_VOLATILE == 0 {
            self.wait_spin()?;
        }
        Ok(())
    }

    fn write_position(&mut self, channel: u8, position: i32) -> Result<(), Ds1881Error<I2C::Error>> {
        check_channel(channel)?;
        let mute = mute_position(self.option) as i32;
        let position = if position < 0 { 0 } else if position > mute { mute } else { position };
        self.write_commands(&[(channel << 6) | position as u8])
    }

    fn read_all_raw(&mut self) -> Result<[u8; 3], Ds1881Error<I2C::Error>> {
        let mut raw = [0u8; 3];
        self.i2c.read(self.addr, &mut raw)?;
        Ok([raw[0] & 0x3F, raw[1] & 0x3F, raw[2]])
    }

    fn db_to_pos(&self, db: f32) -> u8 {
        nearest_position(db, self.option)
    }

    /// Set a channel's attenuation to the nearest position. `db` is positive
    /// = quieter; it is rounded to the nearest step and clamped to 0…62 dB
    /// (Option 1). `channel` must be 0 or 1.
    pub fn set_attenuation(&mut self, channel: u8, db: f32) -> Result<(), Ds1881Error<I2C::Error>> {
        check_channel(channel)?;
        let p = self.db_to_pos(db);
        self.write_position(channel, p as i32)
    }

    /// Read a channel's attenuation in dB (`80.0` for mute).
    pub fn get_attenuation(&mut self, channel: u8) -> Result<f32, Ds1881Error<I2C::Error>> {
        let p = self.get_position(channel)?;
        Ok(position_to_db(p, self.option))
    }

    /// Mute a channel (position 63 in Option 1).
    pub fn mute(&mut self, channel: u8) -> Result<(), Ds1881Error<I2C::Error>> {
        let m = mute_position(self.option) as i32;
        self.write_position(channel, m)
    }

    /// Write a raw wiper position (0–63, 63 = mute; clamped).
    pub fn set_position(&mut self, channel: u8, position: i32) -> Result<(), Ds1881Error<I2C::Error>> {
        self.write_position(channel, position)
    }

    /// Read a wiper position (low 6 bits) with the three-byte round-robin read.
    pub fn get_position(&mut self, channel: u8) -> Result<u8, Ds1881Error<I2C::Error>> {
        check_channel(channel)?;
        Ok(self.read_all_raw()?[channel as usize])
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// DS1881 — full interface: extends [`DS1881Minimal`] with stereo writes,
/// Option 2, zero-crossing and EEPROM control, and table helpers. The driver
/// caches the configuration; [`DS1881Minimal::init`] and
/// [`DS1881Full::get_config`] refresh it from the chip.
pub struct DS1881Full<I2C> {
    inner: DS1881Minimal<I2C>,
}

impl<I2C: I2c> DS1881Full<I2C> {
    /// Construct the driver; same parameters as [`DS1881Minimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: DS1881Minimal::new(i2c, addr) }
    }

    /// Initialization sequence. Delegates to [`DS1881Minimal::init`].
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Ds1881Error<I2C::Error>> {
        self.inner.init(delay)
    }

    fn db_to_pos(&self, db: f32) -> u8 {
        if db >= 70.0 {
            return mute_position(self.inner.option);
        }
        nearest_position(db, self.inner.option)
    }

    /// Set a channel's attenuation to the nearest position of the current
    /// option (≥ 70 dB maps to mute).
    pub fn set_attenuation(&mut self, channel: u8, db: f32) -> Result<(), Ds1881Error<I2C::Error>> {
        check_channel(channel)?;
        let p = self.db_to_pos(db);
        self.inner.write_position(channel, p as i32)
    }

    /// Read a channel's attenuation in dB. Delegates to [`DS1881Minimal::get_attenuation`].
    pub fn get_attenuation(&mut self, channel: u8) -> Result<f32, Ds1881Error<I2C::Error>> {
        self.inner.get_attenuation(channel)
    }

    /// Mute a channel. Delegates to [`DS1881Minimal::mute`].
    pub fn mute(&mut self, channel: u8) -> Result<(), Ds1881Error<I2C::Error>> {
        self.inner.mute(channel)
    }

    /// Write a raw wiper position. Delegates to [`DS1881Minimal::set_position`].
    pub fn set_position(&mut self, channel: u8, position: i32) -> Result<(), Ds1881Error<I2C::Error>> {
        self.inner.set_position(channel, position)
    }

    /// Read a wiper position. Delegates to [`DS1881Minimal::get_position`].
    pub fn get_position(&mut self, channel: u8) -> Result<u8, Ds1881Error<I2C::Error>> {
        self.inner.get_position(channel)
    }

    /// Write both wipers in one transaction (no STOP between them); both
    /// positions are clamped to the current option.
    pub fn set_both(&mut self, position0: i32, position1: i32) -> Result<(), Ds1881Error<I2C::Error>> {
        let mute = mute_position(self.inner.option) as i32;
        let clamp = |p: i32| if p < 0 { 0 } else if p > mute { mute } else { p } as u8;
        let data = [clamp(position0), CMD_WIPER1 | clamp(position1)];
        self.inner.write_commands(&data)
    }

    /// Set both channels' attenuation (stereo volume); `db1` defaults to `db0`.
    pub fn set_attenuation_both(&mut self, db0: f32, db1: Option<f32>) -> Result<(), Ds1881Error<I2C::Error>> {
        let p0 = self.db_to_pos(db0);
        let p1 = self.db_to_pos(db1.unwrap_or(db0));
        self.set_both(p0 as i32, p1 as i32)
    }

    /// Write the configuration byte if it differs from the cached value. The
    /// configuration is stored in EEPROM, so a write waits for acknowledge
    /// polling. The wipers are not rewritten — positions keep their numeric
    /// value when the option changes. `option` is 1 or 2.
    pub fn configure(&mut self, option: u8, zero_crossing: bool, nonvolatile: bool) -> Result<(), Ds1881Error<I2C::Error>> {
        if option != 1 && option != 2 {
            return Err(Ds1881Error::InvalidArgument);
        }
        let mut config = 0u8;
        if !nonvolatile {
            config |= CFG_VOLATILE;
        }
        if zero_crossing {
            config |= CFG_ZC;
        }
        if option == 2 {
            config |= CFG_OPTION2;
        }
        if config != self.inner.config {
            self.inner.i2c.write(self.inner.addr, &[CMD_CONFIG | config])?;
            self.inner.wait_spin()?;
        }
        self.inner.config = config;
        self.inner.option = option;
        Ok(())
    }

    /// Read the configuration register (fresh read; refreshes the cache).
    pub fn get_config(&mut self) -> Result<Ds1881Config, Ds1881Error<I2C::Error>> {
        let raw = self.inner.read_all_raw()?[2];
        self.inner.config = raw & CFG_MASK;
        self.inner.option = if raw & CFG_OPTION2 != 0 { 2 } else { 1 };
        Ok(Ds1881Config {
            option: self.inner.option,
            zero_crossing: raw & CFG_ZC != 0,
            nonvolatile: raw & CFG_VOLATILE == 0,
            raw,
        })
    }

    /// Switch between Option 1 and 2 (positions are not converted).
    pub fn set_option(&mut self, option: u8) -> Result<(), Ds1881Error<I2C::Error>> {
        let c = self.inner.config;
        self.configure(option, c & CFG_ZC != 0, c & CFG_VOLATILE == 0)
    }

    /// Enable or disable zero-crossing detection.
    pub fn set_zero_crossing(&mut self, enabled: bool) -> Result<(), Ds1881Error<I2C::Error>> {
        let c = self.inner.config;
        let option = self.inner.option;
        self.configure(option, enabled, c & CFG_VOLATILE == 0)
    }

    /// Select where wiper writes go (V/NV bit): `true` stores subsequent
    /// wiper writes in EEPROM (rated for only 50 000 writes) and restores
    /// them at power-up.
    pub fn set_nonvolatile(&mut self, enabled: bool) -> Result<(), Ds1881Error<I2C::Error>> {
        let c = self.inner.config;
        let option = self.inner.option;
        self.configure(option, c & CFG_ZC != 0, enabled)
    }

    /// Persist the current wiper positions to EEPROM: read both positions,
    /// switch to nonvolatile mode if needed, rewrite both wiper bytes in one
    /// transaction, wait by acknowledge polling and restore volatile mode if
    /// it was active. The EEPROM is rated for only 50 000 writes.
    pub fn store(&mut self) -> Result<(), Ds1881Error<I2C::Error>> {
        let raw = self.inner.read_all_raw()?;
        let was_nv = self.inner.config & CFG_VOLATILE == 0;
        let zc = self.inner.config & CFG_ZC != 0;
        let option = self.inner.option;
        if !was_nv {
            self.configure(option, zc, true)?;
        }
        self.inner.write_commands(&[raw[0], CMD_WIPER1 | raw[1]])?;
        if !was_nv {
            self.configure(option, zc, false)?;
        }
        Ok(())
    }

    /// Acknowledge-poll until the device answers (it NACKs while an EEPROM
    /// write is in progress), waiting 1 ms between attempts; returns
    /// [`Ds1881Error::Timeout`] after `timeout_ms` (default 50).
    pub fn wait_ready<D: DelayNs>(&mut self, delay: &mut D, timeout_ms: u32) -> Result<(), Ds1881Error<I2C::Error>> {
        let mut buf = [0u8; 1];
        for _ in 0..=timeout_ms {
            if self.inner.i2c.read(self.inner.addr, &mut buf).is_ok() {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Ds1881Error::Timeout)
    }

    /// Read wiper 0, wiper 1 and the configuration byte in one transaction:
    /// `(position0, position1, config_byte)`.
    pub fn read_all(&mut self) -> Result<(u8, u8, u8), Ds1881Error<I2C::Error>> {
        let r = self.inner.read_all_raw()?;
        Ok((r[0], r[1], r[2]))
    }

    /// Attenuation of a position in the current option (`80.0` for mute and above).
    pub fn position_to_db(&self, position: u8) -> f32 {
        position_to_db(position, self.inner.option)
    }

    /// Nearest position for an attenuation in the current option; ≥ 70 dB
    /// maps to mute, negative to 0.
    pub fn db_to_position(&self, db: f32) -> u8 {
        self.db_to_pos(db)
    }

    /// Last non-mute position of the current option: 62 (Option 1) or 32 (Option 2).
    pub fn max_position(&self) -> u8 {
        mute_position(self.inner.option) - 1
    }

    /// Volatile, zero-crossing on, Option 1 and both wipers to mute, sent as
    /// one transaction (configuration byte, then both wiper bytes); waits for
    /// the configuration EEPROM write.
    pub fn reset(&mut self) -> Result<(), Ds1881Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[CMD_CONFIG | CFG_DEFAULT, 63, CMD_WIPER1 | 63])?;
        self.inner.config = CFG_DEFAULT;
        self.inner.option = 1;
        self.inner.wait_spin()
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = DS1881_I2C_ADDRESS;

    #[test]
    fn init_writes_config_only_when_different() {
        let t = vec![
            T::read(ADDR, vec![0, 0, 0x00]),
            T::write(ADDR, vec![0x86]),
            T::read(ADDR, vec![0]), // acknowledge poll
            T::read(ADDR, vec![10, 20, 0xB6]), // low bits already 0b110: nothing sent
        ];
        let mut i2c = I2cMock::new(&t);
        let mut pot = DS1881Minimal::new(i2c.clone(), ADDR);
        pot.init(&mut NoopDelay::new()).unwrap();
        pot.init(&mut NoopDelay::new()).unwrap();
        i2c.done();
    }

    #[test]
    fn minimal_attenuation_and_positions() {
        let t = vec![
            T::write(ADDR, vec![20]),
            T::write(ADDR, vec![0x40 | 20]),
            T::write(ADDR, vec![0x40 | 21]),
            T::write(ADDR, vec![62]),
            T::write(ADDR, vec![0]),
            T::write(ADDR, vec![0x7F]),
            T::write(ADDR, vec![63]),
            T::write(ADDR, vec![0]),
            T::read(ADDR, vec![0xC5, 0x3F, 0x86]),
            T::read(ADDR, vec![0xC5, 0x3F, 0x86]),
            T::read(ADDR, vec![0xC5, 0x3F, 0x86]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut pot = DS1881Minimal::new(i2c.clone(), ADDR);
        pot.set_attenuation(0, 20.0).unwrap();
        pot.set_attenuation(1, 20.4).unwrap();
        pot.set_attenuation(1, 20.6).unwrap();
        pot.set_attenuation(0, 100.0).unwrap();
        pot.set_attenuation(0, -5.0).unwrap();
        pot.mute(1).unwrap();
        pot.set_position(0, 99).unwrap();
        pot.set_position(0, -3).unwrap();
        assert_eq!(pot.get_position(0).unwrap(), 5);
        assert_eq!(pot.get_position(1).unwrap(), 63);
        assert_eq!(pot.get_attenuation(1).unwrap(), 80.0);
        assert_eq!(pot.set_attenuation(2, 0.0), Err(Ds1881Error::InvalidArgument));
        assert_eq!(pot.get_position(2), Err(Ds1881Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn full_stereo_and_tables() {
        let t = vec![
            T::write(ADDR, vec![10, 0x40 | 63]),
            T::write(ADDR, vec![30, 0x40 | 30]),
            T::write(ADDR, vec![5, 0x7F]),
            T::write(ADDR, vec![0x87]), // configure(2, true, false)
            T::read(ADDR, vec![0]),     // acknowledge poll
            T::write(ADDR, vec![33, 0x40 | 33]),
            T::write(ADDR, vec![33]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut full = DS1881Full::new(i2c.clone(), ADDR);
        full.set_both(10, 99).unwrap();
        full.set_attenuation_both(30.0, None).unwrap();
        full.set_attenuation_both(5.0, Some(70.0)).unwrap();
        assert_eq!(full.position_to_db(0), 0.0);
        assert_eq!(full.position_to_db(62), 62.0);
        assert_eq!(full.position_to_db(63), 80.0);
        assert_eq!(full.max_position(), 62);
        assert_eq!(full.db_to_position(12.4), 12);
        assert_eq!(full.db_to_position(75.0), 63);
        full.configure(2, true, false).unwrap();
        full.configure(2, true, false).unwrap(); // unchanged: no traffic
        let table: Vec<f32> = [0u8, 12, 13, 24, 25, 32, 33].iter().map(|&p| full.position_to_db(p)).collect();
        assert_eq!(table, vec![0.0, 12.0, 14.0, 36.0, 39.0, 60.0, 80.0]);
        assert_eq!(full.max_position(), 32);
        assert_eq!(full.db_to_position(14.0), 13);
        assert_eq!(full.db_to_position(36.0), 24);
        assert_eq!(full.db_to_position(60.0), 32);
        assert_eq!(full.db_to_position(37.0), 24);
        assert_eq!(full.db_to_position(38.0), 25);
        assert_eq!(full.db_to_position(70.0), 33);
        full.set_both(40, 40).unwrap();
        full.mute(0).unwrap();
        assert_eq!(full.configure(3, true, false), Err(Ds1881Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn config_bits_and_nv_polling() {
        let t = vec![
            T::write(ADDR, vec![0x84]), // zero-crossing off
            T::read(ADDR, vec![0]),
            T::write(ADDR, vec![0x80]), // nonvolatile on
            T::read(ADDR, vec![0]),
            T::write(ADDR, vec![0x81]), // option 2 keeps the other bits
            T::read(ADDR, vec![0]),
            T::write(ADDR, vec![1, 0x42]), // NV wiper write polls: one NACK, then ACK
            T::read(ADDR, vec![0]).with_error(ErrorKind::Other),
            T::read(ADDR, vec![0]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut full = DS1881Full::new(i2c.clone(), ADDR);
        full.set_zero_crossing(false).unwrap();
        full.set_nonvolatile(true).unwrap();
        full.set_option(2).unwrap();
        full.set_both(1, 2).unwrap();
        i2c.done();
    }

    #[test]
    fn get_config_read_all_and_wait_ready() {
        let t = vec![
            T::read(ADDR, vec![0, 0, 0x87]),
            T::read(ADDR, vec![1, 2, 0x82]),
            T::read(ADDR, vec![0x41, 0x02, 0x86]),
            T::read(ADDR, vec![0]).with_error(ErrorKind::Other),
            T::read(ADDR, vec![0]).with_error(ErrorKind::Other),
            T::read(ADDR, vec![0]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut full = DS1881Full::new(i2c.clone(), ADDR);
        let cfg = full.get_config().unwrap();
        assert_eq!(cfg, Ds1881Config { option: 2, zero_crossing: true, nonvolatile: false, raw: 0x87 });
        assert_eq!(full.max_position(), 32);
        let cfg = full.get_config().unwrap();
        assert!(cfg.nonvolatile && cfg.option == 1 && cfg.zero_crossing);
        assert_eq!(full.read_all().unwrap(), (1, 2, 0x86));
        full.wait_ready(&mut NoopDelay::new(), 50).unwrap();
        i2c.done();
    }

    #[test]
    fn store_and_reset() {
        let t = vec![
            // store from volatile: read, NV config, both wipers, back to volatile
            T::read(ADDR, vec![12, 34, 0x86]),
            T::write(ADDR, vec![0x82]),
            T::read(ADDR, vec![0]),
            T::write(ADDR, vec![12, 0x40 | 34]),
            T::read(ADDR, vec![0]),
            T::write(ADDR, vec![0x86]),
            T::read(ADDR, vec![0]),
            // reset
            T::write(ADDR, vec![0x86, 0x3F, 0x7F]),
            T::read(ADDR, vec![0]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut full = DS1881Full::new(i2c.clone(), ADDR);
        full.store().unwrap();
        full.reset().unwrap();
        assert_eq!(full.max_position(), 62);
        i2c.done();
    }
}
