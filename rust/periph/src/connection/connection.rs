//! Generic software-enable wrapper around an `embedded-hal` I²C bus.
//!
//! Rust never had a periph-owned struct wrapping [`I2c`] the way other
//! languages wrap their bus implementations — chip drivers here are, and
//! remain, generic directly over `embedded_hal::i2c::I2c` /
//! `embedded_hal::spi::SpiBus`. [`Connection`] is new, optional code: it adds
//! a software enable/disable gate around a generic bus for chip drivers that
//! want one, without requiring every existing I²C/SPI chip driver to change.
//!
//! For hardware EN pin control, drive an `embedded_hal::digital::OutputPin`
//! directly before constructing the chip — no wrapper is needed for that:
//!
//! ```rust,ignore
//! en_pin.set_high().unwrap();    // power up the chip
//! let conn = Connection::new(i2c);
//! let imu  = Mpu6050Minimal::new(conn);
//! ```

use embedded_hal::i2c::I2c;

/// Software-enable wrapper around a generic I²C bus.
///
/// While disabled, [`Connection::read`] zero-fills the destination buffer
/// and [`Connection::write`] is a no-op — neither touches the bus.
pub struct Connection<BUS> {
    pub(crate) bus: BUS,
    enabled: bool,
}

impl<BUS> Connection<BUS>
where
    BUS: I2c,
{
    /// Wrap `bus`, enabled by default.
    pub fn new(bus: BUS) -> Self {
        Self { bus, enabled: true }
    }

    /// Resume bus access.
    pub fn enable(&mut self) { self.enabled = true; }

    /// Gate [`Connection::read`]/[`Connection::write`] — reads zero-fill,
    /// writes become no-ops, without touching the bus.
    pub fn disable(&mut self) { self.enabled = false; }

    /// Return the current software-gate state.
    pub fn is_enabled(&self) -> bool { self.enabled }

    /// Release the underlying bus, consuming the connection.
    pub fn release(self) -> BUS {
        self.bus
    }

    /// Write `reg` then read back `buf.len()` bytes (repeated start).
    ///
    /// Zero-fills `buf` without touching the bus if disabled.
    pub(crate) fn read(&mut self, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), BUS::Error> {
        if !self.enabled {
            buf.fill(0);
            return Ok(());
        }
        self.bus.write_read(addr, &[reg], buf)
    }

    /// Write `reg` followed by `data` in one transaction.
    ///
    /// No-op if disabled.
    pub(crate) fn write(&mut self, addr: u8, reg: u8, data: &[u8]) -> Result<(), BUS::Error> {
        if !self.enabled {
            return Ok(());
        }
        let mut buf = [0u8; 17];
        buf[0] = reg;
        buf[1..=data.len()].copy_from_slice(data);
        self.bus.write(addr, &buf[..=data.len()])
    }
}
