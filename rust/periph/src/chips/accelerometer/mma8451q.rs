//! MMA8451Q — 3-axis MEMS accelerometer (NXP / Freescale).
//!
//! Low-power capacitive accelerometer with 14-bit output (4096 counts/*g* at
//! ±2 *g*), dynamically selectable ±2 / ±4 / ±8 *g* full scale and output data
//! rates from 1.56 Hz to 800 Hz. Communicates over I²C at address `0x1C`
//! (SA0 = GND) or `0x1D` (SA0 = VDDIO).
//!
//! ## Default configuration
//!
//! - Range ±2 *g* (4096 counts/*g*)
//! - Output data rate 100 Hz
//! - 14-bit output (fast read off)
//! - Normal oversampling in WAKE and SLEEP
//! - Low-noise mode off
//! - High-pass output off
//! - FIFO disabled
//! - Interrupt pins active-low, push-pull
//!
//! ## Timing
//!
//! This driver is `no_std` and therefore has no delay provider. The chip
//! needs a few milliseconds after power-up (≥ 1 ms), after a software reset
//! (≥ 1 ms) and after a STANDBY → ACTIVE transition (2/ODR + 2 ms, 22 ms at
//! 100 Hz). Those waits are the caller's responsibility — the same precedent
//! as [`Bma150Minimal`](super::bma150::Bma150Minimal). Applications should
//! sleep for the turn-on time after [`Mma8451QFull::activate`] (or after
//! construction) before the first sample is valid.
//!
//! ## Interrupts
//!
//! Rust drivers expose only [`Mma8451QFull::poll_interrupt`]; the caller is
//! responsible for wiring the INT1 / INT2 pin into an ISR or polling loop.
//!
//! ## Constants
//!
//! Interrupt sources: [`SOURCE_DATA_READY`], [`SOURCE_FF_MT`], [`SOURCE_PULSE`],
//! [`SOURCE_ORIENTATION`], [`SOURCE_TRANSIENT`], [`SOURCE_FIFO`],
//! [`SOURCE_AUTO_SLEEP`].
//!
//! Note: the ADXL362 driver already exports a `SOURCE_DATA_READY` constant;
//! this module's constants are therefore re-exported from the accelerometer
//! module under the `MMA8451Q_` prefix.

use embedded_hal::i2c::I2c;

use crate::connection::register::{read_register, write_register};

// Register addresses.
const REG_STATUS: u8         = 0x00;
const REG_OUT_X_MSB: u8      = 0x01;
const REG_F_SETUP: u8        = 0x09;
const REG_TRIG_CFG: u8       = 0x0A;
const REG_SYSMOD: u8         = 0x0B;
const REG_INT_SOURCE: u8     = 0x0C;
const REG_WHO_AM_I: u8       = 0x0D;
const REG_XYZ_DATA_CFG: u8   = 0x0E;
const REG_HP_FILTER_CUTOFF: u8 = 0x0F;
const REG_PL_STATUS: u8      = 0x10;
const REG_PL_CFG: u8         = 0x11;
const REG_PL_COUNT: u8       = 0x12;
const REG_PL_BF_ZCOMP: u8    = 0x13;
const REG_PL_THS: u8         = 0x14;
const REG_FF_MT_CFG: u8      = 0x15;
const REG_FF_MT_SRC: u8      = 0x16;
const REG_FF_MT_THS: u8      = 0x17;
const REG_FF_MT_COUNT: u8    = 0x18;
const REG_TRANSIENT_CFG: u8  = 0x1D;
const REG_TRANSIENT_SRC: u8  = 0x1E;
const REG_TRANSIENT_THS: u8  = 0x1F;
const REG_TRANSIENT_COUNT: u8 = 0x20;
const REG_PULSE_CFG: u8      = 0x21;
const REG_PULSE_SRC: u8      = 0x22;
const REG_PULSE_THSX: u8     = 0x23;
const REG_PULSE_THSY: u8     = 0x24;
const REG_PULSE_THSZ: u8     = 0x25;
const REG_PULSE_TMLT: u8     = 0x26;
const REG_PULSE_LTCY: u8     = 0x27;
const REG_PULSE_WIND: u8     = 0x28;
const REG_ASLP_COUNT: u8     = 0x29;
const REG_CTRL_REG1: u8      = 0x2A;
const REG_CTRL_REG2: u8      = 0x2B;
const REG_CTRL_REG3: u8      = 0x2C;
const REG_CTRL_REG4: u8      = 0x2D;
const REG_CTRL_REG5: u8      = 0x2E;
const REG_OFF_X: u8          = 0x2F;
const REG_OFF_Y: u8          = 0x30;
const REG_OFF_Z: u8          = 0x31;

/// Expected `WHO_AM_I` value.
const WHO_AM_I_VALUE: u8 = 0x1A;

// CTRL_REG1 bits.
const CTRL1_ACTIVE: u8    = 0x01;
const CTRL1_F_READ: u8    = 0x02;
const CTRL1_LNOISE: u8    = 0x04;
const CTRL1_DR_SHIFT: u8  = 3;
const CTRL1_ASLP_RATE_SHIFT: u8 = 6;

// CTRL_REG2 bits.
const CTRL2_RST: u8     = 0x40;
const CTRL2_ST: u8      = 0x80;
const CTRL2_SLPE: u8    = 0x04;
const CTRL2_MODS_SHIFT: u8 = 0;
const CTRL2_SMODS_SHIFT: u8 = 3;

// CTRL_REG3 bits.
const CTRL3_FIFO_GATE: u8 = 0x80;
const CTRL3_IPOL: u8     = 0x02;
const CTRL3_PP_OD: u8    = 0x01;

/// Interrupt source: new XYZ sample.
pub const SOURCE_DATA_READY: u8 = 0x01;
/// Interrupt source: freefall / motion.
pub const SOURCE_FF_MT: u8 = 0x04;
/// Interrupt source: single / double tap.
pub const SOURCE_PULSE: u8 = 0x08;
/// Interrupt source: orientation change.
pub const SOURCE_ORIENTATION: u8 = 0x10;
/// Interrupt source: transient.
pub const SOURCE_TRANSIENT: u8 = 0x20;
/// Interrupt source: FIFO overflow or watermark.
pub const SOURCE_FIFO: u8 = 0x40;
/// Interrupt source: WAKE ↔ SLEEP transition.
pub const SOURCE_AUTO_SLEEP: u8 = 0x80;

/// Maximum number of samples in the hardware FIFO.
const FIFO_DEPTH: usize = 32;

/// Threshold resolution: 0.063 *g*/LSB.
const THRESHOLD_LSB_G: f32 = 0.063;

/// Offset resolution: 2 mg/LSB.
const OFFSET_LSB_G: f32 = 0.002;

/// Output data rates and their `CTRL_REG1.DR` codes.
const ODR_RATES: [f32; 8] = [800.0, 400.0, 200.0, 100.0, 50.0, 12.5, 6.25, 1.56];

/// Debounce step (ms) per ODR and oversampling mode (normal, low-noise
/// low-power, high resolution, low power) — datasheet Table 27.
const DEBOUNCE_STEPS: [[f32; 4]; 8] = [
    [1.25, 1.25, 1.25, 1.25],
    [2.5, 2.5, 2.5, 2.5],
    [5.0, 5.0, 2.5, 5.0],
    [10.0, 10.0, 2.5, 10.0],
    [20.0, 20.0, 2.5, 20.0],
    [20.0, 80.0, 2.5, 80.0],
    [20.0, 80.0, 2.5, 160.0],
    [20.0, 80.0, 2.5, 160.0],
];

/// Pulse time-limit step (ms) per ODR and oversampling mode, `Pulse_LPF_EN` = 1.
const PULSE_TMLT_STEPS_LPF: [[f32; 4]; 8] = [
    [1.25, 1.25, 1.25, 1.25],
    [2.5, 2.5, 2.5, 2.5],
    [5.0, 5.0, 2.5, 5.0],
    [10.0, 10.0, 2.5, 10.0],
    [20.0, 20.0, 2.5, 20.0],
    [20.0, 80.0, 2.5, 80.0],
    [20.0, 80.0, 2.5, 160.0],
    [20.0, 80.0, 2.5, 160.0],
];

/// Pulse time-limit step (ms) per ODR and oversampling mode, `Pulse_LPF_EN` = 0.
const PULSE_TMLT_STEPS_NO_LPF: [[f32; 4]; 8] = [
    [0.625, 0.625, 0.625, 0.625],
    [0.625, 0.625, 0.625, 1.25],
    [1.25, 1.25, 0.625, 2.5],
    [2.5, 2.5, 0.625, 5.0],
    [5.0, 5.0, 0.625, 10.0],
    [5.0, 20.0, 0.625, 40.0],
    [5.0, 20.0, 0.625, 40.0],
    [5.0, 20.0, 0.625, 40.0],
];

/// Pulse latency / window step (ms) per ODR and oversampling mode, `Pulse_LPF_EN` = 1.
const PULSE_LTCY_STEPS_LPF: [[f32; 4]; 8] = [
    [2.5, 2.5, 2.5, 2.5],
    [5.0, 5.0, 5.0, 5.0],
    [10.0, 10.0, 5.0, 10.0],
    [20.0, 20.0, 5.0, 20.0],
    [40.0, 40.0, 5.0, 40.0],
    [40.0, 160.0, 5.0, 160.0],
    [40.0, 160.0, 5.0, 320.0],
    [40.0, 160.0, 5.0, 320.0],
];

/// Pulse latency / window step (ms) per ODR and oversampling mode, `Pulse_LPF_EN` = 0.
const PULSE_LTCY_STEPS_NO_LPF: [[f32; 4]; 8] = [
    [1.25, 1.25, 1.25, 1.25],
    [1.25, 1.25, 1.25, 2.5],
    [2.5, 2.5, 1.25, 5.0],
    [5.0, 5.0, 1.25, 10.0],
    [10.0, 10.0, 1.25, 20.0],
    [10.0, 40.0, 1.25, 80.0],
    [10.0, 40.0, 1.25, 80.0],
    [10.0, 40.0, 1.25, 80.0],
];

/// Orientation trip angles and their `P_L_THS` codes — datasheet Table 32.
const ORIENTATION_THRESHOLDS: [(u8, u8); 10] = [
    (15, 0x07),
    (20, 0x09),
    (30, 0x0C),
    (35, 0x0D),
    (40, 0x0F),
    (45, 0x10),
    (55, 0x13),
    (60, 0x14),
    (70, 0x17),
    (75, 0x19),
];

/// Self-test typical count deltas (X, Y, Z).
const SELF_TEST_TYPICAL: [i16; 3] = [181, 255, 1680];

/// MMA8451Q minimal driver — 3-axis acceleration in *g*.
///
/// Runs the initialization sequence at construction: verifies `WHO_AM_I`,
/// performs a software reset, selects ±2 *g*, 14-bit output, 100 Hz and
/// normal oversampling, then activates the device.
pub struct Mma8451QMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    range_g: u8,
    odr_index: usize,
    resolution_bits: u8,
    oversampling: u8,
    sleep_oversampling: u8,
    low_noise: bool,
    fifo_mode: u8,
}

impl<I2C: I2c> Mma8451QMinimal<I2C> {
    /// Create a new `Mma8451QMinimal` and run the initialization sequence.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit I²C address (`0x1C` with SA0 = GND, `0x1D` with SA0 = VDDIO).
    ///
    /// # Errors
    /// Returns the bus error if any register access fails. A wrong
    /// `WHO_AM_I` value panics, since it indicates that no MMA8451Q is present.
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        // WHO_AM_I check.
        let who = read_reg8(&mut i2c, addr, REG_WHO_AM_I)?;
        if who != WHO_AM_I_VALUE {
            panic!("MMA8451Q WHO_AM_I: expected 0x{:02X}, got 0x{:02X}",
                   WHO_AM_I_VALUE, who);
        }

        // Software reset. The I²C interface is reset by the write, so the
        // write may NACK — ignore the error and poll CTRL_REG2 until it
        // reads back as 0 (up to 10 attempts).
        let _ = write_reg(&mut i2c, addr, REG_CTRL_REG2, CTRL2_RST);
        for _ in 0..10 {
            if let Ok(v) = read_reg8(&mut i2c, addr, REG_CTRL_REG2) {
                if v == 0 { break; }
            }
        }

        // ±2 g, no high-pass output.
        write_reg(&mut i2c, addr, REG_XYZ_DATA_CFG, 0x00)?;
        // Normal oversampling in WAKE and SLEEP, Auto-SLEEP off.
        write_reg(&mut i2c, addr, REG_CTRL_REG2, 0x00)?;
        // DR = 011 (100 Hz), ACTIVE = 1.
        write_reg(&mut i2c, addr, REG_CTRL_REG1, 0x19)?;

        Ok(Self {
            i2c,
            addr,
            range_g: 2,
            odr_index: 3,
            resolution_bits: 14,
            oversampling: 0,
            sleep_oversampling: 0,
            low_noise: false,
            fifo_mode: 0,
        })
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns (x, y, z) in *g*. In 14-bit mode this is one 6-byte read of
    /// registers `0x01`–`0x06`; in 8-bit fast-read mode only the three MSB
    /// registers are read.
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let (rx, ry, rz) = self.read_raw()?;
        let scale = self.counts_per_g();
        Ok((rx as f32 / scale, ry as f32 / scale, rz as f32 / scale))
    }

    /// Read raw signed acceleration counts at the current resolution.
    ///
    /// Returns (x, y, z) in counts: right-aligned 14-bit values in normal
    /// mode, signed 8-bit values in fast-read mode.
    pub fn read_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        if self.resolution_bits == 8 {
            let mut raw = [0u8; 3];
            read_register(&mut self.i2c, self.addr, REG_OUT_X_MSB as u32, 1, &mut raw)?;
            Ok((raw[0] as i8 as i16, raw[1] as i8 as i16, raw[2] as i8 as i16))
        } else {
            let mut raw = [0u8; 6];
            read_register(&mut self.i2c, self.addr, REG_OUT_X_MSB as u32, 1, &mut raw)?;
            Ok((
                decode_14bit(&raw[0..2]),
                decode_14bit(&raw[2..4]),
                decode_14bit(&raw[4..6]),
            ))
        }
    }

    /// Counts per *g* at the current range and resolution.
    pub(crate) fn counts_per_g(&self) -> f32 {
        let base = match self.range_g {
            4 => 2048.0,
            8 => 1024.0,
            _ => 4096.0,
        };
        if self.resolution_bits == 8 { base / 64.0 } else { base }
    }
}

/// MMA8451Q full driver — extends the minimal driver with the complete chip
/// feature set.
///
/// Re-exports [`Mma8451QMinimal::read`] and [`Mma8451QMinimal::read_raw`] as
/// one-line delegates (the Rust equivalent of "Full never duplicates
/// Minimal"), then adds the Full-only methods below.
pub struct Mma8451QFull<I2C> {
    inner: Mma8451QMinimal<I2C>,
}

impl<I2C: I2c> Mma8451QFull<I2C> {
    /// Create a new `Mma8451QFull`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (`0x1C` or `0x1D`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Mma8451QMinimal::new(i2c, addr)?;
        Ok(Self { inner })
    }

    /// Read 3-axis linear acceleration in *g*.
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read()
    }

    /// Read raw signed acceleration counts.
    pub fn read_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        self.inner.read_raw()
    }

    /// Set the full-scale range to ±2, ±4 or ±8 *g*.
    ///
    /// # Arguments
    /// * `range_g` — 2, 4 or 8. Any other value is ignored.
    ///
    /// ±8 *g* is rejected while low-noise mode is enabled because the chip
    /// limits the low-noise range to ±4 *g*.
    pub fn set_range(&mut self, range_g: u8) -> Result<(), I2C::Error> {
        let fs = match range_g {
            2 => 0x00,
            4 => 0x01,
            8 => 0x02,
            _ => return Ok(()),
        };
        if range_g == 8 && self.inner.low_noise {
            panic!("MMA8451Q: ±8 g is not available with low-noise mode enabled");
        }
        self.standby_write(REG_XYZ_DATA_CFG, fs)?;
        self.inner.range_g = range_g;
        Ok(())
    }

    /// Set the output data rate.
    ///
    /// # Arguments
    /// * `rate_hz` — one of 800, 400, 200, 100, 50, 12.5, 6.25 or 1.56 Hz.
    ///   The nearest supported rate is used.
    pub fn set_data_rate(&mut self, rate_hz: f32) -> Result<(), I2C::Error> {
        let idx = nearest_odr(rate_hz);
        let dr = (idx as u8) << CTRL1_DR_SHIFT;
        self.standby_modify_ctrl1(|ctrl1| {
            (ctrl1 & !(0x07 << CTRL1_DR_SHIFT)) | dr
        })?;
        self.inner.odr_index = idx;
        Ok(())
    }

    /// Set the oversampling mode used in WAKE / ACTIVE (and optionally SLEEP).
    ///
    /// # Arguments
    /// * `mode` — 0 normal, 1 low-noise low-power, 2 high resolution, 3 low power.
    /// * `sleep_mode` — optional SLEEP oversampling mode with the same encoding.
    pub fn set_oversampling(&mut self, mode: u8, sleep_mode: Option<u8>) -> Result<(), I2C::Error> {
        if mode > 3 { return Ok(()); }
        if let Some(sm) = sleep_mode {
            if sm > 3 { return Ok(()); }
        }
        self.standby_modify(REG_CTRL_REG2, |i2c, addr| {
            let ctrl2 = read_reg8(i2c, addr, REG_CTRL_REG2)?;
            let mut out = (ctrl2 & !(0x03 << CTRL2_MODS_SHIFT)) | (mode << CTRL2_MODS_SHIFT);
            if let Some(sm) = sleep_mode {
                out = (out & !(0x03 << CTRL2_SMODS_SHIFT)) | (sm << CTRL2_SMODS_SHIFT);
            }
            Ok(out)
        })?;
        self.inner.oversampling = mode;
        if let Some(sm) = sleep_mode {
            self.inner.sleep_oversampling = sm;
        }
        Ok(())
    }

    /// Enable or disable the reduced-noise (low-noise) mode.
    ///
    /// Low-noise mode limits the dynamic range to ±4 *g*, so enabling it while
    /// the range is ±8 *g* panics.
    pub fn set_low_noise(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        if enabled && self.inner.range_g == 8 {
            panic!("MMA8451Q: low-noise mode is limited to ±4 g");
        }
        self.standby_modify_ctrl1(|ctrl1| {
            if enabled { ctrl1 | CTRL1_LNOISE } else { ctrl1 & !CTRL1_LNOISE }
        })?;
        self.inner.low_noise = enabled;
        Ok(())
    }

    /// Select the output resolution: 14 bits or 8 bits (fast read).
    ///
    /// 8-bit mode is rejected while the FIFO is enabled.
    pub fn set_resolution(&mut self, bits: u8) -> Result<(), I2C::Error> {
        if bits != 8 && bits != 14 { return Ok(()); }
        if bits == 8 && self.inner.fifo_mode != 0 {
            panic!("MMA8451Q: fast read (8-bit) cannot be used while the FIFO is enabled");
        }
        self.standby_modify_ctrl1(|ctrl1| {
            if bits == 8 { ctrl1 | CTRL1_F_READ } else { ctrl1 & !CTRL1_F_READ }
        })?;
        self.inner.resolution_bits = bits;
        Ok(())
    }

    /// Put the device into STANDBY mode (`CTRL_REG1.ACTIVE` = 0).
    pub fn standby(&mut self) -> Result<(), I2C::Error> {
        let ctrl1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1)?;
        self.write_ctrl1(ctrl1 & !CTRL1_ACTIVE)
    }

    /// Activate the device (`CTRL_REG1.ACTIVE` = 1).
    ///
    /// The caller must wait 2/ODR + 2 ms (22 ms at 100 Hz) before the first
    /// sample is valid — this driver has no delay provider.
    pub fn activate(&mut self) -> Result<(), I2C::Error> {
        let ctrl1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1)?;
        self.write_ctrl1(ctrl1 | CTRL1_ACTIVE)
    }

    /// Perform a software reset.
    ///
    /// The chip returns to STANDBY with all registers at their reset values;
    /// re-run the initialization sequence afterwards.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        let _ = write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, CTRL2_RST);
        for _ in 0..10 {
            if let Ok(v) = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2) {
                if v == 0 { break; }
            }
        }
        Ok(())
    }

    /// Return true when a new XYZ sample is available (`STATUS.ZYXDR`).
    ///
    /// Not valid while the FIFO is enabled — use [`Self::fifo_level`] then.
    pub fn new_data_available(&mut self) -> Result<bool, I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)?;
        Ok((status & 0x08) != 0)
    }

    /// Read the raw `STATUS` register byte.
    pub fn read_status(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)
    }

    /// Read the current system mode: 0 STANDBY, 1 WAKE, 2 SLEEP.
    pub fn read_system_mode(&mut self) -> Result<u8, I2C::Error> {
        let sysmod = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_SYSMOD)?;
        Ok(sysmod & 0x03)
    }

    /// Enable or disable the high-pass-filtered data output.
    ///
    /// # Arguments
    /// * `enabled` — `HPF_OUT` bit.
    /// * `cutoff` — `SEL` code 0…3 (the cutoff in Hz depends on the ODR and
    ///   oversampling mode; the datasheet Table 23 lists the values).
    pub fn set_high_pass_output(&mut self, enabled: bool, cutoff: u8) -> Result<(), I2C::Error> {
        let sel = cutoff & 0x03;
        self.standby_modify(REG_XYZ_DATA_CFG, |i2c, addr| {
            let cfg = read_reg8(i2c, addr, REG_XYZ_DATA_CFG)?;
            let mut out = (cfg & !0x10) | sel;
            if enabled { out |= 0x10; }
            Ok(out)
        })
    }

    /// Set the per-axis offset correction registers.
    ///
    /// # Arguments
    /// * `x_g`, `y_g`, `z_g` — offsets in *g*; each is converted with 2 mg/LSB
    ///   and clamped to −128…127 (±0.256 *g*).
    pub fn set_offset(&mut self, x_g: f32, y_g: f32, z_g: f32) -> Result<(), I2C::Error> {
        let x = offset_code(x_g);
        let y = offset_code(y_g);
        let z = offset_code(z_g);
        self.standby_write(REG_OFF_X, x)?;
        self.standby_write(REG_OFF_Y, y)?;
        self.standby_write(REG_OFF_Z, z)
    }

    /// Read the current per-axis offsets in *g*.
    pub fn get_offset(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let x = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_OFF_X)? as i8;
        let y = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_OFF_Y)? as i8;
        let z = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_OFF_Z)? as i8;
        Ok((
            x as f32 * OFFSET_LSB_G,
            y as f32 * OFFSET_LSB_G,
            z as f32 * OFFSET_LSB_G,
        ))
    }

    /// Configure the hardware FIFO.
    ///
    /// # Arguments
    /// * `mode` — 0 disabled, 1 circular, 2 fill, 3 trigger.
    /// * `watermark` — FIFO watermark 0…31 (0 disables the flag); in trigger
    ///   mode this is the number of pre-trigger samples.
    ///
    /// The mode always passes through 0 so that switching between enabled
    /// modes works as required by the datasheet.
    pub fn set_fifo(&mut self, mode: u8, watermark: u8) -> Result<(), I2C::Error> {
        let mode = mode & 0x03;
        let wm = watermark & 0x1F;
        // Disable first, then enable the requested mode.
        self.standby_write(REG_F_SETUP, 0x00)?;
        self.inner.fifo_mode = 0;
        if mode != 0 {
            let setup = (mode << 6) | wm;
            self.standby_write(REG_F_SETUP, setup)?;
            self.inner.fifo_mode = mode;
        }
        Ok(())
    }

    /// Select the FIFO trigger sources (`TRIG_CFG`).
    ///
    /// # Arguments
    /// * `transient` — transient detector may trigger the FIFO.
    /// * `orientation` — orientation detector may trigger the FIFO.
    /// * `pulse` — tap detector may trigger the FIFO.
    /// * `motion` — freefall / motion detector may trigger the FIFO.
    pub fn set_fifo_trigger(&mut self, transient: bool, orientation: bool,
                            pulse: bool, motion: bool) -> Result<(), I2C::Error> {
        let mut out = 0u8;
        if transient { out |= 0x20; }
        if orientation { out |= 0x10; }
        if pulse { out |= 0x08; }
        if motion { out |= 0x04; }
        self.standby_write(REG_TRIG_CFG, out)
    }

    /// Return the number of samples currently in the FIFO (`F_CNT`, 0…32).
    pub fn fifo_level(&mut self) -> Result<u8, I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)?;
        Ok(status & 0x3F)
    }

    /// Read the decoded FIFO status: (overflow, watermark, count).
    ///
    /// Reading `F_STATUS` also clears the FIFO interrupt source.
    pub fn read_fifo_status(&mut self) -> Result<(bool, bool, u8), I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)?;
        Ok(((status & 0x80) != 0, (status & 0x40) != 0, status & 0x3F))
    }

    /// Read samples from the FIFO.
    ///
    /// # Arguments
    /// * `count` — maximum number of samples to read; `None` reads all
    ///   samples currently in the FIFO.
    ///
    /// The FIFO holds at most 32 samples, so the result is a fixed-capacity
    /// `heapless::Vec` rather than a heap-backed one — this crate is `no_std`.
    pub fn read_fifo(&mut self, count: Option<usize>) -> Result<heapless::Vec<(f32, f32, f32), 32>, I2C::Error> {
        let level = self.fifo_level()? as usize;
        let n = match count {
            Some(c) => c.min(level).min(FIFO_DEPTH),
            None => level.min(FIFO_DEPTH),
        };
        let mut out = heapless::Vec::new();
        if n == 0 { return Ok(out); }
        let scale = self.inner.counts_per_g();
        for _ in 0..n {
            let (rx, ry, rz) = self.inner.read_raw()?;
            let _ = out.push((rx as f32 / scale, ry as f32 / scale, rz as f32 / scale));
        }
        Ok(out)
    }

    /// Configure the freefall / motion detector.
    ///
    /// # Arguments
    /// * `motion` — false = freefall (all enabled axes below threshold),
    ///   true = motion (any enabled axis above threshold).
    /// * `axes` — axis mask, X = 1, Y = 2, Z = 4.
    /// * `threshold_g` — threshold in *g* (0.063 *g*/LSB).
    /// * `debounce_ms` — debounce time in ms.
    /// * `latch` — latch the source flags (`ELE`).
    /// * `debounce_clear` — clear the debounce counter when the condition is
    ///   lost (`DBCNTM`).
    pub fn set_freefall_motion(&mut self, motion: bool, axes: u8, threshold_g: f32,
                               debounce_ms: f32, latch: bool, debounce_clear: bool)
                               -> Result<(), I2C::Error> {
        // FF_MT_CFG: ELE 7, OAE 6, ZEFE 5, YEFE 4, XEFE 3.
        let mut cfg = (axes & 0x07) << 3;
        if motion { cfg |= 0x40; }
        if latch { cfg |= 0x80; }
        self.standby_write(REG_FF_MT_CFG, cfg)?;
        let ths = threshold_code(threshold_g);
        let count = debounce_count(debounce_ms, self.inner.odr_index, self.inner.oversampling);
        // DBCNTM (bit 7 of FF_MT_THS) clears the debounce counter when the
        // condition is lost.
        let ths = if debounce_clear { ths | 0x80 } else { ths };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FF_MT_THS, ths)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FF_MT_COUNT, count)
    }

    /// Read the `FF_MT_SRC` byte (the latched flags when `ELE` = 1).
    pub fn read_motion_source(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_FF_MT_SRC)
    }

    /// Configure the transient (high-pass filtered motion) detector.
    ///
    /// # Arguments
    /// * `axes` — axis mask, X = 1, Y = 2, Z = 4.
    /// * `threshold_g` — threshold in *g* (0.063 *g*/LSB).
    /// * `debounce_ms` — debounce time in ms.
    /// * `bypass_hpf` — bypass the high-pass filter (behaves like motion).
    /// * `latch` — latch the source flags (`ELE`).
    pub fn set_transient(&mut self, axes: u8, threshold_g: f32, debounce_ms: f32,
                         bypass_hpf: bool, latch: bool) -> Result<(), I2C::Error> {
        // TRANSIENT_CFG: ELE 4, ZTEFE 3, YTEFE 2, XTEFE 1, HPF_BYP 0.
        let mut cfg = (axes & 0x07) << 1;
        if latch { cfg |= 0x10; }
        if bypass_hpf { cfg |= 0x01; }
        self.standby_write(REG_TRANSIENT_CFG, cfg)?;
        let ths = threshold_code(threshold_g);
        let count = debounce_count(debounce_ms, self.inner.odr_index, self.inner.oversampling);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TRANSIENT_THS, ths)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TRANSIENT_COUNT, count)
    }

    /// Read the `TRANSIENT_SRC` byte — clears the transient interrupt.
    pub fn read_transient_source(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_TRANSIENT_SRC)
    }

    /// Configure the tap (pulse) detector.
    ///
    /// # Arguments
    /// * `single` — single-tap axis mask, X = 1, Y = 2, Z = 4.
    /// * `double` — double-tap axis mask, X = 1, Y = 2, Z = 4.
    /// * `threshold_g` — (x, y, z) thresholds in *g* (0.063 *g*/LSB).
    /// * `time_limit_ms` — pulse time limit in ms.
    /// * `latency_ms` — pulse latency in ms.
    /// * `window_ms` — second-pulse window in ms.
    /// * `latch` — latch the source flags (`ELE`).
    /// * `double_abort` — abort double-tap detection on a pulse in the
    ///   latency window (`DPA`).
    /// * `lpf` — enable the low-pass filter for the pulse detector.
    /// * `hpf` — enable the high-pass filter for the pulse detector.
    pub fn set_pulse(&mut self, single: u8, double: u8, threshold_g: (f32, f32, f32),
                     time_limit_ms: f32, latency_ms: f32, window_ms: f32,
                     latch: bool, double_abort: bool, lpf: bool, hpf: bool)
                     -> Result<(), I2C::Error> {
        // PULSE_CFG: DPA 7, ELE 6, ZDPEFE 5, ZSPEFE 4, YDPEFE 3, YSPEFE 2,
        // XDPEFE 1, XSPEFE 0.
        let mut cfg = 0u8;
        cfg |= single & 0x01;        // XSPEFE  — bit 0
        cfg |= (single & 0x02) << 1;  // YSPEFE  — bit 2
        cfg |= (single & 0x04) << 2;  // ZSPEFE  — bit 4
        cfg |= (double & 0x01) << 1;  // XDPEFE  — bit 1
        cfg |= (double & 0x02) << 2;  // YDPEFE  — bit 3
        cfg |= (double & 0x04) << 3;  // ZDPEFE  — bit 5
        if latch { cfg |= 0x40; }        // ELE  — bit 6
        if double_abort { cfg |= 0x80; } // DPA  — bit 7
        self.standby_write(REG_PULSE_CFG, cfg)?;

        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PULSE_THSX, threshold_code(threshold_g.0))?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PULSE_THSY, threshold_code(threshold_g.1))?;
        self.standby_write(REG_PULSE_THSZ, threshold_code(threshold_g.2))?;

        let odr = self.inner.odr_index;
        let mode = self.inner.oversampling as usize;
        let tmlt_step = if lpf {
            PULSE_TMLT_STEPS_LPF[odr][mode]
        } else {
            PULSE_TMLT_STEPS_NO_LPF[odr][mode]
        };
        let ltcy_step = if lpf {
            PULSE_LTCY_STEPS_LPF[odr][mode]
        } else {
            PULSE_LTCY_STEPS_NO_LPF[odr][mode]
        };
        self.standby_write(REG_PULSE_TMLT, time_code(time_limit_ms, tmlt_step))?;
        self.standby_write(REG_PULSE_LTCY, time_code(latency_ms, ltcy_step))?;
        self.standby_write(REG_PULSE_WIND, time_code(window_ms, ltcy_step))?;

        // Pulse_HPF_BYP (bit 5) and Pulse_LPF_EN (bit 4).
        self.standby_modify(REG_HP_FILTER_CUTOFF, |i2c, addr| {
            let hp = read_reg8(i2c, addr, REG_HP_FILTER_CUTOFF)?;
            let mut out = hp & !0x30;
            if lpf { out |= 0x10; }
            if !hpf { out |= 0x20; }
            Ok(out)
        })
    }

    /// Read the `PULSE_SRC` byte — clears the tap interrupt.
    pub fn read_pulse_source(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_PULSE_SRC)
    }

    /// Configure the portrait / landscape orientation detector.
    ///
    /// # Arguments
    /// * `enabled` — enable the detector (`PL_EN`).
    /// * `debounce_ms` — debounce time in ms.
    /// * `threshold_deg` — trip angle; one of 15, 20, 30, 35, 40, 45, 55, 60,
    ///   70 or 75 degrees.
    /// * `hysteresis` — hysteresis code 0…7.
    /// * `z_lock` — Z-lock angle code 0…7.
    /// * `back_front` — back/front angle code 0…3.
    pub fn set_orientation(&mut self, enabled: bool, debounce_ms: f32, threshold_deg: u8,
                           hysteresis: u8, z_lock: u8, back_front: u8)
                           -> Result<(), I2C::Error> {
        let ths = match orientation_threshold(threshold_deg) {
            Some(v) => v,
            None => panic!("MMA8451Q: invalid orientation threshold angle {} deg", threshold_deg),
        };
        let hys = hysteresis & 0x07;
        if ths + hys < 1 || ths + hys > 31 {
            panic!("MMA8451Q: orientation threshold + hysteresis out of range");
        }
        let mut cfg = 0u8;
        if enabled { cfg |= 0x40; }
        self.standby_write(REG_PL_CFG, cfg)?;
        let count = debounce_count(debounce_ms, self.inner.odr_index, self.inner.oversampling);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PL_COUNT, count)?;
        let ths_reg = (ths << 3) | hys;
        self.standby_write(REG_PL_THS, ths_reg)?;
        let zcomp = ((back_front & 0x03) << 6) | (z_lock & 0x07);
        self.standby_write(REG_PL_BF_ZCOMP, zcomp)
    }

    /// Read the decoded orientation status.
    ///
    /// Returns (changed, lockout, portrait_landscape, back):
    /// * `changed` — `NEWLP`, a new orientation was detected.
    /// * `lockout` — `LO`, the orientation is frozen.
    /// * `portrait_landscape` — 0 portrait up, 1 portrait down,
    ///   2 landscape right, 3 landscape left.
    /// * `back` — `BAFRO`, the device is face-down.
    ///
    /// Reading `PL_STATUS` clears the `NEWLP` flag.
    pub fn read_orientation(&mut self) -> Result<(bool, bool, u8, bool), I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_PL_STATUS)?;
        let changed = (status & 0x80) != 0;
        let lockout = (status & 0x40) != 0;
        let lapo = (status >> 1) & 0x03;
        let back = (status & 0x01) != 0;
        Ok((changed, lockout, lapo, back))
    }

    /// Configure Auto-SLEEP / Auto-WAKE.
    ///
    /// # Arguments
    /// * `enabled` — enable Auto-SLEEP (`SLPE`).
    /// * `sleep_rate_hz` — sleep-mode ODR: 50, 12.5, 6.25 or 1.56 Hz.
    /// * `timeout_s` — inactivity timeout in seconds (0…81 s, or 0…162 s at
    ///   1.56 Hz).
    /// * `wake_sources` — mask of sources that may wake the device; use
    ///   [`SOURCE_TRANSIENT`], [`SOURCE_ORIENTATION`], [`SOURCE_PULSE`] and
    ///   [`SOURCE_FF_MT`].
    pub fn set_auto_sleep(&mut self, enabled: bool, sleep_rate_hz: f32, timeout_s: f32,
                           wake_sources: u8) -> Result<(), I2C::Error> {
        let aslp_rate = match sleep_rate_hz {
            50.0 => 0u8,
            12.5 => 1,
            6.25 => 2,
            1.56 => 3,
            _ => 0,
        };
        self.standby_modify_ctrl1(|ctrl1| {
            (ctrl1 & !(0x03 << CTRL1_ASLP_RATE_SHIFT))
                | (aslp_rate << CTRL1_ASLP_RATE_SHIFT)
        })?;

        self.standby_modify(REG_CTRL_REG2, |i2c, addr| {
            let ctrl2 = read_reg8(i2c, addr, REG_CTRL_REG2)?;
            if enabled { Ok(ctrl2 | CTRL2_SLPE) } else { Ok(ctrl2 & !CTRL2_SLPE) }
        })?;

        let step_s = if self.inner.odr_index >= 6 { 0.64 } else { 0.32 };
        let count = clamp_u8(libm::roundf(timeout_s / step_s) as i32);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ASLP_COUNT, count)?;

        // CTRL_REG3 wake sources: WAKE_TRANS 6, WAKE_LNDPRT 5, WAKE_PULSE 4,
        // WAKE_FF_MT 3.
        self.standby_modify(REG_CTRL_REG3, |i2c, addr| {
            let ctrl3 = read_reg8(i2c, addr, REG_CTRL_REG3)?;
            let mut out3 = ctrl3 & !0x78;
            if wake_sources & SOURCE_TRANSIENT != 0 { out3 |= 0x40; }
            if wake_sources & SOURCE_ORIENTATION != 0 { out3 |= 0x20; }
            if wake_sources & SOURCE_PULSE != 0 { out3 |= 0x10; }
            if wake_sources & SOURCE_FF_MT != 0 { out3 |= 0x08; }
            Ok(out3)
        })
    }

    /// Enable or disable the FIFO gate around WAKE/SLEEP transitions.
    pub fn set_fifo_gate(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.standby_modify(REG_CTRL_REG3, |i2c, addr| {
            let ctrl3 = read_reg8(i2c, addr, REG_CTRL_REG3)?;
            if enabled { Ok(ctrl3 | CTRL3_FIFO_GATE) } else { Ok(ctrl3 & !CTRL3_FIFO_GATE) }
        })
    }

    /// Configure the interrupt pin polarity and drive.
    ///
    /// # Arguments
    /// * `active_high` — `IPOL`; false (default) means active-low.
    /// * `open_drain` — `PP_OD`; true means open-drain, false push-pull.
    pub fn set_interrupt_pins(&mut self, active_high: bool, open_drain: bool) -> Result<(), I2C::Error> {
        self.standby_modify(REG_CTRL_REG3, |i2c, addr| {
            let ctrl3 = read_reg8(i2c, addr, REG_CTRL_REG3)?;
            let mut out = ctrl3 & !(CTRL3_IPOL | CTRL3_PP_OD);
            if active_high { out |= CTRL3_IPOL; }
            if open_drain { out |= CTRL3_PP_OD; }
            Ok(out)
        })
    }

    /// Enable one interrupt source and route it to INT`pin`.
    ///
    /// # Arguments
    /// * `source` — one of the `SOURCE_*` constants.
    /// * `pin` — 1 for INT1, 2 for INT2.
    pub fn enable_interrupt(&mut self, source: u8, pin: u8) -> Result<(), I2C::Error> {
        // Valid sources: DRDY (0x01), FF_MT (0x04), PULSE (0x08),
        // LNDPRT (0x10), TRANS (0x20), FIFO (0x40), ASLP (0x80).
        if source == 0 || source & !0x8F != 0 {
            panic!("MMA8451Q: invalid interrupt source 0x{:02X}", source);
        }
        self.standby_modify(REG_CTRL_REG4, |i2c, addr| {
            let ctrl4 = read_reg8(i2c, addr, REG_CTRL_REG4)?;
            Ok(ctrl4 | source)
        })?;

        self.standby_modify(REG_CTRL_REG5, |i2c, addr| {
            let ctrl5 = read_reg8(i2c, addr, REG_CTRL_REG5)?;
            if pin == 1 { Ok(ctrl5 | source) } else { Ok(ctrl5 & !source) }
        })
    }

    /// Disable one interrupt source.
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        self.standby_modify(REG_CTRL_REG4, |i2c, addr| {
            let ctrl4 = read_reg8(i2c, addr, REG_CTRL_REG4)?;
            Ok(ctrl4 & !source)
        })
    }

    /// Read and clear the interrupt status (`INT_SOURCE`).
    ///
    /// For every set bit except [`SOURCE_DATA_READY`] the matching source
    /// register is read as well so the flag (and the INT pin) clears.
    /// [`SOURCE_DATA_READY`] stays set — read the data to clear it.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_SOURCE)?;
        if status & SOURCE_FIFO != 0 {
            read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)?;
        }
        if status & SOURCE_FF_MT != 0 {
            read_reg8(&mut self.inner.i2c, self.inner.addr, REG_FF_MT_SRC)?;
        }
        if status & SOURCE_PULSE != 0 {
            read_reg8(&mut self.inner.i2c, self.inner.addr, REG_PULSE_SRC)?;
        }
        if status & SOURCE_ORIENTATION != 0 {
            read_reg8(&mut self.inner.i2c, self.inner.addr, REG_PL_STATUS)?;
        }
        if status & SOURCE_TRANSIENT != 0 {
            read_reg8(&mut self.inner.i2c, self.inner.addr, REG_TRANSIENT_SRC)?;
        }
        if status & SOURCE_AUTO_SLEEP != 0 {
            read_reg8(&mut self.inner.i2c, self.inner.addr, REG_SYSMOD)?;
        }
        Ok(status)
    }

    /// Run the electrostatic self-test.
    ///
    /// Puts the device in STANDBY, selects ±4 *g* at 100 Hz with normal
    /// oversampling, averages a few samples, enables `CTRL_REG2.ST`, averages
    /// again and computes the count deltas. The previous configuration is
    /// restored afterwards.
    ///
    /// Returns (ok, dx, dy, dz). `ok` is a heuristic: the deltas must exceed
    /// half of the datasheet's typical values (X +181, Y +255, Z +1680).
    pub fn self_test(&mut self) -> Result<(bool, i16, i16, i16), I2C::Error> {
        // Remember the current configuration.
        let ctrl1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1)?;
        let ctrl2 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2)?;
        let cfg = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_XYZ_DATA_CFG)?;

        // STANDBY, ±4 g, 100 Hz (DR = 011), normal oversampling.
        let standby_ctrl1 = (ctrl1 & !CTRL1_ACTIVE) | (3u8 << CTRL1_DR_SHIFT);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, standby_ctrl1)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_XYZ_DATA_CFG, 0x01)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2,
                  ctrl2 & !(0x03 << CTRL2_MODS_SHIFT))?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, standby_ctrl1 | CTRL1_ACTIVE)?;

        let before = self.average_samples(4)?;

        // Enable the self-test.
        let ctrl2_now = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, ctrl2_now | CTRL2_ST)?;
        let after = self.average_samples(4)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, ctrl2_now & !CTRL2_ST)?;

        // Restore the previous configuration.
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, ctrl1 & !CTRL1_ACTIVE)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_XYZ_DATA_CFG, cfg)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, ctrl2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, ctrl1)?;

        let dx = (after.0 - before.0) as i16;
        let dy = (after.1 - before.1) as i16;
        let dz = (after.2 - before.2) as i16;
        let ok = dx > SELF_TEST_TYPICAL[0] / 2
            && dy > SELF_TEST_TYPICAL[1] / 2
            && dz > SELF_TEST_TYPICAL[2] / 2;
        Ok((ok, dx, dy, dz))
    }

    /// Average `n` raw samples.
    fn average_samples(&mut self, n: u8) -> Result<(i32, i32, i32), I2C::Error> {
        let mut sx = 0i32;
        let mut sy = 0i32;
        let mut sz = 0i32;
        for _ in 0..n {
            let (x, y, z) = self.inner.read_raw()?;
            sx += x as i32;
            sy += y as i32;
            sz += z as i32;
        }
        Ok((sx / n as i32, sy / n as i32, sz / n as i32))
    }

    /// Write `CTRL_REG1`, preserving the ACTIVE state of the caller.
    fn write_ctrl1(&mut self, value: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, value)
    }

    /// Write a STANDBY-only register, temporarily clearing ACTIVE and
    /// restoring it afterwards.
    fn standby_write(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.standby_modify(reg, |_, _| Ok(value))
    }

    /// Read-modify-write a STANDBY-only register.
    ///
    /// The closure receives the bus and the device address and returns the
    /// new register value. The device is put into STANDBY before the closure
    /// runs and the previous ACTIVE state is restored afterwards.
    fn standby_modify<F>(&mut self, reg: u8, f: F) -> Result<(), I2C::Error>
        where F: FnOnce(&mut I2C, u8) -> Result<u8, I2C::Error>
    {
        let addr = self.inner.addr;
        let ctrl1 = read_reg8(&mut self.inner.i2c, addr, REG_CTRL_REG1)?;
        let was_active = (ctrl1 & CTRL1_ACTIVE) != 0;
        if was_active {
            write_reg(&mut self.inner.i2c, addr, REG_CTRL_REG1, ctrl1 & !CTRL1_ACTIVE)?;
        }
        let value = f(&mut self.inner.i2c, addr)?;
        let r = write_reg(&mut self.inner.i2c, addr, reg, value);
        if was_active {
            write_reg(&mut self.inner.i2c, addr, REG_CTRL_REG1, ctrl1)?;
        }
        r
    }

    /// Write a new `CTRL_REG1` value.
    ///
    /// Only the ACTIVE bit may be written while the chip is running, so the
    /// device is briefly put into STANDBY, the new value is written and the
    /// previous ACTIVE state is restored. The closure receives the current
    /// `CTRL_REG1` value and returns the new one.
    fn standby_modify_ctrl1<F>(&mut self, f: F) -> Result<(), I2C::Error>
        where F: FnOnce(u8) -> u8
    {
        let addr = self.inner.addr;
        let ctrl1 = read_reg8(&mut self.inner.i2c, addr, REG_CTRL_REG1)?;
        if (ctrl1 & CTRL1_ACTIVE) == 0 {
            // Already in standby — write directly.
            let value = f(ctrl1);
            return write_reg(&mut self.inner.i2c, addr, REG_CTRL_REG1, value);
        }
        write_reg(&mut self.inner.i2c, addr, REG_CTRL_REG1, ctrl1 & !CTRL1_ACTIVE)?;
        let value = f(ctrl1) & !CTRL1_ACTIVE;
        let r = write_reg(&mut self.inner.i2c, addr, REG_CTRL_REG1, value);
        write_reg(&mut self.inner.i2c, addr, REG_CTRL_REG1, ctrl1)?;
        r
    }
}

/// Decode a 14-bit left-justified sample pair.
fn decode_14bit(p: &[u8]) -> i16 {
    let raw = ((p[0] as u16) << 8) | p[1] as u16;
    // The sample is left-justified in the 16-bit pair; shift the signed value
    // right by 2 to right-align the 14-bit result.
    (raw as i16) >> 2
}

/// Convert a threshold in *g* to the 7-bit register code (0.063 *g*/LSB).
fn threshold_code(threshold_g: f32) -> u8 {
    clamp_u8(libm::roundf(threshold_g / THRESHOLD_LSB_G) as i32)
}

/// Convert an offset in *g* to the signed 8-bit register code (2 mg/LSB).
fn offset_code(offset_g: f32) -> u8 {
    let code = libm::roundf(offset_g / OFFSET_LSB_G) as i32;
    if code < -128 { (-128i8) as u8 }
    else if code > 127 { 127u8 }
    else { (code as i8) as u8 }
}

/// Clamp a computed count to 0…255.
fn clamp_u8(value: i32) -> u8 {
    if value < 0 { 0 } else if value > 255 { 255 } else { value as u8 }
}

/// Convert a time in ms to a register code using the given step.
fn time_code(time_ms: f32, step_ms: f32) -> u8 {
    if step_ms <= 0.0 { return 0; }
    clamp_u8(libm::roundf(time_ms / step_ms) as i32)
}

/// Debounce count for the given time at the current ODR and oversampling mode.
fn debounce_count(debounce_ms: f32, odr_index: usize, oversampling: u8) -> u8 {
    let step = DEBOUNCE_STEPS[odr_index][oversampling as usize];
    time_code(debounce_ms, step)
}

/// Index of the output data rate closest to `rate_hz`.
fn nearest_odr(rate_hz: f32) -> usize {
    let mut best = 0usize;
    let mut best_diff = libm::fabsf(ODR_RATES[0] - rate_hz);
    for i in 1..ODR_RATES.len() {
        let d = libm::fabsf(ODR_RATES[i] - rate_hz);
        if d < best_diff {
            best = i;
            best_diff = d;
        }
    }
    best
}

/// `P_L_THS` code for an orientation trip angle.
fn orientation_threshold(deg: u8) -> Option<u8> {
    ORIENTATION_THRESHOLDS.iter()
        .find(|(d, _)| *d == deg)
        .map(|(_, v)| *v)
}

/// Read a single register byte.
fn read_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    read_register(i2c, addr, reg as u32, 1, &mut buf)?;
    Ok(buf[0])
}

/// Write a single register byte.
fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    write_register(i2c, addr, reg as u32, 1, &[value])
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x1C;

    /// Initialization transactions shared by all tests.
    fn init_transactions() -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![WHO_AM_I_VALUE]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, CTRL2_RST]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG2], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_XYZ_DATA_CFG, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x19]),
        ]
    }

    /// Build a Full driver with the init sequence plus `extra` transactions.
    fn new_full(extra: &[I2cTransaction]) -> Mma8451QFull<I2cMock> {
        let mut init = init_transactions();
        init.extend_from_slice(extra);
        let i2c = I2cMock::new(&init);
        Mma8451QFull::new(i2c, ADDR).expect("new")
    }

    /// Transactions for a STANDBY-only write while the chip is ACTIVE.
    ///
    /// The order is: read `CTRL_REG1`, drop ACTIVE, write the target
    /// register, restore ACTIVE.
    fn standby_tx(reg: u8, value: u8) -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x19]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x18]),
            I2cTransaction::write(ADDR, vec![reg, value]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x19]),
        ]
    }

    /// Transactions for a STANDBY-only `CTRL_REG1` write while ACTIVE.
    fn standby_ctrl1_tx(value: u8) -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x19]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x18]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, value & !CTRL1_ACTIVE]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x19]),
        ]
    }

    /// Transactions for a STANDBY-only read-modify-write of `reg`.
    fn standby_modify_tx(reg: u8, current: u8, value: u8) -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x19]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x18]),
            I2cTransaction::write_read(ADDR, vec![reg], vec![current]),
            I2cTransaction::write(ADDR, vec![reg, value]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x19]),
        ]
    }

    #[test]
    fn init_sequence_writes() {
        let mut tx = init_transactions();
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_OUT_X_MSB],
                                           vec![0x40, 0x00, 0x20, 0x00, 0xC0, 0x00]));
        let i2c = I2cMock::new(&tx);
        let mut chip = Mma8451QMinimal::new(i2c, ADDR).expect("new");
        let _ = chip.read();
        chip.i2c.done();
    }

    #[test]
    fn fourteen_bit_conversion() {
        let mut chip = new_full(&[
            // 1.0 g on X (4096 counts = 0x1000 → raw16 0x4000), 0.5 g on Y
            // (2048 counts = 0x0800 → raw16 0x2000), -1.0 g on Z
            // (-4096 counts = 0xF000 → raw16 0xC000).
            I2cTransaction::write_read(ADDR, vec![REG_OUT_X_MSB],
                                      vec![0x40, 0x00, 0x20, 0x00, 0xC0, 0x00]),
        ]);
        let (x, y, z) = chip.read().unwrap();
        assert!((x - 1.0).abs() < 1e-6, "x = {}", x);
        assert!((y - 0.5).abs() < 1e-6, "y = {}", y);
        assert!((z + 1.0).abs() < 1e-6, "z = {}", z);
        chip.inner.i2c.done();
    }

    #[test]
    fn read_raw_returns_counts() {
        let mut chip = new_full(&[
            I2cTransaction::write_read(ADDR, vec![REG_OUT_X_MSB],
                                      vec![0x40, 0x00, 0x20, 0x00, 0xC0, 0x00]),
        ]);
        let (x, y, z) = chip.read_raw().unwrap();
        assert_eq!(x, 4096);
        assert_eq!(y, 2048);
        assert_eq!(z, -4096);
        chip.inner.i2c.done();
    }

    #[test]
    fn set_range_selects_fs_code() {
        let mut tx = standby_tx(REG_XYZ_DATA_CFG, 0x01);
        tx.extend(standby_tx(REG_XYZ_DATA_CFG, 0x02));
        let mut chip = new_full(&tx);
        chip.set_range(4).unwrap();
        chip.set_range(8).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn set_data_rate_sets_dr_code() {
        // 800 Hz → DR = 000, so CTRL_REG1 = 0x19 & !0x38 = 0x01 (ACTIVE
        // only), written with ACTIVE cleared while in standby.
        let mut chip = new_full(&standby_ctrl1_tx(0x01));
        chip.set_data_rate(800.0).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn set_oversampling_sets_mods() {
        // MODS = 2 (high resolution) → CTRL_REG2 = 0x02.
        let mut chip = new_full(&standby_modify_tx(REG_CTRL_REG2, 0x00, 0x02));
        chip.set_oversampling(2, None).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn set_low_noise_sets_lnoise_bit() {
        // LNOISE (bit 2) → CTRL_REG1 = 0x1D.
        let mut chip = new_full(&standby_ctrl1_tx(0x1D));
        chip.set_low_noise(true).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn set_resolution_sets_f_read_bit() {
        // F_READ (bit 1) → CTRL_REG1 = 0x1B.
        let mut chip = new_full(&standby_ctrl1_tx(0x1B));
        chip.set_resolution(8).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn offsets_use_two_mg_per_lsb() {
        let mut tx = standby_tx(REG_OFF_X, 10);
        tx.extend(standby_tx(REG_OFF_Y, (-5i8) as u8));
        tx.extend(standby_tx(REG_OFF_Z, 0));
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_OFF_X], vec![10]));
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_OFF_Y], vec![(-5i8) as u8]));
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_OFF_Z], vec![0]));
        let mut chip = new_full(&tx);
        // 0.02 g / 0.002 = 10; -0.01 g / 0.002 = -5.
        chip.set_offset(0.02, -0.01, 0.0).unwrap();
        let (x, y, z) = chip.get_offset().unwrap();
        assert!((x - 0.02).abs() < 1e-6);
        assert!((y + 0.01).abs() < 1e-6);
        assert!((z).abs() < 1e-6);
        chip.inner.i2c.done();
    }

    #[test]
    fn threshold_uses_063_g_per_lsb() {
        // 0.063 g / 0.063 = 1 → code 1.
        assert_eq!(threshold_code(0.063), 1);
        assert_eq!(threshold_code(1.0), 16);
        assert_eq!(threshold_code(8.0), 127);
    }

    #[test]
    fn orientation_threshold_table() {
        assert_eq!(orientation_threshold(15), Some(0x07));
        assert_eq!(orientation_threshold(45), Some(0x10));
        assert_eq!(orientation_threshold(75), Some(0x19));
        assert_eq!(orientation_threshold(33), None);
    }

    #[test]
    fn orientation_registers() {
        let mut tx = standby_tx(REG_PL_CFG, 0x40);
        tx.push(I2cTransaction::write(ADDR, vec![REG_PL_COUNT, 10]));
        tx.extend(standby_tx(REG_PL_THS, (0x10 << 3) | 4));
        tx.extend(standby_tx(REG_PL_BF_ZCOMP, (1 << 6) | 4));
        let mut chip = new_full(&tx);
        // 100 Hz normal oversampling → 10 ms step → 100 ms = 10 counts.
        chip.set_orientation(true, 100.0, 45, 4, 4, 1).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn read_orientation_decodes_status() {
        let mut chip = new_full(&[
            // NEWLP | LO | LAPO = 2 (landscape right) | BAFRO
            I2cTransaction::write_read(ADDR, vec![REG_PL_STATUS], vec![0xC5]),
        ]);
        let (changed, lockout, lapo, back) = chip.read_orientation().unwrap();
        assert!(changed);
        assert!(lockout);
        assert_eq!(lapo, 2);
        assert!(back);
        chip.inner.i2c.done();
    }

    #[test]
    fn fifo_setup_and_status() {
        let mut tx = standby_tx(REG_F_SETUP, 0x00);
        tx.extend(standby_tx(REG_F_SETUP, (1 << 6) | 16));
        // F_STATUS: F_OVF (0x80) | F_WMRK_FLAG (0x40) | F_CNT = 16.
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0xD0]));
        let mut chip = new_full(&tx);
        chip.set_fifo(1, 16).unwrap();
        let (ovf, wmrk, cnt) = chip.read_fifo_status().unwrap();
        assert!(ovf);
        assert!(wmrk);
        assert_eq!(cnt, 16);
        chip.inner.i2c.done();
    }

    #[test]
    fn read_fifo_drains_samples() {
        let mut tx = standby_tx(REG_F_SETUP, 0x00);
        tx.extend(standby_tx(REG_F_SETUP, (1 << 6) | 16));
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x02]));
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_OUT_X_MSB],
                                           vec![0x40, 0x00, 0x00, 0x00, 0x00, 0x00]));
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_OUT_X_MSB],
                                           vec![0x20, 0x00, 0x00, 0x00, 0x00, 0x00]));
        let mut chip = new_full(&tx);
        chip.set_fifo(1, 16).unwrap();
        let samples = chip.read_fifo(None).unwrap();
        assert_eq!(samples.len(), 2);
        assert!((samples[0].0 - 1.0).abs() < 1e-6);
        assert!((samples[1].0 - 0.5).abs() < 1e-6);
        chip.inner.i2c.done();
    }

    #[test]
    fn auto_sleep_configuration() {
        let mut tx = standby_ctrl1_tx(0x59);
        tx.extend(standby_modify_tx(REG_CTRL_REG2, 0x00, 0x04));
        tx.push(I2cTransaction::write(ADDR, vec![REG_ASLP_COUNT, 31]));
        tx.extend(standby_modify_tx(REG_CTRL_REG3, 0x00, 0x18));
        let mut chip = new_full(&tx);
        chip.set_auto_sleep(true, 12.5, 10.0, SOURCE_PULSE | SOURCE_FF_MT).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn interrupt_enable_and_disable_bit_placement() {
        let mut tx = standby_modify_tx(REG_CTRL_REG4, 0x00, SOURCE_PULSE);
        tx.extend(standby_modify_tx(REG_CTRL_REG5, 0x00, SOURCE_PULSE));
        tx.extend(standby_modify_tx(REG_CTRL_REG4, SOURCE_PULSE, 0x00));
        let mut chip = new_full(&tx);
        chip.enable_interrupt(SOURCE_PULSE, 1).unwrap();
        chip.disable_interrupt(SOURCE_PULSE).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn poll_interrupt_clears_source_registers() {
        let mut chip = new_full(&[
            // FIFO | PULSE | FF_MT | ORIENTATION | TRANSIENT | AUTO_SLEEP
            I2cTransaction::write_read(ADDR, vec![REG_INT_SOURCE], vec![0xFC]),
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_FF_MT_SRC], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_PULSE_SRC], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_PL_STATUS], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_TRANSIENT_SRC], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_SYSMOD], vec![0x01]),
        ]);
        let status = chip.poll_interrupt().unwrap();
        assert_eq!(status, 0xFC);
        chip.inner.i2c.done();
    }

    #[test]
    fn poll_interrupt_leaves_data_ready_set() {
        let mut chip = new_full(&[
            I2cTransaction::write_read(ADDR, vec![REG_INT_SOURCE], vec![0x01]),
        ]);
        let status = chip.poll_interrupt().unwrap();
        assert_eq!(status, 0x01);
        chip.inner.i2c.done();
    }

    #[test]
    fn freefall_motion_configuration() {
        // OAE = 0 (freefall), ELE = 1 (latch), axes X|Y|Z = 0x07 → 0xB8.
        let mut tx = standby_tx(REG_FF_MT_CFG, 0xB8);
        // 0.2 g / 0.063 = 3.17 → 3.
        tx.push(I2cTransaction::write(ADDR, vec![REG_FF_MT_THS, 3]));
        // 60 ms at 100 Hz / 10 ms step = 6.
        tx.push(I2cTransaction::write(ADDR, vec![REG_FF_MT_COUNT, 6]));
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_FF_MT_SRC], vec![0x80]));
        let mut chip = new_full(&tx);
        chip.set_freefall_motion(false, 0x07, 0.2, 60.0, true, false).unwrap();
        let src = chip.read_motion_source().unwrap();
        assert_eq!(src, 0x80);
        chip.inner.i2c.done();
    }

    #[test]
    fn transient_configuration() {
        // ELE (0x10) | axes X|Y|Z (0x07 << 1 = 0x0E) = 0x1E.
        let mut tx = standby_tx(REG_TRANSIENT_CFG, 0x1E);
        tx.push(I2cTransaction::write(ADDR, vec![REG_TRANSIENT_THS, 3]));
        tx.push(I2cTransaction::write(ADDR, vec![REG_TRANSIENT_COUNT, 0]));
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_TRANSIENT_SRC], vec![0x40]));
        let mut chip = new_full(&tx);
        chip.set_transient(0x07, 0.2, 0.0, false, true).unwrap();
        let src = chip.read_transient_source().unwrap();
        assert_eq!(src, 0x40);
        chip.inner.i2c.done();
    }

    #[test]
    fn pulse_configuration() {
        // Double tap on Z only: ZDPEFE (bit 5) | ELE (bit 6) = 0x60.
        let mut tx = standby_tx(REG_PULSE_CFG, 0x60);
        // 0.5 g / 0.063 = 7.94 → 8; 1.0 g / 0.063 = 15.87 → 16.
        tx.push(I2cTransaction::write(ADDR, vec![REG_PULSE_THSX, 8]));
        tx.push(I2cTransaction::write(ADDR, vec![REG_PULSE_THSY, 8]));
        tx.extend(standby_tx(REG_PULSE_THSZ, 16));
        // 40 ms / 10 ms step = 4 (100 Hz, normal oversampling, LPF on).
        tx.extend(standby_tx(REG_PULSE_TMLT, 4));
        // 100 ms / 20 ms step = 5.
        tx.extend(standby_tx(REG_PULSE_LTCY, 5));
        // 300 ms / 20 ms step = 15.
        tx.extend(standby_tx(REG_PULSE_WIND, 15));
        tx.extend(standby_modify_tx(REG_HP_FILTER_CUTOFF, 0x00, 0x10));
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_PULSE_SRC], vec![0x80]));
        let mut chip = new_full(&tx);
        chip.set_pulse(0, 0x04, (0.5, 0.5, 1.0), 40.0, 100.0, 300.0,
                        true, false, true, true).unwrap();
        let src = chip.read_pulse_source().unwrap();
        assert_eq!(src, 0x80);
        chip.inner.i2c.done();
    }

    #[test]
    fn fifo_trigger_sources() {
        // Trig_TRANS (0x20) | Trig_PULSE (0x08) = 0x28.
        let mut chip = new_full(&standby_tx(REG_TRIG_CFG, 0x28));
        chip.set_fifo_trigger(true, false, true, false).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn interrupt_pins_polarity_and_drive() {
        // IPOL (0x02) | PP_OD (0x01) = 0x03.
        let mut chip = new_full(&standby_modify_tx(REG_CTRL_REG3, 0x00, 0x03));
        chip.set_interrupt_pins(true, true).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn standby_and_activate_toggle_active_bit() {
        let mut chip = new_full(&[
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x19]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x18]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x18]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x19]),
        ]);
        chip.standby().unwrap();
        chip.activate().unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn status_and_system_mode() {
        let mut chip = new_full(&[
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x08]),
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x08]),
            I2cTransaction::write_read(ADDR, vec![REG_SYSMOD], vec![0x02]),
        ]);
        assert!(chip.new_data_available().unwrap());
        assert_eq!(chip.read_status().unwrap(), 0x08);
        // SYSMOD bits 1:0 = 2 → SLEEP.
        assert_eq!(chip.read_system_mode().unwrap(), 2);
        chip.inner.i2c.done();
    }

    #[test]
    fn reset_restores_standby_state() {
        let mut chip = new_full(&[
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, CTRL2_RST]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG2], vec![0x00]),
        ]);
        chip.reset().unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn fifo_level_reads_f_cnt() {
        let mut chip = new_full(&[
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x05]),
        ]);
        assert_eq!(chip.fifo_level().unwrap(), 5);
        chip.inner.i2c.done();
    }

    #[test]
    fn self_test_computes_count_deltas() {
        let mut tx = vec![
            // Read the current configuration.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x19]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG2], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_XYZ_DATA_CFG], vec![0x00]),
            // STANDBY, ±4 g, 100 Hz.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x18]),
            I2cTransaction::write(ADDR, vec![REG_XYZ_DATA_CFG, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x19]),
        ];
        // Four samples before the self-test.
        for _ in 0..4 {
            tx.push(I2cTransaction::write_read(ADDR, vec![REG_OUT_X_MSB],
                                              vec![0x00, 0x00, 0x00, 0x00, 0x00, 0x00]));
        }
        // Enable the self-test.
        tx.push(I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG2], vec![0x00]));
        tx.push(I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, CTRL2_ST]));
        // Four samples with the self-test active: X +181, Y +255, Z +1680
        // counts (raw16 = counts << 2, left-justified).
        for _ in 0..4 {
            tx.push(I2cTransaction::write_read(ADDR, vec![REG_OUT_X_MSB],
                                              vec![0x02, 0xD4, 0x03, 0xFC, 0x1A, 0x40]));
        }
        // Clear the self-test and restore the configuration.
        tx.push(I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x00]));
        tx.push(I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x18]));
        tx.push(I2cTransaction::write(ADDR, vec![REG_XYZ_DATA_CFG, 0x00]));
        tx.push(I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x00]));
        tx.push(I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x19]));
        let mut chip = new_full(&tx);
        let (ok, dx, dy, dz) = chip.self_test().unwrap();
        assert!(ok);
        assert_eq!(dx, 181);
        assert_eq!(dy, 255);
        assert_eq!(dz, 1680);
        chip.inner.i2c.done();
    }
}
