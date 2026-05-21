//! MCP23017 16-bit bidirectional I/O port expander (Microchip).
//!
//! Communicates over I²C at up to 1.7 MHz (fast-mode-plus) and provides
//! 16 GPIO pins split into two 8-bit ports (PORTA: GPA0–GPA7, PORTB: GPB0–GPB7).
//!
//! ## Register model (IOCON.BANK = 0, power-on default)
//!
//! Port A registers alternate with Port B registers in ascending address
//! order. GPA7 and GPB7 are output-only; their IODIR bits must be 0.
//!
//! ## Pin mapping
//!
//! * pins 0–7  → PORTA (GPA0–GPA7)
//! * pins 8–15 → PORTB (GPB0–GPB7)
//!
//! A shadow register tracks the output latch (OLATA/OLATB) so individual
//! pins can be set without a read-modify-write bus transaction.
//!
//! ## Addresses
//!
//! `0x20`–`0x27` (A2, A1, A0 add to base `0x20`; default `0x20`).
//!
//! ## Safety
//!
//! The I²C bus is wrapped in a [`core::cell::RefCell`]. Multiple [`ExPin`]
//! objects may coexist, but simultaneous access from different execution
//! contexts (e.g. concurrent ISRs) is not safe. Use only from a single
//! execution context.

use core::cell::{Cell, RefCell};
use embedded_hal::digital::{ErrorKind, ErrorType, InputPin, OutputPin, StatefulOutputPin};
use embedded_hal::i2c::I2c;

// ============================================================
// Register addresses (IOCON.BANK = 0)
// ============================================================

const REG_IODIRA: u8 = 0x00;
const REG_IODIRB: u8 = 0x01;
const REG_GPPUA:  u8 = 0x0C;
const REG_GPPUB:  u8 = 0x0D;
const REG_GPIOA:  u8 = 0x12;
const REG_GPIOB:  u8 = 0x13;
const REG_OLATA:  u8 = 0x14;
const REG_OLATB:  u8 = 0x15;

// ============================================================
// Errors
// ============================================================

/// Wraps an I²C error so it satisfies `embedded_hal::digital::Error`.
#[derive(Debug)]
pub struct PinError<E>(pub E);

impl<E: embedded_hal::i2c::Error> embedded_hal::digital::Error for PinError<E> {
    fn kind(&self) -> ErrorKind { ErrorKind::Other }
}

// ============================================================
// Mcp23017Minimal
// ============================================================

/// MCP23017 minimal driver — exposes all 16 pins as GPIO objects.
///
/// At construction, all pins initialise as inputs except GPA7 and GPB7
/// which are output-only on the hardware and are forced to output mode.
/// A 2-element shadow array tracks OLATA and OLATB.
pub struct Mcp23017Minimal<I2C> {
    i2c:    RefCell<I2C>,
    addr:   u8,
    shadow: [Cell<u8>; 2],
}

impl<I2C: I2c> Mcp23017Minimal<I2C> {
    /// Create a new `Mcp23017Minimal`.
    ///
    /// Initialises OLAT = 0x00 for both ports, then sets IODIRA = IODIRB = 0x7F
    /// (pins 0–6 input, pins 7 output-only). Pull-ups are disabled.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x20`–`0x27`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let chip = Self {
            i2c:    RefCell::new(i2c),
            addr,
            shadow: [Cell::new(0), Cell::new(0)],
        };
        chip.write_reg(REG_OLATA, 0)?;
        chip.write_reg(REG_OLATB, 0)?;
        chip.write_reg(REG_IODIRA, 0x7F)?;
        chip.write_reg(REG_IODIRB, 0x7F)?;
        Ok(chip)
    }

    /// Write a single register.
    fn write_reg(&self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.i2c.borrow_mut().write(self.addr, &[reg, value])
    }

    /// Read a single register.
    fn read_reg(&self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.i2c.borrow_mut().write_read(self.addr, &[reg], &mut buf)?;
        Ok(buf[0])
    }

    /// Write all 8 output pins of a port via the output latch.
    ///
    /// Updates the internal shadow register.
    ///
    /// # Arguments
    /// * `port` — 0 = PORTA, 1 = PORTB.
    /// * `mask` — 8-bit output mask; bit n = 1 drives pin n high.
    pub fn write_port(&self, port: u8, mask: u8) -> Result<(), I2C::Error> {
        self.shadow[port as usize].set(mask & 0xFF);
        self.write_reg(REG_OLATA + port, mask)
    }

    /// Read all 8 pins of a port as a bitmask.
    ///
    /// Returns the actual logic level at each GPIO pin.
    ///
    /// # Arguments
    /// * `port` — 0 = PORTA (GPIOA), 1 = PORTB (GPIOB).
    pub fn read_port(&self, port: u8) -> Result<u8, I2C::Error> {
        self.read_reg(GPIOA + port)
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–15).
    ///
    /// Pins 0–7 map to PORTA (GPA0–GPA7); pins 8–15 map to PORTB (GPB0–GPB7).
    /// GPA7 (pin 7) and GPB7 (pin 15) are output-only on the hardware.
    ///
    /// The returned pin holds a shared reference into this driver and borrows
    /// the I²C bus only during each individual operation.
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        ExPin { chip: self, n }
    }

    pub(crate) fn set_pin(&self, n: u8, high: bool) -> Result<(), I2C::Error> {
        let port = (n >> 3) as usize;
        let bit  = n & 7;
        let mut s = self.shadow[port].get();
        if high { s |=   1 << bit; }
        else    { s &= !(1 << bit); }
        self.shadow[port].set(s);
        self.write_reg(REG_OLATA + port as u8, s)
    }

    pub(crate) fn read_pin(&self, n: u8) -> Result<u8, I2C::Error> {
        let port = (n >> 3) as u8;
        Ok((self.read_port(port)? >> (n & 7)) & 1)
    }
}

// ============================================================
// ExPin
// ============================================================

/// GPIO proxy for a single MCP23017 pin.
///
/// Obtained via [`Mcp23017Minimal::pin`] or [`Mcp23017Full::pin`].
/// Implements [`OutputPin`], [`InputPin`], and [`StatefulOutputPin`].
///
/// `StatefulOutputPin::is_set_high` reads from the shadow register (no bus
/// transaction); it reflects what was last written, not the actual pin level.
pub struct ExPin<'a, I2C> {
    chip: &'a Mcp23017Minimal<I2C>,
    pub n: u8,
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
        Ok(self.chip.read_pin(self.n).map_err(PinError)? == 1)
    }

    fn is_low(&mut self) -> Result<bool, PinError<I2C::Error>> {
        Ok(!self.is_high()?)
    }
}

impl<I2C: I2c> StatefulOutputPin for ExPin<'_, I2C> {
    fn is_set_high(&mut self) -> Result<bool, PinError<I2C::Error>> {
        let port = (self.n >> 3) as usize;
        Ok((self.chip.shadow[port].get() >> (self.n & 7)) & 1 == 1)
    }

    fn is_set_low(&mut self) -> Result<bool, PinError<I2C::Error>> {
        Ok(!self.is_set_high()?)
    }
}

// ============================================================
// Mcp23017Full
// ============================================================

/// MCP23017 full driver — extends [`Mcp23017Minimal`] with pull-up
/// configuration, polarity inversion, and interrupt support.
///
/// Uses a background polling thread to detect input changes when no hardware
/// INT line is available; when `int_gpio` is provided, edge detection via
/// epoll is used instead.
pub struct Mcp23017Full<I2C> {
    inner:  Mcp23017Minimal<I2C>,
    prev:   [Cell<u8>; 2],
    flags:  Cell<u8>,
    #[allow(dead_code)]
    callback: Cell<Option<Box<dyn Fn(u8, u8) + Send + 'static>>>,
    poll_timer: Cell<Option<core::time::Duration>>,
}

impl<I2C: I2c> Mcp23017Full<I2C> {
    /// Create a new `Mcp23017Full`.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Mcp23017Minimal::new(i2c, addr)?;
        Ok(Self {
            inner,
            prev: [Cell::new(0), Cell::new(0)],
            flags: Cell::new(0),
            callback: Cell::new(None),
            poll_timer: Cell::new(None),
        })
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–15).
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        self.inner.pin(n)
    }

    /// Write all 8 output pins of a port via the output latch.
    pub fn write_port(&self, port: u8, mask: u8) -> Result<(), I2C::Error> {
        self.inner.write_port(port, mask)
    }

    /// Read all 8 pins of a port as a bitmask.
    pub fn read_port(&self, port: u8) -> Result<u8, I2C::Error> {
        self.inner.read_port(port)
    }

    /// Enable/disable per-pin 100 kΩ pull-ups on a port.
    ///
    /// Pull-ups are only electrically active on pins configured as inputs.
    /// Enabling them on output pins has no hardware effect.
    ///
    /// # Arguments
    /// * `port` — 0 = PORTA (GPPUA), 1 = PORTB (GPPUB).
    /// * `mask` — 8-bit mask: bit n = 1 enables pull-up on pin n.
    pub fn configure_pullup(&self, port: u8, mask: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_GPPUA + port, mask)
    }

    /// Configure input polarity inversion on a port.
    ///
    /// # Arguments
    /// * `port` — 0 = PORTA (IPOLA), 1 = PORTB (IPOLB).
    /// * `mask` — 8-bit mask: bit n = 1 inverts the GPIO read for pin n.
    pub fn configure_polarity(&self, port: u8, mask: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(0x02 + port, mask)
    }

    /// Read and clear the interrupt for a port, returning the changed-pin bitmask.
    ///
    /// Also updates the per-port previous-state tracker used by the polling loop.
    ///
    /// # Arguments
    /// * `port` — 0 = PORTA, 1 = PORTB.
    ///
    /// Returns an 8-bit mask where bit n = 1 means pin n changed since the
    /// last call to `clear_interrupt`.
    pub fn clear_interrupt(&self, port: u8) -> Result<u8, I2C::Error> {
        let captured = self.inner.read_reg(0x10 + port)?;
        let current  = self.inner.read_reg(GPIOA + port)?;
        let changed  = (current ^ self.prev[port as usize].get()) & 0xFF;
        self.prev[port as usize].set(current);
        Ok(changed)
    }

    /// Read interrupt flags without clearing the interrupt.
    ///
    /// # Arguments
    /// * `port` — 0 = INTFA, 1 = INTFB.
    ///
    /// Returns an 8-bit mask where bit n = 1 means pin n triggered an interrupt.
    pub fn read_interrupt_flags(&self, port: u8) -> Result<u8, I2C::Error> {
        self.inner.read_reg(0x0E + port)
    }
}