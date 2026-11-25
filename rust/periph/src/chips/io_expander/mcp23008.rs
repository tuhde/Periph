//! MCP23008 / MCP23S08 8-bit bidirectional I/O port expander (Microchip).
//!
//! One driver family for both chips: the MCP23008 (I²C, `0x20`–`0x27`) and the
//! MCP23S08 (SPI, hardware address 0–3). They share an identical register map
//! and GPIO/interrupt behaviour and differ only in the serial interface, which
//! is abstracted by the small [`Bus`] trait.
//!
//! * [`Mcp23008Minimal`] / [`Mcp23008Full`] — generic over [`embedded_hal::i2c::I2c`]
//! * [`Mcp23S08Minimal`] / [`Mcp23S08Full`] — generic over [`embedded_hal::spi::SpiDevice`]
//!
//! All eight pins (GP0–GP7) are bidirectional. A shadow of OLAT and IODIR makes
//! a single-pin change one register write. Setting a pin to output writes its
//! OLAT bit before clearing its IODIR bit so the pin never glitches.
//!
//! ## SPI framing
//!
//! Every transaction is `[0x40 | (addr << 1) | R/W] [reg] [data…]` (R/W = 1 for
//! read). This is not the read-bit convention of the shared SPI register helpers,
//! so the driver builds the two header bytes itself. `IOCON.HAEN` is set during
//! construction (the first IOCON write goes out with the address-0 opcode `0x40`
//! because `HAEN` resets to 0) so up to four devices can share one chip select.
//!
//! ## Safety
//!
//! The bus is wrapped in a [`core::cell::RefCell`]. Multiple [`ExPin`] objects
//! may coexist, but simultaneous access from different execution contexts
//! (e.g. concurrent ISRs) is not safe. Use only from a single execution context.

use core::cell::{Cell, RefCell};
use embedded_hal::digital::{ErrorKind, ErrorType, InputPin, OutputPin, StatefulOutputPin};
use embedded_hal::i2c::I2c;
use embedded_hal::spi::SpiDevice;

use crate::connection::register;

// ============================================================
// Register addresses
// ============================================================

const REG_IODIR:   u8 = 0x00;
const REG_IPOL:    u8 = 0x01;
const REG_GPINTEN: u8 = 0x02;
const REG_DEFVAL:  u8 = 0x03;
const REG_INTCON:  u8 = 0x04;
const REG_IOCON:   u8 = 0x05;
const REG_GPPU:    u8 = 0x06;
const REG_INTF:    u8 = 0x07;
const REG_INTCAP:  u8 = 0x08;
const REG_GPIO:    u8 = 0x09;
const REG_OLAT:    u8 = 0x0A;

const IOCON_SEQOP:  u8 = 0x20;
const IOCON_HAEN:   u8 = 0x08;
const IOCON_ODR:    u8 = 0x04;
const IOCON_INTPOL: u8 = 0x02;

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
// Bus abstraction
// ============================================================

/// Register-level access to the chip over its serial interface.
pub trait Bus {
    /// Bus error type.
    type Error: core::fmt::Debug;
    /// Read `buf.len()` bytes starting at register `reg`.
    fn read_reg(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), Self::Error>;
    /// Write `data` to register `reg` (and following registers).
    fn write_reg(&mut self, reg: u8, data: &[u8]) -> Result<(), Self::Error>;
}

/// I²C transport of the MCP23008 (1-byte register address).
pub struct I2cBus<I2C> {
    i2c:  I2C,
    addr: u8,
}

impl<I2C: I2c> Bus for I2cBus<I2C> {
    type Error = I2C::Error;

    fn read_reg(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, buf)
    }

    fn write_reg(&mut self, reg: u8, data: &[u8]) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, data)
    }
}

/// SPI transport of the MCP23S08 (opcode byte + register byte, then data).
pub struct SpiBus<SPI> {
    spi:  SPI,
    addr: u8,
}

impl<SPI: SpiDevice> Bus for SpiBus<SPI> {
    type Error = SPI::Error;

    fn read_reg(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), SPI::Error> {
        let header = [0x40 | (self.addr << 1) | 1, reg];
        self.spi.transaction(&mut [
            embedded_hal::spi::Operation::Write(&header),
            embedded_hal::spi::Operation::Read(buf),
        ])
    }

    fn write_reg(&mut self, reg: u8, data: &[u8]) -> Result<(), SPI::Error> {
        let header = [0x40 | (self.addr << 1), reg];
        self.spi.transaction(&mut [
            embedded_hal::spi::Operation::Write(&header),
            embedded_hal::spi::Operation::Write(data),
        ])
    }
}

// ============================================================
// Minimal
// ============================================================

/// MCP23008 / MCP23S08 minimal driver — exposes all 8 pins as GPIO objects.
///
/// Use the aliases [`Mcp23008Minimal`] (I²C) and [`Mcp23S08Minimal`] (SPI).
pub struct Mcp23x08Minimal<B> {
    bus:        RefCell<B>,
    iocon_base: u8,
    /// Shadow of OLAT.
    pub shadow:    Cell<u8>,
    /// Shadow of IODIR (1 = input).
    pub direction: Cell<u8>,
}

/// MCP23008 (I²C) minimal driver.
pub type Mcp23008Minimal<I2C> = Mcp23x08Minimal<I2cBus<I2C>>;
/// MCP23S08 (SPI) minimal driver.
pub type Mcp23S08Minimal<SPI> = Mcp23x08Minimal<SpiBus<SPI>>;

impl<B: Bus> Mcp23x08Minimal<B> {
    fn init(bus: B, iocon_base: u8) -> Result<Self, B::Error> {
        let chip = Self {
            bus:        RefCell::new(bus),
            iocon_base,
            shadow:     Cell::new(0x00),
            direction:  Cell::new(0xFF),
        };
        chip.write_reg(REG_OLAT, 0x00)?;
        Ok(chip)
    }

    pub(crate) fn read_reg(&self, reg: u8) -> Result<u8, B::Error> {
        let mut buf = [0u8; 1];
        self.bus.borrow_mut().read_reg(reg, &mut buf)?;
        Ok(buf[0])
    }

    pub(crate) fn write_reg(&self, reg: u8, value: u8) -> Result<(), B::Error> {
        self.bus.borrow_mut().write_reg(reg, &[value])
    }

    /// Write all 8 output bits via OLAT and update the shadow.
    ///
    /// Bits of input pins are stored but have no effect until the pin becomes
    /// an output.
    ///
    /// # Arguments
    /// * `mask` — 8-bit output mask; bit n = 1 drives GPn high.
    pub fn write_port(&self, mask: u8) -> Result<(), B::Error> {
        self.shadow.set(mask);
        self.write_reg(REG_OLAT, mask)
    }

    /// Read all 8 pins as a bitmask (GPIO register, actual pin levels).
    pub fn read_port(&self) -> Result<u8, B::Error> {
        self.read_reg(REG_GPIO)
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–7).
    ///
    /// The returned pin holds a shared reference into this driver and borrows
    /// the bus only during each individual operation.
    pub fn pin(&self, n: u8) -> ExPin<'_, B> {
        ExPin { chip: self, n: n & 7 }
    }

    pub(crate) fn set_pin(&self, n: u8, high: bool) -> Result<(), B::Error> {
        let mut s = self.shadow.get();
        if high { s |= 1 << n; } else { s &= !(1 << n); }
        self.write_port(s)
    }

    pub(crate) fn read_pin(&self, n: u8) -> Result<u8, B::Error> {
        Ok((self.read_port()? >> n) & 1)
    }

    pub(crate) fn set_direction(&self, n: u8, input: bool) -> Result<(), B::Error> {
        let mut d = self.direction.get();
        if input {
            d |= 1 << n;
        } else {
            // Latch the output level first so the pin never glitches.
            self.write_reg(REG_OLAT, self.shadow.get())?;
            d &= !(1 << n);
        }
        self.direction.set(d);
        self.write_reg(REG_IODIR, d)
    }
}

impl<I2C: I2c> Mcp23x08Minimal<I2cBus<I2C>> {
    /// Create a new MCP23008 minimal driver (I²C).
    ///
    /// Clears OLAT; IODIR stays at its reset value `0xFF` (all inputs).
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x20`–`0x27`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Self::init(I2cBus { i2c, addr }, 0x00)
    }
}

impl<SPI: SpiDevice> Mcp23x08Minimal<SpiBus<SPI>> {
    /// Create a new MCP23S08 minimal driver (SPI, mode 0, MSB first).
    ///
    /// Sets `IOCON.HAEN` using the address-0 opcode, then clears OLAT.
    ///
    /// # Arguments
    /// * `spi`  — [`embedded_hal::spi::SpiDevice`] bound to the chip's CS pin.
    /// * `addr` — hardware device address 0–3 (A1:A0 pins).
    pub fn new(mut spi: SPI, addr: u8) -> Result<Self, SPI::Error> {
        spi.write(&[0x40, REG_IOCON, IOCON_HAEN])?;
        Self::init(SpiBus { spi, addr: addr & 3 }, IOCON_HAEN)
    }
}

// ============================================================
// ExPin
// ============================================================

/// GPIO proxy for a single MCP23008 / MCP23S08 pin.
///
/// Obtained via [`Mcp23x08Minimal::pin`] or [`Mcp23x08Full::pin`]. Implements
/// [`OutputPin`], [`InputPin`] and [`StatefulOutputPin`]. Pins start as inputs;
/// call [`ExPin::into_output`] before driving one.
///
/// `StatefulOutputPin::is_set_high` reads the shadow register (no bus
/// transaction); it reflects what was last written, not the actual pin level.
pub struct ExPin<'a, B> {
    chip: &'a Mcp23x08Minimal<B>,
    /// Pin index 0–7.
    pub n: u8,
}

impl<B: Bus> ExPin<'_, B> {
    /// Make this pin an output (writes OLAT, then clears its IODIR bit).
    pub fn into_output(&mut self) -> Result<(), B::Error> {
        self.chip.set_direction(self.n, false)
    }

    /// Make this pin an input (sets its IODIR bit).
    pub fn into_input(&mut self) -> Result<(), B::Error> {
        self.chip.set_direction(self.n, true)
    }
}

impl<B: Bus> ErrorType for ExPin<'_, B> {
    type Error = PinError<B::Error>;
}

impl<B: Bus> OutputPin for ExPin<'_, B> {
    fn set_high(&mut self) -> Result<(), PinError<B::Error>> {
        self.chip.set_pin(self.n, true).map_err(PinError)
    }

    fn set_low(&mut self) -> Result<(), PinError<B::Error>> {
        self.chip.set_pin(self.n, false).map_err(PinError)
    }
}

impl<B: Bus> InputPin for ExPin<'_, B> {
    fn is_high(&mut self) -> Result<bool, PinError<B::Error>> {
        Ok(self.chip.read_pin(self.n).map_err(PinError)? == 1)
    }

    fn is_low(&mut self) -> Result<bool, PinError<B::Error>> {
        Ok(!self.is_high()?)
    }
}

impl<B: Bus> StatefulOutputPin for ExPin<'_, B> {
    fn is_set_high(&mut self) -> Result<bool, PinError<B::Error>> {
        Ok((self.chip.shadow.get() >> self.n) & 1 == 1)
    }

    fn is_set_low(&mut self) -> Result<bool, PinError<B::Error>> {
        Ok(!self.is_set_high()?)
    }
}

// ============================================================
// Full
// ============================================================

/// MCP23008 / MCP23S08 full driver — extends the minimal driver with pull-up
/// configuration, polarity inversion, interrupt-on-change and raw register access.
///
/// Use the aliases [`Mcp23008Full`] (I²C) and [`Mcp23S08Full`] (SPI). There is no
/// callback machinery in this `no_std` driver: arm pins with
/// [`enable_pin_interrupt`](Self::enable_pin_interrupt), watch the INT line with
/// your own GPIO, and call [`poll_interrupt`](Self::poll_interrupt) /
/// [`read_capture`](Self::read_capture) to identify and clear the event.
pub struct Mcp23x08Full<B> {
    inner:   Mcp23x08Minimal<B>,
    pullup:  Cell<u8>,
    gpinten: Cell<u8>,
    iocon:   Cell<u8>,
}

/// MCP23008 (I²C) full driver.
pub type Mcp23008Full<I2C> = Mcp23x08Full<I2cBus<I2C>>;
/// MCP23S08 (SPI) full driver.
pub type Mcp23S08Full<SPI> = Mcp23x08Full<SpiBus<SPI>>;

impl<B: Bus> Mcp23x08Full<B> {
    fn wrap(inner: Mcp23x08Minimal<B>) -> Self {
        let iocon = inner.iocon_base;
        Self { inner, pullup: Cell::new(0), gpinten: Cell::new(0), iocon: Cell::new(iocon) }
    }

    /// Return an [`ExPin`] proxy for pin `n` (0–7).
    pub fn pin(&self, n: u8) -> ExPin<'_, B> {
        self.inner.pin(n)
    }

    /// Write all 8 output bits via OLAT. See [`Mcp23x08Minimal::write_port`].
    pub fn write_port(&self, mask: u8) -> Result<(), B::Error> {
        self.inner.write_port(mask)
    }

    /// Read all 8 pins as a bitmask. See [`Mcp23x08Minimal::read_port`].
    pub fn read_port(&self) -> Result<u8, B::Error> {
        self.inner.read_port()
    }

    /// Enable/disable per-pin 100 kΩ pull-ups (GPPU ← `mask`). Only effective on inputs.
    ///
    /// # Arguments
    /// * `mask` — bit n = 1 enables the pull-up on GPn.
    pub fn configure_pullup(&self, mask: u8) -> Result<(), B::Error> {
        self.pullup.set(mask);
        self.inner.write_reg(REG_GPPU, mask)
    }

    /// Configure input polarity inversion (IPOL ← `mask`).
    ///
    /// # Arguments
    /// * `mask` — bit n = 1 inverts the GPIO read of GPn.
    pub fn configure_polarity(&self, mask: u8) -> Result<(), B::Error> {
        self.inner.write_reg(REG_IPOL, mask)
    }

    /// Set DEFVAL for default-compare interrupts.
    pub fn set_default_value(&self, mask: u8) -> Result<(), B::Error> {
        self.inner.write_reg(REG_DEFVAL, mask)
    }

    /// Select the interrupt comparison per pin (INTCON ← `mask`).
    ///
    /// # Arguments
    /// * `mask` — bit n = 1 compares GPn against DEFVAL, 0 against its previous value.
    pub fn set_interrupt_control(&self, mask: u8) -> Result<(), B::Error> {
        self.inner.write_reg(REG_INTCON, mask)
    }

    /// Arm interrupt-on-change pins (GPINTEN ← `mask`). Only inputs can interrupt.
    pub fn enable_pin_interrupt(&self, mask: u8) -> Result<(), B::Error> {
        self.gpinten.set(mask);
        self.inner.write_reg(REG_GPINTEN, mask)
    }

    /// Configure the INT output (IOCON ODR / INTPOL).
    ///
    /// # Arguments
    /// * `open_drain`  — open-drain INT (overrides `active_high`).
    /// * `active_high` — active-high when push-pull.
    pub fn configure_int_pin(&self, open_drain: bool, active_high: bool) -> Result<(), B::Error> {
        let mut iocon = self.iocon.get() & !(IOCON_ODR | IOCON_INTPOL);
        if open_drain { iocon |= IOCON_ODR; } else if active_high { iocon |= IOCON_INTPOL; }
        self.iocon.set(iocon);
        self.inner.write_reg(REG_IOCON, iocon)
    }

    /// Enable or disable sequential register access (IOCON SEQOP).
    ///
    /// The driver itself never relies on a value other than enabled.
    pub fn set_sequential(&self, enabled: bool) -> Result<(), B::Error> {
        let mut iocon = self.iocon.get() & !IOCON_SEQOP;
        if !enabled { iocon |= IOCON_SEQOP; }
        self.iocon.set(iocon);
        self.inner.write_reg(REG_IOCON, iocon)
    }

    /// Raw register read (debug / escape hatch).
    pub fn read_register(&self, reg: u8) -> Result<u8, B::Error> {
        self.inner.read_reg(reg)
    }

    /// Raw register write; the IODIR / OLAT / IOCON / GPPU / GPINTEN shadows are kept in sync.
    pub fn write_register(&self, reg: u8, value: u8) -> Result<(), B::Error> {
        match reg {
            REG_IODIR   => self.inner.direction.set(value),
            REG_OLAT    => self.inner.shadow.set(value),
            REG_IOCON   => self.iocon.set(value),
            REG_GPPU    => self.pullup.set(value),
            REG_GPINTEN => self.gpinten.set(value),
            _ => {}
        }
        self.inner.write_reg(reg, value)
    }

    /// Rewrite every register to its reset value and clear the shadows.
    ///
    /// The chip has no reset command (RESET is an external pin). On the MCP23S08
    /// IOCON is rewritten to `HAEN` (not 0) so addressed access keeps working.
    pub fn reset(&self) -> Result<(), B::Error> {
        for reg in [REG_IPOL, REG_GPINTEN, REG_DEFVAL, REG_INTCON, REG_GPPU, REG_OLAT] {
            self.inner.write_reg(reg, 0x00)?;
        }
        self.inner.write_reg(REG_IODIR, 0xFF)?;
        let iocon = self.inner.iocon_base;
        self.inner.write_reg(REG_IOCON, iocon)?;
        self.iocon.set(iocon);
        self.inner.shadow.set(0x00);
        self.inner.direction.set(0xFF);
        self.pullup.set(0x00);
        self.gpinten.set(0x00);
        Ok(())
    }

    /// Read INTF, then INTCAP (clearing the interrupt); returns INTF.
    ///
    /// The captured port value is discarded — use [`read_capture`](Self::read_capture).
    ///
    /// Returns a mask where bit n = 1 means GPn caused the interrupt.
    pub fn poll_interrupt(&self) -> Result<u8, B::Error> {
        let flags = self.inner.read_reg(REG_INTF)?;
        self.inner.read_reg(REG_INTCAP)?;
        Ok(flags)
    }

    /// Read INTCAP: the port state captured at the interrupt. Also clears the interrupt.
    pub fn read_capture(&self) -> Result<u8, B::Error> {
        self.inner.read_reg(REG_INTCAP)
    }
}

impl<I2C: I2c> Mcp23x08Full<I2cBus<I2C>> {
    /// Create a new MCP23008 full driver (I²C).
    ///
    /// # Arguments
    /// * `i2c`  — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x20`–`0x27`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self::wrap(Mcp23x08Minimal::<I2cBus<I2C>>::new(i2c, addr)?))
    }
}

impl<SPI: SpiDevice> Mcp23x08Full<SpiBus<SPI>> {
    /// Create a new MCP23S08 full driver (SPI, mode 0, MSB first).
    ///
    /// # Arguments
    /// * `spi`  — [`embedded_hal::spi::SpiDevice`] bound to the chip's CS pin.
    /// * `addr` — hardware device address 0–3 (A1:A0 pins).
    pub fn new(spi: SPI, addr: u8) -> Result<Self, SPI::Error> {
        Ok(Self::wrap(Mcp23x08Minimal::<SpiBus<SPI>>::new(spi, addr)?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};
    use embedded_hal_mock::eh1::spi::{Mock as SpiMock, Transaction as SpiTransaction};

    const ADDR: u8 = 0x20;

    fn i2c_init() -> Vec<I2cTransaction> {
        vec![I2cTransaction::write(ADDR, vec![REG_OLAT, 0x00])]
    }

    fn w(reg: u8, v: u8) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![reg, v])
    }

    fn r(reg: u8, v: u8) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![reg], vec![v])
    }

    #[test]
    fn i2c_minimal_api() {
        let mut t = i2c_init();
        t.extend(vec![
            r(REG_GPIO, 0xA5),              // read_port
            w(REG_OLAT, 0x3C),              // write_port(0x3C)
            r(REG_GPIO, 0x04),              // pin(2).is_high()
            w(REG_OLAT, 0x00),              // write_port(0)
            w(REG_OLAT, 0x01),              // pin0 set_high
            w(REG_OLAT, 0x05),              // pin2 set_high
            w(REG_OLAT, 0x04),              // pin0 set_low
            w(REG_OLAT, 0x04),              // GP7 into_output: OLAT first ...
            w(REG_IODIR, 0x7F),             // ... then IODIR
            w(REG_IODIR, 0xFF),             // GP7 into_input
        ]);
        let chip = Mcp23008Minimal::new(I2cMock::new(&t), ADDR).expect("init");
        assert_eq!(chip.direction.get(), 0xFF);

        assert_eq!(chip.read_port().unwrap(), 0xA5);
        chip.write_port(0x3C).unwrap();
        assert_eq!(chip.shadow.get(), 0x3C);
        assert!(chip.pin(2).is_high().unwrap());

        chip.write_port(0x00).unwrap();
        chip.pin(0).set_high().unwrap();
        chip.pin(2).set_high().unwrap();
        chip.pin(0).set_low().unwrap();
        assert_eq!(chip.shadow.get(), 0x04);
        assert!(chip.pin(0).is_set_low().unwrap());
        assert!(chip.pin(2).is_set_high().unwrap());

        let mut p7 = chip.pin(7);
        p7.into_output().unwrap();
        assert_eq!(chip.direction.get(), 0x7F);
        p7.into_input().unwrap();
        assert_eq!(chip.direction.get(), 0xFF);

        chip.bus.borrow_mut().i2c.done();
    }

    #[test]
    fn i2c_full_api() {
        let mut t = i2c_init();
        t.extend(vec![
            w(REG_GPPU, 0xF0),
            w(REG_IPOL, 0x0F),
            w(REG_DEFVAL, 0x11),
            w(REG_INTCON, 0xF0),
            w(REG_GPINTEN, 0xF0),
            w(REG_IOCON, IOCON_INTPOL),
            w(REG_IOCON, IOCON_ODR),
            w(REG_IOCON, 0x00),
            w(REG_IOCON, IOCON_SEQOP),
            w(REG_IOCON, 0x00),
            r(REG_INTF, 0x10),              // poll_interrupt: INTF ...
            r(REG_INTCAP, 0xFF),            // ... then INTCAP (discarded)
            r(REG_INTCAP, 0x22),            // read_capture
            w(REG_IODIR, 0x0F),             // write_register shadows
            w(REG_OLAT, 0x81),
            r(REG_OLAT, 0x81),
            // reset
            w(REG_IPOL, 0x00), w(REG_GPINTEN, 0x00), w(REG_DEFVAL, 0x00),
            w(REG_INTCON, 0x00), w(REG_GPPU, 0x00), w(REG_OLAT, 0x00),
            w(REG_IODIR, 0xFF), w(REG_IOCON, 0x00),
        ]);
        let chip = Mcp23008Full::new(I2cMock::new(&t), ADDR).expect("init");

        chip.configure_pullup(0xF0).unwrap();
        chip.configure_polarity(0x0F).unwrap();
        chip.set_default_value(0x11).unwrap();
        chip.set_interrupt_control(0xF0).unwrap();
        chip.enable_pin_interrupt(0xF0).unwrap();
        chip.configure_int_pin(false, true).unwrap();
        chip.configure_int_pin(true, true).unwrap();
        chip.configure_int_pin(false, false).unwrap();
        chip.set_sequential(false).unwrap();
        chip.set_sequential(true).unwrap();

        assert_eq!(chip.poll_interrupt().unwrap(), 0x10);
        assert_eq!(chip.read_capture().unwrap(), 0x22);

        chip.write_register(REG_IODIR, 0x0F).unwrap();
        assert_eq!(chip.inner.direction.get(), 0x0F);
        chip.write_register(REG_OLAT, 0x81).unwrap();
        assert_eq!(chip.inner.shadow.get(), 0x81);
        assert_eq!(chip.read_register(REG_OLAT).unwrap(), 0x81);

        chip.reset().unwrap();
        assert_eq!(chip.inner.shadow.get(), 0x00);
        assert_eq!(chip.inner.direction.get(), 0xFF);

        chip.inner.bus.borrow_mut().i2c.done();
    }

    fn spi_w(bytes: &[u8]) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(bytes.to_vec()),
            SpiTransaction::transaction_end(),
        ]
    }

    fn spi_hdr_w(hdr: [u8; 2], v: u8) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(hdr.to_vec()),
            SpiTransaction::write_vec(vec![v]),
            SpiTransaction::transaction_end(),
        ]
    }

    fn spi_hdr_r(hdr: [u8; 2], v: u8) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(hdr.to_vec()),
            SpiTransaction::read_vec(vec![v]),
            SpiTransaction::transaction_end(),
        ]
    }

    #[test]
    fn spi_framing_and_haen() {
        let mut t = spi_w(&[0x40, REG_IOCON, IOCON_HAEN]);   // HAEN with the address-0 opcode
        t.extend(spi_hdr_w([0x44, REG_OLAT], 0x00));         // device address 2 -> opcode 0x44
        t.extend(spi_hdr_w([0x44, REG_OLAT], 0x5A));         // write_port
        t.extend(spi_hdr_r([0x45, REG_GPIO], 0xC3));         // read_port (R/W = 1)
        t.extend(spi_hdr_w([0x44, REG_GPPU], 0x0F));         // configure_pullup
        t.extend(spi_hdr_w([0x44, REG_IOCON], IOCON_HAEN | IOCON_ODR)); // HAEN preserved
        // reset keeps HAEN
        for reg in [REG_IPOL, REG_GPINTEN, REG_DEFVAL, REG_INTCON, REG_GPPU, REG_OLAT] {
            t.extend(spi_hdr_w([0x44, reg], 0x00));
        }
        t.extend(spi_hdr_w([0x44, REG_IODIR], 0xFF));
        t.extend(spi_hdr_w([0x44, REG_IOCON], IOCON_HAEN));
        let chip = Mcp23S08Full::new(SpiMock::new(&t), 2).expect("init");

        chip.write_port(0x5A).unwrap();
        assert_eq!(chip.read_port().unwrap(), 0xC3);
        chip.configure_pullup(0x0F).unwrap();
        chip.configure_int_pin(true, false).unwrap();
        chip.reset().unwrap();

        chip.inner.bus.borrow_mut().spi.done();
    }

    #[test]
    fn spi_minimal_pin() {
        let mut t = spi_w(&[0x40, REG_IOCON, IOCON_HAEN]);
        t.extend(spi_hdr_w([0x40, REG_OLAT], 0x00));
        t.extend(spi_hdr_w([0x40, REG_OLAT], 0x02));         // GP1 high
        let chip = Mcp23S08Minimal::new(SpiMock::new(&t), 0).expect("init");
        chip.pin(1).set_high().unwrap();
        chip.bus.borrow_mut().spi.done();
    }
}
