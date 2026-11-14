//! AD5252 — Dual 256-position I²C digital potentiometer with EEMEM (Analog Devices).
//!
//! Two independent 3-terminal potentiometers (A, W, B) usable as voltage
//! dividers or 2-terminal rheostats. End-to-end resistance `RAB` is ordered
//! as 1, 10, 50 or 100 kΩ. Each wiper (RDAC) can be stored in nonvolatile
//! memory (EEMEM) and is restored at power-on; until a value is stored the
//! wipers power up at midscale (`128`). The 7-bit I²C address is
//! `0x2C`–`0x2F` (pins AD1, AD0).
//!
//! The chip has no register map: the instruction byte works as a one-byte
//! register pointer (`0x01` RDAC1 = channel 0, `0x03` RDAC3 = channel 1) and
//! is used as the "register" of [`register::write_register`] /
//! [`register::read_register`]. Quick commands are single-byte writes. A write
//! to EEMEM (store, user EEMEM write, reset) starts a ≈ 26 ms cycle during
//! which the chip does not acknowledge; those methods take a [`DelayNs`] and
//! poll with the NOP quick command until the chip answers (timeout 100 ms).
//!
//! Resistances follow `R_WB = D/256 · RAB + RW` and
//! `R_WA = (256 − D)/256 · RAB + RW` — see `specs/adc_dac/ad5252.md`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const RDAC: [u8; 2] = [0x01, 0x03];
const EEMEM_FLAG: u8 = 0x20;
const TOL_INT: [u8; 2] = [0x3A, 0x3E];

const Q_NOP: u8 = 0x80;
const Q_RESTORE: u8 = 0x88;
const Q_STORE: u8 = 0x90;
const Q_HALVE: u8 = 0x98;
const Q_DEC_STEP: u8 = 0xA8;
const Q_INC_6DB: u8 = 0xC0;
const Q_INC_STEP: u8 = 0xD0;
const Q_DEC_6DB_ALL: u8 = 0xA0;
const Q_DEC_STEP_ALL: u8 = 0xB0;
const Q_RESET: u8 = 0xB8;
const Q_INC_6DB_ALL: u8 = 0xC8;
const Q_INC_STEP_ALL: u8 = 0xD8;

const POLL_STEP_MS: u32 = 2;
const POLL_ATTEMPTS: u32 = 51;

/// Default 7-bit I²C address (AD1 = AD0 = GND); `0x2C`–`0x2F` are possible.
pub const AD5252_I2C_ADDRESS: u8 = 0x2C;

/// Errors from the AD5252 drivers.
#[derive(Debug)]
pub enum AD5252Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// `channel` was not 0 or 1 (or `index` outside 4–15 for user EEMEM).
    InvalidChannel,
    /// The chip did not acknowledge again within 100 ms after an EEMEM write.
    Timeout,
}

impl<E> From<E> for AD5252Error<E> {
    fn from(e: E) -> Self {
        AD5252Error::Bus(e)
    }
}

fn check_channel<E>(channel: u8) -> Result<usize, AD5252Error<E>> {
    if channel > 1 {
        Err(AD5252Error::InvalidChannel)
    } else {
        Ok(channel as usize)
    }
}

fn clamp_code(code: i32) -> u8 {
    if code < 0 {
        0
    } else if code > 255 {
        255
    } else {
        code as u8
    }
}

/// AD5252 — minimal interface: set and read back the wiper position or the
/// wiper-to-B resistance of either channel.
pub struct AD5252Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    r_ab: f32,
    r_w: f32,
}

impl<I2C: I2c> AD5252Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (the chip has no
    /// identity register and needs no initialisation).
    ///
    /// `addr` is `0x2C`–`0x2F` ([`AD5252_I2C_ADDRESS`]); `r_ab` is the
    /// end-to-end resistance in Ω (default part: 10 000.0) and `r_wiper` the
    /// wiper resistance RW in Ω (typical 75.0).
    pub fn new(i2c: I2C, addr: u8, r_ab: f32, r_wiper: f32) -> Self {
        Self { i2c, addr, r_ab, r_w: r_wiper }
    }

    fn write_code(&mut self, channel: u8, code: i32) -> Result<(), AD5252Error<I2C::Error>> {
        let ch = check_channel(channel)?;
        let code = clamp_code(code);
        register::write_register(&mut self.i2c, self.addr, RDAC[ch].into(), 1, &[code])?;
        Ok(())
    }

    fn read_code(&mut self, channel: u8) -> Result<u8, AD5252Error<I2C::Error>> {
        let ch = check_channel(channel)?;
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, RDAC[ch].into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    /// Set the wiper position of `channel` (0 or 1); `code` is clamped to 0–255.
    pub fn set_wiper(&mut self, channel: u8, code: i32) -> Result<(), AD5252Error<I2C::Error>> {
        self.write_code(channel, code)
    }

    /// Read back the wiper position (0–255): sends the RDAC instruction byte
    /// alone, then reads one byte.
    pub fn get_wiper(&mut self, channel: u8) -> Result<u8, AD5252Error<I2C::Error>> {
        self.read_code(channel)
    }

    /// Set the W–B resistance (Ω) to the nearest reachable code.
    pub fn set_resistance(&mut self, channel: u8, ohms: f32) -> Result<(), AD5252Error<I2C::Error>> {
        let code = ((ohms - self.r_w) / self.r_ab * 256.0 + 0.5) as i32;
        self.write_code(channel, code)
    }

    /// W–B resistance (Ω) for the current wiper code: `D/256·RAB + RW`.
    pub fn get_resistance(&mut self, channel: u8) -> Result<f32, AD5252Error<I2C::Error>> {
        let d = self.read_code(channel)? as f32;
        Ok(d / 256.0 * self.r_ab + self.r_w)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// AD5252 — full interface: extends [`AD5252Minimal`] with ratio and W–A
/// helpers, quick commands (±1 step, ±6 dB), EEMEM store/restore/reset, user
/// EEMEM and the factory RAB tolerance. Channel arguments of the quick
/// commands are `Option<u8>`: `None` addresses both channels.
pub struct AD5252Full<I2C> {
    inner: AD5252Minimal<I2C>,
}

impl<I2C: I2c> AD5252Full<I2C> {
    /// Construct the driver; same parameters as [`AD5252Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, r_ab: f32, r_wiper: f32) -> Self {
        Self { inner: AD5252Minimal::new(i2c, addr, r_ab, r_wiper) }
    }

    /// Set the wiper position. Delegates to [`AD5252Minimal::set_wiper`].
    pub fn set_wiper(&mut self, channel: u8, code: i32) -> Result<(), AD5252Error<I2C::Error>> {
        self.inner.set_wiper(channel, code)
    }

    /// Read back the wiper position. Delegates to [`AD5252Minimal::get_wiper`].
    pub fn get_wiper(&mut self, channel: u8) -> Result<u8, AD5252Error<I2C::Error>> {
        self.inner.get_wiper(channel)
    }

    /// Set the W–B resistance. Delegates to [`AD5252Minimal::set_resistance`].
    pub fn set_resistance(&mut self, channel: u8, ohms: f32) -> Result<(), AD5252Error<I2C::Error>> {
        self.inner.set_resistance(channel, ohms)
    }

    /// W–B resistance. Delegates to [`AD5252Minimal::get_resistance`].
    pub fn get_resistance(&mut self, channel: u8) -> Result<f32, AD5252Error<I2C::Error>> {
        self.inner.get_resistance(channel)
    }

    fn quick_byte(&mut self, byte: u8) -> Result<(), AD5252Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[byte])?;
        Ok(())
    }

    fn quick(&mut self, base: u8, base_all: u8, channel: Option<u8>) -> Result<(), AD5252Error<I2C::Error>> {
        match channel {
            None => self.quick_byte(base_all),
            Some(c) => {
                let ch = check_channel(c)?;
                self.quick_byte(base | RDAC[ch])
            }
        }
    }

    fn wait_ready<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), AD5252Error<I2C::Error>> {
        for _ in 0..POLL_ATTEMPTS {
            if self.inner.i2c.write(self.inner.addr, &[Q_NOP]).is_ok() {
                return Ok(());
            }
            delay.delay_ms(POLL_STEP_MS);
        }
        Err(AD5252Error::Timeout)
    }

    /// Write both wipers in one consecutive-write transaction.
    pub fn set_both(&mut self, code0: i32, code1: i32) -> Result<(), AD5252Error<I2C::Error>> {
        let data = [clamp_code(code0), clamp_code(code1)];
        register::write_register(&mut self.inner.i2c, self.inner.addr, RDAC[0].into(), 1, &data)?;
        Ok(())
    }

    /// Set the wiper by divider ratio V(W−B)/V(A−B) (0.0–1.0): code is
    /// `round(ratio × 256)` clamped to 255.
    pub fn set_ratio(&mut self, channel: u8, ratio: f32) -> Result<(), AD5252Error<I2C::Error>> {
        self.inner.write_code(channel, (ratio * 256.0 + 0.5) as i32)
    }

    /// Divider ratio `code / 256`.
    pub fn get_ratio(&mut self, channel: u8) -> Result<f32, AD5252Error<I2C::Error>> {
        Ok(self.inner.read_code(channel)? as f32 / 256.0)
    }

    /// Set the W–A resistance (rheostat mode, B open) to the nearest code.
    pub fn set_resistance_wa(&mut self, channel: u8, ohms: f32) -> Result<(), AD5252Error<I2C::Error>> {
        let steps = ((ohms - self.inner.r_w) / self.inner.r_ab * 256.0 + 0.5) as i32;
        self.inner.write_code(channel, 256 - steps)
    }

    /// W–A resistance (Ω): `(256 − D)/256·RAB + RW`.
    pub fn get_resistance_wa(&mut self, channel: u8) -> Result<f32, AD5252Error<I2C::Error>> {
        let d = self.inner.read_code(channel)? as f32;
        Ok((256.0 - d) / 256.0 * self.inner.r_ab + self.inner.r_w)
    }

    /// Increment the wiper by one step (quick command); `None` = both channels.
    pub fn increment(&mut self, channel: Option<u8>) -> Result<(), AD5252Error<I2C::Error>> {
        self.quick(Q_INC_STEP, Q_INC_STEP_ALL, channel)
    }

    /// Decrement the wiper by one step (quick command); `None` = both channels.
    pub fn decrement(&mut self, channel: Option<u8>) -> Result<(), AD5252Error<I2C::Error>> {
        self.quick(Q_DEC_STEP, Q_DEC_STEP_ALL, channel)
    }

    /// Increase by 6 dB — the code doubles (quick command); `None` = both.
    pub fn double(&mut self, channel: Option<u8>) -> Result<(), AD5252Error<I2C::Error>> {
        self.quick(Q_INC_6DB, Q_INC_6DB_ALL, channel)
    }

    /// Decrease by 6 dB — the code halves (quick command); `None` = both.
    pub fn halve(&mut self, channel: Option<u8>) -> Result<(), AD5252Error<I2C::Error>> {
        self.quick(Q_HALVE, Q_DEC_6DB_ALL, channel)
    }

    /// Store the wiper in EEMEM (restored at power-on) and wait for the
    /// ≈ 26 ms write cycle by acknowledge polling; `None` = both channels.
    pub fn store<D: DelayNs>(&mut self, channel: Option<u8>, delay: &mut D) -> Result<(), AD5252Error<I2C::Error>> {
        match channel {
            None => {
                self.quick(Q_STORE, 0, Some(0))?;
                self.wait_ready(delay)?;
                self.quick(Q_STORE, 0, Some(1))?;
            }
            Some(c) => self.quick(Q_STORE, 0, Some(c))?,
        }
        self.wait_ready(delay)
    }

    /// Restore the wiper from EEMEM, then send a NOP; `None` = both channels.
    pub fn restore(&mut self, channel: Option<u8>) -> Result<(), AD5252Error<I2C::Error>> {
        match channel {
            None => {
                self.quick(Q_RESTORE, 0, Some(0))?;
                self.quick(Q_RESTORE, 0, Some(1))?;
            }
            Some(c) => self.quick(Q_RESTORE, 0, Some(c))?,
        }
        self.nop()
    }

    /// Quick-command reset (`0xB8`): restore both wipers from EEMEM and wait
    /// for the chip to acknowledge again.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), AD5252Error<I2C::Error>> {
        self.quick_byte(Q_RESET)?;
        self.wait_ready(delay)
    }

    /// Read a user EEMEM byte (`index` 4–15).
    pub fn read_eemem(&mut self, index: u8) -> Result<u8, AD5252Error<I2C::Error>> {
        if !(4..=15).contains(&index) {
            return Err(AD5252Error::InvalidChannel);
        }
        let mut buf = [0u8; 1];
        register::read_register(&mut self.inner.i2c, self.inner.addr, (EEMEM_FLAG | index).into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    /// Write a user EEMEM byte (`index` 4–15) and wait for the write cycle.
    pub fn write_eemem<D: DelayNs>(&mut self, index: u8, value: u8, delay: &mut D) -> Result<(), AD5252Error<I2C::Error>> {
        if !(4..=15).contains(&index) {
            return Err(AD5252Error::InvalidChannel);
        }
        register::write_register(&mut self.inner.i2c, self.inner.addr, (EEMEM_FLAG | index).into(), 1, &[value])?;
        self.wait_ready(delay)
    }

    /// Factory-stored RAB tolerance of `channel` in percent (sign-magnitude
    /// integer byte plus decimal byte in units of 1/256 %).
    pub fn read_tolerance(&mut self, channel: u8) -> Result<f32, AD5252Error<I2C::Error>> {
        let ch = check_channel(channel)?;
        let mut raw = [0u8; 2];
        register::read_register(&mut self.inner.i2c, self.inner.addr, TOL_INT[ch].into(), 1, &mut raw)?;
        let value = (raw[0] & 0x7F) as f32 + raw[1] as f32 / 256.0;
        Ok(if raw[0] & 0x80 != 0 { -value } else { value })
    }

    /// Actual RAB of `channel` (Ω): `r_ab × (1 + tolerance / 100)`.
    pub fn rab_actual(&mut self, channel: u8) -> Result<f32, AD5252Error<I2C::Error>> {
        let tol = self.read_tolerance(channel)?;
        Ok(self.inner.r_ab * (1.0 + tol / 100.0))
    }

    /// Send the NOP quick command (`0x80`), returning the chip to idle.
    pub fn nop(&mut self) -> Result<(), AD5252Error<I2C::Error>> {
        self.quick_byte(Q_NOP)
    }

    /// Change the resistance parameters used by conversions (no I²C
    /// traffic); `r_wiper` of `None` leaves RW unchanged.
    pub fn set_rab(&mut self, r_ab: f32, r_wiper: Option<f32>) {
        self.inner.r_ab = r_ab;
        if let Some(rw) = r_wiper {
            self.inner.r_w = rw;
        }
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
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = AD5252_I2C_ADDRESS;

    fn q(b: u8) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![b])
    }

    #[test]
    fn minimal_wiper_and_resistance() {
        let transactions = vec![
            I2cTransaction::write(ADDR, vec![0x01, 100]),
            I2cTransaction::write(ADDR, vec![0x03, 200]),
            I2cTransaction::write(ADDR, vec![0x01, 255]),
            I2cTransaction::write(ADDR, vec![0x01, 0]),
            I2cTransaction::write_read(ADDR, vec![0x01], vec![17]),
            I2cTransaction::write_read(ADDR, vec![0x03], vec![99]),
            // set_resistance(5075 ohm) -> D = 128; clamps
            I2cTransaction::write(ADDR, vec![0x01, 128]),
            I2cTransaction::write(ADDR, vec![0x01, 0]),
            I2cTransaction::write(ADDR, vec![0x03, 255]),
            I2cTransaction::write_read(ADDR, vec![0x01], vec![128]),
            I2cTransaction::write_read(ADDR, vec![0x03], vec![0]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5252Minimal::new(i2c.clone(), ADDR, 10000.0, 75.0);
        pot.set_wiper(0, 100).expect("set 0");
        pot.set_wiper(1, 200).expect("set 1");
        pot.set_wiper(0, 300).expect("clamp hi");
        pot.set_wiper(0, -5).expect("clamp lo");
        assert!(matches!(pot.set_wiper(2, 1), Err(AD5252Error::InvalidChannel)));
        assert_eq!(pot.get_wiper(0).expect("get 0"), 17);
        assert_eq!(pot.get_wiper(1).expect("get 1"), 99);
        pot.set_resistance(0, 5075.0).expect("set R");
        pot.set_resistance(0, 0.0).expect("set R lo");
        pot.set_resistance(1, 1.0e6).expect("set R hi");
        assert!((pot.get_resistance(0).expect("R") - 5075.0).abs() < 0.01);
        assert!((pot.get_resistance(1).expect("R") - 75.0).abs() < 0.01);
        i2c.done();
    }

    #[test]
    fn full_helpers() {
        let transactions = vec![
            I2cTransaction::write(ADDR, vec![0x01, 1, 2]),
            I2cTransaction::write(ADDR, vec![0x01, 64]),
            I2cTransaction::write(ADDR, vec![0x01, 255]),
            I2cTransaction::write(ADDR, vec![0x03, 128]),
            I2cTransaction::write_read(ADDR, vec![0x03], vec![128]),
            I2cTransaction::write_read(ADDR, vec![0x03], vec![128]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5252Full::new(i2c.clone(), ADDR, 10000.0, 75.0);
        pot.set_both(1, 2).expect("both");
        pot.set_ratio(0, 0.25).expect("ratio");
        pot.set_ratio(0, 1.0).expect("ratio clamp");
        pot.set_resistance_wa(1, 5075.0).expect("wa");
        assert_eq!(pot.get_ratio(1).expect("ratio"), 0.5);
        assert!((pot.get_resistance_wa(1).expect("wa") - 5075.0).abs() < 0.01);
        i2c.done();
    }

    #[test]
    fn quick_commands() {
        let transactions = vec![
            q(0xD1), q(0xD3), q(0xD8), q(0xA9), q(0xAB), q(0xB0),
            q(0xC1), q(0xC3), q(0xC8), q(0x99), q(0x9B), q(0xA0),
            q(0x80),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5252Full::new(i2c.clone(), ADDR, 10000.0, 75.0);
        pot.increment(Some(0)).unwrap();
        pot.increment(Some(1)).unwrap();
        pot.increment(None).unwrap();
        pot.decrement(Some(0)).unwrap();
        pot.decrement(Some(1)).unwrap();
        pot.decrement(None).unwrap();
        pot.double(Some(0)).unwrap();
        pot.double(Some(1)).unwrap();
        pot.double(None).unwrap();
        pot.halve(Some(0)).unwrap();
        pot.halve(Some(1)).unwrap();
        pot.halve(None).unwrap();
        pot.nop().unwrap();
        assert!(matches!(pot.increment(Some(2)), Err(AD5252Error::InvalidChannel)));
        i2c.done();
    }

    #[test]
    fn store_restore_reset_poll() {
        let transactions = vec![
            // store(1): quick command, then ACK polling
            q(0x93), q(0x80),
            // store(0) with two NACKs before the chip answers
            q(0x91),
            q(0x80).with_error(ErrorKind::Other),
            q(0x80).with_error(ErrorKind::Other),
            q(0x80),
            // store(None)
            q(0x91), q(0x80), q(0x93), q(0x80),
            // restore(0), restore(None)
            q(0x89), q(0x80),
            q(0x89), q(0x8B), q(0x80),
            // reset
            q(0xB8), q(0x80),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5252Full::new(i2c.clone(), ADDR, 10000.0, 75.0);
        let mut d = NoopDelay::new();
        pot.store(Some(1), &mut d).unwrap();
        pot.store(Some(0), &mut d).unwrap();
        pot.store(None, &mut d).unwrap();
        pot.restore(Some(0)).unwrap();
        pot.restore(None).unwrap();
        pot.reset(&mut d).unwrap();
        i2c.done();
    }

    #[test]
    fn store_timeout() {
        let mut transactions = vec![q(0x91)];
        for _ in 0..POLL_ATTEMPTS {
            transactions.push(q(0x80).with_error(ErrorKind::Other));
        }
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5252Full::new(i2c.clone(), ADDR, 10000.0, 75.0);
        assert!(matches!(pot.store(Some(0), &mut NoopDelay::new()), Err(AD5252Error::Timeout)));
        i2c.done();
    }

    #[test]
    fn user_eemem_and_tolerance() {
        let transactions = vec![
            I2cTransaction::write_read(ADDR, vec![0x24], vec![0x5A]),
            I2cTransaction::write_read(ADDR, vec![0x2F], vec![0xA5]),
            I2cTransaction::write(ADDR, vec![0x29, 0x42]),
            q(0x80),
            // tolerance: datasheet example 0x1C / 0x0F, and a negative one
            I2cTransaction::write_read(ADDR, vec![0x3A], vec![0x1C, 0x0F]),
            I2cTransaction::write_read(ADDR, vec![0x3E], vec![0x82, 0x80]),
            I2cTransaction::write_read(ADDR, vec![0x3A], vec![0x1C, 0x0F]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5252Full::new(i2c.clone(), ADDR, 10000.0, 75.0);
        assert_eq!(pot.read_eemem(4).unwrap(), 0x5A);
        assert_eq!(pot.read_eemem(15).unwrap(), 0xA5);
        pot.write_eemem(9, 0x42, &mut NoopDelay::new()).unwrap();
        assert!(matches!(pot.read_eemem(3), Err(AD5252Error::InvalidChannel)));
        assert!(matches!(pot.write_eemem(16, 0, &mut NoopDelay::new()), Err(AD5252Error::InvalidChannel)));
        assert!((pot.read_tolerance(0).unwrap() - 28.058_59).abs() < 1e-3);
        assert!((pot.read_tolerance(1).unwrap() + 2.5).abs() < 1e-4);
        assert!((pot.rab_actual(0).unwrap() - 12805.859).abs() < 0.05);
        i2c.done();
    }

    #[test]
    fn set_rab_changes_conversion_only() {
        let transactions = vec![
            I2cTransaction::write_read(ADDR, vec![0x01], vec![128]),
            I2cTransaction::write_read(ADDR, vec![0x01], vec![128]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5252Full::new(i2c.clone(), ADDR, 10000.0, 75.0);
        pot.set_rab(50000.0, Some(100.0));
        assert!((pot.get_resistance(0).expect("R") - 25100.0).abs() < 0.01);
        pot.set_rab(10000.0, None);
        assert!((pot.get_resistance(0).expect("R") - 5100.0).abs() < 0.01);
        i2c.done();
    }
}
