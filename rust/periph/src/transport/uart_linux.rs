//! Linux UART transport using the `serialport` crate.
//!
//! [`LinuxUart`] wraps a `Box<dyn serialport::SerialPort>` in a newtype that
//! implements [`embedded_io::Read`] + [`embedded_io::Write`], so chip drivers
//! generic over those traits work unchanged on Linux.
//!
//! ## Usage
//!
//! ```rust,ignore
//! use periph::transport::uart_linux::LinuxUart;
//!
//! let uart = LinuxUart::open("/dev/ttyS0", 9600)?;
//! // pass `uart` to any chip driver that takes `impl Read + Write`
//! ```
//!
//! `Cargo.toml` dependencies for Linux examples and tests:
//!
//! ```toml
//! serialport = "4"
//! embedded-io = "0.6"
//! ```

use embedded_io::{ErrorType, Read, Write};
use serialport::SerialPort;
use std::io;

/// Wraps a [`SerialPort`] and implements [`Read`] + [`Write`] from
/// `embedded_io` 0.6, bridging the `std::io` error type.
pub struct LinuxUart(pub Box<dyn SerialPort>);

impl LinuxUart {
    /// Open a serial port at the given baud rate with default settings
    /// (8N1, 1-second read timeout).
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if the port cannot be opened or configured.
    pub fn open(path: &str, baud_rate: u32) -> io::Result<Self> {
        let port = serialport::new(path, baud_rate)
            .timeout(std::time::Duration::from_secs(1))
            .open()
            .map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;
        Ok(LinuxUart(port))
    }
}

impl ErrorType for LinuxUart {
    type Error = io::Error;
}

impl Read for LinuxUart {
    fn read(&mut self, buf: &mut [u8]) -> Result<usize, Self::Error> {
        io::Read::read(&mut self.0, buf)
    }
}

impl Write for LinuxUart {
    fn write(&mut self, buf: &[u8]) -> Result<usize, Self::Error> {
        io::Write::write(&mut self.0, buf)
    }

    fn flush(&mut self) -> Result<(), Self::Error> {
        io::Write::flush(&mut self.0)
    }
}
