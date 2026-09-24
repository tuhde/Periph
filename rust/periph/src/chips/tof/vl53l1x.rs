//! VL53L1X — long-distance Time-of-Flight laser-ranging sensor (STMicroelectronics).
//!
//! 940 nm VCSEL emitter, 16×16 SPAD receiving array behind a lens and an
//! embedded ranging microcontroller measuring absolute distance up to 4 m at
//! up to 50 Hz. Two distance modes (short ~1.3 m, robust in sunlight; long
//! ~3.6–4 m in the dark), a 15–500 ms timing budget and a programmable region
//! of interest (4×4 to 16×16 SPADs). The datasheet has no register map:
//! registers, the default configuration block and all sequences follow ST's
//! Ultra Lite Driver (STSW-IMG009) — see `specs/tof/vl53l1x.md`. Registers use
//! a 16-bit index; multi-byte registers are big-endian.
//!
//! Pin-to-pin compatible with the VL53L0X and shares its register-access /
//! polling plumbing (`vl53_base`) and public API shape.
//!
//! The driver takes the raw `embedded-hal` I²C bus plus a [`DelayNs`] used for
//! the 1.2 ms boot wait and for the bounded poll loops (500 × 1 ms; expiry is
//! [`Vl53l1xError::Timeout`]). Drive `XSHUT` high before constructing it.
//!
//! ## Multiple sensors on one bus
//!
//! All VL53L0X/VL53L1X sensors power up at `0x29`. Hold every sensor's `XSHUT`
//! low, then for each sensor in turn release its `XSHUT`, construct a driver
//! on `0x29`, call [`Vl53l1xFull::set_address`], [`Vl53l1xFull::release`] the
//! bus and build the real driver at the new address. The new address is
//! volatile.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Vl53l1xFull::poll_interrupt`] (no callback
//! subscription — polling is always caller-managed in this crate's `no_std`
//! Rust drivers). `GPIO1` is configured active low, open drain.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use super::vl53_base::{self, Vl53Bus};

const REG_I2C_SLAVE_DEVICE_ADDRESS: u16 = 0x0001;
const REG_VHV_CONFIG_LOOP_BOUND: u16 = 0x0008;
const REG_VHV_INIT: u16 = 0x000B;
const REG_XTALK_PLANE_OFFSET: u16 = 0x0016;
const REG_XTALK_X_GRADIENT: u16 = 0x0018;
const REG_XTALK_Y_GRADIENT: u16 = 0x001A;
const REG_PART_TO_PART_OFFSET: u16 = 0x001E;
const REG_MM_INNER_OFFSET: u16 = 0x0020;
const REG_MM_OUTER_OFFSET: u16 = 0x0022;
const REG_PAD_I2C_HV_EXTSUP: u16 = 0x002E;
const REG_GPIO_EXTSUP_HV: u16 = 0x002F;
const REG_GPIO_HV_MUX_CTRL: u16 = 0x0030;
const REG_GPIO_TIO_HV_STATUS: u16 = 0x0031;
const REG_INTERRUPT_CONFIG_GPIO: u16 = 0x0046;
const REG_PHASECAL_TIMEOUT: u16 = 0x004B;
const REG_RANGE_TIMEOUT_A: u16 = 0x005E;
const REG_RANGE_VCSEL_PERIOD_A: u16 = 0x0060;
const REG_RANGE_TIMEOUT_B: u16 = 0x0061;
const REG_RANGE_VCSEL_PERIOD_B: u16 = 0x0063;
const REG_SIGMA_THRESH: u16 = 0x0064;
const REG_MIN_COUNT_RATE_RTN_LIMIT: u16 = 0x0066;
const REG_RANGE_VALID_PHASE_HIGH: u16 = 0x0069;
const REG_INTERMEASUREMENT_PERIOD: u16 = 0x006C;
const REG_THRESH_HIGH: u16 = 0x0072;
const REG_THRESH_LOW: u16 = 0x0074;
const REG_SD_WOI_SD0: u16 = 0x0078;
const REG_SD_INITIAL_PHASE_SD0: u16 = 0x007A;
const REG_ROI_CENTRE_SPAD: u16 = 0x007F;
const REG_ROI_XY_SIZE: u16 = 0x0080;
const REG_INTERRUPT_CLEAR: u16 = 0x0086;
const REG_MODE_START: u16 = 0x0087;
const REG_RESULT_RANGE_STATUS: u16 = 0x0089;
const REG_OSC_CALIBRATE_VAL: u16 = 0x00DE;
const REG_FIRMWARE_SYSTEM_STATUS: u16 = 0x00E5;
const REG_MODEL_ID: u16 = 0x010F;
const REG_MODULE_TYPE: u16 = 0x0110;
const REG_REVISION_ID: u16 = 0x0111;
const REG_MODE_ROI_CENTRE_SPAD: u16 = 0x013E;

const DEFAULT_CONFIG_START: u16 = 0x002D;
const CALIBRATION_SAMPLES: u32 = 50;

/// ULD `VL51L1X_DEFAULT_CONFIGURATION` — opaque, written verbatim to
/// `0x002D..=0x0087`, one byte per register.
const DEFAULT_CONFIGURATION: [u8; 91] = [
    0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x02, 0x08, 0x00, 0x08, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00, //
    0x00, 0xFF, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0B, 0x00, 0x00, 0x02, 0x0A, 0x21, //
    0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x38, 0xFF, 0x01, 0x00, 0x08, 0x00, //
    0x00, 0x01, 0xCC, 0x0F, 0x01, 0xF1, 0x0D, 0x01, 0x68, 0x00, 0x80, 0x08, 0xB8, 0x00, 0x00, 0x00, //
    0x00, 0x0F, 0x89, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0F, 0x0D, 0x0E, 0x0E, 0x00, //
    0x00, 0x02, 0xC7, 0xFF, 0x9B, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
];

/// ULD `status_rtn`: raw `RESULT__RANGE_STATUS` (bits 4:0) → mapped range status.
const STATUS_MAP: [u8; 24] = [
    255, 255, 255, 5, 2, 4, 1, 7, 3, 0, 255, 255, 9, 13, 255, 255, 255, 255, 10, 6, 255, 255, 11, 12,
];

/// Timing budget table: (ms, short A, short B, long A, long B); 0 = not available.
const BUDGETS: [(u16, u16, u16, u16, u16); 7] = [
    (15, 0x001D, 0x0027, 0, 0),
    (20, 0x0051, 0x006E, 0x001E, 0x0022),
    (33, 0x00D6, 0x006E, 0x0060, 0x006E),
    (50, 0x01AE, 0x01E8, 0x00AD, 0x00C6),
    (100, 0x02E1, 0x0388, 0x01CC, 0x01EA),
    (200, 0x03E1, 0x0496, 0x02D9, 0x02F8),
    (500, 0x0591, 0x05C1, 0x048F, 0x04A4),
];

/// Default 7-bit I²C address.
pub const VL53L1X_I2C_ADDRESS: u8 = vl53_base::I2C_ADDRESS;
/// Expected `IDENTIFICATION__MODEL_ID` + `MODULE_TYPE` word.
pub const VL53L1X_SENSOR_ID: u16 = 0xEACC;
/// `IDENTIFICATION__MODEL_ID`.
pub const VL53L1X_MODEL_ID: u8 = 0xEA;
/// `IDENTIFICATION__MODULE_TYPE`.
pub const VL53L1X_MODULE_TYPE: u8 = 0xCC;
/// Mapped range status meaning "range valid".
pub const VL53L1X_RANGE_STATUS_VALID: u8 = 0;

/// Range < low threshold.
pub const VL53L1X_SOURCE_LEVEL_LOW: u8 = vl53_base::SOURCE_LEVEL_LOW;
/// Range > high threshold.
pub const VL53L1X_SOURCE_LEVEL_HIGH: u8 = vl53_base::SOURCE_LEVEL_HIGH;
/// Range < low threshold or > high threshold.
pub const VL53L1X_SOURCE_OUT_OF_WINDOW: u8 = vl53_base::SOURCE_OUT_OF_WINDOW;
/// A new measurement is available (driver default).
pub const VL53L1X_SOURCE_NEW_SAMPLE_READY: u8 = vl53_base::SOURCE_NEW_SAMPLE_READY;
/// low ≤ range ≤ high.
pub const VL53L1X_SOURCE_IN_WINDOW: u8 = vl53_base::SOURCE_IN_WINDOW;

/// Distance mode for [`Vl53l1xFull::set_distance_mode`].
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Vl53l1xDistanceMode {
    /// ~1.3 m, robust against ambient light.
    Short,
    /// Up to 4 m in the dark (default).
    Long,
}

/// Decoded result block, as returned by [`Vl53l1xFull::read_measurement`].
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Vl53l1xMeasurement {
    /// Range in mm.
    pub distance_mm: u16,
    /// Mapped range status (0 = valid, 255 = no update).
    pub range_status: u8,
    /// Return signal rate in MCPS.
    pub signal_rate_mcps: f32,
    /// Ambient rate in MCPS.
    pub ambient_rate_mcps: f32,
    /// Effective SPAD return count.
    pub effective_spad_count: f32,
}

/// Errors from the VL53L1X driver.
#[derive(Debug)]
pub enum Vl53l1xError<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// The model ID / module type word was not `0xEACC` — wrong chip, wrong
    /// address, or a wiring problem.
    NotFound,
    /// A poll loop expired (500 ms).
    Timeout,
    /// An argument outside its documented range (no bus transaction made).
    InvalidArgument,
    /// `PHASECAL_CONFIG__TIMEOUT_MACROP` holds neither distance mode's value.
    UnknownDistanceMode,
}

impl<E> From<E> for Vl53l1xError<E> {
    fn from(e: E) -> Self {
        Vl53l1xError::Bus(e)
    }
}

fn round_half_away(x: f32) -> i32 {
    if x >= 0.0 { (x + 0.5) as i32 } else { -((-x + 0.5) as i32) }
}

fn word(result: &[u8; 17], i: usize) -> u16 {
    ((result[i] as u16) << 8) | result[i + 1] as u16
}

/// VL53L1X — minimal interface: single-shot distance in mm.
pub struct Vl53l1xMinimal<I2C, D> {
    bus: Vl53Bus<I2C, D>,
    range_status: u8,
    result: [u8; 17],
}

impl<I2C: I2c, D: DelayNs> Vl53l1xMinimal<I2C, D> {
    /// Construct the driver and run the full initialization sequence: 1.2 ms
    /// boot wait, firmware boot poll, sensor ID check, ULD default
    /// configuration, 2V8 I/O mode, `GPIO1` active low, settling ranging.
    /// Leaves the chip idle in long distance mode with a 100 ms timing budget.
    ///
    /// `addr` is `0x29` after power-up.
    pub fn new(i2c: I2C, addr: u8, delay: D) -> Result<Self, Vl53l1xError<I2C::Error>> {
        let mut chip = Self { bus: Vl53Bus::new(i2c, addr, delay, 2), range_status: 255, result: [0; 17] };
        chip.init()?;
        Ok(chip)
    }

    fn wait_data_ready(&mut self) -> Result<(), Vl53l1xError<I2C::Error>> {
        if self.bus.wait_until(|bus| Ok((bus.rd8(REG_GPIO_TIO_HV_STATUS)? & 0x01) == 0))? {
            Ok(())
        } else {
            Err(Vl53l1xError::Timeout)
        }
    }

    fn init(&mut self) -> Result<(), Vl53l1xError<I2C::Error>> {
        self.bus.boot_wait();
        if !self.bus.wait_until(|bus| Ok(bus.rd8(REG_FIRMWARE_SYSTEM_STATUS)? & 0x01 != 0))? {
            return Err(Vl53l1xError::Timeout);
        }
        if self.bus.rd16(REG_MODEL_ID)? != VL53L1X_SENSOR_ID {
            return Err(Vl53l1xError::NotFound);
        }

        for (i, v) in DEFAULT_CONFIGURATION.iter().enumerate() {
            self.bus.wr8(DEFAULT_CONFIG_START + i as u16, *v)?;
        }

        // 2V8 I/O mode for I²C and GPIO1 pads; GPIO1 active low.
        self.bus.wr8(REG_PAD_I2C_HV_EXTSUP, 0x01)?;
        self.bus.wr8(REG_GPIO_EXTSUP_HV, 0x01)?;
        self.bus.wr8(REG_GPIO_HV_MUX_CTRL, 0x11)?;

        // Settling ranging (ULD SensorInit), then two-bound VHV from the
        // previous temperature.
        self.bus.wr8(REG_MODE_START, 0x40)?;
        let ready = self.wait_data_ready();
        self.bus.wr8(REG_INTERRUPT_CLEAR, 0x01)?;
        self.bus.wr8(REG_MODE_START, 0x00)?;
        self.bus.wr8(REG_VHV_CONFIG_LOOP_BOUND, 0x09)?;
        self.bus.wr8(REG_VHV_INIT, 0x00)?;
        ready
    }

    fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        // GPIO1 is active low: line asserted (bit 0 == 0) means data ready.
        Ok((self.bus.rd8(REG_GPIO_TIO_HV_STATUS)? & 0x01) == 0)
    }

    fn read_result(&mut self) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 17];
        self.bus.rd_block(REG_RESULT_RANGE_STATUS, &mut buf)?;
        self.bus.wr8(REG_INTERRUPT_CLEAR, 0x01)?;
        self.result = buf;
        let raw = (buf[0] & 0x1F) as usize;
        self.range_status = if raw < STATUS_MAP.len() { STATUS_MAP[raw] } else { 255 };
        Ok(())
    }

    fn wait_and_read(&mut self) -> Result<u16, Vl53l1xError<I2C::Error>> {
        self.wait_data_ready()?;
        self.read_result()?;
        Ok(word(&self.result, 13))
    }

    /// Take one single-shot measurement; returns the distance in mm.
    ///
    /// Blocks for about one timing budget (100 ms by default). Returns the raw
    /// range even when the measurement is not valid; check
    /// [`Self::range_valid`].
    pub fn distance(&mut self) -> Result<u16, Vl53l1xError<I2C::Error>> {
        self.bus.wr8(REG_INTERRUPT_CLEAR, 0x01)?;
        self.bus.wr8(REG_MODE_START, 0x10)?;
        self.wait_and_read()
    }

    /// `true` iff the mapped range status of the most recent measurement was
    /// 0 (range valid).
    pub fn range_valid(&self) -> bool {
        self.range_status == VL53L1X_RANGE_STATUS_VALID
    }

    fn active_source(&mut self) -> Result<u8, I2C::Error> {
        let value = self.bus.rd8(REG_INTERRUPT_CONFIG_GPIO)?;
        if value & 0x20 != 0 {
            return Ok(VL53L1X_SOURCE_NEW_SAMPLE_READY);
        }
        Ok([VL53L1X_SOURCE_LEVEL_LOW, VL53L1X_SOURCE_LEVEL_HIGH, VL53L1X_SOURCE_OUT_OF_WINDOW, VL53L1X_SOURCE_IN_WINDOW]
            [(value & 0x03) as usize])
    }

    /// Consume the driver and return the underlying I²C bus and delay.
    pub fn release(self) -> (I2C, D) {
        self.bus.release()
    }
}

/// VL53L1X — full interface: extends [`Vl53l1xMinimal`] with timed continuous
/// ranging, the full measurement record, distance mode, timing budget,
/// inter-measurement period, signal and sigma thresholds, region of interest,
/// offset and crosstalk compensation with calibration helpers, temperature
/// update, address change, distance thresholds, identification, and
/// interrupt polling.
pub struct Vl53l1xFull<I2C, D> {
    inner: Vl53l1xMinimal<I2C, D>,
}

impl<I2C: I2c, D: DelayNs> Vl53l1xFull<I2C, D> {
    /// Construct the driver; same initialization as [`Vl53l1xMinimal::new`].
    pub fn new(i2c: I2C, addr: u8, delay: D) -> Result<Self, Vl53l1xError<I2C::Error>> {
        Ok(Self { inner: Vl53l1xMinimal::new(i2c, addr, delay)? })
    }

    /// Single-shot distance in mm. Delegates to [`Vl53l1xMinimal::distance`].
    pub fn distance(&mut self) -> Result<u16, Vl53l1xError<I2C::Error>> {
        self.inner.distance()
    }

    /// Validity of the last measurement. Delegates to
    /// [`Vl53l1xMinimal::range_valid`].
    pub fn range_valid(&self) -> bool {
        self.inner.range_valid()
    }

    /// Consume the driver and return the underlying I²C bus and delay.
    pub fn release(self) -> (I2C, D) {
        self.inner.release()
    }

    /// Start timed continuous ranging. `period_ms` 0–60000; 0 (and any value
    /// below the timing budget) runs at the timing budget, i.e. back-to-back —
    /// the chip requires period ≥ budget.
    pub fn start_continuous(&mut self, period_ms: u32) -> Result<(), Vl53l1xError<I2C::Error>> {
        if period_ms > 60000 {
            return Err(Vl53l1xError::InvalidArgument);
        }
        let budget_ms = self.timing_budget()? / 1000;
        self.set_inter_measurement(period_ms.max(budget_ms).max(1))?;
        self.inner.bus.wr8(REG_INTERRUPT_CLEAR, 0x01)?;
        self.inner.bus.wr8(REG_MODE_START, 0x40)?;
        Ok(())
    }

    /// Stop continuous ranging. Does not wait for a running measurement.
    pub fn stop_continuous(&mut self) -> Result<(), I2C::Error> {
        self.inner.bus.wr8(REG_MODE_START, 0x00)
    }

    /// Wait for the next continuous-mode result and read it (mm; check
    /// [`Self::range_valid`]).
    pub fn read_continuous(&mut self) -> Result<u16, Vl53l1xError<I2C::Error>> {
        self.inner.wait_and_read()
    }

    /// `true` if `GPIO__TIO_HV_STATUS` shows the `GPIO1` line asserted
    /// (non-blocking).
    pub fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        self.inner.data_ready()
    }

    /// Read the full result block and clear the interrupt (non-blocking).
    pub fn read_measurement(&mut self) -> Result<Vl53l1xMeasurement, I2C::Error> {
        self.inner.read_result()?;
        let r = &self.inner.result;
        Ok(Vl53l1xMeasurement {
            distance_mm: word(r, 13),
            range_status: self.inner.range_status,
            signal_rate_mcps: word(r, 15) as f32 / 128.0,
            ambient_rate_mcps: word(r, 7) as f32 / 128.0,
            effective_spad_count: word(r, 3) as f32 / 256.0,
        })
    }

    /// Mapped range status of the most recent measurement: 0 = valid, 1 =
    /// sigma fail, 2 = signal fail, 4 = out of bounds, 7 = wrap-around, 255 =
    /// no update.
    pub fn range_status(&self) -> u8 {
        self.inner.range_status
    }

    /// Set the per-measurement timing budget in µs (ULD table values only):
    /// 15000 (short mode only), 20000, 33000, 50000, 100000, 200000 or 500000.
    pub fn set_timing_budget(&mut self, budget_us: u32) -> Result<(), Vl53l1xError<I2C::Error>> {
        let short = self.distance_mode()? == Vl53l1xDistanceMode::Short;
        if budget_us % 1000 != 0 {
            return Err(Vl53l1xError::InvalidArgument);
        }
        for &(ms, sa, sb, la, lb) in BUDGETS.iter() {
            if ms as u32 * 1000 == budget_us {
                let (a, b) = if short { (sa, sb) } else { (la, lb) };
                if a == 0 {
                    return Err(Vl53l1xError::InvalidArgument);
                }
                self.inner.bus.wr16(REG_RANGE_TIMEOUT_A, a)?;
                self.inner.bus.wr16(REG_RANGE_TIMEOUT_B, b)?;
                return Ok(());
            }
        }
        Err(Vl53l1xError::InvalidArgument)
    }

    /// Timing budget in µs decoded from `RANGE_CONFIG__TIMEOUT_MACROP_A`, or 0
    /// if the register holds no table value.
    pub fn timing_budget(&mut self) -> Result<u32, I2C::Error> {
        let a = self.inner.bus.rd16(REG_RANGE_TIMEOUT_A)?;
        for &(ms, sa, _, la, _) in BUDGETS.iter() {
            if sa == a || (la != 0 && la == a) {
                return Ok(ms as u32 * 1000);
            }
        }
        Ok(0)
    }

    /// Select short or long distance mode, keeping the timing budget (100 ms
    /// if the current budget is unknown). Switching to long at a 15 ms budget
    /// is [`Vl53l1xError::InvalidArgument`].
    pub fn set_distance_mode(&mut self, mode: Vl53l1xDistanceMode) -> Result<(), Vl53l1xError<I2C::Error>> {
        let mut budget = self.timing_budget()?;
        if budget == 0 {
            budget = 100000;
        }
        if mode == Vl53l1xDistanceMode::Long && budget == 15000 {
            return Err(Vl53l1xError::InvalidArgument);
        }
        let (phasecal, vcsel_a, vcsel_b, phase_high, woi, initial_phase) = match mode {
            Vl53l1xDistanceMode::Short => (0x14, 0x07, 0x05, 0x38, 0x0705, 0x0606),
            Vl53l1xDistanceMode::Long => (0x0A, 0x0F, 0x0D, 0xB8, 0x0F0D, 0x0E0E),
        };
        let bus = &mut self.inner.bus;
        bus.wr8(REG_PHASECAL_TIMEOUT, phasecal)?;
        bus.wr8(REG_RANGE_VCSEL_PERIOD_A, vcsel_a)?;
        bus.wr8(REG_RANGE_VCSEL_PERIOD_B, vcsel_b)?;
        bus.wr8(REG_RANGE_VALID_PHASE_HIGH, phase_high)?;
        bus.wr16(REG_SD_WOI_SD0, woi)?;
        bus.wr16(REG_SD_INITIAL_PHASE_SD0, initial_phase)?;
        self.set_timing_budget(budget)
    }

    /// Current distance mode.
    pub fn distance_mode(&mut self) -> Result<Vl53l1xDistanceMode, Vl53l1xError<I2C::Error>> {
        match self.inner.bus.rd8(REG_PHASECAL_TIMEOUT)? {
            0x14 => Ok(Vl53l1xDistanceMode::Short),
            0x0A => Ok(Vl53l1xDistanceMode::Long),
            _ => Err(Vl53l1xError::UnknownDistanceMode),
        }
    }

    /// Set the continuous-mode inter-measurement period in ms (1–60000);
    /// should be ≥ the timing budget ([`Self::start_continuous`] enforces this).
    pub fn set_inter_measurement(&mut self, period_ms: u32) -> Result<(), Vl53l1xError<I2C::Error>> {
        if !(1..=60000).contains(&period_ms) {
            return Err(Vl53l1xError::InvalidArgument);
        }
        let clock_pll = (self.inner.bus.rd16(REG_OSC_CALIBRATE_VAL)? & 0x03FF) as u64;
        let value = (clock_pll * period_ms as u64 * 1075) / 1000;
        self.inner.bus.wr32(REG_INTERMEASUREMENT_PERIOD, value as u32)?;
        Ok(())
    }

    /// Continuous-mode inter-measurement period in ms (0 if the oscillator
    /// calibration reads 0).
    pub fn inter_measurement(&mut self) -> Result<u32, I2C::Error> {
        let clock_pll = (self.inner.bus.rd16(REG_OSC_CALIBRATE_VAL)? & 0x03FF) as u64;
        if clock_pll == 0 {
            return Ok(0);
        }
        let raw = self.inner.bus.rd32(REG_INTERMEASUREMENT_PERIOD)? as u64;
        Ok(((raw * 1000) / (clock_pll * 1075)) as u32)
    }

    /// Set the minimum return signal rate for a valid result, MCPS 0–511.99
    /// (default 1.0).
    pub fn set_signal_rate_limit(&mut self, limit_mcps: f32) -> Result<(), Vl53l1xError<I2C::Error>> {
        if !(0.0..=511.99).contains(&limit_mcps) {
            return Err(Vl53l1xError::InvalidArgument);
        }
        self.inner.bus.wr16(REG_MIN_COUNT_RATE_RTN_LIMIT, (limit_mcps * 128.0 + 0.5) as u16)?;
        Ok(())
    }

    /// Minimum return signal rate in MCPS.
    pub fn signal_rate_limit(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.bus.rd16(REG_MIN_COUNT_RATE_RTN_LIMIT)? as f32 / 128.0)
    }

    /// Set the maximum estimated standard deviation for a valid result, mm
    /// 0–16383 (default 90).
    pub fn set_sigma_threshold(&mut self, sigma_mm: u16) -> Result<(), Vl53l1xError<I2C::Error>> {
        if sigma_mm > 16383 {
            return Err(Vl53l1xError::InvalidArgument);
        }
        self.inner.bus.wr16(REG_SIGMA_THRESH, sigma_mm << 2)?;
        Ok(())
    }

    /// Sigma threshold in mm.
    pub fn sigma_threshold(&mut self) -> Result<u16, I2C::Error> {
        Ok(self.inner.bus.rd16(REG_SIGMA_THRESH)? >> 2)
    }

    /// Set the receiving region-of-interest size in SPADs (4–16 each); sizes
    /// above 10 re-centre the ROI on SPAD 199 (array centre).
    pub fn set_roi(&mut self, width: u8, height: u8) -> Result<(), Vl53l1xError<I2C::Error>> {
        if !(4..=16).contains(&width) || !(4..=16).contains(&height) {
            return Err(Vl53l1xError::InvalidArgument);
        }
        if width > 10 || height > 10 {
            self.inner.bus.wr8(REG_ROI_CENTRE_SPAD, 199)?;
        }
        self.inner.bus.wr8(REG_ROI_XY_SIZE, ((height - 1) << 4) | (width - 1))?;
        Ok(())
    }

    /// Region-of-interest size `(width, height)` in SPADs.
    pub fn roi(&mut self) -> Result<(u8, u8), I2C::Error> {
        let value = self.inner.bus.rd8(REG_ROI_XY_SIZE)?;
        Ok(((value & 0x0F) + 1, (value >> 4) + 1))
    }

    /// Move the region of interest to a centre SPAD (ST UM2555 numbering, 199
    /// = array centre). The caller keeps the ROI inside the array.
    pub fn set_roi_center(&mut self, spad: u8) -> Result<(), I2C::Error> {
        self.inner.bus.wr8(REG_ROI_CENTRE_SPAD, spad)
    }

    /// Region-of-interest centre SPAD.
    pub fn roi_center(&mut self) -> Result<u8, I2C::Error> {
        self.inner.bus.rd8(REG_ROI_CENTRE_SPAD)
    }

    /// Factory-measured optical-centre SPAD from NVM; pass to
    /// [`Self::set_roi_center`] to align the ROI with this part's lens.
    pub fn optical_center(&mut self) -> Result<u8, I2C::Error> {
        self.inner.bus.rd8(REG_MODE_ROI_CENTRE_SPAD)
    }

    /// Override the part-to-part range offset in mm (volatile), −1024.0 to
    /// 1023.75 in 0.25 mm steps.
    pub fn set_offset(&mut self, offset_mm: f32) -> Result<(), Vl53l1xError<I2C::Error>> {
        if !(-1024.0..=1023.75).contains(&offset_mm) {
            return Err(Vl53l1xError::InvalidArgument);
        }
        let bus = &mut self.inner.bus;
        bus.wr16(REG_PART_TO_PART_OFFSET, (round_half_away(offset_mm * 4.0) & 0x1FFF) as u16)?;
        bus.wr16(REG_MM_INNER_OFFSET, 0)?;
        bus.wr16(REG_MM_OUTER_OFFSET, 0)?;
        Ok(())
    }

    /// Part-to-part range offset in mm.
    pub fn offset(&mut self) -> Result<f32, I2C::Error> {
        let mut raw = (self.inner.bus.rd16(REG_PART_TO_PART_OFFSET)? & 0x1FFF) as i16;
        if raw & 0x1000 != 0 {
            raw -= 0x2000;
        }
        Ok(raw as f32 * 0.25)
    }

    /// Set the per-SPAD crosstalk compensation rate in MCPS (volatile); 0
    /// disables, otherwise 0 < rate < 0.128.
    pub fn set_crosstalk_compensation(&mut self, rate_mcps: f32) -> Result<(), Vl53l1xError<I2C::Error>> {
        if !(0.0..0.128).contains(&rate_mcps) {
            return Err(Vl53l1xError::InvalidArgument);
        }
        let raw = ((rate_mcps * 512000.0 + 0.5) as u32).min(0xFFFF) as u16;
        let bus = &mut self.inner.bus;
        bus.wr16(REG_XTALK_X_GRADIENT, 0)?;
        bus.wr16(REG_XTALK_Y_GRADIENT, 0)?;
        bus.wr16(REG_XTALK_PLANE_OFFSET, raw)?;
        Ok(())
    }

    /// Per-SPAD crosstalk compensation rate in MCPS.
    pub fn crosstalk_compensation(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.bus.rd16(REG_XTALK_PLANE_OFFSET)? as f32 / 512000.0)
    }

    /// Range 50 timed-mode samples, calling `acc` with each result block;
    /// always stops ranging.
    fn collect<F: FnMut(&[u8; 17])>(&mut self, mut acc: F) -> Result<(), Vl53l1xError<I2C::Error>> {
        self.inner.bus.wr8(REG_INTERRUPT_CLEAR, 0x01)?;
        self.inner.bus.wr8(REG_MODE_START, 0x40)?;
        let mut outcome = Ok(());
        for _ in 0..CALIBRATION_SAMPLES {
            if let Err(e) = self.inner.wait_data_ready() {
                outcome = Err(e);
                break;
            }
            if let Err(e) = self.inner.read_result() {
                outcome = Err(Vl53l1xError::Bus(e));
                break;
            }
            acc(&self.inner.result);
        }
        self.inner.bus.wr8(REG_MODE_START, 0x00)?;
        outcome
    }

    /// Measure and apply the range offset against a target at a known
    /// distance (ULD CalibrateOffset; ST recommends 88 % white at 140 mm).
    /// Ranges 50 times with the offset zeroed; must not be called while
    /// ranging. Returns the applied offset in mm (target − mean distance);
    /// store it and re-apply it with [`Self::set_offset`] after each power-up.
    pub fn calibrate_offset(&mut self, target_mm: u16) -> Result<f32, Vl53l1xError<I2C::Error>> {
        let bus = &mut self.inner.bus;
        bus.wr16(REG_PART_TO_PART_OFFSET, 0)?;
        bus.wr16(REG_MM_INNER_OFFSET, 0)?;
        bus.wr16(REG_MM_OUTER_OFFSET, 0)?;
        let mut sum = 0u32;
        self.collect(|r| sum += word(r, 13) as u32)?;
        let offset = target_mm as f32 - sum as f32 / CALIBRATION_SAMPLES as f32;
        self.set_offset(offset)?;
        Ok(offset)
    }

    /// Measure and apply crosstalk compensation for a cover glass (ULD
    /// CalibrateXtalk; ST uses a 17 % grey target where the sensor starts to
    /// under-range). Ranges 50 times with compensation off; must not be called
    /// while ranging. Returns the applied per-SPAD rate in MCPS (0–0.127);
    /// store it and re-apply it with [`Self::set_crosstalk_compensation`].
    pub fn calibrate_crosstalk(&mut self, target_mm: u16) -> Result<f32, Vl53l1xError<I2C::Error>> {
        if target_mm == 0 {
            return Err(Vl53l1xError::InvalidArgument);
        }
        self.inner.bus.wr16(REG_XTALK_PLANE_OFFSET, 0)?;
        let (mut distance, mut signal, mut spads) = (0u32, 0f32, 0f32);
        self.collect(|r| {
            distance += word(r, 13) as u32;
            signal += word(r, 15) as f32 / 128.0;
            spads += word(r, 3) as f32 / 256.0;
        })?;
        let n = CALIBRATION_SAMPLES as f32;
        let (mean_distance, mean_signal, mean_spads) = (distance as f32 / n, signal / n, spads / n);
        let mut rate = if mean_spads > 0.0 {
            mean_signal * (1.0 - mean_distance / target_mm as f32) / mean_spads
        } else {
            0.0
        };
        rate = rate.clamp(0.0, 0.127);
        self.set_crosstalk_compensation(rate)?;
        Ok(rate)
    }

    /// Run the temperature update (ULD StartTemperatureUpdate). Call in
    /// software standby (not while ranging), after the temperature changes by
    /// more than about 8 °C.
    pub fn recalibrate(&mut self) -> Result<(), Vl53l1xError<I2C::Error>> {
        self.inner.bus.wr8(REG_VHV_CONFIG_LOOP_BOUND, 0x81)?;
        self.inner.bus.wr8(REG_VHV_INIT, 0x92)?;
        self.inner.bus.wr8(REG_MODE_START, 0x40)?;
        let ready = self.inner.wait_data_ready();
        let bus = &mut self.inner.bus;
        bus.wr8(REG_INTERRUPT_CLEAR, 0x01)?;
        bus.wr8(REG_MODE_START, 0x00)?;
        bus.wr8(REG_VHV_CONFIG_LOOP_BOUND, 0x09)?;
        bus.wr8(REG_VHV_INIT, 0x00)?;
        ready
    }

    /// Change the chip's I²C address (volatile), `0x08`–`0x77`. The chip
    /// answers on the new address immediately; [`Self::release`] the bus and
    /// construct a new driver at the new address.
    pub fn set_address(&mut self, address: u8) -> Result<(), Vl53l1xError<I2C::Error>> {
        if self.inner.bus.set_address_reg(REG_I2C_SLAVE_DEVICE_ADDRESS, address)? {
            Ok(())
        } else {
            Err(Vl53l1xError::InvalidArgument)
        }
    }

    /// Set the distance thresholds in mm used by the threshold interrupt
    /// sources (`low_mm` ≤ `high_mm`).
    pub fn set_interrupt_thresholds(&mut self, low_mm: u16, high_mm: u16) -> Result<(), Vl53l1xError<I2C::Error>> {
        if high_mm < low_mm {
            return Err(Vl53l1xError::InvalidArgument);
        }
        self.inner.bus.wr16(REG_THRESH_HIGH, high_mm)?;
        self.inner.bus.wr16(REG_THRESH_LOW, low_mm)?;
        Ok(())
    }

    /// Distance thresholds `(low_mm, high_mm)`.
    pub fn interrupt_thresholds(&mut self) -> Result<(u16, u16), I2C::Error> {
        Ok((self.inner.bus.rd16(REG_THRESH_LOW)?, self.inner.bus.rd16(REG_THRESH_HIGH)?))
    }

    /// `IDENTIFICATION__MODEL_ID` — `0xEA`.
    pub fn model_id(&mut self) -> Result<u8, I2C::Error> {
        self.inner.bus.rd8(REG_MODEL_ID)
    }

    /// `IDENTIFICATION__MODULE_TYPE` — `0xCC`.
    pub fn module_type(&mut self) -> Result<u8, I2C::Error> {
        self.inner.bus.rd8(REG_MODULE_TYPE)
    }

    /// `IDENTIFICATION__REVISION_ID` (mask revision) — `0x10`.
    pub fn revision_id(&mut self) -> Result<u8, I2C::Error> {
        self.inner.bus.rd8(REG_REVISION_ID)
    }

    /// Select the `GPIO1` interrupt source (one of the `VL53L1X_SOURCE_*`
    /// constants, 1–5), replacing the active one. With a threshold source
    /// active, [`Self::data_ready`]/[`Self::read_continuous`] only see a
    /// pending result when the threshold condition is met.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Vl53l1xError<I2C::Error>> {
        let config = match source {
            VL53L1X_SOURCE_LEVEL_LOW => 0x00,
            VL53L1X_SOURCE_LEVEL_HIGH => 0x01,
            VL53L1X_SOURCE_OUT_OF_WINDOW => 0x02,
            VL53L1X_SOURCE_NEW_SAMPLE_READY => 0x20,
            VL53L1X_SOURCE_IN_WINDOW => 0x03,
            _ => return Err(Vl53l1xError::InvalidArgument),
        };
        self.inner.bus.wr8(REG_INTERRUPT_CONFIG_GPIO, config)?;
        Ok(())
    }

    /// Revert to `VL53L1X_SOURCE_NEW_SAMPLE_READY` if `source` is the active
    /// threshold source. The chip has no disabled state, so disabling
    /// `VL53L1X_SOURCE_NEW_SAMPLE_READY` is a no-op.
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        if source != VL53L1X_SOURCE_NEW_SAMPLE_READY && self.inner.active_source()? == source {
            self.inner.bus.wr8(REG_INTERRUPT_CONFIG_GPIO, 0x20)?;
        }
        Ok(())
    }

    /// Read and clear a pending interrupt: the active `VL53L1X_SOURCE_*` value
    /// if `GPIO1` is asserted, else 0.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        if !self.inner.data_ready()? {
            return Ok(0);
        }
        self.inner.bus.wr8(REG_INTERRUPT_CLEAR, 0x01)?;
        self.inner.active_source()
    }
}

#[cfg(test)]
mod tests {
    extern crate std;

    use super::*;
    use core::cell::RefCell;
    use embedded_hal::i2c::{ErrorType, Operation};
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use std::collections::HashMap;
    use std::rc::Rc;
    use std::vec;
    use std::vec::Vec;

    /// VL53L1X simulator with explicit 16-bit register indices.
    /// `GPIO__TIO_HV_STATUS` (0x0031) is computed: bit 0 is 0 (active-low line
    /// asserted) while a result is pending. Starting a single-shot or timed
    /// ranging makes a result pending; the interrupt clear drops it unless
    /// timed ranging is running.
    #[derive(Default)]
    struct State {
        regs: HashMap<u16, u8>,
        log: Vec<Vec<u8>>,
        pending: bool,
        ranging: bool,
    }

    impl State {
        fn reg(&self, r: u16) -> u8 {
            *self.regs.get(&r).unwrap_or(&0)
        }
        fn reg16(&self, r: u16) -> u16 {
            ((self.reg(r) as u16) << 8) | self.reg(r + 1) as u16
        }
        fn set(&mut self, r: u16, values: &[u8]) {
            for (i, v) in values.iter().enumerate() {
                self.regs.insert(r + i as u16, *v);
            }
        }
        fn writes_to(&self, r: u16) -> Vec<u8> {
            self.log.iter().filter(|w| w.len() == 3 && (((w[0] as u16) << 8) | w[1] as u16) == r).map(|w| w[2]).collect()
        }
        fn write(&mut self, data: &[u8]) {
            self.log.push(data.to_vec());
            let reg = ((data[0] as u16) << 8) | data[1] as u16;
            for (i, v) in data[2..].iter().enumerate() {
                self.regs.insert(reg + i as u16, *v);
            }
            if reg == 0x0087 && data.len() == 3 {
                if data[2] == 0x10 || data[2] == 0x40 {
                    self.pending = true;
                    self.ranging = data[2] == 0x40;
                } else {
                    self.ranging = false;
                }
            } else if reg == 0x0086 && data[2] == 0x01 {
                self.pending = self.ranging;
            }
        }
        fn read(&self, reg: u16, buf: &mut [u8]) {
            for (i, b) in buf.iter_mut().enumerate() {
                let r = reg + i as u16;
                *b = if r == 0x0031 { if self.pending { 0x00 } else { 0x01 } } else { self.reg(r) };
            }
        }
    }

    #[derive(Clone)]
    struct Sim(Rc<RefCell<State>>);

    impl Sim {
        fn new() -> Self {
            let mut s = State::default();
            s.set(0x00E5, &[0x01]);
            s.set(0x010F, &[0xEA, 0xCC, 0x10]);
            s.set(0x013E, &[0x91]);
            s.set(0x00DE, &[0x00, 0x50]);
            // Result block: raw status 9 (valid), 10.0 SPADs, 0.5 MCPS ambient, 250 mm, 5.0 MCPS signal.
            s.set(0x0089, &[9, 0, 0, 0x0A, 0x00, 0, 0, 0x00, 0x40, 0, 0, 0, 0, 0x00, 0xFA, 0x02, 0x80]);
            Sim(Rc::new(RefCell::new(s)))
        }
        fn st(&self) -> std::cell::Ref<'_, State> {
            self.0.borrow()
        }
        fn stm(&self) -> std::cell::RefMut<'_, State> {
            self.0.borrow_mut()
        }
    }

    impl ErrorType for Sim {
        type Error = core::convert::Infallible;
    }

    impl I2c for Sim {
        fn transaction(&mut self, _addr: u8, ops: &mut [Operation<'_>]) -> Result<(), Self::Error> {
            let mut s = self.0.borrow_mut();
            let mut last_reg = 0u16;
            for op in ops.iter_mut() {
                match op {
                    Operation::Write(data) => {
                        last_reg = ((data[0] as u16) << 8) | data[1] as u16;
                        if data.len() >= 3 {
                            s.write(data);
                        }
                    }
                    Operation::Read(buf) => s.read(last_reg, buf),
                }
            }
            Ok(())
        }
    }

    const ADDR: u8 = 0x29;

    fn full() -> (Sim, Vl53l1xFull<Sim, NoopDelay>) {
        let sim = Sim::new();
        let f = Vl53l1xFull::new(sim.clone(), ADDR, NoopDelay::new()).expect("init");
        (sim, f)
    }

    #[test]
    fn rejects_wrong_sensor_id_and_boot_timeout() {
        let sim = Sim::new();
        sim.stm().set(0x0110, &[0xCD]);
        assert!(matches!(Vl53l1xMinimal::new(sim, ADDR, NoopDelay::new()), Err(Vl53l1xError::NotFound)));
        let sim = Sim::new();
        sim.stm().set(0x00E5, &[0x00]);
        assert!(matches!(Vl53l1xMinimal::new(sim, ADDR, NoopDelay::new()), Err(Vl53l1xError::Timeout)));
    }

    #[test]
    fn init_sequence() {
        let sim = Sim::new();
        let _sensor = Vl53l1xMinimal::new(sim.clone(), ADDR, NoopDelay::new()).expect("init");
        let s = sim.st();
        assert_eq!(&s.log[0][..2], &[0x00, 0x2D]);
        for i in 0..91 {
            let w = &s.log[i];
            assert_eq!(w.len(), 3);
            assert_eq!(((w[0] as u16) << 8) | w[1] as u16, 0x2D + i as u16);
        }
        assert_eq!(s.writes_to(0x0046)[0], 0x20);
        assert_eq!(s.writes_to(0x0081)[0], 0x9B);
        assert_eq!((s.reg(0x002E), s.reg(0x002F), s.reg(0x0030)), (0x01, 0x01, 0x11));
        let starts = s.writes_to(0x0087);
        assert_eq!(&starts[starts.len() - 2..], &[0x40, 0x00]);
        assert_eq!((s.reg(0x0008), s.reg(0x000B)), (0x09, 0x00));
        assert!(!s.ranging);
    }

    #[test]
    fn single_shot() {
        let sim = Sim::new();
        let mut sensor = Vl53l1xMinimal::new(sim.clone(), ADDR, NoopDelay::new()).unwrap();
        sim.stm().log.clear();
        assert_eq!(sensor.distance().unwrap(), 250);
        assert!(sensor.range_valid());
        {
            let s = sim.st();
            assert_eq!(s.log[0], vec![0x00, 0x86, 0x01]);
            assert_eq!(s.log[1], vec![0x00, 0x87, 0x10]);
            assert_eq!(s.log.last().unwrap(), &vec![0x00, 0x86, 0x01]);
        }
        sim.stm().set(0x0089, &[4]);
        assert_eq!(sensor.distance().unwrap(), 250);
        assert!(!sensor.range_valid());
    }

    #[test]
    fn measurement_budget_mode() {
        let (sim, mut f) = full();
        f.distance().unwrap();
        let m = f.read_measurement().unwrap();
        assert_eq!(m.distance_mm, 250);
        assert_eq!(m.range_status, 0);
        assert_eq!(m.signal_rate_mcps, 5.0);
        assert_eq!(m.ambient_rate_mcps, 0.5);
        assert_eq!(m.effective_spad_count, 10.0);
        sim.stm().set(0x0089, &[0x1F]);
        assert_eq!(f.read_measurement().unwrap().range_status, 255);
        sim.stm().set(0x0089, &[9]);

        assert_eq!(f.timing_budget().unwrap(), 100000);
        assert_eq!(f.distance_mode().unwrap(), Vl53l1xDistanceMode::Long);
        f.set_timing_budget(33000).unwrap();
        assert_eq!((sim.st().reg16(0x005E), sim.st().reg16(0x0061)), (0x0060, 0x006E));
        assert_eq!(f.timing_budget().unwrap(), 33000);
        assert!(matches!(f.set_timing_budget(15000), Err(Vl53l1xError::InvalidArgument)));
        assert!(matches!(f.set_timing_budget(40000), Err(Vl53l1xError::InvalidArgument)));
        f.set_distance_mode(Vl53l1xDistanceMode::Short).unwrap();
        {
            let s = sim.st();
            assert_eq!((s.reg(0x004B), s.reg(0x0060), s.reg(0x0063), s.reg(0x0069)), (0x14, 0x07, 0x05, 0x38));
            assert_eq!((s.reg16(0x0078), s.reg16(0x007A), s.reg16(0x005E)), (0x0705, 0x0606, 0x00D6));
        }
        f.set_timing_budget(15000).unwrap();
        assert_eq!((sim.st().reg16(0x005E), sim.st().reg16(0x0061)), (0x001D, 0x0027));
        assert!(matches!(f.set_distance_mode(Vl53l1xDistanceMode::Long), Err(Vl53l1xError::InvalidArgument)));
        f.set_timing_budget(100000).unwrap();
        f.set_distance_mode(Vl53l1xDistanceMode::Long).unwrap();
        {
            let s = sim.st();
            assert_eq!((s.reg(0x004B), s.reg16(0x0078), s.reg16(0x005E), s.reg16(0x0061)), (0x0A, 0x0F0D, 0x01CC, 0x01EA));
        }
        sim.stm().set(0x004B, &[0x33]);
        assert!(matches!(f.distance_mode(), Err(Vl53l1xError::UnknownDistanceMode)));
    }

    #[test]
    fn continuous_and_thresholds() {
        let (sim, mut f) = full();
        f.set_inter_measurement(200).unwrap();
        let raw = ((sim.st().reg16(0x006C) as u32) << 16) | sim.st().reg16(0x006E) as u32;
        assert_eq!(raw, (0x50 * 200 * 1075) / 1000);
        assert_eq!(f.inter_measurement().unwrap(), 200);
        assert!(matches!(f.set_inter_measurement(0), Err(Vl53l1xError::InvalidArgument)));
        sim.stm().log.clear();
        f.start_continuous(0).unwrap();
        assert_eq!(f.inter_measurement().unwrap(), 100);
        assert_eq!(sim.st().log.last().unwrap(), &vec![0x00, 0x87, 0x40]);
        assert!(f.data_ready().unwrap());
        assert_eq!(f.read_continuous().unwrap(), 250);
        assert!(f.data_ready().unwrap());
        f.stop_continuous().unwrap();
        assert_eq!(sim.st().log.last().unwrap(), &vec![0x00, 0x87, 0x00]);
        f.start_continuous(50).unwrap();
        assert_eq!(f.inter_measurement().unwrap(), 100);
        f.stop_continuous().unwrap();
        f.start_continuous(500).unwrap();
        assert_eq!(f.inter_measurement().unwrap(), 500);
        f.stop_continuous().unwrap();
        assert!(matches!(f.start_continuous(60001), Err(Vl53l1xError::InvalidArgument)));

        f.set_interrupt_thresholds(100, 801).unwrap();
        assert_eq!((sim.st().reg16(0x0074), sim.st().reg16(0x0072)), (100, 801));
        assert_eq!(f.interrupt_thresholds().unwrap(), (100, 801));
        assert!(matches!(f.set_interrupt_thresholds(500, 100), Err(Vl53l1xError::InvalidArgument)));
    }

    #[test]
    fn signal_sigma_roi_offset_crosstalk() {
        let (sim, mut f) = full();
        assert_eq!(f.signal_rate_limit().unwrap(), 1.0);
        f.set_signal_rate_limit(0.25).unwrap();
        assert_eq!(sim.st().reg16(0x0066), 32);
        assert!(matches!(f.set_signal_rate_limit(-1.0), Err(Vl53l1xError::InvalidArgument)));
        assert_eq!(f.sigma_threshold().unwrap(), 90);
        f.set_sigma_threshold(45).unwrap();
        assert_eq!(sim.st().reg16(0x0064), 180);
        assert!(matches!(f.set_sigma_threshold(16384), Err(Vl53l1xError::InvalidArgument)));

        assert_eq!(f.roi().unwrap(), (16, 16));
        assert_eq!(f.roi_center().unwrap(), 199);
        f.set_roi_center(167).unwrap();
        f.set_roi(8, 8).unwrap();
        assert_eq!(sim.st().reg(0x0080), 0x77);
        assert_eq!(f.roi_center().unwrap(), 167);
        f.set_roi(8, 16).unwrap();
        assert_eq!(f.roi().unwrap(), (8, 16));
        assert_eq!(f.roi_center().unwrap(), 199);
        assert!(matches!(f.set_roi(3, 8), Err(Vl53l1xError::InvalidArgument)));
        assert_eq!(f.optical_center().unwrap(), 0x91);

        f.set_offset(-10.25).unwrap();
        assert_eq!(sim.st().reg16(0x001E), ((-41i32) & 0x1FFF) as u16);
        assert_eq!(f.offset().unwrap(), -10.25);
        f.set_offset(700.5).unwrap();
        assert_eq!(f.offset().unwrap(), 700.5);
        assert!(matches!(f.set_offset(1024.0), Err(Vl53l1xError::InvalidArgument)));
        f.set_crosstalk_compensation(0.01).unwrap();
        assert_eq!(sim.st().reg16(0x0016), 5120);
        assert!(matches!(f.set_crosstalk_compensation(0.128), Err(Vl53l1xError::InvalidArgument)));
    }

    #[test]
    fn calibration_and_recalibrate() {
        let (sim, mut f) = full();
        assert_eq!(f.calibrate_offset(260).unwrap(), 10.0);
        assert_eq!(f.offset().unwrap(), 10.0);
        assert!(!sim.st().ranging);
        assert_eq!(f.calibrate_crosstalk(500).unwrap(), 0.127);
        sim.stm().set(0x0098, &[0x00, 0x20]);
        let xt = f.calibrate_crosstalk(500).unwrap();
        assert!((xt - 0.0125).abs() < 1e-6);
        assert_eq!(sim.st().reg16(0x0016), 6400);
        assert!(matches!(f.calibrate_crosstalk(0), Err(Vl53l1xError::InvalidArgument)));

        sim.stm().log.clear();
        f.recalibrate().unwrap();
        let s = sim.st();
        assert_eq!(s.writes_to(0x0008), vec![0x81, 0x09]);
        assert_eq!(s.writes_to(0x000B), vec![0x92, 0x00]);
        assert_eq!(s.writes_to(0x0087), vec![0x40, 0x00]);
    }

    #[test]
    fn address_identification_interrupts() {
        let (sim, mut f) = full();
        f.set_address(0x30).unwrap();
        assert_eq!(sim.st().reg(0x0001), 0x30);
        assert!(matches!(f.set_address(0x78), Err(Vl53l1xError::InvalidArgument)));
        assert_eq!(f.model_id().unwrap(), 0xEA);
        assert_eq!(f.module_type().unwrap(), 0xCC);
        assert_eq!(f.revision_id().unwrap(), 0x10);

        f.enable_interrupt(VL53L1X_SOURCE_OUT_OF_WINDOW).unwrap();
        assert_eq!(sim.st().reg(0x0046), 0x02);
        f.enable_interrupt(VL53L1X_SOURCE_IN_WINDOW).unwrap();
        assert_eq!(sim.st().reg(0x0046), 0x03);
        f.disable_interrupt(VL53L1X_SOURCE_LEVEL_LOW).unwrap();
        assert_eq!(sim.st().reg(0x0046), 0x03);
        f.disable_interrupt(VL53L1X_SOURCE_IN_WINDOW).unwrap();
        assert_eq!(sim.st().reg(0x0046), 0x20);
        f.disable_interrupt(VL53L1X_SOURCE_NEW_SAMPLE_READY).unwrap();
        assert_eq!(sim.st().reg(0x0046), 0x20);
        sim.stm().pending = false;
        assert_eq!(f.poll_interrupt().unwrap(), 0);
        f.enable_interrupt(VL53L1X_SOURCE_OUT_OF_WINDOW).unwrap();
        sim.stm().pending = true;
        assert_eq!(f.poll_interrupt().unwrap(), VL53L1X_SOURCE_OUT_OF_WINDOW);
        assert!(!sim.st().pending);
        assert!(matches!(f.enable_interrupt(6), Err(Vl53l1xError::InvalidArgument)));
    }

    #[test]
    fn timeout_when_data_never_ready() {
        let (sim, mut f) = full();
        // Swallow the start command so no result ever becomes pending.
        f.stop_continuous().unwrap();
        sim.stm().pending = false;
        assert!(!f.data_ready().unwrap());
        assert!(matches!(f.read_continuous(), Err(Vl53l1xError::Timeout)));
    }
}
