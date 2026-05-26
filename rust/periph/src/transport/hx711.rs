//! HX711 GPIO bit-bang transport for chip drivers.
//!
//! The HX711 uses a custom 2-wire protocol: DOUT (input from chip) and PD_SCK
//! (clock output to chip). The transport is generic over any
//! [`InputPin`] / [`OutputPin`] pair from `embedded-hal` 1.0, making it
//! compatible with bare-metal targets (`no_std`) and Linux
//! (`linux-embedded-hal`).
//!
//! ## Usage
//!
//! ```rust,ignore
//! use periph::transport::hx711::HX711Transport;
//!
//! let mut transport = HX711Transport::new(dout_pin, pd_sck_pin);
//!
//! // Read Channel A, Gain 128 (next conversion will also use 128)
//! let raw: i32 = transport.read_raw(25)?;
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
//! let mut transport = HX711Transport::new(dout, pd_sck);
//! ```
//!
//! Add to `Cargo.toml`:
//! ```toml
//! linux-embedded-hal = "0.4"
//! embedded-hal = "1"
//! ```

use embedded_hal::digital::{InputPin, OutputPin};

/// Error type for [`HX711Transport`] operations.
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

/// HX711 GPIO bit-bang transport.
///
/// Generic over any `embedded-hal` 1.0 [`InputPin`] (DOUT) and [`OutputPin`]
/// (PD_SCK) pair.
pub struct HX711Transport<DI, CK> {
    dout:   DI,
    pd_sck: CK,
}

impl<DI, CK> HX711Transport<DI, CK>
where
    DI: InputPin,
    CK: OutputPin,
{
    /// Create a new transport and drive PD_SCK LOW.
    ///
    /// # Arguments
    ///
    /// * `dout`   – Pin connected to DOUT (data output from the chip).
    /// * `pd_sck` – Pin connected to PD_SCK (clock / power-down control).
    pub fn new(dout: DI, mut pd_sck: CK) -> Self {
        let _ = pd_sck.set_low();
        Self { dout, pd_sck }
    }

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
    /// # Errors
    ///
    /// Returns [`HX711Error::InvalidPulseCount`] if `num_pulses` is not 25, 26,
    /// or 27. Returns [`HX711Error::Timeout`] (std only) if DOUT stays HIGH for
    /// more than 1 second. Returns [`HX711Error::Dout`] or
    /// [`HX711Error::Clock`] on GPIO failure.
    pub fn read_raw(&mut self, num_pulses: u8) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
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
            #[cfg(feature = "std")]
            std::thread::sleep(std::time::Duration::from_micros(1));
            self.pd_sck.set_low().map_err(HX711Error::Clock)?;
            #[cfg(feature = "std")]
            std::thread::sleep(std::time::Duration::from_micros(1));
            let bit = self.dout.is_high().map_err(HX711Error::Dout)? as u32;
            raw = (raw << 1) | bit;
        }
        for _ in 24..num_pulses {
            self.pd_sck.set_high().map_err(HX711Error::Clock)?;
            #[cfg(feature = "std")]
            std::thread::sleep(std::time::Duration::from_micros(1));
            self.pd_sck.set_low().map_err(HX711Error::Clock)?;
            #[cfg(feature = "std")]
            std::thread::sleep(std::time::Duration::from_micros(1));
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
    /// releasing the transport or doing other work.
    pub fn power_down(&mut self) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.pd_sck.set_high().map_err(HX711Error::Clock)
    }

    /// Exit power-down mode and reset the chip.
    ///
    /// Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The first
    /// conversion after power-up must be discarded.
    pub fn power_up(&mut self) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.pd_sck.set_low().map_err(HX711Error::Clock)
    }

    /// Consume the transport and return the two pins.
    pub fn release(self) -> (DI, CK) {
        (self.dout, self.pd_sck)
    }
}
