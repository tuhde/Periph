//! HX711 24-bit ADC (Avia Semiconductor).
//!
//! Communicates via a custom 2-wire GPIO bit-bang protocol (DOUT + PD_SCK).
//! Use [`HX711Transport`] from [`crate::transport::hx711`] to construct the
//! transport, then pass it to [`Hx711Minimal`] or [`Hx711Full`].
//!
//! ## Typical workflow
//!
//! ```rust,ignore
//! use periph::transport::hx711::HX711Transport;
//! use periph::chips::adc_dac::{Hx711Minimal, Hx711Full};
//!
//! let transport = HX711Transport::new(dout_pin, pd_sck_pin);
//! let mut chip = Hx711Full::new(transport)?;
//!
//! chip.tare(10)?;
//! chip.set_scale(420.0);
//! loop {
//!     let weight = chip.read_weight(3)?;
//!     println!("{:.1} g", weight);
//! }
//! ```

use crate::transport::hx711::{HX711Error, HX711Transport};
use embedded_hal::digital::{InputPin, OutputPin};

const GAIN_128: u8 = 25;
const GAIN_32:  u8 = 26;
const GAIN_64:  u8 = 27;

/// HX711 minimal driver — reads signed 24-bit ADC values using Channel A, Gain 128.
///
/// The first post-power-up conversion is discarded during construction.
pub struct Hx711Minimal<DI, CK> {
    transport: HX711Transport<DI, CK>,
}

impl<DI, CK> Hx711Minimal<DI, CK>
where
    DI: InputPin,
    CK: OutputPin,
{
    /// Create a new `Hx711Minimal` and discard the first post-power-up conversion.
    ///
    /// # Arguments
    ///
    /// * `transport` — Configured [`HX711Transport`] with DOUT and PD_SCK pins.
    pub fn new(mut transport: HX711Transport<DI, CK>) -> Result<Self, HX711Error<DI::Error, CK::Error>> {
        transport.read_raw(GAIN_128)?;
        Ok(Self { transport })
    }

    /// Return `true` if a conversion result is available (DOUT is LOW).
    ///
    /// Non-blocking.
    pub fn is_ready(&mut self) -> Result<bool, HX711Error<DI::Error, CK::Error>> {
        self.transport.is_ready()
    }

    /// Block until data is ready and return a signed 24-bit ADC value.
    ///
    /// Reads Channel A at Gain 128.
    pub fn read_raw(&mut self) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
        self.transport.read_raw(GAIN_128)
    }
}

/// HX711 full driver — extends [`Hx711Minimal`] with gain, tare, and calibration.
///
/// Adds gain selection, multi-sample averaging, tare offset capture, scale factor
/// calibration, and power management.
pub struct Hx711Full<DI, CK> {
    inner:  Hx711Minimal<DI, CK>,
    pulses: u8,
    offset: i32,
    scale:  f32,
}

impl<DI, CK> Hx711Full<DI, CK>
where
    DI: InputPin,
    CK: OutputPin,
{
    /// Create a new `Hx711Full` with default gain 128, offset 0, and scale 1.0.
    ///
    /// # Arguments
    ///
    /// * `transport` — Configured [`HX711Transport`] with DOUT and PD_SCK pins.
    pub fn new(transport: HX711Transport<DI, CK>) -> Result<Self, HX711Error<DI::Error, CK::Error>> {
        Ok(Self {
            inner:  Hx711Minimal::new(transport)?,
            pulses: GAIN_128,
            offset: 0,
            scale:  1.0,
        })
    }

    /// Return `true` if a conversion result is available (DOUT is LOW).
    pub fn is_ready(&mut self) -> Result<bool, HX711Error<DI::Error, CK::Error>> {
        self.inner.is_ready()
    }

    /// Block until data is ready and return a signed 24-bit ADC value.
    ///
    /// Uses the currently selected channel and gain.
    pub fn read_raw(&mut self) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
        self.inner.transport.read_raw(self.pulses)
    }

    /// Select the input channel and gain.
    ///
    /// Issues one dummy read to apply the new gain before returning.
    ///
    /// # Arguments
    ///
    /// * `gain` — 128 (Channel A), 64 (Channel A), or 32 (Channel B).
    ///
    /// # Errors
    ///
    /// Returns [`HX711Error::InvalidPulseCount`] if gain is not 128, 64, or 32.
    pub fn set_gain(&mut self, gain: u8) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.pulses = match gain {
            128 => GAIN_128,
            32  => GAIN_32,
            64  => GAIN_64,
            _   => return Err(HX711Error::InvalidPulseCount),
        };
        self.inner.transport.read_raw(self.pulses)?;
        Ok(())
    }

    /// Return the average of multiple raw ADC readings.
    ///
    /// # Arguments
    ///
    /// * `times` — Number of readings to average.
    pub fn read_average(&mut self, times: u8) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
        let mut total: i64 = 0;
        for _ in 0..times {
            total += self.read_raw()? as i64;
        }
        Ok((total / times as i64) as i32)
    }

    /// Capture the current average reading as the zero offset.
    ///
    /// # Arguments
    ///
    /// * `times` — Number of readings to average for the tare.
    pub fn tare(&mut self, times: u8) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.offset = self.read_average(times)?;
        Ok(())
    }

    /// Return the stored tare offset.
    pub fn get_offset(&self) -> i32 { self.offset }

    /// Set the calibration scale factor.
    ///
    /// Calibrate: `factor = (read_average() - offset) / known_weight`.
    pub fn set_scale(&mut self, factor: f32) { self.scale = factor; }

    /// Return the current calibration scale factor.
    pub fn get_scale(&self) -> f32 { self.scale }

    /// Return the calibrated weight in the units defined by the scale factor.
    ///
    /// Computes `(read_average(times) - offset) / scale`.
    pub fn read_weight(&mut self, times: u8) -> Result<f32, HX711Error<DI::Error, CK::Error>> {
        let avg = self.read_average(times)?;
        Ok((avg - self.offset) as f32 / self.scale)
    }

    /// Enter power-down mode (PD_SCK held HIGH for >60 µs).
    ///
    /// The caller is responsible for waiting >60 µs before calling other methods.
    pub fn power_down(&mut self) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.inner.transport.power_down()
    }

    /// Exit power-down, reset chip, and discard the settling conversion.
    ///
    /// Resets to Channel A, Gain 128 and discards the first post-reset conversion.
    pub fn power_up(&mut self) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.inner.transport.power_up()?;
        self.pulses = GAIN_128;
        self.inner.transport.read_raw(GAIN_128)?;
        Ok(())
    }
}
