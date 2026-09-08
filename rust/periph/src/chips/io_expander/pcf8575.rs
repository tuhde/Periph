//! PCF8575 16-bit quasi-bidirectional I/O port expander (NXP).
//!
//! Communicates over I²C at up to 400 kHz Fast mode.
//!
//! Direction is implicit: writing 1 to a pin puts it in input mode (weak
//! ~100 µA pull-up); writing 0 drives it strongly low (up to 25 mA sink).
//! Two shadow registers track the output latches so individual bits can be
//! set without a read-modify-write bus transaction.
//!
//! ## Addresses
//!
//! * **PCF8575** — `0x20`–`0x27` (A2, A1, A0 select offset; default `0x20`)
//!
//! ## Safety
//!
//! The I²C bus is wrapped in a [`core::cell::RefCell`]. Multiple [`ExPin`]
//! objects may coexist, but simultaneous access from different ISR contexts
//! is not safe. Use only from a single execution context.

use core::cell::{Cell, RefCell};
use embedded_hal::digital::{ErrorKind, ErrorType, InputPin, OutputPin, StatefulOutputPin};
use embedded_hal::i2c::I2c;

/// Wraps an I²C error so it satisfies `embedded_hal::digital::Error`.
#[derive(Debug)]
pub struct PinError<E>(pub E);

impl<E: embedded_hal::i2c::Error> embedded_hal::digital::Error for PinError<E> {
    fn kind(&self) -> ErrorKind { ErrorKind::Other }
}

// ============================================================
// Pcf8575Minimal
// ============================================================

/// PCF8575 minimal driver — exposes all 16 pins as GPIO objects.
pub struct Pcf8575Minimal<I2C> {
    i2c:    RefCell<I2C>,
    addr:   u8,
    shadow: [Cell<u8>; 2],
}

impl<I2C: I2c> Pcf8575Minimal<I2C> {
    /// Create a new `Pcf8575Minimal` and set all pins to input mode.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x20`–`0x27`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let chip = Self {
            i2c:    RefCell::new(i2c),
            addr,
            shadow: [Cell::new(0xFF), Cell::new(0xFF)],
        };
        chip.write_both()?;
        Ok(chip)
    }

    fn write_both(&self) -> Result<(), I2C::Error> {
        let buf = [self.shadow[0].get(), self.shadow[1].get()];
        self.i2c.borrow_mut().write(self.addr, &buf)
    }

    fn read_both(&self) -> Result<[u8; 2], I2C::Error> {
        let mut buf = [0u8; 2];
        self.i2c.borrow_mut().read(self.addr, &mut buf)?;
        Ok(buf)
    }

    /// Write all 8 pins of a port and update the shadow register.
    ///
    /// `mask` bit n = 1 → input mode (weak pull-up); bit n = 0 → drive low.
    pub fn write_port(&self, port: usize, mask: u8) -> Result<(), I2C::Error> {
        self.shadow[port].set(mask);
        self.write_both()
    }

    /// Read all 8 pins of a port as a bitmask.
    ///
    /// Returns the actual logic level at each pin (not the shadow register).
    pub fn read_port(&self, port: usize) -> Result<u8, I2C::Error> {
        let buf = self.read_both()?;
        Ok(buf[port])
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–15).
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        ExPin { chip: self, n }
    }

    pub(crate) fn set_pin(&self, n: u8, high: bool) -> Result<(), I2C::Error> {
        let port_idx = (n / 8) as usize;
        let bit = n % 8;
        let mut s = self.shadow[port_idx].get();
        if high { s |=   1 << bit; }
        else    { s &= !(1 << bit); }
        self.shadow[port_idx].set(s);
        self.write_both()
    }

    pub(crate) fn shadow_byte(&self, port: usize) -> u8 {
        self.shadow[port].get()
    }
}

// ============================================================
// ExPin — implements OutputPin + InputPin + StatefulOutputPin
// ============================================================

/// GPIO proxy for a single PCF8575 pin.
///
/// Obtained via [`Pcf8575Minimal::pin`] or [`Pcf8575Full::pin`].
/// Implements [`OutputPin`], [`InputPin`], and [`StatefulOutputPin`].
pub struct ExPin<'a, I2C> {
    chip: &'a Pcf8575Minimal<I2C>,
    n: u8,
}

impl<I2C: I2c> ErrorType for ExPin<'_, I2C> {
    type Error = PinError<I2C::Error>;
}

impl<I2C: I2c> OutputPin for ExPin<'_, I2C> {
    fn set_high(&mut self) -> Result<(), PinError<I2C::Error>> {
        self.chip.set_pin(self.n, true).map_err(PinError)
    }

    fn set_low(&mut self) -> Result<(), PinError<I2C::Error>> {
        self.chip.set_pin(self.n, false).map_err(PinError)
    }
}

impl<I2C: I2c> InputPin for ExPin<'_, I2C> {
    fn is_high(&mut self) -> Result<bool, PinError<I2C::Error>> {
        let port = (self.n / 8) as usize;
        let bit = self.n % 8;
        let buf = self.chip.read_both().map_err(PinError)?;
        Ok((buf[port] >> bit) & 1 == 1)
    }

    fn is_low(&mut self) -> Result<bool, PinError<I2C::Error>> {
        Ok(!self.is_high()?)
    }
}

impl<I2C: I2c> StatefulOutputPin for ExPin<'_, I2C> {
    fn is_set_high(&mut self) -> Result<bool, PinError<I2C::Error>> {
        let port = (self.n / 8) as usize;
        let bit = self.n % 8;
        Ok((self.chip.shadow_byte(port) >> bit) & 1 == 1)
    }

    fn is_set_low(&mut self) -> Result<bool, PinError<I2C::Error>> {
        Ok(!self.is_set_high()?)
    }
}

// ============================================================
// Pcf8575Full
// ============================================================

/// PCF8575 full driver — extends [`Pcf8575Minimal`] with interrupt support.
pub struct Pcf8575Full<I2C> {
    inner: Pcf8575Minimal<I2C>,
    prev:  [Cell<u8>; 2],
}

impl<I2C: I2c> Pcf8575Full<I2C> {
    /// Create a new `Pcf8575Full` and set all pins to input mode.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Pcf8575Minimal::new(i2c, addr)?;
        let prev = inner.read_both()?;
        Ok(Self {
            inner,
            prev: [Cell::new(prev[0]), Cell::new(prev[1])],
        })
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–15).
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        self.inner.pin(n)
    }

    /// Write all 8 pins of a port at once.
    pub fn write_port(&self, port: usize, mask: u8) -> Result<(), I2C::Error> {
        self.inner.write_port(port, mask)
    }

    /// Read all 8 pins of a port as a bitmask.
    pub fn read_port(&self, port: usize) -> Result<u8, I2C::Error> {
        self.inner.read_port(port)
    }

    /// Read current pin states and return the 16-bit bitmask of pins that changed.
    ///
    /// Reads both ports over I²C, compares to the previous read, updates the
    /// stored previous values, and returns the XOR. Bits 0–7 = Port 0 changed,
    /// bits 8–15 = Port 1 changed. Reading also clears the chip's INT output.
    pub fn clear_interrupt(&self) -> Result<u16, I2C::Error> {
        let current = self.inner.read_both()?;
        let changed0 = current[0] ^ self.prev[0].get();
        let changed1 = current[1] ^ self.prev[1].get();
        self.prev[0].set(current[0]);
        self.prev[1].set(current[1]);
        Ok((changed0 as u16) | ((changed1 as u16) << 8))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x20;

    #[test]
    fn full_api() {
        let transactions = vec![
            // Pcf8575Minimal::new(): write_both([0xFF, 0xFF])
            I2cTransaction::write(ADDR, vec![0xFF, 0xFF]),
            // Pcf8575Full::new(): seeds `prev` via read_both()
            I2cTransaction::read(ADDR, vec![0xFF, 0xFF]),
            // read_port(0)/(1) -> both derived from one 2-byte read
            I2cTransaction::read(ADDR, vec![0x5A, 0xA5]),
            I2cTransaction::read(ADDR, vec![0x5A, 0xA5]),
            // write_port(0, 0x3C) / write_port(1, 0x0F)
            I2cTransaction::write(ADDR, vec![0x3C, 0xFF]),
            I2cTransaction::write(ADDR, vec![0x3C, 0x0F]),
            // pin(3).is_high() / pin(11).is_high()
            I2cTransaction::read(ADDR, vec![0x08, 0x00]),
            I2cTransaction::read(ADDR, vec![0x00, 0x08]),
            // write_port(0, 0xFF) / write_port(1, 0xFF) reset
            I2cTransaction::write(ADDR, vec![0xFF, 0x0F]),
            I2cTransaction::write(ADDR, vec![0xFF, 0xFF]),
            // pin3.set_low() / pin5.set_low() / pin11.set_low()
            I2cTransaction::write(ADDR, vec![0xF7, 0xFF]),
            I2cTransaction::write(ADDR, vec![0xD7, 0xFF]),
            I2cTransaction::write(ADDR, vec![0xD7, 0xF7]),
            // clear_interrupt() x2
            I2cTransaction::read(ADDR, vec![0xF7, 0xFE]),
            I2cTransaction::read(ADDR, vec![0xF7, 0xFE]),
        ];
        let i2c = I2cMock::new(&transactions);

        let chip = Pcf8575Full::new(i2c, ADDR).expect("init");
        assert_eq!(chip.inner.shadow_byte(0), 0xFF);
        assert_eq!(chip.inner.shadow_byte(1), 0xFF);

        assert_eq!(chip.read_port(0).unwrap(), 0x5A);
        assert_eq!(chip.read_port(1).unwrap(), 0xA5);

        chip.write_port(0, 0x3C).unwrap();
        chip.write_port(1, 0x0F).unwrap();

        let mut pin3 = chip.pin(3);
        assert!(pin3.is_high().unwrap());
        let mut pin11 = chip.pin(11);
        assert!(pin11.is_high().unwrap());

        chip.write_port(0, 0xFF).unwrap();
        chip.write_port(1, 0xFF).unwrap();
        let mut pin3w = chip.pin(3);
        pin3w.set_low().unwrap();
        assert_eq!(chip.inner.shadow_byte(0), 0xF7);
        let mut pin5 = chip.pin(5);
        pin5.set_low().unwrap();
        assert_eq!(chip.inner.shadow_byte(0), 0xD7);
        let mut pin11w = chip.pin(11);
        pin11w.set_low().unwrap();
        assert_eq!(chip.inner.shadow_byte(1), 0xF7);

        // Direct baseline for clear_interrupt's XOR comparison, independent
        // of the pin exercises above.
        chip.prev[0].set(0xFF);
        chip.prev[1].set(0xFF);
        assert_eq!(chip.clear_interrupt().unwrap(), 0x08 | (0x01 << 8));
        assert_eq!(chip.clear_interrupt().unwrap(), 0x00);

        chip.inner.i2c.borrow_mut().done();
    }
}

impl<I2C: I2c> embedded_hal::digital::ErrorType for Pcf8575Full<I2C> {
    type Error = PinError<I2C::Error>;
}