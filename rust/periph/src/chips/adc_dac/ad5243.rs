//! AD5243 — Dual 256-position I²C digital potentiometer (Analog Devices).
//!
//! Two independent 3-terminal potentiometers (A, W, B) usable as voltage
//! dividers or 2-terminal rheostats. End-to-end resistance `RAB` is ordered
//! as 2.5, 10, 50 or 100 kΩ; both wipers power up at midscale (`0x80`). The
//! 7-bit I²C address is fixed at `0x2F`. Wiper values are volatile.
//!
//! The chip has no register map: a write is `[instruction, data]` where the
//! instruction byte carries the channel select (`A0`, bit 7) and the shutdown
//! flag (`SD`, bit 6). The instruction byte is used as the "register" of
//! [`register::write_register`] / [`register::read_register`].
//!
//! Resistances follow `R_WB = D/256 · RAB + 2·RW` and
//! `R_WA = (256 − D)/256 · RAB + 2·RW` — see `specs/adc_dac/ad5243.md`.

use embedded_hal::i2c::I2c;

use crate::connection::register;

const INSTR_A0: u8 = 0x80;
const INSTR_SD: u8 = 0x40;
const MIDSCALE: u8 = 0x80;

/// Fixed 7-bit I²C address (no address pins).
pub const AD5243_I2C_ADDRESS: u8 = 0x2F;

/// Errors from the AD5243 drivers.
#[derive(Debug)]
pub enum AD5243Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// `channel` was not 0 or 1.
    InvalidChannel,
}

impl<E> From<E> for AD5243Error<E> {
    fn from(e: E) -> Self {
        AD5243Error::Bus(e)
    }
}

fn check_channel<E>(channel: u8) -> Result<usize, AD5243Error<E>> {
    if channel > 1 {
        Err(AD5243Error::InvalidChannel)
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

/// AD5243 — minimal interface: set and read back the wiper position or the
/// wiper-to-B resistance of either channel.
pub struct AD5243Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    r_ab: f32,
    r_w: f32,
    code: [u8; 2],
    sd: [bool; 2],
}

impl<I2C: I2c> AD5243Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (the chip has no
    /// identity register and needs no initialisation).
    ///
    /// `addr` is `0x2F` ([`AD5243_I2C_ADDRESS`]); `r_ab` is the end-to-end
    /// resistance in Ω (default part: 10 000.0) and `r_wiper` the wiper
    /// resistance RW in Ω (typical 160.0).
    pub fn new(i2c: I2C, addr: u8, r_ab: f32, r_wiper: f32) -> Self {
        Self { i2c, addr, r_ab, r_w: r_wiper, code: [MIDSCALE; 2], sd: [false; 2] }
    }

    fn instr(&self, ch: usize) -> u8 {
        (if ch == 1 { INSTR_A0 } else { 0 }) | (if self.sd[ch] { INSTR_SD } else { 0 })
    }

    fn write_code(&mut self, channel: u8, code: i32) -> Result<(), AD5243Error<I2C::Error>> {
        let ch = check_channel(channel)?;
        let code = clamp_code(code);
        let instr = self.instr(ch);
        register::write_register(&mut self.i2c, self.addr, instr.into(), 1, &[code])?;
        self.code[ch] = code;
        Ok(())
    }

    fn read_code(&mut self, channel: u8) -> Result<u8, AD5243Error<I2C::Error>> {
        let ch = check_channel(channel)?;
        let instr = self.instr(ch);
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, instr.into(), 1, &mut buf)?;
        self.code[ch] = buf[0];
        Ok(buf[0])
    }

    /// Set the wiper position of `channel` (0 or 1); `code` is clamped to 0–255.
    pub fn set_wiper(&mut self, channel: u8, code: i32) -> Result<(), AD5243Error<I2C::Error>> {
        self.write_code(channel, code)
    }

    /// Read back the wiper position (0–255): sends the channel's instruction
    /// byte alone, then reads one byte.
    pub fn get_wiper(&mut self, channel: u8) -> Result<u8, AD5243Error<I2C::Error>> {
        self.read_code(channel)
    }

    /// Set the W–B resistance (Ω) to the nearest reachable code.
    pub fn set_resistance(&mut self, channel: u8, ohms: f32) -> Result<(), AD5243Error<I2C::Error>> {
        let code = ((ohms - 2.0 * self.r_w) / self.r_ab * 256.0 + 0.5) as i32;
        self.write_code(channel, code)
    }

    /// W–B resistance (Ω) for the current wiper code: `D/256·RAB + 2·RW`.
    pub fn get_resistance(&mut self, channel: u8) -> Result<f32, AD5243Error<I2C::Error>> {
        let d = self.read_code(channel)? as f32;
        Ok(d / 256.0 * self.r_ab + 2.0 * self.r_w)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// AD5243 — full interface: extends [`AD5243Minimal`] with ratio and W–A
/// helpers, increment/decrement, per-channel shutdown and reset. The
/// shutdown state is write-only on the chip, so the driver tracks it.
pub struct AD5243Full<I2C> {
    inner: AD5243Minimal<I2C>,
}

impl<I2C: I2c> AD5243Full<I2C> {
    /// Construct the driver; same parameters as [`AD5243Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, r_ab: f32, r_wiper: f32) -> Self {
        Self { inner: AD5243Minimal::new(i2c, addr, r_ab, r_wiper) }
    }

    /// Set the wiper position. Delegates to [`AD5243Minimal::set_wiper`].
    pub fn set_wiper(&mut self, channel: u8, code: i32) -> Result<(), AD5243Error<I2C::Error>> {
        self.inner.set_wiper(channel, code)
    }

    /// Read back the wiper position. Delegates to [`AD5243Minimal::get_wiper`].
    pub fn get_wiper(&mut self, channel: u8) -> Result<u8, AD5243Error<I2C::Error>> {
        self.inner.get_wiper(channel)
    }

    /// Set the W–B resistance. Delegates to [`AD5243Minimal::set_resistance`].
    pub fn set_resistance(&mut self, channel: u8, ohms: f32) -> Result<(), AD5243Error<I2C::Error>> {
        self.inner.set_resistance(channel, ohms)
    }

    /// W–B resistance. Delegates to [`AD5243Minimal::get_resistance`].
    pub fn get_resistance(&mut self, channel: u8) -> Result<f32, AD5243Error<I2C::Error>> {
        self.inner.get_resistance(channel)
    }

    /// Write both wipers (two transactions).
    pub fn set_both(&mut self, code1: i32, code2: i32) -> Result<(), AD5243Error<I2C::Error>> {
        self.inner.write_code(0, code1)?;
        self.inner.write_code(1, code2)
    }

    /// Set the wiper by divider ratio V(W−B)/V(A−B) (0.0–1.0): code is
    /// `round(ratio × 256)` clamped to 255.
    pub fn set_ratio(&mut self, channel: u8, ratio: f32) -> Result<(), AD5243Error<I2C::Error>> {
        self.inner.write_code(channel, (ratio * 256.0 + 0.5) as i32)
    }

    /// Divider ratio `code / 256`.
    pub fn get_ratio(&mut self, channel: u8) -> Result<f32, AD5243Error<I2C::Error>> {
        Ok(self.inner.read_code(channel)? as f32 / 256.0)
    }

    /// Set the W–A resistance (rheostat mode, B open) to the nearest code.
    pub fn set_resistance_wa(&mut self, channel: u8, ohms: f32) -> Result<(), AD5243Error<I2C::Error>> {
        let steps = ((ohms - 2.0 * self.inner.r_w) / self.inner.r_ab * 256.0 + 0.5) as i32;
        self.inner.write_code(channel, 256 - steps)
    }

    /// W–A resistance (Ω): `(256 − D)/256·RAB + 2·RW`.
    pub fn get_resistance_wa(&mut self, channel: u8) -> Result<f32, AD5243Error<I2C::Error>> {
        let d = self.inner.read_code(channel)? as f32;
        Ok((256.0 - d) / 256.0 * self.inner.r_ab + 2.0 * self.inner.r_w)
    }

    /// Move the wiper by `steps` (negative to decrement): reads the code,
    /// adds, clamps to 0–255, writes and returns the new code.
    pub fn increment(&mut self, channel: u8, steps: i32) -> Result<u8, AD5243Error<I2C::Error>> {
        let code = clamp_code(self.inner.read_code(channel)? as i32 + steps);
        self.inner.write_code(channel, code as i32)?;
        Ok(code)
    }

    /// Shut a channel down: terminal A opens and the wiper shorts to B; the
    /// RDAC contents are kept (the tracked code is rewritten with `SD` = 1).
    pub fn shutdown(&mut self, channel: u8) -> Result<(), AD5243Error<I2C::Error>> {
        let ch = check_channel(channel)?;
        self.inner.sd[ch] = true;
        let code = self.inner.code[ch] as i32;
        self.inner.write_code(channel, code)
    }

    /// Leave shutdown (`SD` = 0), rewriting the tracked code.
    pub fn resume(&mut self, channel: u8) -> Result<(), AD5243Error<I2C::Error>> {
        let ch = check_channel(channel)?;
        self.inner.sd[ch] = false;
        let code = self.inner.code[ch] as i32;
        self.inner.write_code(channel, code)
    }

    /// Driver-tracked shutdown state (the chip cannot report it).
    pub fn is_shutdown(&self, channel: u8) -> Result<bool, AD5243Error<I2C::Error>> {
        Ok(self.inner.sd[check_channel(channel)?])
    }

    /// Restore the power-up preset: both wipers `0x80`, `SD` = 0.
    pub fn reset(&mut self) -> Result<(), AD5243Error<I2C::Error>> {
        self.inner.sd = [false; 2];
        self.inner.write_code(0, MIDSCALE as i32)?;
        self.inner.write_code(1, MIDSCALE as i32)
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
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = AD5243_I2C_ADDRESS;

    #[test]
    fn minimal_wiper_and_resistance() {
        let transactions = vec![
            I2cTransaction::write(ADDR, vec![0x00, 100]),
            I2cTransaction::write(ADDR, vec![0x80, 200]),
            // clamping
            I2cTransaction::write(ADDR, vec![0x00, 255]),
            I2cTransaction::write(ADDR, vec![0x00, 0]),
            // readback selects the channel through the instruction byte
            I2cTransaction::write_read(ADDR, vec![0x00], vec![17]),
            I2cTransaction::write_read(ADDR, vec![0x80], vec![99]),
            // set_resistance(5320 ohm) -> D = 128; clamps
            I2cTransaction::write(ADDR, vec![0x00, 128]),
            I2cTransaction::write(ADDR, vec![0x00, 0]),
            I2cTransaction::write(ADDR, vec![0x80, 255]),
            // get_resistance
            I2cTransaction::write_read(ADDR, vec![0x00], vec![128]),
            I2cTransaction::write_read(ADDR, vec![0x80], vec![0]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5243Minimal::new(i2c.clone(), ADDR, 10000.0, 160.0);
        pot.set_wiper(0, 100).expect("set 0");
        pot.set_wiper(1, 200).expect("set 1");
        pot.set_wiper(0, 300).expect("clamp hi");
        pot.set_wiper(0, -5).expect("clamp lo");
        assert!(matches!(pot.set_wiper(2, 1), Err(AD5243Error::InvalidChannel)));
        assert_eq!(pot.get_wiper(0).expect("get 0"), 17);
        assert_eq!(pot.get_wiper(1).expect("get 1"), 99);
        pot.set_resistance(0, 5320.0).expect("set R");
        pot.set_resistance(0, 0.0).expect("set R lo");
        pot.set_resistance(1, 1.0e6).expect("set R hi");
        assert!((pot.get_resistance(0).expect("R") - 5320.0).abs() < 0.01);
        assert!((pot.get_resistance(1).expect("R") - 320.0).abs() < 0.01);
        i2c.done();
    }

    #[test]
    fn full_helpers() {
        let transactions = vec![
            // set_both
            I2cTransaction::write(ADDR, vec![0x00, 1]),
            I2cTransaction::write(ADDR, vec![0x80, 2]),
            // set_ratio(0.25) -> 64; set_ratio(1.0) -> clamped 255
            I2cTransaction::write(ADDR, vec![0x00, 64]),
            I2cTransaction::write(ADDR, vec![0x00, 255]),
            // set_resistance_wa(5320) -> D = 256 - 128
            I2cTransaction::write(ADDR, vec![0x80, 128]),
            // get_ratio / get_resistance_wa
            I2cTransaction::write_read(ADDR, vec![0x80], vec![128]),
            I2cTransaction::write_read(ADDR, vec![0x80], vec![128]),
            // increment(0, 3) from 250 -> 253; increment(0, 100) -> 255
            I2cTransaction::write_read(ADDR, vec![0x00], vec![250]),
            I2cTransaction::write(ADDR, vec![0x00, 253]),
            I2cTransaction::write_read(ADDR, vec![0x00], vec![253]),
            I2cTransaction::write(ADDR, vec![0x00, 255]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5243Full::new(i2c.clone(), ADDR, 10000.0, 160.0);
        pot.set_both(1, 2).expect("both");
        pot.set_ratio(0, 0.25).expect("ratio");
        pot.set_ratio(0, 1.0).expect("ratio clamp");
        pot.set_resistance_wa(1, 5320.0).expect("wa");
        assert_eq!(pot.get_ratio(1).expect("ratio"), 0.5);
        assert!((pot.get_resistance_wa(1).expect("wa") - 5320.0).abs() < 0.01);
        assert_eq!(pot.increment(0, 3).expect("inc"), 253);
        assert_eq!(pot.increment(0, 100).expect("inc clamp"), 255);
        i2c.done();
    }

    #[test]
    fn shutdown_resume_reset() {
        let transactions = vec![
            I2cTransaction::write(ADDR, vec![0x80, 77]),
            // shutdown(1): SD set, tracked code rewritten
            I2cTransaction::write(ADDR, vec![0xC0, 77]),
            // set_wiper keeps SD
            I2cTransaction::write(ADDR, vec![0xC0, 50]),
            // resume(1)
            I2cTransaction::write(ADDR, vec![0x80, 50]),
            // shutdown(0) from power-up midscale
            I2cTransaction::write(ADDR, vec![0x40, 128]),
            // reset
            I2cTransaction::write(ADDR, vec![0x00, 128]),
            I2cTransaction::write(ADDR, vec![0x80, 128]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5243Full::new(i2c.clone(), ADDR, 10000.0, 160.0);
        pot.set_wiper(1, 77).expect("set");
        assert!(!pot.is_shutdown(1).expect("sd"));
        pot.shutdown(1).expect("shutdown");
        assert!(pot.is_shutdown(1).expect("sd") && !pot.is_shutdown(0).expect("sd"));
        pot.set_wiper(1, 50).expect("set during shutdown");
        pot.resume(1).expect("resume");
        assert!(!pot.is_shutdown(1).expect("sd"));
        pot.shutdown(0).expect("shutdown 0");
        pot.reset().expect("reset");
        assert!(!pot.is_shutdown(0).expect("sd"));
        i2c.done();
    }

    #[test]
    fn set_rab_changes_conversion_only() {
        let transactions = vec![
            I2cTransaction::write_read(ADDR, vec![0x00], vec![128]),
            I2cTransaction::write_read(ADDR, vec![0x00], vec![128]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = AD5243Full::new(i2c.clone(), ADDR, 10000.0, 160.0);
        pot.set_rab(50000.0, Some(100.0));
        assert!((pot.get_resistance(0).expect("R") - 25200.0).abs() < 0.01);
        pot.set_rab(10000.0, None);
        assert!((pot.get_resistance(0).expect("R") - 5200.0).abs() < 0.01);
        i2c.done();
    }
}
