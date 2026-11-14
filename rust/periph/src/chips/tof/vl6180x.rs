//! VL6180X — proximity and ambient-light sensor (STMicroelectronics).
//!
//! VCSEL emitter, SPAD time-of-flight receiver and ambient light sensor (ALS)
//! behind one aperture. Measures absolute distance 0–100 mm (8-bit result in
//! mm) and ambient light (16-bit count, converted to lux). Registers use a
//! 16-bit big-endian index; 16-bit results and thresholds are MSB first. See
//! `specs/tof/vl6180x.md`.
//!
//! The driver takes the raw `embedded-hal` I²C bus plus a [`DelayNs`] passed
//! to every blocking call (1 ms boot wait, bounded poll loops: 100 ms for a
//! range result, integration time + 100 ms for an ALS result; expiry is
//! [`Vl6180xError::Timeout`]). The mandatory private-register settings applied
//! by `init()` come from ST application note AN4545 and are not documented in
//! the datasheet.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Vl6180xFull::poll_interrupt`] (no callback
//! subscription — polling is always caller-managed in this crate's `no_std`
//! Rust drivers). GPIO1 is configured as an interrupt output, active low.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// Default 7-bit I²C address.
pub const VL6180X_I2C_ADDRESS: u8 = 0x29;
/// Expected `IDENTIFICATION__MODEL_ID`.
pub const VL6180X_MODEL_ID: u8 = 0xB4;
/// Interrupt source: range event.
pub const VL6180X_SOURCE_RANGE: u8 = 0x01;
/// Interrupt source: ALS event.
pub const VL6180X_SOURCE_ALS: u8 = 0x02;
/// Interrupt source: error event.
pub const VL6180X_SOURCE_ERROR: u8 = 0x04;
/// Interrupt mode: value below the low threshold.
pub const VL6180X_MODE_LEVEL_LOW: u8 = 1;
/// Interrupt mode: value above the high threshold.
pub const VL6180X_MODE_LEVEL_HIGH: u8 = 2;
/// Interrupt mode: value outside the threshold window.
pub const VL6180X_MODE_OUT_OF_WINDOW: u8 = 3;
/// Interrupt mode: new sample ready (default).
pub const VL6180X_MODE_NEW_SAMPLE_READY: u8 = 4;

const REG_MODEL_ID: u16 = 0x000;
const REG_MODEL_REV_MAJOR: u16 = 0x001;
const REG_MODEL_REV_MINOR: u16 = 0x002;
const REG_MODE_GPIO1: u16 = 0x011;
const REG_INTERRUPT_CONFIG: u16 = 0x014;
const REG_INTERRUPT_CLEAR: u16 = 0x015;
const REG_FRESH_OUT_OF_RESET: u16 = 0x016;
const REG_GROUPED_PARAMETER_HOLD: u16 = 0x017;
const REG_RANGE_START: u16 = 0x018;
const REG_RANGE_THRESH_HIGH: u16 = 0x019;
const REG_RANGE_THRESH_LOW: u16 = 0x01A;
const REG_RANGE_INTERMEASUREMENT: u16 = 0x01B;
const REG_RANGE_MAX_CONVERGENCE: u16 = 0x01C;
const REG_RANGE_CROSSTALK_RATE: u16 = 0x01E;
const REG_RANGE_OFFSET: u16 = 0x024;
const REG_VHV_RECALIBRATE: u16 = 0x02E;
const REG_VHV_REPEAT_RATE: u16 = 0x031;
const REG_ALS_START: u16 = 0x038;
const REG_ALS_THRESH_HIGH: u16 = 0x03A;
const REG_ALS_THRESH_LOW: u16 = 0x03C;
const REG_ALS_INTERMEASUREMENT: u16 = 0x03E;
const REG_ALS_GAIN: u16 = 0x03F;
const REG_ALS_INTEGRATION: u16 = 0x040;
const REG_RANGE_STATUS: u16 = 0x04D;
const REG_INTERRUPT_STATUS: u16 = 0x04F;
const REG_ALS_VAL: u16 = 0x050;
const REG_HISTORY_BUFFER: u16 = 0x052;
const REG_RANGE_VAL: u16 = 0x062;
const REG_AVERAGING_PERIOD: u16 = 0x10A;
const REG_RESULT_SCALER: u16 = 0x120;
const REG_I2C_ADDRESS: u16 = 0x212;
const REG_INTERLEAVED_ENABLE: u16 = 0x2A3;

/// Mandatory private-register settings (ST AN4545), applied while
/// `SYSTEM__FRESH_OUT_OF_RESET` = 1.
const PRIVATE_SETTINGS: [(u16, u8); 30] = [
    (0x0207, 0x01),
    (0x0208, 0x01),
    (0x0096, 0x00),
    (0x0097, 0xFD),
    (0x00E3, 0x01),
    (0x00E4, 0x03),
    (0x00E5, 0x02),
    (0x00E6, 0x01),
    (0x00E7, 0x03),
    (0x00F5, 0x02),
    (0x00D9, 0x05),
    (0x00DB, 0xCE),
    (0x00DC, 0x03),
    (0x00DD, 0xF8),
    (0x009F, 0x00),
    (0x00A3, 0x3C),
    (0x00B7, 0x00),
    (0x00BB, 0x3C),
    (0x00B2, 0x09),
    (0x00CA, 0x09),
    (0x0198, 0x01),
    (0x01B0, 0x17),
    (0x01AD, 0x00),
    (0x00FF, 0x05),
    (0x0100, 0x05),
    (0x0199, 0x05),
    (0x01A6, 0x1B),
    (0x01AC, 0x3E),
    (0x01A7, 0x1F),
    (0x0030, 0x00),
];

/// Recommended public settings (the 0x040 integration period is written as 16 bit separately).
const RECOMMENDED_SETTINGS: [(u16, u8); 8] = [
    (REG_MODE_GPIO1, 0x10),
    (REG_AVERAGING_PERIOD, 0x30),
    (REG_ALS_GAIN, 0x46),
    (REG_VHV_REPEAT_RATE, 0xFF),
    (REG_VHV_RECALIBRATE, 0x01),
    (REG_RANGE_INTERMEASUREMENT, 0x09),
    (REG_ALS_INTERMEASUREMENT, 0x31),
    (REG_INTERRUPT_CONFIG, 0x24),
];

/// Actual ALS gain per gain code 0–7.
const GAIN: [f32; 8] = [20.0, 10.32, 5.21, 2.60, 1.72, 1.28, 1.01, 40.0];

const RANGE_TIMEOUT_MS: u32 = 100;
const VHV_TIMEOUT_MS: u32 = 100;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Vl6180xError<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// A poll loop timed out.
    Timeout,
    /// The range error code of the measurement was non-zero (1–15, datasheet codes).
    Range(u8),
    /// An argument was outside its allowed range.
    InvalidArgument,
}

impl<E> From<E> for Vl6180xError<E> {
    fn from(e: E) -> Self {
        Vl6180xError::Bus(e)
    }
}

/// VL6180X minimal driver — one single-shot range measurement per `read_range()`.
///
/// `init()` waits 1 ms for boot, then — only while `SYSTEM__FRESH_OUT_OF_RESET`
/// is 1 — applies the mandatory private settings, then the recommended
/// settings (averaging period 48, max convergence 49 ms, ALS gain 1, 100 ms ALS
/// integration, new-sample interrupt configuration). Construction does no bus
/// access.
pub struct Vl6180xMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    gain_code: u8,
    integration_ms: u16,
}

impl<I2C: I2c> Vl6180xMinimal<I2C> {
    /// Create a new `Vl6180xMinimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address ([`VL6180X_I2C_ADDRESS`]).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, gain_code: 6, integration_ms: 100 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn rd(&mut self, reg: u16, buf: &mut [u8]) -> Result<(), Vl6180xError<I2C::Error>> {
        self.i2c.write_read(self.addr, &reg.to_be_bytes(), buf)?;
        Ok(())
    }

    fn rd8(&mut self, reg: u16) -> Result<u8, Vl6180xError<I2C::Error>> {
        let mut b = [0u8; 1];
        self.rd(reg, &mut b)?;
        Ok(b[0])
    }

    fn rd16(&mut self, reg: u16) -> Result<u16, Vl6180xError<I2C::Error>> {
        let mut b = [0u8; 2];
        self.rd(reg, &mut b)?;
        Ok(u16::from_be_bytes(b))
    }

    fn wr8(&mut self, reg: u16, value: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        let r = reg.to_be_bytes();
        self.i2c.write(self.addr, &[r[0], r[1], value])?;
        Ok(())
    }

    fn wr16(&mut self, reg: u16, value: u16) -> Result<(), Vl6180xError<I2C::Error>> {
        let r = reg.to_be_bytes();
        let v = value.to_be_bytes();
        self.i2c.write(self.addr, &[r[0], r[1], v[0], v[1]])?;
        Ok(())
    }

    /// Poll the interrupt status until `(status >> shift) & 7 == 4`, at most `timeout_ms`.
    fn wait_sample<D: DelayNs>(&mut self, shift: u8, timeout_ms: u32, delay: &mut D) -> Result<(), Vl6180xError<I2C::Error>> {
        for _ in 0..=timeout_ms {
            if (self.rd8(REG_INTERRUPT_STATUS)? >> shift) & 0x07 == 4 {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Vl6180xError::Timeout)
    }

    fn read_range_result(&mut self) -> Result<(u8, u8), Vl6180xError<I2C::Error>> {
        let error = self.rd8(REG_RANGE_STATUS)? >> 4;
        let mm = self.rd8(REG_RANGE_VAL)?;
        self.wr8(REG_INTERRUPT_CLEAR, 0x07)?;
        Ok((mm, error))
    }

    fn measure_range<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u8, u8), Vl6180xError<I2C::Error>> {
        self.wr8(REG_RANGE_START, 0x01)?;
        if let Err(e) = self.wait_sample(0, RANGE_TIMEOUT_MS, delay) {
            self.wr8(REG_INTERRUPT_CLEAR, 0x07)?;
            return Err(e);
        }
        self.read_range_result()
    }

    /// Run the initialization sequence: 1 ms boot wait, the mandatory private
    /// settings while fresh out of reset, then the recommended settings.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Vl6180xError<I2C::Error>> {
        delay.delay_ms(1);
        if self.rd8(REG_FRESH_OUT_OF_RESET)? == 1 {
            for (reg, value) in PRIVATE_SETTINGS {
                self.wr8(reg, value)?;
            }
            self.wr8(REG_FRESH_OUT_OF_RESET, 0)?;
        }
        for (reg, value) in RECOMMENDED_SETTINGS {
            self.wr8(reg, value)?;
        }
        self.wr16(REG_ALS_INTEGRATION, 0x0063)?;
        self.gain_code = 6;
        self.integration_ms = 100;
        Ok(())
    }

    /// Take one single-shot range measurement; returns the distance in mm
    /// (0–255, reliable up to about 100 mm).
    ///
    /// [`Vl6180xError::Range`] on a non-zero range error code,
    /// [`Vl6180xError::Timeout`] if no result arrives within 100 ms.
    pub fn read_range<D: DelayNs>(&mut self, delay: &mut D) -> Result<u8, Vl6180xError<I2C::Error>> {
        let (mm, error) = self.measure_range(delay)?;
        if error != 0 {
            return Err(Vl6180xError::Range(error));
        }
        Ok(mm)
    }
}

/// VL6180X full driver — extends [`Vl6180xMinimal`] with ALS (lux), continuous
/// and interleaved modes, thresholds, gain / integration / scaler, convergence
/// and averaging, range offset and crosstalk, VHV recalibration, address
/// change, identification, the history buffer and interrupt polling.
pub struct Vl6180xFull<I2C> {
    inner: Vl6180xMinimal<I2C>,
}

impl<I2C: I2c> Vl6180xFull<I2C> {
    /// Create a new `Vl6180xFull`; the constructor does no bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Vl6180xMinimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Run the initialization sequence (see [`Vl6180xMinimal::init`]).
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Vl6180xError<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Take one single-shot range measurement (see [`Vl6180xMinimal::read_range`]).
    pub fn read_range<D: DelayNs>(&mut self, delay: &mut D) -> Result<u8, Vl6180xError<I2C::Error>> {
        self.inner.read_range(delay)
    }

    /// Take one single-shot range measurement without failing on range errors.
    ///
    /// Returns `(range_mm, error_code)`; the range is valid only when the code is 0.
    pub fn read_range_status<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u8, u8), Vl6180xError<I2C::Error>> {
        self.inner.measure_range(delay)
    }

    /// Start continuous ranging. `period_ms`: 10–2550 in 10 ms steps.
    pub fn start_continuous_range(&mut self, period_ms: u16) -> Result<(), Vl6180xError<I2C::Error>> {
        if !(10..=2550).contains(&period_ms) {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr8(REG_RANGE_INTERMEASUREMENT, (period_ms / 10 - 1) as u8)?;
        self.inner.wr8(REG_INTERRUPT_CLEAR, 0x07)?;
        self.inner.wr8(REG_RANGE_START, 0x03)
    }

    /// Stop continuous ranging (the start/stop bit toggles the running mode).
    pub fn stop_continuous_range(&mut self) -> Result<(), Vl6180xError<I2C::Error>> {
        self.inner.wr8(REG_RANGE_START, 0x01)
    }

    /// Wait for the next continuous-mode range sample and read it (mm).
    ///
    /// [`Vl6180xError::Range`] on a non-zero range error code,
    /// [`Vl6180xError::Timeout`] if no sample arrives within 100 ms plus one period.
    pub fn read_range_continuous<D: DelayNs>(&mut self, delay: &mut D) -> Result<u8, Vl6180xError<I2C::Error>> {
        let period = (self.inner.rd8(REG_RANGE_INTERMEASUREMENT)? as u32 + 1) * 10;
        self.inner.wait_sample(0, RANGE_TIMEOUT_MS + period, delay)?;
        let (mm, error) = self.inner.read_range_result()?;
        if error != 0 {
            return Err(Vl6180xError::Range(error));
        }
        Ok(mm)
    }

    /// Take one single-shot ALS measurement; returns the 16-bit count.
    pub fn read_als_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Vl6180xError<I2C::Error>> {
        self.inner.wr8(REG_ALS_START, 0x01)?;
        let timeout = self.inner.integration_ms as u32 + 100;
        if let Err(e) = self.inner.wait_sample(3, timeout, delay) {
            self.inner.wr8(REG_INTERRUPT_CLEAR, 0x07)?;
            return Err(e);
        }
        let raw = self.inner.rd16(REG_ALS_VAL)?;
        self.inner.wr8(REG_INTERRUPT_CLEAR, 0x07)?;
        Ok(raw)
    }

    /// Take one single-shot ALS measurement and convert it to lux:
    /// `0.32 * (count / gain) * (100 / integration_ms)` with the actual gain of the
    /// current gain code (without cover glass).
    pub fn read_lux<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Vl6180xError<I2C::Error>> {
        let raw = self.read_als_raw(delay)?;
        Ok(0.32 * (raw as f32 / GAIN[self.inner.gain_code as usize]) * (100.0 / self.inner.integration_ms as f32))
    }

    /// Start continuous ALS measurements. `period_ms`: 10–2550 in 10 ms steps.
    pub fn start_continuous_als(&mut self, period_ms: u16) -> Result<(), Vl6180xError<I2C::Error>> {
        if !(10..=2550).contains(&period_ms) {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr8(REG_ALS_INTERMEASUREMENT, (period_ms / 10 - 1) as u8)?;
        self.inner.wr8(REG_INTERRUPT_CLEAR, 0x07)?;
        self.inner.wr8(REG_ALS_START, 0x03)
    }

    /// Stop continuous ALS measurements (the start/stop bit toggles the running mode).
    pub fn stop_continuous_als(&mut self) -> Result<(), Vl6180xError<I2C::Error>> {
        self.inner.wr8(REG_ALS_START, 0x01)
    }

    /// Enable or disable interleaved ALS + range mode (started with [`Self::start_continuous_als`]).
    pub fn set_interleaved(&mut self, on: bool) -> Result<(), Vl6180xError<I2C::Error>> {
        self.inner.wr8(REG_INTERLEAVED_ENABLE, on as u8)
    }

    /// Set the ALS analogue gain code 0–7 (0 = 20, 1 = 10, 2 = 5, 3 = 2.5, 4 = 1.67, 5 = 1.25, 6 = 1, 7 = 40).
    pub fn set_als_gain(&mut self, code: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        if code > 7 {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 1)?;
        self.inner.wr8(REG_ALS_GAIN, 0x40 | code)?;
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 0)?;
        self.inner.gain_code = code;
        Ok(())
    }

    /// Set the ALS integration time, 1–512 ms.
    pub fn set_als_integration_time(&mut self, ms: u16) -> Result<(), Vl6180xError<I2C::Error>> {
        if !(1..=512).contains(&ms) {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 1)?;
        self.inner.wr16(REG_ALS_INTEGRATION, ms - 1)?;
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 0)?;
        self.inner.integration_ms = ms;
        Ok(())
    }

    /// Set the ALS result scaler (`FIRMWARE__RESULT_SCALER`), 1–16.
    pub fn set_als_scaler(&mut self, scaler: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        if !(1..=16).contains(&scaler) {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr8(REG_RESULT_SCALER, scaler)
    }

    /// Set the range interrupt thresholds in mm (grouped-parameter-hold protocol); `low <= high`.
    pub fn set_range_thresholds(&mut self, low: u8, high: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        if low > high {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 1)?;
        self.inner.wr8(REG_RANGE_THRESH_HIGH, high)?;
        self.inner.wr8(REG_RANGE_THRESH_LOW, low)?;
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 0)
    }

    /// Set the ALS interrupt thresholds in counts (grouped-parameter-hold protocol); `low <= high`.
    pub fn set_als_thresholds(&mut self, low: u16, high: u16) -> Result<(), Vl6180xError<I2C::Error>> {
        if low > high {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 1)?;
        self.inner.wr16(REG_ALS_THRESH_HIGH, high)?;
        self.inner.wr16(REG_ALS_THRESH_LOW, low)?;
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 0)
    }

    /// Set the maximum convergence time, 1–63 ms.
    pub fn set_max_convergence_time(&mut self, ms: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        if !(1..=63).contains(&ms) {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr8(REG_RANGE_MAX_CONVERGENCE, ms)
    }

    /// Set the ranging averaging sample period (about 64.5 µs per unit; 48 is the default).
    pub fn set_averaging_period(&mut self, units: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        self.inner.wr8(REG_AVERAGING_PERIOD, units)
    }

    /// Set the part-to-part range offset in mm (two's complement on the chip).
    pub fn set_range_offset(&mut self, mm: i8) -> Result<(), Vl6180xError<I2C::Error>> {
        self.inner.wr8(REG_RANGE_OFFSET, mm as u8)
    }

    /// Read the part-to-part range offset in mm.
    pub fn get_range_offset(&mut self) -> Result<i8, Vl6180xError<I2C::Error>> {
        Ok(self.inner.rd8(REG_RANGE_OFFSET)? as i8)
    }

    /// Set the crosstalk compensation rate in Mcps (stored as 9.7 fixed point).
    pub fn set_crosstalk_rate(&mut self, rate: f32) -> Result<(), Vl6180xError<I2C::Error>> {
        if !(rate >= 0.0) || rate * 128.0 + 0.5 > 65535.0 {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr16(REG_RANGE_CROSSTALK_RATE, (rate * 128.0 + 0.5) as u16)
    }

    /// Start a VHV recalibration and wait for the start bit to clear (100 ms timeout).
    pub fn recalibrate_vhv<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Vl6180xError<I2C::Error>> {
        self.inner.wr8(REG_VHV_RECALIBRATE, 0x01)?;
        for _ in 0..=VHV_TIMEOUT_MS {
            if self.inner.rd8(REG_VHV_RECALIBRATE)? & 0x01 == 0 {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Vl6180xError::Timeout)
    }

    /// Change the chip's I²C address (volatile, 0x08–0x77). The driver switches to the new address.
    pub fn set_address(&mut self, address: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        if !(0x08..=0x77).contains(&address) {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.inner.wr8(REG_I2C_ADDRESS, address & 0x7F)?;
        self.inner.addr = address;
        Ok(())
    }

    /// Read the model ID (0xB4).
    pub fn model_id(&mut self) -> Result<u8, Vl6180xError<I2C::Error>> {
        self.inner.rd8(REG_MODEL_ID)
    }

    /// Read the model revision `(major, minor)` (bits 2:0 of each revision register).
    pub fn revision(&mut self) -> Result<(u8, u8), Vl6180xError<I2C::Error>> {
        Ok((self.inner.rd8(REG_MODEL_REV_MAJOR)? & 0x07, self.inner.rd8(REG_MODEL_REV_MINOR)? & 0x07))
    }

    /// Read the eight-entry history buffer (enable it through the history control register first).
    pub fn read_history(&mut self) -> Result<[u16; 8], Vl6180xError<I2C::Error>> {
        let mut b = [0u8; 16];
        self.inner.rd(REG_HISTORY_BUFFER, &mut b)?;
        let mut out = [0u16; 8];
        for (i, v) in out.iter_mut().enumerate() {
            *v = u16::from_be_bytes([b[2 * i], b[2 * i + 1]]);
        }
        Ok(out)
    }

    fn set_interrupt_mode(&mut self, source: u8, mode: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        let shift = match source {
            VL6180X_SOURCE_RANGE => 0,
            VL6180X_SOURCE_ALS => 3,
            _ => return Err(Vl6180xError::InvalidArgument),
        };
        let value = (self.inner.rd8(REG_INTERRUPT_CONFIG)? & !(0x07 << shift)) | (mode << shift);
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 1)?;
        self.inner.wr8(REG_INTERRUPT_CONFIG, value)?;
        self.inner.wr8(REG_GROUPED_PARAMETER_HOLD, 0)
    }

    /// Select the interrupt mode (`VL6180X_MODE_*`, 1–4) of the range or ALS source.
    pub fn enable_interrupt(&mut self, source: u8, mode: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        if !(1..=4).contains(&mode) {
            return Err(Vl6180xError::InvalidArgument);
        }
        self.set_interrupt_mode(source, mode)
    }

    /// Disable the interrupt of the range or ALS source (mode 0).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), Vl6180xError<I2C::Error>> {
        self.set_interrupt_mode(source, 0)
    }

    /// Read the interrupt status and clear all pending sources.
    ///
    /// Returns a bit mask of `VL6180X_SOURCE_RANGE`, `_ALS`, `_ERROR` (0 if none).
    pub fn poll_interrupt(&mut self) -> Result<u8, Vl6180xError<I2C::Error>> {
        let status = self.inner.rd8(REG_INTERRUPT_STATUS)?;
        let mut sources = 0;
        if status & 0x07 != 0 {
            sources |= VL6180X_SOURCE_RANGE;
        }
        if status & 0x38 != 0 {
            sources |= VL6180X_SOURCE_ALS;
        }
        if status & 0xC0 != 0 {
            sources |= VL6180X_SOURCE_ERROR;
        }
        if sources != 0 {
            self.inner.wr8(REG_INTERRUPT_CLEAR, 0x07)?;
        }
        Ok(sources)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = 0x29;

    fn rd(reg: u16, vals: &[u8]) -> T {
        T::write_read(ADDR, reg.to_be_bytes().to_vec(), vals.to_vec())
    }

    fn wr(reg: u16, vals: &[u8]) -> T {
        let mut d = reg.to_be_bytes().to_vec();
        d.extend_from_slice(vals);
        T::write(ADDR, d)
    }

    fn init_transactions(fresh: u8) -> Vec<T> {
        let mut t = vec![rd(0x016, &[fresh])];
        if fresh == 1 {
            for (reg, value) in PRIVATE_SETTINGS {
                t.push(wr(reg, &[value]));
            }
            t.push(wr(0x016, &[0]));
        }
        for (reg, value) in RECOMMENDED_SETTINGS {
            t.push(wr(reg, &[value]));
        }
        t.push(wr(0x040, &[0x00, 0x63]));
        t
    }

    fn range_ok(error: u8, mm: u8) -> Vec<T> {
        vec![
            wr(0x018, &[0x01]),
            rd(0x04F, &[0x04]),
            rd(0x04D, &[error << 4]),
            rd(0x062, &[mm]),
            wr(0x015, &[0x07]),
        ]
    }

    fn hold(inner: Vec<T>) -> Vec<T> {
        let mut t = vec![wr(0x017, &[1])];
        t.extend(inner);
        t.push(wr(0x017, &[0]));
        t
    }

    #[test]
    fn init_applies_private_and_recommended_settings() {
        let mut dev = Vl6180xMinimal::new(I2cMock::new(&init_transactions(1)), ADDR);
        dev.init(&mut NoopDelay::new()).unwrap();
        dev.release().done();
    }

    #[test]
    fn init_skips_private_settings_when_not_fresh() {
        let mut dev = Vl6180xMinimal::new(I2cMock::new(&init_transactions(0)), ADDR);
        dev.init(&mut NoopDelay::new()).unwrap();
        dev.release().done();
    }

    #[test]
    fn read_range_returns_mm() {
        let mut dev = Vl6180xMinimal::new(I2cMock::new(&range_ok(0, 42)), ADDR);
        assert_eq!(dev.read_range(&mut NoopDelay::new()).unwrap(), 42);
        dev.release().done();
    }

    #[test]
    fn read_range_reports_error_code() {
        let mut dev = Vl6180xMinimal::new(I2cMock::new(&range_ok(7, 42)), ADDR);
        assert_eq!(dev.read_range(&mut NoopDelay::new()), Err(Vl6180xError::Range(7)));
        dev.release().done();
    }

    #[test]
    fn read_range_times_out_and_clears() {
        let mut t = vec![wr(0x018, &[0x01])];
        for _ in 0..=RANGE_TIMEOUT_MS {
            t.push(rd(0x04F, &[0x00]));
        }
        t.push(wr(0x015, &[0x07]));
        let mut dev = Vl6180xMinimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(dev.read_range(&mut NoopDelay::new()), Err(Vl6180xError::Timeout));
        dev.release().done();
    }

    #[test]
    fn bus_error_propagates() {
        let t = [wr(0x018, &[0x01]).with_error(ErrorKind::Other)];
        let mut dev = Vl6180xMinimal::new(I2cMock::new(&t), ADDR);
        assert!(matches!(dev.read_range(&mut NoopDelay::new()), Err(Vl6180xError::Bus(_))));
        dev.release().done();
    }

    #[test]
    fn range_status_does_not_fail_on_error() {
        let mut dev = Vl6180xFull::new(I2cMock::new(&range_ok(6, 42)), ADDR);
        assert_eq!(dev.read_range_status(&mut NoopDelay::new()).unwrap(), (42, 6));
        dev.release().done();
    }

    #[test]
    fn als_raw_and_lux() {
        let als = |raw: [u8; 2]| vec![wr(0x038, &[0x01]), rd(0x04F, &[0x20]), rd(0x050, &raw), wr(0x015, &[0x07])];
        let mut dev = Vl6180xFull::new(I2cMock::new(&als([0x01, 0x90])), ADDR);
        assert_eq!(dev.read_als_raw(&mut NoopDelay::new()).unwrap(), 400);
        dev.release().done();

        let mut dev = Vl6180xFull::new(I2cMock::new(&als([0x01, 0x90])), ADDR);
        let lux = dev.read_lux(&mut NoopDelay::new()).unwrap();
        assert!((lux - 0.32 * 400.0 / 1.01).abs() < 1e-3);
        dev.release().done();
    }

    #[test]
    fn gain_and_integration_change_the_lux_scale() {
        let mut t = hold(vec![wr(0x03F, &[0x40])]);
        t.extend(hold(vec![wr(0x040, &[0x00, 0x31])]));
        t.extend([wr(0x038, &[0x01]), rd(0x04F, &[0x20]), rd(0x050, &[0x01, 0x90]), wr(0x015, &[0x07])]);
        let mut dev = Vl6180xFull::new(I2cMock::new(&t), ADDR);
        dev.set_als_gain(0).unwrap();
        dev.set_als_integration_time(50).unwrap();
        let lux = dev.read_lux(&mut NoopDelay::new()).unwrap();
        assert!((lux - 0.32 * (400.0 / 20.0) * 2.0).abs() < 1e-3);
        dev.release().done();
    }

    #[test]
    fn argument_validation() {
        let mut dev = Vl6180xFull::new(I2cMock::new(&[]), ADDR);
        assert_eq!(dev.set_als_gain(8), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.set_als_integration_time(513), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.set_als_scaler(17), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.start_continuous_range(5), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.start_continuous_als(2560), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.set_range_thresholds(90, 80), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.set_als_thresholds(2, 1), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.set_max_convergence_time(64), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.set_crosstalk_rate(-1.0), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.set_address(0x80), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.enable_interrupt(VL6180X_SOURCE_ERROR, 1), Err(Vl6180xError::InvalidArgument));
        assert_eq!(dev.enable_interrupt(VL6180X_SOURCE_RANGE, 5), Err(Vl6180xError::InvalidArgument));
        dev.release().done();
    }

    #[test]
    fn continuous_range() {
        let mut t = vec![wr(0x01B, &[0x04]), wr(0x015, &[0x07]), wr(0x018, &[0x03])];
        t.extend([rd(0x01B, &[0x04]), rd(0x04F, &[0x04]), rd(0x04D, &[0x00]), rd(0x062, &[42]), wr(0x015, &[0x07])]);
        t.push(wr(0x018, &[0x01]));
        let mut dev = Vl6180xFull::new(I2cMock::new(&t), ADDR);
        dev.start_continuous_range(50).unwrap();
        assert_eq!(dev.read_range_continuous(&mut NoopDelay::new()).unwrap(), 42);
        dev.stop_continuous_range().unwrap();
        dev.release().done();
    }

    #[test]
    fn continuous_als_and_interleaved() {
        let t = [
            wr(0x03E, &[0x09]), wr(0x015, &[0x07]), wr(0x038, &[0x03]),
            wr(0x038, &[0x01]), wr(0x2A3, &[1]), wr(0x2A3, &[0]),
        ];
        let mut dev = Vl6180xFull::new(I2cMock::new(&t), ADDR);
        dev.start_continuous_als(100).unwrap();
        dev.stop_continuous_als().unwrap();
        dev.set_interleaved(true).unwrap();
        dev.set_interleaved(false).unwrap();
        dev.release().done();
    }

    #[test]
    fn thresholds_use_grouped_hold() {
        let mut t = hold(vec![wr(0x019, &[80]), wr(0x01A, &[30])]);
        t.extend(hold(vec![wr(0x03A, &[0x13, 0x88]), wr(0x03C, &[0x00, 0x64])]));
        let mut dev = Vl6180xFull::new(I2cMock::new(&t), ADDR);
        dev.set_range_thresholds(30, 80).unwrap();
        dev.set_als_thresholds(100, 5000).unwrap();
        dev.release().done();
    }

    #[test]
    fn tuning_registers() {
        let t = [
            wr(0x120, &[4]), wr(0x01C, &[20]), wr(0x10A, &[100]), wr(0x024, &[0xFB]), rd(0x024, &[0xFB]),
            wr(0x01E, &[0x00, 0x40]),
        ];
        let mut dev = Vl6180xFull::new(I2cMock::new(&t), ADDR);
        dev.set_als_scaler(4).unwrap();
        dev.set_max_convergence_time(20).unwrap();
        dev.set_averaging_period(100).unwrap();
        dev.set_range_offset(-5).unwrap();
        assert_eq!(dev.get_range_offset().unwrap(), -5);
        dev.set_crosstalk_rate(0.5).unwrap();
        dev.release().done();
    }

    #[test]
    fn recalibrate_waits_for_clear() {
        let t = [wr(0x02E, &[0x01]), rd(0x02E, &[0x01]), rd(0x02E, &[0x00])];
        let mut dev = Vl6180xFull::new(I2cMock::new(&t), ADDR);
        dev.recalibrate_vhv(&mut NoopDelay::new()).unwrap();
        dev.release().done();
    }

    #[test]
    fn address_change_switches_the_driver_address() {
        let t = [wr(0x212, &[0x30]), T::write_read(0x30, vec![0x00, 0x00], vec![0xB4])];
        let mut dev = Vl6180xFull::new(I2cMock::new(&t), ADDR);
        dev.set_address(0x30).unwrap();
        assert_eq!(dev.model_id().unwrap(), 0xB4);
        dev.release().done();
    }

    #[test]
    fn identification_and_history() {
        let hist = [0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 1, 0];
        let t = [rd(0x001, &[0x01]), rd(0x002, &[0x03]), rd(0x052, &hist)];
        let mut dev = Vl6180xFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(dev.revision().unwrap(), (1, 3));
        assert_eq!(dev.read_history().unwrap(), [1, 2, 3, 4, 5, 6, 7, 256]);
        dev.release().done();
    }

    #[test]
    fn interrupt_modes_and_polling() {
        let mut t = vec![rd(0x014, &[0x24])];
        t.extend(hold(vec![wr(0x014, &[0x23])]));
        t.push(rd(0x014, &[0x23]));
        t.extend(hold(vec![wr(0x014, &[0x0B])]));
        t.push(rd(0x014, &[0x0B]));
        t.extend(hold(vec![wr(0x014, &[0x08])]));
        t.extend([rd(0x04F, &[0x24]), wr(0x015, &[0x07]), rd(0x04F, &[0x40]), wr(0x015, &[0x07]), rd(0x04F, &[0x00])]);
        let mut dev = Vl6180xFull::new(I2cMock::new(&t), ADDR);
        dev.enable_interrupt(VL6180X_SOURCE_RANGE, VL6180X_MODE_OUT_OF_WINDOW).unwrap();
        dev.enable_interrupt(VL6180X_SOURCE_ALS, VL6180X_MODE_LEVEL_LOW).unwrap();
        dev.disable_interrupt(VL6180X_SOURCE_RANGE).unwrap();
        assert_eq!(dev.poll_interrupt().unwrap(), VL6180X_SOURCE_RANGE | VL6180X_SOURCE_ALS);
        assert_eq!(dev.poll_interrupt().unwrap(), VL6180X_SOURCE_ERROR);
        assert_eq!(dev.poll_interrupt().unwrap(), 0);
        dev.release().done();
    }
}
