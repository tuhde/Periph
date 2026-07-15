//! DHT11 temperature and humidity sensor (ASAIR).
//!
//! Communicates via a custom single-wire bidirectional GPIO protocol. The
//! host drives LOW during the start signal and listens during the 40-bit
//! data phase. Use [`DHT11Transport`] from [`crate::transport::dht11`] to
//! construct the transport (two pin handles, one for input and one for
//! output — `embedded-hal` 1.0 has no `IoPin` trait), then pass it to
//! [`Dht11Minimal`] or [`Dht11Full`].
//!
//! ## Typical workflow
//!
//! ```rust,ignore
//! use periph::transport::dht11::DHT11Transport;
//! use periph::chips::humidity::{Dht11Minimal, Dht11Full};
//!
//! let transport = DHT11Transport::new(input_pin, output_pin);
//! let mut chip = Dht11Full::new(transport)?;
//!
//! loop {
//!     let (t, h) = chip.read(&mut delay)?;
//!     println!("T={:.1} C  H={:.1} %RH", t, h);
//! }
//! ```
//!
//! Callers must respect the 2-second minimum sampling interval between reads.

use crate::transport::dht11::{DHT11Error, DHT11Transport};
use embedded_hal::delay::DelayNs;
use embedded_hal::digital::{InputPin, OutputPin};

/// DHT11 minimal driver — reads temperature and humidity with a single call.
///
/// Performs one full protocol transaction and returns temperature in °C and
/// humidity in %RH. Throws [`DHT11Error::Checksum`] on checksum mismatch or
/// [`DHT11Error::Timeout`] if the sensor does not respond.
pub struct Dht11Minimal<DI, DO> {
    transport: DHT11Transport<DI, DO>,
}

impl<DI, DO> Dht11Minimal<DI, DO>
where
    DI: InputPin,
    DO: OutputPin,
{
    /// Create a new `Dht11Minimal`.
    ///
    /// # Arguments
    ///
    /// * `transport` — Configured [`DHT11Transport`] with both DATA pin handles.
    pub fn new(transport: DHT11Transport<DI, DO>) -> Self {
        Self { transport }
    }

    /// Perform a full protocol read.
    ///
    /// Returns `(temperature_c, humidity_rh)`.
    pub fn read<D: DelayNs>(
        &mut self,
        delay: &mut D,
    ) -> Result<(f32, f32), DHT11Error<DI::Error, DO::Error>> {
        let b = self.transport.read_frame(delay)?;
        Ok(decode_frame(&b))
    }
}

/// DHT11 full driver — extends [`Dht11Minimal`] with retry, separate accessors, and raw frame access.
///
/// Adds `read_retry()` (automatic retry on checksum/timeout), `read_temperature()`
/// / `read_humidity()` convenience accessors, and `read_raw()` for the 5-byte
/// frame.
pub struct Dht11Full<DI, DO> {
    inner:  Dht11Minimal<DI, DO>,
    t:      f32,
    h:      f32,
}

impl<DI, DO> Dht11Full<DI, DO>
where
    DI: InputPin,
    DO: OutputPin,
{
    /// Create a new `Dht11Full`.
    pub fn new(transport: DHT11Transport<DI, DO>) -> Self {
        Self { inner: Dht11Minimal::new(transport), t: 0.0, h: 0.0 }
    }

    /// Read with automatic retry on checksum/timeout failure.
    ///
    /// # Arguments
    ///
    /// * `delay`      — Delay provider for microsecond timing.
    /// * `max_retries` — Maximum number of attempts (default 3).
    ///
    /// Returns `(temperature_c, humidity_rh)` on success. Returns
    /// [`DHT11Error::Checksum`] or [`DHT11Error::Timeout`] after all retries
    /// have been exhausted (the last error encountered is propagated).
    pub fn read_retry<D: DelayNs>(
        &mut self,
        delay: &mut D,
        max_retries: u8,
    ) -> Result<(f32, f32), DHT11Error<DI::Error, DO::Error>> {
        let mut last_err = DHT11Error::Timeout;
        for _ in 0..max_retries {
            match self.inner.read(delay) {
                Ok((t, h)) => {
                    self.t = t;
                    self.h = h;
                    return Ok((t, h));
                }
                Err(e) => last_err = e,
            }
        }
        Err(last_err)
    }

    /// Read the raw 5-byte frame without interpretation.
    pub fn read_raw<D: DelayNs>(
        &mut self,
        delay: &mut D,
    ) -> Result<[u8; 5], DHT11Error<DI::Error, DO::Error>> {
        self.inner.transport.read_frame(delay)
    }

    /// Return the last temperature reading in °C.
    pub fn read_temperature(&self) -> f32 { self.t }

    /// Return the last humidity reading in %RH.
    pub fn read_humidity(&self) -> f32 { self.h }
}

/// Decode a raw 5-byte DHT11 frame into (temperature_c, humidity_rh).
///
/// The frame is `[hum_int, hum_dec, temp_int, temp_dec, checksum]` where
/// temperature sign is bit 7 of `temp_dec`. The caller is responsible for
/// checksum validation; this function does not re-verify.
pub fn decode_frame(b: &[u8; 5]) -> (f32, f32) {
    let sign     = if b[3] & 0x80 != 0 { -1.0 } else { 1.0 };
    let temp_dec = (b[3] & 0x7F) as f32;
    let temperature_c = sign * (b[2] as f32 + temp_dec / 10.0);
    let humidity_rh   = b[0] as f32 + b[1] as f32 / 10.0;
    (temperature_c, humidity_rh)
}
