//! HX710B 24-bit ADC (Avia Semiconductor).
//!
//! Communicates via the HX711's custom 2-wire GPIO bit-bang protocol
//! (DOUT + PD_SCK) — the HX710B reuses the same transport. Use
//! [`HX711Connection`] from [`crate::connection::hx711`] to construct the
//! connection, then pass it to [`Hx710bMinimal`] or [`Hx710bFull`].
//!
//! Unlike the HX711, the HX710B has no software-selectable gain and no
//! second input channel — only the differential-input output rate (10 or
//! 40 SPS) is selectable, and pulse count 26 reads the DVDD−AVDD
//! supply-voltage-difference channel instead of a second analog input.
//!
//! ## Typical workflow
//!
//! ```rust,ignore
//! use periph::connection::hx711::HX711Connection;
//! use periph::chips::adc_dac::{Hx710bMinimal, Hx710bFull};
//!
//! let connection = HX711Connection::new(dout_pin, pd_sck_pin);
//! let mut chip = Hx710bFull::new(connection)?;
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
const SUPPLY_DIFF: u8 = 26;
const RATE_40SPS: u8 = 27;

/// HX710B minimal driver — reads signed 24-bit ADC values from the
/// differential input at Gain 128, 10 SPS.
///
/// The first post-power-up conversion is discarded during construction.
pub struct Hx710bMinimal<DI, CK> {
    conn: HX711Connection<DI, CK>,
}

impl<DI, CK> Hx710bMinimal<DI, CK>
where
    DI: InputPin,
    CK: OutputPin,
{
    /// Create a new `Hx710bMinimal` and discard the first post-power-up conversion.
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

/// HX710B full driver — extends [`Hx710bMinimal`] with rate selection, tare,
/// calibration, supply-difference monitoring, and power management.
pub struct Hx710bFull<DI, CK> {
    inner:  Hx710bMinimal<DI, CK>,
    pulses: u8,
    offset: i32,
    scale:  f32,
}

impl<DI, CK> Hx710bFull<DI, CK>
where
    DI: InputPin,
    CK: OutputPin,
{
    /// Create a new `Hx710bFull` with default 10 SPS rate, offset 0, and scale 1.0.
    ///
    /// # Arguments
    ///
    /// * `connection` — Configured [`HX711Connection`] with DOUT and PD_SCK pins.
    pub fn new(connection: HX711Connection<DI, CK>) -> Result<Self, HX711Error<DI::Error, CK::Error>> {
        Ok(Self {
            inner:  Hx710bMinimal::new(connection)?,
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

    /// Block until data is ready and return the raw DVDD−AVDD code.
    ///
    /// Clocks 26 pulses (DVDD−AVDD channel, 40 Hz). This is **not** a
    /// calibrated volts value — the datasheet gives no LSB-to-volts scale
    /// factor for this reading; it is described only qualitatively as
    /// usable for battery-voltage detection. Use for relative drift
    /// tracking against a known-good baseline, or calibrate against a
    /// reference voltmeter for absolute readings.
    pub fn read_supply_diff_raw(&mut self) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
        self.inner.conn.read_raw(SUPPLY_DIFF)
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

#[cfg(test)]
mod tests {
    use super::*;
    use core::convert::Infallible;
    use embedded_hal::digital::ErrorType;
    use std::cell::RefCell;
    use std::collections::VecDeque;
    use std::rc::Rc;

    // See hx711.rs's test module for the full rationale behind these two
    // hand-rolled fake pins (embedded_hal_mock's strict Transaction model
    // doesn't fit HX711Connection::read_raw's is_high() spin-poll loop).

    #[derive(Clone)]
    struct FakeDout {
        queue: Rc<RefCell<VecDeque<bool>>>,
        ready: Rc<RefCell<bool>>,
    }

    impl ErrorType for FakeDout {
        type Error = Infallible;
    }

    impl InputPin for FakeDout {
        fn is_high(&mut self) -> Result<bool, Infallible> {
            Ok(self.queue.borrow_mut().pop_front().unwrap_or(false))
        }
        fn is_low(&mut self) -> Result<bool, Infallible> {
            Ok(*self.ready.borrow())
        }
    }

    #[derive(Clone, Default)]
    struct FakeSck {
        highs: Rc<RefCell<u32>>,
        lows: Rc<RefCell<u32>>,
    }

    impl ErrorType for FakeSck {
        type Error = Infallible;
    }

    impl OutputPin for FakeSck {
        fn set_low(&mut self) -> Result<(), Infallible> {
            *self.lows.borrow_mut() += 1;
            Ok(())
        }
        fn set_high(&mut self) -> Result<(), Infallible> {
            *self.highs.borrow_mut() += 1;
            Ok(())
        }
    }

    /// Preload one read_raw() cycle's DOUT bit sequence on `queue`: a
    /// `false` for the ready-poll, then 24 bits (MSB-first) encoding
    /// `value` as a signed 24-bit two's-complement result.
    fn push_read(queue: &Rc<RefCell<VecDeque<bool>>>, value: i32) {
        let mut q = queue.borrow_mut();
        q.push_back(false);
        let raw = (value as u32) & 0x00FF_FFFF;
        for i in (0..24).rev() {
            q.push_back((raw >> i) & 1 == 1);
        }
    }

    fn new_pins() -> (FakeDout, FakeSck) {
        (
            FakeDout { queue: Rc::new(RefCell::new(VecDeque::new())), ready: Rc::new(RefCell::new(true)) },
            FakeSck::default(),
        )
    }

    #[test]
    fn minimal_init_discards_first_reading() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let before = *sck.highs.borrow();
        let _sensor = Hx710bMinimal::new(conn).unwrap();
        assert_eq!(*sck.highs.borrow() - before, 25, "init should discard one 25-pulse reading");
    }

    #[test]
    fn minimal_read_raw_uses_10sps() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx710bMinimal::new(conn).unwrap();

        push_read(&dout.queue, 12345);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 12345);
        assert_eq!(*sck.highs.borrow() - before, 25);
    }

    #[test]
    fn full_read_raw_default_10sps() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx710bFull::new(conn).unwrap();

        push_read(&dout.queue, 1000);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 1000);
        assert_eq!(*sck.highs.borrow() - before, 25);
    }

    #[test]
    fn set_rate_switches_pulse_count() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx710bFull::new(conn).unwrap();

        // set_rate(40): 27 pulses. Issues one dummy read to apply it.
        push_read(&dout.queue, 0);
        let before = *sck.highs.borrow();
        sensor.set_rate(40).unwrap();
        assert_eq!(*sck.highs.borrow() - before, 27, "set_rate(40) dummy read should use 27 pulses");
        push_read(&dout.queue, 2000);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 2000);
        assert_eq!(*sck.highs.borrow() - before, 27);

        // set_rate(10): back to 25 pulses.
        push_read(&dout.queue, 0);
        let before = *sck.highs.borrow();
        sensor.set_rate(10).unwrap();
        assert_eq!(*sck.highs.borrow() - before, 25, "set_rate(10) dummy read should use 25 pulses");
    }

    #[test]
    fn set_rate_invalid_is_rejected() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx710bFull::new(conn).unwrap();

        let before = *sck.highs.borrow();
        let result = sensor.set_rate(99);
        assert!(matches!(result, Err(HX711Error::InvalidPulseCount)));
        assert_eq!(*sck.highs.borrow() - before, 0, "an invalid rate must not issue a dummy read");

        push_read(&dout.queue, 4242);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 4242);
        assert_eq!(*sck.highs.borrow() - before, 25, "rate stays at its last valid setting (10 SPS / 25 pulses)");
    }

    #[test]
    fn read_average_is_integer_division_mean() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx710bFull::new(conn).unwrap();

        push_read(&dout.queue, 10);
        push_read(&dout.queue, 20);
        push_read(&dout.queue, 33);
        assert_eq!(sensor.read_average(3).unwrap(), (10 + 20 + 33) / 3);
    }

    #[test]
    fn tare_and_read_weight() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx710bFull::new(conn).unwrap();

        push_read(&dout.queue, 100);
        push_read(&dout.queue, 100);
        sensor.tare(2).unwrap();
        assert_eq!(sensor.get_offset(), 100);

        sensor.set_scale(2.5);
        assert_eq!(sensor.get_scale(), 2.5);

        push_read(&dout.queue, 350);
        assert_eq!(sensor.read_weight(1).unwrap(), (350.0 - 100.0) / 2.5);
    }

    // Regression: read_supply_diff_raw() must clock exactly 26 pulses (the
    // DVDD-AVDD channel per the HX710B pulse-count table), not 25 or 27
    // (which would silently read the differential input instead).
    #[test]
    fn read_supply_diff_raw_uses_26_pulses() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx710bFull::new(conn).unwrap();

        push_read(&dout.queue, 777);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_supply_diff_raw().unwrap(), 777);
        assert_eq!(*sck.highs.borrow() - before, 26, "supply-diff reading should use exactly 26 pulses");
    }

    #[test]
    fn power_down_and_power_up() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx710bFull::new(conn).unwrap();

        let before_high = *sck.highs.borrow();
        sensor.power_down().unwrap();
        assert_eq!(*sck.highs.borrow() - before_high, 1, "power_down should drive PD_SCK high once");

        push_read(&dout.queue, 0); // discarded by power_up()
        let before_high = *sck.highs.borrow();
        sensor.power_up().unwrap();
        assert_eq!(*sck.highs.borrow() - before_high, 25, "power_up's discard read should use 25 pulses");

        push_read(&dout.queue, 4242);
        let before_high = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 4242);
        assert_eq!(*sck.highs.borrow() - before_high, 25, "next read_raw() after power_up() should still use 25 pulses");
    }
}
