//! DHT11 — combined temperature and humidity sensor (ASAIR / Aosong).
//!
//! The DHT11 returns a 40-bit reading (humidity integer + decimal,
//! temperature integer + decimal, checksum) over a single bidirectional
//! data line. This crate provides the chip-side driver; the single-wire
//! connection is implemented in [`crate::connection::dhtxx`].
//!
//! ## Usage
//!
//! ```rust,ignore
//! use periph::connection::dhtxx::DHTxxConnectionLinux;
//! use periph::chips::humidity::Dht11Minimal;
//!
//! let connection = DHTxxConnectionLinux::new(pin);
//! let mut sensor = Dht11Minimal::new(connection);
//! let (temperature, humidity) = sensor.read()?;
//! ```

use crate::connection::dhtxx::{DHTxxError, DHTxxConnectionEsp32s3, DHTxxConnectionLinux};

/// Error type for DHT11 operations.
#[derive(Debug)]
pub enum Dht11Error<PE> {
    /// Connection-level error.
    Connection(DHTxxError<PE>),
    /// The frame's checksum is invalid.
    Checksum { expected: u8, got: u8 },
    /// The frame has the wrong length.
    BadFrame,
}

impl<PE> From<DHTxxError<PE>> for Dht11Error<PE> {
    fn from(e: DHTxxError<PE>) -> Self {
        Dht11Error::Connection(e)
    }
}

const FRAME_LEN: usize = 5;

fn decode_frame(frame: &[u8; FRAME_LEN]) -> Result<(f32, f32), Dht11Error<embedded_hal::digital::ErrorKind>> {
    let expected = (frame[0] as u16 + frame[1] as u16 + frame[2] as u16 + frame[3] as u16) as u8;
    if expected != frame[4] {
        return Err(Dht11Error::Checksum { expected, got: frame[4] });
    }
    let humidity = frame[0] as f32 + (frame[1] as f32) / 10.0;
    let sign: i32 = if frame[3] & 0x80 != 0 { -1 } else { 1 };
    let temp_dec_value = (frame[3] & 0x7F) as f32;
    let temperature = (sign as f32) * (frame[2] as f32 + temp_dec_value / 10.0);
    Ok((temperature, humidity))
}

// ============================================================================
// Linux host
// ============================================================================

/// DHT11 minimal interface (Linux host).
pub struct Dht11Minimal<P> {
    conn: DHTxxConnectionLinux<P>,
}

impl<P> Dht11Minimal<P>
where
    P: embedded_hal::digital::OutputPin + embedded_hal::digital::InputPin,
{
    /// Create a new DHT11 driver from a Linux DHTxx connection.
    pub fn new(connection: DHTxxConnectionLinux<P>) -> Self {
        Self { conn: connection }
    }

    /// Read both temperature and humidity in a single transaction.
    pub fn read(&mut self) -> Result<(f32, f32), Dht11Error<P::Error>> {
        let frame = self.conn.read()?;
        decode_frame(&frame).map_err(|e| match e {
            Dht11Error::Checksum { expected, got } => Dht11Error::Checksum { expected, got },
            _ => Dht11Error::BadFrame,
        })
    }
}

/// DHT11 full interface (Linux host).
pub struct Dht11Full<P> {
    conn: DHTxxConnectionLinux<P>,
    max_retries: u8,
}

impl<P> Dht11Full<P>
where
    P: embedded_hal::digital::OutputPin + embedded_hal::digital::InputPin,
{
    /// Create a new DHT11 full driver.
    pub fn new(conn: DHTxxConnectionLinux<P>, max_retries: u8) -> Self {
        Self { conn, max_retries }
    }

    /// Read both temperature and humidity in a single transaction.
    pub fn read(&mut self) -> Result<(f32, f32), Dht11Error<P::Error>> {
        let frame = self.conn.read()?;
        decode_frame(&frame).map_err(|e| match e {
            Dht11Error::Checksum { expected, got } => Dht11Error::Checksum { expected, got },
            _ => Dht11Error::BadFrame,
        })
    }

    /// Read temperature only.
    pub fn read_temperature(&mut self) -> Result<f32, Dht11Error<P::Error>> {
        Ok(self.read()?.0)
    }

    /// Read humidity only.
    pub fn read_humidity(&mut self) -> Result<f32, Dht11Error<P::Error>> {
        Ok(self.read()?.1)
    }

    /// Read both values, retrying on checksum error.
    pub fn read_retry(&mut self, max_retries: u8) -> Result<(f32, f32), Dht11Error<P::Error>> {
        let n = if max_retries == 0 { self.max_retries } else { max_retries };
        let mut last_err: Option<Dht11Error<P::Error>> = None;
        for _ in 0..n {
            match self.read() {
                Ok(v) => return Ok(v),
                Err(e) => last_err = Some(e),
            }
        }
        Err(last_err.unwrap_or(Dht11Error::BadFrame))
    }

    /// Read the raw 5-byte frame (validated).
    pub fn read_raw(&mut self) -> Result<[u8; FRAME_LEN], Dht11Error<P::Error>> {
        let frame = self.conn.read()?;
        decode_frame(&frame).map_err(|e| match e {
            Dht11Error::Checksum { expected, got } => Dht11Error::Checksum { expected, got },
            _ => Dht11Error::BadFrame,
        })?;
        Ok(frame)
    }
}

// ============================================================================
// ESP32-S3 bare metal
// ============================================================================

/// DHT11 minimal interface (ESP32-S3).
pub struct Dht11MinimalEsp32s3<P> {
    conn: DHTxxConnectionEsp32s3<P>,
}

impl<P> Dht11MinimalEsp32s3<P>
where
    P: embedded_hal::digital::InputPin,
{
    /// Create a new DHT11 driver from an ESP32-S3 DHTxx connection.
    pub fn new(connection: DHTxxConnectionEsp32s3<P>) -> Self {
        Self { conn: connection }
    }

    /// Read both temperature and humidity in a single transaction.
    ///
    /// `delay_ms` is the HAL's millisecond delay (used for the 20 ms start
    /// pulse) and `delay_us` is the µs-scale delay (used during bit timing).
    pub fn read(
        &mut self,
        delay_ms: impl FnMut(u32),
        delay_us: impl FnMut(u32),
    ) -> Result<(f32, f32), Dht11Error<P::Error>> {
        let frame = self.conn.read_with_delay(delay_ms, delay_us)?;
        decode_frame(&frame).map_err(|e| match e {
            Dht11Error::Checksum { expected, got } => Dht11Error::Checksum { expected, got },
            _ => Dht11Error::BadFrame,
        })
    }
}
