//! VL53L0X — Time-of-Flight laser-ranging sensor (STMicroelectronics).
//!
//! 940 nm VCSEL emitter, SPAD receiving array and an embedded ranging
//! microcontroller measuring absolute distance up to ~2 m, largely
//! independent of target reflectance. The datasheet has no register map:
//! registers, the tuning table and the init/calibration sequences follow ST's
//! STSW-IMG005 API (the same derivation as Pololu's VL53L0X library) — see
//! `specs/tof/vl53l0x.md`. Multi-byte registers are big-endian.
//!
//! The driver takes the raw `embedded-hal` I²C bus plus a [`DelayNs`] used for
//! the 1.2 ms boot wait and for the bounded poll loops (500 × 1 ms; expiry is
//! [`Vl53l0xError::Timeout`]). Drive `XSHUT` high before constructing it.
//!
//! ## Multiple sensors on one bus
//!
//! All sensors power up at `0x29`. Hold every sensor's `XSHUT` low, then for
//! each sensor in turn release its `XSHUT`, construct a driver on `0x29`, call
//! [`Vl53l0xFull::set_address`], [`Vl53l0xFull::release`] the bus and build the
//! real driver at the new address. The new address is volatile — it reverts
//! to `0x29` on power-up or an `XSHUT` low pulse.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Vl53l0xFull::poll_interrupt`] (no callback
//! subscription — polling is always caller-managed in this crate's `no_std`
//! Rust drivers). The caller is responsible for wiring it into an ISR on the
//! `GPIO1` pin (active low, open drain) or a polling loop.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const REG_SYSRANGE_START: u8 = 0x00;
const REG_SYSTEM_SEQUENCE_CONFIG: u8 = 0x01;
const REG_SYSTEM_INTERMEASUREMENT: u8 = 0x04;
const REG_SYSTEM_INTERRUPT_CONFIG: u8 = 0x0A;
const REG_SYSTEM_INTERRUPT_CLEAR: u8 = 0x0B;
const REG_SYSTEM_THRESH_HIGH: u8 = 0x0C;
const REG_SYSTEM_THRESH_LOW: u8 = 0x0E;
const REG_RESULT_INTERRUPT_STATUS: u8 = 0x13;
const REG_RESULT_RANGE_STATUS: u8 = 0x14;
const REG_CROSSTALK_COMPENSATION: u8 = 0x20;
const REG_PART_TO_PART_RANGE_OFFSET: u8 = 0x28;
const REG_PHASECAL_CONFIG_TIMEOUT: u8 = 0x30;
const REG_GLOBAL_CONFIG_VCSEL_WIDTH: u8 = 0x32;
const REG_FINAL_MIN_COUNT_RATE_RTN: u8 = 0x44;
const REG_MSRC_CONFIG_TIMEOUT: u8 = 0x46;
const REG_FINAL_VALID_PHASE_LOW: u8 = 0x47;
const REG_FINAL_VALID_PHASE_HIGH: u8 = 0x48;
const REG_DYNAMIC_SPAD_NUM_REQ: u8 = 0x4E;
const REG_DYNAMIC_SPAD_START_OFFSET: u8 = 0x4F;
const REG_PRE_RANGE_VCSEL_PERIOD: u8 = 0x50;
const REG_PRE_RANGE_TIMEOUT: u8 = 0x51;
const REG_PRE_VALID_PHASE_LOW: u8 = 0x56;
const REG_PRE_VALID_PHASE_HIGH: u8 = 0x57;
const REG_MSRC_CONFIG_CONTROL: u8 = 0x60;
const REG_FINAL_RANGE_VCSEL_PERIOD: u8 = 0x70;
const REG_FINAL_RANGE_TIMEOUT: u8 = 0x71;
const REG_POWER_FORCE: u8 = 0x80;
const REG_GPIO_HV_MUX_ACTIVE_HIGH: u8 = 0x84;
const REG_I2C_MODE: u8 = 0x88;
const REG_VHV_PAD_EXTSUP_HV: u8 = 0x89;
const REG_I2C_SLAVE_DEVICE_ADDRESS: u8 = 0x8A;
const REG_STOP_VARIABLE: u8 = 0x91;
const REG_SPAD_ENABLES_REF_0: u8 = 0xB0;
const REG_REF_EN_START_SELECT: u8 = 0xB6;
const REG_MODEL_ID: u8 = 0xC0;
const REG_REVISION_ID: u8 = 0xC2;
const REG_OSC_CALIBRATE_VAL: u8 = 0xF8;
const REG_PAGE_SELECT: u8 = 0xFF;

const SEQ_TCC: u8 = 0x10;
const SEQ_DSS: u8 = 0x08;
const SEQ_MSRC: u8 = 0x04;
const SEQ_PRE_RANGE: u8 = 0x40;
const SEQ_FINAL_RANGE: u8 = 0x80;
const SEQ_OPERATING: u8 = 0xE8;

/// Poll loops: 500 attempts with a 1 ms delay between them (~500 ms).
const POLL_ATTEMPTS: u32 = 500;
const MIN_TIMING_BUDGET_US: u32 = 20000;

// Timing-budget overheads, µs.
const START_OVERHEAD: u32 = 1910;
const END_OVERHEAD: u32 = 960;
const MSRC_OVERHEAD: u32 = 660;
const TCC_OVERHEAD: u32 = 590;
const DSS_OVERHEAD: u32 = 690;
const PRE_RANGE_OVERHEAD: u32 = 660;
const FINAL_RANGE_OVERHEAD: u32 = 550;

/// ST `DefaultTuningSettings` — opaque, written verbatim in this order as
/// (reg, value) pairs.
const TUNING: [u8; 160] = [
    0xFF, 0x01, 0x00, 0x00, 0xFF, 0x00, 0x09, 0x00, 0x10, 0x00, 0x11, 0x00, 0x24, 0x01, 0x25, 0xFF, 0x75, 0x00,
    0xFF, 0x01, 0x4E, 0x2C, 0x48, 0x00, 0x30, 0x20, 0xFF, 0x00, 0x30, 0x09, 0x54, 0x00, 0x31, 0x04, 0x32, 0x03,
    0x40, 0x83, 0x46, 0x25, 0x60, 0x00, 0x27, 0x00, 0x50, 0x06, 0x51, 0x00, 0x52, 0x96, 0x56, 0x08, 0x57, 0x30,
    0x61, 0x00, 0x62, 0x00, 0x64, 0x00, 0x65, 0x00, 0x66, 0xA0, 0xFF, 0x01, 0x22, 0x32, 0x47, 0x14, 0x49, 0xFF,
    0x4A, 0x00, 0xFF, 0x00, 0x7A, 0x0A, 0x7B, 0x00, 0x78, 0x21, 0xFF, 0x01, 0x23, 0x34, 0x42, 0x00, 0x44, 0xFF,
    0x45, 0x26, 0x46, 0x05, 0x40, 0x40, 0x0E, 0x06, 0x20, 0x1A, 0x43, 0x40, 0xFF, 0x00, 0x34, 0x03, 0x35, 0x44,
    0xFF, 0x01, 0x31, 0x04, 0x4B, 0x09, 0x4C, 0x05, 0x4D, 0x04, 0xFF, 0x00, 0x44, 0x00, 0x45, 0x20, 0x47, 0x08,
    0x48, 0x28, 0x67, 0x00, 0x70, 0x04, 0x71, 0x01, 0x72, 0xFE, 0x76, 0x00, 0x77, 0x00, 0xFF, 0x01, 0x0D, 0x01,
    0xFF, 0x00, 0x80, 0x01, 0x01, 0xF8, 0xFF, 0x01, 0x8E, 0x01, 0x00, 0x01, 0xFF, 0x00, 0x80, 0x00,
];

/// Default 7-bit I²C address.
pub const VL53L0X_I2C_ADDRESS: u8 = 0x29;
/// Expected `IDENTIFICATION_MODEL_ID`.
pub const VL53L0X_MODEL_ID: u8 = 0xEE;
/// Device range status meaning "range complete — valid".
pub const VL53L0X_RANGE_STATUS_VALID: u8 = 11;

/// Interrupt source: range < low threshold.
pub const VL53L0X_SOURCE_LEVEL_LOW: u8 = 0x01;
/// Interrupt source: range > high threshold.
pub const VL53L0X_SOURCE_LEVEL_HIGH: u8 = 0x02;
/// Interrupt source: range < low threshold or > high threshold.
pub const VL53L0X_SOURCE_OUT_OF_WINDOW: u8 = 0x03;
/// Interrupt source: a new measurement is available (driver default).
pub const VL53L0X_SOURCE_NEW_SAMPLE_READY: u8 = 0x04;

/// VCSEL period type for [`Vl53l0xFull::set_vcsel_pulse_period`].
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Vl53l0xVcselPeriodType {
    /// Pre-range: 12, 14, 16 or 18 PCLKs.
    PreRange,
    /// Final-range: 8, 10, 12 or 14 PCLKs.
    FinalRange,
}

/// Ranging profile for [`Vl53l0xFull::set_profile`].
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Vl53l0xProfile {
    /// 0.25 MCPS, 14/10 PCLKs, 33 ms.
    Default,
    /// 0.10 MCPS, 18/14 PCLKs, 33 ms — dark conditions only.
    LongRange,
    /// 0.25 MCPS, 14/10 PCLKs, 20 ms.
    HighSpeed,
    /// 0.25 MCPS, 14/10 PCLKs, 200 ms.
    HighAccuracy,
}

/// Decoded result block, as returned by [`Vl53l0xFull::read_measurement`].
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Vl53l0xMeasurement {
    /// Range in mm.
    pub distance_mm: u16,
    /// Device range status 0–15 (11 = valid).
    pub range_status: u8,
    /// Return signal rate in MCPS.
    pub signal_rate_mcps: f32,
    /// Ambient rate in MCPS.
    pub ambient_rate_mcps: f32,
    /// Effective SPAD return count.
    pub effective_spad_count: f32,
}

/// Errors from the VL53L0X driver.
#[derive(Debug)]
pub enum Vl53l0xError<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// `IDENTIFICATION_MODEL_ID` was not `0xEE` — wrong chip, wrong address,
    /// or a wiring problem.
    NotFound,
    /// A poll loop expired (500 ms).
    Timeout,
    /// An argument outside its documented range (no bus transaction made).
    InvalidArgument,
}

impl<E> From<E> for Vl53l0xError<E> {
    fn from(e: E) -> Self {
        Vl53l0xError::Bus(e)
    }
}

fn decode_vcsel(reg: u8) -> u16 {
    ((reg as u16) + 1) << 1
}

fn encode_vcsel(pclks: u16) -> u8 {
    ((pclks >> 1) - 1) as u8
}

fn macro_period_ns(pclks: u16) -> u32 {
    (2304 * pclks as u32 * 1655 + 500) / 1000
}

fn mclks_to_us(mclks: u32, pclks: u16) -> u32 {
    (mclks * macro_period_ns(pclks) + 500) / 1000
}

fn us_to_mclks(us: u32, pclks: u16) -> u32 {
    let period = macro_period_ns(pclks);
    (us * 1000 + period / 2) / period
}

fn decode_timeout(reg: u16) -> u32 {
    (((reg & 0xFF) as u32) << (reg >> 8)) + 1
}

fn encode_timeout(mclks: u32) -> u16 {
    if mclks == 0 {
        return 0;
    }
    let mut ls = mclks - 1;
    let mut ms: u16 = 0;
    while ls > 0xFF {
        ls >>= 1;
        ms += 1;
    }
    (ms << 8) | (ls & 0xFF) as u16
}

fn round_half_away(x: f32) -> i32 {
    if x >= 0.0 { (x + 0.5) as i32 } else { -((-x + 0.5) as i32) }
}

/// Sequence-step timeouts read from the registers.
struct StepTimeouts {
    msrc_us: u32,
    pre_mclks: u32,
    pre_us: u32,
    final_pclks: u16,
    final_us: u32,
}

/// VL53L0X — minimal interface: single-shot distance in mm.
pub struct Vl53l0xMinimal<I2C, D> {
    i2c: I2C,
    delay: D,
    addr: u8,
    stop_variable: u8,
    range_status: u8,
    timing_budget_us: u32,
    result: [u8; 12],
}

impl<I2C: I2c, D: DelayNs> Vl53l0xMinimal<I2C, D> {
    /// Construct the driver and run the full initialization sequence: 1.2 ms
    /// boot wait, model ID check, 2V8 I/O mode, reference SPADs, default
    /// tuning, `GPIO1` = new sample ready (active low), ~33 ms timing budget,
    /// VHV + phase reference calibration. Leaves the chip idle.
    ///
    /// `addr` is `0x29` after power-up.
    pub fn new(i2c: I2C, addr: u8, delay: D) -> Result<Self, Vl53l0xError<I2C::Error>> {
        let mut chip = Self {
            i2c,
            delay,
            addr,
            stop_variable: 0,
            range_status: 0,
            timing_budget_us: 0,
            result: [0; 12],
        };
        chip.init()?;
        Ok(chip)
    }

    fn wr(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[reg, value])
    }

    fn rd(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.i2c.write_read(self.addr, &[reg], &mut buf)?;
        Ok(buf[0])
    }

    fn wr16(&mut self, reg: u8, value: u16) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[reg, (value >> 8) as u8, value as u8])
    }

    fn rd16(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        self.i2c.write_read(self.addr, &[reg], &mut buf)?;
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    fn wr32(&mut self, reg: u8, value: u32) -> Result<(), I2C::Error> {
        let b = value.to_be_bytes();
        self.i2c.write(self.addr, &[reg, b[0], b[1], b[2], b[3]])
    }

    fn wait(&mut self, reg: u8, mask: u8, until_set: bool) -> Result<(), Vl53l0xError<I2C::Error>> {
        for _ in 0..POLL_ATTEMPTS {
            if ((self.rd(reg)? & mask) != 0) == until_set {
                return Ok(());
            }
            self.delay.delay_ms(1);
        }
        Err(Vl53l0xError::Timeout)
    }

    fn init(&mut self) -> Result<(), Vl53l0xError<I2C::Error>> {
        self.delay.delay_us(1200);

        if self.rd(REG_MODEL_ID)? != VL53L0X_MODEL_ID {
            return Err(Vl53l0xError::NotFound);
        }

        // 2V8 I/O mode, standard I²C mode.
        let v = self.rd(REG_VHV_PAD_EXTSUP_HV)?;
        self.wr(REG_VHV_PAD_EXTSUP_HV, v | 0x01)?;
        self.wr(REG_I2C_MODE, 0x00)?;

        // Stop variable.
        self.wr(REG_POWER_FORCE, 0x01)?;
        self.wr(REG_PAGE_SELECT, 0x01)?;
        self.wr(REG_SYSRANGE_START, 0x00)?;
        self.stop_variable = self.rd(REG_STOP_VARIABLE)?;
        self.wr(REG_SYSRANGE_START, 0x01)?;
        self.wr(REG_PAGE_SELECT, 0x00)?;
        self.wr(REG_POWER_FORCE, 0x00)?;

        // Disable MSRC and pre-range signal-rate limit checks; 0.25 MCPS limit.
        let v = self.rd(REG_MSRC_CONFIG_CONTROL)?;
        self.wr(REG_MSRC_CONFIG_CONTROL, v | 0x12)?;
        self.wr16(REG_FINAL_MIN_COUNT_RATE_RTN, 0x0020)?;
        self.wr(REG_SYSTEM_SEQUENCE_CONFIG, 0xFF)?;

        let (spad_count, spad_is_aperture) = self.spad_info()?;

        // Reference SPADs.
        let mut ref_map = [0u8; 7];
        ref_map[0] = REG_SPAD_ENABLES_REF_0;
        self.i2c.write_read(self.addr, &[REG_SPAD_ENABLES_REF_0], &mut ref_map[1..])?;
        self.wr(REG_PAGE_SELECT, 0x01)?;
        self.wr(REG_DYNAMIC_SPAD_START_OFFSET, 0x00)?;
        self.wr(REG_DYNAMIC_SPAD_NUM_REQ, 0x2C)?;
        self.wr(REG_PAGE_SELECT, 0x00)?;
        self.wr(REG_REF_EN_START_SELECT, 0xB4)?;
        let first = if spad_is_aperture { 12 } else { 0 };
        let mut enabled = 0u8;
        for i in 0..48usize {
            let byte = &mut ref_map[1 + i / 8];
            let bit = 1u8 << (i % 8);
            if i < first || enabled == spad_count {
                *byte &= !bit;
            } else if *byte & bit != 0 {
                enabled += 1;
            }
        }
        self.i2c.write(self.addr, &ref_map)?;

        // Default tuning settings.
        for pair in TUNING.chunks(2) {
            self.wr(pair[0], pair[1])?;
        }

        // GPIO1 = new sample ready, active low.
        self.wr(REG_SYSTEM_INTERRUPT_CONFIG, VL53L0X_SOURCE_NEW_SAMPLE_READY)?;
        let v = self.rd(REG_GPIO_HV_MUX_ACTIVE_HIGH)?;
        self.wr(REG_GPIO_HV_MUX_ACTIVE_HIGH, v & !0x10)?;
        self.wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)?;

        let budget = self.get_timing_budget()?;
        self.wr(REG_SYSTEM_SEQUENCE_CONFIG, SEQ_OPERATING)?;
        self.set_timing_budget(budget)?;

        self.ref_calibration()
    }

    fn spad_info(&mut self) -> Result<(u8, bool), Vl53l0xError<I2C::Error>> {
        self.wr(REG_POWER_FORCE, 0x01)?;
        self.wr(REG_PAGE_SELECT, 0x01)?;
        self.wr(REG_SYSRANGE_START, 0x00)?;
        self.wr(REG_PAGE_SELECT, 0x06)?;
        let v = self.rd(0x83)?;
        self.wr(0x83, v | 0x04)?;
        self.wr(REG_PAGE_SELECT, 0x07)?;
        self.wr(0x81, 0x01)?;
        self.wr(REG_POWER_FORCE, 0x01)?;
        self.wr(0x94, 0x6B)?;
        self.wr(0x83, 0x00)?;
        let ready = self.wait(0x83, 0xFF, true);
        self.wr(0x83, 0x01)?;
        let tmp = self.rd(0x92)?;
        self.wr(0x81, 0x00)?;
        self.wr(REG_PAGE_SELECT, 0x06)?;
        let v = self.rd(0x83)?;
        self.wr(0x83, v & !0x04)?;
        self.wr(REG_PAGE_SELECT, 0x01)?;
        self.wr(REG_SYSRANGE_START, 0x01)?;
        self.wr(REG_PAGE_SELECT, 0x00)?;
        self.wr(REG_POWER_FORCE, 0x00)?;
        ready?;
        Ok((tmp & 0x7F, (tmp >> 7) & 0x01 == 1))
    }

    fn single_ref_calibration(&mut self, vhv_init: u8) -> Result<(), Vl53l0xError<I2C::Error>> {
        self.wr(REG_SYSRANGE_START, 0x01 | vhv_init)?;
        let ready = self.wait(REG_RESULT_INTERRUPT_STATUS, 0x07, true);
        self.wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)?;
        self.wr(REG_SYSRANGE_START, 0x00)?;
        ready
    }

    fn ref_calibration(&mut self) -> Result<(), Vl53l0xError<I2C::Error>> {
        let seq = self.rd(REG_SYSTEM_SEQUENCE_CONFIG)?;
        self.wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x01)?;
        let vhv = self.single_ref_calibration(0x40);
        self.wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x02)?;
        let phase = self.single_ref_calibration(0x00);
        self.wr(REG_SYSTEM_SEQUENCE_CONFIG, seq)?;
        vhv.and(phase)
    }

    fn step_timeouts(&mut self, enables: u8) -> Result<StepTimeouts, I2C::Error> {
        let pre_pclks = decode_vcsel(self.rd(REG_PRE_RANGE_VCSEL_PERIOD)?);
        let msrc_us = mclks_to_us(self.rd(REG_MSRC_CONFIG_TIMEOUT)? as u32 + 1, pre_pclks);
        let pre_mclks = decode_timeout(self.rd16(REG_PRE_RANGE_TIMEOUT)?);
        let pre_us = mclks_to_us(pre_mclks, pre_pclks);
        let final_pclks = decode_vcsel(self.rd(REG_FINAL_RANGE_VCSEL_PERIOD)?);
        let mut final_mclks = decode_timeout(self.rd16(REG_FINAL_RANGE_TIMEOUT)?);
        if enables & SEQ_PRE_RANGE != 0 {
            final_mclks = final_mclks.saturating_sub(pre_mclks);
        }
        let final_us = mclks_to_us(final_mclks, final_pclks);
        Ok(StepTimeouts { msrc_us, pre_mclks, pre_us, final_pclks, final_us })
    }

    fn fixed_overhead_us(enables: u8, t: &StepTimeouts) -> u32 {
        let mut budget = START_OVERHEAD + END_OVERHEAD;
        if enables & SEQ_TCC != 0 {
            budget += t.msrc_us + TCC_OVERHEAD;
        }
        if enables & SEQ_DSS != 0 {
            budget += 2 * (t.msrc_us + DSS_OVERHEAD);
        } else if enables & SEQ_MSRC != 0 {
            budget += t.msrc_us + MSRC_OVERHEAD;
        }
        if enables & SEQ_PRE_RANGE != 0 {
            budget += t.pre_us + PRE_RANGE_OVERHEAD;
        }
        budget
    }

    fn get_timing_budget(&mut self) -> Result<u32, I2C::Error> {
        let enables = self.rd(REG_SYSTEM_SEQUENCE_CONFIG)?;
        let t = self.step_timeouts(enables)?;
        let mut budget = Self::fixed_overhead_us(enables, &t);
        if enables & SEQ_FINAL_RANGE != 0 {
            budget += t.final_us + FINAL_RANGE_OVERHEAD;
        }
        Ok(budget)
    }

    fn set_timing_budget(&mut self, budget_us: u32) -> Result<(), Vl53l0xError<I2C::Error>> {
        if budget_us < MIN_TIMING_BUDGET_US {
            return Err(Vl53l0xError::InvalidArgument);
        }
        let enables = self.rd(REG_SYSTEM_SEQUENCE_CONFIG)?;
        let t = self.step_timeouts(enables)?;
        let mut used = Self::fixed_overhead_us(enables, &t);
        if enables & SEQ_FINAL_RANGE != 0 {
            used += FINAL_RANGE_OVERHEAD;
            if used > budget_us {
                return Err(Vl53l0xError::InvalidArgument);
            }
            let mut final_mclks = us_to_mclks(budget_us - used, t.final_pclks);
            if enables & SEQ_PRE_RANGE != 0 {
                final_mclks += t.pre_mclks;
            }
            self.wr16(REG_FINAL_RANGE_TIMEOUT, encode_timeout(final_mclks))?;
        }
        self.timing_budget_us = budget_us;
        Ok(())
    }

    fn stop_variable_preamble(&mut self) -> Result<(), I2C::Error> {
        self.wr(REG_POWER_FORCE, 0x01)?;
        self.wr(REG_PAGE_SELECT, 0x01)?;
        self.wr(REG_SYSRANGE_START, 0x00)?;
        self.wr(REG_STOP_VARIABLE, self.stop_variable)?;
        self.wr(REG_SYSRANGE_START, 0x01)?;
        self.wr(REG_PAGE_SELECT, 0x00)?;
        self.wr(REG_POWER_FORCE, 0x00)
    }

    fn read_result(&mut self) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 12];
        self.i2c.write_read(self.addr, &[REG_RESULT_RANGE_STATUS], &mut buf)?;
        self.wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)?;
        self.result = buf;
        self.range_status = (buf[0] & 0x78) >> 3;
        Ok(())
    }

    fn result_distance(&self) -> u16 {
        ((self.result[10] as u16) << 8) | self.result[11] as u16
    }

    fn wait_and_read(&mut self) -> Result<u16, Vl53l0xError<I2C::Error>> {
        self.wait(REG_RESULT_INTERRUPT_STATUS, 0x07, true)?;
        self.read_result()?;
        Ok(self.result_distance())
    }

    /// Take one single-shot measurement; returns the distance in mm.
    ///
    /// Blocks for about one timing budget (33 ms by default). Returns the raw
    /// range even when the measurement is not valid — typically 8190 or 8191
    /// with no target in range; check [`Self::range_valid`].
    pub fn distance(&mut self) -> Result<u16, Vl53l0xError<I2C::Error>> {
        self.stop_variable_preamble()?;
        self.wr(REG_SYSRANGE_START, 0x01)?;
        self.wait(REG_SYSRANGE_START, 0x01, false)?;
        self.wait_and_read()
    }

    /// `true` iff the device range status of the most recent measurement was
    /// 11 (range complete).
    pub fn range_valid(&self) -> bool {
        self.range_status == VL53L0X_RANGE_STATUS_VALID
    }

    /// Consume the driver and return the underlying I²C bus and delay.
    pub fn release(self) -> (I2C, D) {
        (self.i2c, self.delay)
    }
}

/// VL53L0X — full interface: extends [`Vl53l0xMinimal`] with continuous and
/// timed ranging, the full measurement record, timing budget, signal-rate
/// limit, VCSEL pulse periods, ranging profiles, offset and crosstalk
/// compensation, reference recalibration, address change, distance
/// thresholds, identification, and interrupt polling.
pub struct Vl53l0xFull<I2C, D> {
    inner: Vl53l0xMinimal<I2C, D>,
}

impl<I2C: I2c, D: DelayNs> Vl53l0xFull<I2C, D> {
    /// Construct the driver; same initialization as [`Vl53l0xMinimal::new`].
    pub fn new(i2c: I2C, addr: u8, delay: D) -> Result<Self, Vl53l0xError<I2C::Error>> {
        Ok(Self { inner: Vl53l0xMinimal::new(i2c, addr, delay)? })
    }

    /// Single-shot distance in mm. Delegates to [`Vl53l0xMinimal::distance`].
    pub fn distance(&mut self) -> Result<u16, Vl53l0xError<I2C::Error>> {
        self.inner.distance()
    }

    /// Validity of the last measurement. Delegates to
    /// [`Vl53l0xMinimal::range_valid`].
    pub fn range_valid(&self) -> bool {
        self.inner.range_valid()
    }

    /// Consume the driver and return the underlying I²C bus and delay.
    pub fn release(self) -> (I2C, D) {
        self.inner.release()
    }

    /// Start continuous ranging: `period_ms` = 0 for back-to-back mode,
    /// otherwise timed mode with this inter-measurement period in ms (should
    /// be ≥ the timing budget).
    pub fn start_continuous(&mut self, period_ms: u32) -> Result<(), I2C::Error> {
        let s = &mut self.inner;
        s.stop_variable_preamble()?;
        if period_ms > 0 {
            let osc = s.rd16(REG_OSC_CALIBRATE_VAL)? as u32;
            s.wr32(REG_SYSTEM_INTERMEASUREMENT, if osc != 0 { period_ms * osc } else { period_ms })?;
            s.wr(REG_SYSRANGE_START, 0x04)
        } else {
            s.wr(REG_SYSRANGE_START, 0x02)
        }
    }

    /// Stop continuous ranging. Does not wait for a running measurement.
    pub fn stop_continuous(&mut self) -> Result<(), I2C::Error> {
        let s = &mut self.inner;
        s.wr(REG_SYSRANGE_START, 0x01)?;
        s.wr(REG_PAGE_SELECT, 0x01)?;
        s.wr(REG_SYSRANGE_START, 0x00)?;
        s.wr(REG_STOP_VARIABLE, 0x00)?;
        s.wr(REG_SYSRANGE_START, 0x01)?;
        s.wr(REG_PAGE_SELECT, 0x00)
    }

    /// Wait for the next continuous-mode result and return its distance in
    /// mm (check [`Self::range_valid`]).
    pub fn read_continuous(&mut self) -> Result<u16, Vl53l0xError<I2C::Error>> {
        self.inner.wait_and_read()
    }

    /// `true` if a measurement is pending (`RESULT_INTERRUPT_STATUS` bits 2:0
    /// non-zero) — non-blocking.
    pub fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.rd(REG_RESULT_INTERRUPT_STATUS)? & 0x07 != 0)
    }

    /// Read the full result block and clear the interrupt (non-blocking).
    pub fn read_measurement(&mut self) -> Result<Vl53l0xMeasurement, I2C::Error> {
        self.inner.read_result()?;
        let b = &self.inner.result;
        let word = |i: usize| ((b[i] as u16) << 8) | b[i + 1] as u16;
        Ok(Vl53l0xMeasurement {
            distance_mm: word(10),
            range_status: self.inner.range_status,
            signal_rate_mcps: word(6) as f32 / 128.0,
            ambient_rate_mcps: word(8) as f32 / 128.0,
            effective_spad_count: word(2) as f32 / 256.0,
        })
    }

    /// Device range status (0–15) of the most recent measurement; 11 = valid,
    /// 4 = no target (MSRC).
    pub fn range_status(&self) -> u8 {
        self.inner.range_status
    }

    /// Set the per-measurement timing budget in µs (≥ 20000). Returns
    /// [`Vl53l0xError::InvalidArgument`] below 20000 µs or below the enabled
    /// steps' overhead.
    pub fn set_timing_budget(&mut self, budget_us: u32) -> Result<(), Vl53l0xError<I2C::Error>> {
        self.inner.set_timing_budget(budget_us)
    }

    /// Timing budget in µs, computed from the current registers.
    pub fn timing_budget(&mut self) -> Result<u32, I2C::Error> {
        self.inner.get_timing_budget()
    }

    /// Set the final-range return signal-rate limit in MCPS (0 to 511.99).
    /// Lower values extend range but admit noisier readings.
    pub fn set_signal_rate_limit(&mut self, limit_mcps: f32) -> Result<(), Vl53l0xError<I2C::Error>> {
        if !(0.0..=511.99).contains(&limit_mcps) {
            return Err(Vl53l0xError::InvalidArgument);
        }
        self.inner.wr16(REG_FINAL_MIN_COUNT_RATE_RTN, (limit_mcps * 128.0 + 0.5) as u16)?;
        Ok(())
    }

    /// Final-range return signal-rate limit in MCPS.
    pub fn signal_rate_limit(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.rd16(REG_FINAL_MIN_COUNT_RATE_RTN)? as f32 / 128.0)
    }

    /// Set a VCSEL pulse period in PCLKs (pre-range 12/14/16/18, final-range
    /// 8/10/12/14), then re-apply the timing budget and redo the phase
    /// reference calibration.
    pub fn set_vcsel_pulse_period(
        &mut self,
        period_type: Vl53l0xVcselPeriodType,
        pclks: u8,
    ) -> Result<(), Vl53l0xError<I2C::Error>> {
        let pre_high = match (period_type, pclks) {
            (Vl53l0xVcselPeriodType::PreRange, 12) => 0x18,
            (Vl53l0xVcselPeriodType::PreRange, 14) => 0x30,
            (Vl53l0xVcselPeriodType::PreRange, 16) => 0x40,
            (Vl53l0xVcselPeriodType::PreRange, 18) => 0x50,
            (Vl53l0xVcselPeriodType::PreRange, _) => return Err(Vl53l0xError::InvalidArgument),
            _ => 0,
        };
        // VALID_PHASE_HIGH, VALID_PHASE_LOW, VCSEL_WIDTH, PHASECAL_CONFIG_TIMEOUT, page-1 PHASECAL_LIM.
        let fin: [u8; 5] = match (period_type, pclks) {
            (Vl53l0xVcselPeriodType::FinalRange, 8) => [0x10, 0x08, 0x02, 0x0C, 0x30],
            (Vl53l0xVcselPeriodType::FinalRange, 10) => [0x28, 0x08, 0x03, 0x09, 0x20],
            (Vl53l0xVcselPeriodType::FinalRange, 12) => [0x38, 0x08, 0x03, 0x08, 0x20],
            (Vl53l0xVcselPeriodType::FinalRange, 14) => [0x48, 0x08, 0x03, 0x07, 0x20],
            (Vl53l0xVcselPeriodType::FinalRange, _) => return Err(Vl53l0xError::InvalidArgument),
            _ => [0; 5],
        };

        let s = &mut self.inner;
        let enables = s.rd(REG_SYSTEM_SEQUENCE_CONFIG)?;
        let t = s.step_timeouts(enables)?;
        let pclks = pclks as u16;
        let vcsel = encode_vcsel(pclks);

        match period_type {
            Vl53l0xVcselPeriodType::PreRange => {
                s.wr(REG_PRE_VALID_PHASE_HIGH, pre_high)?;
                s.wr(REG_PRE_VALID_PHASE_LOW, 0x08)?;
                s.wr(REG_PRE_RANGE_VCSEL_PERIOD, vcsel)?;
                s.wr16(REG_PRE_RANGE_TIMEOUT, encode_timeout(us_to_mclks(t.pre_us, pclks)))?;
                let m = us_to_mclks(t.msrc_us, pclks);
                s.wr(REG_MSRC_CONFIG_TIMEOUT, if m > 256 { 255 } else { m.saturating_sub(1) as u8 })?;
            }
            Vl53l0xVcselPeriodType::FinalRange => {
                s.wr(REG_FINAL_VALID_PHASE_HIGH, fin[0])?;
                s.wr(REG_FINAL_VALID_PHASE_LOW, fin[1])?;
                s.wr(REG_GLOBAL_CONFIG_VCSEL_WIDTH, fin[2])?;
                s.wr(REG_PHASECAL_CONFIG_TIMEOUT, fin[3])?;
                s.wr(REG_PAGE_SELECT, 0x01)?;
                s.wr(REG_PHASECAL_CONFIG_TIMEOUT, fin[4])?;
                s.wr(REG_PAGE_SELECT, 0x00)?;
                s.wr(REG_FINAL_RANGE_VCSEL_PERIOD, vcsel)?;
                let mut f = us_to_mclks(t.final_us, pclks);
                if enables & SEQ_PRE_RANGE != 0 {
                    f += t.pre_mclks;
                }
                s.wr16(REG_FINAL_RANGE_TIMEOUT, encode_timeout(f))?;
            }
        }

        let budget = s.timing_budget_us;
        s.set_timing_budget(budget)?;
        let seq = s.rd(REG_SYSTEM_SEQUENCE_CONFIG)?;
        s.wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x02)?;
        let phase = s.single_ref_calibration(0x00);
        s.wr(REG_SYSTEM_SEQUENCE_CONFIG, seq)?;
        phase
    }

    /// VCSEL pulse period in PCLKs.
    pub fn vcsel_pulse_period(&mut self, period_type: Vl53l0xVcselPeriodType) -> Result<u8, I2C::Error> {
        let reg = match period_type {
            Vl53l0xVcselPeriodType::PreRange => REG_PRE_RANGE_VCSEL_PERIOD,
            Vl53l0xVcselPeriodType::FinalRange => REG_FINAL_RANGE_VCSEL_PERIOD,
        };
        Ok(decode_vcsel(self.inner.rd(reg)?) as u8)
    }

    /// Apply a ranging profile: signal-rate limit, VCSEL periods (pre first),
    /// then timing budget.
    pub fn set_profile(&mut self, profile: Vl53l0xProfile) -> Result<(), Vl53l0xError<I2C::Error>> {
        let (limit, pre, fin, budget) = match profile {
            Vl53l0xProfile::Default => (0.25, 14, 10, 33000),
            Vl53l0xProfile::LongRange => (0.10, 18, 14, 33000),
            Vl53l0xProfile::HighSpeed => (0.25, 14, 10, 20000),
            Vl53l0xProfile::HighAccuracy => (0.25, 14, 10, 200000),
        };
        self.set_signal_rate_limit(limit)?;
        self.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange, pre)?;
        self.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange, fin)?;
        self.inner.set_timing_budget(budget)
    }

    /// Override the part-to-part range offset in mm (−512.0 to 511.75, 0.25 mm
    /// steps; volatile).
    pub fn set_offset(&mut self, offset_mm: f32) -> Result<(), Vl53l0xError<I2C::Error>> {
        if !(-512.0..=511.75).contains(&offset_mm) {
            return Err(Vl53l0xError::InvalidArgument);
        }
        self.inner.wr16(REG_PART_TO_PART_RANGE_OFFSET, (round_half_away(offset_mm * 4.0) & 0x0FFF) as u16)?;
        Ok(())
    }

    /// Part-to-part range offset in mm.
    pub fn offset(&mut self) -> Result<f32, I2C::Error> {
        let mut raw = (self.inner.rd16(REG_PART_TO_PART_RANGE_OFFSET)? & 0x0FFF) as i16;
        if raw & 0x0800 != 0 {
            raw -= 0x1000;
        }
        Ok(raw as f32 * 0.25)
    }

    /// Set the crosstalk compensation peak rate in MCPS (volatile): 0
    /// disables, otherwise 0 < rate < 8.0 from the host's own cover-glass
    /// calibration.
    pub fn set_crosstalk_compensation(&mut self, rate_mcps: f32) -> Result<(), Vl53l0xError<I2C::Error>> {
        if !(0.0..8.0).contains(&rate_mcps) {
            return Err(Vl53l0xError::InvalidArgument);
        }
        self.inner.wr16(REG_CROSSTALK_COMPENSATION, (rate_mcps * 8192.0 + 0.5) as u16)?;
        Ok(())
    }

    /// Re-run the VHV and phase reference calibrations. Call in software
    /// standby (not while continuous ranging), and after the die temperature
    /// changes by more than 8 °C.
    pub fn recalibrate(&mut self) -> Result<(), Vl53l0xError<I2C::Error>> {
        self.inner.ref_calibration()
    }

    /// Change the chip's I²C address (`0x08`–`0x77`, volatile). The chip
    /// answers on the new address immediately; [`Self::release`] the bus and
    /// construct a new driver at the new address.
    pub fn set_address(&mut self, address: u8) -> Result<(), Vl53l0xError<I2C::Error>> {
        if !(0x08..=0x77).contains(&address) {
            return Err(Vl53l0xError::InvalidArgument);
        }
        self.inner.wr(REG_I2C_SLAVE_DEVICE_ADDRESS, address & 0x7F)?;
        Ok(())
    }

    /// Set the distance thresholds in mm used by the threshold interrupt
    /// sources (2 mm resolution; `low_mm` ≤ `high_mm` ≤ 8190).
    pub fn set_interrupt_thresholds(&mut self, low_mm: u16, high_mm: u16) -> Result<(), Vl53l0xError<I2C::Error>> {
        if high_mm < low_mm || high_mm > 8190 {
            return Err(Vl53l0xError::InvalidArgument);
        }
        self.inner.wr16(REG_SYSTEM_THRESH_LOW, (low_mm / 2) & 0x0FFF)?;
        self.inner.wr16(REG_SYSTEM_THRESH_HIGH, (high_mm / 2) & 0x0FFF)?;
        Ok(())
    }

    /// Distance thresholds `(low_mm, high_mm)`.
    pub fn interrupt_thresholds(&mut self) -> Result<(u16, u16), I2C::Error> {
        let low = (self.inner.rd16(REG_SYSTEM_THRESH_LOW)? & 0x0FFF) * 2;
        let high = (self.inner.rd16(REG_SYSTEM_THRESH_HIGH)? & 0x0FFF) * 2;
        Ok((low, high))
    }

    /// `IDENTIFICATION_MODEL_ID` — `0xEE`.
    pub fn model_id(&mut self) -> Result<u8, I2C::Error> {
        self.inner.rd(REG_MODEL_ID)
    }

    /// `IDENTIFICATION_REVISION_ID` — `0x10` on current silicon.
    pub fn revision_id(&mut self) -> Result<u8, I2C::Error> {
        self.inner.rd(REG_REVISION_ID)
    }

    /// Select the `GPIO1` interrupt source (one of the `VL53L0X_SOURCE_*`
    /// constants), replacing the active one. With a threshold source active,
    /// [`Self::data_ready`]/[`Self::read_continuous`] only see a pending status
    /// when the threshold condition is met.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Vl53l0xError<I2C::Error>> {
        if !(VL53L0X_SOURCE_LEVEL_LOW..=VL53L0X_SOURCE_NEW_SAMPLE_READY).contains(&source) {
            return Err(Vl53l0xError::InvalidArgument);
        }
        self.inner.wr(REG_SYSTEM_INTERRUPT_CONFIG, source)?;
        Ok(())
    }

    /// Disable `GPIO1` interrupts if `source` is the active one.
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        if self.inner.rd(REG_SYSTEM_INTERRUPT_CONFIG)? & 0x07 == source {
            self.inner.wr(REG_SYSTEM_INTERRUPT_CONFIG, 0x00)?;
        }
        Ok(())
    }

    /// Read and clear the pending interrupt status: the `VL53L0X_SOURCE_*`
    /// value that fired, or 0 if nothing is pending.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        let status = self.inner.rd(REG_RESULT_INTERRUPT_STATUS)? & 0x07;
        if status != 0 {
            self.inner.wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)?;
        }
        Ok(status)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::{ErrorType, Operation};
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use std::cell::RefCell;
    use std::collections::HashMap;
    use std::rc::Rc;
    use std::vec::Vec;

    /// Page-aware VL53L0X simulator. Registers written while `0xFF` != 0 go
    /// to a separate per-page store, so the private-bank tuning writes don't
    /// clobber page-0 registers. Starting a ranging (or calibration) raises
    /// `RESULT_INTERRUPT_STATUS`; the interrupt clear drops it unless
    /// continuous mode is active. The SPAD-info handshake (page 7, `0x83`)
    /// completes immediately.
    #[derive(Default)]
    struct State {
        regs: HashMap<u8, u8>,
        pages: HashMap<(u8, u8), u8>,
        log: Vec<(u8, Vec<u8>)>,
        page: u8,
        continuous: bool,
    }

    impl State {
        fn reg(&self, r: u8) -> u8 {
            *self.regs.get(&r).unwrap_or(&0)
        }
        fn reg16(&self, r: u8) -> u16 {
            ((self.reg(r) as u16) << 8) | self.reg(r + 1) as u16
        }
        fn set(&mut self, r: u8, values: &[u8]) {
            for (i, v) in values.iter().enumerate() {
                self.regs.insert(r + i as u8, *v);
            }
        }
        fn page0_writes(&self, r: u8) -> Vec<Vec<u8>> {
            self.log.iter().filter(|(p, w)| *p == 0 && w.len() >= 2 && w[0] == r).map(|(_, w)| w.clone()).collect()
        }
        fn logged(&self, w: &[u8]) -> bool {
            self.log.iter().any(|(_, x)| x.as_slice() == w)
        }
        fn write(&mut self, data: &[u8]) {
            let reg = data[0];
            if reg == 0xFF {
                self.page = data[1];
            }
            self.log.push((self.page, data.to_vec()));
            if self.page != 0 && reg != 0xFF {
                for (i, v) in data[1..].iter().enumerate() {
                    self.pages.insert((self.page, reg + i as u8), *v);
                }
                if self.page == 7 && reg == 0x83 && data[1] == 0x00 {
                    self.pages.insert((7, 0x83), 0x01);
                }
                return;
            }
            for (i, v) in data[1..].iter().enumerate() {
                self.regs.insert(reg.wrapping_add(i as u8), *v);
            }
            if reg == 0x00 && data.len() == 2 {
                let value = data[1];
                if value & 0x06 != 0 {
                    self.continuous = true;
                    self.regs.insert(0x13, 0x04);
                } else if value & 0x01 != 0 {
                    if self.continuous {
                        self.continuous = false;
                    } else {
                        self.regs.insert(0x13, 0x04);
                    }
                }
                self.regs.insert(0x00, 0x00);
            } else if reg == 0x0B && data[1] == 0x01 && !self.continuous {
                self.regs.insert(0x13, 0x00);
            }
        }
        fn read(&self, reg: u8, buf: &mut [u8]) {
            for (i, b) in buf.iter_mut().enumerate() {
                let r = reg.wrapping_add(i as u8);
                *b = if self.page != 0 {
                    *self.pages.get(&(self.page, r)).unwrap_or(&0)
                } else {
                    self.reg(r)
                };
            }
        }
    }

    #[derive(Clone)]
    struct Sim(Rc<RefCell<State>>);

    impl Sim {
        fn new() -> Self {
            let mut s = State::default();
            s.set(0xC0, &[0xEE, 0xAA, 0x10]);
            s.set(0x84, &[0x11]);
            s.set(0xB0, &[0xFF; 6]);
            s.set(0xF8, &[0x00, 0x10]);
            // Result block: status 11, 10.0 SPADs, 5.0 MCPS signal, 0.5 MCPS ambient, 250 mm.
            s.set(0x14, &[11 << 3, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x02, 0x80, 0x00, 0x40, 0x00, 0xFA]);
            s.pages.insert((1, 0x91), 0x3C);
            s.pages.insert((7, 0x92), 0x85);
            Sim(Rc::new(RefCell::new(s)))
        }
        fn st(&self) -> std::cell::Ref<'_, State> {
            self.0.borrow()
        }
    }

    impl ErrorType for Sim {
        type Error = core::convert::Infallible;
    }

    impl I2c for Sim {
        fn transaction(&mut self, _addr: u8, ops: &mut [Operation<'_>]) -> Result<(), Self::Error> {
            let mut s = self.0.borrow_mut();
            let mut last_reg = 0u8;
            for op in ops.iter_mut() {
                match op {
                    Operation::Write(data) => {
                        last_reg = data[0];
                        if data.len() >= 2 {
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

    #[test]
    fn rejects_wrong_model_id() {
        let sim = Sim::new();
        sim.0.borrow_mut().set(0xC0, &[0xEF]);
        assert!(matches!(Vl53l0xMinimal::new(sim.clone(), ADDR, NoopDelay::new()), Err(Vl53l0xError::NotFound)));
    }

    #[test]
    fn init_sequence() {
        let sim = Sim::new();
        let _sensor = Vl53l0xMinimal::new(sim.clone(), ADDR, NoopDelay::new()).expect("init");
        let s = sim.st();
        assert_eq!(s.reg(0x89) & 0x01, 0x01);
        assert_eq!(s.reg(0x88), 0x00);
        assert_eq!(s.page0_writes(0x60)[0], vec![0x60, 0x12]);
        assert_eq!(s.page0_writes(0x44)[0], vec![0x44, 0x00, 0x20]);
        assert_eq!((0..6).map(|i| s.reg(0xB0 + i)).collect::<Vec<_>>(), vec![0x00, 0xF0, 0x01, 0, 0, 0]);
        assert_eq!(s.reg(0xB6), 0xB4);
        assert_eq!(s.pages[&(1, 0x4E)], 0x2C);
        assert_eq!(s.reg(0x46), 0x25);
        assert_eq!(s.pages[&(1, 0x46)], 0x05);
        assert_eq!(s.reg(0x0A), 0x04);
        assert_eq!(s.reg(0x84), 0x01);
        assert_eq!(s.reg(0x01), 0xE8);
        let starts: Vec<u8> = s.page0_writes(0x00).iter().map(|w| w[1]).collect();
        assert_eq!(&starts[starts.len() - 4..], &[0x41, 0x00, 0x01, 0x00]);
        let seq: Vec<u8> = s.page0_writes(0x01).iter().map(|w| w[1]).collect();
        assert_eq!(&seq[seq.len() - 4..], &[0xE8, 0x01, 0x02, 0xE8]);
        assert_eq!(s.page, 0);
    }

    #[test]
    fn single_shot() {
        let sim = Sim::new();
        let mut sensor = Vl53l0xMinimal::new(sim.clone(), ADDR, NoopDelay::new()).expect("init");
        sim.0.borrow_mut().log.clear();
        assert_eq!(sensor.distance().unwrap(), 250);
        assert!(sensor.range_valid());
        {
            let s = sim.st();
            let pre: Vec<Vec<u8>> = s.log[..8].iter().map(|(_, w)| w.clone()).collect();
            assert_eq!(pre, vec![
                vec![0x80, 0x01], vec![0xFF, 0x01], vec![0x00, 0x00], vec![0x91, 0x3C],
                vec![0x00, 0x01], vec![0xFF, 0x00], vec![0x80, 0x00], vec![0x00, 0x01],
            ]);
            assert_eq!(s.log.last().unwrap().1, vec![0x0B, 0x01]);
        }
        sim.0.borrow_mut().set(0x14, &[4 << 3]);
        sim.0.borrow_mut().set(0x1E, &[0x1F, 0xFF]);
        assert_eq!(sensor.distance().unwrap(), 8191);
        assert!(!sensor.range_valid());
    }

    #[test]
    fn full_measurement_and_continuous() {
        let sim = Sim::new();
        let mut full = Vl53l0xFull::new(sim.clone(), ADDR, NoopDelay::new()).expect("init");
        full.distance().unwrap();
        let m = full.read_measurement().unwrap();
        assert_eq!(m.distance_mm, 250);
        assert_eq!(m.range_status, 11);
        assert_eq!(full.range_status(), 11);
        assert_eq!(m.signal_rate_mcps, 5.0);
        assert_eq!(m.ambient_rate_mcps, 0.5);
        assert_eq!(m.effective_spad_count, 10.0);

        sim.0.borrow_mut().log.clear();
        full.start_continuous(0).unwrap();
        assert_eq!(sim.st().log.last().unwrap().1, vec![0x00, 0x02]);
        assert!(sim.st().logged(&[0x91, 0x3C]));
        assert!(full.data_ready().unwrap());
        assert_eq!(full.read_continuous().unwrap(), 250);
        full.stop_continuous().unwrap();
        {
            let s = sim.st();
            let tail: Vec<Vec<u8>> = s.log[s.log.len() - 6..].iter().map(|(_, w)| w.clone()).collect();
            assert_eq!(tail, vec![
                vec![0x00, 0x01], vec![0xFF, 0x01], vec![0x00, 0x00], vec![0x91, 0x00],
                vec![0x00, 0x01], vec![0xFF, 0x00],
            ]);
        }
        full.start_continuous(100).unwrap();
        assert_eq!((0..4).map(|i| sim.st().reg(0x04 + i)).collect::<Vec<_>>(), vec![0x00, 0x00, 0x06, 0x40]);
        assert_eq!(sim.st().log.last().unwrap().1, vec![0x00, 0x04]);
        full.stop_continuous().unwrap();
    }

    #[test]
    fn full_timing_vcsel_profiles() {
        let sim = Sim::new();
        let mut full = Vl53l0xFull::new(sim.clone(), ADDR, NoopDelay::new()).expect("init");
        let budget = full.timing_budget().unwrap();
        assert!((32000..=34000).contains(&budget));
        full.set_timing_budget(50000).unwrap();
        assert!(full.timing_budget().unwrap().abs_diff(50000) < 50);
        assert!(matches!(full.set_timing_budget(19999), Err(Vl53l0xError::InvalidArgument)));

        full.set_signal_rate_limit(0.1).unwrap();
        assert_eq!(sim.st().reg16(0x44), 13);
        assert_eq!(full.signal_rate_limit().unwrap(), 13.0 / 128.0);
        assert!(matches!(full.set_signal_rate_limit(-1.0), Err(Vl53l0xError::InvalidArgument)));

        assert_eq!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange).unwrap(), 14);
        assert_eq!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange).unwrap(), 10);
        full.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange, 18).unwrap();
        assert_eq!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange).unwrap(), 18);
        assert_eq!(sim.st().reg(0x57), 0x50);
        full.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange, 14).unwrap();
        assert_eq!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange).unwrap(), 14);
        {
            let s = sim.st();
            assert_eq!((s.reg(0x48), s.reg(0x32), s.reg(0x30), s.pages[&(1, 0x30)]), (0x48, 0x03, 0x07, 0x20));
            assert_eq!(s.reg(0x01), 0xE8);
        }
        assert!(full.timing_budget().unwrap().abs_diff(50000) < 300);
        assert!(matches!(
            full.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange, 13),
            Err(Vl53l0xError::InvalidArgument)
        ));

        full.set_profile(Vl53l0xProfile::HighSpeed).unwrap();
        assert_eq!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange).unwrap(), 14);
        assert_eq!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange).unwrap(), 10);
        assert!(full.timing_budget().unwrap().abs_diff(20000) < 50);
        assert_eq!(sim.st().reg16(0x44), 32);
        full.set_profile(Vl53l0xProfile::LongRange).unwrap();
        assert_eq!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange).unwrap(), 18);
        assert_eq!(sim.st().reg16(0x44), 13);
    }

    #[test]
    fn full_offset_crosstalk_thresholds_interrupts() {
        let sim = Sim::new();
        let mut full = Vl53l0xFull::new(sim.clone(), ADDR, NoopDelay::new()).expect("init");
        full.set_offset(-10.25).unwrap();
        assert_eq!(sim.st().reg16(0x28), (-41i32 & 0x0FFF) as u16);
        assert_eq!(full.offset().unwrap(), -10.25);
        full.set_offset(12.5).unwrap();
        assert_eq!(full.offset().unwrap(), 12.5);
        assert!(matches!(full.set_offset(512.0), Err(Vl53l0xError::InvalidArgument)));
        full.set_crosstalk_compensation(0.5).unwrap();
        assert_eq!(sim.st().reg16(0x20), 4096);
        assert!(matches!(full.set_crosstalk_compensation(8.0), Err(Vl53l0xError::InvalidArgument)));

        sim.0.borrow_mut().log.clear();
        full.recalibrate().unwrap();
        assert!(sim.st().logged(&[0x00, 0x41]) && sim.st().logged(&[0x01, 0x02]));
        assert_eq!(sim.st().reg(0x01), 0xE8);

        full.set_interrupt_thresholds(100, 801).unwrap();
        assert_eq!((sim.st().reg16(0x0E), sim.st().reg16(0x0C)), (50, 400));
        assert_eq!(full.interrupt_thresholds().unwrap(), (100, 800));
        assert!(matches!(full.set_interrupt_thresholds(500, 100), Err(Vl53l0xError::InvalidArgument)));
        full.set_address(0x30).unwrap();
        assert_eq!(sim.st().reg(0x8A), 0x30);
        assert!(matches!(full.set_address(0x78), Err(Vl53l0xError::InvalidArgument)));
        assert_eq!(full.model_id().unwrap(), 0xEE);
        assert_eq!(full.revision_id().unwrap(), 0x10);

        full.enable_interrupt(VL53L0X_SOURCE_OUT_OF_WINDOW).unwrap();
        assert_eq!(sim.st().reg(0x0A), 0x03);
        full.disable_interrupt(VL53L0X_SOURCE_LEVEL_LOW).unwrap();
        assert_eq!(sim.st().reg(0x0A), 0x03);
        full.disable_interrupt(VL53L0X_SOURCE_OUT_OF_WINDOW).unwrap();
        assert_eq!(sim.st().reg(0x0A), 0x00);
        sim.0.borrow_mut().set(0x13, &[0x03 | 0x08]);
        assert_eq!(full.poll_interrupt().unwrap(), VL53L0X_SOURCE_OUT_OF_WINDOW);
        assert_eq!(sim.st().reg(0x13), 0x00);
        assert_eq!(full.poll_interrupt().unwrap(), 0);
        assert!(matches!(full.enable_interrupt(5), Err(Vl53l0xError::InvalidArgument)));
    }

    #[test]
    fn timeout_when_data_never_ready() {
        let sim = Sim::new();
        let mut full = Vl53l0xFull::new(sim.clone(), ADDR, NoopDelay::new()).expect("init");
        full.start_continuous(0).unwrap();
        sim.0.borrow_mut().continuous = false;
        sim.0.borrow_mut().set(0x13, &[0x00]);
        assert!(matches!(full.read_continuous(), Err(Vl53l0xError::Timeout)));
    }
}
