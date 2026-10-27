//! LSM303DLHC — 3-axis accelerometer + 3-axis magnetometer e-compass (STMicroelectronics).
//!
//! System-in-package combining a three-axis MEMS accelerometer (I²C address
//! 0x19) and a three-axis magnetometer (I²C address 0x1E) behind two
//! independent I²C slave addresses on one bus. The driver treats the module
//! as one logical device addressed through a single [`Connection`]; the
//! compass heading is computed in software from both sensors.
//!
//! ## Default configuration (written at construction)
//!
//! - Accelerometer: ±2 *g* (1 mg/LSB), 100 Hz ODR, high-resolution mode,
//!   all axes enabled, BDU=1, high-pass filter bypassed, FIFO bypassed,
//!   all interrupt routing cleared
//! - Magnetometer: ±1.3 gauss (±130 µT), 15 Hz ODR, continuous conversion,
//!   temperature sensor off
//!
//! The two slaves use different I²C framing: the accelerometer sub-address
//! bit 7 enables address auto-increment (set by this driver for every
//! multi-byte transfer); the magnetometer has an address pointer that always
//! auto-increments (wrapping from 0x08 back to 0x03 and from 0x0C to 0x00)
//! and needs no extra bit. Magnetometer outputs are X, Z, Y with the high
//! byte first — this driver reorders to X, Y, Z. The accelerometer outputs
//! are X, Y, Z low byte first.
//!
//! ## Constants
//!
//! Interrupt sources: [`SOURCE_DATA_READY`], [`SOURCE_WATERMARK`],
//! [`SOURCE_OVERRUN`], [`SOURCE_IA1`], [`SOURCE_IA2`], [`SOURCE_CLICK`],
//! [`SOURCE_BOOT`], [`SOURCE_MAG_DATA_READY`].

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::connection::Connection;

// Fixed 7-bit I²C slave addresses: the module has no address pins.
const ACCEL_ADDR: u8 = 0x19;
const MAG_ADDR: u8 = 0x1E;

// Accelerometer register map (sub-address bit 7 = auto-increment).
const REG_CTRL1_A: u8      = 0x20;
const REG_CTRL2_A: u8      = 0x21;
const REG_CTRL3_A: u8      = 0x22;
const REG_CTRL4_A: u8      = 0x23;
const REG_CTRL5_A: u8      = 0x24;
const REG_CTRL6_A: u8      = 0x25;
const REG_REFERENCE_A: u8  = 0x26;
const REG_STATUS_A: u8     = 0x27;
const REG_OUT_X_L_A: u8    = 0x28;
const REG_FIFO_CTRL_A: u8  = 0x2E;
const REG_FIFO_SRC_A: u8   = 0x2F;
const REG_INT1_CFG_A: u8   = 0x30;
const REG_INT1_SRC_A: u8   = 0x31;
const REG_INT1_THS_A: u8   = 0x32;
const REG_INT1_DUR_A: u8   = 0x33;
const REG_INT2_CFG_A: u8   = 0x34;
const REG_INT2_SRC_A: u8   = 0x35;
const REG_INT2_THS_A: u8   = 0x36;
const REG_INT2_DUR_A: u8   = 0x37;
const REG_CLICK_CFG_A: u8  = 0x38;
const REG_CLICK_SRC_A: u8  = 0x39;
const REG_CLICK_THS_A: u8  = 0x3A;
const REG_TIME_LIMIT_A: u8 = 0x3B;
const REG_TIME_LAT_A: u8   = 0x3C;
const REG_TIME_WIN_A: u8   = 0x3D;

// Magnetometer register map (address pointer auto-increments; no auto-inc bit).
const REG_CRA_M: u8     = 0x00;
const REG_CRB_M: u8     = 0x01;
const REG_MR_M: u8      = 0x02;
const REG_OUT_X_H_M: u8 = 0x03;
const REG_SR_M: u8      = 0x09;
const REG_IRA_M: u8     = 0x0A;
const REG_TEMP_H_M: u8  = 0x31;

// Magnetometer identity: IRA/IRB/IRC = ASCII "H43"; identical to HMC5883L.
const MAG_ID: [u8; 3] = [0x48, 0x34, 0x33];

// Valid accelerometer output data rates in Hz (CTRL_REG1_A ODR bits 7:4).
// Rate 0 (power-down) is handled separately (ODR = 0000).
const ACCEL_ODR_CODES: [(u16, u8); 10] = [
    (1, 0x1), (10, 0x2), (25, 0x3), (50, 0x4), (100, 0x5),
    (200, 0x6), (400, 0x7), (1620, 0x8), (1344, 0x9), (5376, 0x9),
];

// Sensitivity in mg/LSB, indexed [mode][range_bits]; mode 0 = low-power
// (8-bit), 1 = normal (10-bit), 2 = high-resolution (12-bit);
// range 0..3 = ±2/4/8/16 g.
const ACCEL_SENSITIVITY_MG: [[f32; 4]; 3] = [
    [16.0, 32.0, 64.0, 192.0],
    [4.0, 8.0, 16.0, 48.0],
    [1.0, 2.0, 4.0, 12.0],
];

// Arithmetic shift per mode: 8-bit low-power → 8, 10-bit normal → 6, 12-bit HR → 4.
const ACCEL_SHIFT: [u8; 3] = [8, 6, 4];

// Magnetometer gain codes GN<2:0> 1..7: full scale in µT and LSB/gauss for X/Y and Z.
const MAG_RANGES: [(u16, f32, f32); 7] = [
    (130, 1100.0, 980.0),
    (190, 855.0, 760.0),
    (250, 670.0, 600.0),
    (400, 450.0, 400.0),
    (470, 400.0, 355.0),
    (560, 330.0, 295.0),
    (810, 230.0, 205.0),
];

// Valid magnetometer output data rates in Hz (CRA_REG_M DO bits 4:2).
const MAG_ODR_CODES: [(f32, u8); 8] = [
    (0.75, 0x0), (1.5, 0x1), (3.0, 0x2), (7.5, 0x3),
    (15.0, 0x4), (30.0, 0x5), (75.0, 0x6), (220.0, 0x7),
];

// CTRL_REG3_A routing bits (INT1 side).
const I1_CLICK: u8   = 0x80;
const I1_AOI1: u8    = 0x40;
const I1_AOI2: u8    = 0x20;
const I1_DRDY1: u8   = 0x10;
const I1_WTM: u8     = 0x04;
const I1_OVERRUN: u8 = 0x01;

// CTRL_REG6_A routing bits (INT2 side).
const I2_CLICK: u8 = 0x80;
const I2_INT1: u8  = 0x40;
const I2_INT2: u8  = 0x20;
const BOOT_I1: u8  = 0x10;

/// Interrupt source: new accelerometer XYZ sample (`ZYXDA`), INT1.
pub const SOURCE_DATA_READY: u16 = 0x0001;
/// Interrupt source: FIFO content exceeds watermark (`WTM`), INT1.
pub const SOURCE_WATERMARK: u16 = 0x0002;
/// Interrupt source: FIFO overrun (`OVRN_FIFO`), INT1.
pub const SOURCE_OVERRUN: u16 = 0x0004;
/// Interrupt source: inertial generator 1 event (`INT1_SOURCE_A.IA`), INT1 or INT2.
pub const SOURCE_IA1: u16 = 0x0008;
/// Interrupt source: inertial generator 2 event (`INT2_SOURCE_A.IA`), INT1 or INT2.
pub const SOURCE_IA2: u16 = 0x0010;
/// Interrupt source: single / double click (`CLICK_SRC_A.IA`), INT1 or INT2.
pub const SOURCE_CLICK: u16 = 0x0020;
/// Interrupt source: boot procedure running (INT2, pin-only — never reported by
/// [`Lsm303Full::poll_interrupt`]).
pub const SOURCE_BOOT: u16 = 0x0040;
/// Interrupt source: new magnetometer sample (`SR_REG_M.DRDY`); always on the
/// dedicated DRDY pin, not routable.
pub const SOURCE_MAG_DATA_READY: u16 = 0x0080;

/// LSM303 minimal driver — 3-axis acceleration in *g* and 3-axis magnetic
/// flux density in µT.
///
/// Performs the magnetometer identity check and runs the full init sequence
/// (±2 *g*, 100 Hz, high-resolution, ±130 µT, 15 Hz, continuous conversion)
/// at construction.
///
/// The LSM303DLHC exposes **two separate I²C slaves on one bus** — the
/// accelerometer at 0x19 and the magnetometer at 0x1E — so, unlike other
/// Rust drivers in this crate, there is no `addr` argument: the single
/// [`Connection`] is a bus wrapper whose read/write take the device address
/// per call, and this driver addresses both slaves through it.
///
/// # Arguments
/// * `conn` — I²C [`Connection`] (bus + software enable gate). Both slaves
///   must be reachable on the same bus.
/// * `delay` — Delay provider for the init sequence (≥ 5 ms boot, 70 ms
///   accelerometer turn-on, 67 ms first magnetometer sample).
///
/// Panics if the magnetometer identity registers do not read `0x48 0x34 0x33`.
pub struct Lsm303Minimal<I2C> {
    conn: Connection<I2C>,
    range_bits: u8,
    mode: u8,
    odr_hz: f32,
    mag_gn: usize,
    #[allow(dead_code)]  // cached for parity with the Python driver; no read path
    mag_odr_hz: f32,
    temp_enabled: bool,
}

impl<I2C: I2c> Lsm303Minimal<I2C> {
    /// Create a new `Lsm303Minimal`, verify the magnetometer identity, and
    /// run the init sequence.
    ///
    /// See the struct documentation for the argument description.
    pub fn new<D: DelayNs>(mut conn: Connection<I2C>, delay: &mut D) -> Result<Self, I2C::Error> {
        // Boot download of the factory trim values (≥ 5 ms, as for sibling parts).
        delay.delay_ms(5);

        let mut id = [0u8; 3];
        mag_read(&mut conn, REG_IRA_M, &mut id)?;
        if id != MAG_ID {
            panic!("LSM303 magnetometer ID: expected 0x483433, got 0x{:02X}{:02X}{:02X}",
                   id[0], id[1], id[2]);
        }

        // Accelerometer: no soft reset — write the control registers to known values.
        accel_write(&mut conn, REG_CTRL2_A, 0x00)?;
        accel_write(&mut conn, REG_CTRL3_A, 0x00)?;
        accel_write(&mut conn, REG_CTRL5_A, 0x00)?;
        accel_write(&mut conn, REG_CTRL6_A, 0x00)?;
        accel_write(&mut conn, REG_FIFO_CTRL_A, 0x00)?;
        // BDU=1, FS=00 (±2 g), HR=1 (high-resolution).
        accel_write(&mut conn, REG_CTRL4_A, 0x88)?;
        // ODR=0101 (100 Hz), LPen=0, all axes enabled — leaves power-down.
        accel_write(&mut conn, REG_CTRL1_A, 0x57)?;

        // Magnetometer: the part resets into sleep mode, so write all three
        // control registers explicitly (15 Hz, ±1.3 gauss, continuous).
        mag_write(&mut conn, REG_CRA_M, 0x10)?;
        mag_write(&mut conn, REG_CRB_M, 0x20)?;
        mag_write(&mut conn, REG_MR_M, 0x00)?;

        // Turn-on waits: 7/ODR (70 ms at 100 Hz) for the accelerometer and one
        // output period (67 ms at 15 Hz) for the first magnetometer sample.
        delay.delay_ms(70);
        delay.delay_ms(67);

        Ok(Self {
            conn,
            range_bits: 0,
            mode: 2,
            odr_hz: 100.0,
            mag_gn: 0,
            mag_odr_hz: 15.0,
            temp_enabled: false,
        })
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Burst-reads OUT_X_L_A..OUT_Z_H_A with the auto-increment bit set so
    /// all six bytes belong to one sample (BDU=1).
    ///
    /// Returns (x, y, z) acceleration in *g*.
    pub fn read_acceleration(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut raw = [0u8; 6];
        accel_read6(&mut self.conn, REG_OUT_X_L_A, &mut raw)?;
        let (cx, cy, cz) = self.convert_accel_sample(&raw);
        let scale = self.accel_sensitivity_g();
        Ok((cx as f32 * scale, cy as f32 * scale, cz as f32 * scale))
    }

    /// Read 3-axis magnetic flux density.
    ///
    /// Burst-reads the six output registers 0x03..0x08 (on the wire: X, Z, Y,
    /// each high byte first) in one transaction and reorders to X, Y, Z.
    ///
    /// Returns (x, y, z) magnetic flux density in µT.
    pub fn read_magnetic(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut raw = [0u8; 6];
        mag_read(&mut self.conn, REG_OUT_X_H_M, &mut raw)?;
        let mx = s16(raw[0], raw[1]);
        let mz = s16(raw[2], raw[3]);
        let my = s16(raw[4], raw[5]);
        let gain_xy = MAG_RANGES[self.mag_gn].1;
        let gain_z = MAG_RANGES[self.mag_gn].2;
        Ok((mx as f32 / gain_xy * 100.0,
            my as f32 / gain_xy * 100.0,
            mz as f32 / gain_z * 100.0))
    }

    /// Sensitivity in g/LSB at the current mode and range.
    fn accel_sensitivity_g(&self) -> f32 {
        ACCEL_SENSITIVITY_MG[self.mode as usize][self.range_bits as usize] / 1000.0
    }

    /// Convert a 6-byte accelerometer burst to (x, y, z) signed counts:
    /// sign-extend the 16-bit pairs first, then arithmetic-shift right.
    fn convert_accel_sample(&self, raw: &[u8; 6]) -> (i16, i16, i16) {
        let shift = ACCEL_SHIFT[self.mode as usize];
        (s16(raw[1], raw[0]) >> shift,
         s16(raw[3], raw[2]) >> shift,
         s16(raw[5], raw[4]) >> shift)
    }
}

/// LSM303 full driver — extends [`Lsm303Minimal`] with complete functionality.
///
/// Adds accelerometer range / data-rate / power-mode configuration, per-axis
/// enable, raw data access, the high-pass filter, the 32-level FIFO, two
/// inertial interrupt generators with free-fall and 6D/4D orientation,
/// single/double click detection, interrupt routing across INT1/INT2, the
/// magnetometer gain / data-rate / operating-mode controls, the relative
/// temperature sensor, the software compass heading, and memory reboot.
///
/// Re-exports [`Lsm303Minimal::read_acceleration`] and
/// [`Lsm303Minimal::read_magnetic`] as one-line delegates (the Rust
/// equivalent of "Full never duplicates Minimal"), then adds Full-only
/// methods below.
pub struct Lsm303Full<I2C> {
    inner: Lsm303Minimal<I2C>,
    int_sources: u16,
}

impl<I2C: I2c> Lsm303Full<I2C> {
    /// Create a new `Lsm303Full`.
    ///
    /// Same arguments and init sequence as [`Lsm303Minimal::new`].
    pub fn new<D: DelayNs>(conn: Connection<I2C>, delay: &mut D) -> Result<Self, I2C::Error> {
        let inner = Lsm303Minimal::new(conn, delay)?;
        Ok(Self { inner, int_sources: 0 })
    }

    /// Read 3-axis linear acceleration in *g*. Delegates to the inner
    /// [`Lsm303Minimal`].
    pub fn read_acceleration(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read_acceleration()
    }

    /// Read 3-axis magnetic flux density in µT. Delegates to the inner
    /// [`Lsm303Minimal`].
    pub fn read_magnetic(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read_magnetic()
    }

    /// Set the accelerometer full-scale range.
    ///
    /// # Arguments
    /// * `range_g` — One of 2, 4, 8, 16 (*g*). Updates the cached sensitivity.
    ///
    /// Panics if `range_g` is not one of 2, 4, 8, 16.
    pub fn set_range(&mut self, range_g: u8) -> Result<(), I2C::Error> {
        let code = match range_g {
            2 => 0u8,
            4 => 1,
            8 => 2,
            16 => 3,
            _ => panic!("range_g must be one of 2, 4, 8, 16"),
        };
        self.inner.range_bits = code;
        let ctrl4 = accel_read8(&mut self.inner.conn, REG_CTRL4_A)?;
        accel_write(&mut self.inner.conn, REG_CTRL4_A, (ctrl4 & 0xCF) | (code << 4))
    }

    /// Set the accelerometer output data rate.
    ///
    /// # Arguments
    /// * `rate_hz` — One of 0 (power-down), 1, 10, 25, 50, 100, 200, 400,
    ///   1344, 1620 or 5376 Hz. 1620 Hz and 5376 Hz exist in low-power mode
    ///   only, 1344 Hz in normal / high-resolution mode.
    ///
    /// Panics if the rate is not allowed in the current mode.
    pub fn set_data_rate(&mut self, rate_hz: u16) -> Result<(), I2C::Error> {
        let code = if rate_hz == 0 {
            0x0
        } else {
            match ACCEL_ODR_CODES.iter().find(|&&(rate, _)| rate == rate_hz) {
                Some(&(_, code)) => code,
                None => panic!("rate_hz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376"),
            }
        };
        if code == 0x8 && self.inner.mode != 0 {
            panic!("1620 Hz requires low-power mode (set_mode(0))");
        }
        if code == 0x9 && rate_hz == 5376 && self.inner.mode != 0 {
            panic!("5376 Hz requires low-power mode (set_mode(0))");
        }
        if code == 0x9 && rate_hz == 1344 && self.inner.mode == 0 {
            panic!("1344 Hz requires normal or high-resolution mode");
        }
        let ctrl1 = accel_read8(&mut self.inner.conn, REG_CTRL1_A)?;
        accel_write(&mut self.inner.conn, REG_CTRL1_A, (ctrl1 & 0x0F) | (code << 4))?;
        self.inner.odr_hz = rate_hz as f32;
        Ok(())
    }

    /// Set the accelerometer operating mode (resolution).
    ///
    /// # Arguments
    /// * `mode` — 0 = low-power (8-bit), 1 = normal (10-bit), 2 =
    ///   high-resolution (12-bit). Updates the cached sensitivity.
    ///
    /// Panics if `mode` is not 0, 1 or 2.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        if mode > 2 {
            panic!("mode must be 0 (low-power), 1 (normal) or 2 (high-resolution)");
        }
        let mut ctrl1 = accel_read8(&mut self.inner.conn, REG_CTRL1_A)?;
        let mut ctrl4 = accel_read8(&mut self.inner.conn, REG_CTRL4_A)?;
        match mode {
            0 => { ctrl1 |= 0x08; ctrl4 &= !0x08; }
            1 => { ctrl1 &= !0x08; ctrl4 &= !0x08; }
            _ => { ctrl1 &= !0x08; ctrl4 |= 0x08; }
        }
        accel_write(&mut self.inner.conn, REG_CTRL1_A, ctrl1)?;
        accel_write(&mut self.inner.conn, REG_CTRL4_A, ctrl4)?;
        self.inner.mode = mode;
        Ok(())
    }

    /// Enable or disable the accelerometer axes.
    ///
    /// # Arguments
    /// * `x`, `y`, `z` — Axis enables (Xen / Yen / Zen).
    pub fn set_axes(&mut self, x: bool, y: bool, z: bool) -> Result<(), I2C::Error> {
        let ctrl1 = accel_read8(&mut self.inner.conn, REG_CTRL1_A)?;
        let axes = ((z as u8) << 2) | ((y as u8) << 1) | x as u8;
        accel_write(&mut self.inner.conn, REG_CTRL1_A, (ctrl1 & 0xF8) | axes)
    }

    /// Enter power-down (ODR = 0000). Register contents are retained.
    pub fn power_down(&mut self) -> Result<(), I2C::Error> {
        let ctrl1 = accel_read8(&mut self.inner.conn, REG_CTRL1_A)?;
        accel_write(&mut self.inner.conn, REG_CTRL1_A, ctrl1 & 0x0F)?;
        self.inner.odr_hz = 0.0;
        Ok(())
    }

    /// Read raw accelerometer counts at the current resolution.
    ///
    /// Returns (x, y, z) signed counts (12/10/8-bit right-aligned per mode).
    pub fn read_acceleration_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut raw = [0u8; 6];
        accel_read6(&mut self.inner.conn, REG_OUT_X_L_A, &mut raw)?;
        Ok(self.inner.convert_accel_sample(&raw))
    }

    /// Check whether a new accelerometer sample is available.
    ///
    /// Returns true when STATUS_REG_A.ZYXDA is set.
    pub fn new_data_available(&mut self) -> Result<bool, I2C::Error> {
        Ok(accel_read8(&mut self.inner.conn, REG_STATUS_A)? & 0x08 != 0)
    }

    /// Read the accelerometer status register.
    ///
    /// Returns the STATUS_REG_A byte (ZYXOR/ZOR/YOR/XOR overrun,
    /// ZYXDA/*DA data-ready).
    pub fn read_status(&mut self) -> Result<u8, I2C::Error> {
        accel_read8(&mut self.inner.conn, REG_STATUS_A)
    }

    /// Configure the high-pass filter.
    ///
    /// # Arguments
    /// * `mode` — HPM<1:0>: 0 normal (reset by reading REFERENCE_A), 1
    ///   reference signal for filtering, 2 normal, 3 autoreset on interrupt event.
    /// * `cutoff` — HPCF<2:1> raw code 0–3 (no Hz values tabulated for this part).
    /// * `filter_output` — Filtered data goes to the output registers and FIFO.
    /// * `on_ia1` — High-pass filter enabled for interrupt generator 1.
    /// * `on_ia2` — High-pass filter enabled for interrupt generator 2.
    /// * `on_click` — High-pass filter enabled for click detection.
    #[allow(clippy::too_many_arguments)]
    pub fn set_high_pass(&mut self, mode: u8, cutoff: u8, filter_output: bool,
                         on_ia1: bool, on_ia2: bool, on_click: bool) -> Result<(), I2C::Error> {
        let mut ctrl2 = ((mode & 0x03) << 6) | ((cutoff & 0x03) << 4);
        if filter_output { ctrl2 |= 0x08; }
        if on_click { ctrl2 |= 0x04; }
        if on_ia2 { ctrl2 |= 0x02; }
        if on_ia1 { ctrl2 |= 0x01; }
        accel_write(&mut self.inner.conn, REG_CTRL2_A, ctrl2)
    }

    /// Reset the high-pass filter (HPM = 00) by reading REFERENCE_A.
    ///
    /// Returns the REFERENCE_A register value.
    pub fn reset_high_pass(&mut self) -> Result<u8, I2C::Error> {
        accel_read8(&mut self.inner.conn, REG_REFERENCE_A)
    }

    /// Write the high-pass reference register (for HPM = 01).
    ///
    /// # Arguments
    /// * `value` — Reference value 0–255.
    pub fn set_reference(&mut self, value: u8) -> Result<(), I2C::Error> {
        accel_write(&mut self.inner.conn, REG_REFERENCE_A, value)
    }

    /// Configure the 32-level FIFO.
    ///
    /// # Arguments
    /// * `mode` — 0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO).
    ///   Returning to bypass clears the buffer.
    /// * `watermark` — Watermark level 0–31.
    /// * `trigger_int2` — Trigger mode source: false = INT1, true = INT2.
    pub fn set_fifo(&mut self, mode: u8, watermark: u8, trigger_int2: bool) -> Result<(), I2C::Error> {
        let mut fifo_ctrl = ((mode & 0x03) << 6) | (watermark & 0x1F);
        if trigger_int2 { fifo_ctrl |= 0x20; }
        accel_write(&mut self.inner.conn, REG_FIFO_CTRL_A, fifo_ctrl)?;
        let ctrl5 = accel_read8(&mut self.inner.conn, REG_CTRL5_A)?;
        let ctrl5 = if mode >= 1 && mode <= 3 { ctrl5 | 0x40 } else { ctrl5 & !0x40 };
        accel_write(&mut self.inner.conn, REG_CTRL5_A, ctrl5)
    }

    /// Return the number of unread sample sets in the FIFO (0–32).
    ///
    /// Returns FSS<4:0> from FIFO_SRC_REG_A.
    pub fn fifo_level(&mut self) -> Result<u8, I2C::Error> {
        Ok(accel_read8(&mut self.inner.conn, REG_FIFO_SRC_A)? & 0x1F)
    }

    /// Drain up to `count` sample sets from the FIFO.
    ///
    /// Each sample is popped with one 6-byte burst read of
    /// OUT_X_L_A..OUT_Z_H_A. `None` reads all unread samples reported by FSS.
    /// The FIFO physically holds 32 sample sets, so counts above 32 are
    /// capped; the result is a fixed-capacity `heapless::Vec` rather than a
    /// heap-backed one — this crate is `no_std` by default.
    ///
    /// Returns (x, y, z) tuples in *g*.
    pub fn read_fifo(&mut self, count: Option<usize>)
        -> Result<heapless::Vec<(f32, f32, f32), 32>, I2C::Error> {
        let requested = match count {
            Some(c) => c,
            None => self.fifo_level()? as usize,
        };
        let n = requested.min(32);
        let scale = self.inner.accel_sensitivity_g();
        let mut out = heapless::Vec::new();
        for _ in 0..n {
            let mut raw = [0u8; 6];
            accel_read6(&mut self.inner.conn, REG_OUT_X_L_A, &mut raw)?;
            let (cx, cy, cz) = self.inner.convert_accel_sample(&raw);
            let _ = out.push((cx as f32 * scale, cy as f32 * scale, cz as f32 * scale));
        }
        Ok(out)
    }

    /// Read the decoded FIFO status.
    ///
    /// Returns (watermark, overrun, empty, level) — the first three are
    /// bools, `level` is the FSS<4:0> sample count.
    pub fn read_fifo_status(&mut self) -> Result<(bool, bool, bool, u8), I2C::Error> {
        let src = accel_read8(&mut self.inner.conn, REG_FIFO_SRC_A)?;
        Ok((src & 0x80 != 0, src & 0x40 != 0, src & 0x20 != 0, src & 0x1F))
    }

    /// Configure one inertial interrupt generator.
    ///
    /// # Arguments
    /// * `generator` — 1 or 2 (INT1 generator / INT2 generator).
    /// * `events` — 6-bit event mask (bit 5 = Z high, 4 = Z low, 3 = Y high,
    ///   2 = Y low, 1 = X high, 0 = X low; the same bits select the direction
    ///   zones in 6D modes).
    /// * `threshold_g` — Event threshold in *g* (1 LSb = full-scale / 128).
    /// * `duration_ms` — Minimum event duration in ms (1 LSb = 1/ODR).
    /// * `and_mode` — AND combination of the enabled events (false = OR).
    ///
    /// Panics if `generator` is not 1 or 2.
    pub fn set_motion_interrupt(&mut self, generator: u8, events: u8, threshold_g: f32,
                                duration_ms: f32, and_mode: bool) -> Result<(), I2C::Error> {
        let (cfg, _src, ths, dur) = int_regs(generator);
        let ths_code = self.encode_threshold(threshold_g);
        let dur_code = self.encode_duration(duration_ms, 7);
        accel_write(&mut self.inner.conn, cfg, ((and_mode as u8) << 7) | (events & 0x3F))?;
        accel_write(&mut self.inner.conn, ths, ths_code)?;
        accel_write(&mut self.inner.conn, dur, dur_code)
    }

    /// Configure free-fall detection on one generator.
    ///
    /// All three axes' low events, AND-combined (INTx_CFG_A = 0x95).
    ///
    /// # Arguments
    /// * `generator` — 1 (default) or 2.
    /// * `threshold_g` — Free-fall threshold in *g*.
    /// * `duration_ms` — Minimum free-fall duration in ms.
    pub fn set_free_fall(&mut self, generator: u8, threshold_g: f32, duration_ms: f32)
        -> Result<(), I2C::Error> {
        self.set_motion_interrupt(generator, 0x15, threshold_g, duration_ms, true)
    }

    /// Configure 6D / 4D orientation recognition on one generator.
    ///
    /// # Arguments
    /// * `generator` — 1 (default) or 2.
    /// * `position` — false = 6D movement recognition (one-ODR pulse when the
    ///   orientation moves into a known zone), true = 6D position recognition
    ///   (stays active inside a known zone).
    /// * `four_d` — 4D detection (Z-axis zone disabled; needs the 6D bit).
    ///
    /// Panics if `generator` is not 1 or 2.
    pub fn set_orientation(&mut self, generator: u8, position: bool, four_d: bool)
        -> Result<(), I2C::Error> {
        let (cfg, _src, _ths, _dur) = int_regs(generator);
        let mut value = 0x40 | 0x3F;  // 6D + all six direction zones
        if position { value |= 0x80; }
        accel_write(&mut self.inner.conn, cfg, value)?;
        let ctrl5 = accel_read8(&mut self.inner.conn, REG_CTRL5_A)?;
        let mask = if generator == 1 { 0x04 } else { 0x01 };
        let ctrl5 = if four_d { ctrl5 | mask } else { ctrl5 & !mask };
        accel_write(&mut self.inner.conn, REG_CTRL5_A, ctrl5)
    }

    /// Latch (or unlatch) one generator's interrupt until its source is read.
    ///
    /// # Arguments
    /// * `generator` — 1 or 2.
    /// * `enabled` — Latch INT output until INTx_SOURCE_A is read.
    ///
    /// Panics if `generator` is not 1 or 2.
    pub fn set_latch(&mut self, generator: u8, enabled: bool) -> Result<(), I2C::Error> {
        if generator < 1 || generator > 2 {
            panic!("generator must be 1 or 2");
        }
        let ctrl5 = accel_read8(&mut self.inner.conn, REG_CTRL5_A)?;
        let mask = if generator == 1 { 0x08 } else { 0x02 };
        let ctrl5 = if enabled { ctrl5 | mask } else { ctrl5 & !mask };
        accel_write(&mut self.inner.conn, REG_CTRL5_A, ctrl5)
    }

    /// Read one generator's source register — clears the interrupt.
    ///
    /// # Arguments
    /// * `generator` — 1 or 2.
    ///
    /// Returns the INTx_SOURCE_A byte (bit 6 = IA, bits 5:0 = axis high/low flags).
    ///
    /// Panics if `generator` is not 1 or 2.
    pub fn read_interrupt_source(&mut self, generator: u8) -> Result<u8, I2C::Error> {
        let (_cfg, src, _ths, _dur) = int_regs(generator);
        accel_read8(&mut self.inner.conn, src)
    }

    /// Configure single / double click detection.
    ///
    /// # Arguments
    /// * `single` — Axis mask for single click (X=1, Y=2, Z=4; 0 = off).
    /// * `double` — Axis mask for double click (X=1, Y=2, Z=4; 0 = off).
    /// * `threshold_g` — Click threshold in *g* (1 LSb = full-scale / 128).
    /// * `time_limit_ms` — Maximum click duration in ms (1 LSb = 1/ODR, 7-bit).
    /// * `latency_ms` — Time after a click in which the click interrupt is not
    ///   re-armed (1 LSb = 1/ODR, 8-bit).
    /// * `window_ms` — Time window for a second click (1 LSb = 1/ODR, 8-bit).
    ///
    /// Panics if `single` or `double` is greater than 7.
    #[allow(clippy::too_many_arguments)]
    pub fn set_click(&mut self, single: u8, double: u8, threshold_g: f32,
                     time_limit_ms: f32, latency_ms: f32, window_ms: f32) -> Result<(), I2C::Error> {
        if single > 7 || double > 7 {
            panic!("single / double are axis masks: X=1, Y=2, Z=4");
        }
        // Axis masks X=1/Y=2/Z=4 spread to CLICK_CFG_A bits:
        // XD=0, XS=1, YD=2, YS=3, ZD=4, ZS=5.
        let single_code = ((single & 1) << 1) | ((single & 2) << 2) | ((single & 4) << 3);
        let double_code = ((double & 1) << 0) | ((double & 2) << 1) | ((double & 4) << 2);
        let ths_code = self.encode_threshold(threshold_g);
        let limit_code = self.encode_duration(time_limit_ms, 7);
        let latency_code = self.encode_duration(latency_ms, 8);
        let window_code = self.encode_duration(window_ms, 8);
        accel_write(&mut self.inner.conn, REG_CLICK_CFG_A, single_code | double_code)?;
        accel_write(&mut self.inner.conn, REG_CLICK_THS_A, ths_code)?;
        accel_write(&mut self.inner.conn, REG_TIME_LIMIT_A, limit_code)?;
        accel_write(&mut self.inner.conn, REG_TIME_LAT_A, latency_code)?;
        accel_write(&mut self.inner.conn, REG_TIME_WIN_A, window_code)
    }

    /// Read the click source register — clears the click interrupt.
    ///
    /// Returns the CLICK_SRC_A byte (bit 6 = IA, bit 5 = DCLICK, bit 4 =
    /// SCLICK, bit 3 = sign, bits 2:0 = Z/Y/X event axis).
    pub fn read_click_source(&mut self) -> Result<u8, I2C::Error> {
        accel_read8(&mut self.inner.conn, REG_CLICK_SRC_A)
    }

    /// Set the INT1 / INT2 output polarity.
    ///
    /// # Arguments
    /// * `active_low` — INT pins active-low (false = active-high, the default).
    pub fn set_interrupt_polarity(&mut self, active_low: bool) -> Result<(), I2C::Error> {
        let ctrl6 = accel_read8(&mut self.inner.conn, REG_CTRL6_A)?;
        let ctrl6 = if active_low { ctrl6 | 0x02 } else { ctrl6 & !0x02 };
        accel_write(&mut self.inner.conn, REG_CTRL6_A, ctrl6)
    }

    /// Set the magnetometer full-scale range (gain).
    ///
    /// # Arguments
    /// * `range_ut` — One of 130, 190, 250, 400, 470, 560, 810 (µT).
    ///   Updates the cached conversion gains.
    ///
    /// Panics if `range_ut` is not one of the supported values.
    pub fn set_mag_range(&mut self, range_ut: u16) -> Result<(), I2C::Error> {
        let index = MAG_RANGES.iter().position(|&(fs, _, _)| fs == range_ut)
            .unwrap_or_else(|| panic!("range_ut must be one of 130, 190, 250, 400, 470, 560, 810"));
        self.inner.mag_gn = index;
        mag_write(&mut self.inner.conn, REG_CRB_M, ((index + 1) as u8) << 5)
    }

    /// Set the magnetometer output data rate.
    ///
    /// # Arguments
    /// * `rate_hz` — One of 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220 Hz.
    ///   Preserves the TEMP_EN setting.
    ///
    /// Panics if `rate_hz` is not one of the supported rates.
    pub fn set_mag_data_rate(&mut self, rate_hz: f32) -> Result<(), I2C::Error> {
        let code = match MAG_ODR_CODES.iter().find(|&&(rate, _)| rate == rate_hz) {
            Some(&(_, code)) => code,
            None => panic!("rate_hz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220"),
        };
        let cra = mag_read8(&mut self.inner.conn, REG_CRA_M)?;
        mag_write(&mut self.inner.conn, REG_CRA_M, (cra & 0x80) | (code << 2))?;
        self.inner.mag_odr_hz = rate_hz;
        Ok(())
    }

    /// Set the magnetometer operating mode.
    ///
    /// # Arguments
    /// * `mode` — 0 continuous conversion, 1 single conversion (one
    ///   conversion, then back to sleep), 2 sleep.
    ///
    /// Panics if `mode` is not 0, 1 or 2.
    pub fn set_mag_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        if mode > 2 {
            panic!("mode must be 0 (continuous), 1 (single) or 2 (sleep)");
        }
        mag_write(&mut self.inner.conn, REG_MR_M, mode)
    }

    /// Read raw magnetometer counts (X, Y, Z order, after reordering).
    ///
    /// Returns (x, y, z) signed 12-bit counts.
    pub fn read_magnetic_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut raw = [0u8; 6];
        mag_read(&mut self.inner.conn, REG_OUT_X_H_M, &mut raw)?;
        let mx = s16(raw[0], raw[1]);
        let mz = s16(raw[2], raw[3]);
        let my = s16(raw[4], raw[5]);
        Ok((mx, my, mz))
    }

    /// Check whether a new magnetometer sample is available.
    ///
    /// Returns true when SR_REG_M.DRDY is set.
    pub fn mag_data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(mag_read8(&mut self.inner.conn, REG_SR_M)? & 0x01 != 0)
    }

    /// Read the decoded magnetometer status register.
    ///
    /// Returns (drdy, lock) — DRDY = new set of measurements available,
    /// LOCK = output registers locked mid-read.
    pub fn read_mag_status(&mut self) -> Result<(bool, bool), I2C::Error> {
        let sr = mag_read8(&mut self.inner.conn, REG_SR_M)?;
        Ok((sr & 0x01 != 0, sr & 0x02 != 0))
    }

    /// Read the magnetometer identification registers.
    ///
    /// Returns IRA/IRB/IRC (`0x48 0x34 0x33` — identical to HMC5883L).
    pub fn read_mag_identity(&mut self) -> Result<[u8; 3], I2C::Error> {
        let mut id = [0u8; 3];
        mag_read(&mut self.inner.conn, REG_IRA_M, &mut id)?;
        Ok(id)
    }

    /// Enable or disable the relative temperature sensor.
    ///
    /// # Arguments
    /// * `enabled` — TEMP_EN set (updates at the magnetometer data rate).
    pub fn enable_temperature(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let cra = mag_read8(&mut self.inner.conn, REG_CRA_M)?;
        let cra = if enabled { cra | 0x80 } else { cra & !0x80 };
        mag_write(&mut self.inner.conn, REG_CRA_M, cra)?;
        self.inner.temp_enabled = enabled;
        Ok(())
    }

    /// Read the relative temperature.
    ///
    /// The sensor is relative only — the datasheet gives no zero-degree
    /// reference — so the value is a delta, never an absolute °C. Enables
    /// TEMP_EN on first use (read-modify-write).
    ///
    /// Returns the relative temperature in °C (8 LSB/°C).
    pub fn read_temperature_delta(&mut self) -> Result<f32, I2C::Error> {
        if !self.inner.temp_enabled {
            self.enable_temperature(true)?;
        }
        let mut raw = [0u8; 2];
        mag_read(&mut self.inner.conn, REG_TEMP_H_M, &mut raw)?;
        let value = (s16(raw[0], raw[1]) >> 4) as f32;
        Ok(value / 8.0)
    }

    /// Compute the compass heading from both sensors.
    ///
    /// Uses the `atan2(y, x)` convention, wrapped to 0–360°. The datasheet
    /// documents neither the axis alignment between the two dies nor a
    /// declination, so the result assumes both sensors report in the same
    /// body frame and is magnetic (not true) north.
    ///
    /// # Arguments
    /// * `tilt_compensate` — Project the magnetic field onto the horizontal
    ///   plane using the current acceleration; false = flat compass from the
    ///   magnetic X/Y components only.
    ///
    /// Returns the heading in degrees, 0–360.
    pub fn heading(&mut self, tilt_compensate: bool) -> Result<f32, I2C::Error> {
        let (mx, my, mz) = self.inner.read_magnetic()?;
        let angle = if !tilt_compensate {
            libm::atan2f(my, mx) * 180.0 / core::f32::consts::PI
        } else {
            let (ax, ay, az) = self.inner.read_acceleration()?;
            let mut norm = libm::sqrtf(ax * ax + ay * ay + az * az);
            if norm == 0.0 { norm = 1.0; }
            let (ax, ay, az) = (ax / norm, ay / norm, az / norm);
            let roll = libm::atan2f(ay, az);
            let pitch = libm::atan2f(-ax, ay * libm::sinf(roll) + az * libm::cosf(roll));
            let xh = mx * libm::cosf(pitch) + mz * libm::sinf(pitch);
            let yh = mx * libm::sinf(roll) * libm::sinf(pitch) + my * libm::cosf(roll)
                   - mz * libm::sinf(roll) * libm::cosf(pitch);
            libm::atan2f(yh, xh) * 180.0 / core::f32::consts::PI
        };
        Ok(if angle >= 0.0 { angle } else { angle + 360.0 })
    }

    /// Route one interrupt source to INT1 or INT2.
    ///
    /// Only routes the source; the generator or function behind it is
    /// configured with [`Self::set_motion_interrupt`] / [`Self::set_click`]
    /// / .... `pin` is ignored for INT1-only / INT2-only sources.
    /// [`SOURCE_MAG_DATA_READY`] is rejected (it is always on the dedicated
    /// magnetometer DRDY pin and is not routable).
    ///
    /// # Arguments
    /// * `source` — One of the `SOURCE_*` constants.
    /// * `pin` — 1 (default) or 2.
    ///
    /// Panics if `pin` is not 1 or 2, the source is not routable on the
    /// requested pin, or the source is unknown.
    pub fn enable_interrupt(&mut self, source: u16, pin: u8) -> Result<(), I2C::Error> {
        let (in_ctrl3, mask) = route_bit(source, pin);
        let mut ctrl3 = accel_read8(&mut self.inner.conn, REG_CTRL3_A)?;
        let mut ctrl6 = accel_read8(&mut self.inner.conn, REG_CTRL6_A)?;
        if in_ctrl3 { ctrl3 |= mask; } else { ctrl6 |= mask; }
        accel_write(&mut self.inner.conn, REG_CTRL3_A, ctrl3)?;
        accel_write(&mut self.inner.conn, REG_CTRL6_A, ctrl6)?;
        self.int_sources |= source;
        Ok(())
    }

    /// Remove one interrupt source from INT1 or INT2.
    ///
    /// # Arguments
    /// * `source` — One of the `SOURCE_*` constants.
    /// * `pin` — 1 (default) or 2.
    ///
    /// Panics if `pin` is not 1 or 2, the source is not routable on the
    /// requested pin, or the source is unknown.
    pub fn disable_interrupt(&mut self, source: u16, pin: u8) -> Result<(), I2C::Error> {
        let (in_ctrl3, mask) = route_bit(source, pin);
        let mut ctrl3 = accel_read8(&mut self.inner.conn, REG_CTRL3_A)?;
        let mut ctrl6 = accel_read8(&mut self.inner.conn, REG_CTRL6_A)?;
        if in_ctrl3 { ctrl3 &= !mask; } else { ctrl6 &= !mask; }
        accel_write(&mut self.inner.conn, REG_CTRL3_A, ctrl3)?;
        accel_write(&mut self.inner.conn, REG_CTRL6_A, ctrl6)?;
        self.int_sources &= !source;
        Ok(())
    }

    /// Read the source registers of every enabled interrupt source.
    ///
    /// Reading INTx_SOURCE_A / CLICK_SRC_A clears their latched flags, so a
    /// poll acknowledges the INT line.
    ///
    /// Returns the combined status — bit 0 [`SOURCE_DATA_READY`], bit 1
    /// [`SOURCE_WATERMARK`], bit 2 [`SOURCE_OVERRUN`], bit 3 [`SOURCE_IA1`],
    /// bit 4 [`SOURCE_IA2`], bit 5 [`SOURCE_CLICK`], bit 7
    /// [`SOURCE_MAG_DATA_READY`]. [`SOURCE_BOOT`] is pin-only and never reported.
    pub fn poll_interrupt(&mut self) -> Result<u16, I2C::Error> {
        let mut status = 0u16;
        if self.int_sources & SOURCE_DATA_READY != 0 {
            if accel_read8(&mut self.inner.conn, REG_STATUS_A)? & 0x08 != 0 {
                status |= SOURCE_DATA_READY;
            }
        }
        if self.int_sources & (SOURCE_WATERMARK | SOURCE_OVERRUN) != 0 {
            let fifo_src = accel_read8(&mut self.inner.conn, REG_FIFO_SRC_A)?;
            if fifo_src & 0x80 != 0 { status |= SOURCE_WATERMARK; }
            if fifo_src & 0x40 != 0 { status |= SOURCE_OVERRUN; }
        }
        if self.int_sources & SOURCE_IA1 != 0 {
            if accel_read8(&mut self.inner.conn, REG_INT1_SRC_A)? & 0x40 != 0 {
                status |= SOURCE_IA1;
            }
        }
        if self.int_sources & SOURCE_IA2 != 0 {
            if accel_read8(&mut self.inner.conn, REG_INT2_SRC_A)? & 0x40 != 0 {
                status |= SOURCE_IA2;
            }
        }
        if self.int_sources & SOURCE_CLICK != 0 {
            if accel_read8(&mut self.inner.conn, REG_CLICK_SRC_A)? & 0x40 != 0 {
                status |= SOURCE_CLICK;
            }
        }
        // The magnetometer DRDY flag is always polled (both slaves are
        // managed by this driver); it is reported but not routable.
        if mag_read8(&mut self.inner.conn, REG_SR_M)? & 0x01 != 0 {
            status |= SOURCE_MAG_DATA_READY;
        }
        Ok(status)
    }

    /// Reboot the accelerometer memory content (BOOT = 1).
    ///
    /// Reloads the factory trim values; waits ≥ 5 ms. The datasheet does not
    /// say whether the control registers survive, so re-run the
    /// initialisation (construct a fresh driver) afterwards.
    pub fn reboot<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        let ctrl5 = accel_read8(&mut self.inner.conn, REG_CTRL5_A)?;
        accel_write(&mut self.inner.conn, REG_CTRL5_A, ctrl5 | 0x80)?;
        delay.delay_ms(5);
        Ok(())
    }

    /// Convert a threshold in g to the 7-bit INTx_THS / CLICK_THS code.
    fn encode_threshold(&self, threshold_g: f32) -> u8 {
        let fs = [2.0f32, 4.0, 8.0, 16.0][self.inner.range_bits as usize];
        let code = libm::roundf(threshold_g * 128.0 / fs) as i32;
        code.clamp(0, 127) as u8
    }

    /// Convert a duration in ms to the 1/ODR code, clamped to the width.
    fn encode_duration(&self, duration_ms: f32, max_bits: u8) -> u8 {
        let code = libm::roundf(duration_ms * self.inner.odr_hz / 1000.0) as i32;
        code.clamp(0, (1i32 << max_bits) - 1) as u8
    }
}

/// Register tuple (CFG, SRC, THS, DUR) for generator 1 or 2.
fn int_regs(generator: u8) -> (u8, u8, u8, u8) {
    match generator {
        1 => (REG_INT1_CFG_A, REG_INT1_SRC_A, REG_INT1_THS_A, REG_INT1_DUR_A),
        2 => (REG_INT2_CFG_A, REG_INT2_SRC_A, REG_INT2_THS_A, REG_INT2_DUR_A),
        _ => panic!("generator must be 1 or 2"),
    }
}

/// Routing bit for one interrupt source: (true = CTRL_REG3_A / INT1 side,
/// mask). DATA_READY / WATERMARK / OVERRUN are hard-wired to INT1; the other
/// sources route to the requested pin; BOOT is INT2-only. Panics on an
/// unroutable source, invalid pin, or unknown source.
fn route_bit(source: u16, pin: u8) -> (bool, u8) {
    if source == SOURCE_MAG_DATA_READY {
        panic!("SOURCE_MAG_DATA_READY is on the magnetometer DRDY pin and is not routable");
    }
    if pin != 1 && pin != 2 {
        panic!("pin must be 1 or 2");
    }
    match source {
        SOURCE_DATA_READY => (true, I1_DRDY1),
        SOURCE_WATERMARK  => (true, I1_WTM),
        SOURCE_OVERRUN    => (true, I1_OVERRUN),
        SOURCE_IA1   => if pin == 1 { (true, I1_AOI1) } else { (false, I2_INT1) },
        SOURCE_IA2   => if pin == 1 { (true, I1_AOI2) } else { (false, I2_INT2) },
        SOURCE_CLICK => if pin == 1 { (true, I1_CLICK) } else { (false, I2_CLICK) },
        SOURCE_BOOT => {
            if pin != 2 {
                panic!("SOURCE_BOOT is INT2-only");
            }
            (false, BOOT_I1)
        }
        _ => panic!("unknown interrupt source 0x{:X}", source),
    }
}

/// Combine high and low byte into a signed 16-bit value.
fn s16(hi: u8, lo: u8) -> i16 {
    (((hi as u16) << 8) | lo as u16) as i16
}

/// Write one accelerometer register.
fn accel_write<I2C: I2c>(conn: &mut Connection<I2C>, reg: u8, value: u8) -> Result<(), I2C::Error> {
    conn.write(ACCEL_ADDR, reg, &[value])
}

/// Read one accelerometer register.
fn accel_read8<I2C: I2c>(conn: &mut Connection<I2C>, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    conn.read(ACCEL_ADDR, reg, &mut buf)?;
    Ok(buf[0])
}

/// Burst-read `buf.len()` accelerometer registers, setting the sub-address
/// auto-increment bit (bit 7) required for every multi-byte transfer.
fn accel_read6<I2C: I2c>(conn: &mut Connection<I2C>, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    conn.read(ACCEL_ADDR, reg | 0x80, buf)
}

/// Write one magnetometer register (no auto-increment bit on this slave).
fn mag_write<I2C: I2c>(conn: &mut Connection<I2C>, reg: u8, value: u8) -> Result<(), I2C::Error> {
    conn.write(MAG_ADDR, reg, &[value])
}

/// Read one magnetometer register.
fn mag_read8<I2C: I2c>(conn: &mut Connection<I2C>, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    mag_read(conn, reg, &mut buf)?;
    Ok(buf[0])
}

/// Burst-read `buf.len()` magnetometer registers; the address pointer
/// auto-increments by itself.
fn mag_read<I2C: I2c>(conn: &mut Connection<I2C>, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    conn.read(MAG_ADDR, reg, buf)
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ACC: u8 = 0x19;
    const MAG: u8 = 0x1E;

    /// Transaction sequence emitted by `Lsm303Full::new` on a fresh chip.
    fn init_transactions() -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(MAG, vec![REG_IRA_M], vec![0x48, 0x34, 0x33]),
            I2cTransaction::write(ACC, vec![REG_CTRL2_A, 0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL3_A, 0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL5_A, 0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL6_A, 0x00]),
            I2cTransaction::write(ACC, vec![REG_FIFO_CTRL_A, 0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL4_A, 0x88]),
            I2cTransaction::write(ACC, vec![REG_CTRL1_A, 0x57]),
            I2cTransaction::write(MAG, vec![REG_CRA_M, 0x10]),
            I2cTransaction::write(MAG, vec![REG_CRB_M, 0x20]),
            I2cTransaction::write(MAG, vec![REG_MR_M, 0x00]),
        ]
    }

    fn new_chip(extra: &[I2cTransaction]) -> Lsm303Full<I2cMock> {
        let mut transactions = init_transactions();
        transactions.extend_from_slice(extra);
        let i2c = I2cMock::new(&transactions);
        let mut delay = NoopDelay::new();
        Lsm303Full::new(Connection::new(i2c), &mut delay).expect("init")
    }

    #[test]
    fn init_sequence() {
        let chip = new_chip(&[]);
        chip.inner.conn.release().done();
    }

    #[test]
    #[should_panic(expected = "magnetometer ID")]
    fn identity_mismatch_panics() {
        let transactions = vec![
            I2cTransaction::write_read(MAG, vec![REG_IRA_M], vec![0x48, 0x34, 0x00]),
        ];
        let i2c = I2cMock::new(&transactions);
        let mut delay = NoopDelay::new();
        let _ = Lsm303Minimal::new(Connection::new(i2c), &mut delay);
    }

    #[test]
    fn accel_conversion_and_sensitivity() {
        // HR, ±2 g: raw16 0x1000 = 4096 → >>4 = 256 counts → 0.256 g.
        let mut chip = new_chip(&[
            I2cTransaction::write_read(ACC, vec![REG_OUT_X_L_A | 0x80],
                vec![0x00, 0x10, 0x00, 0x00, 0x00, 0x40]),
            I2cTransaction::write_read(ACC, vec![REG_OUT_X_L_A | 0x80],
                vec![0x00, 0x10, 0x00, 0x00, 0x00, 0x40]),
            // set_range(4): read CTRL4 (0x88), write FS=01 → 0x98.
            I2cTransaction::write_read(ACC, vec![REG_CTRL4_A], vec![0x88]),
            I2cTransaction::write(ACC, vec![REG_CTRL4_A, 0x98]),
            I2cTransaction::write_read(ACC, vec![REG_OUT_X_L_A | 0x80],
                vec![0x00, 0x10, 0x00, 0x00, 0x00, 0x40]),
            // set_mode(0): CTRL1 |= LPen → 0x5F; CTRL4 &= ~HR → 0x90.
            I2cTransaction::write_read(ACC, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL4_A], vec![0x98]),
            I2cTransaction::write(ACC, vec![REG_CTRL1_A, 0x5F]),
            I2cTransaction::write(ACC, vec![REG_CTRL4_A, 0x90]),
            I2cTransaction::write_read(ACC, vec![REG_OUT_X_L_A | 0x80],
                vec![0x00, 0x10, 0x00, 0x00, 0x00, 0x40]),
            I2cTransaction::write_read(ACC, vec![REG_OUT_X_L_A | 0x80],
                vec![0x00, 0x10, 0x00, 0x00, 0x00, 0x40]),
        ]);
        let (x, y, z) = chip.read_acceleration().unwrap();
        assert!((x - 0.256).abs() < 1e-6);
        assert!((y - 0.0).abs() < 1e-6);
        assert!((z - 1.024).abs() < 1e-6);
        assert_eq!(chip.read_acceleration_raw().unwrap(), (256, 0, 1024));

        chip.set_range(4).unwrap();
        let (x, _, z) = chip.read_acceleration().unwrap();
        assert!((x - 0.512).abs() < 1e-6);  // 256 counts * 2 mg
        assert!((z - 2.048).abs() < 1e-6);

        chip.set_mode(0).unwrap();
        assert_eq!(chip.read_acceleration_raw().unwrap(), (16, 0, 64));  // 4096 >> 8
        let (x, _, z) = chip.read_acceleration().unwrap();
        assert!((x - 0.512).abs() < 1e-6);  // 16 counts * 32 mg (range still ±4 g)
        assert!((z - 2.048).abs() < 1e-6);
        chip.inner.conn.release().done();
    }

    #[test]
    fn mag_reorder_and_gains() {
        // On the wire: X, Z, Y, each high byte first.
        let bytes = vec![0x01, 0x00, 0x02, 0x00, 0x03, 0x00];
        let mut chip = new_chip(&[
            I2cTransaction::write_read(MAG, vec![REG_OUT_X_H_M], bytes.clone()),
            I2cTransaction::write_read(MAG, vec![REG_OUT_X_H_M], bytes.clone()),
            // set_mag_range(810): GN = 111 → CRB = 0xE0.
            I2cTransaction::write(MAG, vec![REG_CRB_M, 0xE0]),
            I2cTransaction::write_read(MAG, vec![REG_OUT_X_H_M], bytes),
        ]);
        let (mx, my, mz) = chip.read_magnetic().unwrap();
        assert!((mx - 256.0 / 1100.0 * 100.0).abs() < 1e-3);   // X = 0x0100, XY gain 1100
        assert!((my - 768.0 / 1100.0 * 100.0).abs() < 1e-3);   // Y = 0x0300 (bytes 4..6)
        assert!((mz - 512.0 / 980.0 * 100.0).abs() < 1e-3);    // Z = 0x0200 (bytes 2..4)
        assert_eq!(chip.read_magnetic_raw().unwrap(), (256, 768, 512));

        chip.set_mag_range(810).unwrap();
        let (mx, _, _) = chip.read_magnetic().unwrap();
        assert!((mx - 256.0 / 230.0 * 100.0).abs() < 1e-3);    // 810 µT gain 230 LSB/gauss
        chip.inner.conn.release().done();
    }

    #[test]
    fn range_and_data_rate_validation() {
        let mut chip = new_chip(&[
            // set_data_rate(50): ODR = 0100 → CTRL1 0x47.
            I2cTransaction::write_read(ACC, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write(ACC, vec![REG_CTRL1_A, 0x47]),
            // set_mode(0): CTRL1 |= LPen → 0x5F; CTRL4 &= ~HR → 0x80.
            I2cTransaction::write_read(ACC, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL4_A], vec![0x88]),
            I2cTransaction::write(ACC, vec![REG_CTRL1_A, 0x5F]),
            I2cTransaction::write(ACC, vec![REG_CTRL4_A, 0x80]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL1_A], vec![0x5F]),
            I2cTransaction::write(ACC, vec![REG_CTRL1_A, 0x8F]),
        ]);
        // 1620 Hz in high-resolution mode is rejected before touching the bus.
        chip.set_data_rate(50).unwrap();
        assert_eq!(chip.inner.odr_hz, 50.0);
        chip.set_mode(0).unwrap();
        chip.set_data_rate(1620).unwrap();
        assert_eq!(chip.inner.odr_hz, 1620.0);
        chip.inner.conn.release().done();
    }

    #[test]
    #[should_panic(expected = "1620 Hz requires low-power mode")]
    fn data_rate_1620_in_high_resolution_panics() {
        let mut chip = new_chip(&[]);
        let _ = chip.set_data_rate(1620);
    }

    #[test]
    #[should_panic(expected = "5376 Hz requires low-power mode")]
    fn data_rate_5376_in_normal_mode_panics() {
        let mut chip = new_chip(&[
            // set_mode(1): CTRL1 &= ~LPen → 0x57; CTRL4 &= ~HR → 0x80.
            I2cTransaction::write_read(ACC, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL4_A], vec![0x88]),
            I2cTransaction::write(ACC, vec![REG_CTRL1_A, 0x57]),
            I2cTransaction::write(ACC, vec![REG_CTRL4_A, 0x80]),
        ]);
        chip.set_mode(1).unwrap();
        let _ = chip.set_data_rate(5376);
    }

    #[test]
    #[should_panic(expected = "1344 Hz requires normal or high-resolution mode")]
    fn data_rate_1344_in_low_power_panics() {
        let mut chip = new_chip(&[
            I2cTransaction::write_read(ACC, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL4_A], vec![0x88]),
            I2cTransaction::write(ACC, vec![REG_CTRL1_A, 0x5F]),
            I2cTransaction::write(ACC, vec![REG_CTRL4_A, 0x80]),
        ]);
        chip.set_mode(0).unwrap();
        let _ = chip.set_data_rate(1344);
    }

    #[test]
    #[should_panic(expected = "range_g must be one of 2, 4, 8, 16")]
    fn invalid_range_panics() {
        let mut chip = new_chip(&[]);
        let _ = chip.set_range(3);
    }

    #[test]
    fn fifo_encoding_and_drain() {
        let mut chip = new_chip(&[
            // set_fifo(2, 16, false): FM=10, FTH=16 → 0x90; CTRL5 FIFO_EN.
            I2cTransaction::write(ACC, vec![REG_FIFO_CTRL_A, 0x90]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL5_A], vec![0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL5_A, 0x40]),
            // set_fifo(3, 5, true): FM=11, TR, FTH=5 → 0xE5; FIFO_EN stays.
            I2cTransaction::write(ACC, vec![REG_FIFO_CTRL_A, 0xE5]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL5_A], vec![0x40]),
            I2cTransaction::write(ACC, vec![REG_CTRL5_A, 0x40]),
            // set_fifo(0, 0, false): bypass clears FIFO_EN.
            I2cTransaction::write(ACC, vec![REG_FIFO_CTRL_A, 0x00]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL5_A], vec![0x40]),
            I2cTransaction::write(ACC, vec![REG_CTRL5_A, 0x00]),
            // fifo_level: FSS = 2.
            I2cTransaction::write_read(ACC, vec![REG_FIFO_SRC_A], vec![0x02]),
            // read_fifo(None): level read, then two 6-byte pops.
            I2cTransaction::write_read(ACC, vec![REG_FIFO_SRC_A], vec![0x02]),
            I2cTransaction::write_read(ACC, vec![REG_OUT_X_L_A | 0x80],
                vec![0x00, 0x10, 0x00, 0x00, 0x00, 0x40]),
            I2cTransaction::write_read(ACC, vec![REG_OUT_X_L_A | 0x80],
                vec![0x00, 0x20, 0x00, 0x00, 0x00, 0x80]),
            // read_fifo(Some(1)): one pop, no level read.
            I2cTransaction::write_read(ACC, vec![REG_OUT_X_L_A | 0x80],
                vec![0x00, 0x30, 0x00, 0x00, 0x00, 0xC0]),
            // read_fifo_status: WTM=1, OVRN=1, EMPTY=0, FSS=5.
            I2cTransaction::write_read(ACC, vec![REG_FIFO_SRC_A], vec![0xC5]),
        ]);
        chip.set_fifo(2, 16, false).unwrap();
        chip.set_fifo(3, 5, true).unwrap();
        chip.set_fifo(0, 0, false).unwrap();
        assert_eq!(chip.fifo_level().unwrap(), 2);
        let samples = chip.read_fifo(None).unwrap();
        assert_eq!(samples.len(), 2);
        assert!((samples[0].0 - 0.256).abs() < 1e-6);
        assert!((samples[1].0 - 0.512).abs() < 1e-6);
        assert!((samples[1].2 - (-2.048)).abs() < 1e-6);  // 0x8000 >> 4 = -2048
        let one = chip.read_fifo(Some(1)).unwrap();
        assert_eq!(one.len(), 1);
        assert!((one[0].0 - 0.768).abs() < 1e-6);
        let (wtm, ovrn, empty, level) = chip.read_fifo_status().unwrap();
        assert!(wtm && ovrn && !empty);
        assert_eq!(level, 5);
        chip.inner.conn.release().done();
    }

    #[test]
    fn free_fall_encoding() {
        // set_free_fall(1, 0.35, 30) at ±2 g, 100 Hz:
        // CFG = AOI | ZLIE|YLIE|XLIE = 0x95; THS = round(0.35*128/2) = 22;
        // DUR = round(30*100/1000) = 3.
        let mut chip = new_chip(&[
            I2cTransaction::write(ACC, vec![REG_INT1_CFG_A, 0x95]),
            I2cTransaction::write(ACC, vec![REG_INT1_THS_A, 22]),
            I2cTransaction::write(ACC, vec![REG_INT1_DUR_A, 3]),
        ]);
        chip.set_free_fall(1, 0.35, 30.0).unwrap();
        chip.inner.conn.release().done();
    }

    #[test]
    fn click_encoding() {
        // set_click(1, 4, 0.5, 10, 20, 50) at ±2 g, 100 Hz:
        // single X spreads to bit 1 (XS) = 0x02, double Z to bit 4 (ZD) = 0x10;
        // THS = round(0.5*128/2) = 32; TL = 1, LAT = 2, WIN = 5.
        let mut chip = new_chip(&[
            I2cTransaction::write(ACC, vec![REG_CLICK_CFG_A, 0x12]),
            I2cTransaction::write(ACC, vec![REG_CLICK_THS_A, 32]),
            I2cTransaction::write(ACC, vec![REG_TIME_LIMIT_A, 1]),
            I2cTransaction::write(ACC, vec![REG_TIME_LAT_A, 2]),
            I2cTransaction::write(ACC, vec![REG_TIME_WIN_A, 5]),
        ]);
        chip.set_click(1, 4, 0.5, 10.0, 20.0, 50.0).unwrap();
        chip.inner.conn.release().done();
    }

    #[test]
    #[should_panic(expected = "single / double are axis masks")]
    fn invalid_click_mask_panics() {
        let mut chip = new_chip(&[]);
        let _ = chip.set_click(8, 0, 0.5, 10.0, 20.0, 50.0);
    }

    #[test]
    fn interrupt_routing() {
        let mut chip = new_chip(&[
            // enable_interrupt(SOURCE_CLICK, 2): CTRL6 |= I2_CLICK.
            I2cTransaction::write_read(ACC, vec![REG_CTRL3_A], vec![0x00]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL6_A], vec![0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL3_A, 0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL6_A, 0x80]),
            // enable_interrupt(SOURCE_DATA_READY, 1): CTRL3 |= I1_DRDY1.
            I2cTransaction::write_read(ACC, vec![REG_CTRL3_A], vec![0x00]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL6_A], vec![0x80]),
            I2cTransaction::write(ACC, vec![REG_CTRL3_A, 0x10]),
            I2cTransaction::write(ACC, vec![REG_CTRL6_A, 0x80]),
            // disable_interrupt(SOURCE_DATA_READY, 1).
            I2cTransaction::write_read(ACC, vec![REG_CTRL3_A], vec![0x10]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL6_A], vec![0x80]),
            I2cTransaction::write(ACC, vec![REG_CTRL3_A, 0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL6_A, 0x80]),
            // set_interrupt_polarity(true): CTRL6 |= H_LACTIVE.
            I2cTransaction::write_read(ACC, vec![REG_CTRL6_A], vec![0x80]),
            I2cTransaction::write(ACC, vec![REG_CTRL6_A, 0x82]),
        ]);
        chip.enable_interrupt(SOURCE_CLICK, 2).unwrap();
        chip.enable_interrupt(SOURCE_DATA_READY, 1).unwrap();
        chip.disable_interrupt(SOURCE_DATA_READY, 1).unwrap();
        chip.set_interrupt_polarity(true).unwrap();
        chip.inner.conn.release().done();
    }

    #[test]
    #[should_panic(expected = "SOURCE_BOOT is INT2-only")]
    fn boot_on_int1_panics() {
        let mut chip = new_chip(&[]);
        let _ = chip.enable_interrupt(SOURCE_BOOT, 1);
    }

    #[test]
    #[should_panic(expected = "not routable")]
    fn mag_data_ready_not_routable_panics() {
        let mut chip = new_chip(&[]);
        let _ = chip.enable_interrupt(SOURCE_MAG_DATA_READY, 1);
    }

    #[test]
    fn poll_interrupt_combined_status() {
        let mut chip = new_chip(&[
            // Enable DATA_READY (pin 1), WATERMARK (pin 1), IA1 (pin 1), CLICK (pin 2).
            I2cTransaction::write_read(ACC, vec![REG_CTRL3_A], vec![0x00]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL6_A], vec![0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL3_A, I1_DRDY1]),
            I2cTransaction::write(ACC, vec![REG_CTRL6_A, 0x00]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL3_A], vec![I1_DRDY1]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL6_A], vec![0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL3_A, I1_DRDY1 | I1_WTM]),
            I2cTransaction::write(ACC, vec![REG_CTRL6_A, 0x00]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL3_A], vec![I1_DRDY1 | I1_WTM]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL6_A], vec![0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL3_A, I1_DRDY1 | I1_WTM | I1_AOI1]),
            I2cTransaction::write(ACC, vec![REG_CTRL6_A, 0x00]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL3_A], vec![I1_DRDY1 | I1_WTM | I1_AOI1]),
            I2cTransaction::write_read(ACC, vec![REG_CTRL6_A], vec![0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL3_A, I1_DRDY1 | I1_WTM | I1_AOI1]),
            I2cTransaction::write(ACC, vec![REG_CTRL6_A, I2_CLICK]),
            // poll_interrupt: STATUS.ZYXDA, FIFO WTM+OVRN, INT1.IA, CLICK.IA, mag DRDY.
            I2cTransaction::write_read(ACC, vec![REG_STATUS_A], vec![0x08]),
            I2cTransaction::write_read(ACC, vec![REG_FIFO_SRC_A], vec![0xC0]),
            I2cTransaction::write_read(ACC, vec![REG_INT1_SRC_A], vec![0x40]),
            I2cTransaction::write_read(ACC, vec![REG_CLICK_SRC_A], vec![0x40]),
            I2cTransaction::write_read(MAG, vec![REG_SR_M], vec![0x01]),
        ]);
        chip.enable_interrupt(SOURCE_DATA_READY, 1).unwrap();
        chip.enable_interrupt(SOURCE_WATERMARK, 1).unwrap();
        chip.enable_interrupt(SOURCE_IA1, 1).unwrap();
        chip.enable_interrupt(SOURCE_CLICK, 2).unwrap();
        let status = chip.poll_interrupt().unwrap();
        assert_eq!(status,
                   SOURCE_DATA_READY | SOURCE_WATERMARK | SOURCE_OVERRUN
                   | SOURCE_IA1 | SOURCE_CLICK | SOURCE_MAG_DATA_READY);
        chip.inner.conn.release().done();
    }

    #[test]
    fn mag_gain_rate_mode_and_status() {
        let mut chip = new_chip(&[
            // set_mag_range(190): GN = 010 → CRB = 0x40.
            I2cTransaction::write(MAG, vec![REG_CRB_M, 0x40]),
            // set_mag_data_rate(220): DO = 111, keep TEMP_EN=0 → CRA 0x1C.
            I2cTransaction::write_read(MAG, vec![REG_CRA_M], vec![0x10]),
            I2cTransaction::write(MAG, vec![REG_CRA_M, 0x1C]),
            // set_mag_mode(1): single conversion.
            I2cTransaction::write(MAG, vec![REG_MR_M, 0x01]),
            // mag_data_ready: DRDY set.
            I2cTransaction::write_read(MAG, vec![REG_SR_M], vec![0x01]),
            // read_mag_status: DRDY + LOCK.
            I2cTransaction::write_read(MAG, vec![REG_SR_M], vec![0x03]),
            // read_mag_identity.
            I2cTransaction::write_read(MAG, vec![REG_IRA_M], vec![0x48, 0x34, 0x33]),
        ]);
        chip.set_mag_range(190).unwrap();
        chip.set_mag_data_rate(220.0).unwrap();
        chip.set_mag_mode(1).unwrap();
        assert!(chip.mag_data_ready().unwrap());
        let (drdy, lock) = chip.read_mag_status().unwrap();
        assert!(drdy && lock);
        assert_eq!(chip.read_mag_identity().unwrap(), [0x48, 0x34, 0x33]);
        chip.inner.conn.release().done();
    }

    #[test]
    #[should_panic(expected = "range_ut must be one of 130, 190, 250, 400, 470, 560, 810")]
    fn invalid_mag_range_panics() {
        let mut chip = new_chip(&[]);
        let _ = chip.set_mag_range(500);
    }

    #[test]
    fn temperature() {
        let mut chip = new_chip(&[
            // First read_temperature_delta enables TEMP_EN: CRA 0x10 → 0x90.
            I2cTransaction::write_read(MAG, vec![REG_CRA_M], vec![0x10]),
            I2cTransaction::write(MAG, vec![REG_CRA_M, 0x90]),
            // 12-bit left-justified: 0x0100 → >>4 = 16 → 2.0 °C.
            I2cTransaction::write_read(MAG, vec![REG_TEMP_H_M], vec![0x01, 0x00]),
            // Second call reads directly (TEMP_EN already set).
            I2cTransaction::write_read(MAG, vec![REG_TEMP_H_M], vec![0xF0, 0x00]),
            // enable_temperature(false): CRA 0x90 → 0x10.
            I2cTransaction::write_read(MAG, vec![REG_CRA_M], vec![0x90]),
            I2cTransaction::write(MAG, vec![REG_CRA_M, 0x10]),
        ]);
        let t = chip.read_temperature_delta().unwrap();
        assert!((t - 2.0).abs() < 1e-6);
        let t2 = chip.read_temperature_delta().unwrap();
        // 0xF000 sign-extended = -4096 → >>4 = -256 → -32.0 °C.
        assert!((t2 - (-32.0)).abs() < 1e-6);
        chip.enable_temperature(false).unwrap();
        chip.inner.conn.release().done();
    }

    #[test]
    fn heading_flat_45_degrees() {
        let bytes = vec![0x01, 0x00, 0x00, 0x00, 0x01, 0x00];
        let mut chip = new_chip(&[
            I2cTransaction::write_read(MAG, vec![REG_OUT_X_H_M], bytes.clone()),
            I2cTransaction::write_read(MAG, vec![REG_OUT_X_H_M], bytes),
            // Level device (z ≈ 1 g, x = y = 0): tilt compensation is a no-op.
            I2cTransaction::write_read(ACC, vec![REG_OUT_X_L_A | 0x80],
                vec![0x00, 0x00, 0x00, 0x00, 0x00, 0x40]),
        ]);
        // mx = my = 0x0100 → atan2(1, 1) = 45°.
        let flat = chip.heading(false).unwrap();
        assert!((flat - 45.0).abs() < 1e-4);
        // Level device (z ≈ 1 g, x = y = 0): tilt compensation is a no-op.
        let tilted = chip.heading(true).unwrap();
        assert!((tilted - 45.0).abs() < 1e-4);
        chip.inner.conn.release().done();
    }

    #[test]
    fn axes_power_down_high_pass_and_reboot() {
        let mut chip = new_chip(&[
            // set_axes(false, true, false): CTRL1 keeps ODR|LPen, Xen=0, Zen=0.
            I2cTransaction::write_read(ACC, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write(ACC, vec![REG_CTRL1_A, 0x52]),
            // power_down: ODR = 0000.
            I2cTransaction::write_read(ACC, vec![REG_CTRL1_A], vec![0x52]),
            I2cTransaction::write(ACC, vec![REG_CTRL1_A, 0x02]),
            // set_high_pass(1, 2, true, false, true, false): HPM=01, HPCF=10, FDS.
            I2cTransaction::write(ACC, vec![REG_CTRL2_A, 0x40 | 0x20 | 0x08 | 0x02]),
            // reset_high_pass: read REFERENCE_A.
            I2cTransaction::write_read(ACC, vec![REG_REFERENCE_A], vec![0x2A]),
            // set_reference(42).
            I2cTransaction::write(ACC, vec![REG_REFERENCE_A, 42]),
            // reboot: CTRL5 |= BOOT.
            I2cTransaction::write_read(ACC, vec![REG_CTRL5_A], vec![0x00]),
            I2cTransaction::write(ACC, vec![REG_CTRL5_A, 0x80]),
            // new_data_available: ZYXDA set.
            I2cTransaction::write_read(ACC, vec![REG_STATUS_A], vec![0x08]),
            // read_status.
            I2cTransaction::write_read(ACC, vec![REG_STATUS_A], vec![0x20]),
        ]);
        chip.set_axes(false, true, false).unwrap();
        chip.power_down().unwrap();
        assert_eq!(chip.inner.odr_hz, 0.0);
        chip.set_high_pass(1, 2, true, false, true, false).unwrap();
        assert_eq!(chip.reset_high_pass().unwrap(), 0x2A);
        chip.set_reference(42).unwrap();
        let mut delay = NoopDelay::new();
        chip.reboot(&mut delay).unwrap();
        assert!(chip.new_data_available().unwrap());
        assert_eq!(chip.read_status().unwrap(), 0x20);
        chip.inner.conn.release().done();
    }
}
