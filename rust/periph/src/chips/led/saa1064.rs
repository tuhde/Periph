//! SAA1064 — 4-digit LED driver (Philips / NXP).
//!
//! Drives four 7-segment digits with decimal point, multiplexed in pairs,
//! over I²C (address `0x38`–`0x3B`, selected by the voltage on ADR). The chip
//! has no register pointer: every write starts with an instruction byte that
//! selects the subaddress (`0` control, `1`–`4` digits) and the following
//! bytes auto-increment. The only readable byte is the status byte (bit 7 =
//! PR power-reset flag).
//!
//! Defaults at construction: control `0x47` (dynamic mode, all digits
//! unblanked, 12 mA), digits blank.
//!
//! ## Constants
//!
//! [`ADDR_DEFAULT`], [`DIGITS`]

use core::ops::{Deref, DerefMut};
use embedded_hal::i2c::I2c;

/// Default I²C address (ADR = VEE).
pub const ADDR_DEFAULT: u8 = 0x38;
/// Number of digits.
pub const DIGITS: u8 = 4;

const SUB_CONTROL: u8 = 0x00;
const C_DYNAMIC: u8 = 0x01;
const C_UNBLANK_13: u8 = 0x02;
const C_UNBLANK_24: u8 = 0x04;
const C_TEST: u8 = 0x08;
const C_CURRENT: u8 = 0x70;

/// Segment names `a`–`g` per digit value 0–9 (as bit masks over a=1 … g=64).
const GLYPHS: [u8; 10] = [0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F];

/// Default segment-bit mapping: `a` = bit 0 … `g` = bit 6, `dp` = bit 7.
pub const DEFAULT_MAP: [u8; 8] = [0, 1, 2, 3, 4, 5, 6, 7];

/// SAA1064 minimal driver — four digits with a number helper.
pub struct SAA1064Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    control: u8,
    patterns: [u8; 4],
    map: [u8; 8],
}

fn glyph(map: &[u8; 8], value: u8) -> u8 {
    let base = GLYPHS[value as usize];
    let mut out = 0u8;
    for s in 0..7 {
        if base >> s & 1 != 0 {
            out |= 1 << map[s];
        }
    }
    out
}

impl<I2C: I2c> SAA1064Minimal<I2C> {
    /// Create a new `SAA1064Minimal` and write control `0x47` with blank digits.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x38–0x3B).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut s = Self { i2c, addr, control: 0x47, patterns: [0; 4], map: DEFAULT_MAP };
        s.i2c.write(addr, &[SUB_CONTROL, 0x47, 0, 0, 0, 0])?;
        Ok(s)
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn write_digits(&mut self, first: u8) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 5];
        buf[0] = first;
        let n = (DIGITS - first + 1) as usize;
        buf[1..1 + n].copy_from_slice(&self.patterns[(first - 1) as usize..]);
        self.i2c.write(self.addr, &buf[..1 + n])
    }

    /// Show four digits (each 0–9, or `None` to blank) using the segment mapping.
    ///
    /// Returns `Ok(false)` without bus access if a value is above 9.
    pub fn show_digits(&mut self, digits: [Option<u8>; 4]) -> Result<bool, I2C::Error> {
        if digits.iter().any(|d| matches!(d, Some(v) if *v > 9)) {
            return Ok(false);
        }
        for i in 0..4 {
            self.patterns[i] = match digits[i] {
                Some(v) => glyph(&self.map, v),
                None => 0,
            };
        }
        self.write_digits(1)?;
        Ok(true)
    }

    /// Show a right-aligned decimal number 0–9999, leading zeros blanked.
    ///
    /// Returns `Ok(false)` without bus access if `value` is above 9999.
    pub fn show_number(&mut self, value: u16) -> Result<bool, I2C::Error> {
        if value > 9999 {
            return Ok(false);
        }
        let mut digits: [Option<u8>; 4] = [None, None, None, Some((value % 10) as u8)];
        let mut v = value / 10;
        for i in [2usize, 1, 0] {
            if v > 0 {
                digits[i] = Some((v % 10) as u8);
                v /= 10;
            }
        }
        self.show_digits(digits)
    }

    /// Blank all four digits.
    pub fn clear(&mut self) -> Result<(), I2C::Error> {
        self.patterns = [0; 4];
        self.write_digits(1)
    }

    /// Write raw segment bytes starting at digit `first_digit` (1–4).
    ///
    /// Returns `Ok(false)` without bus access if the range does not fit.
    pub fn write_raw(&mut self, patterns: &[u8], first_digit: u8) -> Result<bool, I2C::Error> {
        if first_digit < 1 || first_digit > DIGITS || patterns.is_empty()
            || (first_digit as usize - 1) + patterns.len() > DIGITS as usize
        {
            return Ok(false);
        }
        let mut buf = [0u8; 5];
        buf[0] = first_digit;
        for (i, p) in patterns.iter().enumerate() {
            self.patterns[first_digit as usize - 1 + i] = *p;
            buf[1 + i] = *p;
        }
        self.i2c.write(self.addr, &buf[..1 + patterns.len()])?;
        Ok(true)
    }
}

/// SAA1064 full driver — mode, blanking, current, test, segment map, status.
pub struct SAA1064Full<I2C> {
    inner: SAA1064Minimal<I2C>,
}

impl<I2C> Deref for SAA1064Full<I2C> {
    type Target = SAA1064Minimal<I2C>;
    fn deref(&self) -> &Self::Target { &self.inner }
}

impl<I2C> DerefMut for SAA1064Full<I2C> {
    fn deref_mut(&mut self) -> &mut Self::Target { &mut self.inner }
}

impl<I2C: I2c> SAA1064Full<I2C> {
    /// Create a new `SAA1064Full` (same initialization as the minimal driver).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: SAA1064Minimal::new(i2c, addr)? })
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    fn write_control_byte(&mut self) -> Result<(), I2C::Error> {
        let (addr, control) = (self.inner.addr, self.inner.control);
        self.inner.i2c.write(addr, &[SUB_CONTROL, control])
    }

    fn set_bit(&mut self, mask: u8, on: bool) -> Result<(), I2C::Error> {
        if on {
            self.inner.control |= mask;
        } else {
            self.inner.control &= !mask & 0x7F;
        }
        self.write_control_byte()
    }

    /// Select dynamic (all four digits) or static (digits 1 and 2) mode.
    pub fn set_mode(&mut self, dynamic: bool) -> Result<(), I2C::Error> {
        self.set_bit(C_DYNAMIC, dynamic)
    }

    /// Blank digit pairs (`true` blanks digits 1+3 / 2+4).
    pub fn blank(&mut self, digits_13: bool, digits_24: bool) -> Result<(), I2C::Error> {
        let mut c = self.inner.control;
        c = if digits_13 { c & !C_UNBLANK_13 } else { c | C_UNBLANK_13 };
        c = if digits_24 { c & !C_UNBLANK_24 } else { c | C_UNBLANK_24 };
        self.inner.control = c & 0x7F;
        self.write_control_byte()
    }

    /// Set the segment output current: 0, 3, 6, 9, 12, 15, 18 or 21 mA.
    ///
    /// Returns `Ok(false)` without bus access for any other value.
    pub fn set_current(&mut self, ma: u8) -> Result<bool, I2C::Error> {
        if ma > 21 || ma % 3 != 0 {
            return Ok(false);
        }
        self.inner.control = (self.inner.control & !C_CURRENT & 0x7F) | ((ma / 3) << 4);
        self.write_control_byte()?;
        Ok(true)
    }

    /// Switch all segment outputs on (segment test).
    pub fn segment_test(&mut self, on: bool) -> Result<(), I2C::Error> {
        self.set_bit(C_TEST, on)
    }

    /// Remap the helper's segment bits: `map` = bit numbers (0–7) for `a`…`g` and `dp`.
    ///
    /// Returns `false` (and keeps the old mapping) if a bit number is above 7.
    pub fn set_segment_map(&mut self, map: [u8; 8]) -> bool {
        if map.iter().any(|b| *b > 7) {
            return false;
        }
        self.inner.map = map;
        true
    }

    /// Show one digit (0–9, `None` blanks) with an optional decimal point.
    ///
    /// Returns `Ok(false)` without bus access for a bad position or value.
    pub fn show_digit(&mut self, position: u8, value: Option<u8>, dp: bool) -> Result<bool, I2C::Error> {
        if position < 1 || position > DIGITS || matches!(value, Some(v) if v > 9) {
            return Ok(false);
        }
        let mut pattern = match value {
            Some(v) => glyph(&self.inner.map, v),
            None => 0,
        };
        if dp {
            pattern |= 1 << self.inner.map[7];
        }
        self.inner.patterns[position as usize - 1] = pattern;
        let addr = self.inner.addr;
        self.inner.i2c.write(addr, &[position, pattern])?;
        Ok(true)
    }

    /// Switch a decimal point, keeping the cached segments.
    ///
    /// Returns `Ok(false)` without bus access for a bad position.
    pub fn set_decimal_point(&mut self, position: u8, on: bool) -> Result<bool, I2C::Error> {
        if position < 1 || position > DIGITS {
            return Ok(false);
        }
        let bit = 1u8 << self.inner.map[7];
        let p = &mut self.inner.patterns[position as usize - 1];
        if on { *p |= bit } else { *p &= !bit }
        let (addr, v) = (self.inner.addr, *p);
        self.inner.i2c.write(addr, &[position, v])?;
        Ok(true)
    }

    /// Read the status byte (bit 7 = PR flag, cleared by the read).
    pub fn read_status(&mut self) -> Result<u8, I2C::Error> {
        let mut b = [0u8; 1];
        let addr = self.inner.addr;
        self.inner.i2c.read(addr, &mut b)?;
        Ok(b[0])
    }

    /// Read the status byte and return its PR (power-reset) flag.
    pub fn power_reset_flag(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.read_status()? & 0x80 != 0)
    }

    /// Write a raw control byte (bit 7 forced to 0).
    pub fn write_control(&mut self, value: u8) -> Result<(), I2C::Error> {
        self.inner.control = value & 0x7F;
        self.write_control_byte()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = ADDR_DEFAULT;

    #[test]
    fn full_api() {
        let t = vec![
            // init
            T::write(ADDR, vec![0x00, 0x47, 0, 0, 0, 0]),
            // show_digits([1,2,3,4])
            T::write(ADDR, vec![0x01, 0x06, 0x5B, 0x4F, 0x66]),
            // show_digits([None,0,9,8])
            T::write(ADDR, vec![0x01, 0x00, 0x3F, 0x6F, 0x7F]),
            // show_number(42), (0), (9999)
            T::write(ADDR, vec![0x01, 0x00, 0x00, 0x66, 0x5B]),
            T::write(ADDR, vec![0x01, 0x00, 0x00, 0x00, 0x3F]),
            T::write(ADDR, vec![0x01, 0x6F, 0x6F, 0x6F, 0x6F]),
            // clear()
            T::write(ADDR, vec![0x01, 0, 0, 0, 0]),
            // write_raw(&[0xAA, 0x55], 2)
            T::write(ADDR, vec![0x02, 0xAA, 0x55]),
            // set_mode(false), (true)
            T::write(ADDR, vec![0x00, 0x46]),
            T::write(ADDR, vec![0x00, 0x47]),
            // blank(true,false), (true,true), (false,false)
            T::write(ADDR, vec![0x00, 0x45]),
            T::write(ADDR, vec![0x00, 0x41]),
            T::write(ADDR, vec![0x00, 0x47]),
            // set_current(21), (0), (12)
            T::write(ADDR, vec![0x00, 0x77]),
            T::write(ADDR, vec![0x00, 0x07]),
            T::write(ADDR, vec![0x00, 0x47]),
            // segment_test(true), (false)
            T::write(ADDR, vec![0x00, 0x4F]),
            T::write(ADDR, vec![0x00, 0x47]),
            // write_control(0xFF)
            T::write(ADDR, vec![0x00, 0x7F]),
            T::write(ADDR, vec![0x00, 0x47]),
            // show_digit(2, 7, dp)
            T::write(ADDR, vec![0x02, 0x87]),
            // set_decimal_point(2, false), (3, true)
            T::write(ADDR, vec![0x02, 0x07]),
            T::write(ADDR, vec![0x03, 0xD5]),
            // custom map then show_digits([1,1,1,1])
            T::write(ADDR, vec![0x01, 0x60, 0x60, 0x60, 0x60]),
            // status reads
            T::read(ADDR, vec![0x80]),
            T::read(ADDR, vec![0x80]),
            T::read(ADDR, vec![0x00]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut d = SAA1064Full::new(i2c.clone(), ADDR).unwrap();

        assert!(d.show_digits([Some(1), Some(2), Some(3), Some(4)]).unwrap());
        assert!(d.show_digits([None, Some(0), Some(9), Some(8)]).unwrap());
        assert!(!d.show_digits([Some(10), None, None, None]).unwrap());
        assert!(d.show_number(42).unwrap());
        assert!(d.show_number(0).unwrap());
        assert!(d.show_number(9999).unwrap());
        assert!(!d.show_number(10000).unwrap());
        d.clear().unwrap();
        assert!(d.write_raw(&[0xAA, 0x55], 2).unwrap());
        assert!(!d.write_raw(&[1, 2, 3], 3).unwrap());
        assert!(!d.write_raw(&[1], 0).unwrap());
        assert!(!d.write_raw(&[], 1).unwrap());

        d.set_mode(false).unwrap();
        d.set_mode(true).unwrap();
        d.blank(true, false).unwrap();
        d.blank(true, true).unwrap();
        d.blank(false, false).unwrap();
        assert!(d.set_current(21).unwrap());
        assert!(d.set_current(0).unwrap());
        assert!(d.set_current(12).unwrap());
        assert!(!d.set_current(10).unwrap());
        d.segment_test(true).unwrap();
        d.segment_test(false).unwrap();
        d.write_control(0xFF).unwrap();
        d.write_control(0x47).unwrap();

        assert!(d.show_digit(2, Some(7), true).unwrap());
        assert!(d.set_decimal_point(2, false).unwrap());
        assert!(d.set_decimal_point(3, true).unwrap());
        assert!(!d.show_digit(5, Some(1), false).unwrap());
        assert!(!d.set_decimal_point(0, true).unwrap());

        assert!(!d.set_segment_map([0, 1, 2, 3, 4, 5, 6, 8]));
        assert!(d.set_segment_map([7, 6, 5, 4, 3, 2, 1, 0]));
        assert!(d.show_digits([Some(1); 4]).unwrap());

        assert_eq!(d.read_status().unwrap(), 0x80);
        assert!(d.power_reset_flag().unwrap());
        assert!(!d.power_reset_flag().unwrap());
        i2c.done();
    }
}
