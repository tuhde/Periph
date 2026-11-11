//! CAT5171 — 256-position I²C digital potentiometer (ON Semiconductor).
//!
//! A single 3-terminal potentiometer (A, W, B) usable as a voltage divider or
//! a 2-terminal rheostat. End-to-end resistance `RAB` is 50 kΩ or 100 kΩ
//! (ordering option); the wiper powers up at midscale (`0x80`). The 7-bit I²C
//! address is `0x2C` (AD0 = GND) or `0x2D` (AD0 = VDD). The wiper is volatile.
//!
//! The chip has no register map: a write is `[instruction, data]` where the
//! instruction byte carries the midscale-reset flag (`RS`, bit 6) and the
//! shutdown flag (`SD`, bit 5); a read is a bare one-byte read returning the
//! wiper register. The driver therefore uses plain `embedded-hal` I²C
//! `write` / `read` calls, not register access.
//!
//! Resistances follow `R_WB = D/256 · RAB + RW` and
//! `R_WA = (256 − D)/256 · RAB + RW` — see `specs/adc_dac/cat5171.md`.

use embedded_hal::i2c::I2c;

const INSTR_RS: u8 = 0x40;
const INSTR_SD: u8 = 0x20;
const MIDSCALE: u8 = 0x80;

/// 7-bit I²C address with AD0 = GND.
pub const CAT5171_I2C_ADDRESS: u8 = 0x2C;
/// 7-bit I²C address with AD0 = VDD.
pub const CAT5171_I2C_ADDRESS_AD0_HIGH: u8 = 0x2D;

fn clamp_code(code: i32) -> u8 {
    if code < 0 {
        0
    } else if code > 255 {
        255
    } else {
        code as u8
    }
}

/// CAT5171 — minimal interface: set and read back the wiper position or the
/// wiper-to-B resistance.
pub struct CAT5171Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    r_ab: f32,
    r_w: f32,
    code: u8,
    sd: bool,
}

impl<I2C: I2c> CAT5171Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (the chip has no
    /// identity register and needs no initialisation).
    ///
    /// `addr` is `0x2C` or `0x2D` ([`CAT5171_I2C_ADDRESS`],
    /// [`CAT5171_I2C_ADDRESS_AD0_HIGH`]); `r_ab` is the end-to-end resistance
    /// in Ω (default part: 100 000.0) and `r_wiper` the wiper resistance RW
    /// in Ω (typical 50.0).
    pub fn new(i2c: I2C, addr: u8, r_ab: f32, r_wiper: f32) -> Self {
        Self { i2c, addr, r_ab, r_w: r_wiper, code: MIDSCALE, sd: false }
    }

    fn write_instr(&mut self, instr: u8, code: u8) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[instr, code])
    }

    fn write_code(&mut self, code: i32) -> Result<(), I2C::Error> {
        let code = clamp_code(code);
        let instr = if self.sd { INSTR_SD } else { 0 };
        self.write_instr(instr, code)?;
        self.code = code;
        Ok(())
    }

    fn read_code(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.i2c.read(self.addr, &mut buf)?;
        self.code = buf[0];
        Ok(buf[0])
    }

    /// Set the wiper position; `code` is clamped to 0–255.
    pub fn set_wiper(&mut self, code: i32) -> Result<(), I2C::Error> {
        self.write_code(code)
    }

    /// Read back the wiper position (0–255) with a single read transaction.
    pub fn get_wiper(&mut self) -> Result<u8, I2C::Error> {
        self.read_code()
    }

    /// Set the W–B resistance (Ω) to the nearest reachable code.
    pub fn set_resistance(&mut self, ohms: f32) -> Result<(), I2C::Error> {
        let code = ((ohms - self.r_w) / self.r_ab * 256.0 + 0.5) as i32;
        self.write_code(code)
    }

    /// W–B resistance (Ω) for the current wiper code: `D/256·RAB + RW`.
    pub fn get_resistance(&mut self) -> Result<f32, I2C::Error> {
        let d = self.read_code()? as f32;
        Ok(d / 256.0 * self.r_ab + self.r_w)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// CAT5171 — full interface: extends [`CAT5171Minimal`] with ratio and W–A
/// helpers, increment/decrement, midscale, shutdown and reset. The shutdown
/// state is write-only on the chip, so the driver tracks it.
pub struct CAT5171Full<I2C> {
    inner: CAT5171Minimal<I2C>,
}

impl<I2C: I2c> CAT5171Full<I2C> {
    /// Construct the driver; same parameters as [`CAT5171Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, r_ab: f32, r_wiper: f32) -> Self {
        Self { inner: CAT5171Minimal::new(i2c, addr, r_ab, r_wiper) }
    }

    /// Set the wiper position. Delegates to [`CAT5171Minimal::set_wiper`].
    pub fn set_wiper(&mut self, code: i32) -> Result<(), I2C::Error> {
        self.inner.set_wiper(code)
    }

    /// Read back the wiper position. Delegates to [`CAT5171Minimal::get_wiper`].
    pub fn get_wiper(&mut self) -> Result<u8, I2C::Error> {
        self.inner.get_wiper()
    }

    /// Set the W–B resistance. Delegates to [`CAT5171Minimal::set_resistance`].
    pub fn set_resistance(&mut self, ohms: f32) -> Result<(), I2C::Error> {
        self.inner.set_resistance(ohms)
    }

    /// W–B resistance. Delegates to [`CAT5171Minimal::get_resistance`].
    pub fn get_resistance(&mut self) -> Result<f32, I2C::Error> {
        self.inner.get_resistance()
    }

    /// Set the wiper by divider ratio V(W−B)/V(A−B) (0.0–1.0): code is
    /// `round(ratio × 256)` clamped to 255.
    pub fn set_ratio(&mut self, ratio: f32) -> Result<(), I2C::Error> {
        self.inner.write_code((ratio * 256.0 + 0.5) as i32)
    }

    /// Divider ratio `code / 256`.
    pub fn get_ratio(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.read_code()? as f32 / 256.0)
    }

    /// Set the W–A resistance (rheostat mode, B open) to the nearest code.
    pub fn set_resistance_wa(&mut self, ohms: f32) -> Result<(), I2C::Error> {
        let steps = ((ohms - self.inner.r_w) / self.inner.r_ab * 256.0 + 0.5) as i32;
        self.inner.write_code(256 - steps)
    }

    /// W–A resistance (Ω): `(256 − D)/256·RAB + RW`.
    pub fn get_resistance_wa(&mut self) -> Result<f32, I2C::Error> {
        let d = self.inner.read_code()? as f32;
        Ok((256.0 - d) / 256.0 * self.inner.r_ab + self.inner.r_w)
    }

    /// Move the wiper by `steps` (negative to decrement): reads the code,
    /// adds, clamps to 0–255, writes and returns the new code.
    pub fn increment(&mut self, steps: i32) -> Result<u8, I2C::Error> {
        let code = clamp_code(self.inner.read_code()? as i32 + steps);
        self.inner.write_code(code as i32)?;
        Ok(code)
    }

    /// Move the wiper to midscale (`0x80`) with the `RS` instruction bit; `SD`
    /// is kept if the device is shut down.
    pub fn midscale(&mut self) -> Result<(), I2C::Error> {
        let instr = INSTR_RS | if self.inner.sd { INSTR_SD } else { 0 };
        self.inner.write_instr(instr, MIDSCALE)?;
        self.inner.code = MIDSCALE;
        Ok(())
    }

    /// Shut down: terminal A opens and the wiper shorts to B; the wiper
    /// register is kept (the tracked code is rewritten with `SD` = 1).
    pub fn shutdown(&mut self) -> Result<(), I2C::Error> {
        self.inner.sd = true;
        let code = self.inner.code;
        self.inner.write_instr(INSTR_SD, code)
    }

    /// Leave shutdown (`SD` = 0), rewriting the tracked code.
    pub fn resume(&mut self) -> Result<(), I2C::Error> {
        self.inner.sd = false;
        let code = self.inner.code;
        self.inner.write_instr(0, code)
    }

    /// Driver-tracked shutdown state (the chip cannot report it).
    pub fn is_shutdown(&self) -> bool {
        self.inner.sd
    }

    /// Midscale reset (`RS` = 1, `SD` = 0): wiper to `0x80`, shutdown cleared.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.sd = false;
        self.inner.write_instr(INSTR_RS, MIDSCALE)?;
        self.inner.code = MIDSCALE;
        Ok(())
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

    const ADDR: u8 = CAT5171_I2C_ADDRESS;

    #[test]
    fn minimal_wiper_and_resistance() {
        let transactions = vec![
            I2cTransaction::write(ADDR, vec![0x00, 100]),
            // clamping
            I2cTransaction::write(ADDR, vec![0x00, 255]),
            I2cTransaction::write(ADDR, vec![0x00, 0]),
            // bare one-byte readback
            I2cTransaction::read(ADDR, vec![17]),
            // set_resistance(50050 ohm) -> D = 128; clamps
            I2cTransaction::write(ADDR, vec![0x00, 128]),
            I2cTransaction::write(ADDR, vec![0x00, 0]),
            I2cTransaction::write(ADDR, vec![0x00, 255]),
            // get_resistance
            I2cTransaction::read(ADDR, vec![128]),
            I2cTransaction::read(ADDR, vec![0]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = CAT5171Minimal::new(i2c.clone(), ADDR, 100000.0, 50.0);
        pot.set_wiper(100).expect("set");
        pot.set_wiper(300).expect("clamp hi");
        pot.set_wiper(-5).expect("clamp lo");
        assert_eq!(pot.get_wiper().expect("get"), 17);
        pot.set_resistance(50050.0).expect("set R");
        pot.set_resistance(0.0).expect("set R lo");
        pot.set_resistance(1.0e7).expect("set R hi");
        assert!((pot.get_resistance().expect("R") - 50050.0).abs() < 0.01);
        assert!((pot.get_resistance().expect("R") - 50.0).abs() < 0.01);
        i2c.done();
    }

    #[test]
    fn full_helpers() {
        let transactions = vec![
            // set_ratio(0.25) -> 64; set_ratio(1.0) -> clamped 255
            I2cTransaction::write(ADDR, vec![0x00, 64]),
            I2cTransaction::write(ADDR, vec![0x00, 255]),
            // set_resistance_wa(50050) -> D = 256 - 128
            I2cTransaction::write(ADDR, vec![0x00, 128]),
            // get_ratio / get_resistance_wa
            I2cTransaction::read(ADDR, vec![128]),
            I2cTransaction::read(ADDR, vec![64]),
            // increment(3) from 250 -> 253; increment(100) from 253 -> 255
            I2cTransaction::read(ADDR, vec![250]),
            I2cTransaction::write(ADDR, vec![0x00, 253]),
            I2cTransaction::read(ADDR, vec![253]),
            I2cTransaction::write(ADDR, vec![0x00, 255]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = CAT5171Full::new(i2c.clone(), ADDR, 100000.0, 50.0);
        pot.set_ratio(0.25).expect("ratio");
        pot.set_ratio(1.0).expect("ratio clamp");
        pot.set_resistance_wa(50050.0).expect("wa");
        assert_eq!(pot.get_ratio().expect("ratio"), 0.5);
        assert!((pot.get_resistance_wa().expect("wa") - 75050.0).abs() < 0.01);
        assert_eq!(pot.increment(3).expect("inc"), 253);
        assert_eq!(pot.increment(100).expect("inc clamp"), 255);
        i2c.done();
    }

    #[test]
    fn shutdown_midscale_reset() {
        let transactions = vec![
            // shutdown from power-up midscale
            I2cTransaction::write(ADDR, vec![0x20, 128]),
            // resume
            I2cTransaction::write(ADDR, vec![0x00, 128]),
            I2cTransaction::write(ADDR, vec![0x00, 77]),
            // shutdown with tracked code; set_wiper keeps SD
            I2cTransaction::write(ADDR, vec![0x20, 77]),
            I2cTransaction::write(ADDR, vec![0x20, 50]),
            // resume rewrites the tracked code
            I2cTransaction::write(ADDR, vec![0x00, 50]),
            // midscale: RS set; SD kept while shut down
            I2cTransaction::write(ADDR, vec![0x40, 0x80]),
            I2cTransaction::write(ADDR, vec![0x20, 128]),
            I2cTransaction::write(ADDR, vec![0x60, 0x80]),
            // reset clears SD
            I2cTransaction::write(ADDR, vec![0x40, 0x80]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = CAT5171Full::new(i2c.clone(), ADDR, 100000.0, 50.0);
        assert!(!pot.is_shutdown());
        pot.shutdown().expect("shutdown");
        assert!(pot.is_shutdown());
        pot.resume().expect("resume");
        assert!(!pot.is_shutdown());
        pot.set_wiper(77).expect("set");
        pot.shutdown().expect("shutdown 77");
        pot.set_wiper(50).expect("set during shutdown");
        pot.resume().expect("resume 50");
        pot.midscale().expect("midscale");
        pot.shutdown().expect("shutdown midscale");
        pot.midscale().expect("midscale in shutdown");
        assert!(pot.is_shutdown());
        pot.reset().expect("reset");
        assert!(!pot.is_shutdown());
        i2c.done();
    }

    #[test]
    fn set_rab_changes_conversion_only() {
        let transactions = vec![
            I2cTransaction::read(ADDR, vec![128]),
            I2cTransaction::read(ADDR, vec![128]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut pot = CAT5171Full::new(i2c.clone(), ADDR, 100000.0, 50.0);
        pot.set_rab(50000.0, Some(100.0));
        assert!((pot.get_resistance().expect("R") - 25100.0).abs() < 0.01);
        pot.set_rab(50000.0, None);
        assert!((pot.get_resistance().expect("R") - 25100.0).abs() < 0.01);
        i2c.done();
    }
}
