//! LSM303 — 3-axis accelerometer + magnetometer e-compass (STMicroelectronics).
//!
//! The LSM303DLHC combines a three-axis MEMS accelerometer and a three-axis
//! magnetometer in one package. The two dies sit behind **two independent I²C
//! slave addresses** on the same bus: the accelerometer at 0x19 and the
//! magnetometer at 0x1E. The driver therefore takes two buses.
//!
//! Accelerometer (0x19)
//! - Four full-scale ranges (±2 / ±4 / ±8 / ±16 *g*), 12-bit output at
//!   1 mg/LSB in the default high-resolution mode, output data rates from
//!   1 Hz to 5.376 kHz, a 32-level FIFO, a selectable high-pass filter, two
//!   programmable inertial interrupt generators (threshold, free-fall, 6D/4D
//!   orientation) and single/double click detection.
//!
//! Magnetometer (0x1E)
//! - Seven full-scale ranges (±130 µT to ±810 µT), 12-bit output, output data
//!   rates from 0.75 Hz to 220 Hz and a relative-only temperature sensor.
//!
//! The compass heading is computed in software from both sensors.
//!
//! ## I²C framing
//!
//! The two slaves frame their register accesses differently:
//!
//! - **Accelerometer** — after the slave address the master sends an 8-bit
//!   sub-address: the 7 LSbs are the register address and the **MSb enables
//!   address auto-increment**. A single-register access uses `reg`; a
//!   multi-byte access uses `reg | 0x80`.
//! - **Magnetometer** — an address pointer without an auto-increment bit.
//!   The pointer always advances by one, so a 6-byte read from 0x03 returns
//!   all six output registers in one transaction.
//!
//! The magnetometer output order is **X, Z, Y** (not X, Y, Z) and the byte
//! order is **high byte first** — the opposite of the accelerometer.
//!
//! ## Constants
//!
//! Interrupt sources: [`SOURCE_DATA_READY`], [`SOURCE_WATERMARK`],
//! [`SOURCE_OVERRUN`], [`SOURCE_IA1`], [`SOURCE_IA2`], [`SOURCE_CLICK`],
//! [`SOURCE_BOOT`], [`SOURCE_MAG_DATA_READY`]

use embedded_hal::i2c::I2c;

use crate::connection::register::{read_register, write_register};

// --- Accelerometer registers ------------------------------------------------
const REG_CTRL1_A: u8 = 0x20;
const REG_CTRL2_A: u8 = 0x21;
const REG_CTRL3_A: u8 = 0x22;
const REG_CTRL4_A: u8 = 0x23;
const REG_CTRL5_A: u8 = 0x24;
const REG_CTRL6_A: u8 = 0x25;
const REG_REFERENCE_A: u8 = 0x26;
const REG_STATUS_A: u8 = 0x27;
const REG_OUT_X_L_A: u8 = 0x28;
const REG_FIFO_CTRL_A: u8 = 0x2E;
const REG_FIFO_SRC_A: u8 = 0x2F;
const REG_INT1_CFG_A: u8 = 0x30;
const REG_INT1_SOURCE_A: u8 = 0x31;
const REG_INT1_THS_A: u8 = 0x32;
const REG_INT1_DURATION_A: u8 = 0x33;
const REG_INT2_CFG_A: u8 = 0x34;
const REG_INT2_SOURCE_A: u8 = 0x35;
const REG_INT2_THS_A: u8 = 0x36;
const REG_INT2_DURATION_A: u8 = 0x37;
const REG_CLICK_CFG_A: u8 = 0x38;
const REG_CLICK_SRC_A: u8 = 0x39;
const REG_CLICK_THS_A: u8 = 0x3A;
const REG_TIME_LIMIT_A: u8 = 0x3B;
const REG_TIME_LATENCY_A: u8 = 0x3C;
const REG_TIME_WINDOW_A: u8 = 0x3D;

// --- Magnetometer registers -------------------------------------------------
const REG_CRA_M: u8 = 0x00;
const REG_CRB_M: u8 = 0x01;
const REG_MR_M: u8 = 0x02;
const REG_OUT_X_H_M: u8 = 0x03;
const REG_SR_M: u8 = 0x09;
const REG_IRA_M: u8 = 0x0A;
const REG_TEMP_OUT_H_M: u8 = 0x31;

/// Fixed I²C slave address of the accelerometer.
pub const I2C_ADDRESS_ACCEL: u8 = 0x19;
/// Fixed I²C slave address of the magnetometer.
pub const I2C_ADDRESS_MAG: u8 = 0x1E;

/// Bit 7 of the accelerometer sub-address enables address auto-increment.
const ACCEL_AUTO_INCREMENT: u8 = 0x80;

/// Accelerometer operating mode: low-power (8-bit output).
pub const MODE_LOW_POWER: u8 = 0;
/// Accelerometer operating mode: normal (10-bit output).
pub const MODE_NORMAL: u8 = 1;
/// Accelerometer operating mode: high-resolution (12-bit output).
pub const MODE_HIGH_RESOLUTION: u8 = 2;

/// FIFO mode: bypass — the FIFO is not used.
pub const FIFO_BYPASS: u8 = 0;
/// FIFO mode: FIFO — the oldest sample is overwritten when the FIFO is full.
pub const FIFO_FIFO: u8 = 1;
/// FIFO mode: stream — the newest samples are kept.
pub const FIFO_STREAM: u8 = 2;
/// FIFO mode: trigger — the FIFO stops filling once the trigger fires.
pub const FIFO_TRIGGER: u8 = 3;

/// Magnetometer operating mode: continuous conversion.
pub const MAG_MODE_CONTINUOUS: u8 = 0;
/// Magnetometer operating mode: single conversion.
pub const MAG_MODE_SINGLE: u8 = 1;
/// Magnetometer operating mode: sleep.
pub const MAG_MODE_SLEEP: u8 = 2;

/// Click detector axis mask: X axis.
pub const CLICK_AXIS_X: u8 = 0x01;
/// Click detector axis mask: Y axis.
pub const CLICK_AXIS_Y: u8 = 0x02;
/// Click detector axis mask: Z axis.
pub const CLICK_AXIS_Z: u8 = 0x04;

/// Interrupt source: new accelerometer XYZ sample.
pub const SOURCE_DATA_READY: u16 = 0x0001;
/// Interrupt source: FIFO content exceeds the watermark.
pub const SOURCE_WATERMARK: u16 = 0x0002;
/// Interrupt source: FIFO overrun.
pub const SOURCE_OVERRUN: u16 = 0x0004;
/// Interrupt source: inertial generator 1 event.
pub const SOURCE_IA1: u16 = 0x0008;
/// Interrupt source: inertial generator 2 event.
pub const SOURCE_IA2: u16 = 0x0010;
/// Interrupt source: single / double click.
pub const SOURCE_CLICK: u16 = 0x0020;
/// Interrupt source: boot procedure running (INT2 only).
pub const SOURCE_BOOT: u16 = 0x0040;
/// Interrupt source: new magnetometer sample (on the DRDY pin).
pub const SOURCE_MAG_DATA_READY: u16 = 0x0080;

// CTRL_REG1_A bits.
const CTRL1_ODR_MASK: u8 = 0xF0;
const CTRL1_LPEN: u8 = 0x08;
const CTRL1_AXES_MASK: u8 = 0x07;

// CTRL_REG2_A bits.
const CTRL2_FDS: u8 = 0x08;
const CTRL2_HPCLICK: u8 = 0x04;
const CTRL2_HPIS2: u8 = 0x02;
const CTRL2_HPIS1: u8 = 0x01;

// CTRL_REG3_A bits (routing to INT1).
const CTRL3_I1_CLICK: u8 = 0x80;
const CTRL3_I1_AOI1: u8 = 0x40;
const CTRL3_I1_AOI2: u8 = 0x20;
const CTRL3_I1_DRDY1: u8 = 0x10;
const CTRL3_I1_WTM: u8 = 0x04;
const CTRL3_I1_OVERRUN: u8 = 0x02;

// CTRL_REG4_A bits.
const CTRL4_FS_MASK: u8 = 0x30;
const CTRL4_HR: u8 = 0x08;

// CTRL_REG5_A bits.
const CTRL5_BOOT: u8 = 0x80;
const CTRL5_FIFO_EN: u8 = 0x40;
const CTRL5_LIR_INT1: u8 = 0x08;
const CTRL5_D4D_INT1: u8 = 0x04;
const CTRL5_LIR_INT2: u8 = 0x02;
const CTRL5_D4D_INT2: u8 = 0x01;

// CTRL_REG6_A bits (routing to INT2).
const CTRL6_I2_CLICK: u8 = 0x80;
const CTRL6_I2_INT1: u8 = 0x40;
const CTRL6_I2_INT2: u8 = 0x20;
const CTRL6_BOOT_I1: u8 = 0x10;
const CTRL6_H_LACTIVE: u8 = 0x02;

// STATUS_REG_A bits.
const STATUS_ZYXDA: u8 = 0x08;

// FIFO_SRC_REG_A bits.
const FIFO_SRC_WTM: u8 = 0x80;
const FIFO_SRC_OVERRUN: u8 = 0x40;
const FIFO_SRC_EMPTY: u8 = 0x20;
const FIFO_SRC_LEVEL_MASK: u8 = 0x1F;

// INTx_SOURCE_A / CLICK_SRC_A interrupt-activation bit.
const SOURCE_IA: u8 = 0x40;

// CRA_REG_M bits.
const CRA_TEMP_EN: u8 = 0x80;
const CRA_DO_MASK: u8 = 0x1C;

// CRB_REG_M bits.
const CRB_GN_MASK: u8 = 0xE0;

// SR_REG_M bits.
const SR_LOCK: u8 = 0x02;
const SR_DRDY: u8 = 0x01;

/// LSM303 minimal driver — acceleration and magnetic field.
///
/// The constructor runs the datasheet initialization sequence: it verifies
/// the magnetometer identity (0x48 0x34 0x33), puts the accelerometer
/// control registers into a known state and starts both dies.
///
/// Default configuration:
/// - Accelerometer: ±2 *g*, 100 Hz, high-resolution (12-bit), all three axes
///   enabled, `BDU` set, high-pass filter bypassed, FIFO bypassed, all
///   interrupt routing cleared.
/// - Magnetometer: ±130 µT (±1.3 gauss), 15 Hz, continuous conversion,
///   temperature sensor off.
pub struct Lsm303Minimal<I2C> {
    i2c: I2C,
    mode: u8,
    fs_g: u8,
    odr_hz: f32,
    mag_range_ut: u16,
}

impl<I2C: I2c> Lsm303Minimal<I2C> {
    /// Create a new `Lsm303Minimal` and run the initialization sequence.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus carrying both slaves: the accelerometer at 0x19 and
    ///   the magnetometer at 0x1E.
    ///
    /// # Errors
    /// Returns the bus error if the magnetometer identity does not match.
    pub fn new(i2c: I2C) -> Result<Self, I2C::Error> {
        let mut chip = Self {
            i2c,
            mode: MODE_HIGH_RESOLUTION,
            fs_g: 2,
            odr_hz: 100.0,
            mag_range_ut: 130,
        };
        chip.init()?;
        Ok(chip)
    }

    /// Run the datasheet initialization sequence.
    ///
    /// Verifies the magnetometer identity, clears the accelerometer's
    /// interrupt routing, FIFO and high-pass configuration, selects
    /// ±2 *g* / 100 Hz / high-resolution on the accelerometer and
    /// ±130 µT / 15 Hz / continuous conversion on the magnetometer.
    pub fn init(&mut self) -> Result<(), I2C::Error> {
        let mut identity = [0u8; 3];
        read_register(&mut self.i2c, I2C_ADDRESS_MAG, REG_IRA_M as u32, 1, &mut identity)?;
        if identity != [0x48, 0x34, 0x33] {
            panic!("LSM303 magnetometer not found: expected identity 0x483433, got 0x{:02X}{:02X}{:02X}",
                   identity[0], identity[1], identity[2]);
        }

        // Accelerometer: known state for the high-pass filter, the interrupt
        // routing and the FIFO.
        self.accel_write(REG_CTRL2_A, 0x00)?;
        self.accel_write(REG_CTRL3_A, 0x00)?;
        self.accel_write(REG_CTRL5_A, 0x00)?;
        self.accel_write(REG_CTRL6_A, 0x00)?;
        self.accel_write(REG_FIFO_CTRL_A, 0x00)?;

        // Accelerometer: BDU = 1, ±2 g, high-resolution mode.
        self.accel_write(REG_CTRL4_A, 0x88)?;
        // Accelerometer: 100 Hz, X/Y/Z enabled.
        self.accel_write(REG_CTRL1_A, 0x57)?;

        // Magnetometer: 15 Hz, ±1.3 gauss, continuous conversion (the part
        // resets into sleep mode).
        self.mag_write(REG_CRA_M, 0x10)?;
        self.mag_write(REG_CRB_M, 0x20)?;
        self.mag_write(REG_MR_M, 0x00)?;

        self.mode = MODE_HIGH_RESOLUTION;
        self.fs_g = 2;
        self.odr_hz = 100.0;
        Ok(())
    }

    /// Read `length` bytes from an accelerometer register.
    ///
    /// Multi-byte reads set bit 7 of the sub-address so the accelerometer
    /// auto-increments the register pointer.
    fn accel_read(&mut self, reg: u8, length: usize) -> Result<[u8; 8], I2C::Error> {
        let mut buf = [0u8; 8];
        let addr = if length > 1 { reg | ACCEL_AUTO_INCREMENT } else { reg };
        read_register(&mut self.i2c, I2C_ADDRESS_ACCEL, addr as u32, 1, &mut buf[..length])?;
        Ok(buf)
    }

    /// Write one byte to an accelerometer register.
    fn accel_write(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        write_register(&mut self.i2c, I2C_ADDRESS_ACCEL, reg as u32, 1, &[value])
    }

    /// Read `length` bytes from a magnetometer register.
    ///
    /// The magnetometer address pointer always auto-increments, so no
    /// auto-increment bit is needed.
    fn mag_read(&mut self, reg: u8, length: usize) -> Result<[u8; 8], I2C::Error> {
        let mut buf = [0u8; 8];
        read_register(&mut self.i2c, I2C_ADDRESS_MAG, reg as u32, 1, &mut buf[..length])?;
        Ok(buf)
    }

    /// Write one byte to a magnetometer register.
    fn mag_write(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        write_register(&mut self.i2c, I2C_ADDRESS_MAG, reg as u32, 1, &[value])
    }

    /// Read the acceleration.
    ///
    /// Burst-reads the six output registers so all three axes belong to the
    /// same sample. Returns (x, y, z) in *g*.
    pub fn read_acceleration(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let raw = self.accel_read(REG_OUT_X_L_A, 6)?;
        Ok(self.convert_accel(&raw))
    }

    /// Read the magnetic flux density.
    ///
    /// Burst-reads the six output registers (X, Z, Y on the wire) and
    /// reorders them to X, Y, Z. Returns (x, y, z) in µT.
    pub fn read_magnetic(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let raw = self.mag_read(REG_OUT_X_H_M, 6)?;
        let (x, y, z) = self.reorder_mag(&raw);
        let (gain_x, gain_z) = self.mag_gains();
        Ok((x as f32 / gain_x as f32 * 100.0,
            y as f32 / gain_x as f32 * 100.0,
            z as f32 / gain_z as f32 * 100.0))
    }

    /// Return the right-shift that turns the raw 16-bit pair into counts.
    fn shift(&self) -> u32 {
        match self.mode {
            MODE_LOW_POWER => 8,
            MODE_NORMAL => 6,
            _ => 4,
        }
    }

    /// Return the sensitivity in mg/LSB for the current mode and range.
    fn sensitivity_mg(&self) -> f32 {
        let table: [f32; 4] = match self.mode {
            MODE_LOW_POWER => [16.0, 32.0, 64.0, 192.0],
            MODE_NORMAL => [4.0, 8.0, 16.0, 48.0],
            _ => [1.0, 2.0, 4.0, 12.0],
        };
        let index = match self.fs_g {
            4 => 1,
            8 => 2,
            16 => 3,
            _ => 0,
        };
        table[index]
    }

    /// Convert a six-byte accelerometer burst into (x, y, z) in *g*.
    fn convert_accel(&self, raw: &[u8; 8]) -> (f32, f32, f32) {
        let shift = self.shift();
        let sens = self.sensitivity_mg() / 1000.0;
        let x = (to_signed(raw[0] as u32 | ((raw[1] as u32) << 8), 16) >> shift) as f32;
        let y = (to_signed(raw[2] as u32 | ((raw[3] as u32) << 8), 16) >> shift) as f32;
        let z = (to_signed(raw[4] as u32 | ((raw[5] as u32) << 8), 16) >> shift) as f32;
        (x * sens, y * sens, z * sens)
    }

    /// Convert a six-byte accelerometer burst into raw counts.
    fn raw_accel(&self, raw: &[u8; 8]) -> (i16, i16, i16) {
        let shift = self.shift();
        (
            (to_signed(raw[0] as u32 | ((raw[1] as u32) << 8), 16) >> shift) as i16,
            (to_signed(raw[2] as u32 | ((raw[3] as u32) << 8), 16) >> shift) as i16,
            (to_signed(raw[4] as u32 | ((raw[5] as u32) << 8), 16) >> shift) as i16,
        )
    }

    /// Reorder a six-byte magnetometer burst (X, Z, Y) into (X, Y, Z).
    fn reorder_mag(&self, raw: &[u8; 8]) -> (i16, i16, i16) {
        let x = to_signed(((raw[0] as u32) << 8) | raw[1] as u32, 16);
        let z = to_signed(((raw[2] as u32) << 8) | raw[3] as u32, 16);
        let y = to_signed(((raw[4] as u32) << 8) | raw[5] as u32, 16);
        (x as i16, y as i16, z as i16)
    }

    /// Return the (X/Y, Z) gains in LSB per gauss for the current range.
    fn mag_gains(&self) -> (u16, u16) {
        match self.mag_range_ut {
            190 => (855, 760),
            250 => (670, 600),
            400 => (450, 400),
            470 => (400, 355),
            560 => (330, 295),
            810 => (230, 205),
            _ => (1100, 980),
        }
    }
}

/// LSM303 full driver — extends the minimal driver with configuration,
/// the FIFO, the inertial interrupt generators, click detection, the
/// magnetometer controls, the compass heading and the interrupt API.
///
/// Re-exports the minimal driver's methods as one-line delegates (the Rust
/// equivalent of "Full never duplicates Minimal"), then adds Full-only
/// methods below.
pub struct Lsm303Full<I2C> {
    inner: Lsm303Minimal<I2C>,
    enabled_sources: u16,
    temp_enabled: bool,
}

impl<I2C: I2c> Lsm303Full<I2C> {
    /// Create a new `Lsm303Full` and run the initialization sequence.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus carrying both slaves: the accelerometer at 0x19 and
    ///   the magnetometer at 0x1E.
    pub fn new(i2c: I2C) -> Result<Self, I2C::Error> {
        let inner = Lsm303Minimal::new(i2c)?;
        Ok(Self { inner, enabled_sources: 0, temp_enabled: false })
    }

    /// Read the acceleration in *g*.
    pub fn read_acceleration(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read_acceleration()
    }

    /// Read the magnetic flux density in µT.
    pub fn read_magnetic(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read_magnetic()
    }

    /// Set the accelerometer full-scale range to 2, 4, 8 or 16 *g*.
    pub fn set_range(&mut self, range_g: u8) -> Result<(), I2C::Error> {
        let code = match range_g {
            4 => 0x10,
            8 => 0x20,
            16 => 0x30,
            _ => 0x00,
        };
        let ctrl4 = self.accel_read_reg(REG_CTRL4_A)?;
        self.accel_write(REG_CTRL4_A, (ctrl4 & !CTRL4_FS_MASK) | code)?;
        self.inner.fs_g = range_g;
        Ok(())
    }

    /// Set the accelerometer output data rate in Hz.
    ///
    /// The rates 1620 and 5376 Hz exist in low-power mode only, 1344 Hz in
    /// normal and high-resolution mode only.
    pub fn set_data_rate(&mut self, rate_hz: u16) -> Result<(), I2C::Error> {
        let code = match rate_hz {
            0 => 0x0,
            1 => 0x1,
            10 => 0x2,
            25 => 0x3,
            50 => 0x4,
            100 => 0x5,
            200 => 0x6,
            400 => 0x7,
            1620 => 0x8,
            1344 | 5376 => 0x9,
            _ => return Ok(()),
        };
        let ctrl1 = self.accel_read_reg(REG_CTRL1_A)?;
        self.accel_write(REG_CTRL1_A, (ctrl1 & !CTRL1_ODR_MASK) | (code << 4))?;
        self.inner.odr_hz = rate_hz as f32;
        Ok(())
    }

    /// Set the accelerometer operating mode.
    ///
    /// Low-power mode uses 8-bit output, normal mode 10-bit and
    /// high-resolution mode 12-bit; the sensitivity changes accordingly.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        let (lpen, hr) = match mode {
            MODE_LOW_POWER => (true, false),
            MODE_NORMAL => (false, false),
            MODE_HIGH_RESOLUTION => (false, true),
            _ => return Ok(()),
        };
        let ctrl1 = self.accel_read_reg(REG_CTRL1_A)?;
        let ctrl4 = self.accel_read_reg(REG_CTRL4_A)?;
        let v1 = if lpen { ctrl1 | CTRL1_LPEN } else { ctrl1 & !CTRL1_LPEN };
        let v4 = if hr { ctrl4 | CTRL4_HR } else { ctrl4 & !CTRL4_HR };
        self.accel_write(REG_CTRL1_A, v1)?;
        self.accel_write(REG_CTRL4_A, v4)?;
        self.inner.mode = mode;
        Ok(())
    }

    /// Enable or disable the individual accelerometer axes.
    pub fn set_axes(&mut self, x: bool, y: bool, z: bool) -> Result<(), I2C::Error> {
        let ctrl1 = self.accel_read_reg(REG_CTRL1_A)?;
        let mut value = ctrl1 & !CTRL1_AXES_MASK;
        if x { value |= 0x01; }
        if y { value |= 0x02; }
        if z { value |= 0x04; }
        self.accel_write(REG_CTRL1_A, value)
    }

    /// Put the accelerometer into power-down mode.
    ///
    /// The register contents are retained, so `set_data_rate()` resumes the
    /// conversions.
    pub fn power_down(&mut self) -> Result<(), I2C::Error> {
        let ctrl1 = self.accel_read_reg(REG_CTRL1_A)?;
        self.accel_write(REG_CTRL1_A, ctrl1 & !CTRL1_ODR_MASK)?;
        self.inner.odr_hz = 0.0;
        Ok(())
    }

    /// Read the acceleration as raw counts.
    pub fn read_acceleration_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let raw = self.inner.accel_read(REG_OUT_X_L_A, 6)?;
        Ok(self.inner.raw_accel(&raw))
    }

    /// Report whether a new accelerometer sample is available.
    pub fn new_data_available(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.accel_read_reg(REG_STATUS_A)? & STATUS_ZYXDA != 0)
    }

    /// Read the raw accelerometer status register.
    pub fn read_status(&mut self) -> Result<u8, I2C::Error> {
        self.accel_read_reg(REG_STATUS_A)
    }

    /// Configure the accelerometer high-pass filter.
    ///
    /// `mode` is the HPM code 0–3, `cutoff` the HPCF code 0–3.
    pub fn set_high_pass(&mut self, mode: u8, cutoff: u8, filter_output: bool,
                         on_ia1: bool, on_ia2: bool, on_click: bool) -> Result<(), I2C::Error> {
        if mode > 3 || cutoff > 3 {
            return Ok(());
        }
        let mut ctrl2 = (mode << 6) | (cutoff << 4);
        if filter_output { ctrl2 |= CTRL2_FDS; }
        if on_click { ctrl2 |= CTRL2_HPCLICK; }
        if on_ia2 { ctrl2 |= CTRL2_HPIS2; }
        if on_ia1 { ctrl2 |= CTRL2_HPIS1; }
        self.accel_write(REG_CTRL2_A, ctrl2)
    }

    /// Reset the high-pass filter and return the reference value.
    pub fn reset_high_pass(&mut self) -> Result<u8, I2C::Error> {
        self.accel_read_reg(REG_REFERENCE_A)
    }

    /// Write the high-pass reference value.
    pub fn set_reference(&mut self, value: u8) -> Result<(), I2C::Error> {
        self.accel_write(REG_REFERENCE_A, value)
    }

    /// Configure the accelerometer FIFO.
    ///
    /// `watermark` is the level 0–31.
    pub fn set_fifo(&mut self, mode: u8, watermark: u8, trigger_int2: bool) -> Result<(), I2C::Error> {
        if mode > 3 || watermark > 31 {
            return Ok(());
        }
        let mut fifo = (mode << 6) | (watermark & 0x1F);
        if trigger_int2 { fifo |= 0x20; }
        self.accel_write(REG_FIFO_CTRL_A, fifo)?;
        let ctrl5 = self.accel_read_reg(REG_CTRL5_A)?;
        let value = if mode == FIFO_BYPASS { ctrl5 & !CTRL5_FIFO_EN } else { ctrl5 | CTRL5_FIFO_EN };
        self.accel_write(REG_CTRL5_A, value)
    }

    /// Return the number of unread sample sets in the FIFO.
    pub fn fifo_level(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.accel_read_reg(REG_FIFO_SRC_A)? & FIFO_SRC_LEVEL_MASK)
    }

    /// Read `count` sample sets from the FIFO.
    ///
    /// Each sample set is popped with one six-byte burst read. Returns the
    /// sample sets in *g*.
    pub fn read_fifo(&mut self, count: usize, out: &mut [(f32, f32, f32)]) -> Result<usize, I2C::Error> {
        let n = count.min(out.len());
        for i in 0..n {
            let raw = self.inner.accel_read(REG_OUT_X_L_A, 6)?;
            out[i] = self.inner.convert_accel(&raw);
        }
        Ok(n)
    }

    /// Read the decoded FIFO status as (watermark, overrun, empty, level).
    pub fn read_fifo_status(&mut self) -> Result<(bool, bool, bool, u8), I2C::Error> {
        let src = self.accel_read_reg(REG_FIFO_SRC_A)?;
        Ok((src & FIFO_SRC_WTM != 0,
            src & FIFO_SRC_OVERRUN != 0,
            src & FIFO_SRC_EMPTY != 0,
            src & FIFO_SRC_LEVEL_MASK))
    }

    /// Configure one inertial interrupt generator.
    ///
    /// `events` is a 6-bit mask of the enabled axis events, `threshold_g` the
    /// threshold relative to the current range and `duration_ms` the minimum
    /// event duration.
    pub fn set_motion_interrupt(&mut self, generator: u8, events: u8, threshold_g: f32,
                                duration_ms: f32, and_mode: bool) -> Result<(), I2C::Error> {
        if generator == 0 || generator > 2 || events > 0x3F {
            return Ok(());
        }
        let (cfg, ths, dur) = int_regs(generator);
        let mut value = events & 0x3F;
        if and_mode { value |= 0x80; }
        self.accel_write(cfg, value)?;
        self.accel_write(ths, encode_threshold(threshold_g, self.inner.fs_g))?;
        self.accel_write(dur, encode_duration(duration_ms, self.inner.odr_hz, 7))
    }

    /// Arm the free-fall detector of one generator.
    ///
    /// All three axes are watched for a low event and combined with AND.
    pub fn set_free_fall(&mut self, generator: u8, threshold_g: f32,
                         duration_ms: f32) -> Result<(), I2C::Error> {
        self.set_motion_interrupt(generator, 0x15, threshold_g, duration_ms, true)
    }

    /// Arm the 6D orientation detector of one generator.
    ///
    /// `position` selects position recognition (a level) over movement
    /// recognition (a one-ODR pulse); `four_d` drops the Z axis.
    pub fn set_orientation(&mut self, generator: u8, position: bool,
                           four_d: bool) -> Result<(), I2C::Error> {
        if generator == 0 || generator > 2 {
            return Ok(());
        }
        let (cfg, _, _) = int_regs(generator);
        let mut events = 0x3Fu8;
        if four_d { events &= !0x30; }
        let mut value = events | 0x40;
        if position { value |= 0x80; }
        self.accel_write(cfg, value)?;
        let ctrl5 = self.accel_read_reg(REG_CTRL5_A)?;
        let bit = if generator == 1 { CTRL5_D4D_INT1 } else { CTRL5_D4D_INT2 };
        let updated = if four_d { ctrl5 | bit } else { ctrl5 & !bit };
        self.accel_write(REG_CTRL5_A, updated)
    }

    /// Latch or release the interrupt of one generator.
    pub fn set_latch(&mut self, generator: u8, enabled: bool) -> Result<(), I2C::Error> {
        if generator == 0 || generator > 2 {
            return Ok(());
        }
        let ctrl5 = self.accel_read_reg(REG_CTRL5_A)?;
        let bit = if generator == 1 { CTRL5_LIR_INT1 } else { CTRL5_LIR_INT2 };
        let value = if enabled { ctrl5 | bit } else { ctrl5 & !bit };
        self.accel_write(REG_CTRL5_A, value)
    }

    /// Read the interrupt source register of one generator.
    ///
    /// Reading the register clears the latched interrupt.
    pub fn read_interrupt_source(&mut self, generator: u8) -> Result<u8, I2C::Error> {
        if generator == 0 || generator > 2 {
            return Ok(0);
        }
        let reg = if generator == 1 { REG_INT1_SOURCE_A } else { REG_INT2_SOURCE_A };
        self.accel_read_reg(reg)
    }

    /// Configure the click detector.
    ///
    /// `single` and `double_click` are axis masks built from
    /// [`CLICK_AXIS_X`], [`CLICK_AXIS_Y`] and [`CLICK_AXIS_Z`].
    pub fn set_click(&mut self, single: u8, double_click: u8, threshold_g: f32,
                     time_limit_ms: f32, latency_ms: f32, window_ms: f32) -> Result<(), I2C::Error> {
        if single > 0x07 || double_click > 0x07 {
            return Ok(());
        }
        let mut cfg = 0u8;
        if single & CLICK_AXIS_X != 0 { cfg |= 0x01; }
        if single & CLICK_AXIS_Y != 0 { cfg |= 0x04; }
        if single & CLICK_AXIS_Z != 0 { cfg |= 0x10; }
        if double_click & CLICK_AXIS_X != 0 { cfg |= 0x02; }
        if double_click & CLICK_AXIS_Y != 0 { cfg |= 0x08; }
        if double_click & CLICK_AXIS_Z != 0 { cfg |= 0x20; }
        self.accel_write(REG_CLICK_CFG_A, cfg)?;
        self.accel_write(REG_CLICK_THS_A, encode_threshold(threshold_g, self.inner.fs_g))?;
        self.accel_write(REG_TIME_LIMIT_A, encode_duration(time_limit_ms, self.inner.odr_hz, 7))?;
        self.accel_write(REG_TIME_LATENCY_A, encode_duration(latency_ms, self.inner.odr_hz, 8))?;
        self.accel_write(REG_TIME_WINDOW_A, encode_duration(window_ms, self.inner.odr_hz, 8))
    }

    /// Read the click source register.
    ///
    /// Reading the register clears the latched click interrupt.
    pub fn read_click_source(&mut self) -> Result<u8, I2C::Error> {
        self.accel_read_reg(REG_CLICK_SRC_A)
    }

    /// Set the polarity of the INT1 / INT2 outputs.
    pub fn set_interrupt_polarity(&mut self, active_low: bool) -> Result<(), I2C::Error> {
        let ctrl6 = self.accel_read_reg(REG_CTRL6_A)?;
        let value = if active_low { ctrl6 | CTRL6_H_LACTIVE } else { ctrl6 & !CTRL6_H_LACTIVE };
        self.accel_write(REG_CTRL6_A, value)
    }

    /// Set the magnetometer full-scale range in µT.
    pub fn set_mag_range(&mut self, range_ut: u16) -> Result<(), I2C::Error> {
        let code = match range_ut {
            130 => 0x20,
            190 => 0x40,
            250 => 0x60,
            400 => 0x80,
            470 => 0xA0,
            560 => 0xC0,
            810 => 0xE0,
            _ => return Ok(()),
        };
        let crb = self.mag_read_reg(REG_CRB_M)?;
        self.inner.mag_write(REG_CRB_M, (crb & !CRB_GN_MASK) | code)?;
        self.inner.mag_range_ut = range_ut;
        Ok(())
    }

    /// Set the magnetometer output data rate in Hz.
    pub fn set_mag_data_rate(&mut self, rate_hz: f32) -> Result<(), I2C::Error> {
        const RATES: [f32; 8] = [0.75, 1.5, 3.0, 7.5, 15.0, 30.0, 75.0, 220.0];
        for (code, rate) in RATES.iter().enumerate() {
            if (rate - rate_hz).abs() < 1e-6 {
                let cra = self.mag_read_reg(REG_CRA_M)?;
                self.inner.mag_write(REG_CRA_M, (cra & !CRA_DO_MASK) | ((code as u8) << 2))?;
                return Ok(());
            }
        }
        Ok(())
    }

    /// Set the magnetometer operating mode.
    pub fn set_mag_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        if mode > MAG_MODE_SLEEP {
            return Ok(());
        }
        let mr = self.mag_read_reg(REG_MR_M)?;
        self.inner.mag_write(REG_MR_M, (mr & !0x03) | mode)
    }

    /// Read the magnetic field as raw counts in X, Y, Z order.
    pub fn read_magnetic_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let raw = self.inner.mag_read(REG_OUT_X_H_M, 6)?;
        Ok(self.inner.reorder_mag(&raw))
    }

    /// Report whether a new magnetometer sample is available.
    pub fn mag_data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.mag_read_reg(REG_SR_M)? & SR_DRDY != 0)
    }

    /// Read the decoded magnetometer status as (drdy, lock).
    pub fn read_mag_status(&mut self) -> Result<(bool, bool), I2C::Error> {
        let sr = self.mag_read_reg(REG_SR_M)?;
        Ok((sr & SR_DRDY != 0, sr & SR_LOCK != 0))
    }

    /// Read the magnetometer identity bytes.
    pub fn read_mag_identity(&mut self) -> Result<[u8; 3], I2C::Error> {
        let mut buf = [0u8; 3];
        read_register(&mut self.inner.i2c, I2C_ADDRESS_MAG, REG_IRA_M as u32, 1, &mut buf)?;
        Ok(buf)
    }

    /// Enable or disable the magnetometer temperature sensor.
    pub fn enable_temperature(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let cra = self.mag_read_reg(REG_CRA_M)?;
        let value = if enabled { cra | CRA_TEMP_EN } else { cra & !CRA_TEMP_EN };
        self.inner.mag_write(REG_CRA_M, value)?;
        self.temp_enabled = enabled;
        Ok(())
    }

    /// Read the relative temperature of the magnetometer in °C.
    ///
    /// The sensor is enabled on first use. The datasheet gives no zero
    /// reference, so the value is a *delta* relative to an unspecified
    /// temperature and not an absolute °C reading.
    pub fn read_temperature_delta(&mut self) -> Result<f32, I2C::Error> {
        if !self.temp_enabled {
            self.enable_temperature(true)?;
        }
        let raw = self.inner.mag_read(REG_TEMP_OUT_H_M, 2)?;
        let value = to_signed(((raw[0] as u32) << 8) | raw[1] as u32, 16) >> 4;
        Ok(value as f32 / 8.0)
    }

    /// Compute the compass heading in degrees, 0–360.
    ///
    /// With `tilt_compensate` the heading is corrected for the tilt reported
    /// by the accelerometer; without it the flat (magnetic-only) heading is
    /// returned.
    pub fn heading(&mut self, tilt_compensate: bool) -> Result<f32, I2C::Error> {
        let (mx, my, mz) = self.read_magnetic()?;
        if !tilt_compensate {
            return Ok(wrap_degrees(my.atan2(mx) * 180.0 / core::f32::consts::PI));
        }
        let (ax, ay, az) = self.read_acceleration()?;
        let roll = ay.atan2(az);
        let pitch = (-ax).atan2(ay * roll.sin() + az * roll.cos());
        let xh = mx * pitch.cos() + mz * pitch.sin();
        let yh = mx * roll.sin() * pitch.sin() + my * roll.cos() - mz * roll.sin() * pitch.cos();
        Ok(wrap_degrees(yh.atan2(xh) * 180.0 / core::f32::consts::PI))
    }

    /// Route one interrupt source to INT1 or INT2.
    ///
    /// Only the routing is configured; the generator or function behind the
    /// source is set up with the `set_*` methods.
    pub fn enable_interrupt(&mut self, source: u16, pin: u8) -> Result<(), I2C::Error> {
        if pin != 1 && pin != 2 {
            return Ok(());
        }
        let (ctrl3_bit, ctrl6_bit) = source_bits(source);
        if ctrl3_bit.is_none() && ctrl6_bit.is_none() {
            return Ok(());
        }
        if let Some(bit) = ctrl3_bit {
            if pin == 1 || ctrl6_bit.is_none() {
                let ctrl3 = self.accel_read_reg(REG_CTRL3_A)?;
                self.accel_write(REG_CTRL3_A, ctrl3 | bit)?;
            }
        }
        if let Some(bit) = ctrl6_bit {
            if pin == 2 || ctrl3_bit.is_none() {
                let ctrl6 = self.accel_read_reg(REG_CTRL6_A)?;
                self.accel_write(REG_CTRL6_A, ctrl6 | bit)?;
            }
        }
        self.enabled_sources |= source;
        Ok(())
    }

    /// Remove one interrupt source from INT1 or INT2.
    pub fn disable_interrupt(&mut self, source: u16, pin: u8) -> Result<(), I2C::Error> {
        if pin != 1 && pin != 2 {
            return Ok(());
        }
        let (ctrl3_bit, ctrl6_bit) = source_bits(source);
        if ctrl3_bit.is_none() && ctrl6_bit.is_none() {
            return Ok(());
        }
        if let Some(bit) = ctrl3_bit {
            if pin == 1 || ctrl6_bit.is_none() {
                let ctrl3 = self.accel_read_reg(REG_CTRL3_A)?;
                self.accel_write(REG_CTRL3_A, ctrl3 & !bit)?;
            }
        }
        if let Some(bit) = ctrl6_bit {
            if pin == 2 || ctrl3_bit.is_none() {
                let ctrl6 = self.accel_read_reg(REG_CTRL6_A)?;
                self.accel_write(REG_CTRL6_A, ctrl6 & !bit)?;
            }
        }
        self.enabled_sources &= !source;
        Ok(())
    }

    /// Read the interrupt status of every enabled source.
    ///
    /// Returns a mask of the `SOURCE_*` constants.
    pub fn poll_interrupt(&mut self) -> Result<u16, I2C::Error> {
        let mut status = 0u16;
        if self.accel_read_reg(REG_STATUS_A)? & STATUS_ZYXDA != 0 {
            status |= SOURCE_DATA_READY;
        }
        let fifo = self.accel_read_reg(REG_FIFO_SRC_A)?;
        if fifo & FIFO_SRC_WTM != 0 { status |= SOURCE_WATERMARK; }
        if fifo & FIFO_SRC_OVERRUN != 0 { status |= SOURCE_OVERRUN; }
        if self.enabled_sources & SOURCE_IA1 != 0 {
            if self.accel_read_reg(REG_INT1_SOURCE_A)? & SOURCE_IA != 0 {
                status |= SOURCE_IA1;
            }
        }
        if self.enabled_sources & SOURCE_IA2 != 0 {
            if self.accel_read_reg(REG_INT2_SOURCE_A)? & SOURCE_IA != 0 {
                status |= SOURCE_IA2;
            }
        }
        if self.enabled_sources & SOURCE_CLICK != 0 {
            if self.accel_read_reg(REG_CLICK_SRC_A)? & SOURCE_IA != 0 {
                status |= SOURCE_CLICK;
            }
        }
        if self.mag_read_reg(REG_SR_M)? & SR_DRDY != 0 {
            status |= SOURCE_MAG_DATA_READY;
        }
        Ok(status)
    }

    /// Reboot the accelerometer's memory content.
    ///
    /// The `BOOT` bit reloads the factory trim values. The initialization
    /// sequence is run again afterwards, so any configuration applied after
    /// `init()` has to be repeated.
    pub fn reboot(&mut self) -> Result<(), I2C::Error> {
        let ctrl5 = self.accel_read_reg(REG_CTRL5_A)?;
        self.accel_write(REG_CTRL5_A, ctrl5 | CTRL5_BOOT)?;
        self.inner.init()?;
        self.temp_enabled = false;
        Ok(())
    }

    /// Read one byte from an accelerometer register.
    fn accel_read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let buf = self.inner.accel_read(reg, 1)?;
        Ok(buf[0])
    }

    /// Write one byte to an accelerometer register.
    fn accel_write(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.inner.accel_write(reg, value)
    }

    /// Read one byte from a magnetometer register.
    fn mag_read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let buf = self.inner.mag_read(reg, 1)?;
        Ok(buf[0])
    }
}

/// Return the (config, threshold, duration) register triple of a generator.
fn int_regs(generator: u8) -> (u8, u8, u8) {
    match generator {
        1 => (REG_INT1_CFG_A, REG_INT1_THS_A, REG_INT1_DURATION_A),
        _ => (REG_INT2_CFG_A, REG_INT2_THS_A, REG_INT2_DURATION_A),
    }
}

/// Convert a threshold in *g* into a 7-bit register code.
fn encode_threshold(threshold_g: f32, fs_g: u8) -> u8 {
    let mut code = (threshold_g * 128.0 / fs_g as f32 + 0.5) as i32;
    if code < 0 { code = 0; }
    if code > 127 { code = 127; }
    code as u8
}

/// Convert a duration in milliseconds into a register code.
///
/// One LSB is 1/ODR; the code is clamped to the register width.
fn encode_duration(duration_ms: f32, odr_hz: f32, bits: u32) -> u8 {
    let mut code = if odr_hz > 0.0 {
        (duration_ms * odr_hz / 1000.0 + 0.5) as i32
    } else {
        0
    };
    let max_value = (1i32 << bits) - 1;
    if code < 0 { code = 0; }
    if code > max_value { code = max_value; }
    code as u8
}

/// Return the (CTRL_REG3_A, CTRL_REG6_A) routing bits of a source.
fn source_bits(source: u16) -> (Option<u8>, Option<u8>) {
    match source {
        SOURCE_DATA_READY => (Some(CTRL3_I1_DRDY1), None),
        SOURCE_WATERMARK => (Some(CTRL3_I1_WTM), None),
        SOURCE_OVERRUN => (Some(CTRL3_I1_OVERRUN), None),
        SOURCE_IA1 => (Some(CTRL3_I1_AOI1), Some(CTRL6_I2_INT1)),
        SOURCE_IA2 => (Some(CTRL3_I1_AOI2), Some(CTRL6_I2_INT2)),
        SOURCE_CLICK => (Some(CTRL3_I1_CLICK), Some(CTRL6_I2_CLICK)),
        SOURCE_BOOT => (None, Some(CTRL6_BOOT_I1)),
        _ => (None, None),
    }
}

/// Wrap an angle in degrees into the 0–360 range.
fn wrap_degrees(degrees: f32) -> f32 {
    let mut value = degrees % 360.0;
    if value < 0.0 { value += 360.0; }
    value
}

/// Interpret the low `bits` bits of `value` as two's-complement signed.
fn to_signed(value: u32, bits: u32) -> i32 {
    let sign_bit = 1u32 << (bits - 1);
    ((value & (sign_bit - 1)).wrapping_sub(value & sign_bit)) as i32
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ACCEL: u8 = I2C_ADDRESS_ACCEL;
    const MAG: u8 = I2C_ADDRESS_MAG;

    /// The initialization sequence, in the order the driver performs it.
    fn init_seq() -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(MAG, vec![REG_IRA_M], vec![0x48, 0x34, 0x33]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL2_A, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL3_A, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL5_A, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL6_A, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_FIFO_CTRL_A, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL4_A, 0x88]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL1_A, 0x57]),
            I2cTransaction::write(MAG, vec![REG_CRA_M, 0x10]),
            I2cTransaction::write(MAG, vec![REG_CRB_M, 0x20]),
            I2cTransaction::write(MAG, vec![REG_MR_M, 0x00]),
        ]
    }

    fn new_minimal() -> Lsm303Minimal<I2cMock> {
        let i2c = I2cMock::new(&init_seq());
        Lsm303Minimal::new(i2c).expect("new")
    }

    fn new_full() -> Lsm303Full<I2cMock> {
        let i2c = I2cMock::new(&init_seq());
        Lsm303Full::new(i2c).expect("new")
    }

    /// Preload the accelerometer output registers with 12-bit counts.
    ///
    /// The high-resolution mode writes the 12-bit value left-justified into
    /// the 16-bit register pair.
    fn accel_output(x: i16, y: i16, z: i16) -> Vec<u8> {
        let mut out = Vec::new();
        for v in [x << 4, y << 4, z << 4] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out
    }

    /// Preload the magnetometer output registers with 12-bit counts.
    ///
    /// The magnetometer writes the 12-bit value right-justified into the
    /// 16-bit register pair and sends X, Z, Y on the wire.
    fn mag_output(x: i16, y: i16, z: i16) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&x.to_be_bytes());  // X
        out.extend_from_slice(&z.to_be_bytes());  // Z
        out.extend_from_slice(&y.to_be_bytes());  // Y
        out
    }

    #[test]
    fn construction_and_read_acceleration() {
        let mut chip = new_minimal();
        // The multi-byte read sets the auto-increment bit.
        chip.i2c.update_expectations(&[I2cTransaction::write_read(
            ACCEL,
            vec![REG_OUT_X_L_A | ACCEL_AUTO_INCREMENT],
            accel_output(1000, -2000, 2000),
        )]);
        let (x, y, z) = chip.read_acceleration().unwrap();
        assert!((x - 1.0).abs() < 1e-6);
        assert!((y - (-2.0)).abs() < 1e-6);
        assert!((z - 2.0).abs() < 1e-6);
        chip.i2c.done();
    }

    #[test]
    fn construction_and_read_magnetic() {
        let mut chip = new_minimal();
        chip.i2c.update_expectations(&[I2cTransaction::write_read(
            MAG, vec![REG_OUT_X_H_M], mag_output(1000, 3000, 2000),
        )]);
        let (x, y, z) = chip.read_magnetic().unwrap();
        assert!((x - (1000.0 / 1100.0 * 100.0)).abs() < 1e-3);
        assert!((y - (3000.0 / 1100.0 * 100.0)).abs() < 1e-3);
        assert!((z - (2000.0 / 980.0 * 100.0)).abs() < 1e-3);
        chip.i2c.done();
    }

    #[test]
    fn set_range_rewrites_fs_field() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL4_A], vec![0x88]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL4_A, 0xA8]),
        ]);
        chip.set_range(8).unwrap();
        assert_eq!(chip.inner.fs_g, 8);
        chip.inner.i2c.done();
    }

    #[test]
    fn set_data_rate_rewrites_odr_field() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL1_A, 0x77]),
        ]);
        chip.set_data_rate(400).unwrap();
        assert_eq!(chip.inner.odr_hz, 400.0);
        chip.inner.i2c.done();
    }

    #[test]
    fn set_mode_switches_lpen_and_hr() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL4_A], vec![0x88]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL1_A, 0x5F]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL4_A, 0x80]),
        ]);
        chip.set_mode(MODE_LOW_POWER).unwrap();
        assert_eq!(chip.inner.mode, MODE_LOW_POWER);
        chip.inner.i2c.done();
    }

    #[test]
    fn set_axes_rewrites_axis_bits() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL1_A, 0x55]),
        ]);
        chip.set_axes(true, false, true).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn power_down_clears_odr() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL1_A], vec![0x57]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL1_A, 0x07]),
        ]);
        chip.power_down().unwrap();
        assert_eq!(chip.inner.odr_hz, 0.0);
        chip.inner.i2c.done();
    }

    #[test]
    fn status_and_data_ready() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(ACCEL, vec![REG_STATUS_A], vec![0x08]),
            I2cTransaction::write_read(ACCEL, vec![REG_STATUS_A], vec![0x08]),
        ]);
        assert!(chip.new_data_available().unwrap());
        assert!(chip.new_data_available().unwrap());
        chip.inner.i2c.done();
    }

    #[test]
    fn fifo_configuration_and_status() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write(ACCEL, vec![REG_FIFO_CTRL_A, 0x90]),
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL5_A], vec![0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL5_A, 0x40]),
            I2cTransaction::write_read(ACCEL, vec![REG_FIFO_SRC_A], vec![0xE7]),
        ]);
        chip.set_fifo(FIFO_STREAM, 16, false).unwrap();
        let (wm, ov, empty, level) = chip.read_fifo_status().unwrap();
        assert!(wm);
        assert!(ov);
        assert!(empty);
        assert_eq!(level, 7);
        chip.inner.i2c.done();
    }

    #[test]
    fn motion_interrupt_threshold_is_range_relative() {
        let mut chip = new_full();
        // The threshold code is relative to the current range: 0.5 g * 128 / 8.
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL4_A], vec![0x88]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL4_A, 0xA8]),
            I2cTransaction::write(ACCEL, vec![REG_INT1_CFG_A, 0x02]),
            I2cTransaction::write(ACCEL, vec![REG_INT1_THS_A, 0x08]),
            I2cTransaction::write(ACCEL, vec![REG_INT1_DURATION_A, 0x01]),
        ]);
        chip.set_range(8).unwrap();
        chip.set_motion_interrupt(1, 0x02, 0.5, 10.0, false).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn click_configuration() {
        let mut chip = new_full();
        // The threshold code is relative to the current range (+-2 g here):
        // 0.8 g * 128 / 2 = 51 = 0x33.
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write(ACCEL, vec![REG_CLICK_CFG_A, 0x20]),
            I2cTransaction::write(ACCEL, vec![REG_CLICK_THS_A, 0x33]),
            I2cTransaction::write(ACCEL, vec![REG_TIME_LIMIT_A, 0x02]),
            I2cTransaction::write(ACCEL, vec![REG_TIME_LATENCY_A, 0x04]),
            I2cTransaction::write(ACCEL, vec![REG_TIME_WINDOW_A, 0x14]),
        ]);
        chip.set_click(0, 0x04, 0.8, 20.0, 40.0, 200.0).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn magnetometer_configuration() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(MAG, vec![REG_CRB_M], vec![0x20]),
            I2cTransaction::write(MAG, vec![REG_CRB_M, 0x80]),
            I2cTransaction::write_read(MAG, vec![REG_CRA_M], vec![0x10]),
            I2cTransaction::write(MAG, vec![REG_CRA_M, 0x14]),
        ]);
        chip.set_mag_range(400).unwrap();
        chip.set_mag_data_rate(30.0).unwrap();
        assert_eq!(chip.inner.mag_range_ut, 400);
        chip.inner.i2c.done();
    }

    #[test]
    fn magnetometer_identity_and_status() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(MAG, vec![REG_IRA_M], vec![0x48, 0x34, 0x33]),
            I2cTransaction::write_read(MAG, vec![REG_SR_M], vec![0x03]),
        ]);
        assert_eq!(chip.read_mag_identity().unwrap(), [0x48, 0x34, 0x33]);
        let (drdy, lock) = chip.read_mag_status().unwrap();
        assert!(drdy);
        assert!(lock);
        chip.inner.i2c.done();
    }

    #[test]
    fn heading_follows_magnetic_field() {
        let mut chip = new_full();
        // X = 1100 counts (1 gauss), Y = 0 -> heading 0 deg (north).
        chip.inner.i2c.update_expectations(&[I2cTransaction::write_read(
            MAG, vec![REG_OUT_X_H_M], mag_output(1100, 0, 0),
        )]);
        let heading = chip.heading(false).unwrap();
        assert!((heading - 0.0).abs() < 1e-3);
        chip.inner.i2c.done();
    }

    #[test]
    fn interrupt_routing() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL3_A], vec![0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL3_A, 0x80]),
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL3_A], vec![0x80]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL3_A, 0x00]),
        ]);
        chip.enable_interrupt(SOURCE_CLICK, 1).unwrap();
        chip.disable_interrupt(SOURCE_CLICK, 1).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn poll_interrupt_combines_status_bits() {
        let mut chip = new_full();
        chip.inner.i2c.update_expectations(&[
            I2cTransaction::write_read(ACCEL, vec![REG_STATUS_A], vec![0x08]),
            I2cTransaction::write_read(ACCEL, vec![REG_FIFO_SRC_A], vec![0x80]),
            I2cTransaction::write_read(MAG, vec![REG_SR_M], vec![0x00]),
        ]);
        let status = chip.poll_interrupt().unwrap();
        assert_ne!(status & SOURCE_DATA_READY, 0);
        assert_ne!(status & SOURCE_WATERMARK, 0);
        assert_eq!(status & SOURCE_OVERRUN, 0);
        chip.inner.i2c.done();
    }

    #[test]
    fn reboot_sets_boot_bit() {
        let mut chip = new_full();
        let mut seq = vec![I2cTransaction::write_read(ACCEL, vec![REG_CTRL5_A], vec![0x00])];
        seq.push(I2cTransaction::write(ACCEL, vec![REG_CTRL5_A, 0x80]));
        seq.extend(init_seq());
        chip.inner.i2c.update_expectations(&seq);
        chip.reboot().unwrap();
        chip.inner.i2c.done();
    }
}
