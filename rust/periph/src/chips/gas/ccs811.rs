//! CCS811 — indoor air quality gas sensor (ScioSense / ams).
//!
//! Communicates over I²C at `0x5A` (`ADDR` low, default) or `0x5B` (`ADDR` high). The on-chip
//! algorithm reports an equivalent CO₂ (eCO₂, 400–8192 ppm) and total VOC (TVOC, 0–1187 ppb).
//! Construction does no bus access; `init()` checks `HW_ID` (`0x81`), writes `APP_START` if the chip
//! is still in boot mode and selects drive mode 1 (`MEAS_MODE = 0x10`: one sample per second,
//! interrupts off). No environment data is written (the chip assumes 50 %RH and 25 °C).
//!
//! The chip stretches the I²C clock; tie `nWAKE` low. New sensors need a 48 h burn-in and values
//! settle about 20 min after each power-up.
//!
//! `nINT` is active low and open-drain and is released when `ALG_RESULT_DATA` is read. The Rust
//! driver exposes [`Ccs811Full::poll_interrupt`] (no callback subscription): the caller wires the
//! pin into an ISR or polling loop and calls `poll_interrupt`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register::{read_register, write_register};

const REG_STATUS: u8 = 0x00;
const REG_MEAS_MODE: u8 = 0x01;
const REG_ALG_RESULT_DATA: u8 = 0x02;
const REG_RAW_DATA: u8 = 0x03;
const REG_ENV_DATA: u8 = 0x05;
const REG_NTC: u8 = 0x06;
const REG_THRESHOLDS: u8 = 0x10;
const REG_BASELINE: u8 = 0x11;
const REG_HW_ID: u8 = 0x20;
const REG_HW_VERSION: u8 = 0x21;
const REG_FW_BOOT_VERSION: u8 = 0x23;
const REG_FW_APP_VERSION: u8 = 0x24;
const REG_ERROR_ID: u8 = 0xE0;
const REG_APP_START: u8 = 0xF4;
const REG_SW_RESET: u8 = 0xFF;

const HW_ID: u8 = 0x81;
const MODE_1: u8 = 0x10;
const INT_DATARDY: u8 = 0x08;
const INT_THRESH: u8 = 0x04;
const RESET_KEY: [u8; 4] = [0x11, 0xE5, 0x72, 0x8A];
/// Sample interval in ms per drive mode (0 = idle).
const INTERVAL_MS: [u32; 5] = [0, 1000, 10000, 60000, 250];

/// `STATUS.FW_MODE`: application mode.
pub const STATUS_FW_MODE: u8 = 0x80;
/// `STATUS.APP_VALID`: valid application firmware loaded.
pub const STATUS_APP_VALID: u8 = 0x10;
/// `STATUS.DATA_READY`: new sample in `ALG_RESULT_DATA`.
pub const STATUS_DATA_READY: u8 = 0x08;
/// `STATUS.ERROR`: see `ERROR_ID`.
pub const STATUS_ERROR: u8 = 0x01;
/// Interrupt source: new sample (`MEAS_MODE.INT_DATARDY`).
pub const SOURCE_DATA_READY: u8 = 0x01;
/// Interrupt source: eCO₂ threshold crossing (`INT_DATARDY` + `INT_THRESH`).
pub const SOURCE_THRESHOLD: u8 = 0x02;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// `HW_ID` did not return `0x81`.
    InvalidChip(u8),
    /// `STATUS.APP_VALID` is 0: no application firmware loaded.
    NoApplication,
    /// The chip did not enter application mode after `APP_START`.
    AppStartFailed,
    /// No sample arrived in time.
    Timeout,
    /// The chip set `STATUS.ERROR`; carries `ERROR_ID`.
    Sensor(u8),
    /// An argument is outside the supported range.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// One algorithm sample.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct Ccs811Measurement {
    /// Equivalent CO₂ in ppm (400–8192).
    pub eco2_ppm: u16,
    /// Total VOC in ppb (0–1187).
    pub tvoc_ppb: u16,
}

/// Firmware version (major.minor.trivial).
#[derive(Debug, PartialEq, Eq, Clone, Copy, Default)]
pub struct Ccs811Version {
    /// Major version.
    pub major: u8,
    /// Minor version.
    pub minor: u8,
    /// Trivial (patch) version.
    pub trivial: u8,
}

/// CCS811 minimal driver — one call returns eCO₂ and TVOC.
pub struct Ccs811Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    meas_mode: u8,
}

impl<I2C: I2c> Ccs811Minimal<I2C> {
    /// Create a new `Ccs811Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x5A` or `0x5B`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, meas_mode: MODE_1 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Verify the chip, start the application firmware and select drive mode 1.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let id = self.read_u8(REG_HW_ID)?;
        if id != HW_ID {
            return Err(Error::InvalidChip(id));
        }
        self.start_app(delay)?;
        self.meas_mode = MODE_1;
        self.write(REG_MEAS_MODE, &[MODE_1])
    }

    /// Wait for the next sample (`DATA_READY`) and read eCO₂ and TVOC.
    ///
    /// Returns [`Error::Sensor`] with `ERROR_ID` when the chip reports an error.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Ccs811Measurement, Error<I2C::Error>> {
        self.wait_data_ready(delay)?;
        self.read_result()
    }

    fn start_app<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let status = self.read_u8(REG_STATUS)?;
        if status & STATUS_APP_VALID == 0 {
            return Err(Error::NoApplication);
        }
        if status & STATUS_FW_MODE != 0 {
            return Ok(());
        }
        self.write(REG_APP_START, &[])?;
        delay.delay_ms(2);
        if self.read_u8(REG_STATUS)? & STATUS_FW_MODE == 0 {
            return Err(Error::AppStartFailed);
        }
        Ok(())
    }

    fn wait_data_ready<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let mode = ((self.meas_mode >> 4) & 0x07) as usize;
        let interval = if mode < INTERVAL_MS.len() { INTERVAL_MS[mode] } else { 0 };
        let limit = core::cmp::max(1500, interval * 3 / 2);
        let mut waited = 0u32;
        loop {
            if self.read_u8(REG_STATUS)? & STATUS_DATA_READY != 0 {
                return Ok(());
            }
            if waited >= limit {
                return Err(Error::Timeout);
            }
            delay.delay_ms(20);
            waited += 20;
        }
    }

    fn read_result(&mut self) -> Result<Ccs811Measurement, Error<I2C::Error>> {
        let mut b = [0u8; 5];
        read_register(&mut self.i2c, self.addr, REG_ALG_RESULT_DATA as u32, 1, &mut b)?;
        if b[4] & STATUS_ERROR != 0 {
            return Err(Error::Sensor(self.read_u8(REG_ERROR_ID)?));
        }
        Ok(Ccs811Measurement {
            eco2_ppm: ((b[0] as u16) << 8) | b[1] as u16,
            tvoc_ppb: ((b[2] as u16) << 8) | b[3] as u16,
        })
    }

    fn read_u8(&mut self, reg: u8) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        read_register(&mut self.i2c, self.addr, reg as u32, 1, &mut b)?;
        Ok(b[0])
    }

    fn read_u16(&mut self, reg: u8) -> Result<u16, Error<I2C::Error>> {
        let mut b = [0u8; 2];
        read_register(&mut self.i2c, self.addr, reg as u32, 1, &mut b)?;
        Ok(((b[0] as u16) << 8) | b[1] as u16)
    }

    fn write(&mut self, reg: u8, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        write_register(&mut self.i2c, self.addr, reg as u32, 1, data)?;
        Ok(())
    }
}

/// CCS811 full driver — drive modes, raw sensor data, environment compensation, the NTC input,
/// thresholds, baseline save/restore, versions, soft reset and the interrupt configuration.
///
/// Owns a [`Ccs811Minimal`] and delegates `init` and `read`.
pub struct Ccs811Full<I2C> {
    inner: Ccs811Minimal<I2C>,
}

impl<I2C: I2c> Ccs811Full<I2C> {
    /// Create a new `Ccs811Full`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x5A` or `0x5B`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Ccs811Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Verify the chip, start the application firmware and select drive mode 1.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Wait for the next sample and read eCO₂ and TVOC.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Ccs811Measurement, Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Wait for the next sample and return the equivalent CO₂ in ppm.
    pub fn read_eco2<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        Ok(self.read(delay)?.eco2_ppm)
    }

    /// Wait for the next sample and return the total VOC in ppb.
    pub fn read_tvoc<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        Ok(self.read(delay)?.tvoc_ppb)
    }

    /// Read `RAW_DATA`: `(current_ua, voltage_v)` — sense current 0–63 µA and the voltage across
    /// the sensor (ADC × 1.65 / 1023).
    pub fn read_raw(&mut self) -> Result<(u8, f32), Error<I2C::Error>> {
        let raw = self.inner.read_u16(REG_RAW_DATA)?;
        Ok(((raw >> 10) as u8, (raw & 0x3FF) as f32 * 1.65 / 1023.0))
    }

    /// `STATUS.DATA_READY`: a new sample is waiting.
    pub fn data_ready(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.status()? & STATUS_DATA_READY != 0)
    }

    /// Select the drive mode, keeping the interrupt bits.
    ///
    /// 0 idle, 1 every second, 2 every 10 s, 3 every 60 s, 4 every 250 ms (raw data only). When
    /// moving to a slower mode, idle (mode 0) first; the first sample at the new rate may be stale.
    /// Returns [`Error::InvalidArgument`] for a mode above 4.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), Error<I2C::Error>> {
        if mode > 4 {
            return Err(Error::InvalidArgument);
        }
        self.inner.meas_mode = (self.inner.meas_mode & 0x0C) | (mode << 4);
        self.write_meas_mode()
    }

    /// Write ambient humidity (%, 0–100) and temperature (°C, -25 to 102) to `ENV_DATA`.
    pub fn set_environment(&mut self, humidity_rh: f32, temperature_c: f32) -> Result<(), Error<I2C::Error>> {
        if !(0.0..=100.0).contains(&humidity_rh) || !(-25.0..=102.0).contains(&temperature_c) {
            return Err(Error::InvalidArgument);
        }
        let h = (humidity_rh * 512.0 + 0.5) as u16;
        let t = ((temperature_c + 25.0) * 512.0 + 0.5) as u16;
        self.inner.write(REG_ENV_DATA, &[(h >> 8) as u8, h as u8, (t >> 8) as u8, t as u8])
    }

    /// Read the NTC circuit voltages `(v_ref_mv, v_ntc_mv)` in mV.
    pub fn read_ntc(&mut self) -> Result<(u16, u16), Error<I2C::Error>> {
        let mut b = [0u8; 4];
        read_register(&mut self.inner.i2c, self.inner.addr, REG_NTC as u32, 1, &mut b)?;
        Ok((((b[0] as u16) << 8) | b[1] as u16, ((b[2] as u16) << 8) | b[3] as u16))
    }

    /// NTC resistance in ohms, `V_NTC × R_REF / V_REF`. Returns [`Error::InvalidArgument`] when
    /// `V_REF` reads 0 (no NTC circuit).
    pub fn ntc_resistance(&mut self, r_ref_ohm: f32) -> Result<f32, Error<I2C::Error>> {
        let (v_ref, v_ntc) = self.read_ntc()?;
        if v_ref == 0 {
            return Err(Error::InvalidArgument);
        }
        Ok(v_ntc as f32 * r_ref_ohm / v_ref as f32)
    }

    /// Set the eCO₂ boundaries (ppm) and hysteresis for the threshold interrupt (spec defaults
    /// 1500 / 2500 / 50). Returns [`Error::InvalidArgument`] if `low_medium_ppm ≥ medium_high_ppm`.
    pub fn set_thresholds(&mut self, low_medium_ppm: u16, medium_high_ppm: u16, hysteresis_ppm: u8)
        -> Result<(), Error<I2C::Error>> {
        if low_medium_ppm >= medium_high_ppm {
            return Err(Error::InvalidArgument);
        }
        self.inner.write(REG_THRESHOLDS, &[(low_medium_ppm >> 8) as u8, low_medium_ppm as u8,
                                           (medium_high_ppm >> 8) as u8, medium_high_ppm as u8, hysteresis_ppm])
    }

    /// Read the encoded algorithm baseline (save it to non-volatile storage).
    pub fn get_baseline(&mut self) -> Result<u16, Error<I2C::Error>> {
        self.inner.read_u16(REG_BASELINE)
    }

    /// Restore a saved baseline (only after about 20 min of operation).
    pub fn set_baseline(&mut self, baseline: u16) -> Result<(), Error<I2C::Error>> {
        self.inner.write(REG_BASELINE, &[(baseline >> 8) as u8, baseline as u8])
    }

    /// Read `STATUS` (`STATUS_*` bits).
    pub fn status(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_u8(REG_STATUS)
    }

    /// Read `ERROR_ID` (bit 0 write-invalid … bit 5 heater supply).
    pub fn error(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_u8(REG_ERROR_ID)
    }

    /// Read `(hw_id, hw_version)` — `0x81` and `0x1X`.
    pub fn hardware_version(&mut self) -> Result<(u8, u8), Error<I2C::Error>> {
        Ok((self.inner.read_u8(REG_HW_ID)?, self.inner.read_u8(REG_HW_VERSION)?))
    }

    /// Read the bootloader and application firmware versions `(boot, app)`.
    pub fn firmware_version(&mut self) -> Result<(Ccs811Version, Ccs811Version), Error<I2C::Error>> {
        Ok((self.version(REG_FW_BOOT_VERSION)?, self.version(REG_FW_APP_VERSION)?))
    }

    /// `SW_RESET` into boot mode, then start the application and mode 1 again (`init`). Drive mode,
    /// interrupt bits, thresholds and environment data return to their defaults.
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        // The chip may not acknowledge while it resets.
        let _ = self.inner.write(REG_SW_RESET, &RESET_KEY);
        delay.delay_ms(20);
        self.inner.init(delay)
    }

    /// Enable `nINT` for a source (`SOURCE_THRESHOLD` also sets `INT_DATARDY`), keeping the drive mode.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.meas_mode |= int_bits(source);
        self.write_meas_mode()
    }

    /// Disable `nINT` for a source, keeping the drive mode.
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.meas_mode &= !int_bits(source);
        self.write_meas_mode()
    }

    /// Read `STATUS`; `STATUS_DATA_READY` marks a new sample. Does not release `nINT` (reading
    /// `ALG_RESULT_DATA` does).
    pub fn poll_interrupt(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.status()
    }

    fn write_meas_mode(&mut self) -> Result<(), Error<I2C::Error>> {
        let m = self.inner.meas_mode;
        self.inner.write(REG_MEAS_MODE, &[m])
    }

    fn version(&mut self, reg: u8) -> Result<Ccs811Version, Error<I2C::Error>> {
        let v = self.inner.read_u16(reg)?;
        Ok(Ccs811Version { major: (v >> 12) as u8, minor: ((v >> 8) & 0x0F) as u8, trivial: v as u8 })
    }
}

fn int_bits(source: u8) -> u8 {
    let mut bits = 0;
    if source & SOURCE_DATA_READY != 0 {
        bits |= INT_DATARDY;
    }
    if source & SOURCE_THRESHOLD != 0 {
        bits |= INT_DATARDY | INT_THRESH;
    }
    bits
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const A: u8 = 0x5A;
    const RESULT: [u8; 5] = [0x01, 0xF4, 0x00, 0x0F, 0x98];

    fn w(reg: u8, data: &[u8]) -> T {
        let mut v = vec![reg];
        v.extend_from_slice(data);
        T::write(A, v)
    }
    fn wr(reg: u8, data: &[u8]) -> T {
        T::write_read(A, vec![reg], data.to_vec())
    }
    fn chain(parts: &[Vec<T>]) -> Vec<T> {
        parts.iter().flat_map(|p| p.iter().cloned()).collect()
    }
    fn init_seq() -> Vec<T> {
        vec![wr(0x20, &[0x81]), wr(0x00, &[0x10]), w(0xF4, &[]), wr(0x00, &[0x90]), w(0x01, &[0x10])]
    }
    fn read_seq() -> Vec<T> {
        vec![wr(0x00, &[0x98]), wr(0x02, &RESULT)]
    }

    #[test]
    fn init_starts_application_and_mode_1() {
        let mut i2c = I2cMock::new(&chain(&[init_seq(), read_seq()]));
        let mut s = Ccs811Minimal::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        s.init(&mut d).unwrap();
        assert_eq!(s.read(&mut d).unwrap(), Ccs811Measurement { eco2_ppm: 500, tvoc_ppb: 15 });
        i2c.done();
    }

    #[test]
    fn init_skips_app_start_in_application_mode() {
        let mut i2c = I2cMock::new(&[wr(0x20, &[0x81]), wr(0x00, &[0x90]), w(0x01, &[0x10])]);
        let mut s = Ccs811Minimal::new(i2c.clone(), A);
        s.init(&mut NoopDelay::new()).unwrap();
        i2c.done();
    }

    #[test]
    fn init_errors() {
        let mut i2c = I2cMock::new(&[wr(0x20, &[0x55])]);
        assert_eq!(Ccs811Minimal::new(i2c.clone(), A).init(&mut NoopDelay::new()), Err(Error::InvalidChip(0x55)));
        i2c.done();
        let mut i2c = I2cMock::new(&[wr(0x20, &[0x81]), wr(0x00, &[0x00])]);
        assert_eq!(Ccs811Minimal::new(i2c.clone(), A).init(&mut NoopDelay::new()), Err(Error::NoApplication));
        i2c.done();
        let mut i2c = I2cMock::new(&[wr(0x20, &[0x81]), wr(0x00, &[0x10]), w(0xF4, &[]), wr(0x00, &[0x10])]);
        assert_eq!(Ccs811Minimal::new(i2c.clone(), A).init(&mut NoopDelay::new()), Err(Error::AppStartFailed));
        i2c.done();
    }

    #[test]
    fn read_waits_for_data_ready_and_reports_errors() {
        let t = chain(&[init_seq(), vec![wr(0x00, &[0x90]), wr(0x00, &[0x98]), wr(0x02, &RESULT),
                                         wr(0x00, &[0x98]), wr(0x02, &[0x01, 0xF4, 0x00, 0x0F, 0x99]),
                                         wr(0xE0, &[0x08])]]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Ccs811Full::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        s.init(&mut d).unwrap();
        assert_eq!(s.read_eco2(&mut d).unwrap(), 500);
        assert_eq!(s.read_tvoc(&mut d), Err(Error::Sensor(0x08)));
        i2c.done();
    }

    #[test]
    fn read_times_out() {
        let mut t = init_seq();
        for _ in 0..76 {
            t.push(wr(0x00, &[0x90]));
        }
        let mut i2c = I2cMock::new(&t);
        let mut s = Ccs811Minimal::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        s.init(&mut d).unwrap();
        assert_eq!(s.read(&mut d), Err(Error::Timeout));
        i2c.done();
    }

    #[test]
    fn raw_ntc_status_and_versions() {
        let t = chain(&[init_seq(), vec![
            wr(0x03, &[0x8C, 0x88]), wr(0x00, &[0x98]), wr(0x06, &[0x06, 0x40, 0x03, 0x20]),
            wr(0x06, &[0x06, 0x40, 0x03, 0x20]), wr(0x06, &[0x00, 0x00, 0x03, 0x20]),
            wr(0x00, &[0x90]), wr(0xE0, &[0x10]), wr(0x20, &[0x81]), wr(0x21, &[0x12]),
            wr(0x23, &[0x10, 0x00]), wr(0x24, &[0x11, 0x02]), wr(0x00, &[0x98]),
        ]]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Ccs811Full::new(i2c.clone(), A);
        s.init(&mut NoopDelay::new()).unwrap();
        let (cur, volt) = s.read_raw().unwrap();
        assert_eq!(cur, 35);
        assert!((volt - 136.0 * 1.65 / 1023.0).abs() < 1e-5);
        assert!(s.data_ready().unwrap());
        assert_eq!(s.read_ntc().unwrap(), (1600, 800));
        assert!((s.ntc_resistance(100000.0).unwrap() - 50000.0).abs() < 0.01);
        assert_eq!(s.ntc_resistance(100000.0), Err(Error::InvalidArgument));
        assert_eq!(s.status().unwrap(), 0x90);
        assert_eq!(s.error().unwrap(), 0x10);
        assert_eq!(s.hardware_version().unwrap(), (0x81, 0x12));
        let (boot, app) = s.firmware_version().unwrap();
        assert_eq!(boot, Ccs811Version { major: 1, minor: 0, trivial: 0 });
        assert_eq!(app, Ccs811Version { major: 1, minor: 1, trivial: 2 });
        assert_eq!(s.poll_interrupt().unwrap(), 0x98);
        i2c.done();
    }

    #[test]
    fn mode_and_interrupt_bits() {
        let t = chain(&[init_seq(), vec![
            w(0x01, &[0x18]), w(0x01, &[0x28]), w(0x01, &[0x2C]), w(0x01, &[0x20]),
            w(0x01, &[0x2C]), w(0x01, &[0x24]), w(0x01, &[0x20]), w(0x01, &[0x40]), w(0x01, &[0x00]),
        ]]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Ccs811Full::new(i2c.clone(), A);
        s.init(&mut NoopDelay::new()).unwrap();
        s.enable_interrupt(SOURCE_DATA_READY).unwrap();
        s.set_mode(2).unwrap();
        s.enable_interrupt(SOURCE_THRESHOLD).unwrap();
        s.disable_interrupt(SOURCE_THRESHOLD).unwrap();
        s.enable_interrupt(SOURCE_THRESHOLD).unwrap();
        s.disable_interrupt(SOURCE_DATA_READY).unwrap();
        s.disable_interrupt(SOURCE_THRESHOLD).unwrap();
        s.set_mode(4).unwrap();
        s.set_mode(0).unwrap();
        assert_eq!(s.set_mode(5), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn environment_thresholds_and_baseline() {
        let t = chain(&[init_seq(), vec![
            w(0x05, &[0x61, 0x00, 0x5F, 0x00]), w(0x05, &[0x64, 0x00, 0x64, 0x00]),
            w(0x10, &[0x03, 0xE8, 0x07, 0xD0, 0x32]), w(0x10, &[0x05, 0xDC, 0x09, 0xC4, 0x32]),
            wr(0x11, &[0x84, 0x7B]), w(0x11, &[0x12, 0x34]),
        ]]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Ccs811Full::new(i2c.clone(), A);
        s.init(&mut NoopDelay::new()).unwrap();
        s.set_environment(48.5, 22.5).unwrap();
        s.set_environment(50.0, 25.0).unwrap();
        assert_eq!(s.set_environment(101.0, 25.0), Err(Error::InvalidArgument));
        assert_eq!(s.set_environment(50.0, -26.0), Err(Error::InvalidArgument));
        s.set_thresholds(1000, 2000, 50).unwrap();
        s.set_thresholds(1500, 2500, 50).unwrap();
        assert_eq!(s.set_thresholds(2000, 1000, 50), Err(Error::InvalidArgument));
        assert_eq!(s.get_baseline().unwrap(), 0x847B);
        s.set_baseline(0x1234).unwrap();
        i2c.done();
    }

    #[test]
    fn soft_reset_restarts_application() {
        let t = chain(&[init_seq(), vec![w(0x01, &[0x30]), w(0xFF, &RESET_KEY)], init_seq(), read_seq()]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Ccs811Full::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        s.init(&mut d).unwrap();
        s.set_mode(3).unwrap();
        s.soft_reset(&mut d).unwrap();
        assert_eq!(s.read(&mut d).unwrap().eco2_ppm, 500);
        i2c.done();
    }
}
