//! HX711 GPIO bit-bang connection for chip drivers.
//!
//! The HX711 uses a custom 2-wire protocol: DOUT (input from chip) and PD_SCK
//! (clock output to chip). The connection is generic over any
//! [`InputPin`] / [`OutputPin`] pair from `embedded-hal` 1.0, making it
//! compatible with bare-metal targets (`no_std`) and Linux
//! (`linux-embedded-hal`).
//!
//! ## Usage
//!
//! ```rust,ignore
//! use periph::connection::hx711::HX711Connection;
//!
//! let mut connection = HX711Connection::new(dout_pin, pd_sck_pin);
//!
//! // Read Channel A, Gain 128 (next conversion will also use 128)
//! let raw: i32 = connection.read_raw(25)?;
//! ```
//!
//! ## Pulse count → channel / gain
//!
//! | `num_pulses` | Channel | Gain |
//! |---|---|---|
//! | 25 | A | 128 |
//! | 26 | B | 32  |
//! | 27 | A | 64  |
//!
//! ## Linux host (`linux-embedded-hal` crate)
//!
//! ```rust,ignore
//! use linux_embedded_hal::CdevPin;
//!
//! // Request DOUT as input and PD_SCK as output via gpiocdev, then:
//! let mut connection = HX711Connection::new(dout, pd_sck);
//! ```
//!
//! Add to `Cargo.toml`:
//! ```toml
//! linux-embedded-hal = "0.4"
//! embedded-hal = "1"
//! ```

use embedded_hal::digital::{InputPin, OutputPin};

/// Error type for [`HX711Connection`] operations.
#[derive(Debug)]
pub enum HX711Error<DE, CE> {
    /// Error reading the DOUT pin.
    Dout(DE),
    /// Error driving the PD_SCK pin.
    Clock(CE),
    /// `num_pulses` was not 25, 26, or 27.
    InvalidPulseCount,
    /// DOUT did not go LOW within 1 second (conversion not ready).
    Timeout,
}

/// HX711 GPIO bit-bang connection.
///
/// Generic over any `embedded-hal` 1.0 [`InputPin`] (DOUT) and [`OutputPin`]
/// (PD_SCK) pair.
pub struct HX711Connection<DI, CK> {
    dout:   DI,
    pd_sck: CK,
    enabled: bool,
}

impl<DI, CK> HX711Connection<DI, CK>
where
    DI: InputPin,
    CK: OutputPin,
{
    /// Create a new connection and drive PD_SCK LOW.
    ///
    /// # Arguments
    ///
    /// * `dout`   – Pin connected to DOUT (data output from the chip).
    /// * `pd_sck` – Pin connected to PD_SCK (clock / power-down control).
    pub fn new(dout: DI, mut pd_sck: CK) -> Self {
        let _ = pd_sck.set_low();
        Self { dout, pd_sck, enabled: true }
    }

    /// Resume conversions.
    pub fn enable(&mut self) { self.enabled = true; }

    /// Gate [`HX711Connection::read_raw`] — it returns `Ok(0)` without
    /// touching the bus while disabled.
    pub fn disable(&mut self) { self.enabled = false; }

    /// Return the current software-gate state.
    pub fn is_enabled(&self) -> bool { self.enabled }

    /// Return `true` if a conversion result is available (DOUT is LOW).
    ///
    /// Non-blocking.
    pub fn is_ready(&mut self) -> Result<bool, HX711Error<DI::Error, CK::Error>> {
        self.dout.is_low().map_err(HX711Error::Dout)
    }

    /// Wait up to 1 s for data ready, then clock out a conversion.
    ///
    /// Polls DOUT until LOW (conversion ready), then sends `num_pulses` PD_SCK
    /// pulses, sampling DOUT at each falling edge (HIGH→LOW transition). Leaves
    /// PD_SCK LOW after the last pulse. Sends `num_pulses - 24` extra pulses
    /// after the 24 data bits to program the channel and gain for the **next**
    /// conversion.
    ///
    /// On `std` targets, returns [`HX711Error::Timeout`] if DOUT does not go
    /// LOW within 1 second. On `no_std` bare-metal, spins indefinitely.
    ///
    /// Returns `Ok(0)` without touching the bus if this connection is disabled.
    ///
    /// # Errors
    ///
    /// Returns [`HX711Error::InvalidPulseCount`] if `num_pulses` is not 25, 26,
    /// or 27. Returns [`HX711Error::Timeout`] (std only) if DOUT stays HIGH for
    /// more than 1 second. Returns [`HX711Error::Dout`] or
    /// [`HX711Error::Clock`] on GPIO failure.
    pub fn read_raw(&mut self, num_pulses: u8) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
        if !self.enabled {
            return Ok(0);
        }
        if !matches!(num_pulses, 25 | 26 | 27) {
            return Err(HX711Error::InvalidPulseCount);
        }
        #[cfg(feature = "std")]
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
        while self.dout.is_high().map_err(HX711Error::Dout)? {
            #[cfg(feature = "std")]
            if std::time::Instant::now() >= deadline {
                return Err(HX711Error::Timeout);
            }
        }

        let mut raw: u32 = 0;
        for _ in 0..24 {
            self.pd_sck.set_high().map_err(HX711Error::Clock)?;
            self.pd_sck.set_low().map_err(HX711Error::Clock)?;
            let bit = self.dout.is_high().map_err(HX711Error::Dout)? as u32;
            raw = (raw << 1) | bit;
        }
        for _ in 24..num_pulses {
            self.pd_sck.set_high().map_err(HX711Error::Clock)?;
            self.pd_sck.set_low().map_err(HX711Error::Clock)?;
        }

        if raw >= 0x800000 {
            Ok(raw as i32 - 0x1000000)
        } else {
            Ok(raw as i32)
        }
    }

    /// Enter power-down mode by holding PD_SCK HIGH for >60 µs.
    ///
    /// On bare-metal, busy-wait 65 µs using your HAL's delay primitive before
    /// releasing the connection or doing other work.
    ///
    /// No-op if this connection is disabled.
    pub fn power_down(&mut self) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        if !self.enabled {
            return Ok(());
        }
        self.pd_sck.set_high().map_err(HX711Error::Clock)
    }

    /// Exit power-down mode and reset the chip.
    ///
    /// Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The first
    /// conversion after power-up must be discarded.
    ///
    /// No-op if this connection is disabled.
    pub fn power_up(&mut self) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        if !self.enabled {
            return Ok(());
        }
        self.pd_sck.set_low().map_err(HX711Error::Clock)
    }

    /// Consume the connection and return the two pins.
    pub fn release(self) -> (DI, CK) {
        (self.dout, self.pd_sck)
    }
}
