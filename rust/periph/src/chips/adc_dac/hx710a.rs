//! HX710A 24-bit ADC (Avia Semiconductor).
//!
//! Communicates via the HX711's custom 2-wire GPIO bit-bang protocol
//! (DOUT + PD_SCK) — the HX710A reuses the same transport. Use
//! [`HX711Connection`] from [`crate::connection::hx711`] to construct the
//! connection, then pass it to [`Hx710aMinimal`] or [`Hx710aFull`].
//!
//! Unlike the HX711, the HX710A has no software-selectable gain and no
//! second input channel — only the differential-input output rate (10 or
//! 40 SPS) is selectable, and pulse count 26 reads the on-chip temperature
//! sensor instead of a second channel.
//!
//! ## Typical workflow
//!
//! ```rust,ignore
//! use periph::connection::hx711::HX711Connection;
//! use periph::chips::adc_dac::{Hx710aMinimal, Hx710aFull};
//!
//! let connection = HX711Connection::new(dout_pin, pd_sck_pin);
//! let mut chip = Hx710aFull::new(connection)?;
//!
//! chip.tare(10)?;
//! chip.set_scale(420.0);
//! loop {
//!     let weight = chip.read_weight(3)?;
//!     println!("{:.1} g", weight);
//! }
//! ```

use crate::connection::hx711::{HX711Error, HX711Connection};
use embedded_hal::digital::{InputPin, OutputPin};

const RATE_10SPS: u8 = 25;
const TEMPERATURE: u8 = 26;
const RATE_40SPS: u8 = 27;

/// HX710A minimal driver — reads signed 24-bit ADC values from the
/// differential input at Gain 128, 10 SPS.
///
/// The first post-power-up conversion is discarded during construction.
pub struct Hx710aMinimal<DI, CK> {
    conn: HX711Connection<DI, CK>,
}

impl<DI, CK> Hx710aMinimal<DI, CK>
where
    DI: InputPin,
    CK: OutputPin,
{
    /// Create a new `Hx710aMinimal` and discard the first post-power-up conversion.
    ///
    /// # Arguments
    ///
    /// * `connection` — Configured [`HX711Connection`] with DOUT and PD_SCK pins.
    pub fn new(mut connection: HX711Connection<DI, CK>) -> Result<Self, HX711Error<DI::Error, CK::Error>> {
        connection.read_raw(RATE_10SPS)?;
        Ok(Self { conn: connection })
    }

    /// Return `true` if a conversion result is available (DOUT is LOW).
    ///
    /// Non-blocking.
    pub fn is_ready(&mut self) -> Result<bool, HX711Error<DI::Error, CK::Error>> {
        self.conn.is_ready()
    }

    /// Block until data is ready and return a signed 24-bit ADC value.
    ///
    /// Reads the differential input at Gain 128, 10 SPS.
    pub fn read_raw(&mut self) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
        self.conn.read_raw(RATE_10SPS)
    }
}

/// HX710A full driver — extends [`Hx710aMinimal`] with rate selection, tare,
/// calibration, temperature, and power management.
pub struct Hx710aFull<DI, CK> {
    inner:  Hx710aMinimal<DI, CK>,
    pulses: u8,
    offset: i32,
    scale:  f32,
}

impl<DI, CK> Hx710aFull<DI, CK>
where
    DI: InputPin,
    CK: OutputPin,
{
    /// Create a new `Hx710aFull` with default 10 SPS rate, offset 0, and scale 1.0.
    ///
    /// # Arguments
    ///
    /// * `connection` — Configured [`HX711Connection`] with DOUT and PD_SCK pins.
    pub fn new(connection: HX711Connection<DI, CK>) -> Result<Self, HX711Error<DI::Error, CK::Error>> {
        Ok(Self {
            inner:  Hx710aMinimal::new(connection)?,
            pulses: RATE_10SPS,
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
    /// Uses the currently selected output rate.
    pub fn read_raw(&mut self) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
        self.inner.conn.read_raw(self.pulses)
    }

    /// Select the differential-input output rate.
    ///
    /// Issues one dummy read to apply the new rate before returning.
    ///
    /// # Arguments
    ///
    /// * `rate` — 10 or 40 (samples per second).
    ///
    /// # Errors
    ///
    /// Returns [`HX711Error::InvalidPulseCount`] if rate is not 10 or 40.
    pub fn set_rate(&mut self, rate: u8) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.pulses = match rate {
            10 => RATE_10SPS,
            40 => RATE_40SPS,
            _  => return Err(HX711Error::InvalidPulseCount),
        };
        self.inner.conn.read_raw(self.pulses)?;
        Ok(())
    }

    /// Return the average of multiple raw differential-input readings.
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

    /// Block until data is ready and return the raw on-chip temperature code.
    ///
    /// Clocks 26 pulses (temperature channel, 40 Hz). This is **not** a
    /// calibrated degrees-Celsius value — the datasheet gives only a typical
    /// resolution of ~20.4 LSB/°C and states offset and gain vary
    /// significantly chip-to-chip. Use for relative drift tracking, or
    /// calibrate against a reference thermometer for absolute readings.
    pub fn read_temperature_raw(&mut self) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
        self.inner.conn.read_raw(TEMPERATURE)
    }

    /// Enter power-down mode (PD_SCK held HIGH for >60 µs).
    ///
    /// The caller is responsible for waiting >60 µs before calling other methods.
    pub fn power_down(&mut self) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.inner.conn.power_down()
    }

    /// Exit power-down, reset chip, and discard the settling conversion.
    ///
    /// Resets to the differential input at Gain 128, 10 SPS and discards
    /// the first post-reset conversion.
    pub fn power_up(&mut self) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.inner.conn.power_up()?;
        self.pulses = RATE_10SPS;
        self.inner.conn.read_raw(RATE_10SPS)?;
        Ok(())
    }
}
