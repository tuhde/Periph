//! HX711 24-bit ADC (Avia Semiconductor).
//!
//! Communicates via a custom 2-wire GPIO bit-bang protocol (DOUT + PD_SCK).
//! Use [`HX711Connection`] from [`crate::connection::hx711`] to construct the
//! connection, then pass it to [`Hx711Minimal`] or [`Hx711Full`].
//!
//! ## Typical workflow
//!
//! ```rust,ignore
//! use periph::connection::hx711::HX711Connection;
//! use periph::chips::adc_dac::{Hx711Minimal, Hx711Full};
//!
//! let connection = HX711Connection::new(dout_pin, pd_sck_pin);
//! let mut chip = Hx711Full::new(connection)?;
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

const GAIN_128: u8 = 25;
const GAIN_32:  u8 = 26;
const GAIN_64:  u8 = 27;

/// HX711 minimal driver — reads signed 24-bit ADC values using Channel A, Gain 128.
///
/// The first post-power-up conversion is discarded during construction.
pub struct Hx711Minimal<DI, CK> {
    conn: HX711Connection<DI, CK>,
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
    /// * `connection` — Configured [`HX711Connection`] with DOUT and PD_SCK pins.
    pub fn new(mut connection: HX711Connection<DI, CK>) -> Result<Self, HX711Error<DI::Error, CK::Error>> {
        connection.read_raw(GAIN_128)?;
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
    /// Reads Channel A at Gain 128.
    pub fn read_raw(&mut self) -> Result<i32, HX711Error<DI::Error, CK::Error>> {
        self.conn.read_raw(GAIN_128)
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
    /// * `connection` — Configured [`HX711Connection`] with DOUT and PD_SCK pins.
    pub fn new(connection: HX711Connection<DI, CK>) -> Result<Self, HX711Error<DI::Error, CK::Error>> {
        Ok(Self {
            inner:  Hx711Minimal::new(connection)?,
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
        self.inner.conn.read_raw(self.pulses)
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
        self.inner.conn.read_raw(self.pulses)?;
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
        self.inner.conn.power_down()
    }

    /// Exit power-down, reset chip, and discard the settling conversion.
    ///
    /// Resets to Channel A, Gain 128 and discards the first post-reset conversion.
    pub fn power_up(&mut self) -> Result<(), HX711Error<DI::Error, CK::Error>> {
        self.inner.conn.power_up()?;
        self.pulses = GAIN_128;
        self.inner.conn.read_raw(GAIN_128)?;
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

    // Hx711Minimal/Hx711Full are generic directly over embedded-hal's
    // InputPin/OutputPin (not the shared Connection<BUS> wrapper other Rust
    // chips use), and HX711Connection::read_raw's DOUT wait-loop polls
    // is_high() in a spin loop with no fixed transaction count. That makes
    // embedded_hal_mock::eh1::digital's strict sequential Transaction model
    // awkward here, so these two tiny hand-rolled fake pins model exactly
    // what the protocol needs instead:
    //
    // - FakeDout: is_low() returns a settable `ready` flag (drives
    //   is_ready()). is_high() pops from a shared queue of bools the test
    //   preloads once per read_raw() call via push_read(): one `false` for
    //   the ready-poll (so the wait loop exits after exactly one check),
    //   then 24 bits MSB-first encoding the desired signed 24-bit result —
    //   this is deterministic because the poll always sees "ready" here.
    // - FakeSck: counts set_high()/set_low() calls; a read_raw(num_pulses)
    //   cycle contributes exactly num_pulses to each counter, so snapshotting
    //   the count before/after a driver call gives the pulse count that call
    //   actually used — the same thing Python's mock exposes directly via
    //   `connection.reads`.

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
        push_read(&dout.queue, 0); // discarded by construction
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let before = *sck.highs.borrow();
        let _sensor = Hx711Minimal::new(conn).unwrap();
        assert_eq!(*sck.highs.borrow() - before, 25, "init should discard one 25-pulse reading");
    }

    #[test]
    fn minimal_is_ready_proxies_connection() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Minimal::new(conn).unwrap();

        *dout.ready.borrow_mut() = false;
        assert_eq!(sensor.is_ready().unwrap(), false);
        *dout.ready.borrow_mut() = true;
        assert_eq!(sensor.is_ready().unwrap(), true);
    }

    #[test]
    fn minimal_read_raw_uses_gain_128() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Minimal::new(conn).unwrap();

        push_read(&dout.queue, 12345);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 12345);
        assert_eq!(*sck.highs.borrow() - before, 25);
    }

    #[test]
    fn full_init_discards_first_reading() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let before = *sck.highs.borrow();
        let _sensor = Hx711Full::new(conn).unwrap();
        assert_eq!(*sck.highs.borrow() - before, 25);
    }

    #[test]
    fn full_read_raw_default_gain_128() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Full::new(conn).unwrap();

        push_read(&dout.queue, 1000);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 1000);
        assert_eq!(*sck.highs.borrow() - before, 25);
    }

    #[test]
    fn set_gain_switches_pulse_count() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Full::new(conn).unwrap();

        // set_gain(64): Channel A, 27 pulses. Issues one dummy read to apply it.
        push_read(&dout.queue, 0);
        let before = *sck.highs.borrow();
        sensor.set_gain(64).unwrap();
        assert_eq!(*sck.highs.borrow() - before, 27, "set_gain(64) dummy read should use 27 pulses");
        push_read(&dout.queue, 2000);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 2000);
        assert_eq!(*sck.highs.borrow() - before, 27);

        // set_gain(32): Channel B, 26 pulses.
        push_read(&dout.queue, 0);
        let before = *sck.highs.borrow();
        sensor.set_gain(32).unwrap();
        assert_eq!(*sck.highs.borrow() - before, 26, "set_gain(32) dummy read should use 26 pulses");
        push_read(&dout.queue, 3000);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 3000);
        assert_eq!(*sck.highs.borrow() - before, 26);

        // set_gain(128): Channel A, 25 pulses.
        push_read(&dout.queue, 0);
        let before = *sck.highs.borrow();
        sensor.set_gain(128).unwrap();
        assert_eq!(*sck.highs.borrow() - before, 25, "set_gain(128) dummy read should use 25 pulses");
    }

    #[test]
    fn set_gain_invalid_is_rejected() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Full::new(conn).unwrap();

        let before = *sck.highs.borrow();
        let result = sensor.set_gain(99);
        assert!(matches!(result, Err(HX711Error::InvalidPulseCount)));
        assert_eq!(*sck.highs.borrow() - before, 0, "an invalid gain must not issue a dummy read");

        // Gain stays at its last valid setting (128 / 25 pulses by default).
        push_read(&dout.queue, 4242);
        let before = *sck.highs.borrow();
        assert_eq!(sensor.read_raw().unwrap(), 4242);
        assert_eq!(*sck.highs.borrow() - before, 25);
    }

    #[test]
    fn read_average_is_integer_division_mean() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Full::new(conn).unwrap();

        push_read(&dout.queue, 10);
        push_read(&dout.queue, 20);
        push_read(&dout.queue, 33);
        assert_eq!(sensor.read_average(3).unwrap(), (10 + 20 + 33) / 3);
    }

    #[test]
    fn tare_sets_offset_from_read_average() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Full::new(conn).unwrap();

        push_read(&dout.queue, 100);
        push_read(&dout.queue, 100);
        sensor.tare(2).unwrap();
        assert_eq!(sensor.get_offset(), 100);
    }

    #[test]
    fn set_scale_get_scale_round_trip() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Full::new(conn).unwrap();

        sensor.set_scale(2.5);
        assert_eq!(sensor.get_scale(), 2.5);
    }

    #[test]
    fn read_weight_computes_scaled_offset_average() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Full::new(conn).unwrap();

        push_read(&dout.queue, 100);
        push_read(&dout.queue, 100);
        sensor.tare(2).unwrap();
        sensor.set_scale(2.5);

        push_read(&dout.queue, 350);
        assert_eq!(sensor.read_weight(1).unwrap(), (350.0 - 100.0) / 2.5);
    }

    #[test]
    fn power_down_drives_sck_high() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Full::new(conn).unwrap();

        let before_high = *sck.highs.borrow();
        let before_low = *sck.lows.borrow();
        sensor.power_down().unwrap();
        assert_eq!(*sck.highs.borrow() - before_high, 1, "power_down should drive PD_SCK high once");
        assert_eq!(*sck.lows.borrow() - before_low, 0);
    }

    #[test]
    fn power_up_resets_gain_and_discards_reading() {
        let (dout, sck) = new_pins();
        push_read(&dout.queue, 0);
        let conn = HX711Connection::new(dout.clone(), sck.clone());
        let mut sensor = Hx711Full::new(conn).unwrap();

        // Select a non-default gain, then power down, then power up — even
        // with gain 64 (27 pulses) active beforehand, power_up() must reset
        // to gain 128 (25 pulses) and discard exactly one reading.
        push_read(&dout.queue, 0);
        sensor.set_gain(64).unwrap();
        sensor.power_down().unwrap();

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
