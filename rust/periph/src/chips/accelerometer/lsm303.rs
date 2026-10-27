//! LSM303 — 3-axis accelerometer + 3-axis magnetometer e-compass (ST).
//!
//! The LSM303DLHC combines a three-axis MEMS accelerometer (I²C `0x19`) and
//! a three-axis magnetometer (I²C `0x1E`) behind two independent slave
//! addresses on one bus. The accelerometer has four full-scale ranges
//! (±2 / ±4 / ±8 / ±16 *g*), left-justified 16-bit output, three operating
//! modes (low-power / normal / high-resolution), a 32-level FIFO, a
//! high-pass filter, two inertial interrupt generators and click detection.
//! The magnetometer has seven gain settings (±1.3 to ±8.1 gauss), 12-bit
//! output, data rates from 0.75 Hz to 220 Hz and a relative-only temperature
//! sensor.
//!
//! ## Transport
//!
//! Both slaves share the one I²C bus, so the driver owns a single
//! [`Connection`] and takes both addresses. Accelerometer multi-byte
//! transfers OR 0x80 into the sub-address (auto-increment); the magnetometer
//! pointer always auto-increments and needs no such bit.
//!
//! ## Interrupts
//!
//! Rust drivers expose `poll_interrupt()` only — the caller wires it into an
//! ISR or polling loop. Reading `INTx_SOURCE_A` / `CLICK_SRC_A` clears the
//! latched flags.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register::to_signed;
use crate::connection::Connection;

// --- Accelerometer registers (file-private) ------------------------------
const REG_CTRL_REG1: u8 = 0x20;
const REG_CTRL_REG2: u8 = 0x21;
const REG_CTRL_REG3: u8 = 0x22;
const REG_CTRL_REG4: u8 = 0x23;
const REG_CTRL_REG5: u8 = 0x24;
const REG_CTRL_REG6: u8 = 0x25;
const REG_REFERENCE: u8 = 0x26;
const REG_STATUS: u8 = 0x27;
const REG_OUT_X_L: u8 = 0x28;
const REG_FIFO_CTRL: u8 = 0x2E;
const REG_FIFO_SRC: u8 = 0x2F;
const REG_INT1_CFG: u8 = 0x30;
const REG_INT1_SRC: u8 = 0x31;
const REG_INT1_THS: u8 = 0x32;
const REG_INT1_DUR: u8 = 0x33;
const REG_INT2_CFG: u8 = 0x34;
const REG_INT2_SRC: u8 = 0x35;
const REG_INT2_THS: u8 = 0x36;
const REG_INT2_DUR: u8 = 0x37;
const REG_CLICK_CFG: u8 = 0x38;
const REG_CLICK_SRC: u8 = 0x39;
const REG_CLICK_THS: u8 = 0x3A;
const REG_TIME_LIMIT: u8 = 0x3B;
const REG_TIME_LATENCY: u8 = 0x3C;
const REG_TIME_WINDOW: u8 = 0x3D;

// --- Magnetometer registers (file-private) -------------------------------
const MAG_REG_CRA: u8 = 0x00;
const MAG_REG_CRB: u8 = 0x01;
const MAG_REG_MR: u8 = 0x02;
const MAG_REG_OUT_X_H: u8 = 0x03;
const MAG_REG_SR: u8 = 0x09;
const MAG_REG_IRA: u8 = 0x0A;
const MAG_REG_TEMP_H: u8 = 0x31;
const MAG_REG_TEMP_L: u8 = 0x32;

const MAG_ID: [u8; 3] = [0x48, 0x34, 0x33];

/// Default accelerometer I²C address.
pub const LSM303_ACCEL_ADDRESS: u8 = 0x19;
/// Default magnetometer I²C address.
pub const LSM303_MAG_ADDRESS: u8 = 0x1E;

/// Interrupt source: new accelerometer sample (`STATUS_REG_A.ZYXDA`).
pub const SOURCE_DATA_READY: u16 = 0x0001;
/// Interrupt source: FIFO watermark (`FIFO_SRC_REG_A.WTM`).
pub const SOURCE_WATERMARK: u16 = 0x0002;
/// Interrupt source: FIFO overrun (`FIFO_SRC_REG_A.OVRN_FIFO`).
pub const SOURCE_OVERRUN: u16 = 0x0004;
/// Interrupt source: inertial generator 1 event (`INT1_SOURCE_A.IA`).
pub const SOURCE_IA1: u16 = 0x0008;
/// Interrupt source: inertial generator 2 event (`INT2_SOURCE_A.IA`).
pub const SOURCE_IA2: u16 = 0x0010;
/// Interrupt source: single / double click (`CLICK_SRC_A.IA`).
pub const SOURCE_CLICK: u16 = 0x0020;
/// Interrupt source: boot procedure running — pin-only, never reported.
pub const SOURCE_BOOT: u16 = 0x0040;
/// Interrupt source: new magnetometer sample (`SR_REG_M.DRDY`).
pub const SOURCE_MAG_DATA_READY: u16 = 0x0080;

/// Operating mode: low-power, 8-bit output.
pub const MODE_LOW_POWER: u8 = 0;
/// Operating mode: normal, 10-bit output.
pub const MODE_NORMAL: u8 = 1;
/// Operating mode: high-resolution, 12-bit output.
pub const MODE_HIGH_RESOLUTION: u8 = 2;

/// FIFO mode: bypass.
pub const FIFO_BYPASS: u8 = 0;
/// FIFO mode: FIFO.
pub const FIFO_FIFO: u8 = 1;
/// FIFO mode: stream.
pub const FIFO_STREAM: u8 = 2;
/// FIFO mode: trigger (stream-to-FIFO).
pub const FIFO_STREAM_TO_FIFO: u8 = 3;

const CTRL_REG4_DEFAULT: u8 = 0x88;
const CTRL_REG1_DEFAULT: u8 = 0x57;
const DEFAULT_RANGE_G: u8 = 2;
const DEFAULT_RATE_HZ: u16 = 100;
const DEFAULT_MODE: u8 = MODE_HIGH_RESOLUTION;
const DEFAULT_MAG_GAIN: u8 = 0x20;
const DEFAULT_MAG_RATE_HZ: f32 = 15.0;

/// LSM303 minimal driver — acceleration in *g* and magnetic flux density in µT.
pub struct Lsm303Minimal<I2C, D> {
    conn: Connection<I2C>,
    delay: D,
    accel_addr: u8,
    mag_addr: u8,
    range_g: u8,
    mode: u8,
    rate_hz: u16,
    mag_gain_code: u8,
    mag_rate_hz: f32,
}

impl<I2C: I2c, D: DelayNs> Lsm303Minimal<I2C, D> {
    /// Create a new driver and run the Initialization Sequence.
    ///
    /// # Arguments
    /// * `conn` — [`Connection`] on the shared I²C bus.
    /// * `accel_addr` — accelerometer address (normally [`LSM303_ACCEL_ADDRESS`]).
    /// * `mag_addr` — magnetometer address (normally [`LSM303_MAG_ADDRESS`]).
    /// * `delay` — delay source for the boot and turn-on times.
    ///
    /// # Panics
    /// Panics if the magnetometer identity is not `0x48 0x34 0x33`.
    pub fn new(
        conn: Connection<I2C>,
        accel_addr: u8,
        mag_addr: u8,
        delay: D,
    ) -> Result<Self, I2C::Error> {
        let mut chip = Self {
            conn,
            delay,
            accel_addr,
            mag_addr,
            range_g: DEFAULT_RANGE_G,
            mode: DEFAULT_MODE,
            rate_hz: DEFAULT_RATE_HZ,
            mag_gain_code: DEFAULT_MAG_GAIN,
            mag_rate_hz: DEFAULT_MAG_RATE_HZ,
        };
        chip.run_init_sequence()?;
        Ok(chip)
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns `(x, y, z)` in *g*.
    pub fn read_acceleration(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut raw = [0u8; 6];
        self.read_burst(REG_OUT_X_L, &mut raw)?;
        let scale = sensitivity_mg(self.mode, self.range_g) / 1000.0;
        let shift = resolution_shift(self.mode);
        Ok((
            decode_axis(&raw[0..2], shift) as f32 * scale,
            decode_axis(&raw[2..4], shift) as f32 * scale,
            decode_axis(&raw[4..6], shift) as f32 * scale,
        ))
    }

    /// Read 3-axis magnetic flux density.
    ///
    /// The wire order is X, Z, Y with the high byte first; the result is
    /// reordered to X, Y, Z. Returns `(x, y, z)` in µT.
    pub fn read_magnetic(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let (xy_gain, z_gain) = mag_gains(self.mag_gain_code);
        let mut raw = [0u8; 6];
        self.mag_read_burst(MAG_REG_OUT_X_H, &mut raw)?;
        let x = decode_mag_axis(&raw[0..2]) as f32 / xy_gain * 100.0;
        let z = decode_mag_axis(&raw[2..4]) as f32 / z_gain * 100.0;
        let y = decode_mag_axis(&raw[4..6]) as f32 / xy_gain * 100.0;
        Ok((x, y, z))
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.conn.read(self.accel_addr, reg, &mut buf)?;
        Ok(buf[0])
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.conn.write(self.accel_addr, reg, &[value])
    }

    fn read_burst(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        let sub = if buf.len() > 1 { reg | 0x80 } else { reg };
        self.conn.read(self.accel_addr, sub, buf)
    }

    fn mag_read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.conn.read(self.mag_addr, reg, &mut buf)?;
        Ok(buf[0])
    }

    fn mag_write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.conn.write(self.mag_addr, reg, &[value])
    }

    fn mag_read_burst(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        self.conn.read(self.mag_addr, reg, buf)
    }

    fn run_init_sequence(&mut self) -> Result<(), I2C::Error> {
        self.delay.delay_ms(5);

        let mut identity = [0u8; 3];
        self.mag_read_burst(MAG_REG_IRA, &mut identity)?;
        if identity != MAG_ID {
            panic!(
                "LSM303 magnetometer identity: expected 48 34 33, got {:02X} {:02X} {:02X}",
                identity[0], identity[1], identity[2]
            );
        }

        self.write_reg(REG_CTRL_REG2, 0x00)?;
        self.write_reg(REG_CTRL_REG3, 0x00)?;
        self.write_reg(REG_CTRL_REG5, 0x00)?;
        self.write_reg(REG_CTRL_REG6, 0x00)?;
        self.write_reg(REG_FIFO_CTRL, 0x00)?;
        self.write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT)?;
        self.write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)?;

        self.mag_write_reg(MAG_REG_CRA, 0x10)?;
        self.mag_write_reg(MAG_REG_CRB, self.mag_gain_code)?;
        self.mag_write_reg(MAG_REG_MR, 0x00)?;

        let mag_settle = ceil_f32(1000.0 / self.mag_rate_hz);
        let settle = settle_ms(self.mode, self.rate_hz);
        self.delay.delay_ms(if settle > mag_settle { settle } else { mag_settle });
        Ok(())
    }

}

fn settle_ms(mode: u8, rate_hz: u16) -> u32 {
    if rate_hz == 0 {
        return 0;
    }
    let period_ms = 1000.0 / rate_hz as f32;
    let turn_on_ms = match mode {
        MODE_LOW_POWER => 1.0,
        MODE_NORMAL => 1.6,
        _ => 7.0 * period_ms,
    };
    ceil_f32(turn_on_ms.max(period_ms))
}

/// LSM303 full driver — composition over the minimal driver.
pub struct Lsm303Full<I2C, D> {
    inner: Lsm303Minimal<I2C, D>,
    enabled1: u16,
    enabled2: u16,
}

impl<I2C: I2c, D: DelayNs> Lsm303Full<I2C, D> {
    /// Create a new full driver and run the Initialization Sequence.
    pub fn new(
        conn: Connection<I2C>,
        accel_addr: u8,
        mag_addr: u8,
        delay: D,
    ) -> Result<Self, I2C::Error> {
        let inner = Lsm303Minimal::new(conn, accel_addr, mag_addr, delay)?;
        Ok(Self { inner, enabled1: 0, enabled2: 0 })
    }

    /// Read 3-axis acceleration in *g*.
    pub fn read_acceleration(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read_acceleration()
    }

    /// Read 3-axis magnetic flux density in µT.
    pub fn read_magnetic(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read_magnetic()
    }

    /// Set the accelerometer full-scale range (2, 4, 8 or 16 *g*).
    pub fn set_range(&mut self, range_g: u8) -> Result<(), I2C::Error> {
        let fs = match range_g {
            2 => 0x00,
            4 => 0x10,
            8 => 0x20,
            16 => 0x30,
            _ => return Ok(()),
        };
        let ctrl4 = self.inner.read_reg(REG_CTRL_REG4)?;
        self.inner.write_reg(REG_CTRL_REG4, (ctrl4 & !0x30) | fs)?;
        self.inner.range_g = range_g;
        Ok(())
    }

    /// Set the accelerometer output data rate (or 0 to power down).
    pub fn set_data_rate(&mut self, rate_hz: u16) -> Result<(), I2C::Error> {
        let code = match rate_code(rate_hz) {
            Some(code) => code,
            None => return Ok(()),
        };
        if is_low_power_only(rate_hz) && self.inner.mode != MODE_LOW_POWER {
            return Ok(());
        }
        if is_normal_hr_only(rate_hz) && self.inner.mode == MODE_LOW_POWER {
            return Ok(());
        }
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        self.inner.write_reg(REG_CTRL_REG1, (ctrl1 & 0x0F) | code)?;
        self.inner.rate_hz = rate_hz;
        let settle = settle_ms(self.inner.mode, self.inner.rate_hz);
        if settle > 0 {
            self.inner.delay.delay_ms(settle);
        }
        Ok(())
    }

    /// Set the accelerometer operating mode.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        if mode > MODE_HIGH_RESOLUTION {
            return Ok(());
        }
        if mode == MODE_LOW_POWER && is_normal_hr_only(self.inner.rate_hz) {
            return Ok(());
        }
        if mode != MODE_LOW_POWER && is_low_power_only(self.inner.rate_hz) {
            return Ok(());
        }
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        let ctrl4 = self.inner.read_reg(REG_CTRL_REG4)?;
        let (ctrl1, ctrl4) = if mode == MODE_LOW_POWER {
            (ctrl1 | 0x08, ctrl4 & !0x08)
        } else {
            let hr = if mode == MODE_HIGH_RESOLUTION { 0x08 } else { 0x00 };
            (ctrl1 & !0x08, (ctrl4 & !0x08) | hr)
        };
        self.inner.write_reg(REG_CTRL_REG1, ctrl1)?;
        self.inner.write_reg(REG_CTRL_REG4, ctrl4)?;
        self.inner.mode = mode;
        let settle = settle_ms(self.inner.mode, self.inner.rate_hz);
        if settle > 0 {
            self.inner.delay.delay_ms(settle);
        }
        Ok(())
    }

    /// Enable or disable the accelerometer axes.
    pub fn set_axes(&mut self, x: bool, y: bool, z: bool) -> Result<(), I2C::Error> {
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        let axes = (x as u8) | ((y as u8) << 1) | ((z as u8) << 2);
        self.inner.write_reg(REG_CTRL_REG1, (ctrl1 & 0xF8) | axes)
    }

    /// Stop accelerometer conversions (`ODR` = 0000).
    pub fn power_down(&mut self) -> Result<(), I2C::Error> {
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        self.inner.write_reg(REG_CTRL_REG1, ctrl1 & 0x0F)?;
        self.inner.rate_hz = 0;
        Ok(())
    }

    /// Read raw accelerometer counts.
    pub fn read_acceleration_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut raw = [0u8; 6];
        self.inner.read_burst(REG_OUT_X_L, &mut raw)?;
        let shift = resolution_shift(self.inner.mode);
        Ok((
            decode_axis(&raw[0..2], shift),
            decode_axis(&raw[2..4], shift),
            decode_axis(&raw[4..6], shift),
        ))
    }

    /// Return true when `STATUS_REG_A.ZYXDA` is set.
    pub fn new_data_available(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_STATUS)? & 0x08 != 0)
    }

    /// Read `STATUS_REG_A`.
    pub fn read_status(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_STATUS)
    }

    /// Configure the high-pass filter.
    pub fn set_high_pass(
        &mut self,
        mode: u8,
        cutoff: u8,
        filter_output: bool,
        on_ia1: bool,
        on_ia2: bool,
        on_click: bool,
    ) -> Result<(), I2C::Error> {
        if mode > 3 || cutoff > 3 {
            return Ok(());
        }
        let mut value = (mode << 6) | (cutoff << 4);
        if filter_output { value |= 0x08; }
        if on_click { value |= 0x04; }
        if on_ia2 { value |= 0x02; }
        if on_ia1 { value |= 0x01; }
        self.inner.write_reg(REG_CTRL_REG2, value)
    }

    /// Reset the high-pass filter by reading `REFERENCE_A`.
    pub fn reset_high_pass(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_REFERENCE)
    }

    /// Write the high-pass reference value.
    pub fn set_reference(&mut self, value: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_REFERENCE, value)
    }

    /// Configure the 32-level FIFO.
    pub fn set_fifo(&mut self, mode: u8, watermark: u8, trigger_int2: bool) -> Result<(), I2C::Error> {
        if mode > FIFO_STREAM_TO_FIFO || watermark > 31 {
            return Ok(());
        }
        let value = (mode << 6) | (if trigger_int2 { 0x20 } else { 0x00 }) | watermark;
        self.inner.write_reg(REG_FIFO_CTRL, value)?;
        let ctrl5 = self.inner.read_reg(REG_CTRL_REG5)?;
        let out = if mode == FIFO_BYPASS { ctrl5 & !0x40 } else { ctrl5 | 0x40 };
        self.inner.write_reg(REG_CTRL_REG5, out)
    }

    /// Read the number of unread sample sets in the FIFO.
    pub fn fifo_level(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.inner.read_reg(REG_FIFO_SRC)? & 0x1F)
    }

    /// Drain FIFO sample sets as `(x, y, z)` tuples in *g*.
    pub fn read_fifo(&mut self, count: Option<u8>) -> Result<heapless::Vec<(f32, f32, f32), 32>, I2C::Error> {
        let level = self.fifo_level()? as usize;
        let n = match count {
            Some(count) => core::cmp::min(count as usize, level),
            None => level,
        };
        let scale = sensitivity_mg(self.inner.mode, self.inner.range_g) / 1000.0;
        let shift = resolution_shift(self.inner.mode);
        let mut out = heapless::Vec::new();
        for _ in 0..n {
            let mut raw = [0u8; 6];
            self.inner.read_burst(REG_OUT_X_L, &mut raw)?;
            let _ = out.push((
                decode_axis(&raw[0..2], shift) as f32 * scale,
                decode_axis(&raw[2..4], shift) as f32 * scale,
                decode_axis(&raw[4..6], shift) as f32 * scale,
            ));
        }
        Ok(out)
    }

    /// Decode `FIFO_SRC_REG_A` as `(watermark, overrun, empty, level)`.
    pub fn read_fifo_status(&mut self) -> Result<(bool, bool, bool, u8), I2C::Error> {
        let raw = self.inner.read_reg(REG_FIFO_SRC)?;
        Ok((raw & 0x80 != 0, raw & 0x40 != 0, raw & 0x20 != 0, raw & 0x1F))
    }

    /// Configure an inertial interrupt generator.
    pub fn set_motion_interrupt(
        &mut self,
        generator: u8,
        events: u8,
        threshold_g: f32,
        duration_ms: f32,
        and_mode: bool,
    ) -> Result<(), I2C::Error> {
        let (cfg_reg, ths_reg, dur_reg) = match generator_regs(generator) {
            Some(regs) => regs,
            None => return Ok(()),
        };
        if events > 0x3F {
            return Ok(());
        }
        self.inner.write_reg(cfg_reg, (if and_mode { 0x80 } else { 0x00 }) | (events & 0x3F))?;
        self.inner.write_reg(ths_reg, threshold_code(self.inner.range_g, threshold_g))?;
        self.inner.write_reg(dur_reg, duration_code(self.inner.rate_hz, duration_ms, 127))
    }

    /// Configure the free-fall detector (all axes low, AND mode).
    pub fn set_free_fall(&mut self, generator: u8, threshold_g: f32, duration_ms: f32) -> Result<(), I2C::Error> {
        let (cfg_reg, ths_reg, dur_reg) = match generator_regs(generator) {
            Some(regs) => regs,
            None => return Ok(()),
        };
        self.inner.write_reg(cfg_reg, 0x95)?;
        self.inner.write_reg(ths_reg, threshold_code(self.inner.range_g, threshold_g))?;
        self.inner.write_reg(dur_reg, duration_code(self.inner.rate_hz, duration_ms, 127))
    }

    /// Configure 6D / 4D orientation detection.
    pub fn set_orientation(&mut self, generator: u8, position: bool, four_d: bool) -> Result<(), I2C::Error> {
        let (cfg_reg, _, _) = match generator_regs(generator) {
            Some(regs) => regs,
            None => return Ok(()),
        };
        let cfg = (if position { 0xC0 } else { 0x40 }) | if four_d { 0x0F } else { 0x3F };
        self.inner.write_reg(cfg_reg, cfg)?;
        let d4d_bit = if generator == 1 { 0x04 } else { 0x01 };
        let ctrl5 = self.inner.read_reg(REG_CTRL_REG5)?;
        let out = if four_d { ctrl5 | d4d_bit } else { ctrl5 & !d4d_bit };
        self.inner.write_reg(REG_CTRL_REG5, out)
    }

    /// Latch a generator's interrupt until its source register is read.
    pub fn set_latch(&mut self, generator: u8, enabled: bool) -> Result<(), I2C::Error> {
        let bit = match generator {
            1 => 0x08,
            2 => 0x02,
            _ => return Ok(()),
        };
        let ctrl5 = self.inner.read_reg(REG_CTRL_REG5)?;
        let out = if enabled { ctrl5 | bit } else { ctrl5 & !bit };
        self.inner.write_reg(REG_CTRL_REG5, out)
    }

    /// Read a generator's source register — clears the interrupt.
    pub fn read_interrupt_source(&mut self, generator: u8) -> Result<u8, I2C::Error> {
        match generator {
            1 => self.inner.read_reg(REG_INT1_SRC),
            2 => self.inner.read_reg(REG_INT2_SRC),
            _ => Ok(0),
        }
    }

    /// Configure single / double click detection.
    pub fn set_click(
        &mut self,
        single: u8,
        double: u8,
        threshold_g: f32,
        time_limit_ms: f32,
        latency_ms: f32,
        window_ms: f32,
    ) -> Result<(), I2C::Error> {
        if single > 7 || double > 7 {
            return Ok(());
        }
        let cfg = ((single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2) |
                   ((double & 0x01) << 1) | ((double & 0x02) << 2) | ((double & 0x04) << 3));
        self.inner.write_reg(REG_CLICK_CFG, cfg)?;
        self.inner.write_reg(REG_CLICK_THS, threshold_code(self.inner.range_g, threshold_g))?;
        self.inner.write_reg(REG_TIME_LIMIT, duration_code(self.inner.rate_hz, time_limit_ms, 127))?;
        self.inner.write_reg(REG_TIME_LATENCY, duration_code(self.inner.rate_hz, latency_ms, 255))?;
        self.inner.write_reg(REG_TIME_WINDOW, duration_code(self.inner.rate_hz, window_ms, 255))
    }

    /// Read `CLICK_SRC_A` — clears the click interrupt.
    pub fn read_click_source(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_CLICK_SRC)
    }

    /// Set the shared INT1 / INT2 polarity.
    pub fn set_interrupt_polarity(&mut self, active_low: bool) -> Result<(), I2C::Error> {
        let ctrl6 = self.inner.read_reg(REG_CTRL_REG6)?;
        let out = if active_low { ctrl6 | 0x02 } else { ctrl6 & !0x02 };
        self.inner.write_reg(REG_CTRL_REG6, out)
    }

    /// Route one interrupt source to an INT pin.
    pub fn enable_interrupt(&mut self, source: u16, pin: u8) -> Result<(), I2C::Error> {
        if source == SOURCE_MAG_DATA_READY {
            return Ok(());
        }
        let (reg, mask, track) = match route_interrupt(source, pin) {
            Some(route) => route,
            None => return Ok(()),
        };
        let value = self.inner.read_reg(reg)?;
        self.inner.write_reg(reg, value | mask)?;
        let bit = 1u16 << source.trailing_zeros();
        if track == 1 { self.enabled1 |= bit; } else { self.enabled2 |= bit; }
        Ok(())
    }

    /// Remove one interrupt source from an INT pin.
    pub fn disable_interrupt(&mut self, source: u16, pin: u8) -> Result<(), I2C::Error> {
        if source == SOURCE_MAG_DATA_READY {
            return Ok(());
        }
        let (reg, mask, track) = match route_interrupt(source, pin) {
            Some(route) => route,
            None => return Ok(()),
        };
        let value = self.inner.read_reg(reg)?;
        self.inner.write_reg(reg, value & !mask)?;
        let bit = 1u16 << source.trailing_zeros();
        if track == 1 { self.enabled1 &= !bit; } else { self.enabled2 &= !bit; }
        Ok(())
    }

    /// Read every enabled source and return the combined status.
    pub fn poll_interrupt(&mut self) -> Result<u16, I2C::Error> {
        let enabled = self.enabled1 | self.enabled2;
        let mut status = 0u16;
        if enabled & 0x01 != 0 && self.inner.read_reg(REG_STATUS)? & 0x08 != 0 {
            status |= SOURCE_DATA_READY;
        }
        if enabled & 0x06 != 0 {
            let fifo = self.inner.read_reg(REG_FIFO_SRC)?;
            if enabled & 0x02 != 0 && fifo & 0x80 != 0 { status |= SOURCE_WATERMARK; }
            if enabled & 0x04 != 0 && fifo & 0x40 != 0 { status |= SOURCE_OVERRUN; }
        }
        if enabled & 0x08 != 0 && self.inner.read_reg(REG_INT1_SRC)? & 0x40 != 0 { status |= SOURCE_IA1; }
        if enabled & 0x10 != 0 && self.inner.read_reg(REG_INT2_SRC)? & 0x40 != 0 { status |= SOURCE_IA2; }
        if enabled & 0x20 != 0 && self.inner.read_reg(REG_CLICK_SRC)? & 0x40 != 0 { status |= SOURCE_CLICK; }
        if self.inner.mag_read_reg(MAG_REG_SR)? & 0x01 != 0 { status |= SOURCE_MAG_DATA_READY; }
        Ok(status)
    }

    /// Set the magnetometer gain / full-scale range in µT.
    pub fn set_mag_range(&mut self, range_ut: u16) -> Result<(), I2C::Error> {
        let code = match mag_code_for_range(range_ut) {
            Some(code) => code,
            None => return Ok(()),
        };
        self.inner.mag_gain_code = code;
        self.inner.mag_write_reg(MAG_REG_CRB, code)
    }

    /// Set the magnetometer output data rate.
    pub fn set_mag_data_rate(&mut self, rate_hz: f32) -> Result<(), I2C::Error> {
        let code = match mag_rate_code(rate_hz) {
            Some(code) => code,
            None => return Ok(()),
        };
        let cra = self.inner.mag_read_reg(MAG_REG_CRA)?;
        self.inner.mag_write_reg(MAG_REG_CRA, (cra & 0xE3) | code)?;
        self.inner.mag_rate_hz = rate_hz;
        Ok(())
    }

    /// Set the magnetometer operating mode (0 continuous, 1 single, 2 sleep).
    pub fn set_mag_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        let code = match mode {
            0 => 0x00,
            1 => 0x01,
            2 => 0x03,
            _ => return Ok(()),
        };
        self.inner.mag_write_reg(MAG_REG_MR, code)?;
        if mode == 1 {
            self.inner.delay.delay_ms(ceil_f32(1000.0 / self.inner.mag_rate_hz));
        }
        Ok(())
    }

    /// Read raw magnetometer counts, reordered to X, Y, Z.
    pub fn read_magnetic_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut raw = [0u8; 6];
        self.inner.mag_read_burst(MAG_REG_OUT_X_H, &mut raw)?;
        Ok((
            decode_mag_axis(&raw[0..2]),
            decode_mag_axis(&raw[4..6]),
            decode_mag_axis(&raw[2..4]),
        ))
    }

    /// Return true when `SR_REG_M.DRDY` is set.
    pub fn mag_data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.mag_read_reg(MAG_REG_SR)? & 0x01 != 0)
    }

    /// Decode `SR_REG_M` as `(drdy, lock)`.
    pub fn read_mag_status(&mut self) -> Result<(bool, bool), I2C::Error> {
        let raw = self.inner.mag_read_reg(MAG_REG_SR)?;
        Ok((raw & 0x01 != 0, raw & 0x02 != 0))
    }

    /// Read the magnetometer identity bytes.
    pub fn read_mag_identity(&mut self) -> Result<[u8; 3], I2C::Error> {
        let mut out = [0u8; 3];
        self.inner.mag_read_burst(MAG_REG_IRA, &mut out)?;
        Ok(out)
    }

    /// Enable or disable the magnetometer temperature sensor.
    pub fn enable_temperature(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let cra = self.inner.mag_read_reg(MAG_REG_CRA)?;
        let out = if enabled { cra | 0x80 } else { cra & !0x80 };
        self.inner.mag_write_reg(MAG_REG_CRA, out)
    }

    /// Read the relative magnetometer temperature in °C (8 LSB/°C).
    pub fn read_temperature_delta(&mut self) -> Result<f32, I2C::Error> {
        if self.inner.mag_read_reg(MAG_REG_CRA)? & 0x80 == 0 {
            self.enable_temperature(true)?;
            self.inner.delay.delay_ms(ceil_f32(1000.0 / self.inner.mag_rate_hz));
        }
        let high = self.inner.mag_read_reg(MAG_REG_TEMP_H)?;
        let low = self.inner.mag_read_reg(MAG_REG_TEMP_L)?;
        let raw = to_signed(((high as u32) << 8) | low as u32, 16);
        Ok((raw >> 4) as f32 / 8.0)
    }

    /// Compute the compass heading in degrees (0-360).
    pub fn heading(&mut self, tilt_compensate: bool) -> Result<f32, I2C::Error> {
        let (mx, my, mz) = self.read_magnetic()?;
        let angle = if tilt_compensate {
            let (ax, ay, az) = self.read_acceleration()?;
            let roll = libm::atan2f(ay, az);
            let pitch = libm::atan2f(-ax, ay * libm::sinf(roll) + az * libm::cosf(roll));
            let xh = mx * libm::cosf(pitch) + mz * libm::sinf(pitch);
            let yh = mx * libm::sinf(roll) * libm::sinf(pitch) + my * libm::cosf(roll)
                   - mz * libm::sinf(roll) * libm::cosf(pitch);
            libm::atan2f(yh, xh)
        } else {
            libm::atan2f(my, mx)
        };
        let mut degrees = angle * 180.0 / core::f32::consts::PI;
        if degrees < 0.0 {
            degrees += 360.0;
        }
        Ok(degrees)
    }

    /// Reboot the accelerometer memory and re-run the Initialization Sequence.
    pub fn reboot(&mut self) -> Result<(), I2C::Error> {
        let ctrl5 = self.inner.read_reg(REG_CTRL_REG5)?;
        self.inner.write_reg(REG_CTRL_REG5, ctrl5 | 0x80)?;
        self.inner.delay.delay_ms(5);
        self.inner.range_g = DEFAULT_RANGE_G;
        self.inner.mode = DEFAULT_MODE;
        self.inner.rate_hz = DEFAULT_RATE_HZ;
        self.inner.mag_gain_code = DEFAULT_MAG_GAIN;
        self.inner.mag_rate_hz = DEFAULT_MAG_RATE_HZ;
        self.enabled1 = 0;
        self.enabled2 = 0;
        self.inner.run_init_sequence()
    }

}

fn threshold_code(range_g: u8, threshold_g: f32) -> u8 {
    let lsb = range_g as f32 / 128.0;
    libm::roundf(threshold_g / lsb).clamp(0.0, 127.0) as u8
}

fn duration_code(rate_hz: u16, duration_ms: f32, maximum: u8) -> u8 {
    if rate_hz == 0 || duration_ms <= 0.0 {
        return 0;
    }
    libm::roundf(duration_ms * rate_hz as f32 / 1000.0)
        .clamp(0.0, maximum as f32) as u8
}

fn sensitivity_mg(mode: u8, range_g: u8) -> f32 {
    let index = match range_g {
        4 => 1,
        8 => 2,
        16 => 3,
        _ => 0,
    };
    let table = match mode {
        MODE_LOW_POWER => [16.0, 32.0, 64.0, 192.0],
        MODE_NORMAL => [4.0, 8.0, 16.0, 48.0],
        _ => [1.0, 2.0, 4.0, 12.0],
    };
    table[index]
}

fn resolution_shift(mode: u8) -> u32 {
    match mode {
        MODE_LOW_POWER => 8,
        MODE_NORMAL => 6,
        _ => 4,
    }
}

fn decode_axis(bytes: &[u8], shift: u32) -> i16 {
    let raw = (bytes[1] as u32) << 8 | bytes[0] as u32;
    (to_signed(raw, 16) >> shift) as i16
}

fn decode_mag_axis(bytes: &[u8]) -> i16 {
    let raw = ((bytes[0] as u32) << 8) | bytes[1] as u32;
    to_signed(raw, 16) as i16
}

fn mag_gains(code: u8) -> (f32, f32) {
    match code {
        0x40 => (855.0, 760.0),
        0x60 => (670.0, 600.0),
        0x80 => (450.0, 400.0),
        0xA0 => (400.0, 355.0),
        0xC0 => (330.0, 295.0),
        0xE0 => (230.0, 205.0),
        _ => (1100.0, 980.0),
    }
}

fn mag_code_for_range(range_ut: u16) -> Option<u8> {
    match range_ut {
        130 => Some(0x20),
        190 => Some(0x40),
        250 => Some(0x60),
        400 => Some(0x80),
        470 => Some(0xA0),
        560 => Some(0xC0),
        810 => Some(0xE0),
        _ => None,
    }
}

fn mag_rate_code(rate_hz: f32) -> Option<u8> {
    if (rate_hz - 0.75).abs() < 1e-3 { Some(0x00) }
    else if (rate_hz - 1.5).abs() < 1e-3 { Some(0x04) }
    else if (rate_hz - 3.0).abs() < 1e-3 { Some(0x08) }
    else if (rate_hz - 7.5).abs() < 1e-3 { Some(0x0C) }
    else if (rate_hz - 15.0).abs() < 1e-3 { Some(0x10) }
    else if (rate_hz - 30.0).abs() < 1e-3 { Some(0x14) }
    else if (rate_hz - 75.0).abs() < 1e-3 { Some(0x18) }
    else if (rate_hz - 220.0).abs() < 1e-3 { Some(0x1C) }
    else { None }
}

fn rate_code(rate_hz: u16) -> Option<u8> {
    match rate_hz {
        0 => Some(0x00),
        1 => Some(0x10),
        10 => Some(0x20),
        25 => Some(0x30),
        50 => Some(0x40),
        100 => Some(0x50),
        200 => Some(0x60),
        400 => Some(0x70),
        1620 => Some(0x80),
        1344 | 5376 => Some(0x90),
        _ => None,
    }
}

fn is_low_power_only(rate_hz: u16) -> bool {
    rate_hz == 1620 || rate_hz == 5376
}

fn is_normal_hr_only(rate_hz: u16) -> bool {
    rate_hz == 1344
}

fn generator_regs(generator: u8) -> Option<(u8, u8, u8)> {
    match generator {
        1 => Some((REG_INT1_CFG, REG_INT1_THS, REG_INT1_DUR)),
        2 => Some((REG_INT2_CFG, REG_INT2_THS, REG_INT2_DUR)),
        _ => None,
    }
}

fn route_interrupt(source: u16, pin: u8) -> Option<(u8, u8, u8)> {
    if pin != 1 && pin != 2 {
        return None;
    }
    match source {
        SOURCE_DATA_READY => Some((REG_CTRL_REG3, 0x10, 1)),
        SOURCE_WATERMARK => Some((REG_CTRL_REG3, 0x04, 1)),
        SOURCE_OVERRUN => Some((REG_CTRL_REG3, 0x02, 1)),
        SOURCE_IA1 => Some((if pin == 2 { REG_CTRL_REG6 } else { REG_CTRL_REG3 }, 0x40, pin)),
        SOURCE_IA2 => Some((if pin == 2 { REG_CTRL_REG6 } else { REG_CTRL_REG3 }, 0x20, pin)),
        SOURCE_CLICK => Some((if pin == 2 { REG_CTRL_REG6 } else { REG_CTRL_REG3 }, 0x80, pin)),
        SOURCE_BOOT => Some((REG_CTRL_REG6, 0x10, 2)),
        _ => None,
    }
}

fn ceil_f32(x: f32) -> u32 {
    if x <= 0.0 {
        return 0;
    }
    let mut n = x as u32;
    if (n as f32) < x {
        n += 1;
    }
    n
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ACCEL: u8 = 0x19;
    const MAG: u8 = 0x1E;

    fn init_transactions() -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(MAG, vec![MAG_REG_IRA], MAG_ID.to_vec()),
            I2cTransaction::write(ACCEL, vec![REG_CTRL_REG2, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL_REG3, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL_REG5, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL_REG6, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_FIFO_CTRL, 0x00]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL_REG4, CTRL_REG4_DEFAULT]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL_REG1, CTRL_REG1_DEFAULT]),
            I2cTransaction::write(MAG, vec![MAG_REG_CRA, 0x10]),
            I2cTransaction::write(MAG, vec![MAG_REG_CRB, DEFAULT_MAG_GAIN]),
            I2cTransaction::write(MAG, vec![MAG_REG_MR, 0x00]),
        ]
    }

    fn new_full(extra: &[I2cTransaction]) -> Lsm303Full<I2cMock, NoopDelay> {
        let mut transactions = init_transactions();
        transactions.extend_from_slice(extra);
        let i2c = I2cMock::new(&transactions);
        Lsm303Full::new(Connection::new(i2c), ACCEL, MAG, NoopDelay::new()).expect("new")
    }

    #[test]
    fn init_and_read() {
        let mut chip = new_full(&[
            I2cTransaction::write_read(ACCEL, vec![REG_OUT_X_L | 0x80],
                                       vec![0x10, 0x00, 0x20, 0x00, 0x30, 0x00]),
            I2cTransaction::write_read(MAG, vec![MAG_REG_OUT_X_H],
                                       vec![0x04, 0x4C, 0x03, 0xD4, 0xFB, 0xB4]),
        ]);
        let (x, y, z) = chip.read_acceleration().unwrap();
        assert!((x - 0.001).abs() < 1e-6);
        assert!((y - 0.002).abs() < 1e-6);
        assert!((z - 0.003).abs() < 1e-6);
        let (mx, my, mz) = chip.read_magnetic().unwrap();
        assert!((mx - 100.0).abs() < 1e-3);
        assert!((my + 100.0).abs() < 1e-3);
        assert!((mz - 100.0).abs() < 1e-3);
        chip.inner.conn.release().done();
    }

    #[test]
    fn mag_identity() {
        let mut chip = new_full(&[I2cTransaction::write_read(MAG, vec![MAG_REG_IRA], MAG_ID.to_vec())]);
        assert_eq!(chip.read_mag_identity().unwrap(), MAG_ID);
        chip.inner.conn.release().done();
    }

    #[test]
    fn range_and_mag_gain() {
        let mut chip = new_full(&[
            I2cTransaction::write_read(ACCEL, vec![REG_CTRL_REG4], vec![0x88]),
            I2cTransaction::write(ACCEL, vec![REG_CTRL_REG4, 0x98]),
            I2cTransaction::write(MAG, vec![MAG_REG_CRB, 0xA0]),
        ]);
        chip.set_range(4).unwrap();
        assert_eq!(chip.inner.range_g, 4);
        chip.set_mag_range(470).unwrap();
        assert_eq!(chip.inner.mag_gain_code, 0xA0);
        chip.inner.conn.release().done();
    }
}
