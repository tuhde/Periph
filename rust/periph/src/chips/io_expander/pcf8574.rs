//! PCF8574 8-bit quasi-bidirectional I/O port expander (Texas Instruments).
//!
//! Communicates over I²C at up to 100 kHz standard mode.
//!
//! Direction is implicit: writing 1 to a pin puts it in input mode (weak
//! ~100 µA pull-up); writing 0 drives it strongly low (up to 25 mA sink).
//! A shadow register in the driver tracks the output latch so individual
//! bits can be set without a read-modify-write bus transaction.
//!
//! ## Addresses
//!
//! * **PCF8574** — `0x20`–`0x27` (A2, A1, A0 select offset; default `0x20`)
//! * **PCF8574A** — `0x38`–`0x3F` (default `0x38`; overlaps common OLED range)
//!
//! ## Safety
//!
//! The I²C bus is wrapped in a [`core::cell::RefCell`]. Multiple [`ExPin`]
//! objects may coexist, but simultaneous access from different ISR contexts
//! is not safe. Use only from a single execution context.

use core::cell::{Cell, RefCell};
use embedded_hal::digital::{ErrorType, InputPin, OutputPin, StatefulOutputPin};
use embedded_hal::i2c::I2c;

// ============================================================
// Pcf8574Minimal
// ============================================================

/// PCF8574 minimal driver — exposes all 8 pins as GPIO objects.
///
/// Initialises all pins to input mode (shadow = `0xFF`) at construction.
pub struct Pcf8574Minimal<I2C> {
    i2c:    RefCell<I2C>,
    addr:   u8,
    /// Output latch shadow. Bit n = last value written to pin n.
    shadow: Cell<u8>,
}

impl<I2C: I2c> Pcf8574Minimal<I2C> {
    /// Create a new `Pcf8574Minimal` and set all pins to input mode.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x20`–`0x27` for PCF8574;
    ///            `0x38`–`0x3F` for PCF8574A).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let chip = Self {
            i2c:    RefCell::new(i2c),
            addr,
            shadow: Cell::new(0xFF),
        };
        chip.write_port(0xFF)?;
        Ok(chip)
    }

    /// Write all 8 pins at once and update the shadow register.
    ///
    /// `mask` bit n = 1 → input mode (weak pull-up); bit n = 0 → drive low.
    pub fn write_port(&self, mask: u8) -> Result<(), I2C::Error> {
        self.shadow.set(mask);
        self.i2c.borrow_mut().write(self.addr, &[mask])
    }

    /// Read all 8 pins as a bitmask.
    ///
    /// Returns the actual logic level at each pin (not the shadow register).
    pub fn read_port(&self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.i2c.borrow_mut().read(self.addr, &mut buf)?;
        Ok(buf[0])
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–7).
    ///
    /// The returned pin holds a shared reference into this driver and borrows
    /// the I²C bus only during each individual operation.
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        ExPin { chip: self, n }
    }

    // Internal: set or clear bit n in shadow and write to bus.
    pub(crate) fn set_pin(&self, n: u8, high: bool) -> Result<(), I2C::Error> {
        let mut s = self.shadow.get();
        if high { s |=   1 << n; }
        else    { s &= !(1 << n); }
        self.shadow.set(s);
        self.i2c.borrow_mut().write(self.addr, &[s])
    }
}

// ============================================================
// ExPin — implements OutputPin + InputPin + StatefulOutputPin
// ============================================================

/// GPIO proxy for a single PCF8574 pin.
///
/// Obtained via [`Pcf8574Minimal::pin`] or [`Pcf8574Full::pin`].
/// Implements [`OutputPin`], [`InputPin`], and [`StatefulOutputPin`].
///
/// `StatefulOutputPin::is_set_high` reads from the shadow register (no bus
/// transaction); it reflects what was last written, not the actual bus level.
pub struct ExPin<'a, I2C> {
    chip: &'a Pcf8574Minimal<I2C>,
    /// Pin index 0–7.
    pub n: u8,
}

impl<I2C: I2c> ErrorType for ExPin<'_, I2C> {
    type Error = I2C::Error;
}

impl<I2C: I2c> OutputPin for ExPin<'_, I2C> {
    /// Set pin high — releases to quasi-input mode (weak pull-up, ~100 µA).
    ///
    /// This is NOT a strong drive high; external loads must use active-low wiring.
    fn set_high(&mut self) -> Result<(), I2C::Error> {
        self.chip.set_pin(self.n, true)
    }

    /// Drive pin low — strong sink up to 25 mA.
    fn set_low(&mut self) -> Result<(), I2C::Error> {
        self.chip.set_pin(self.n, false)
    }
}

impl<I2C: I2c> InputPin for ExPin<'_, I2C> {
    /// Read the actual logic level at the pin.
    fn is_high(&mut self) -> Result<bool, I2C::Error> {
        Ok((self.chip.read_port()? >> self.n) & 1 == 1)
    }

    /// Read the actual logic level at the pin.
    fn is_low(&mut self) -> Result<bool, I2C::Error> {
        Ok(!self.is_high()?)
    }
}

impl<I2C: I2c> StatefulOutputPin for ExPin<'_, I2C> {
    /// Return whether the shadow register has this pin set high.
    ///
    /// Reads from the in-memory shadow; no bus transaction.
    fn is_set_high(&mut self) -> Result<bool, I2C::Error> {
        Ok((self.chip.shadow.get() >> self.n) & 1 == 1)
    }

    /// Return whether the shadow register has this pin set low.
    fn is_set_low(&mut self) -> Result<bool, I2C::Error> {
        Ok(!self.is_set_high()?)
    }
}

// ============================================================
// Pcf8574Full
// ============================================================

/// PCF8574 full driver — extends [`Pcf8574Minimal`] with interrupt support.
///
/// Adds [`Pcf8574Full::clear_interrupt`] to detect which input pins changed
/// since the previous read. Interrupt delivery is left to the application
/// (attach the chip's INT line to a hardware IRQ; call `clear_interrupt` from
/// the ISR or a deferred task).
pub struct Pcf8574Full<I2C> {
    inner: Pcf8574Minimal<I2C>,
    prev:  Cell<u8>,
}

impl<I2C: I2c> Pcf8574Full<I2C> {
    /// Create a new `Pcf8574Full` and set all pins to input mode.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Pcf8574Minimal::new(i2c, addr)?;
        let prev  = inner.read_port()?;
        Ok(Self { inner, prev: Cell::new(prev) })
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–7).
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        self.inner.pin(n)
    }

    /// Write all 8 pins at once and update the shadow register.
    pub fn write_port(&self, mask: u8) -> Result<(), I2C::Error> {
        self.inner.write_port(mask)
    }

    /// Read all 8 pins as a bitmask.
    pub fn read_port(&self) -> Result<u8, I2C::Error> {
        self.inner.read_port()
    }

    /// Read current pin states and return the bitmask of pins that changed.
    ///
    /// Reads `PORT_IN` over I²C, compares to the previous read, updates the
    /// stored previous value, and returns the XOR (1 = changed since last call).
    /// Reading also clears the chip's active-low INT output.
    pub fn clear_interrupt(&self) -> Result<u8, I2C::Error> {
        let current = self.inner.read_port()?;
        let changed = current ^ self.prev.get();
        self.prev.set(current);
        Ok(changed)
    }
}
