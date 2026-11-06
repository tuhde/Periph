//! FXOS8700CQ — 6-axis e-compass: 14-bit accelerometer + 16-bit magnetometer (NXP).
//!
//! The FXOS8700CQ combines a three-axis MEMS accelerometer (±2 / ±4 / ±8 *g*,
//! 0.244 / 0.488 / 0.976 mg/LSB) and a three-axis magnetometer (fixed ±1200 µT
//! range, 0.1 µT/LSB) behind **one I²C address** and **one shared ADC**. In
//! *hybrid mode* both sensors are sampled in an interleaved fashion at half
//! the programmed output data rate (ODR) and all twelve data bytes can be read
//! in a single burst. The accelerometer side adds a 32-sample FIFO, a
//! high-pass filtered output, embedded freefall / motion, transient, tap,
//! orientation and Auto-SLEEP / WAKE detectors; the magnetometer side adds
//! hard-iron offsets, an autonomous min / max tracker with hardware
//! auto-calibration and a relative die-temperature sensor.
//!
//! I²C framing is the plain register-pointer protocol (write the register
//! address, repeated START, read); multi-byte transfers auto-increment within
//! one transaction. The address is `0x1C`–`0x1F` selected by `SA1` / `SA0`;
//! do not leave `SA0` floating (it would select SPI mode).
//!
//! The vector-magnitude detectors (0x5F-0x72), the magnetic threshold detector
//! (0x52-0x5A) and the per-axis freefall / motion thresholds (0x73-0x78) are
//! not wrapped: use [`Fxos8700Full::read_register`] /
//! [`Fxos8700Full::write_register`].
//!
//! ## Constants
//!
//! Interrupt sources: [`SOURCE_DATA_READY`], [`SOURCE_ACCEL_VECM`],
//! [`SOURCE_FF_MT`], [`SOURCE_PULSE`], [`SOURCE_ORIENTATION`],
//! [`SOURCE_TRANSIENT`], [`SOURCE_FIFO`], [`SOURCE_AUTO_SLEEP`]

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::connection::Connection;
use crate::connection::register::to_signed;

/// I²C address for `SA1` = 0, `SA0` = 0 (default).
pub const I2C_ADDRESS: u8 = 0x1E;

/// New sample (accelerometer in accel-only mode, magnetometer otherwise).
pub const SOURCE_DATA_READY: u8 = 0x01;
/// Accelerometer vector-magnitude change (configured via raw registers).
pub const SOURCE_ACCEL_VECM: u8 = 0x02;
/// Freefall / motion (`A_FFMT_SRC.ea`).
pub const SOURCE_FF_MT: u8 = 0x04;
/// Single / double tap (`PULSE_SRC.ea`).
pub const SOURCE_PULSE: u8 = 0x08;
/// Orientation change (`PL_STATUS.newlp`).
pub const SOURCE_ORIENTATION: u8 = 0x10;
/// Transient (`TRANSIENT_SRC.ea`).
pub const SOURCE_TRANSIENT: u8 = 0x20;
/// FIFO overflow or watermark.
pub const SOURCE_FIFO: u8 = 0x40;
/// WAKE <-> SLEEP transition.
pub const SOURCE_AUTO_SLEEP: u8 = 0x80;

/// Operating mode: accelerometer only.
pub const MODE_ACCEL_ONLY: u8 = 0;
/// Operating mode: magnetometer only.
pub const MODE_MAG_ONLY: u8 = 1;
/// Operating mode: hybrid (both sensors at half the ODR each).
pub const MODE_HYBRID: u8 = 2;

/// FIFO disabled.
pub const FIFO_DISABLED: u8 = 0;
/// FIFO circular mode (oldest sample discarded).
pub const FIFO_CIRCULAR: u8 = 1;
/// FIFO fill mode (stops accepting when full).
pub const FIFO_FILL: u8 = 2;
/// FIFO trigger mode.
pub const FIFO_TRIGGER: u8 = 3;

// --- Registers ---------------------------------------------------------------
const REG_STATUS: u8 = 0x00;
const REG_OUT_X_MSB: u8 = 0x01;
const REG_F_SETUP: u8 = 0x09;
const REG_TRIG_CFG: u8 = 0x0A;
const REG_SYSMOD: u8 = 0x0B;
const REG_INT_SOURCE: u8 = 0x0C;
const REG_WHO_AM_I: u8 = 0x0D;
const REG_XYZ_DATA_CFG: u8 = 0x0E;
const REG_HP_FILTER_CUTOFF: u8 = 0x0F;
const REG_PL_STATUS: u8 = 0x10;
const REG_PL_CFG: u8 = 0x11;
const REG_PL_COUNT: u8 = 0x12;
const REG_PL_BF_ZCOMP: u8 = 0x13;
const REG_PL_THS: u8 = 0x14;
const REG_A_FFMT_CFG: u8 = 0x15;
const REG_A_FFMT_SRC: u8 = 0x16;
const REG_A_FFMT_THS: u8 = 0x17;
const REG_A_FFMT_COUNT: u8 = 0x18;
const REG_TRANSIENT_CFG: u8 = 0x1D;
const REG_TRANSIENT_SRC: u8 = 0x1E;
const REG_TRANSIENT_THS: u8 = 0x1F;
const REG_TRANSIENT_COUNT: u8 = 0x20;
const REG_PULSE_CFG: u8 = 0x21;
const REG_PULSE_SRC: u8 = 0x22;
const REG_PULSE_THSX: u8 = 0x23;
const REG_PULSE_TMLT: u8 = 0x26;
const REG_PULSE_LTCY: u8 = 0x27;
const REG_PULSE_WIND: u8 = 0x28;
const REG_ASLP_COUNT: u8 = 0x29;
const REG_CTRL1: u8 = 0x2A;
const REG_CTRL2: u8 = 0x2B;
const REG_CTRL3: u8 = 0x2C;
const REG_CTRL4: u8 = 0x2D;
const REG_CTRL5: u8 = 0x2E;
const REG_OFF_X: u8 = 0x2F;
const REG_M_DR_STATUS: u8 = 0x32;
const REG_M_OUT_X_MSB: u8 = 0x33;
const REG_M_OFF_X_MSB: u8 = 0x3F;
const REG_MAX_X_MSB: u8 = 0x45;
const REG_MIN_X_MSB: u8 = 0x4B;
const REG_TEMP: u8 = 0x51;
const REG_M_CTRL1: u8 = 0x5B;
const REG_M_CTRL2: u8 = 0x5C;
const REG_M_CTRL3: u8 = 0x5D;

// --- Bit masks ---------------------------------------------------------------
const CTRL1_ACTIVE: u8 = 0x01;
const CTRL1_F_READ: u8 = 0x02;
const CTRL1_LNOISE: u8 = 0x04;
const CTRL1_DR_MASK: u8 = 0x38;
const CTRL1_ASLP_MASK: u8 = 0xC0;
const CTRL2_ST: u8 = 0x80;
const CTRL2_RST: u8 = 0x40;
const CTRL2_SMODS_MASK: u8 = 0x18;
const CTRL2_SLPE: u8 = 0x04;
const CTRL2_MODS_MASK: u8 = 0x03;
const STATUS_ZYXDR: u8 = 0x08;
const M_CTRL1_ACAL: u8 = 0x80;
const M_CTRL1_RST: u8 = 0x40;
const M_CTRL1_OST: u8 = 0x20;
const M_CTRL1_OS_MASK: u8 = 0x1C;
const M_CTRL1_HMS_MASK: u8 = 0x03;
const M_CTRL2_HYB_AUTOINC: u8 = 0x20;

/// System output data rates selected by `CTRL_REG1.dr` (Hz).
const ODR_HZ: [f32; 8] = [800.0, 400.0, 200.0, 100.0, 50.0, 12.5, 6.25, 1.5625];

/// Debounce time steps in ms (also `PULSE_TMLT` with the pulse LPF on),
/// indexed `[mods][row]`; `row` is the `dr` code, shifted by one in hybrid mode.
const STEP_MS: [[f32; 8]; 4] = [
    [1.25, 2.5, 5.0, 10.0, 20.0, 20.0, 20.0, 20.0],
    [1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 80.0, 80.0],
    [1.25, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5],
    [1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 160.0, 160.0],
];

/// `PULSE_TMLT` steps with the pulse low-pass filter off (`PULSE_LTCY` /
/// `PULSE_WIND` steps are twice as large).
const PULSE_STEP_NO_LPF_MS: [[f32; 8]; 4] = [
    [0.625, 0.625, 1.25, 2.5, 5.0, 5.0, 5.0, 5.0],
    [0.625, 0.625, 1.25, 2.5, 5.0, 20.0, 20.0, 20.0],
    [0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625],
    [0.625, 1.25, 2.5, 5.0, 10.0, 40.0, 40.0, 40.0],
];

/// Orientation trip angle (degrees) -> `P_L_THS<4:0>`.
const PL_THRESHOLDS: [(u8, u8); 10] = [
    (15, 0x07), (20, 0x09), (30, 0x0C), (35, 0x0D), (40, 0x0F),
    (45, 0x10), (55, 0x13), (60, 0x14), (70, 0x17), (75, 0x19),
];

/// Auto-SLEEP rates (Hz) -> `CTRL_REG1.aslp_rate`.
const ASLP_RATES: [(f32, u8); 4] = [(50.0, 0), (12.5, 1), (6.25, 2), (1.56, 3)];

/// Tap (pulse) detector configuration for [`Fxos8700Full::set_pulse`].
#[derive(Clone, Copy, Debug)]
pub struct PulseConfig {
    /// Axis mask (X = 1, Y = 2, Z = 4) for single-tap detection.
    pub single: u8,
    /// Axis mask for double-tap detection.
    pub double: u8,
    /// `(x, y, z)` thresholds in *g* (0.063 g/LSB).
    pub threshold_g: [f32; 3],
    /// Pulse time limit in ms.
    pub time_limit_ms: f32,
    /// Latency between the two taps in ms.
    pub latency_ms: f32,
    /// Window for the second tap in ms.
    pub window_ms: f32,
    /// Latch until `PULSE_SRC` is read.
    pub latch: bool,
    /// Abort double-tap detection on a pulse during the latency window.
    pub double_abort: bool,
    /// Enable the pulse low-pass filter.
    pub lpf: bool,
    /// Enable the pulse high-pass filter.
    pub hpf: bool,
}

impl Default for PulseConfig {
    fn default() -> Self {
        Self {
            single: 0,
            double: 0,
            threshold_g: [0.5, 0.5, 0.5],
            time_limit_ms: 30.0,
            latency_ms: 100.0,
            window_ms: 300.0,
            latch: false,
            double_abort: false,
            lpf: true,
            hpf: true,
        }
    }
}

/// FXOS8700 minimal driver — acceleration and magnetic field.
///
/// The constructor runs the datasheet initialization sequence: it verifies
/// `WHO_AM_I` (0xC7, or 0xC4 on pre-production parts), performs a software
/// reset (the `rst` write is not acknowledged; the resulting error is
/// ignored) and starts hybrid mode.
///
/// Default configuration: hybrid mode, system ODR 200 Hz (100 Hz per sensor),
/// ±2 *g* (4096 counts/*g*), 14-bit output, normal accelerometer oversampling,
/// magnetometer OSR 7, low-noise off, `hyb_autoinc_mode` on, no FIFO, no
/// detectors, no interrupt routing, interrupts active-low push-pull.
pub struct Fxos8700Minimal<I2C, D> {
    conn: Connection<I2C>,
    delay: D,
    addr: u8,
    mode: u8,
    dr: u8,
    fs_g: u8,
    mods: u8,
    lnoise: bool,
    f_read: bool,
    fifo_mode: u8,
    hyb: bool,
}

impl<I2C: I2c, D: DelayNs> Fxos8700Minimal<I2C, D> {
    /// Create a new `Fxos8700Minimal` and run the initialization sequence.
    ///
    /// # Arguments
    /// * `conn` — [`Connection`] bound to the chip's I²C bus.
    /// * `addr` — 7-bit I²C address, `0x1C`–`0x1F` ([`I2C_ADDRESS`] = `0x1E`).
    /// * `delay` — delay source for the boot, reset and turn-on times.
    ///
    /// # Panics
    /// Panics if `WHO_AM_I` is neither 0xC7 nor 0xC4 — wrong chip, wrong
    /// address or a wiring problem.
    pub fn new(conn: Connection<I2C>, addr: u8, delay: D) -> Result<Self, I2C::Error> {
        let mut chip = Self {
            conn,
            delay,
            addr,
            mode: 2,
            dr: 2,
            fs_g: 2,
            mods: 0,
            lnoise: false,
            f_read: false,
            fifo_mode: 0,
            hyb: true,
        };
        chip.init()?;
        Ok(chip)
    }

    /// Run the datasheet initialization sequence (also used to resume after a
    /// software reset).
    ///
    /// # Panics
    /// Panics if `WHO_AM_I` is neither 0xC7 nor 0xC4.
    pub fn init(&mut self) -> Result<(), I2C::Error> {
        self.delay.delay_ms(1);
        let who = self.who_am_i()?;
        if who != 0xC7 && who != 0xC4 {
            panic!("FXOS8700 WHO_AM_I: expected 0xC7 or 0xC4, got 0x{:02X}", who);
        }
        self.soft_reset();
        let who = self.who_am_i()?;
        if who != 0xC7 && who != 0xC4 {
            panic!("FXOS8700 not found after reset (WHO_AM_I 0x{:02X})", who);
        }
        self.write_reg(REG_CTRL1, 0x00)?;
        self.write_reg(REG_M_CTRL1, 0x1F)?;
        self.write_reg(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC)?;
        self.write_reg(REG_XYZ_DATA_CFG, 0x00)?;
        self.write_reg(REG_CTRL2, 0x00)?;
        self.write_reg(REG_CTRL1, 0x11)?;
        self.mode = 2;
        self.dr = 2;
        self.fs_g = 2;
        self.mods = 0;
        self.lnoise = false;
        self.f_read = false;
        self.fifo_mode = 0;
        self.hyb = true;
        let ms = self.turn_on_ms();
        self.delay.delay_ms(ms);
        Ok(())
    }

    /// Read the acceleration (one burst read of 0x01–0x06).
    ///
    /// Returns `(x, y, z)` in *g*.
    pub fn read_accel(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let (x, y, z) = self.read_accel_counts()?;
        let scale = self.accel_scale();
        Ok((x as f32 / scale, y as f32 / scale, z as f32 / scale))
    }

    /// Read the magnetic flux density (one burst read of 0x33–0x38; reading
    /// `M_OUT_X_MSB` first latches the X / Y / Z set).
    ///
    /// Returns `(x, y, z)` in µT.
    pub fn read_mag(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let (x, y, z) = self.read_mag_counts()?;
        Ok((x as f32 * 0.1, y as f32 * 0.1, z as f32 * 0.1))
    }

    // --- Internal helpers (shared with Fxos8700Full) -------------------------

    /// Read one register.
    pub(crate) fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.conn.read(self.addr, reg, &mut buf)?;
        Ok(buf[0])
    }

    /// Write one register.
    pub(crate) fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.conn.write(self.addr, reg, &[value])
    }

    /// Read-modify-write: clear `mask`, OR in `value`.
    pub(crate) fn modify(&mut self, reg: u8, mask: u8, value: u8) -> Result<(), I2C::Error> {
        let cur = self.read_reg(reg)?;
        self.write_reg(reg, (cur & !mask) | (value & mask))
    }

    /// Read `WHO_AM_I`, retrying on a bus error for up to 10 ms.
    fn who_am_i(&mut self) -> Result<u8, I2C::Error> {
        for _ in 0..10 {
            if let Ok(v) = self.read_reg(REG_WHO_AM_I) {
                return Ok(v);
            }
            self.delay.delay_ms(1);
        }
        self.read_reg(REG_WHO_AM_I)
    }

    /// Write `CTRL_REG2.rst`; the chip does not acknowledge it, so the bus
    /// error is ignored. Waits 2 ms.
    pub(crate) fn soft_reset(&mut self) {
        let _ = self.write_reg(REG_CTRL2, CTRL2_RST);
        self.delay.delay_ms(2);
    }

    /// Counts per *g* at the current range and resolution.
    pub(crate) fn accel_scale(&self) -> f32 {
        let cpg = match self.fs_g {
            2 => 4096.0,
            4 => 2048.0,
            _ => 1024.0,
        };
        if self.f_read { cpg / 64.0 } else { cpg }
    }

    /// Decode three accelerometer axes from `data[offset..]`.
    pub(crate) fn decode_accel(&self, data: &[u8], offset: usize) -> (i16, i16, i16) {
        if self.f_read {
            return (data[offset] as i8 as i16, data[offset + 1] as i8 as i16, data[offset + 2] as i8 as i16);
        }
        let axis = |i: usize| -> i16 {
            let raw = ((data[offset + 2 * i] as u32) << 8) | data[offset + 2 * i + 1] as u32;
            (to_signed(raw, 16) >> 2) as i16
        };
        (axis(0), axis(1), axis(2))
    }

    /// Decode three magnetometer axes (16-bit, MSB first) from `data[offset..]`.
    pub(crate) fn decode_mag(data: &[u8], offset: usize) -> (i16, i16, i16) {
        let axis = |i: usize| -> i16 {
            let raw = ((data[offset + 2 * i] as u32) << 8) | data[offset + 2 * i + 1] as u32;
            to_signed(raw, 16) as i16
        };
        (axis(0), axis(1), axis(2))
    }

    /// Read the signed accelerometer counts.
    pub(crate) fn read_accel_counts(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut buf = [0u8; 6];
        let n = if self.f_read { 3 } else { 6 };
        self.conn.read(self.addr, REG_OUT_X_MSB, &mut buf[..n])?;
        Ok(self.decode_accel(&buf, 0))
    }

    /// Read the signed magnetometer counts.
    pub(crate) fn read_mag_counts(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut buf = [0u8; 6];
        self.conn.read(self.addr, REG_M_OUT_X_MSB, &mut buf)?;
        Ok(Self::decode_mag(&buf, 0))
    }

    /// STANDBY -> ACTIVE turn-on time, 2 / ODR + 2 ms (rounded up).
    pub(crate) fn turn_on_ms(&self) -> u32 {
        (2000.0 / ODR_HZ[self.dr as usize]) as u32 + 3
    }
}

/// FXOS8700 full driver — the complete chip functionality.
///
/// Methods that change a STANDBY-only register put the chip into STANDBY,
/// write, and restore ACTIVE internally, so the caller never manages the
/// mode. Every such change flushes the FIFO and resets the detectors'
/// debounce counters.
///
/// The Rust analog of "Full never duplicates Minimal" is composition: `inner`
/// owns the minimal driver, every inherited method is a one-line delegate,
/// and the Full-only methods follow. Setters that validate their argument
/// return `Ok(false)` when it is rejected (nothing is written) and `Ok(true)`
/// when it was applied.
pub struct Fxos8700Full<I2C, D> {
    inner: Fxos8700Minimal<I2C, D>,
}

impl<I2C: I2c, D: DelayNs> Fxos8700Full<I2C, D> {
    /// Create a new `Fxos8700Full` and run the initialization sequence.
    ///
    /// # Panics
    /// Panics if `WHO_AM_I` is neither 0xC7 nor 0xC4.
    pub fn new(conn: Connection<I2C>, addr: u8, delay: D) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Fxos8700Minimal::new(conn, addr, delay)? })
    }

    /// Re-run the initialization sequence (use after [`Fxos8700Full::reset`]).
    pub fn init(&mut self) -> Result<(), I2C::Error> {
        self.inner.init()
    }

    /// Read the acceleration in *g*; see [`Fxos8700Minimal::read_accel`].
    pub fn read_accel(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read_accel()
    }

    /// Read the magnetic flux density in µT; see [`Fxos8700Minimal::read_mag`].
    pub fn read_mag(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read_mag()
    }

    // --- Mode / power --------------------------------------------------------

    /// Poll `SYSMOD` until it reads 00 (up to 350 ms). Returns `false` on a timeout.
    fn wait_standby(&mut self) -> Result<bool, I2C::Error> {
        for _ in 0..350 {
            if self.inner.read_reg(REG_SYSMOD)? & 0x03 == 0 {
                return Ok(true);
            }
            self.inner.delay.delay_ms(1);
        }
        Ok(false)
    }

    /// Enter STANDBY (`active` = 0) and wait until `SYSMOD` reads 0 (up to
    /// 300 ms when leaving mag-only / hybrid mode). Returns `false` on a timeout.
    pub fn standby(&mut self) -> Result<bool, I2C::Error> {
        let c1 = self.inner.read_reg(REG_CTRL1)?;
        self.inner.write_reg(REG_CTRL1, c1 & !CTRL1_ACTIVE)?;
        self.wait_standby()
    }

    /// Enter ACTIVE (`active` = 1) and wait the turn-on time.
    pub fn activate(&mut self) -> Result<(), I2C::Error> {
        let c1 = self.inner.read_reg(REG_CTRL1)?;
        self.inner.write_reg(REG_CTRL1, c1 | CTRL1_ACTIVE)?;
        let ms = self.inner.turn_on_ms();
        self.inner.delay.delay_ms(ms);
        Ok(())
    }

    /// Enter STANDBY if ACTIVE; returns whether the chip was ACTIVE.
    fn begin(&mut self) -> Result<bool, I2C::Error> {
        let c1 = self.inner.read_reg(REG_CTRL1)?;
        if c1 & CTRL1_ACTIVE != 0 {
            self.inner.write_reg(REG_CTRL1, c1 & !CTRL1_ACTIVE)?;
            self.wait_standby()?;
            return Ok(true);
        }
        Ok(false)
    }

    /// Restore ACTIVE if `was_active`.
    fn end(&mut self, was_active: bool) -> Result<(), I2C::Error> {
        if was_active { self.activate() } else { Ok(()) }
    }

    /// Software reset (`CTRL_REG2.rst`). The bus error of the unacknowledged
    /// write is ignored; every register returns to its reset value. Call
    /// [`Fxos8700Full::init`] afterwards.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.soft_reset();
        self.inner.mode = 0;
        self.inner.dr = 0;
        self.inner.fs_g = 2;
        self.inner.mods = 0;
        self.inner.lnoise = false;
        self.inner.f_read = false;
        self.inner.fifo_mode = 0;
        self.inner.hyb = false;
        Ok(())
    }

    /// Select accelerometer-only (0), magnetometer-only (1) or hybrid (2)
    /// operation; hybrid also enables `hyb_autoinc_mode`.
    pub fn set_mode(&mut self, mode: u8) -> Result<bool, I2C::Error> {
        if mode > 2 {
            return Ok(false);
        }
        let was = self.begin()?;
        self.inner.modify(REG_M_CTRL1, M_CTRL1_HMS_MASK, [0, 1, 3][mode as usize])?;
        if mode == 2 {
            self.inner.modify(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC, M_CTRL2_HYB_AUTOINC)?;
            self.inner.hyb = true;
        }
        self.inner.mode = mode;
        self.end(was)?;
        Ok(true)
    }

    /// Select the accelerometer range (2, 4 or 8 *g*). Rejected for any other
    /// value and for 8 *g* while low-noise mode is on.
    pub fn set_range(&mut self, range_g: u8) -> Result<bool, I2C::Error> {
        if !matches!(range_g, 2 | 4 | 8) || (range_g == 8 && self.inner.lnoise) {
            return Ok(false);
        }
        let fs = match range_g { 2 => 0, 4 => 1, _ => 2 };
        self.inner.modify(REG_XYZ_DATA_CFG, 0x03, fs)?;
        self.inner.fs_g = range_g;
        Ok(true)
    }

    /// Select the system output data rate: 800, 400, 200, 100, 50, 12.5, 6.25
    /// or 1.5625 Hz (1.56 accepted). Each sensor runs at half this rate in
    /// hybrid mode.
    pub fn set_data_rate(&mut self, rate_hz: f32) -> Result<bool, I2C::Error> {
        let mut code: Option<u8> = None;
        for (i, hz) in ODR_HZ.iter().enumerate() {
            if (hz - rate_hz).abs() <= 0.01 * hz {
                code = Some(i as u8);
            }
        }
        let code = match code { Some(c) => c, None => return Ok(false) };
        let was = self.begin()?;
        self.inner.modify(REG_CTRL1, CTRL1_DR_MASK, code << 3)?;
        self.inner.dr = code;
        self.end(was)?;
        Ok(true)
    }

    /// Select the accelerometer oversampling mode: 0 normal, 1 low-noise
    /// low-power, 2 high resolution, 3 low power. `sleep_mode` (same
    /// encoding, `None` = unchanged) selects the SLEEP-mode oversampling.
    pub fn set_accel_oversampling(&mut self, mode: u8, sleep_mode: Option<u8>) -> Result<bool, I2C::Error> {
        if mode > 3 || sleep_mode.map_or(false, |s| s > 3) {
            return Ok(false);
        }
        let was = self.begin()?;
        self.inner.modify(REG_CTRL2, CTRL2_MODS_MASK, mode)?;
        if let Some(s) = sleep_mode {
            self.inner.modify(REG_CTRL2, CTRL2_SMODS_MASK, s << 3)?;
        }
        self.inner.mods = mode;
        self.end(was)?;
        Ok(true)
    }

    /// Select the magnetometer oversampling ratio code (`m_os`, 0–7; datasheet
    /// Table 203) and optionally the Auto-SLEEP code (`m_aslp_os`).
    pub fn set_mag_oversampling(&mut self, ratio: u8, sleep_ratio: Option<u8>) -> Result<bool, I2C::Error> {
        if ratio > 7 || sleep_ratio.map_or(false, |s| s > 7) {
            return Ok(false);
        }
        self.inner.modify(REG_M_CTRL1, M_CTRL1_OS_MASK, ratio << 2)?;
        if let Some(s) = sleep_ratio {
            self.inner.modify(REG_M_CTRL3, 0x70, s << 4)?;
        }
        Ok(true)
    }

    /// Enable or disable the reduced-noise mode (limits the range to ±4 *g*).
    /// Rejected when enabling at ±8 *g*.
    pub fn set_low_noise(&mut self, enabled: bool) -> Result<bool, I2C::Error> {
        if enabled && self.inner.fs_g == 8 {
            return Ok(false);
        }
        let was = self.begin()?;
        self.inner.modify(REG_CTRL1, CTRL1_LNOISE, if enabled { CTRL1_LNOISE } else { 0 })?;
        self.inner.lnoise = enabled;
        self.end(was)?;
        Ok(true)
    }

    /// Select 14-bit (`14`) or 8-bit fast-read (`8`) accelerometer output.
    /// Rejected for another value and while the FIFO is on.
    pub fn set_resolution(&mut self, bits: u8) -> Result<bool, I2C::Error> {
        if (bits != 14 && bits != 8) || self.inner.fifo_mode != 0 {
            return Ok(false);
        }
        let was = self.begin()?;
        self.inner.modify(REG_CTRL1, CTRL1_F_READ, if bits == 8 { CTRL1_F_READ } else { 0 })?;
        self.inner.f_read = bits == 8;
        self.end(was)?;
        Ok(true)
    }

    // --- Data ------------------------------------------------------------------

    /// Read the raw accelerometer counts (14-bit right-aligned, or 8-bit).
    pub fn read_raw_accel(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        self.inner.read_accel_counts()
    }

    /// Read the raw magnetometer counts (16-bit, 0.1 µT/LSB).
    pub fn read_raw_mag(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        self.inner.read_mag_counts()
    }

    /// Read both sensors with one burst from 0x01.
    ///
    /// Returns `Some(((ax, ay, az) in g, (mx, my, mz) in µT))`, or `None`
    /// when the chip is not in hybrid mode.
    pub fn read_hybrid(&mut self) -> Result<Option<((f32, f32, f32), (f32, f32, f32))>, I2C::Error> {
        if self.inner.mode != 2 || !self.inner.hyb {
            return Ok(None);
        }
        let n_accel = if self.inner.f_read { 3 } else { 6 };
        let mut buf = [0u8; 12];
        self.inner.conn.read(self.inner.addr, REG_OUT_X_MSB, &mut buf[..n_accel + 6])?;
        let a = self.inner.decode_accel(&buf, 0);
        let m = Fxos8700Minimal::<I2C, D>::decode_mag(&buf, n_accel);
        let s = self.inner.accel_scale();
        Ok(Some((
            (a.0 as f32 / s, a.1 as f32 / s, a.2 as f32 / s),
            (m.0 as f32 * 0.1, m.1 as f32 * 0.1, m.2 as f32 * 0.1),
        )))
    }

    /// Read the die temperature in °C (`int8(TEMP) × 0.96`; relative and
    /// uncalibrated). `None` in accelerometer-only mode.
    pub fn read_temperature(&mut self) -> Result<Option<f32>, I2C::Error> {
        if self.inner.mode == 0 {
            return Ok(None);
        }
        Ok(Some(self.inner.read_reg(REG_TEMP)? as i8 as f32 * 0.96))
    }

    /// `true` if a new accelerometer sample is ready (`STATUS.zyxdr`; invalid
    /// while the FIFO is on).
    pub fn new_accel_data(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_STATUS)? & STATUS_ZYXDR != 0)
    }

    /// `true` if a new magnetometer sample is ready (`M_DR_STATUS.zyxdr`).
    pub fn new_mag_data(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_M_DR_STATUS)? & STATUS_ZYXDR != 0)
    }

    /// Read the `STATUS` byte.
    pub fn read_status(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_STATUS)
    }

    /// Read the `M_DR_STATUS` byte.
    pub fn read_mag_status(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_M_DR_STATUS)
    }

    /// Read `SYSMOD` bits 1:0 (0 standby, 1 wake, 2 sleep).
    pub fn read_system_mode(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.inner.read_reg(REG_SYSMOD)? & 0x03)
    }

    /// Select high-pass-filtered output data (`hpf_out`) and the cutoff code
    /// (`sel`, 0–3; datasheet Table 64). Rejected for a cutoff above 3.
    pub fn set_high_pass_output(&mut self, enabled: bool, cutoff: u8) -> Result<bool, I2C::Error> {
        if cutoff > 3 {
            return Ok(false);
        }
        let was = self.begin()?;
        self.inner.modify(REG_XYZ_DATA_CFG, 0x10, if enabled { 0x10 } else { 0 })?;
        self.inner.modify(REG_HP_FILTER_CUTOFF, 0x03, cutoff)?;
        self.end(was)?;
        Ok(true)
    }

    /// Write the per-axis accelerometer offsets in *g* (added to the output,
    /// 2 mg/LSB, ±0.256 *g*).
    pub fn set_accel_offset(&mut self, x_g: f32, y_g: f32, z_g: f32) -> Result<(), I2C::Error> {
        let enc = |v: f32| -> u8 { (libm::roundf(v / 0.002) as i32).clamp(-128, 127) as i8 as u8 };
        let data = [enc(x_g), enc(y_g), enc(z_g)];
        let was = self.begin()?;
        self.inner.conn.write(self.inner.addr, REG_OFF_X, &data)?;
        self.end(was)
    }

    /// Read the accelerometer offsets in *g*.
    pub fn get_accel_offset(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut d = [0u8; 3];
        self.inner.conn.read(self.inner.addr, REG_OFF_X, &mut d)?;
        Ok((d[0] as i8 as f32 * 0.002, d[1] as i8 as f32 * 0.002, d[2] as i8 as f32 * 0.002))
    }

    /// Write the hard-iron offsets in µT (15-bit value in bits 15:1,
    /// *subtracted* from each measurement, 0.1 µT/LSB).
    pub fn set_mag_offset(&mut self, x_ut: f32, y_ut: f32, z_ut: f32) -> Result<(), I2C::Error> {
        let mut data = [0u8; 6];
        for (i, v) in [x_ut, y_ut, z_ut].iter().enumerate() {
            let value = (libm::roundf(v / 0.1) as i32).clamp(-16384, 16383);
            let word = ((value << 1) & 0xFFFF) as u16;
            data[2 * i] = (word >> 8) as u8;
            data[2 * i + 1] = (word & 0xFF) as u8;
        }
        self.inner.conn.write(self.inner.addr, REG_M_OFF_X_MSB, &data)
    }

    /// Read the hard-iron offsets in µT.
    pub fn get_mag_offset(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut d = [0u8; 6];
        self.inner.conn.read(self.inner.addr, REG_M_OFF_X_MSB, &mut d)?;
        let axis = |i: usize| -> f32 {
            let word = ((d[2 * i] as u32) << 8) | d[2 * i + 1] as u32;
            (to_signed(word, 16) >> 1) as f32 * 0.1
        };
        Ok((axis(0), axis(1), axis(2)))
    }

    /// Select raw magnetometer output (`m_raw`): `true` = the hard-iron
    /// offsets are not applied to the output.
    pub fn set_mag_raw(&mut self, raw: bool) -> Result<(), I2C::Error> {
        self.inner.modify(REG_M_CTRL3, 0x80, if raw { 0x80 } else { 0 })
    }

    /// Enable or disable hardware hard-iron auto-calibration (`m_acal`).
    /// Enabling also clears `m_raw` and enables min/max tracking. Rotate the
    /// board through all orientations while on; the offsets stay in `M_OFF_*`
    /// afterwards (NXP AN4399).
    pub fn set_mag_auto_calibration(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        if enabled {
            self.inner.modify(REG_M_CTRL3, 0x80, 0)?;
            let was = self.begin()?;
            self.inner.modify(REG_M_CTRL2, 0x10, 0)?;
            self.end(was)?;
        }
        self.inner.modify(REG_M_CTRL1, M_CTRL1_ACAL, if enabled { M_CTRL1_ACAL } else { 0 })
    }

    /// Read the tracked magnetic minimum and maximum.
    ///
    /// Returns `((minx, miny, minz), (maxx, maxy, maxz))` in µT.
    pub fn read_mag_min_max(&mut self) -> Result<((f32, f32, f32), (f32, f32, f32)), I2C::Error> {
        let mut d = [0u8; 6];
        self.inner.conn.read(self.inner.addr, REG_MIN_X_MSB, &mut d)?;
        let mn = Fxos8700Minimal::<I2C, D>::decode_mag(&d, 0);
        self.inner.conn.read(self.inner.addr, REG_MAX_X_MSB, &mut d)?;
        let mx = Fxos8700Minimal::<I2C, D>::decode_mag(&d, 0);
        Ok((
            (mn.0 as f32 * 0.1, mn.1 as f32 * 0.1, mn.2 as f32 * 0.1),
            (mx.0 as f32 * 0.1, mx.1 as f32 * 0.1, mx.2 as f32 * 0.1),
        ))
    }

    /// Reset the min/max registers to 0x7FFF / 0x8000 (`m_maxmin_rst`).
    pub fn reset_mag_min_max(&mut self) -> Result<(), I2C::Error> {
        self.inner.modify(REG_M_CTRL2, 0x04, 0x04)
    }

    /// Select the automatic magnetic reset (degauss) period: 0 every ODR
    /// cycle, 1 every 16, 2 every 512, 3 disabled. Rejected above 3.
    pub fn set_mag_reset(&mut self, mode: u8) -> Result<bool, I2C::Error> {
        if mode > 3 {
            return Ok(false);
        }
        let was = self.begin()?;
        self.inner.modify(REG_M_CTRL2, 0x03, mode)?;
        self.end(was)?;
        Ok(true)
    }

    /// Trigger a one-shot magnetic reset (`m_rst`).
    pub fn trigger_mag_reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.modify(REG_M_CTRL1, M_CTRL1_RST, M_CTRL1_RST)
    }

    /// Take one magnetic measurement in STANDBY (`m_ost`); ACTIVE is restored.
    ///
    /// Returns `(x, y, z)` in µT, or `None` in accelerometer-only mode or on a
    /// 1 s timeout.
    pub fn measure_mag_once(&mut self) -> Result<Option<(f32, f32, f32)>, I2C::Error> {
        if self.inner.mode == 0 {
            return Ok(None);
        }
        let was = self.begin()?;
        self.inner.modify(REG_M_CTRL1, M_CTRL1_OST, M_CTRL1_OST)?;
        let mut done = false;
        for _ in 0..1000 {
            if self.inner.read_reg(REG_M_CTRL1)? & M_CTRL1_OST == 0 {
                done = true;
                break;
            }
            self.inner.delay.delay_ms(1);
        }
        let value = if done { Some(self.inner.read_mag()?) } else { None };
        self.end(was)?;
        Ok(value)
    }

    /// Compute the tilt-compensated compass heading in degrees, `[0, 360)`
    /// (chip frame X forward, Y right, Z down; verify the axis signs on
    /// hardware). `declination_deg` is added to the result.
    pub fn heading(&mut self, declination_deg: f32) -> Result<f32, I2C::Error> {
        let (a, m) = match self.read_hybrid()? {
            Some(v) => v,
            None => (self.inner.read_accel()?, self.inner.read_mag()?),
        };
        let (ax, ay, az) = a;
        let (mx, my, mz) = m;
        let roll = libm::atan2f(ay, az);
        let pitch = libm::atan2f(-ax, ay * libm::sinf(roll) + az * libm::cosf(roll));
        let mx2 = mx * libm::cosf(pitch) + mz * libm::sinf(pitch);
        let my2 = mx * libm::sinf(roll) * libm::sinf(pitch) + my * libm::cosf(roll)
            - mz * libm::sinf(roll) * libm::cosf(pitch);
        let deg = libm::atan2f(-my2, mx2) * 180.0 / core::f32::consts::PI + declination_deg;
        let mut wrapped = deg % 360.0;
        if wrapped < 0.0 {
            wrapped += 360.0;
        }
        Ok(wrapped)
    }

    // --- FIFO --------------------------------------------------------------------

    /// Configure the 32-sample accelerometer FIFO: `mode` 0 disabled,
    /// 1 circular, 2 fill, 3 trigger; `watermark` 0–63. Changing between two
    /// enabled modes goes through disabled. The FIFO holds accelerometer data
    /// only.
    pub fn set_fifo(&mut self, mode: u8, watermark: u8) -> Result<bool, I2C::Error> {
        if mode > 3 || watermark > 63 {
            return Ok(false);
        }
        let was = self.begin()?;
        if self.inner.fifo_mode != 0 && mode != 0 && mode != self.inner.fifo_mode {
            self.inner.write_reg(REG_F_SETUP, 0x00)?;
        }
        self.inner.write_reg(REG_F_SETUP, (mode << 6) | watermark)?;
        self.inner.fifo_mode = mode;
        self.end(was)?;
        Ok(true)
    }

    /// Select the FIFO trigger sources (`TRIG_CFG`).
    pub fn set_fifo_trigger(&mut self, transient: bool, orientation: bool, pulse: bool, motion: bool)
        -> Result<(), I2C::Error>
    {
        let value = (if transient { 0x20 } else { 0 }) | (if orientation { 0x10 } else { 0 })
            | (if pulse { 0x08 } else { 0 }) | (if motion { 0x04 } else { 0 });
        let was = self.begin()?;
        self.inner.write_reg(REG_TRIG_CFG, value)?;
        self.end(was)
    }

    /// Number of samples in the FIFO (0–32).
    pub fn fifo_level(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.inner.read_reg(REG_STATUS)? & 0x3F)
    }

    /// Decode `F_STATUS` (clears `SOURCE_FIFO`): `(overflow, watermark, count)`.
    pub fn read_fifo_status(&mut self) -> Result<(bool, bool, u8), I2C::Error> {
        let v = self.inner.read_reg(REG_STATUS)?;
        Ok((v & 0x80 != 0, v & 0x40 != 0, v & 0x3F))
    }

    /// Drain accelerometer samples (in *g*) from the FIFO into `out`, one
    /// 6-byte (3 in 8-bit mode) transaction per sample so the hybrid
    /// auto-increment jump to the magnetometer never matters.
    ///
    /// Returns the number of samples written.
    pub fn read_fifo(&mut self, out: &mut [(f32, f32, f32)]) -> Result<usize, I2C::Error> {
        let n = (self.fifo_level()? as usize).min(out.len());
        let scale = self.inner.accel_scale();
        for slot in out.iter_mut().take(n) {
            let (x, y, z) = self.inner.read_accel_counts()?;
            *slot = (x as f32 / scale, y as f32 / scale, z as f32 / scale);
        }
        Ok(n)
    }

    /// Enable or disable the FIFO gate (`fifo_gate`).
    pub fn set_fifo_gate(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let was = self.begin()?;
        self.inner.modify(REG_CTRL3, 0x80, if enabled { 0x80 } else { 0 })?;
        self.end(was)
    }

    // --- Detectors -----------------------------------------------------------------

    /// Time-step row: the `dr` code, shifted by one in hybrid mode.
    fn row(&self) -> usize {
        (self.inner.dr as usize + if self.inner.mode == 2 { 1 } else { 0 }).min(7)
    }

    /// Debounce time step in ms at the effective ODR and oversampling mode.
    fn step_ms(&self) -> f32 {
        STEP_MS[self.inner.mods as usize][self.row()]
    }

    /// `PULSE_TMLT` time step in ms.
    fn pulse_step_ms(&self, lpf: bool) -> f32 {
        let row = self.row();
        if lpf { STEP_MS[self.inner.mods as usize][row] } else { PULSE_STEP_NO_LPF_MS[self.inner.mods as usize][row] }
    }

    /// Encode a 7-bit threshold (0.063 g/LSB).
    fn threshold(threshold_g: f32) -> u8 {
        (libm::roundf(threshold_g / 0.063) as i32).clamp(0, 127) as u8
    }

    /// Encode a time as `round(time_ms / step_ms)` clamped to a byte.
    fn count(time_ms: f32, step_ms: f32) -> u8 {
        (libm::roundf(time_ms / step_ms) as i32).clamp(0, 255) as u8
    }

    /// Configure the freefall / motion detector (`A_FFMT_*`).
    ///
    /// `motion` = `true` detects motion (any enabled axis above the
    /// threshold), `false` freefall (all below). `axes` is a mask (X = 1,
    /// Y = 2, Z = 4); `latch` holds the event until `A_FFMT_SRC` is read;
    /// `debounce_clear` clears (instead of decrements) the debounce counter
    /// when the condition is lost.
    pub fn set_freefall_motion(&mut self, motion: bool, axes: u8, threshold_g: f32, debounce_ms: f32,
                               latch: bool, debounce_clear: bool) -> Result<(), I2C::Error> {
        let cfg = (if latch { 0x80 } else { 0 }) | (if motion { 0x40 } else { 0 }) | ((axes & 7) << 3);
        let ths = (if debounce_clear { 0x80 } else { 0 }) | Self::threshold(threshold_g);
        let count = Self::count(debounce_ms, self.step_ms());
        let was = self.begin()?;
        self.inner.write_reg(REG_A_FFMT_CFG, cfg)?;
        self.inner.write_reg(REG_A_FFMT_THS, ths)?;
        self.inner.write_reg(REG_A_FFMT_COUNT, count)?;
        self.end(was)
    }

    /// Read `A_FFMT_SRC` (clears the flags when latched).
    pub fn read_motion_source(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_A_FFMT_SRC)
    }

    /// Configure the transient detector (`TRANSIENT_*`). `axes` is a mask
    /// (X = 1, Y = 2, Z = 4); `bypass_hpf` makes it behave like motion
    /// detection; `latch` holds the event until `TRANSIENT_SRC` is read.
    pub fn set_transient(&mut self, axes: u8, threshold_g: f32, debounce_ms: f32, bypass_hpf: bool,
                         latch: bool) -> Result<(), I2C::Error> {
        let cfg = (if latch { 0x10 } else { 0 }) | ((axes & 7) << 1) | (if bypass_hpf { 1 } else { 0 });
        let count = Self::count(debounce_ms, self.step_ms());
        let was = self.begin()?;
        self.inner.write_reg(REG_TRANSIENT_CFG, cfg)?;
        self.inner.write_reg(REG_TRANSIENT_THS, Self::threshold(threshold_g))?;
        self.inner.write_reg(REG_TRANSIENT_COUNT, count)?;
        self.end(was)
    }

    /// Read `TRANSIENT_SRC` — **clears** the interrupt.
    pub fn read_transient_source(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_TRANSIENT_SRC)
    }

    /// Configure tap (pulse) detection (`PULSE_*`); see [`PulseConfig`].
    pub fn set_pulse(&mut self, cfg: &PulseConfig) -> Result<(), I2C::Error> {
        let mut reg = (if cfg.double_abort { 0x80 } else { 0 }) | (if cfg.latch { 0x40 } else { 0 });
        for i in 0..3 {
            if (cfg.single >> i) & 1 != 0 {
                reg |= 1 << (2 * i);
            }
            if (cfg.double >> i) & 1 != 0 {
                reg |= 2 << (2 * i);
            }
        }
        let step = self.pulse_step_ms(cfg.lpf);
        let tmlt = Self::count(cfg.time_limit_ms, step);
        let ltcy = Self::count(cfg.latency_ms, 2.0 * step);
        let wind = Self::count(cfg.window_ms, 2.0 * step);
        let ths = [
            Self::threshold(cfg.threshold_g[0]),
            Self::threshold(cfg.threshold_g[1]),
            Self::threshold(cfg.threshold_g[2]),
        ];
        let was = self.begin()?;
        self.inner.write_reg(REG_PULSE_CFG, reg)?;
        self.inner.conn.write(self.inner.addr, REG_PULSE_THSX, &ths)?;
        self.inner.write_reg(REG_PULSE_TMLT, tmlt)?;
        self.inner.write_reg(REG_PULSE_LTCY, ltcy)?;
        self.inner.write_reg(REG_PULSE_WIND, wind)?;
        self.inner.modify(REG_HP_FILTER_CUTOFF, 0x30,
                          (if cfg.hpf { 0 } else { 0x20 }) | (if cfg.lpf { 0x10 } else { 0 }))?;
        self.end(was)
    }

    /// Read `PULSE_SRC` — **clears** the interrupt.
    pub fn read_pulse_source(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_PULSE_SRC)
    }

    /// Configure orientation (portrait / landscape) detection.
    ///
    /// `threshold_deg` is one of 15, 20, 30, 35, 40, 45, 55, 60, 70, 75;
    /// `hysteresis` the `hys` code 0–7; `z_lock` the Z-lock code 0–7;
    /// `back_front` the back/front trip code 0–3. Rejected for an invalid
    /// argument or when `pl_ths + hys` is outside 1–31.
    pub fn set_orientation(&mut self, enabled: bool, debounce_ms: f32, threshold_deg: u8, hysteresis: u8,
                           z_lock: u8, back_front: u8) -> Result<bool, I2C::Error> {
        let ths = match PL_THRESHOLDS.iter().find(|(deg, _)| *deg == threshold_deg) {
            Some((_, code)) => *code,
            None => return Ok(false),
        };
        if hysteresis > 7 || z_lock > 7 || back_front > 3 {
            return Ok(false);
        }
        let sum = ths as u32 + hysteresis as u32;
        if !(1..=31).contains(&sum) {
            return Ok(false);
        }
        let count = Self::count(debounce_ms, self.step_ms());
        let was = self.begin()?;
        self.inner.write_reg(REG_PL_CFG, 0x80 | if enabled { 0x40 } else { 0 })?;
        self.inner.write_reg(REG_PL_COUNT, count)?;
        self.inner.write_reg(REG_PL_THS, (ths << 3) | hysteresis)?;
        self.inner.write_reg(REG_PL_BF_ZCOMP, (back_front << 6) | z_lock)?;
        self.end(was)?;
        Ok(true)
    }

    /// Decode `PL_STATUS` — **clears** `newlp`.
    ///
    /// Returns `(changed, lockout, portrait_landscape, back)` where
    /// `portrait_landscape` is 0 portrait up, 1 portrait down, 2 landscape
    /// right, 3 landscape left.
    pub fn read_orientation(&mut self) -> Result<(bool, bool, u8, bool), I2C::Error> {
        let v = self.inner.read_reg(REG_PL_STATUS)?;
        Ok((v & 0x80 != 0, v & 0x40 != 0, (v >> 1) & 0x03, v & 0x01 != 0))
    }

    /// Configure Auto-SLEEP / WAKE: `sleep_rate_hz` is 50, 12.5, 6.25 or 1.56
    /// (halved in hybrid mode), `timeout_s` the inactivity time, and
    /// `wake_sources` a mask of `SOURCE_TRANSIENT`, `SOURCE_ORIENTATION`,
    /// `SOURCE_PULSE`, `SOURCE_FF_MT` and `SOURCE_ACCEL_VECM`. Rejected for an
    /// unsupported rate or source.
    pub fn set_auto_sleep(&mut self, enabled: bool, sleep_rate_hz: f32, timeout_s: f32, wake_sources: u8)
        -> Result<bool, I2C::Error>
    {
        let code = match ASLP_RATES.iter().find(|(hz, _)| (hz - sleep_rate_hz).abs() <= 0.01 * hz) {
            Some((_, c)) => *c,
            None => return Ok(false),
        };
        if wake_sources & !0x3E != 0 {
            return Ok(false);
        }
        let step = if self.inner.dr == 7 { 0.64 } else { 0.32 };
        let count = Self::count(timeout_s, step);
        let was = self.begin()?;
        self.inner.modify(REG_CTRL1, CTRL1_ASLP_MASK, code << 6)?;
        self.inner.write_reg(REG_ASLP_COUNT, count)?;
        self.inner.modify(REG_CTRL2, CTRL2_SLPE, if enabled { CTRL2_SLPE } else { 0 })?;
        self.inner.modify(REG_CTRL3, 0x7C, (wake_sources & 0x3E) << 1)?;
        self.end(was)?;
        Ok(true)
    }

    // --- Interrupts ------------------------------------------------------------------

    /// Configure the INT1 / INT2 polarity and drive (`ipol`, `pp_od`).
    pub fn set_interrupt_pins(&mut self, active_high: bool, open_drain: bool) -> Result<(), I2C::Error> {
        let was = self.begin()?;
        self.inner.modify(REG_CTRL3, 0x03,
                          (if active_high { 0x02 } else { 0 }) | (if open_drain { 0x01 } else { 0 }))?;
        self.end(was)
    }

    /// Enable interrupt `source` (a mask of the `SOURCE_*` constants) and
    /// route it to INT1 (`pin` = 1) or INT2 (`pin` = 2). Only routes the
    /// source; the detector behind it is configured with the `set_*` methods.
    /// Rejected for another pin.
    pub fn enable_interrupt(&mut self, source: u8, pin: u8) -> Result<bool, I2C::Error> {
        if pin != 1 && pin != 2 {
            return Ok(false);
        }
        let was = self.begin()?;
        self.inner.modify(REG_CTRL4, source, source)?;
        self.inner.modify(REG_CTRL5, source, if pin == 1 { source } else { 0 })?;
        self.end(was)?;
        Ok(true)
    }

    /// Disable interrupt `source` (clears its `int_en_*` bit).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        let was = self.begin()?;
        self.inner.modify(REG_CTRL4, source, 0)?;
        self.end(was)
    }

    /// Read `INT_SOURCE` and clear the pending sources: for every set bit
    /// except `SOURCE_DATA_READY` and `SOURCE_ACCEL_VECM` the matching source
    /// register is read so the flag (and the pin) clears.
    ///
    /// Returns a mask of the `SOURCE_*` constants.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        let status = self.inner.read_reg(REG_INT_SOURCE)?;
        const CLEAR: [(u8, u8); 6] = [
            (SOURCE_AUTO_SLEEP, REG_SYSMOD),
            (SOURCE_FIFO, REG_STATUS),
            (SOURCE_TRANSIENT, REG_TRANSIENT_SRC),
            (SOURCE_ORIENTATION, REG_PL_STATUS),
            (SOURCE_PULSE, REG_PULSE_SRC),
            (SOURCE_FF_MT, REG_A_FFMT_SRC),
        ];
        for (bit, reg) in CLEAR.iter() {
            if status & bit != 0 {
                self.inner.read_reg(*reg)?;
            }
        }
        Ok(status)
    }

    // --- Escape hatch / self-test --------------------------------------------------------

    /// Read raw register bytes (vector-magnitude, magnetic-threshold and
    /// per-axis FFMT registers). No STANDBY handling.
    pub fn read_register(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        self.inner.conn.read(self.inner.addr, reg, buf)
    }

    /// Write raw register bytes (at most 16). No STANDBY handling.
    pub fn write_register(&mut self, reg: u8, data: &[u8]) -> Result<(), I2C::Error> {
        self.inner.conn.write(self.inner.addr, reg, data)
    }

    /// Average 8 accelerometer readings (accel-only, 14-bit).
    fn average_counts(&mut self) -> Result<(i32, i32, i32), I2C::Error> {
        let (mut sx, mut sy, mut sz) = (0i32, 0i32, 0i32);
        for _ in 0..8 {
            self.inner.delay.delay_ms(12);
            let (x, y, z) = self.inner.read_accel_counts()?;
            sx += x as i32;
            sy += y as i32;
            sz += z as i32;
        }
        Ok((sx.div_euclid(8), sy.div_euclid(8), sz.div_euclid(8)))
    }

    /// Run the accelerometer self-test and restore the configuration.
    ///
    /// STANDBY, ±2 *g*, 100 Hz accelerometer-only, normal oversampling; the
    /// difference of two 8-sample averages (before / after `st`) is checked
    /// against the datasheet minimum X ≥ +192, Y ≥ +270, Z ≥ +1275 counts.
    ///
    /// Returns `(ok, dx, dy, dz)` with the differences in 14-bit counts.
    pub fn self_test(&mut self) -> Result<(bool, i16, i16, i16), I2C::Error> {
        let s_ctrl1 = self.inner.read_reg(REG_CTRL1)?;
        let s_ctrl2 = self.inner.read_reg(REG_CTRL2)?;
        let s_cfg = self.inner.read_reg(REG_XYZ_DATA_CFG)?;
        let s_m1 = self.inner.read_reg(REG_M_CTRL1)?;
        let cache = (self.inner.mode, self.inner.dr, self.inner.fs_g, self.inner.mods,
                     self.inner.lnoise, self.inner.f_read);
        self.standby()?;
        self.inner.write_reg(REG_XYZ_DATA_CFG, 0x00)?;
        self.inner.write_reg(REG_M_CTRL1, s_m1 & !M_CTRL1_HMS_MASK)?;
        self.inner.write_reg(REG_CTRL2, 0x00)?;
        self.inner.mode = 0;
        self.inner.dr = 3;
        self.inner.fs_g = 2;
        self.inner.mods = 0;
        self.inner.lnoise = false;
        self.inner.f_read = false;
        self.inner.write_reg(REG_CTRL1, (3 << 3) | CTRL1_ACTIVE)?;
        let ms = self.inner.turn_on_ms();
        self.inner.delay.delay_ms(ms);
        let before = self.average_counts()?;
        self.inner.write_reg(REG_CTRL2, CTRL2_ST)?;
        self.inner.delay.delay_ms(25);
        let after = self.average_counts()?;
        self.inner.write_reg(REG_CTRL2, 0x00)?;
        self.standby()?;
        self.inner.write_reg(REG_XYZ_DATA_CFG, s_cfg)?;
        self.inner.write_reg(REG_M_CTRL1, s_m1)?;
        self.inner.write_reg(REG_CTRL2, s_ctrl2)?;
        (self.inner.mode, self.inner.dr, self.inner.fs_g, self.inner.mods, self.inner.lnoise,
         self.inner.f_read) = cache;
        self.inner.write_reg(REG_CTRL1, s_ctrl1)?;
        if s_ctrl1 & CTRL1_ACTIVE != 0 {
            let ms = self.inner.turn_on_ms();
            self.inner.delay.delay_ms(ms);
        }
        let dx = (after.0 - before.0) as i16;
        let dy = (after.1 - before.1) as i16;
        let dz = (after.2 - before.2) as i16;
        Ok((dx >= 192 && dy >= 270 && dz >= 1275, dx, dy, dz))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = I2C_ADDRESS;

    fn rd(reg: u8, v: u8) -> T { T::write_read(ADDR, vec![reg], vec![v]) }
    fn rdn(reg: u8, v: &[u8]) -> T { T::write_read(ADDR, vec![reg], v.to_vec()) }
    fn wr(reg: u8, v: u8) -> T { T::write(ADDR, vec![reg, v]) }
    fn wrn(reg: u8, v: &[u8]) -> T {
        let mut d = vec![reg];
        d.extend_from_slice(v);
        T::write(ADDR, d)
    }
    /// Read-modify-write of one register.
    fn modify(reg: u8, cur: u8, new: u8) -> Vec<T> { vec![rd(reg, cur), wr(reg, new)] }
    /// `begin()` while ACTIVE with `CTRL_REG1` = `c1`.
    fn begin(c1: u8) -> Vec<T> { vec![rd(REG_CTRL1, c1), wr(REG_CTRL1, c1 & !1), rd(REG_SYSMOD, 0)] }
    /// `activate()` with `CTRL_REG1` currently `c1`.
    fn activate(c1: u8) -> Vec<T> { vec![rd(REG_CTRL1, c1), wr(REG_CTRL1, c1 | 1)] }

    fn init_seq() -> Vec<T> {
        vec![
            rd(REG_WHO_AM_I, 0xC7),
            wr(REG_CTRL2, 0x40),
            rd(REG_WHO_AM_I, 0xC7),
            wr(REG_CTRL1, 0x00),
            wr(REG_M_CTRL1, 0x1F),
            wr(REG_M_CTRL2, 0x20),
            wr(REG_XYZ_DATA_CFG, 0x00),
            wr(REG_CTRL2, 0x00),
            wr(REG_CTRL1, 0x11),
        ]
    }

    fn new_full(extra: Vec<T>) -> Fxos8700Full<I2cMock, NoopDelay> {
        let mut t = init_seq();
        t.extend(extra);
        Fxos8700Full::new(Connection::new(I2cMock::new(&t)), ADDR, NoopDelay::new()).expect("new")
    }

    fn done(chip: Fxos8700Full<I2cMock, NoopDelay>) {
        chip.inner.conn.release().done();
    }

    fn concat(parts: Vec<Vec<T>>) -> Vec<T> {
        parts.into_iter().flatten().collect()
    }

    fn close(a: f32, b: f32) -> bool { (a - b).abs() < 1e-4 }

    #[test]
    fn init_sequence() {
        let chip = new_full(vec![]);
        done(chip);
    }

    #[test]
    fn init_tolerates_nack_on_reset_write() {
        let mut t = init_seq();
        t[1] = T::write(ADDR, vec![REG_CTRL2, 0x40]).with_error(ErrorKind::Other);
        let chip = Fxos8700Minimal::new(Connection::new(I2cMock::new(&t)), ADDR, NoopDelay::new()).unwrap();
        chip.conn.release().done();
    }

    #[test]
    fn init_retries_who_am_i_after_reset() {
        let mut t = init_seq();
        t.insert(2, T::write_read(ADDR, vec![REG_WHO_AM_I], vec![0]).with_error(ErrorKind::Other));
        let chip = Fxos8700Minimal::new(Connection::new(I2cMock::new(&t)), ADDR, NoopDelay::new()).unwrap();
        chip.conn.release().done();
    }

    #[test]
    fn init_accepts_preproduction_who_am_i() {
        let mut t = init_seq();
        t[0] = rd(REG_WHO_AM_I, 0xC4);
        let chip = Fxos8700Minimal::new(Connection::new(I2cMock::new(&t)), ADDR, NoopDelay::new()).unwrap();
        chip.conn.release().done();
    }

    #[test]
    #[should_panic(expected = "WHO_AM_I")]
    fn init_panics_on_wrong_who_am_i() {
        let t = vec![rd(REG_WHO_AM_I, 0x1A)];
        let _ = Fxos8700Minimal::new(Connection::new(I2cMock::new(&t)), ADDR, NoopDelay::new());
    }

    #[test]
    fn read_accel_and_raw() {
        let burst = [0x40, 0x00, 0xC0, 0x00, 0x00, 0x04];
        let mut chip = new_full(vec![rdn(REG_OUT_X_MSB, &burst), rdn(REG_OUT_X_MSB, &burst)]);
        let (x, y, z) = chip.read_accel().unwrap();
        assert!(close(x, 1.0) && close(y, -1.0) && close(z, 1.0 / 4096.0), "{} {} {}", x, y, z);
        assert_eq!(chip.read_raw_accel().unwrap(), (4096, -4096, 1));
        done(chip);
    }

    #[test]
    fn read_mag_and_raw() {
        let burst = [0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4];
        let mut chip = new_full(vec![rdn(REG_M_OUT_X_MSB, &burst), rdn(REG_M_OUT_X_MSB, &burst)]);
        let (x, y, z) = chip.read_mag().unwrap();
        assert!(close(x, 10.0) && close(y, -10.0) && close(z, 50.0), "{} {} {}", x, y, z);
        assert_eq!(chip.read_raw_mag().unwrap(), (100, -100, 500));
        done(chip);
    }

    #[test]
    fn read_hybrid_burst() {
        let burst = [0x40, 0x00, 0xC0, 0x00, 0x00, 0x04, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4];
        let mut chip = new_full(vec![rdn(REG_OUT_X_MSB, &burst)]);
        let ((ax, ay, _), (mx, my, mz)) = chip.read_hybrid().unwrap().unwrap();
        assert!(close(ax, 1.0) && close(ay, -1.0));
        assert!(close(mx, 10.0) && close(my, -10.0) && close(mz, 50.0));
        done(chip);
    }

    #[test]
    fn read_hybrid_and_temperature_reject_accel_only_mode() {
        let t = concat(vec![begin(0x11), modify(REG_M_CTRL1, 0x1F, 0x1C), activate(0x10)]);
        let mut chip = new_full(t);
        assert!(chip.set_mode(MODE_ACCEL_ONLY).unwrap());
        assert!(chip.read_hybrid().unwrap().is_none());
        assert!(chip.read_temperature().unwrap().is_none());
        assert!(chip.measure_mag_once().unwrap().is_none());
        done(chip);
    }

    #[test]
    fn temperature_is_int8_times_0_96() {
        let mut chip = new_full(vec![rd(REG_TEMP, 0xFB)]);
        let t = chip.read_temperature().unwrap().unwrap();
        assert!(close(t, -5.0 * 0.96), "{}", t);
        done(chip);
    }

    #[test]
    fn range_scales_accel_and_validates() {
        let burst = [0x40, 0x00, 0, 0, 0, 0];
        let t = concat(vec![
            modify(REG_XYZ_DATA_CFG, 0x00, 0x01),
            vec![rdn(REG_OUT_X_MSB, &burst)],
            modify(REG_XYZ_DATA_CFG, 0x01, 0x02),
        ]);
        let mut chip = new_full(t);
        assert!(chip.set_range(4).unwrap());
        let (x, _, _) = chip.read_accel().unwrap();
        assert!(close(x, 2.0), "{}", x);
        assert!(chip.set_range(8).unwrap());
        assert!(!chip.set_range(3).unwrap());
        done(chip);
    }

    #[test]
    fn low_noise_and_8g_are_exclusive() {
        let t = concat(vec![
            begin(0x11), modify(REG_CTRL1, 0x10, 0x14), activate(0x14),
            modify(REG_XYZ_DATA_CFG, 0x00, 0x00),
        ]);
        let mut chip = new_full(t);
        assert!(chip.set_low_noise(true).unwrap());
        assert!(!chip.set_range(8).unwrap());
        assert!(chip.set_range(2).unwrap());
        done(chip);
    }

    #[test]
    fn data_rate_goes_through_standby() {
        let t = concat(vec![begin(0x11), modify(REG_CTRL1, 0x10, 0x18), activate(0x18)]);
        let mut chip = new_full(t);
        assert!(chip.set_data_rate(100.0).unwrap());
        assert!(!chip.set_data_rate(77.0).unwrap());
        done(chip);
    }

    #[test]
    fn data_rate_1_56_selects_code_7() {
        let t = concat(vec![begin(0x11), modify(REG_CTRL1, 0x10, 0x38), activate(0x38)]);
        let mut chip = new_full(t);
        assert!(chip.set_data_rate(1.56).unwrap());
        done(chip);
    }

    #[test]
    fn accel_offset_roundtrip() {
        let t = concat(vec![
            begin(0x11),
            vec![wrn(REG_OFF_X, &[30, 0xE2, 127])],
            activate(0x10),
            vec![rdn(REG_OFF_X, &[30, 0xE2, 127])],
        ]);
        let mut chip = new_full(t);
        chip.set_accel_offset(0.06, -0.06, 1.0).unwrap();
        let (x, y, z) = chip.get_accel_offset().unwrap();
        assert!(close(x, 0.06) && close(y, -0.06) && close(z, 0.254), "{} {} {}", x, y, z);
        done(chip);
    }

    #[test]
    fn mag_offset_roundtrip() {
        let regs = [0x04, 0xB0, 0xFB, 0x50, 0x00, 0x00];
        let mut chip = new_full(vec![wrn(REG_M_OFF_X_MSB, &regs), rdn(REG_M_OFF_X_MSB, &regs)]);
        chip.set_mag_offset(60.0, -60.0, 0.0).unwrap();
        let (x, y, z) = chip.get_mag_offset().unwrap();
        assert!(close(x, 60.0) && close(y, -60.0) && close(z, 0.0), "{} {} {}", x, y, z);
        done(chip);
    }

    #[test]
    fn mag_min_max() {
        let t = vec![rdn(REG_MIN_X_MSB, &[0xFF, 0x9C, 0, 0, 0, 0]), rdn(REG_MAX_X_MSB, &[0x00, 0x64, 0, 0, 0, 0])];
        let mut chip = new_full(t);
        let (mn, mx) = chip.read_mag_min_max().unwrap();
        assert!(close(mn.0, -10.0) && close(mx.0, 10.0));
        done(chip);
    }

    #[test]
    fn mag_auto_calibration_enable_clears_raw_and_tracking_disable() {
        let t = concat(vec![
            modify(REG_M_CTRL3, 0x80, 0x00),
            begin(0x11), modify(REG_M_CTRL2, 0x30, 0x20), activate(0x10),
            modify(REG_M_CTRL1, 0x1F, 0x9F),
            modify(REG_M_CTRL1, 0x9F, 0x1F),
        ]);
        let mut chip = new_full(t);
        chip.set_mag_auto_calibration(true).unwrap();
        chip.set_mag_auto_calibration(false).unwrap();
        done(chip);
    }

    #[test]
    fn measure_mag_once_polls_and_restores_active() {
        let t = concat(vec![
            begin(0x11),
            modify(REG_M_CTRL1, 0x1F, 0x3F),
            vec![rd(REG_M_CTRL1, 0x3F), rd(REG_M_CTRL1, 0x1F), rdn(REG_M_OUT_X_MSB, &[0x00, 0x64, 0, 0, 0, 0])],
            activate(0x10),
        ]);
        let mut chip = new_full(t);
        let (x, _, _) = chip.measure_mag_once().unwrap().unwrap();
        assert!(close(x, 10.0));
        done(chip);
    }

    #[test]
    fn heading_north_and_east() {
        let accel = [0, 0, 0, 0, 0x40, 0];
        let hybrid = |mag: [u8; 6]| -> T {
            let mut b = accel.to_vec();
            b.extend_from_slice(&mag);
            rdn(REG_OUT_X_MSB, &b)
        };
        let t = vec![
            hybrid([0x01, 0xF4, 0, 0, 0, 0]),
            hybrid([0x01, 0xF4, 0, 0, 0, 0]),
            hybrid([0x00, 0x00, 0xFE, 0x0C, 0, 0]),
        ];
        let mut chip = new_full(t);
        assert!(close(chip.heading(0.0).unwrap(), 0.0));
        assert!(close(chip.heading(10.0).unwrap(), 10.0));
        assert!((chip.heading(0.0).unwrap() - 90.0).abs() < 1e-3);
        done(chip);
    }

    #[test]
    fn fifo_mode_changes_go_through_disabled() {
        let t = concat(vec![
            begin(0x11), vec![wr(REG_F_SETUP, 0x50)], activate(0x10),
            begin(0x11), vec![wr(REG_F_SETUP, 0x00), wr(REG_F_SETUP, 0x88)], activate(0x10),
            vec![rd(REG_STATUS, 0x42)],
            vec![rd(REG_STATUS, 0x42)],
            vec![rd(REG_STATUS, 0x42), rdn(REG_OUT_X_MSB, &[0x40, 0, 0, 0, 0, 0]), rdn(REG_OUT_X_MSB, &[0x40, 0, 0, 0, 0, 0])],
        ]);
        let mut chip = new_full(t);
        assert!(chip.set_fifo(FIFO_CIRCULAR, 16).unwrap());
        assert!(chip.set_fifo(FIFO_FILL, 8).unwrap());
        assert_eq!(chip.fifo_level().unwrap(), 2);
        assert_eq!(chip.read_fifo_status().unwrap(), (false, true, 2));
        let mut out = [(0.0f32, 0.0f32, 0.0f32); 4];
        assert_eq!(chip.read_fifo(&mut out).unwrap(), 2);
        assert!(close(out[0].0, 1.0));
        assert!(!chip.set_fifo(4, 0).unwrap());
        // Resolution cannot change while the FIFO is on.
        assert!(!chip.set_resolution(8).unwrap());
        done(chip);
    }

    #[test]
    fn freefall_motion_registers() {
        let t = concat(vec![
            begin(0x11),
            vec![wr(REG_A_FFMT_CFG, 0xF8), wr(REG_A_FFMT_THS, 5), wr(REG_A_FFMT_COUNT, 6)],
            activate(0x10),
        ]);
        let mut chip = new_full(t);
        chip.set_freefall_motion(true, 7, 0.3, 60.0, true, false).unwrap();
        done(chip);
    }

    #[test]
    fn transient_registers() {
        let t = concat(vec![
            begin(0x11),
            vec![wr(REG_TRANSIENT_CFG, 0x1D), wr(REG_TRANSIENT_THS, 8), wr(REG_TRANSIENT_COUNT, 4)],
            activate(0x10),
        ]);
        let mut chip = new_full(t);
        chip.set_transient(6, 0.5, 40.0, true, true).unwrap();
        done(chip);
    }

    #[test]
    fn pulse_registers_with_and_without_lpf() {
        let t = concat(vec![
            begin(0x11),
            vec![wr(REG_PULSE_CFG, 0x61), wrn(REG_PULSE_THSX, &[8, 8, 16]), wr(REG_PULSE_TMLT, 3),
                 wr(REG_PULSE_LTCY, 5), wr(REG_PULSE_WIND, 15)],
            modify(REG_HP_FILTER_CUTOFF, 0x00, 0x10),
            activate(0x10),
            begin(0x11),
            vec![wr(REG_PULSE_CFG, 0x01), wrn(REG_PULSE_THSX, &[8, 8, 8]), wr(REG_PULSE_TMLT, 12),
                 wr(REG_PULSE_LTCY, 20), wr(REG_PULSE_WIND, 60)],
            modify(REG_HP_FILTER_CUTOFF, 0x10, 0x20),
            activate(0x10),
        ]);
        let mut chip = new_full(t);
        chip.set_pulse(&PulseConfig { single: 1, double: 4, threshold_g: [0.5, 0.5, 1.0], latch: true,
                                      ..Default::default() }).unwrap();
        chip.set_pulse(&PulseConfig { single: 1, lpf: false, hpf: false, ..Default::default() }).unwrap();
        done(chip);
    }

    #[test]
    fn orientation_registers_and_validation() {
        let t = concat(vec![
            begin(0x11),
            vec![wr(REG_PL_CFG, 0xC0), wr(REG_PL_COUNT, 10), wr(REG_PL_THS, 0x84), wr(REG_PL_BF_ZCOMP, 0x84)],
            activate(0x10),
            vec![rd(REG_PL_STATUS, 0x80 | 0x40 | (3 << 1) | 1)],
        ]);
        let mut chip = new_full(t);
        assert!(chip.set_orientation(true, 100.0, 45, 4, 4, 2).unwrap());
        assert!(!chip.set_orientation(true, 0.0, 75, 7, 4, 2).unwrap());
        assert!(!chip.set_orientation(true, 0.0, 50, 4, 4, 2).unwrap());
        assert_eq!(chip.read_orientation().unwrap(), (true, true, 3, true));
        done(chip);
    }

    #[test]
    fn auto_sleep_registers() {
        let t = concat(vec![
            begin(0x11),
            modify(REG_CTRL1, 0x10, 0x50),
            vec![wr(REG_ASLP_COUNT, 31)],
            modify(REG_CTRL2, 0x00, 0x04),
            modify(REG_CTRL3, 0x00, 0x18),
            activate(0x50),
        ]);
        let mut chip = new_full(t);
        assert!(chip.set_auto_sleep(true, 12.5, 10.0, SOURCE_FF_MT | SOURCE_PULSE).unwrap());
        assert!(!chip.set_auto_sleep(true, 50.0, 10.0, SOURCE_DATA_READY).unwrap());
        assert!(!chip.set_auto_sleep(true, 33.0, 10.0, 0).unwrap());
        done(chip);
    }

    #[test]
    fn interrupt_routing_and_poll() {
        let t = concat(vec![
            begin(0x11), modify(REG_CTRL4, 0x00, 0x14), modify(REG_CTRL5, 0x00, 0x14), activate(0x10),
            begin(0x11), modify(REG_CTRL4, 0x14, 0x14), modify(REG_CTRL5, 0x14, 0x10), activate(0x10),
            begin(0x11), modify(REG_CTRL4, 0x14, 0x04), activate(0x10),
            vec![rd(REG_INT_SOURCE, 0x14), rd(REG_PL_STATUS, 0), rd(REG_A_FFMT_SRC, 0)],
        ]);
        let mut chip = new_full(t);
        assert!(chip.enable_interrupt(SOURCE_FF_MT | SOURCE_ORIENTATION, 1).unwrap());
        assert!(chip.enable_interrupt(SOURCE_FF_MT, 2).unwrap());
        assert!(!chip.enable_interrupt(1, 3).unwrap());
        chip.disable_interrupt(SOURCE_ORIENTATION).unwrap();
        assert_eq!(chip.poll_interrupt().unwrap(), 0x14);
        done(chip);
    }

    #[test]
    fn status_helpers_and_raw_registers() {
        let t = vec![
            rd(REG_STATUS, 0x08), rd(REG_M_DR_STATUS, 0x00), rd(REG_SYSMOD, 0x02),
            wrn(0x5F, &[1, 2]), rdn(0x5F, &[1, 2]),
        ];
        let mut chip = new_full(t);
        assert!(chip.new_accel_data().unwrap());
        assert!(!chip.new_mag_data().unwrap());
        assert_eq!(chip.read_system_mode().unwrap(), 2);
        chip.write_register(0x5F, &[1, 2]).unwrap();
        let mut buf = [0u8; 2];
        chip.read_register(0x5F, &mut buf).unwrap();
        assert_eq!(buf, [1, 2]);
        done(chip);
    }

    #[test]
    fn reset_tolerates_nack_and_disables_hybrid_read() {
        let t = vec![T::write(ADDR, vec![REG_CTRL2, 0x40]).with_error(ErrorKind::Other)];
        let mut chip = new_full(t);
        chip.reset().unwrap();
        assert!(chip.read_hybrid().unwrap().is_none());
        done(chip);
    }

    /// Self-test with an accelerometer that reports a shifted Z while `st` is set.
    #[test]
    fn self_test_runs_and_restores() {
        let frame = |counts: [i16; 3]| -> T {
            let mut b = Vec::new();
            for c in counts {
                let raw = ((c as i32) << 2) as u16;
                b.push((raw >> 8) as u8);
                b.push((raw & 0xFF) as u8);
            }
            rdn(REG_OUT_X_MSB, &b)
        };
        let mut t = concat(vec![
            vec![rd(REG_CTRL1, 0x11), rd(REG_CTRL2, 0x00), rd(REG_XYZ_DATA_CFG, 0x00), rd(REG_M_CTRL1, 0x1F)],
            // standby()
            vec![rd(REG_CTRL1, 0x11), wr(REG_CTRL1, 0x10), rd(REG_SYSMOD, 0)],
            vec![wr(REG_XYZ_DATA_CFG, 0x00), wr(REG_M_CTRL1, 0x1C), wr(REG_CTRL2, 0x00), wr(REG_CTRL1, 0x19)],
        ]);
        for _ in 0..8 { t.push(frame([0, 0, 0])); }
        t.push(wr(REG_CTRL2, 0x80));
        for _ in 0..8 { t.push(frame([200, 300, 1300])); }
        t.extend(concat(vec![
            vec![wr(REG_CTRL2, 0x00)],
            vec![rd(REG_CTRL1, 0x19), wr(REG_CTRL1, 0x18), rd(REG_SYSMOD, 0)],
            vec![wr(REG_XYZ_DATA_CFG, 0x00), wr(REG_M_CTRL1, 0x1F), wr(REG_CTRL2, 0x00), wr(REG_CTRL1, 0x11)],
        ]));
        let mut chip = new_full(t);
        let (ok, dx, dy, dz) = chip.self_test().unwrap();
        assert!(ok);
        assert_eq!((dx, dy, dz), (200, 300, 1300));
        done(chip);
    }
}
