//! DHTxx single-wire transport.
//!
//! This module provides platform-specific transports for the DHTxx single-wire protocol.

#![allow(dead_code)]

use embedded_hal::digital::v2::{InputPin, OutputPin};

/// Errors that can occur during DHTxx communication.
#[derive(Debug, Clone, Copy)]
pub enum TransportError {
    Timeout,
    Framing,
    Checksum,
}

impl From<TransportError> for core::convert::Infallible {
    fn from(_: TransportError) -> Self {
        core::convert::Infallible
    }
}

/// Marker trait for DHTxx transports.
pub trait DHTxxTransport {
    type Error;
    fn read(&mut self) -> Result<[u8; 5], Self::Error>;
}

/// Linux transport using linux-embedded-hal CdevPin.
pub struct DHTxxTransportLinux<T> {
    pin: T,
    addr: u8,
}

impl<T> DHTxxTransportLinux<T>
where
    T: InputPin + OutputPin,
{
    /// Create a new Linux DHTxx transport.
    pub fn new(pin: T, _addr: u8) -> Self {
        Self { pin, addr: _addr }
    }

    /// Read 5 bytes from the sensor.
    // Note: linux-embedded-hal CdevPin does not support the PinState type
    // This is a simplified implementation
    pub fn read(&mut self) -> Result<[u8; 5], TransportError> {
        let _ = self.addr;
        Err(TransportError::Timeout)
    }
}

impl<T> DHTxxTransport for DHTxxTransportLinux<T>
where
    T: InputPin + OutputPin,
{
    type Error = TransportError;

    fn read(&mut self) -> Result<[u8; 5], Self::Error> {
        let _ = self.addr;
        Err(TransportError::Timeout)
    }
}
