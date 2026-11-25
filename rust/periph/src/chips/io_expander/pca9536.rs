//! PCA9536 4-bit I/O expander (TI), I²C / SMBus.
//!
//! * [`Pca9536Minimal`] / [`Pca9536Full`] — generic over [`embedded_hal::i2c::I2c`]
//!
//! One 4-pin port (P0–P3) with four registers: INPUT, OUTPUT, POLARITY and
//! CONFIG (bits 7:4 unused). All four pins are bidirectional, each with a weak
//! (about 100 kΩ) pull-up that is **always on and cannot be switched** (so no
//! pull-up API exists; an undriven input reads 1). The address is fixed at
//! `0x41`, there is **no interrupt, reset or address pin**, and **no register
//! auto-increment**: every register is accessed one byte at a time. A shadow of OUTPUT and CONFIG
//! makes a single-pin change one register write. Setting a pin to output writes
//! its OUTPUT bit before clearing its CONFIG bit so the pin never glitches.
//!
//! ## Safety
//!
//! The bus is wrapped in a [`core::cell::RefCell`]. Multiple [`ExPin`] objects
//! may coexist, but simultaneous access from different execution contexts
//! (e.g. concurrent ISRs) is not safe. Use only from a single execution context.

use core::cell::{Cell, RefCell};
use embedded_hal::digital::{ErrorKind, ErrorType, InputPin, OutputPin, StatefulOutputPin};
use embedded_hal::i2c::I2c;

use crate::connection::register;

// ============================================================
// Register addresses
// ============================================================

const REG_INPUT:    u8 = 0x00;
const REG_OUTPUT:   u8 = 0x01;
const REG_POLARITY: u8 = 0x02;
const REG_CONFIG:   u8 = 0x03;

/// Bits 7:4 of every register are unused.
const MASK: u8 = 0x0F;

/// Fixed 7-bit I²C address of the PCA9536.
pub const PCA9536_I2C_ADDRESS: u8 = 0x41;

// ============================================================
// Errors
// ============================================================

/// Wraps a bus error so it satisfies `embedded_hal::digital::Error`.
#[derive(Debug)]
pub struct PinError<E>(pub E);

impl<E: core::fmt::Debug> embedded_hal::digital::Error for PinError<E> {
    fn kind(&self) -> ErrorKind { ErrorKind::Other }
}

// ============================================================
// Minimal
// ============================================================

/// PCA9536 minimal driver — exposes all 4 pins as GPIO objects.
pub struct Pca9536Minimal<I2C> {
    i2c:  RefCell<I2C>,
    addr: u8,
    /// Shadow of OUTPUT.
    pub shadow:    Cell<u8>,
    /// Shadow of CONFIG (1 = input).
    pub direction: Cell<u8>,
    /// Last INPUT value read (change-detection reference).
    last_input: Cell<u8>,
}

impl<I2C: I2c> Pca9536Minimal<I2C> {
    /// Create a new PCA9536 minimal driver.
    ///
    /// Writes the reset state (OUTPUT `0xFF`, POLARITY `0x00`, CONFIG `0xFF`:
    /// all inputs) and reads INPUT to initialise the cached input value.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (fixed [`PCA9536_I2C_ADDRESS`] = `0x41`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let chip = Self {
            i2c:        RefCell::new(i2c),
            addr,
            shadow:     Cell::new(0xFF),
            direction:  Cell::new(0xFF),
            last_input: Cell::new(0x00),
        };
        chip.write_reg(REG_OUTPUT, 0xFF)?;
        chip.write_reg(REG_POLARITY, 0x00)?;
        chip.write_reg(REG_CONFIG, 0xFF)?;
        chip.last_input.set(chip.read_reg(REG_INPUT)? & MASK);
        Ok(chip)
    }

    pub(crate) fn read_reg(&self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut *self.i2c.borrow_mut(), self.addr, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    pub(crate) fn write_reg(&self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut *self.i2c.borrow_mut(), self.addr, reg.into(), 1, &[value])
    }

    /// Write all 4 output bits via OUTPUT and update the shadow (bits 7:4 are
    /// written as 1).
    ///
    /// Bits of input pins are stored but have no effect until the pin becomes
    /// an output.
    ///
    /// # Arguments
    /// * `mask` — 4-bit output mask; bit n = 1 drives Pn high.
    pub fn write_port(&self, mask: u8) -> Result<(), I2C::Error> {
        let v = (mask & MASK) | 0xF0;
        self.shadow.set(v);
        self.write_reg(REG_OUTPUT, v)
    }

    /// Read all 4 pins as a bitmask (INPUT register, actual pin levels).
    pub fn read_port(&self) -> Result<u8, I2C::Error> {
        Ok(self.read_reg(REG_INPUT)? & MASK)
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–3).
    ///
    /// The returned pin holds a shared reference into this driver and borrows
    /// the bus only during each individual operation.
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        ExPin { chip: self, n: n & 3 }
    }

    pub(crate) fn set_pin(&self, n: u8, high: bool) -> Result<(), I2C::Error> {
        let mut s = self.shadow.get();
        if high { s |= 1 << n; } else { s &= !(1 << n); }
        self.write_port(s)
    }

    pub(crate) fn read_pin(&self, n: u8) -> Result<u8, I2C::Error> {
        Ok((self.read_port()? >> n) & 1)
    }

    pub(crate) fn set_pin_direction(&self, n: u8, input: bool) -> Result<(), I2C::Error> {
        let mut d = self.direction.get();
        if input {
            d |= 1 << n;
        } else {
            // Latch the output level first so the pin never glitches.
            self.write_reg(REG_OUTPUT, self.shadow.get())?;
            d &= !(1 << n);
        }
        self.direction.set(d);
        self.write_reg(REG_CONFIG, d)
    }
}

// ============================================================
// ExPin
// ============================================================

/// GPIO proxy for a single PCA9536 pin.
///
/// Obtained via [`Pca9536Minimal::pin`] or [`Pca9536Full::pin`]. Implements
/// [`OutputPin`], [`InputPin`] and [`StatefulOutputPin`]. Pins start as inputs;
/// call [`ExPin::into_output`] before driving one. There is no pull-up
/// configuration: the weak pull-up is fixed in hardware.
///
/// `StatefulOutputPin::is_set_high` reads the shadow register (no bus
/// transaction); it reflects what was last written, not the actual pin level.
pub struct ExPin<'a, I2C> {
    chip: &'a Pca9536Minimal<I2C>,
    /// Pin index 0–3.
    pub n: u8,
}

impl<I2C: I2c> ExPin<'_, I2C> {
    /// Make this pin an output (writes OUTPUT, then clears its CONFIG bit).
    pub fn into_output(&mut self) -> Result<(), I2C::Error> {
        self.chip.set_pin_direction(self.n, false)
    }

    /// Make this pin an input (sets its CONFIG bit).
    pub fn into_input(&mut self) -> Result<(), I2C::Error> {
        self.chip.set_pin_direction(self.n, true)
    }
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
        Ok((self.chip.shadow.get() >> self.n) & 1 == 1)
    }

    fn is_set_low(&mut self) -> Result<bool, PinError<I2C::Error>> {
        Ok(!self.is_set_high()?)
    }
}

// ============================================================
// Full
// ============================================================

/// PCA9536 full driver — extends the minimal driver with polarity inversion,
/// whole-port direction, polling-based change detection and raw register access.
///
/// The chip has no interrupt output and this `no_std` driver has no callback
/// machinery: call [`poll_changes`](Self::poll_changes) periodically (about
/// every 5 ms) to read INPUT and obtain the changed-pin mask.
pub struct Pca9536Full<I2C> {
    inner:    Pca9536Minimal<I2C>,
    polarity: Cell<u8>,
}

impl<I2C: I2c> Pca9536Full<I2C> {
    /// Create a new PCA9536 full driver.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (fixed `0x41`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Pca9536Minimal::new(i2c, addr)?, polarity: Cell::new(0x00) })
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–3).
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        self.inner.pin(n)
    }

    /// Write all 4 output bits via OUTPUT. See [`Pca9536Minimal::write_port`].
    pub fn write_port(&self, mask: u8) -> Result<(), I2C::Error> {
        self.inner.write_port(mask)
    }

    /// Read all 4 pins as a bitmask. See [`Pca9536Minimal::read_port`].
    pub fn read_port(&self) -> Result<u8, I2C::Error> {
        self.inner.read_port()
    }

    /// Configure input polarity inversion (POLARITY ← `mask`).
    ///
    /// The cached input value is refreshed, so the polarity change itself is
    /// not reported by [`poll_changes`](Self::poll_changes).
    ///
    /// # Arguments
    /// * `mask` — 4-bit mask; bit n = 1 inverts the INPUT bit of Pn.
    pub fn configure_polarity(&self, mask: u8) -> Result<(), I2C::Error> {
        let m = mask & MASK;
        self.polarity.set(m);
        self.inner.write_reg(REG_POLARITY, m)?;
        self.inner.last_input.set(self.inner.read_reg(REG_INPUT)? & MASK);
        Ok(())
    }

    /// Return the shadow CONFIG register (1 = input).
    pub fn get_direction(&self) -> u8 {
        self.inner.direction.get() & MASK
    }

    /// Write CONFIG in one transaction (1 = input, 0 = output).
    ///
    /// OUTPUT is written first so pins turning into outputs never glitch; the
    /// cached input value is refreshed afterwards.
    pub fn set_direction(&self, mask: u8) -> Result<(), I2C::Error> {
        let v = (mask & MASK) | 0xF0;
        self.inner.write_reg(REG_OUTPUT, self.inner.shadow.get())?;
        self.inner.direction.set(v);
        self.inner.write_reg(REG_CONFIG, v)?;
        self.inner.last_input.set(self.inner.read_reg(REG_INPUT)? & MASK);
        Ok(())
    }

    /// Raw register read (debug / escape hatch).
    ///
    /// # Arguments
    /// * `reg` — register address 0–3.
    pub fn read_register(&self, reg: u8) -> Result<u8, I2C::Error> {
        self.inner.read_reg(reg & 3)
    }

    /// Raw register write (1–3); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
    ///
    /// Returns `Ok(false)` without touching the bus if `reg` is outside 1–3
    /// (INPUT, 0, is read-only); `Ok(true)` once the write has been done.
    pub fn write_register(&self, reg: u8, value: u8) -> Result<bool, I2C::Error> {
        match reg {
            REG_OUTPUT   => self.inner.shadow.set(value),
            REG_POLARITY => self.polarity.set(value),
            REG_CONFIG   => self.inner.direction.set(value),
            _ => return Ok(false),
        }
        self.inner.write_reg(reg, value)?;
        Ok(true)
    }

    /// Write the reset values (OUTPUT `0xFF`, POLARITY `0x00`, CONFIG `0xFF`) and
    /// refresh the input cache. The chip has no RESET pin; only a power cycle
    /// clears a lock-up.
    pub fn reset(&self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_OUTPUT, 0xFF)?;
        self.inner.write_reg(REG_POLARITY, 0x00)?;
        self.inner.write_reg(REG_CONFIG, 0xFF)?;
        self.inner.shadow.set(0xFF);
        self.polarity.set(0x00);
        self.inner.direction.set(0xFF);
        self.inner.last_input.set(self.inner.read_reg(REG_INPUT)? & MASK);
        Ok(())
    }

    /// Read INPUT and return the changed-pin mask (software edge detection;
    /// the chip has no interrupt output).
    ///
    /// The mask is the XOR of the new and the previously cached INPUT value,
    /// limited to pins configured as inputs; the cache is updated.
    pub fn poll_changes(&self) -> Result<u8, I2C::Error> {
        let now = self.inner.read_reg(REG_INPUT)? & MASK;
        let changed = (now ^ self.inner.last_input.get()) & self.inner.direction.get() & MASK;
        self.inner.last_input.set(now);
        Ok(changed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = PCA9536_I2C_ADDRESS;

    fn w(reg: u8, v: u8) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![reg, v])
    }

    fn r(reg: u8, v: u8) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![reg], vec![v])
    }

    fn i2c_init() -> Vec<I2cTransaction> {
        vec![w(REG_OUTPUT, 0xFF), w(REG_POLARITY, 0x00), w(REG_CONFIG, 0xFF), r(REG_INPUT, 0xF0)]
    }

    #[test]
    fn minimal_api() {
        let mut t = i2c_init();
        t.extend(vec![
            r(REG_INPUT, 0xF5),             // read_port: bits 7:4 masked off
            w(REG_OUTPUT, 0xFC),            // write_port(0x0C): bits 7:4 written as 1
            r(REG_INPUT, 0x04),             // pin(2).is_high()
            w(REG_OUTPUT, 0xF0),            // write_port(0)
            w(REG_OUTPUT, 0xF1),            // pin0 set_high
            w(REG_OUTPUT, 0xF5),            // pin2 set_high
            w(REG_OUTPUT, 0xF4),            // pin0 set_low
            w(REG_OUTPUT, 0xF4),            // P3 into_output: OUTPUT first ...
            w(REG_CONFIG, 0xF7),            // ... then CONFIG
            w(REG_CONFIG, 0xFF),            // P3 into_input
        ]);
        let chip = Pca9536Minimal::new(I2cMock::new(&t), ADDR).expect("init");
        assert_eq!(chip.direction.get(), 0xFF);
        assert_eq!(chip.shadow.get(), 0xFF);

        assert_eq!(chip.read_port().unwrap(), 0x05);
        chip.write_port(0x0C).unwrap();
        assert_eq!(chip.shadow.get(), 0xFC);
        assert!(chip.pin(2).is_high().unwrap());

        chip.write_port(0x00).unwrap();
        chip.pin(0).set_high().unwrap();
        chip.pin(2).set_high().unwrap();
        chip.pin(0).set_low().unwrap();
        assert_eq!(chip.shadow.get(), 0xF4);
        assert!(chip.pin(0).is_set_low().unwrap());
        assert!(chip.pin(2).is_set_high().unwrap());

        let mut p3 = chip.pin(3);
        p3.into_output().unwrap();
        assert_eq!(chip.direction.get(), 0xF7);
        p3.into_input().unwrap();
        assert_eq!(chip.direction.get(), 0xFF);

        chip.i2c.borrow_mut().done();
    }

    #[test]
    fn full_api() {
        let mut t = i2c_init();
        t.extend(vec![
            w(REG_POLARITY, 0x0F), r(REG_INPUT, 0xF0),   // configure_polarity (+ cache refresh)
            w(REG_OUTPUT, 0xFF), w(REG_CONFIG, 0xFC), r(REG_INPUT, 0xF0), // set_direction(0x0C)
            r(REG_CONFIG, 0xFC),                         // read_register
            w(REG_OUTPUT, 0xF1),                         // write_register shadows
            w(REG_CONFIG, 0x0F),
            r(REG_INPUT, 0xFD),                          // poll_changes: P2/P3 changed, outputs masked
            r(REG_INPUT, 0xFD),                          // poll_changes: no change
            // reset
            w(REG_OUTPUT, 0xFF), w(REG_POLARITY, 0x00), w(REG_CONFIG, 0xFF), r(REG_INPUT, 0xF0),
        ]);
        let chip = Pca9536Full::new(I2cMock::new(&t), ADDR).expect("init");

        chip.configure_polarity(0x0F).unwrap();
        chip.set_direction(0x0C).unwrap();
        assert_eq!(chip.get_direction(), 0x0C);
        assert_eq!(chip.read_register(REG_CONFIG).unwrap(), 0xFC);

        assert!(chip.write_register(REG_OUTPUT, 0xF1).unwrap());
        assert_eq!(chip.inner.shadow.get(), 0xF1);
        assert!(chip.write_register(REG_CONFIG, 0x0F).unwrap());
        assert_eq!(chip.inner.direction.get(), 0x0F);
        assert!(!chip.write_register(REG_INPUT, 0x00).unwrap());

        chip.inner.direction.set(0xFC);
        assert_eq!(chip.poll_changes().unwrap(), 0x0C);
        assert_eq!(chip.poll_changes().unwrap(), 0x00);

        chip.reset().unwrap();
        assert_eq!(chip.inner.shadow.get(), 0xFF);
        assert_eq!(chip.inner.direction.get(), 0xFF);

        chip.inner.i2c.borrow_mut().done();
    }
}
