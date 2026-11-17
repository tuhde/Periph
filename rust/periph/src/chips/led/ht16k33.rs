//! HT16K33 — 16 × 8 LED matrix driver with key scan (Holtek).
//!
//! Communicates over I²C (address `0x70`–`0x77`, selected by A0–A2). The chip
//! has no register pointer: each transfer starts with a command / address
//! byte. The driver keeps a 16-byte shadow of the display RAM (eight
//! little-endian 16-bit words, one per COM line, bit `r` = ROW`r`).
//!
//! The reads of the key data RAM and the INT flag are issued as a write of the
//! pointer byte followed by a separate read.
//!
//! ## Constants
//!
//! [`COMMONS`], [`ROWS`], [`ADDR_DEFAULT`]

use core::ops::{Deref, DerefMut};
use embedded_hal::i2c::I2c;

/// Default I²C address (A0 = A1 = A2 = 0).
pub const ADDR_DEFAULT: u8 = 0x70;
/// Number of COM lines.
pub const COMMONS: u8 = 8;
/// Number of ROW lines.
pub const ROWS: u8 = 16;

const CMD_SYSTEM: u8 = 0x20;
const CMD_DISPLAY: u8 = 0x80;
const CMD_DIMMING: u8 = 0xE0;
const CMD_KEY_PTR: u8 = 0x40;
const CMD_INT_PTR: u8 = 0x60;

/// HT16K33 minimal driver — light LEDs of a 16 × 8 matrix with a framebuffer.
///
/// Defaults at construction: oscillator on, ROW15 pin as a ROW output,
/// brightness 16/16, blink off, RAM cleared, display on.
pub struct HT16K33Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    ram: [u8; 16],
    brightness: u8,
    blink: u8,
    display: bool,
    row_int: u8,
}

impl<I2C: I2c> HT16K33Minimal<I2C> {
    /// Create a new `HT16K33Minimal` and run the initialization sequence.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x70–0x77).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut s = Self { i2c, addr, ram: [0; 16], brightness: 15, blink: 0, display: true, row_int: 0xA0 };
        s.command(CMD_SYSTEM | 0x01)?;
        s.command(s.row_int)?;
        s.command(CMD_DIMMING | s.brightness)?;
        s.show()?;
        s.apply_display()?;
        Ok(s)
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn command(&mut self, byte: u8) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[byte])
    }

    fn apply_display(&mut self) -> Result<(), I2C::Error> {
        let b = CMD_DISPLAY | (self.blink << 1) | (self.display as u8);
        self.command(b)
    }

    /// Zero the shadow RAM and write it to the chip.
    pub fn clear(&mut self) -> Result<(), I2C::Error> {
        self.ram = [0; 16];
        self.show()
    }

    /// Set or clear one LED in the shadow RAM (does not write to the chip).
    ///
    /// Returns `false` (and changes nothing) if `com` > 7 or `row` > 15.
    pub fn set_pixel(&mut self, com: u8, row: u8, on: bool) -> bool {
        if com > 7 || row > 15 { return false; }
        let idx = (2 * com + (row >> 3)) as usize;
        let mask = 1u8 << (row & 7);
        if on { self.ram[idx] |= mask; } else { self.ram[idx] &= !mask; }
        true
    }

    /// Write raw RAM bytes at `address` and update the shadow RAM.
    ///
    /// Returns `Ok(false)` without any bus access if `data` is empty or
    /// `address + data.len()` exceeds 16.
    pub fn write_raw(&mut self, data: &[u8], address: u8) -> Result<bool, I2C::Error> {
        let a = address as usize;
        if data.is_empty() || a + data.len() > 16 { return Ok(false); }
        self.ram[a..a + data.len()].copy_from_slice(data);
        let mut buf = [0u8; 17];
        buf[0] = address;
        buf[1..=data.len()].copy_from_slice(data);
        self.i2c.write(self.addr, &buf[..=data.len()])?;
        Ok(true)
    }

    /// Write the whole 16-byte shadow RAM to the chip.
    pub fn show(&mut self) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 17];
        buf[1..].copy_from_slice(&self.ram);
        self.i2c.write(self.addr, &buf)
    }

    /// Set the dimming level, 0–15, giving (level + 1)/16 duty.
    ///
    /// Returns `Ok(false)` without bus access if `level` > 15.
    pub fn set_brightness(&mut self, level: u8) -> Result<bool, I2C::Error> {
        if level > 15 { return Ok(false); }
        self.brightness = level;
        self.command(CMD_DIMMING | level)?;
        Ok(true)
    }
}

/// HT16K33 full driver — extends minimal with display control, standby, key scan and INT output.
///
/// All [`HT16K33Minimal`] methods are available through `Deref`.
pub struct HT16K33Full<I2C> {
    inner: HT16K33Minimal<I2C>,
}

impl<I2C> Deref for HT16K33Full<I2C> {
    type Target = HT16K33Minimal<I2C>;
    fn deref(&self) -> &Self::Target { &self.inner }
}

impl<I2C> DerefMut for HT16K33Full<I2C> {
    fn deref_mut(&mut self) -> &mut Self::Target { &mut self.inner }
}

impl<I2C: I2c> HT16K33Full<I2C> {
    /// Create a new `HT16K33Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x70–0x77).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: HT16K33Minimal::new(i2c, addr)? })
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Turn the display output on (keeps the blink setting).
    pub fn display_on(&mut self) -> Result<(), I2C::Error> {
        self.inner.display = true;
        self.inner.apply_display()
    }

    /// Turn the display output off (keeps the blink setting).
    pub fn display_off(&mut self) -> Result<(), I2C::Error> {
        self.inner.display = false;
        self.inner.apply_display()
    }

    /// Set the blink frequency: 0 (off), 2, 1 or 0.5 Hz.
    ///
    /// Returns `Ok(false)` without bus access for any other value.
    pub fn set_blink(&mut self, frequency: f32) -> Result<bool, I2C::Error> {
        let code = if frequency == 0.0 { 0 }
            else if frequency == 2.0 { 1 }
            else if frequency == 1.0 { 2 }
            else if frequency == 0.5 { 3 }
            else { return Ok(false); };
        self.inner.blink = code;
        self.inner.apply_display()?;
        Ok(true)
    }

    /// Stop the oscillator (display off, key scan stopped).
    pub fn standby(&mut self) -> Result<(), I2C::Error> {
        self.inner.command(CMD_SYSTEM)
    }

    /// Start the oscillator and restore display, dimming, ROW/INT and RAM.
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        self.inner.command(CMD_SYSTEM | 0x01)?;
        self.inner.command(self.inner.row_int)?;
        self.inner.command(CMD_DIMMING | self.inner.brightness)?;
        self.inner.show()?;
        self.inner.apply_display()
    }

    /// Select the function of the ROW15/INT pin.
    ///
    /// `enable` makes it an interrupt output (ROW15/K13 unusable);
    /// `active_high` selects the INT polarity (default active low).
    pub fn set_int_output(&mut self, enable: bool, active_high: bool) -> Result<(), I2C::Error> {
        self.inner.row_int = if enable { if active_high { 0xA3 } else { 0xA1 } } else { 0xA0 };
        self.inner.command(self.inner.row_int)
    }

    /// Read and clear the key data RAM.
    ///
    /// Returns the key bitmask: bit `k * 13 + (K - 1)` for key input K (1–13) on key source KSk (0–2).
    pub fn read_keys(&mut self) -> Result<u64, I2C::Error> {
        let mut b = [0u8; 6];
        self.inner.i2c.write(self.inner.addr, &[CMD_KEY_PTR])?;
        self.inner.i2c.read(self.inner.addr, &mut b)?;
        let mut mask = 0u64;
        for k in 0..3 {
            let word = ((b[2 * k] as u64) | ((b[2 * k + 1] as u64) << 8)) & 0x1FFF;
            mask |= word << (13 * k);
        }
        Ok(mask)
    }

    /// Read the INT flag; `true` if a key press is pending.
    pub fn key_pressed(&mut self) -> Result<bool, I2C::Error> {
        let mut b = [0u8; 1];
        self.inner.i2c.write(self.inner.addr, &[CMD_INT_PTR])?;
        self.inner.i2c.read(self.inner.addr, &mut b)?;
        Ok(b[0] & 0x01 != 0)
    }

    /// Read one LED state from the shadow RAM (`false` if out of range).
    pub fn get_pixel(&self, com: u8, row: u8) -> bool {
        if com > 7 || row > 15 { return false; }
        (self.inner.ram[(2 * com + (row >> 3)) as usize] >> (row & 7)) & 1 == 1
    }

    /// Check the INT flag and, if set, read (and clear) the key data; 0 if none.
    ///
    /// Wire the INT pin to your own GPIO and call this from its handler, or
    /// call it periodically (about every 20 ms).
    pub fn poll_interrupt(&mut self) -> Result<u64, I2C::Error> {
        if !self.key_pressed()? { return Ok(0); }
        self.read_keys()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = ADDR_DEFAULT;

    fn ram(bytes: &[(usize, u8)]) -> Vec<u8> {
        let mut v = vec![0u8; 17];
        for (i, b) in bytes { v[1 + i] = *b; }
        v
    }

    #[test]
    fn full_api() {
        let t = vec![
            // init
            T::write(ADDR, vec![0x21]),
            T::write(ADDR, vec![0xA0]),
            T::write(ADDR, vec![0xEF]),
            T::write(ADDR, ram(&[])),
            T::write(ADDR, vec![0x81]),
            // show(): pixel (1, 9) -> byte 3 = 0x02; pixel (7, 15) -> byte 15 = 0x80
            T::write(ADDR, ram(&[(3, 0x02), (15, 0x80)])),
            // clear()
            T::write(ADDR, ram(&[])),
            // write_raw(&[0xAB, 0xCD], 4)
            T::write(ADDR, vec![0x04, 0xAB, 0xCD]),
            // set_brightness(7)
            T::write(ADDR, vec![0xE7]),
            // set_blink(1.0), 0.5, 2.0
            T::write(ADDR, vec![0x85]),
            T::write(ADDR, vec![0x87]),
            T::write(ADDR, vec![0x83]),
            // display_off(), display_on()
            T::write(ADDR, vec![0x82]),
            T::write(ADDR, vec![0x83]),
            // set_int_output(true, false), (true, true)
            T::write(ADDR, vec![0xA1]),
            T::write(ADDR, vec![0xA3]),
            // standby()
            T::write(ADDR, vec![0x20]),
            // wake()
            T::write(ADDR, vec![0x21]),
            T::write(ADDR, vec![0xA3]),
            T::write(ADDR, vec![0xE7]),
            T::write(ADDR, ram(&[(4, 0xAB)]).into_iter().enumerate().map(|(i, b)| if i == 6 { 0xCD } else { b }).collect()),
            T::write(ADDR, vec![0x83]),
            // read_keys(): KS0 bit 0 and KS1 bit 12
            T::write(ADDR, vec![0x40]),
            T::read(ADDR, vec![0x01, 0x00, 0x00, 0x10, 0x00, 0x00]),
            // key_pressed()
            T::write(ADDR, vec![0x60]),
            T::read(ADDR, vec![0x01]),
            // poll_interrupt(): none
            T::write(ADDR, vec![0x60]),
            T::read(ADDR, vec![0x00]),
            // poll_interrupt(): KS2 bit 0 (bit 26)
            T::write(ADDR, vec![0x60]),
            T::read(ADDR, vec![0x01]),
            T::write(ADDR, vec![0x40]),
            T::read(ADDR, vec![0, 0, 0, 0, 0x01, 0]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut c = HT16K33Full::new(i2c.clone(), ADDR).expect("init");
        assert!(c.set_pixel(1, 9, true));
        assert!(c.set_pixel(7, 15, true));
        assert!(!c.set_pixel(8, 0, true));
        assert!(!c.set_pixel(0, 16, true));
        assert!(c.get_pixel(1, 9) && !c.get_pixel(1, 8));
        c.show().unwrap();
        c.set_pixel(1, 9, false);
        c.set_pixel(7, 15, false);
        c.clear().unwrap();
        assert!(c.write_raw(&[0xAB, 0xCD], 4).unwrap());
        assert!(!c.write_raw(&[0, 0], 15).unwrap());
        assert!(!c.write_raw(&[], 0).unwrap());
        assert!(c.set_brightness(7).unwrap());
        assert!(!c.set_brightness(16).unwrap());
        assert!(c.set_blink(1.0).unwrap());
        assert!(c.set_blink(0.5).unwrap());
        assert!(c.set_blink(2.0).unwrap());
        assert!(!c.set_blink(3.0).unwrap());
        c.display_off().unwrap();
        c.display_on().unwrap();
        c.set_int_output(true, false).unwrap();
        c.set_int_output(true, true).unwrap();
        c.standby().unwrap();
        c.wake().unwrap();
        assert_eq!(c.read_keys().unwrap(), 1 | (1u64 << 25));
        assert!(c.key_pressed().unwrap());
        assert_eq!(c.poll_interrupt().unwrap(), 0);
        assert_eq!(c.poll_interrupt().unwrap(), 1u64 << 26);
        i2c.done();
    }
}
