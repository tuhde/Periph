//! RRH46410 — digital gas sensor module for indoor air quality (Renesas).
//!
//! The module combines a ZMOD4410 metal-oxide sensing element with an ASIC that runs the Renesas
//! algorithms on-chip. The host sends a request `[command, params…, checksum]` in one I²C write
//! and reads the response `[status, params…, checksum]` in a separate read, where
//! `checksum = !(sum of the other bytes)`. The module stretches the I²C clock (fixed address
//! [`ADDRESS`] `0x38`).
//!
//! Construction does no bus access. `init()` retries `Get Product ID` for up to 1 s, verifies
//! [`PRODUCT_ID`] `0x2310` and selects the IAQ 2nd Gen mode (one sample every 3 s; the first
//! 100 samples, about 5 minutes, are the warm-up during which the values stay fixed). The
//! module's default humidity (50 %RH) is kept and the GPIOs are unused.
//!
//! The Rust driver exposes [`Rrh46410Full::poll_interrupt`] (no callback subscription): the
//! caller wires the active-high `INT` pin into an ISR or polling loop, then reads the result
//! with [`Rrh46410Full::read_results`].

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// Fixed 7-bit I²C address.
pub const ADDRESS: u8 = 0x38;
/// Expected `Get Product ID` value.
pub const PRODUCT_ID: u16 = 0x2310;

/// No measurement (default after power-up and reset).
pub const MODE_SUSPEND: u8 = 0x00;
/// IAQ 2nd Gen: one sample every 3 s, 100 warm-up samples.
pub const MODE_IAQ_2ND_GEN: u8 = 0x01;
/// Ultra-low power: one sample every 90 s, 10 warm-up samples.
pub const MODE_ULP: u8 = 0x02;
/// Public-building air quality: TVOC and ethanol every 5 s, 60 warm-up samples.
pub const MODE_PBAQ: u8 = 0x05;
/// One-time sensor cleaning (1 minute).
pub const MODE_CLEANING: u8 = 0x80;

const CMD_GET_PRODUCT_ID: u8 = 0x00;
const CMD_GET_TRACKING_NUMBER: u8 = 0x01;
const CMD_GET_OPERATION_MODE: u8 = 0x10;
const CMD_SET_OPERATION_MODE: u8 = 0x11;
const CMD_SET_HUMIDITY: u8 = 0x12;
const CMD_GET_MEASUREMENT_RESULTS: u8 = 0x18;
const CMD_CONFIG_GPIO: u8 = 0x20;
const CMD_GET_GPIO: u8 = 0x21;
const CMD_SET_GPIO: u8 = 0x22;
const CMD_CLEAR_GPIO: u8 = 0x23;
const CMD_RESET: u8 = 0x8F;

const STATUS_NOT_COMPLETE: u8 = 0x10;
const POLL_MS: u32 = 200;
const MAX_LEN: usize = 16;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// I²C transfer failed.
    Bus(E),
    /// Response checksum wrong.
    Checksum,
    /// Non-zero status byte (bit 7 invalid command, bit 6 request checksum, bit 5 sensor NACK,
    /// bit 4 not complete, bits 3:0 mode error with `0xA` = sensor damaged).
    Status(u8),
    /// `Get Product ID` did not return `0x2310`.
    InvalidProductId(u16),
    /// No answer / no new sample within the timeout.
    Timeout,
    /// Argument out of range.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// A decoded measurement (`Get Measurement Results`).
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub struct Measurement {
    /// Sample counter (0–255, wraps).
    pub sample: u8,
    /// IAQ index, UBA level 1.0–5.0 (0 in PBAQ mode).
    pub iaq: f32,
    /// TVOC in mg/m³.
    pub tvoc_mg_m3: f32,
    /// Ethanol equivalent in ppm.
    pub etoh_ppm: f32,
    /// Estimated CO₂ in ppm (0 in PBAQ mode).
    pub eco2_ppm: u16,
    /// Relative IAQ (0 in PBAQ mode).
    pub relative_iaq: u16,
    /// `true` after the warm-up samples of the mode.
    pub warmed_up: bool,
    /// `true` when decoded in PBAQ mode (only TVOC and ethanol are valid).
    pub pbaq: bool,
}

/// The decoded status byte of the last response.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct Status {
    /// Raw status byte.
    pub raw: u8,
    /// Bit 7: invalid command, read without request or bus error.
    pub invalid_command: bool,
    /// Bit 6: request checksum wrong.
    pub checksum_error: bool,
    /// Bit 5: internal gas-sensor I²C NACK.
    pub sensor_nack: bool,
    /// Bit 4: asynchronous command not complete.
    pub not_complete: bool,
    /// Bits 3:0: operation-mode error (0 OK, 0xA sensor damaged).
    pub mode_error: u8,
}

fn checksum(data: &[u8]) -> u8 {
    !data.iter().fold(0u8, |a, &b| a.wrapping_add(b))
}

/// UBA rating text for an IAQ index: "very good" (< 2.0), "good", "medium", "poor", "bad" (≥ 5.0).
pub fn air_quality_label(iaq: f32) -> &'static str {
    if iaq < 2.0 {
        "very good"
    } else if iaq < 3.0 {
        "good"
    } else if iaq < 4.0 {
        "medium"
    } else if iaq < 5.0 {
        "poor"
    } else {
        "bad"
    }
}

fn period(mode: u8) -> f32 {
    match mode {
        MODE_IAQ_2ND_GEN => 3.0,
        MODE_ULP => 90.0,
        MODE_PBAQ => 5.0,
        _ => 0.0,
    }
}

fn warmup(mode: u8) -> u8 {
    match mode {
        MODE_IAQ_2ND_GEN => 100,
        MODE_ULP => 10,
        MODE_PBAQ => 60,
        _ => 0,
    }
}

/// RRH46410 minimal driver — read the air-quality values of the IAQ 2nd Gen mode.
pub struct Rrh46410Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    mode: u8,
    last_status: u8,
    last_counter: Option<u8>,
    samples: u32,
    last: Measurement,
}

impl<I2C: I2c> Rrh46410Minimal<I2C> {
    /// Create a new `Rrh46410Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`] (clock stretching).
    /// * `addr` — 7-bit device address ([`ADDRESS`], `0x38`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, mode: MODE_SUSPEND, last_status: 0, last_counter: None, samples: 0, last: Measurement::default() }
    }

    /// Release the underlying I²C bus.
    pub fn release_bus(self) -> I2C {
        self.i2c
    }

    /// Wait for the module (up to 1 s), verify the product ID and start IAQ 2nd Gen mode.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.wait_product_id(delay)?;
        self.set_mode_raw(MODE_IAQ_2ND_GEN)
    }

    /// Wait for the next sample (sample period plus 2 s) and decode it.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Measurement, Error<I2C::Error>> {
        let timeout = (period(self.mode) * 1000.0) as u32 + 2000;
        if self.wait_sample(delay, timeout)? {
            Ok(self.last)
        } else {
            Err(Error::Timeout)
        }
    }

    fn transact(&mut self, command: u8, params: &[u8], out: &mut [u8], check: bool) -> Result<u8, Error<I2C::Error>> {
        if params.len() > MAX_LEN || out.len() > MAX_LEN {
            return Err(Error::InvalidArgument);
        }
        let mut req = [0u8; MAX_LEN + 2];
        req[0] = command;
        req[1..=params.len()].copy_from_slice(params);
        req[params.len() + 1] = checksum(&req[..=params.len()]);
        self.i2c.write(self.addr, &req[..params.len() + 2])?;
        let mut resp = [0u8; MAX_LEN + 2];
        let n = out.len();
        self.i2c.read(self.addr, &mut resp[..n + 2])?;
        if checksum(&resp[..=n]) != resp[n + 1] {
            return Err(Error::Checksum);
        }
        self.last_status = resp[0];
        if check && resp[0] != 0 {
            return Err(Error::Status(resp[0]));
        }
        out.copy_from_slice(&resp[1..=n]);
        Ok(resp[0])
    }

    fn wait_product_id<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let mut d = [0u8; 5];
        let mut attempt = 0;
        loop {
            match self.transact(CMD_GET_PRODUCT_ID, &[], &mut d, true) {
                Ok(_) => break,
                Err(e) => {
                    attempt += 1;
                    if attempt > 20 {
                        return Err(match e {
                            Error::Bus(_) => Error::Timeout,
                            other => other,
                        });
                    }
                    delay.delay_ms(50);
                }
            }
        }
        let pid = u16::from_be_bytes([d[0], d[1]]);
        if pid != PRODUCT_ID {
            return Err(Error::InvalidProductId(pid));
        }
        Ok(())
    }

    fn set_mode_raw(&mut self, mode: u8) -> Result<(), Error<I2C::Error>> {
        self.transact(CMD_SET_OPERATION_MODE, &[mode], &mut [], true)?;
        self.mode = mode;
        self.last_counter = None;
        self.samples = 0;
        Ok(())
    }

    /// Raw result bytes, or `None` before the first sample of the mode.
    fn fetch(&mut self) -> Result<Option<[u8; 9]>, Error<I2C::Error>> {
        let mut d = [0u8; 9];
        let n = if self.mode == MODE_PBAQ { 5 } else { 9 };
        let status = self.transact(CMD_GET_MEASUREMENT_RESULTS, &[], &mut d[..n], false)?;
        if status == STATUS_NOT_COMPLETE {
            return Ok(None);
        }
        if status != 0 {
            return Err(Error::Status(status));
        }
        Ok(Some(d))
    }

    fn decode(&mut self, d: &[u8; 9]) -> Measurement {
        let counter = d[0];
        match self.last_counter {
            None => self.samples = counter as u32,
            Some(last) => self.samples += counter.wrapping_sub(last) as u32,
        }
        self.last_counter = Some(counter);
        let warmed_up = self.samples >= warmup(self.mode) as u32;
        let m = if self.mode == MODE_PBAQ {
            Measurement {
                sample: counter,
                tvoc_mg_m3: u16::from_be_bytes([d[1], d[2]]) as f32 / 1000.0,
                etoh_ppm: u16::from_be_bytes([d[3], d[4]]) as f32 / 1000.0,
                warmed_up,
                pbaq: true,
                ..Measurement::default()
            }
        } else {
            Measurement {
                sample: counter,
                iaq: d[1] as f32 / 10.0,
                tvoc_mg_m3: u16::from_be_bytes([d[2], d[3]]) as f32 / 100.0,
                etoh_ppm: u16::from_be_bytes([d[4], d[5]]) as f32 / 100.0,
                eco2_ppm: u16::from_be_bytes([d[6], d[7]]),
                relative_iaq: d[8] as u16 * 10,
                warmed_up,
                pbaq: false,
            }
        };
        self.last = m;
        m
    }

    fn wait_sample<D: DelayNs>(&mut self, delay: &mut D, timeout_ms: u32) -> Result<bool, Error<I2C::Error>> {
        let last = self.last_counter;
        let polls = timeout_ms / POLL_MS + 1;
        for i in 0..polls {
            if let Some(d) = self.fetch()? {
                if last != Some(d[0]) {
                    self.decode(&d);
                    return Ok(true);
                }
            }
            if i + 1 < polls {
                delay.delay_ms(POLL_MS);
            }
        }
        Ok(false)
    }
}

/// RRH46410 full driver — identity, operation modes, humidity, GPIOs, status, reset, raw
/// commands and the INT poll.
pub struct Rrh46410Full<I2C> {
    inner: Rrh46410Minimal<I2C>,
}

impl<I2C: I2c> Rrh46410Full<I2C> {
    /// Create a new `Rrh46410Full`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`] (clock stretching).
    /// * `addr` — 7-bit device address ([`ADDRESS`], `0x38`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Rrh46410Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release_bus(self) -> I2C {
        self.inner.release_bus()
    }

    /// Wait for the module, verify the product ID and start IAQ 2nd Gen mode (see [`Rrh46410Minimal::init`]).
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Wait for the next sample and decode it (see [`Rrh46410Minimal::read`]).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Measurement, Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// `Get Product ID`: the product ID (`0x2310`) and the firmware version `[major, minor, patch]`.
    pub fn product_id(&mut self) -> Result<(u16, [u8; 3]), Error<I2C::Error>> {
        let mut d = [0u8; 5];
        self.inner.transact(CMD_GET_PRODUCT_ID, &[], &mut d, true)?;
        Ok((u16::from_be_bytes([d[0], d[1]]), [d[2], d[3], d[4]]))
    }

    /// `Get Tracking Number`: the 6-byte ASIC tracking number.
    pub fn tracking_number(&mut self) -> Result<[u8; 6], Error<I2C::Error>> {
        let mut d = [0u8; 6];
        self.inner.transact(CMD_GET_TRACKING_NUMBER, &[], &mut d, true)?;
        Ok(d)
    }

    /// `Get Operation Mode`: one of the `MODE_*` constants (other values behave as SUSPEND).
    pub fn get_mode(&mut self) -> Result<u8, Error<I2C::Error>> {
        let mut d = [0u8; 1];
        self.inner.transact(CMD_GET_OPERATION_MODE, &[], &mut d, true)?;
        self.inner.mode = d[0];
        Ok(d[0])
    }

    /// `Set Operation Mode`: [`MODE_SUSPEND`], [`MODE_IAQ_2ND_GEN`], [`MODE_ULP`] or [`MODE_PBAQ`].
    ///
    /// Stops a running measurement, restarts the warm-up and sets INT low. Use
    /// [`Self::clean_sensor`] for the cleaning mode.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), Error<I2C::Error>> {
        if !matches!(mode, MODE_SUSPEND | MODE_IAQ_2ND_GEN | MODE_ULP | MODE_PBAQ) {
            return Err(Error::InvalidArgument);
        }
        self.inner.set_mode_raw(mode)
    }

    /// Stop measuring (SUSPEND, about 0.7 µA).
    pub fn suspend(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.set_mode_raw(MODE_SUSPEND)
    }

    /// Read and decode the latest result without waiting; `None` before the first sample of the mode.
    pub fn read_results(&mut self) -> Result<Option<Measurement>, Error<I2C::Error>> {
        match self.inner.fetch()? {
            Some(d) => Ok(Some(self.inner.decode(&d))),
            None => Ok(None),
        }
    }

    /// Wait until the sample counter changes; the new sample is returned by [`Self::last_result`].
    ///
    /// Returns `true` when a new sample arrived within `timeout_ms`.
    pub fn wait_for_sample<D: DelayNs>(&mut self, delay: &mut D, timeout_ms: u32) -> Result<bool, Error<I2C::Error>> {
        self.inner.wait_sample(delay, timeout_ms)
    }

    /// The result decoded by the last `read`, `read_results` or `wait_for_sample`.
    pub fn last_result(&self) -> Measurement {
        self.inner.last
    }

    /// `Set Humidity`: ambient relative humidity 0–100 %, sent as `round(percent / 100 × 255)`.
    pub fn set_humidity(&mut self, percent: f32) -> Result<(), Error<I2C::Error>> {
        if !(0.0..=100.0).contains(&percent) {
            return Err(Error::InvalidArgument);
        }
        let code = (percent / 100.0 * 255.0 + 0.5) as u8;
        self.inner.transact(CMD_SET_HUMIDITY, &[code], &mut [], true)?;
        Ok(())
    }

    /// Sample period in seconds: 3 (IAQ 2nd Gen), 90 (ULP), 5 (PBAQ), 0 otherwise; `None` = current mode.
    pub fn sample_period(&self, mode: Option<u8>) -> f32 {
        period(mode.unwrap_or(self.inner.mode))
    }

    /// Warm-up samples: 100 (IAQ 2nd Gen), 10 (ULP), 60 (PBAQ), 0 otherwise; `None` = current mode.
    pub fn warmup_samples(&self, mode: Option<u8>) -> u8 {
        warmup(mode.unwrap_or(self.inner.mode))
    }

    /// UBA rating text: "very good" (< 2.0), "good", "medium", "poor", "bad" (≥ 5.0).
    pub fn air_quality_label(iaq: f32) -> &'static str {
        air_quality_label(iaq)
    }

    /// Run the one-time 1-minute sensor cleaning and wait (up to 90 s) for SUSPEND.
    ///
    /// Execute it at most once in the sensor's lifetime and do not interrupt it. `confirm` must
    /// be `true` (otherwise [`Error::InvalidArgument`]).
    pub fn clean_sensor<D: DelayNs>(&mut self, delay: &mut D, confirm: bool) -> Result<(), Error<I2C::Error>> {
        if !confirm {
            return Err(Error::InvalidArgument);
        }
        self.inner.set_mode_raw(MODE_CLEANING)?;
        for _ in 0..90 {
            if self.get_mode()? != MODE_CLEANING {
                self.inner.mode = MODE_SUSPEND;
                return Ok(());
            }
            delay.delay_ms(1000);
        }
        Err(Error::Timeout)
    }

    fn gpio(&mut self, command: u8, mask: u8) -> Result<(), Error<I2C::Error>> {
        if mask > 3 {
            return Err(Error::InvalidArgument);
        }
        self.inner.transact(command, &[mask], &mut [], true)?;
        Ok(())
    }

    /// `Config GPIO`: bit 0 GPIO1 output, bit 1 GPIO2 output (0–3).
    pub fn config_gpio(&mut self, directions: u8) -> Result<(), Error<I2C::Error>> {
        self.gpio(CMD_CONFIG_GPIO, directions)
    }

    /// `Get GPIO`: bits 0–3 = GPIO1–GPIO4 levels.
    pub fn get_gpio(&mut self) -> Result<u8, Error<I2C::Error>> {
        let mut d = [0u8; 1];
        self.inner.transact(CMD_GET_GPIO, &[], &mut d, true)?;
        Ok(d[0])
    }

    /// `Set GPIO`: drive the outputs in `mask` (0–3) high.
    pub fn set_gpio(&mut self, mask: u8) -> Result<(), Error<I2C::Error>> {
        self.gpio(CMD_SET_GPIO, mask)
    }

    /// `Clear GPIO`: drive the outputs in `mask` (0–3) low.
    pub fn clear_gpio(&mut self, mask: u8) -> Result<(), Error<I2C::Error>> {
        self.gpio(CMD_CLEAR_GPIO, mask)
    }

    /// The decoded status byte of the last response.
    pub fn status(&self) -> Status {
        let s = self.inner.last_status;
        Status {
            raw: s,
            invalid_command: s & 0x80 != 0,
            checksum_error: s & 0x40 != 0,
            sensor_nack: s & 0x20 != 0,
            not_complete: s & 0x10 != 0,
            mode_error: s & 0x0F,
        }
    }

    /// `Reset` (`0x8F`, no response), wait 500 ms and verify the product ID again; the module
    /// restarts in SUSPEND.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let addr = self.inner.addr;
        let _ = self.inner.i2c.write(addr, &[CMD_RESET, checksum(&[CMD_RESET])]); // may not be acknowledged
        delay.delay_ms(500);
        self.inner.wait_product_id(delay)?;
        self.inner.mode = MODE_SUSPEND;
        self.inner.last_counter = None;
        self.inner.samples = 0;
        Ok(())
    }

    /// Raw request with checksums; `out.len()` response parameter bytes (max 16) are read and the
    /// status byte is available from [`Self::status`]. `Reset` (`0x8F`) sends the request only.
    pub fn send_command(&mut self, command: u8, params: &[u8], out: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        if command == CMD_RESET {
            if params.len() > MAX_LEN {
                return Err(Error::InvalidArgument);
            }
            let mut req = [0u8; MAX_LEN + 2];
            req[0] = command;
            req[1..=params.len()].copy_from_slice(params);
            req[params.len() + 1] = checksum(&req[..=params.len()]);
            let addr = self.inner.addr;
            self.inner.i2c.write(addr, &req[..params.len() + 2])?;
            return Ok(());
        }
        self.inner.transact(command, params, out, false)?;
        Ok(())
    }

    /// `true` when the sample counter changed since the last decoded result (the result is not
    /// consumed). With `INT` wired, read the pin level instead (high = new result).
    pub fn poll_interrupt(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(match self.inner.fetch()? {
            Some(d) => self.inner.last_counter != Some(d[0]),
            None => false,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};
    use std::vec;
    use std::vec::Vec as StdVec;

    const A: u8 = ADDRESS;

    fn with_cs(d: &[u8]) -> StdVec<u8> {
        let mut v = d.to_vec();
        v.push(checksum(d));
        v
    }

    /// One request/response pair.
    fn tx(req: &[u8], resp: &[u8]) -> StdVec<T> {
        vec![T::write(A, with_cs(req)), T::read(A, with_cs(resp))]
    }

    fn init_tx() -> StdVec<T> {
        let mut t = tx(&[0x00], &[0x00, 0x23, 0x10, 1, 2, 3]);
        t.extend(tx(&[0x11, 0x01], &[0x00]));
        t
    }

    const IAQ: [u8; 10] = [0x00, 5, 25, 0x00, 0x32, 0x01, 0x2C, 0x02, 0x58, 3];

    #[test]
    fn checksum_matches_datasheet() {
        assert_eq!(checksum(&[0x00]), 0xFF);
        assert_eq!(checksum(&[0x18]), 0xE7);
        assert_eq!(checksum(&[0x8F]), 0x70);
    }

    #[test]
    fn init_and_read() {
        let mut t = init_tx();
        t.extend(tx(&[0x18], &IAQ));
        let mut m = Rrh46410Minimal::new(I2cMock::new(&t), A);
        let mut d = NoopDelay::new();
        m.init(&mut d).unwrap();
        let r = m.read(&mut d).unwrap();
        assert!((r.iaq - 2.5).abs() < 1e-6);
        assert!((r.tvoc_mg_m3 - 0.5).abs() < 1e-6);
        assert!((r.etoh_ppm - 3.0).abs() < 1e-6);
        assert_eq!(r.eco2_ppm, 600);
        assert_eq!(r.relative_iaq, 30);
        assert_eq!(r.sample, 5);
        assert!(!r.warmed_up && !r.pbaq);
        m.release_bus().done();
    }

    #[test]
    fn init_retries_and_rejects_wrong_product() {
        let mut t = vec![T::write(A, vec![0x00, 0xFF]).with_error(ErrorKind::Other)];
        t.extend(tx(&[0x00], &[0x00, 0x12, 0x34, 1, 2, 3]));
        let mut m = Rrh46410Minimal::new(I2cMock::new(&t), A);
        assert_eq!(m.init(&mut NoopDelay::new()), Err(Error::InvalidProductId(0x1234)));
        m.release_bus().done();
    }

    #[test]
    fn warm_up_counts_across_wrap() {
        let mut t = init_tx();
        let mut r1 = IAQ;
        r1[1] = 99;
        t.extend(tx(&[0x18], &r1));
        let mut r2 = IAQ;
        r2[1] = 100;
        t.extend(tx(&[0x18], &r2));
        let mut r3 = IAQ;
        r3[1] = 44;
        t.extend(tx(&[0x18], &r3));
        let mut m = Rrh46410Minimal::new(I2cMock::new(&t), A);
        let mut d = NoopDelay::new();
        m.init(&mut d).unwrap();
        assert!(!m.read(&mut d).unwrap().warmed_up);
        assert!(m.read(&mut d).unwrap().warmed_up);
        let r = m.read(&mut d).unwrap();
        assert!(r.warmed_up && r.sample == 44);
        m.release_bus().done();
    }

    #[test]
    fn errors() {
        let mut t = init_tx();
        let mut bad = with_cs(&IAQ);
        bad[10] ^= 1;
        t.push(T::write(A, vec![0x18, 0xE7]));
        t.push(T::read(A, bad));
        let mut damaged = IAQ;
        damaged[0] = 0x0A;
        t.extend(tx(&[0x18], &damaged));
        let mut m = Rrh46410Minimal::new(I2cMock::new(&t), A);
        let mut d = NoopDelay::new();
        m.init(&mut d).unwrap();
        assert_eq!(m.read(&mut d), Err(Error::Checksum));
        assert_eq!(m.read(&mut d), Err(Error::Status(0x0A)));
        m.release_bus().done();
    }

    #[test]
    fn full_api() {
        let mut t = init_tx();
        t.extend(tx(&[0x00], &[0x00, 0x23, 0x10, 1, 2, 3]));
        t.extend(tx(&[0x01], &[0x00, 1, 2, 3, 4, 5, 6]));
        t.extend(tx(&[0x10], &[0x00, 0x01]));
        let mut nr = IAQ;
        nr[0] = 0x10;
        nr[1] = 0;
        t.extend(tx(&[0x18], &nr));
        let mut s3 = IAQ;
        s3[1] = 3;
        t.extend(tx(&[0x18], &s3));
        t.extend(tx(&[0x18], &s3));
        let mut s4 = IAQ;
        s4[1] = 4;
        t.extend(tx(&[0x18], &s4));
        t.extend(tx(&[0x12, 128], &[0x00]));
        t.extend(tx(&[0x11, 0x05], &[0x00]));
        t.extend(tx(&[0x18], &[0x00, 2, 0x01, 0xF4, 0x0B, 0xB8]));
        t.extend(tx(&[0x20, 0x03], &[0x00]));
        t.extend(tx(&[0x22, 0x01], &[0x00]));
        t.extend(tx(&[0x21], &[0x00, 0x0D]));
        t.extend(tx(&[0x23, 0x01], &[0x00]));
        t.extend(tx(&[0x55], &[0x80]));
        t.extend(tx(&[0x11, 0x00], &[0x00]));
        t.push(T::write(A, vec![0x8F, 0x70]));
        t.extend(tx(&[0x00], &[0x00, 0x23, 0x10, 1, 2, 3]));
        let mut f = Rrh46410Full::new(I2cMock::new(&t), A);
        let mut d = NoopDelay::new();
        f.init(&mut d).unwrap();
        assert_eq!(f.product_id().unwrap(), (0x2310, [1, 2, 3]));
        assert_eq!(f.tracking_number().unwrap(), [1, 2, 3, 4, 5, 6]);
        assert_eq!(f.get_mode().unwrap(), MODE_IAQ_2ND_GEN);
        assert_eq!(f.read_results().unwrap(), None);
        assert!(f.status().not_complete);
        assert_eq!(f.read_results().unwrap().unwrap().sample, 3);
        assert!(!f.poll_interrupt().unwrap());
        assert!(f.wait_for_sample(&mut d, 0).unwrap());
        assert_eq!(f.last_result().sample, 4);
        f.set_humidity(50.0).unwrap();
        assert_eq!(f.set_humidity(101.0), Err(Error::InvalidArgument));
        f.set_mode(MODE_PBAQ).unwrap();
        assert_eq!(f.set_mode(0x04), Err(Error::InvalidArgument));
        assert_eq!((f.sample_period(None), f.warmup_samples(None)), (5.0, 60));
        assert_eq!((f.sample_period(Some(MODE_ULP)), f.warmup_samples(Some(MODE_ULP))), (90.0, 10));
        assert_eq!((f.sample_period(Some(MODE_IAQ_2ND_GEN)), f.warmup_samples(Some(MODE_IAQ_2ND_GEN))), (3.0, 100));
        let p = f.read_results().unwrap().unwrap();
        assert!(p.pbaq && (p.tvoc_mg_m3 - 0.5).abs() < 1e-6 && (p.etoh_ppm - 3.0).abs() < 1e-6 && p.eco2_ppm == 0);
        f.config_gpio(0x03).unwrap();
        f.set_gpio(0x01).unwrap();
        assert_eq!(f.get_gpio().unwrap(), 0x0D);
        f.clear_gpio(0x01).unwrap();
        assert_eq!(f.set_gpio(4), Err(Error::InvalidArgument));
        f.send_command(0x55, &[], &mut []).unwrap();
        assert!(f.status().invalid_command);
        assert_eq!(f.clean_sensor(&mut d, false), Err(Error::InvalidArgument));
        f.suspend().unwrap();
        f.reset(&mut d).unwrap();
        assert_eq!(f.sample_period(None), 0.0);
        f.release_bus().done();
    }

    #[test]
    fn labels() {
        type F = Rrh46410Full<I2cMock>;
        assert_eq!(F::air_quality_label(1.5), "very good");
        assert_eq!(F::air_quality_label(2.0), "good");
        assert_eq!(F::air_quality_label(3.9), "medium");
        assert_eq!(F::air_quality_label(4.0), "poor");
        assert_eq!(F::air_quality_label(5.0), "bad");
    }
}
