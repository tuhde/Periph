//! PCA9539 16-bit I/O expander (NXP), I²C / SMBus.
//!
//! * [`Pca9539Minimal`] / [`Pca9539Full`] — generic over [`embedded_hal::i2c::I2c`]
//!
//! Two 8-pin ports (P00–P07 = pins 0–7, P10–P17 = pins 8–15), each with an
//! INPUT, OUTPUT, POLARITY and CONFIG register (four register pairs at
//! `0x00`–`0x07`). All sixteen pins are bidirectional. The chip has **no
//! internal pull-ups** (so no pull-up API exists). The registers toggle within
//! a pair: a two-byte transfer starting at a port-0 register covers both ports.
//! 16-bit shadows of OUTPUT and CONFIG make a single-pin change one register
//! write. Setting a pin to output writes its OUTPUT bit before clearing its
//! CONFIG bit so the pin never glitches.
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

// Port-0 register addresses; port 1 is +1. A two-byte transfer at a port-0
// register toggles to port 1 (register-pair layout).
const REG_INPUT:    u8 = 0x00;
const REG_OUTPUT:   u8 = 0x02;
const REG_POLARITY: u8 = 0x04;
const REG_CONFIG:   u8 = 0x06;

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

/// PCA9539 minimal driver — exposes all 16 pins as GPIO objects.
pub struct Pca9539Minimal<I2C> {
    i2c:  RefCell<I2C>,
    addr: u8,
    /// Shadow of OUTPUT.
    pub shadow:    Cell<u16>,
    /// Shadow of CONFIG (1 = input).
    pub direction: Cell<u16>,
    /// Last INPUT value read (interrupt reference).
    last_input: Cell<u16>,
}

impl<I2C: I2c> Pca9539Minimal<I2C> {
    /// Create a new PCA9539 minimal driver.
    ///
    /// Writes the reset state (OUTPUT `0xFF`, POLARITY `0x00`, CONFIG `0xFF` on
    /// both ports: all inputs) and reads INPUT0/1 to initialise the cached input value.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x74`–`0x77`, set by A1, A0).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let chip = Self {
            i2c:        RefCell::new(i2c),
            addr,
            shadow:     Cell::new(0xFFFF),
            direction:  Cell::new(0xFFFF),
            last_input: Cell::new(0x0000),
        };
        chip.write16(REG_OUTPUT, 0xFFFF)?;
        chip.write16(REG_POLARITY, 0x0000)?;
        chip.write16(REG_CONFIG, 0xFFFF)?;
        chip.last_input.set(chip.read16(REG_INPUT)?);
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

    // One two-byte read at the port-0 register returns port 0 then port 1.
    pub(crate) fn read16(&self, base: u8) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        register::read_register(&mut *self.i2c.borrow_mut(), self.addr, base.into(), 1, &mut buf)?;
        Ok(u16::from_le_bytes(buf))
    }

    pub(crate) fn write16(&self, base: u8, value: u16) -> Result<(), I2C::Error> {
        register::write_register(&mut *self.i2c.borrow_mut(), self.addr, base.into(), 1, &value.to_le_bytes())
    }

    /// Write all 8 output bits of one port via OUTPUTx and update the shadow.
    ///
    /// Bits of input pins are stored but have no effect until the pin becomes
    /// an output.
    ///
    /// # Arguments
    /// * `port` — 0 (P00–P07) or 1 (P10–P17); only bit 0 is used.
    /// * `mask` — 8-bit output mask; bit n = 1 drives the port's pin n high.
    pub fn write_port(&self, port: u8, mask: u8) -> Result<(), I2C::Error> {
        let port = port & 1;
        let shift = 8 * port as u16;
        self.shadow.set((self.shadow.get() & !(0xFFu16 << shift)) | ((mask as u16) << shift));
        self.write_reg(REG_OUTPUT + port, mask)
    }

    /// Read one 8-pin port as a bitmask (INPUTx register, actual pin levels).
    ///
    /// # Arguments
    /// * `port` — 0 (P00–P07) or 1 (P10–P17); only bit 0 is used.
    pub fn read_port(&self, port: u8) -> Result<u8, I2C::Error> {
        self.read_reg(REG_INPUT + (port & 1))
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–15; 0–7 = port 0, 8–15 = port 1).
    ///
    /// The returned pin holds a shared reference into this driver and borrows
    /// the bus only during each individual operation.
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        ExPin { chip: self, n: n & 15 }
    }

    pub(crate) fn set_pin(&self, n: u8, high: bool) -> Result<(), I2C::Error> {
        let mut s = self.shadow.get();
        if high { s |= 1 << n; } else { s &= !(1 << n); }
        self.shadow.set(s);
        self.write_reg(REG_OUTPUT + (n >> 3), (s >> (8 * (n >> 3))) as u8)
    }

    pub(crate) fn read_pin(&self, n: u8) -> Result<u8, I2C::Error> {
        Ok((self.read_port(n >> 3)? >> (n & 7)) & 1)
    }

    pub(crate) fn set_pin_direction(&self, n: u8, input: bool) -> Result<(), I2C::Error> {
        let port = n >> 3;
        let mut d = self.direction.get();
        if input {
            d |= 1 << n;
        } else {
            // Latch the output level first so the pin never glitches.
            self.write_reg(REG_OUTPUT + port, (self.shadow.get() >> (8 * port)) as u8)?;
            d &= !(1 << n);
        }
        self.direction.set(d);
        self.write_reg(REG_CONFIG + port, (d >> (8 * port)) as u8)
    }
}

// ============================================================
// ExPin
// ============================================================

/// GPIO proxy for a single PCA9539 pin.
///
/// Obtained via [`Pca9539Minimal::pin`] or [`Pca9539Full::pin`]. Implements
/// [`OutputPin`], [`InputPin`] and [`StatefulOutputPin`]. Pins start as inputs;
/// call [`ExPin::into_output`] before driving one. There is no pull-up
/// configuration: the chip has no pull-up resistors.
///
/// `StatefulOutputPin::is_set_high` reads the shadow register (no bus
/// transaction); it reflects what was last written, not the actual pin level.
pub struct ExPin<'a, I2C> {
    chip: &'a Pca9539Minimal<I2C>,
    /// Pin index 0–15.
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

/// PCA9539 full driver — extends the minimal driver with polarity inversion,
/// whole-device direction, interrupt change detection and raw register access.
///
/// There is no callback machinery in this `no_std` driver: watch the INT line
/// with your own GPIO and call [`poll_interrupt`](Self::poll_interrupt) to read
/// INPUT (which clears INT) and obtain the changed-pin mask.
pub struct Pca9539Full<I2C> {
    inner:    Pca9539Minimal<I2C>,
    polarity: Cell<u16>,
}

impl<I2C: I2c> Pca9539Full<I2C> {
    /// Create a new PCA9539 full driver.
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x74`–`0x77`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Pca9539Minimal::new(i2c, addr)?, polarity: Cell::new(0x0000) })
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–15).
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> {
        self.inner.pin(n)
    }

    /// Write all 8 output bits of one port. See [`Pca9539Minimal::write_port`].
    pub fn write_port(&self, port: u8, mask: u8) -> Result<(), I2C::Error> {
        self.inner.write_port(port, mask)
    }

    /// Read one 8-pin port as a bitmask. See [`Pca9539Minimal::read_port`].
    pub fn read_port(&self, port: u8) -> Result<u8, I2C::Error> {
        self.inner.read_port(port)
    }

    /// Read both INPUT registers in one two-byte transaction (bit n = pin n).
    pub fn read_all(&self) -> Result<u16, I2C::Error> {
        self.inner.read16(REG_INPUT)
    }

    /// Write both OUTPUT registers in one two-byte transaction and update the
    /// shadow.
    ///
    /// # Arguments
    /// * `value` — 16-bit output value; bit n = 1 drives pin n high.
    pub fn write_all(&self, value: u16) -> Result<(), I2C::Error> {
        self.inner.shadow.set(value);
        self.inner.write16(REG_OUTPUT, value)
    }

    /// Configure input polarity inversion (POLARITY0/1 ← `mask`).
    ///
    /// # Arguments
    /// * `mask` — 16-bit mask; bit n = 1 inverts the INPUT bit of pin n.
    pub fn configure_polarity(&self, mask: u16) -> Result<(), I2C::Error> {
        self.polarity.set(mask);
        self.inner.write16(REG_POLARITY, mask)?;
        self.inner.last_input.set(self.inner.read16(REG_INPUT)?);
        Ok(())
    }

    /// Return the shadow CONFIG registers as a 16-bit value (1 = input).
    pub fn get_direction(&self) -> u16 {
        self.inner.direction.get()
    }

    /// Write CONFIG0/1 in one two-byte transaction (1 = input, 0 = output).
    ///
    /// OUTPUT is written first so pins turning into outputs never glitch; the
    /// cached input value is refreshed afterwards to drop the false interrupt
    /// an output→input change can raise.
    pub fn set_direction(&self, mask: u16) -> Result<(), I2C::Error> {
        self.inner.write16(REG_OUTPUT, self.inner.shadow.get())?;
        self.inner.direction.set(mask);
        self.inner.write16(REG_CONFIG, mask)?;
        self.inner.last_input.set(self.inner.read16(REG_INPUT)?);
        Ok(())
    }

    /// Raw register read (debug / escape hatch).
    ///
    /// # Arguments
    /// * `reg` — register address 0–7.
    pub fn read_register(&self, reg: u8) -> Result<u8, I2C::Error> {
        self.inner.read_reg(reg & 7)
    }

    /// Raw register write (2–7); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
    ///
    /// Returns `Ok(false)` without touching the bus if `reg` is outside 2–7
    /// (INPUT0/1, 0 and 1, are read-only); `Ok(true)` once the write has been done.
    pub fn write_register(&self, reg: u8, value: u8) -> Result<bool, I2C::Error> {
        if !(2..=7).contains(&reg) {
            return Ok(false);
        }
        let shift = 8 * (reg & 1) as u16;
        let keep = !(0xFFu16 << shift);
        let bits = (value as u16) << shift;
        match reg >> 1 {
            1 => self.inner.shadow.set((self.inner.shadow.get() & keep) | bits),
            2 => self.polarity.set((self.polarity.get() & keep) | bits),
            _ => self.inner.direction.set((self.inner.direction.get() & keep) | bits),
        }
        self.inner.write_reg(reg, value)?;
        Ok(true)
    }

    /// Write the reset values (OUTPUT `0xFF`, POLARITY `0x00`, CONFIG `0xFF`, both
    /// ports) and refresh the input cache. The RESET pin is external and not driven.
    pub fn reset(&self) -> Result<(), I2C::Error> {
        self.inner.write16(REG_OUTPUT, 0xFFFF)?;
        self.inner.write16(REG_POLARITY, 0x0000)?;
        self.inner.write16(REG_CONFIG, 0xFFFF)?;
        self.inner.shadow.set(0xFFFF);
        self.polarity.set(0x0000);
        self.inner.direction.set(0xFFFF);
        self.inner.last_input.set(self.inner.read16(REG_INPUT)?);
        Ok(())
    }

    /// Read both INPUT registers (clearing INT) and return the changed-pin mask.
    ///
    /// The mask is the XOR of the new and the previously cached INPUT value,
    /// limited to pins configured as inputs; the cache is updated.
    pub fn poll_interrupt(&self) -> Result<u16, I2C::Error> {
        let now = self.inner.read16(REG_INPUT)?;
        // Errata (datasheet 8.2.4.1): keep the command byte != 0x00 after an INPUT
        // read by rewriting OUTPUT0 with its shadow value (idempotent).
        self.inner.write_reg(REG_OUTPUT, (self.inner.shadow.get() & 0xFF) as u8)?;
        let changed = (now ^ self.inner.last_input.get()) & self.inner.direction.get();
        self.inner.last_input.set(now);
        Ok(changed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x74;

    fn w(reg: u8, v: u8) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![reg, v])
    }

    fn w2(reg: u8, lo: u8, hi: u8) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![reg, lo, hi])
    }

    fn r(reg: u8, v: u8) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![reg], vec![v])
    }

    fn r2(reg: u8, lo: u8, hi: u8) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![reg], vec![lo, hi])
    }

    fn i2c_init() -> Vec<I2cTransaction> {
        vec![
            w2(REG_OUTPUT, 0xFF, 0xFF),
            w2(REG_POLARITY, 0x00, 0x00),
            w2(REG_CONFIG, 0xFF, 0xFF),
            r2(REG_INPUT, 0x00, 0x00),
        ]
    }

    #[test]
    fn minimal_api() {
        let mut t = i2c_init();
        t.extend(vec![
            r(REG_INPUT, 0xA5),             // read_port(0)
            r(REG_INPUT + 1, 0x5A),         // read_port(1)
            w(REG_OUTPUT, 0x3C),            // write_port(0, 0x3C)
            w(REG_OUTPUT + 1, 0xC3),        // write_port(1, 0xC3)
            r(REG_INPUT, 0x04),             // pin(2).is_high()
            r(REG_INPUT + 1, 0x80),         // pin(15).is_high()
            w(REG_OUTPUT, 0x00),            // write_port(0, 0)
            w(REG_OUTPUT, 0x01),            // pin0 set_high
            w(REG_OUTPUT, 0x05),            // pin2 set_high
            w(REG_OUTPUT, 0x04),            // pin0 set_low
            w(REG_OUTPUT + 1, 0xC3),        // pin 8 set_high (port 1, shadow high byte 0xC3 already has bit 0)
            w(REG_OUTPUT, 0x04),            // P07 into_output: OUTPUT0 first ...
            w(REG_CONFIG, 0x7F),            // ... then CONFIG0
            w(REG_CONFIG, 0xFF),            // P07 into_input
            w(REG_OUTPUT + 1, 0xC3),        // P14 into_output: OUTPUT1 first ...
            w(REG_CONFIG + 1, 0xEF),        // ... then CONFIG1
        ]);
        let chip = Pca9539Minimal::new(I2cMock::new(&t), ADDR).expect("init");
        assert_eq!(chip.direction.get(), 0xFFFF);
        assert_eq!(chip.shadow.get(), 0xFFFF);

        assert_eq!(chip.read_port(0).unwrap(), 0xA5);
        assert_eq!(chip.read_port(1).unwrap(), 0x5A);
        chip.write_port(0, 0x3C).unwrap();
        chip.write_port(1, 0xC3).unwrap();
        assert_eq!(chip.shadow.get(), 0xC33C);
        assert!(chip.pin(2).is_high().unwrap());
        assert!(chip.pin(15).is_high().unwrap());

        chip.write_port(0, 0x00).unwrap();
        chip.pin(0).set_high().unwrap();
        chip.pin(2).set_high().unwrap();
        chip.pin(0).set_low().unwrap();
        assert_eq!(chip.shadow.get() & 0xFF, 0x04);
        assert!(chip.pin(0).is_set_low().unwrap());
        assert!(chip.pin(2).is_set_high().unwrap());
        chip.pin(8).set_high().unwrap();
        assert!(chip.pin(8).is_set_high().unwrap());

        let mut p7 = chip.pin(7);
        p7.into_output().unwrap();
        assert_eq!(chip.direction.get(), 0xFF7F);
        p7.into_input().unwrap();
        assert_eq!(chip.direction.get(), 0xFFFF);
        let mut p12 = chip.pin(12);
        p12.into_output().unwrap();
        assert_eq!(chip.direction.get(), 0xEFFF);

        chip.i2c.borrow_mut().done();
    }

    #[test]
    fn full_api() {
        let mut t = i2c_init();
        t.extend(vec![
            r2(REG_INPUT, 0xA5, 0x5A),                                   // read_all
            w2(REG_OUTPUT, 0x34, 0x12),                                  // write_all(0x1234)
            w2(REG_POLARITY, 0x0F, 0xF0), r2(REG_INPUT, 0x00, 0x00),     // configure_polarity (+ cache refresh)
            w2(REG_OUTPUT, 0x34, 0x12), w2(REG_CONFIG, 0x00, 0xFF),      // set_direction: OUTPUT first, then CONFIG
            r2(REG_INPUT, 0x00, 0x00),                                   //   (+ cache refresh)
            r(REG_CONFIG + 1, 0xFF),                                     // read_register
            w(REG_OUTPUT + 1, 0x81),                                     // write_register shadows
            w(REG_CONFIG, 0x0F),
            w(REG_POLARITY + 1, 0x3C),
            r2(REG_INPUT, 0x35, 0x9A), w(REG_OUTPUT, 0x34),             // poll_interrupt: outputs masked (+ errata rewrite)
            r2(REG_INPUT, 0x35, 0x9A), w(REG_OUTPUT, 0x34),             // poll_interrupt: no change (+ errata rewrite)
            // reset
            w2(REG_OUTPUT, 0xFF, 0xFF), w2(REG_POLARITY, 0x00, 0x00), w2(REG_CONFIG, 0xFF, 0xFF),
            r2(REG_INPUT, 0x00, 0x00),
        ]);
        let chip = Pca9539Full::new(I2cMock::new(&t), ADDR).expect("init");

        assert_eq!(chip.read_all().unwrap(), 0x5AA5);
        chip.write_all(0x1234).unwrap();
        assert_eq!(chip.inner.shadow.get(), 0x1234);
        chip.configure_polarity(0xF00F).unwrap();
        chip.set_direction(0xFF00).unwrap();
        assert_eq!(chip.get_direction(), 0xFF00);
        assert_eq!(chip.read_register(REG_CONFIG + 1).unwrap(), 0xFF);

        assert!(chip.write_register(REG_OUTPUT + 1, 0x81).unwrap());
        assert_eq!(chip.inner.shadow.get() >> 8, 0x81);
        assert!(chip.write_register(REG_CONFIG, 0x0F).unwrap());
        assert_eq!(chip.inner.direction.get(), 0xFF0F);
        assert!(chip.write_register(REG_POLARITY + 1, 0x3C).unwrap());
        assert_eq!(chip.polarity.get(), 0x3C0F);
        assert!(!chip.write_register(REG_INPUT, 0x00).unwrap());
        assert!(!chip.write_register(REG_INPUT + 1, 0x00).unwrap());
        assert!(!chip.write_register(8, 0x00).unwrap());

        chip.inner.direction.set(0xF0F0);
        assert_eq!(chip.poll_interrupt().unwrap(), 0x9030);
        assert_eq!(chip.poll_interrupt().unwrap(), 0x0000);

        chip.reset().unwrap();
        assert_eq!(chip.inner.shadow.get(), 0xFFFF);
        assert_eq!(chip.inner.direction.get(), 0xFFFF);

        chip.inner.i2c.borrow_mut().done();
    }
}
