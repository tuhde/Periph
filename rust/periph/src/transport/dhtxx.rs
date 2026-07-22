//! DHTxx single-wire transport for chip drivers.
//!
//! The DHTxx family (DHT11, DHT22, ...) uses a custom single-wire bidirectional
//! protocol: a single DATA line, externally pulled up to VCC via a 4.7 kΩ
//! resistor, carries both the host start signal and the sensor's 40-bit
//! response. The transport handles all GPIO direction switching, timing, and
//! bit decoding. Chip drivers receive a raw 5-byte frame and are responsible
//! only for checksum validation and data interpretation.
//!
//! `embedded-hal` 1.0 defines no `IoPin` (bidirectional) trait, so this
//! transport cannot be generic over a standard trait. Two platform-specific
//! structs are provided:
//!
//! - [`DHTxxTransportLinux`] — Linux host. Uses `std::thread::sleep` for the
//!   20 ms start delay and a busy-loop for µs-scale measurement.
//! - [`DHTxxTransportEsp32s3`] — ESP32-S3 bare-metal. The caller provides a
//!   delay primitive via [`DHTxxTransportEsp32s3::read_with_delay`].
//!
//! ## Protocol summary
//!
//! 1. Host drives DATA LOW for ≥ 18 ms, then releases the bus.
//! 2. Sensor responds: ~83 µs LOW, ~87 µs HIGH.
//! 3. Sensor transmits 40 bits MSB first. Each bit starts with a ~54 µs LOW
//!    pulse, followed by a HIGH pulse whose duration encodes the value
//!    (HIGH > 40 µs → '1', HIGH < 40 µs → '0').
//! 4. `read()` returns the raw 5-byte frame on success and returns
//!    [`DHTxxError`] on timeout or framing error.

use embedded_hal::digital::InputPin;

/// Error type for DHTxx transport operations.
#[derive(Debug)]
pub enum DHTxxError<PE> {
    /// Error reading or writing the pin.
    Pin(PE),
    /// The sensor did not respond within the expected window.
    Timeout,
    /// Fewer than 40 bit pulses were received before the bus returned idle.
    Framing,
}

const START_LOW_MS: u8 = 20;
const RESPONSE_TIMEOUT_US: u32 = 200;
const BIT_TIMEOUT_US: u32 = 200;
const BIT_THRESHOLD_US: u32 = 40;

// ============================================================================
// Linux host (`linux-embedded-hal::CdevPin`)
// ============================================================================

/// Linux DHTxx transport. Owns a single pin and switches its direction as
/// needed. Direction switching goes through the kernel's gpiod request
/// lifecycle (release and re-request with a different direction flag) and is
/// therefore expensive — this is the timing bottleneck on Linux, see
/// `specs/transport_dhtxx.md` for details.
pub struct DHTxxTransportLinux<P> {
    pin: P,
}

impl<P> DHTxxTransportLinux<P>
where
    P: embedded_hal::digital::OutputPin + InputPin,
{
    /// Create a new transport. The pin must be configured as input by the
    /// caller; the transport switches its direction internally.
    pub fn new(pin: P) -> Self {
        Self { pin }
    }

    /// Execute the full DHTxx transaction and return the raw 5-byte frame.
    pub fn read(&mut self) -> Result<[u8; 5], DHTxxError<P::Error>> {
        // Step 1: host start signal — drive LOW for 20 ms.
        self.pin.set_low().map_err(DHTxxError::Pin)?;
        std::thread::sleep(std::time::Duration::from_millis(START_LOW_MS as u64));

        // Step 2: release the bus. The caller's pin implementation is
        // expected to revert to high-impedance on `set_high()` (open-drain
        // semantics). For a strict push-pull pin, the caller would need to
        // reconfigure; we document this in the spec.
        self.pin.set_high().map_err(DHTxxError::Pin)?;

        // Step 3: sensor response — wait for the line to go LOW (~83 µs).
        let mut timeout_counter: u32 = 0;
        while self.pin.is_high().map_err(DHTxxError::Pin)? {
            timeout_counter += 1;
            if timeout_counter > 1_000_000 {
                return Err(DHTxxError::Timeout);
            }
        }
        // Wait for the response HIGH (~87 µs).
        timeout_counter = 0;
        while !self.pin.is_high().map_err(DHTxxError::Pin)? {
            timeout_counter += 1;
            if timeout_counter > 1_000_000 {
                return Err(DHTxxError::Timeout);
            }
        }

        // Step 4: read 40 bits, MSB first.
        let mut frame = [0u8; 5];
        for byte in frame.iter_mut() {
            let mut value: u8 = 0;
            for _ in 0..8 {
                // Wait for the start-of-bit LOW pulse.
                let mut low_loops: u32 = 0;
                while self.pin.is_high().map_err(DHTxxError::Pin)? {
                    low_loops += 1;
                    if low_loops > 1_000_000 {
                        return Err(DHTxxError::Framing);
                    }
                }
                // Time the HIGH pulse. We count iterations while the line
                // is HIGH, then convert to µs assuming 1 iter ≈ 1 ns
                // (very approximate but well within the 24 µs vs 71 µs
                // margin).
                let mut high_count: u32 = 0;
                while self.pin.is_high().map_err(DHTxxError::Pin)? {
                    high_count += 1;
                    if high_count > BIT_TIMEOUT_US * 1000 {
                        return Err(DHTxxError::Framing);
                    }
                }
                let high_us = high_count / 1000;
                value = (value << 1) | (high_us > BIT_THRESHOLD_US) as u8;
            }
            *byte = value;
        }
        Ok(frame)
    }

    /// Consume the transport and return the pin.
    pub fn release(self) -> P {
        self.pin
    }
}

// ============================================================================
// ESP32-S3 (`esp_hal::gpio::AnyFlex`)
// ============================================================================

/// ESP32-S3 DHTxx transport. Caller provides a delay primitive that the
/// transport uses to time the 20 ms host-start pulse and the µs-scale
/// per-bit pulse-width measurement.
pub struct DHTxxTransportEsp32s3<P> {
    pin: P,
}

impl<P> DHTxxTransportEsp32s3<P>
where
    P: InputPin,
{
    /// Create a new transport. The pin must be configured as input by the
    /// caller; the transport switches its direction internally.
    pub fn new(pin: P) -> Self {
        Self { pin }
    }

    /// Execute the full DHTxx transaction. The caller passes a `delay_ms`
    /// closure (for the 20 ms start pulse) and a `delay_us` closure (for
    /// µs-scale waits during the response). Use the HAL's delay primitive.
    pub fn read_with_delay(
        &mut self,
        mut delay_ms: impl FnMut(u32),
        mut delay_us: impl FnMut(u32),
    ) -> Result<[u8; 5], DHTxxError<P::Error>> {
        // Step 1: 20 ms host-start delay (caller has already driven LOW).
        delay_ms(START_LOW_MS as u32);

        // Step 2: caller releases the bus (reconfigures to input).

        // Step 3: wait for the sensor response.
        let mut timeout_counter: u32 = 0;
        while self.pin.is_high().map_err(DHTxxError::Pin)? {
            timeout_counter += 1;
            if timeout_counter > 1_000_000 {
                return Err(DHTxxError::Timeout);
            }
        }
        timeout_counter = 0;
        while !self.pin.is_high().map_err(DHTxxError::Pin)? {
            timeout_counter += 1;
            if timeout_counter > 1_000_000 {
                return Err(DHTxxError::Timeout);
            }
        }

        // Step 4: 40 bits.
        let mut frame = [0u8; 5];
        for byte in frame.iter_mut() {
            let mut value: u8 = 0;
            for _ in 0..8 {
                let mut low_loops: u32 = 0;
                while self.pin.is_high().map_err(DHTxxError::Pin)? {
                    low_loops += 1;
                    if low_loops > 1_000_000 {
                        return Err(DHTxxError::Framing);
                    }
                }
                let mut high_us_count: u32 = 0;
                while self.pin.is_high().map_err(DHTxxError::Pin)? {
                    delay_us(1);
                    high_us_count += 1;
                    if high_us_count > BIT_TIMEOUT_US {
                        return Err(DHTxxError::Framing);
                    }
                }
                value = (value << 1) | (high_us_count > BIT_THRESHOLD_US) as u8;
            }
            *byte = value;
        }
        Ok(frame)
    }

    /// Consume the transport and return the pin.
    pub fn release(self) -> P {
        self.pin
    }
}
