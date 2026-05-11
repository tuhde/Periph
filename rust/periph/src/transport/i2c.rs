//! I²C transport for chip drivers.
//!
//! Chip drivers in this library are generic over [`I2c`] from
//! `embedded-hal` 1.0. The device address is passed at construction time
//! along with the bus — a driver instance represents one device on the bus.
//!
//! ## Writing a chip driver
//!
//! Bound the I²C type parameter on [`I2c`]:
//!
//! ```rust
//! use periph::transport::i2c::I2c;
//!
//! pub struct MyChipMinimal<I2C> {
//!     i2c: I2C,
//!     addr: u8,
//! }
//!
//! impl<I2C: I2c> MyChipMinimal<I2C> {
//!     pub fn new(i2c: I2C, addr: u8) -> Self {
//!         Self { i2c, addr }
//!     }
//! }
//! ```
//!
//! ## Performing transactions
//!
//! Use the three core methods on [`I2c`] directly:
//!
//! ```rust,ignore
//! // Write: send register address + data
//! self.i2c.write(self.addr, &[reg, hi, lo])?;
//!
//! // Read: read n bytes from device
//! let mut buf = [0u8; 2];
//! self.i2c.read(self.addr, &mut buf)?;
//!
//! // Write-read: write register address, then read back data (repeated start)
//! self.i2c.write_read(self.addr, &[reg], &mut buf)?;
//! ```
//!
//! Use [`I2c::transaction`] with [`Operation`] slices for composite transfers:
//!
//! ```rust,ignore
//! use periph::transport::i2c::Operation;
//!
//! self.i2c.transaction(self.addr, &mut [
//!     Operation::Write(&[reg]),
//!     Operation::Read(&mut buf),
//! ])?;
//! ```
//!
//! ## Linux host (`linux-embedded-hal` crate)
//!
//! On Linux, open an `/dev/i2c-N` device using `linux-embedded-hal`'s
//! `I2cdev`, which directly implements [`I2c`]:
//!
//! ```rust,ignore
//! use linux_embedded_hal::I2cdev;
//!
//! let i2c = I2cdev::new("/dev/i2c-1")?;
//! // i2c implements I2c — pass it directly to chip driver constructors
//! let chip = MyChipMinimal::new(i2c, 0x40);
//! ```
//!
//! Add to `Cargo.toml`:
//! ```toml
//! linux-embedded-hal = "0.4"
//! embedded-hal = "1"
//! ```

/// The trait every I²C chip driver is generic over.
///
/// Re-exported from [`embedded_hal::i2c::I2c`]. Provides `write`, `read`,
/// `write_read`, and `transaction` — the four primitives used by chip drivers.
pub use embedded_hal::i2c::I2c;

/// Operation type for [`I2c::transaction`] calls.
///
/// Re-exported from [`embedded_hal::i2c::Operation`]. Variants are
/// `Operation::Write(&[u8])` and `Operation::Read(&mut [u8])`.
pub use embedded_hal::i2c::Operation;
