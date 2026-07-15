//! DHT11 GPIO bit-bang transport for chip drivers.
//!
//! The DHT11 uses a custom single-wire bidirectional protocol: one DATA line
//! that the host drives LOW during the start signal and listens on during
//! the sensor response and 40-bit data phase. `embedded-hal` 1.0 has no
//! `IoPin` trait, so this transport owns **two** `embedded_hal::digital` pin
//! types — one for reading (input) and one for driving (output). The user
//! passes platform-specific pin handles to each.
//!
//! On Linux, request the same line twice with different directions via
//! `gpio-cdev` (or use a platform helper that re-requests a single line per
//! phase). On ESP32-S3, the HAL's `Flex`/`AnyFlex` pin can change direction
//! — the user can use the same handle as both `InputPin` and `OutputPin`
//! by obtaining clones via the HAL's split methods.
//!
//! ## Usage
//!
//! ```rust,ignore
//! use periph::transport::dht11::DHT11Transport;
//!
//! let transport = DHT11Transport::new(input_pin, output_pin);
//! let (t, h) = transport.read_frame(&mut delay)?;
//! ```
//!
//! Add to `Cargo.toml`:
//! ```toml
//! linux-embedded-hal = "0.4"
//! embedded-hal = "1"
//! ```

use embedded_hal::delay::DelayNs;
use embedded_hal::digital::{InputPin, OutputPin};

/// Error type for [`DHT11Transport`] operations.
#[derive(Debug)]
pub enum DHT11Error<DE, OE> {
    /// Error reading the DATA pin.
    Read(DE),
    /// Error driving the DATA pin.
    Write(OE),
    /// Sensor did not respond within the timeout.
    Timeout,
    /// Checksum mismatch on the 40-bit frame.
    Checksum,
}

/// DHT11 GPIO bit-bang transport.
///
/// Generic over any `embedded-hal` 1.0 [`InputPin`] and [`OutputPin`] pair.
/// The same physical GPIO line is represented by two pin handles (one for
/// each direction); the user obtains them via platform-specific helpers.
pub struct DHT11Transport<DI, DO> {
    data_in:  DI,
    data_out: DO,
}

impl<DI, DO> DHT11Transport<DI, DO>
where
    DI: InputPin,
    DO: OutputPin,
{
    /// Create a new transport.
    ///
    /// # Arguments
    ///
    /// * `data_in`  – Pin handle used in input mode (sampling the line).
    /// * `data_out` – Pin handle used in output mode (driving the line).
    ///
    /// The line is left in input mode after construction; an external 4.7 kΩ
    /// pull-up to VCC is required on the DATA line.
    pub fn new(data_in: DI, mut data_out: DO) -> Self {
        let _ = data_out.set_low();
        Self { data_in, data_out }
    }

    /// Perform a full DHT11 transaction and return the raw 5-byte frame.
    ///
    /// Issues the host start signal (DATA LOW for 20 ms, then HIGH for 30 µs),
    /// samples the sensor response, and decodes 40 bits.
    ///
    /// # Errors
    ///
    /// Returns [`DHT11Error::Timeout`] if the sensor does not respond, or
    /// [`DHT11Error::Checksum`] if the 40-bit frame checksum is invalid.
    pub fn read_frame<D: DelayNs>(
        &mut self,
        delay: &mut D,
    ) -> Result<[u8; 5], DHT11Error<DI::Error, DO::Error>> {
        // --- Host start signal ---
        self.data_out.set_low().map_err(DHT11Error::Write)?;
        delay.delay_ms(20);
        self.data_out.set_high().map_err(DHT11Error::Write)?;
        delay.delay_us(30);

        // --- Wait for sensor response (LOW, HIGH, LOW) ---
        self.wait_low(200, delay)?;
        self.wait_high(200, delay)?;
        self.wait_low(200, delay)?;

        // --- Read 40 bits ---
        let mut bits: u64 = 0;
        for _ in 0..40 {
            self.wait_high(200, delay)?;
            let high_us = self.measure_high(delay);
            bits = (bits << 1) | if high_us > 40 { 1u64 } else { 0u64 };
        }

        let b = [
            ((bits >> 32) & 0xFF) as u8,
            ((bits >> 24) & 0xFF) as u8,
            ((bits >> 16) & 0xFF) as u8,
            ((bits >>  8) & 0xFF) as u8,
            ( bits        & 0xFF) as u8,
        ];
        let checksum = b[0].wrapping_add(b[1]).wrapping_add(b[2]).wrapping_add(b[3]);
        if checksum != b[4] { return Err(DHT11Error::Checksum); }
        Ok(b)
    }

    /// Wait until the line reads LOW, with a timeout in microseconds.
    fn wait_low<D: DelayNs>(
        &mut self,
        timeout_us: u32,
        delay: &mut D,
    ) -> Result<(), DHT11Error<DI::Error, DO::Error>> {
        let mut elapsed: u32 = 0;
        while self.data_in.is_high().map_err(DHT11Error::Read)? {
            if elapsed >= timeout_us { return Err(DHT11Error::Timeout); }
            delay.delay_us(1);
            elapsed += 1;
        }
        Ok(())
    }

    /// Wait until the line reads HIGH, with a timeout in microseconds.
    fn wait_high<D: DelayNs>(
        &mut self,
        timeout_us: u32,
        delay: &mut D,
    ) -> Result<(), DHT11Error<DI::Error, DO::Error>> {
        let mut elapsed: u32 = 0;
        while self.data_in.is_low().map_err(DHT11Error::Read)? {
            if elapsed >= timeout_us { return Err(DHT11Error::Timeout); }
            delay.delay_us(1);
            elapsed += 1;
        }
        Ok(())
    }

    /// Measure the duration of a HIGH pulse in microseconds (capped at 100 µs).
    fn measure_high<D: DelayNs>(&mut self, delay: &mut D) -> u32 {
        let mut elapsed: u32 = 0;
        while self.data_in.is_high().unwrap_or(false) {
            if elapsed >= 100 { break; }
            delay.delay_us(1);
            elapsed += 1;
        }
        elapsed
    }

    /// Consume the transport and return the two pin handles.
    pub fn release(self) -> (DI, DO) {
        (self.data_in, self.data_out)
    }
}
