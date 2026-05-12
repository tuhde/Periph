//! SMBus transport wrapper for chip drivers.
//!
//! Wraps any [`I2c`] implementation and adds 7-bit address validation and
//! optional PEC (Packet Error Code) CRC-8 checking. The wrapper itself
//! implements [`I2c`], so chip drivers generic over [`I2c`] accept an
//! [`SmBusTransport`] directly without knowing about SMBus specifics.
//!
//! ## Usage
//!
//! ```rust,ignore
//! use linux_embedded_hal::I2cdev;
//! use periph::transport::smbus::SmBusTransport;
//!
//! let i2c = I2cdev::new("/dev/i2c-1")?;
//! let smbus = SmBusTransport::new(i2c, 0x40, false)?;
//! // smbus implements I2c — pass it directly to chip driver constructors
//! let chip = MyChip::new(smbus, 0x40);
//! ```
//!
//! ## PEC computation
//!
//! CRC-8 with polynomial `x⁸ + x² + x + 1` (0x07), initial value 0x00.
//!
//! | Operation    | Bytes covered by CRC                                             |
//! |--------------|------------------------------------------------------------------|
//! | `write`      | `(addr << 1)` + data                                            |
//! | `read`       | `(addr << 1) \| 1` + received data                              |
//! | `write_read` | `(addr << 1)` + write data + `(addr << 1) \| 1` + received data |

use core::fmt;
use embedded_hal::i2c::{self, ErrorKind, ErrorType, I2c, Operation};

fn crc8_update(mut crc: u8, byte: u8) -> u8 {
    crc ^= byte;
    for _ in 0..8 {
        crc = if crc & 0x80 != 0 { (crc << 1) ^ 0x07 } else { crc << 1 };
    }
    crc
}

/// Error type returned by [`SmBusTransport`] operations.
#[derive(Debug)]
pub enum SmBusError<E: fmt::Debug> {
    /// Underlying I²C bus error.
    I2c(E),
    /// Received PEC byte did not match the computed CRC-8.
    PecMismatch,
    /// Device address is outside the valid SMBus range (0x08–0x77).
    InvalidAddress,
}

impl<E: i2c::Error + fmt::Debug> i2c::Error for SmBusError<E> {
    fn kind(&self) -> ErrorKind {
        match self {
            Self::I2c(e) => e.kind(),
            Self::PecMismatch | Self::InvalidAddress => ErrorKind::Other,
        }
    }
}

/// SMBus transport wrapper.
///
/// Wraps any [`I2c`] implementation and adds:
/// - 7-bit address validation (0x08–0x77) at construction time
/// - Optional software PEC (CRC-8) on all reads and writes
///
/// Implements [`I2c`] so chip drivers generic over [`I2c`] can accept an
/// `SmBusTransport` directly. The per-call `address` parameter in each
/// [`I2c`] method is used for PEC computation.
pub struct SmBusTransport<I2C> {
    i2c: I2C,
    pec: bool,
}

impl<I2C: I2c> SmBusTransport<I2C> {
    /// Create a new SMBus transport, validating the device address range.
    ///
    /// Returns `Err(SmBusError::InvalidAddress)` if `addr` is outside the
    /// valid SMBus range 0x08–0x77. The address is not stored after
    /// validation; per-call addresses in [`I2c`] methods are used for PEC.
    pub fn new(i2c: I2C, addr: u8, pec: bool) -> Result<Self, SmBusError<I2C::Error>> {
        if addr < 0x08 || addr > 0x77 {
            return Err(SmBusError::InvalidAddress);
        }
        Ok(Self { i2c, pec })
    }

    /// Release the underlying I²C bus, consuming the transport.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

impl<I2C: I2c> ErrorType for SmBusTransport<I2C> {
    type Error = SmBusError<I2C::Error>;
}

impl<I2C: I2c> I2c for SmBusTransport<I2C> {
    fn write(&mut self, address: u8, write: &[u8]) -> Result<(), Self::Error> {
        if self.pec {
            let mut buf = [0u8; 256];
            let len = write.len();
            buf[..len].copy_from_slice(write);
            let mut crc = crc8_update(0, address << 1);
            for &b in write {
                crc = crc8_update(crc, b);
            }
            buf[len] = crc;
            self.i2c.write(address, &buf[..len + 1]).map_err(SmBusError::I2c)
        } else {
            self.i2c.write(address, write).map_err(SmBusError::I2c)
        }
    }

    fn read(&mut self, address: u8, read: &mut [u8]) -> Result<(), Self::Error> {
        if self.pec {
            let n = read.len();
            let mut tmp = [0u8; 256];
            self.i2c.read(address, &mut tmp[..n + 1]).map_err(SmBusError::I2c)?;
            read.copy_from_slice(&tmp[..n]);
            let mut crc = crc8_update(0, (address << 1) | 1);
            for &b in &tmp[..n] {
                crc = crc8_update(crc, b);
            }
            if crc != tmp[n] {
                return Err(SmBusError::PecMismatch);
            }
            Ok(())
        } else {
            self.i2c.read(address, read).map_err(SmBusError::I2c)
        }
    }

    fn write_read(&mut self, address: u8, write: &[u8], read: &mut [u8]) -> Result<(), Self::Error> {
        if self.pec {
            let n = read.len();
            let mut tmp = [0u8; 256];
            self.i2c.write_read(address, write, &mut tmp[..n + 1]).map_err(SmBusError::I2c)?;
            read.copy_from_slice(&tmp[..n]);
            let mut crc = crc8_update(0, address << 1);
            for &b in write {
                crc = crc8_update(crc, b);
            }
            crc = crc8_update(crc, (address << 1) | 1);
            for &b in &tmp[..n] {
                crc = crc8_update(crc, b);
            }
            if crc != tmp[n] {
                return Err(SmBusError::PecMismatch);
            }
            Ok(())
        } else {
            self.i2c.write_read(address, write, read).map_err(SmBusError::I2c)
        }
    }

    fn transaction(&mut self, address: u8, operations: &mut [Operation<'_>]) -> Result<(), Self::Error> {
        self.i2c.transaction(address, operations).map_err(SmBusError::I2c)
    }
}
