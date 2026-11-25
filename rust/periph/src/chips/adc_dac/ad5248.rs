//! AD5248 — dual 256-position I²C digital rheostat (Analog Devices).
//!
//! Communicates over I²C at 0x2C–0x2F (AD0/AD1 pins). The chip has no register map:
//! the instruction byte (A0 channel select in bit 7, SD shutdown in bit 6) plays the
//! role of the register address. Wiper values are volatile (midscale 0x80 at power-up).

use embedded_hal::i2c::I2c;

use crate::connection::register;

const SD: u8 = 0x40;

/// Round half away from zero (`f32::round` is unavailable in `no_std`).
fn round_f32(x: f32) -> f32 {
    if x >= 0.0 { (x + 0.5) as i32 as f32 } else { (x - 0.5) as i32 as f32 }
}

fn check_channel(channel: u8) {
    assert!(channel <= 1, "AD5248: channel must be 0 or 1");
}

/// AD5248 minimal driver — set and read back the wiper or the W–B resistance.
///
/// Defaults: RAB = 10 kΩ, RW = 160 Ω; both channels assumed active. Construction
/// performs no I²C traffic. An invalid channel (not 0 or 1) panics.
pub struct Ad5248Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    r_ab: f32,
    r_wiper: f32,
    shutdown: [bool; 2],
}

impl<I2C: I2c> Ad5248Minimal<I2C> {
    /// Create a new `Ad5248Minimal`; no I²C traffic.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus (driver takes ownership).
    /// * `addr` — 7-bit device address (`0x2C`–`0x2F`).
    /// * `r_ab` — end-to-end resistance RAB in ohms (2500, 10000, 50000 or 100000).
    /// * `r_wiper` — wiper resistance RW in ohms (typically 160).
    pub fn new(i2c: I2C, addr: u8, r_ab: f32, r_wiper: f32) -> Self {
        Self { i2c, addr, r_ab, r_wiper, shutdown: [false, false] }
    }

    fn instruction(&self, channel: u8) -> u8 {
        (channel << 7) | if self.shutdown[channel as usize] { SD } else { 0 }
    }

    /// Set the wiper position of a channel (clamped to 0–255).
    ///
    /// On a shut-down channel the RDAC is updated and the channel stays in shutdown.
    pub fn set_wiper(&mut self, channel: u8, code: i32) -> Result<(), I2C::Error> {
        check_channel(channel);
        let code = code.clamp(0, 255) as u8;
        let instr = self.instruction(channel);
        register::write_register(&mut self.i2c, self.addr, instr.into(), 1, &[code])
    }

    /// Read the wiper position of a channel (0–255).
    ///
    /// Sends the instruction byte alone, then reads one byte.
    pub fn get_wiper(&mut self, channel: u8) -> Result<u8, I2C::Error> {
        check_channel(channel);
        let instr = self.instruction(channel);
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, instr.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    /// Set the W–B resistance (ohms) to the nearest reachable value.
    pub fn set_resistance(&mut self, channel: u8, ohms: f32) -> Result<(), I2C::Error> {
        check_channel(channel);
        let code = round_f32((ohms - 2.0 * self.r_wiper) / self.r_ab * 256.0);
        let code = if code < 0.0 { 0 } else if code > 255.0 { 255 } else { code as i32 };
        self.set_wiper(channel, code)
    }

    /// Read the W–B resistance in ohms: `code / 256 * RAB + 2 * RW`.
    pub fn get_resistance(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        let code = self.get_wiper(channel)?;
        Ok(code as f32 / 256.0 * self.r_ab + 2.0 * self.r_wiper)
    }
}

/// AD5248 full driver — extends [`Ad5248Minimal`] with ratio, increment, shutdown and reset.
pub struct Ad5248Full<I2C> {
    inner: Ad5248Minimal<I2C>,
}

impl<I2C: I2c> Ad5248Full<I2C> {
    /// Create a new `Ad5248Full`; same arguments as [`Ad5248Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, r_ab: f32, r_wiper: f32) -> Self {
        Self { inner: Ad5248Minimal::new(i2c, addr, r_ab, r_wiper) }
    }

    /// Set the wiper position. Delegates to the inner [`Ad5248Minimal`].
    pub fn set_wiper(&mut self, channel: u8, code: i32) -> Result<(), I2C::Error> {
        self.inner.set_wiper(channel, code)
    }

    /// Read the wiper position. Delegates to the inner [`Ad5248Minimal`].
    pub fn get_wiper(&mut self, channel: u8) -> Result<u8, I2C::Error> {
        self.inner.get_wiper(channel)
    }

    /// Set the W–B resistance. Delegates to the inner [`Ad5248Minimal`].
    pub fn set_resistance(&mut self, channel: u8, ohms: f32) -> Result<(), I2C::Error> {
        self.inner.set_resistance(channel, ohms)
    }

    /// Read the W–B resistance. Delegates to the inner [`Ad5248Minimal`].
    pub fn get_resistance(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        self.inner.get_resistance(channel)
    }

    /// Write both wipers (two transactions).
    pub fn set_both(&mut self, code1: i32, code2: i32) -> Result<(), I2C::Error> {
        self.inner.set_wiper(0, code1)?;
        self.inner.set_wiper(1, code2)
    }

    /// Set the wiper to `round(ratio × 256)` clamped to 255 (ratio 0.0–1.0).
    ///
    /// Only meaningful if terminal A is wired externally; the AD5248 does not pin it
    /// out, so prefer [`set_resistance`](Self::set_resistance).
    pub fn set_ratio(&mut self, channel: u8, ratio: f32) -> Result<(), I2C::Error> {
        let r = ratio.clamp(0.0, 1.0);
        let code = (round_f32(r * 256.0) as i32).min(255);
        self.inner.set_wiper(channel, code)
    }

    /// Read the wiper position as a ratio: `code / 256`.
    pub fn get_ratio(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        Ok(self.inner.get_wiper(channel)? as f32 / 256.0)
    }

    /// Move the wiper by `steps` (negative to decrement); returns the new clamped code.
    pub fn increment(&mut self, channel: u8, steps: i32) -> Result<u8, I2C::Error> {
        let code = (self.inner.get_wiper(channel)? as i32 + steps).clamp(0, 255);
        self.inner.set_wiper(channel, code)?;
        Ok(code as u8)
    }

    /// Shut a channel down (terminal A open, wiper shorted to B); the RDAC is kept.
    pub fn shutdown(&mut self, channel: u8) -> Result<(), I2C::Error> {
        let code = self.inner.get_wiper(channel)?;
        self.inner.shutdown[channel as usize] = true;
        let instr = self.inner.instruction(channel);
        register::write_register(&mut self.inner.i2c, self.inner.addr, instr.into(), 1, &[code])
    }

    /// Leave shutdown; the RDAC contents are restored to the wiper.
    pub fn resume(&mut self, channel: u8) -> Result<(), I2C::Error> {
        let code = self.inner.get_wiper(channel)?;
        self.inner.shutdown[channel as usize] = false;
        let instr = self.inner.instruction(channel);
        register::write_register(&mut self.inner.i2c, self.inner.addr, instr.into(), 1, &[code])
    }

    /// Driver-tracked shutdown state (the chip cannot be read back).
    pub fn is_shutdown(&self, channel: u8) -> bool {
        check_channel(channel);
        self.inner.shutdown[channel as usize]
    }

    /// Write midscale (0x80) to both channels with SD = 0 (power-up preset).
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.shutdown = [false, false];
        self.inner.set_wiper(0, 0x80)?;
        self.inner.set_wiper(1, 0x80)
    }

    /// Change the resistance model used by the conversions (no I²C traffic).
    pub fn set_rab(&mut self, r_ab: f32, r_wiper: Option<f32>) {
        self.inner.r_ab = r_ab;
        if let Some(w) = r_wiper {
            self.inner.r_wiper = w;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = 0x2C;

    #[test]
    fn minimal_api() {
        let expectations = [
            T::write(ADDR, vec![0x00, 100]),
            T::write(ADDR, vec![0x80, 200]),
            T::write(ADDR, vec![0x00, 255]),
            T::write(ADDR, vec![0x00, 0]),
            T::write_read(ADDR, vec![0x00], vec![100]),
            T::write_read(ADDR, vec![0x80], vec![200]),
            T::write_read(ADDR, vec![0x00], vec![128]),
            T::write(ADDR, vec![0x80, 128]),
            T::write(ADDR, vec![0x80, 0]),
            T::write(ADDR, vec![0x80, 255]),
        ];
        let mut i2c = I2cMock::new(&expectations);
        let mut chip = Ad5248Minimal::new(i2c.clone(), ADDR, 10000.0, 160.0);
        chip.set_wiper(0, 100).unwrap();
        chip.set_wiper(1, 200).unwrap();
        chip.set_wiper(0, 999).unwrap();
        chip.set_wiper(0, -5).unwrap();
        assert_eq!(chip.get_wiper(0).unwrap(), 100);
        assert_eq!(chip.get_wiper(1).unwrap(), 200);
        assert!((chip.get_resistance(0).unwrap() - 5320.0).abs() < 1e-3);
        chip.set_resistance(1, 5320.0).unwrap();
        chip.set_resistance(1, 1.0).unwrap();
        chip.set_resistance(1, 1e9).unwrap();
        i2c.done();
    }

    #[test]
    #[should_panic]
    fn invalid_channel_panics() {
        let mut i2c = I2cMock::new(&[]);
        let mut chip = Ad5248Minimal::new(i2c.clone(), ADDR, 10000.0, 160.0);
        let _ = chip.set_wiper(2, 0);
        i2c.done();
    }

    #[test]
    fn full_api() {
        let expectations = [
            // set_both
            T::write(ADDR, vec![0x00, 10]),
            T::write(ADDR, vec![0x80, 20]),
            // set_ratio(0, 0.5), set_ratio(0, 1.0)
            T::write(ADDR, vec![0x00, 128]),
            T::write(ADDR, vec![0x00, 255]),
            // get_ratio(1)
            T::write_read(ADDR, vec![0x80], vec![64]),
            // increment(0, 10) from 250 -> 255, then -5 from 255 -> 250
            T::write_read(ADDR, vec![0x00], vec![250]),
            T::write(ADDR, vec![0x00, 255]),
            T::write_read(ADDR, vec![0x00], vec![255]),
            T::write(ADDR, vec![0x00, 250]),
            // shutdown(1): read code 77, write with SD
            T::write_read(ADDR, vec![0x80], vec![77]),
            T::write(ADDR, vec![0xC0, 77]),
            // set_wiper(1, 99) keeps SD
            T::write(ADDR, vec![0xC0, 99]),
            // resume(1): read with SD instruction, write without
            T::write_read(ADDR, vec![0xC0], vec![99]),
            T::write(ADDR, vec![0x80, 99]),
            // reset
            T::write(ADDR, vec![0x00, 0x80]),
            T::write(ADDR, vec![0x80, 0x80]),
            // set_rab(50k, 100) then read code 128
            T::write_read(ADDR, vec![0x00], vec![128]),
        ];
        let mut i2c = I2cMock::new(&expectations);
        let mut chip = Ad5248Full::new(i2c.clone(), ADDR, 10000.0, 160.0);
        chip.set_both(10, 20).unwrap();
        chip.set_ratio(0, 0.5).unwrap();
        chip.set_ratio(0, 1.0).unwrap();
        assert!((chip.get_ratio(1).unwrap() - 0.25).abs() < 1e-6);
        assert_eq!(chip.increment(0, 10).unwrap(), 255);
        assert_eq!(chip.increment(0, -5).unwrap(), 250);
        assert!(!chip.is_shutdown(1));
        chip.shutdown(1).unwrap();
        assert!(chip.is_shutdown(1) && !chip.is_shutdown(0));
        chip.set_wiper(1, 99).unwrap();
        chip.resume(1).unwrap();
        assert!(!chip.is_shutdown(1));
        chip.reset().unwrap();
        chip.set_rab(50000.0, Some(100.0));
        assert!((chip.get_resistance(0).unwrap() - 25200.0).abs() < 1e-2);
        i2c.done();
    }
}
