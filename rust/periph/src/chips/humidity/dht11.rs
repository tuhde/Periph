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

#[cfg(test)]
mod tests {
    use super::*;
    use core::convert::Infallible;
    use embedded_hal::digital::ErrorType;
    use std::collections::VecDeque;

    // Dht11Minimal/Dht11Full (Linux) wrap the *concrete* DHTxxConnectionLinux<P>
    // rather than a connection trait, and that connection's read() drives a
    // single bidirectional pin through nested while-loops that measure pulse
    // widths as *poll counts* (not wall-clock time — see
    // BIT_THRESHOLD_US/RESPONSE_TIMEOUT_US in connection/dhtxx.rs), so a fake
    // pin can reproduce the exact bit sequence deterministically without any
    // real timing. FakePin below is a single struct implementing both
    // InputPin and OutputPin (one physical pin switches direction, unlike
    // HX711's separate DOUT/SCK pins). is_high() pops from a queue of
    // (level, run-length) pairs; queue_frame()/queue_timeout() build that
    // queue to match read()'s actual call pattern:
    //   - a "while is_high() {}" loop is satisfied by N `true` pops followed
    //     by one `false` pop (which ends the loop);
    //   - a "while !is_high() {}" loop is satisfied by N `false` pops
    //     followed by one `true` pop;
    //   - each bit's "count while high" loop just needs enough `true` pops
    //     to push high_us (= count/1000) past BIT_THRESHOLD_US (40) for a
    //     '1' bit, or zero for a '0' bit, followed by one `false` pop.
    // set_low()/set_high() (direction-switch/start-pulse calls) are recorded
    // but otherwise no-ops.
    struct FakePin {
        runs: VecDeque<(bool, u32)>,
    }

    impl FakePin {
        fn new() -> Self {
            Self { runs: VecDeque::new() }
        }

        fn push_high(&mut self, count: u32) {
            if count > 0 {
                self.runs.push_back((true, count));
            }
        }
        fn push_low(&mut self, count: u32) {
            if count > 0 {
                self.runs.push_back((false, count));
            }
        }

        /// Run consumed by a `while is_high() {}` loop.
        fn wait_while_high(&mut self, extra: u32) {
            self.push_high(extra);
            self.push_low(1);
        }
        /// Run consumed by a `while !is_high() {}` loop.
        fn wait_while_low(&mut self, extra: u32) {
            self.push_low(extra);
            self.push_high(1);
        }
        /// Run consumed by a bit's "count while high" loop. `count >=
        /// 41_000` decodes as bit '1' (high_us = count/1000 > 40); `count ==
        /// 0` decodes as bit '0'.
        fn count_high(&mut self, count: u32) {
            self.push_high(count);
            self.push_low(1);
        }

        /// Queue one full successful 40-bit transmission of `frame`,
        /// matching read()'s response-wait + per-bit call pattern exactly.
        fn queue_frame(&mut self, frame: [u8; 5]) {
            self.wait_while_high(2); // response: wait for sensor to pull low
            self.wait_while_low(2); // response: wait for sensor to release high
            for byte in frame {
                for bit_idx in (0..8).rev() {
                    self.wait_while_high(2); // wait for start-of-bit low pulse
                    let bit = (byte >> bit_idx) & 1;
                    self.count_high(if bit == 1 { 41_000 } else { 0 });
                }
            }
        }

        /// Queue a response that never goes low, so read()'s first wait loop
        /// exhausts its 1_000_000-iteration guard and returns
        /// DHTxxError::Timeout. Sized to exactly the number of is_high()
        /// calls that loop consumes before erroring, so nothing is left over
        /// in the queue for a subsequent read() call.
        fn queue_timeout(&mut self) {
            self.push_high(1_000_001);
        }
    }

    impl ErrorType for FakePin {
        type Error = Infallible;
    }

    impl embedded_hal::digital::InputPin for FakePin {
        fn is_high(&mut self) -> Result<bool, Infallible> {
            loop {
                match self.runs.front_mut() {
                    Some((level, count)) => {
                        if *count == 0 {
                            self.runs.pop_front();
                            continue;
                        }
                        *count -= 1;
                        return Ok(*level);
                    }
                    None => return Ok(false),
                }
            }
        }
        fn is_low(&mut self) -> Result<bool, Infallible> {
            Ok(!self.is_high()?)
        }
    }

    impl embedded_hal::digital::OutputPin for FakePin {
        fn set_low(&mut self) -> Result<(), Infallible> {
            Ok(())
        }
        fn set_high(&mut self) -> Result<(), Infallible> {
            Ok(())
        }
    }

    const GOOD_FRAME: [u8; 5] = [0x35, 0x00, 0x18, 0x04, 0x51];
    const NEG_TEMP_FRAME: [u8; 5] = [0x20, 0x00, 0x0A, 0x81, 0xAB];
    const BAD_CHECKSUM_FRAME: [u8; 5] = [0x35, 0x00, 0x18, 0x04, 0x00];

    fn close_enough(a: f32, b: f32) -> bool {
        (a - b).abs() < 0.001
    }

    // --- decode_frame(): pure checksum/conversion logic, no I/O. Covers the
    // datasheet decode examples and checksum validation directly, without
    // needing to fake any GPIO timing at all. ---

    #[test]
    fn decode_datasheet_example() {
        let (t, h) = decode_frame(&GOOD_FRAME).unwrap();
        assert!(close_enough(t, 24.4) && close_enough(h, 53.0));
    }

    #[test]
    fn decode_negative_temperature() {
        let (t, h) = decode_frame(&NEG_TEMP_FRAME).unwrap();
        assert!(close_enough(t, -10.1) && close_enough(h, 32.0));
    }

    #[test]
    fn decode_checksum_error() {
        let err = decode_frame(&BAD_CHECKSUM_FRAME).unwrap_err();
        assert!(matches!(err, Dht11Error::Checksum { .. }));
    }

    // Wrong-length frame: skipped intentionally — decode_frame takes a fixed
    // &[u8; 5], and DHTxxConnectionLinux::read() always returns a fixed
    // [u8; 5] too, so Rust's array type can't represent a short frame at
    // this level. The connection's own Framing/Timeout error is the
    // equivalent failure mode and is exercised by
    // read_retry_recovers_from_transport_error below.

    // --- Dht11Minimal/Dht11Full driven end-to-end through the real
    // DHTxxConnectionLinux::read() bit-bang loop via FakePin. ---

    #[test]
    fn minimal_read_decodes_datasheet_example() {
        let mut pin = FakePin::new();
        pin.queue_frame(GOOD_FRAME);
        let mut sensor = Dht11Minimal::new(DHTxxConnectionLinux::new(pin));
        let (t, h) = sensor.read().unwrap();
        assert!(close_enough(t, 24.4) && close_enough(h, 53.0));
    }

    #[test]
    fn minimal_read_checksum_error() {
        let mut pin = FakePin::new();
        pin.queue_frame(BAD_CHECKSUM_FRAME);
        let mut sensor = Dht11Minimal::new(DHTxxConnectionLinux::new(pin));
        let err = sensor.read().unwrap_err();
        assert!(matches!(err, Dht11Error::Checksum { .. }));
    }

    #[test]
    fn full_read_temperature_and_humidity() {
        let mut pin = FakePin::new();
        pin.queue_frame(GOOD_FRAME);
        pin.queue_frame(GOOD_FRAME);
        let mut sensor = Dht11Full::new(DHTxxConnectionLinux::new(pin), 3);
        assert!(close_enough(sensor.read_temperature().unwrap(), 24.4));
        assert!(close_enough(sensor.read_humidity().unwrap(), 53.0));
    }

    #[test]
    fn full_read_raw_returns_frame_and_rejects_bad_checksum() {
        let mut pin = FakePin::new();
        pin.queue_frame(GOOD_FRAME);
        pin.queue_frame(BAD_CHECKSUM_FRAME);
        let mut sensor = Dht11Full::new(DHTxxConnectionLinux::new(pin), 3);
        assert_eq!(sensor.read_raw().unwrap(), GOOD_FRAME);
        assert!(matches!(sensor.read_raw().unwrap_err(), Dht11Error::Checksum { .. }));
    }

    #[test]
    fn full_read_retry_succeeds_after_one_bad_attempt() {
        let mut pin = FakePin::new();
        pin.queue_frame(BAD_CHECKSUM_FRAME); // attempt 1: bad checksum
        pin.queue_frame(GOOD_FRAME); // attempt 2: good
        let mut sensor = Dht11Full::new(DHTxxConnectionLinux::new(pin), 3);
        let (t, h) = sensor.read_retry(3).unwrap();
        assert!(close_enough(t, 24.4) && close_enough(h, 53.0));
    }

    #[test]
    fn full_read_retry_exhausted_on_persistent_checksum_error() {
        let mut pin = FakePin::new();
        pin.queue_frame(BAD_CHECKSUM_FRAME);
        pin.queue_frame(BAD_CHECKSUM_FRAME);
        let mut sensor = Dht11Full::new(DHTxxConnectionLinux::new(pin), 2);
        assert!(sensor.read_retry(2).is_err());
    }

    // Unlike Python's read_retry (which only catches its checksum-specific
    // DHT11Error and lets a transport-level error propagate immediately),
    // Rust's read_retry loop retries on ANY Err returned by self.read() —
    // see dht11.rs's read_retry: `match self.read() { Ok(v) => return
    // Ok(v), Err(e) => last_err = Some(e) }` has no branch that
    // distinguishes Dht11Error::Connection from Dht11Error::Checksum. A
    // transient connection-level error (timeout/framing) should therefore
    // still be recovered by a later successful attempt within max_retries.
    #[test]
    fn full_read_retry_recovers_from_transport_error() {
        let mut pin = FakePin::new();
        pin.queue_timeout(); // attempt 1: connection-level timeout
        pin.queue_frame(GOOD_FRAME); // attempt 2: good
        let mut sensor = Dht11Full::new(DHTxxConnectionLinux::new(pin), 3);
        let (t, h) = sensor.read_retry(3).unwrap();
        assert!(close_enough(t, 24.4) && close_enough(h, 53.0));
    }

    #[test]
    fn full_read_retry_zero_uses_constructor_default() {
        let mut pin = FakePin::new();
        pin.queue_frame(BAD_CHECKSUM_FRAME);
        pin.queue_frame(BAD_CHECKSUM_FRAME);
        pin.queue_frame(GOOD_FRAME); // 3rd attempt succeeds
        let mut sensor = Dht11Full::new(DHTxxConnectionLinux::new(pin), 3);
        let (t, _h) = sensor.read_retry(0).unwrap(); // 0 -> constructor's default (3)
        assert!(close_enough(t, 24.4));
    }
}
