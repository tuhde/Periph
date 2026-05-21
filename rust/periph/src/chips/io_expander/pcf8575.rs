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

impl<I2C: I2c> embedded_hal::digital::ErrorType for Pcf8575Full<I2C> {
    type Error = PinError<I2C::Error>;
}