//! BMA150 — 3-axis MEMS accelerometer (Bosch Sensortec).
//!
//! Triaxial low-g accelerometer with 10-bit digital output and ±2/±4/±8 *g*
//! selectable full-scale range. Communicates over I²C at the fixed address
//! 0x38 (the chip also supports 3-/4-wire SPI; out of scope here).
//!
//! ## Default configuration
//!
//! - Range ±2 *g* (256 LSB/g)
//! - Bandwidth 100 Hz
//! - Calibration bits 7:5 of `RANGE_BW` (0x14) preserved
//! - `shadow_dis` = 0 (LSB-then-MSB ordering enforced)
//!
//! ## Constants
//!
//! Interrupts: [`SOURCE_LOW_G`], [`SOURCE_HIGH_G`], [`SOURCE_ANY_MOTION`],
//! [`SOURCE_ALERT`], [`SOURCE_NEW_DATA`]
//! Status register latched bits: [`STATUS_LG_LATCHED`], [`STATUS_HG_LATCHED`]

use embedded_hal::i2c::I2c;

use crate::connection::register::{read_register, write_register};

const REG_CHIP_ID: u8          = 0x00;
const REG_VERSION: u8          = 0x01;
const REG_ACC_X_LSB: u8        = 0x02;
const REG_ACC_X_MSB: u8        = 0x03;
const REG_ACC_Y_LSB: u8        = 0x04;
const REG_ACC_Y_MSB: u8        = 0x05;
const REG_ACC_Z_LSB: u8        = 0x06;
const REG_ACC_Z_MSB: u8        = 0x07;
const REG_TEMP: u8             = 0x08;
const REG_STATUS: u8           = 0x09;
const REG_CTRL: u8             = 0x0A;
const REG_INT_CTRL: u8         = 0x0B;
const REG_LG_THRES: u8         = 0x0C;
const REG_LG_DUR: u8           = 0x0D;
const REG_HG_THRES: u8         = 0x0E;
const REG_HG_DUR: u8           = 0x0F;
const REG_ANY_MOTION_THRES: u8 = 0x10;
const REG_HYST_DUR: u8         = 0x11;
const REG_CUSTOMER_1: u8       = 0x12;
const REG_CUSTOMER_2: u8       = 0x13;
const REG_RANGE_BW: u8         = 0x14;
const REG_CONFIG: u8           = 0x15;

const CHIP_ID_VALUE: u8 = 0x02;
const CHIP_ID_MASK: u8  = 0x07;

// RANGE_BW bits 4:3.
const RANGE_2G: u8 = 0x00;
const RANGE_4G: u8 = 0x08;
const RANGE_8G: u8 = 0x10;

// RANGE_BW bits 2:0.
const BW_25: u8    = 0x00;
const BW_50: u8    = 0x01;
const BW_100: u8   = 0x02;
const BW_190: u8   = 0x03;
const BW_375: u8   = 0x04;
const BW_750: u8   = 0x05;
const BW_1500: u8  = 0x06;

// Sensitivity (LSB/g).
const SCALE_2G: f32 = 256.0;
const SCALE_4G: f32 = 128.0;
const SCALE_8G: f32 = 64.0;

/// Interrupt source: low-g (free-fall).
pub const SOURCE_LOW_G: u8     = 0x01;
/// Interrupt source: high-g (shock).
pub const SOURCE_HIGH_G: u8    = 0x02;
/// Interrupt source: any-motion.
pub const SOURCE_ANY_MOTION: u8 = 0x04;
/// Interrupt source: alert (preloads low/high-g).
pub const SOURCE_ALERT: u8     = 0x08;
/// Interrupt source: new data (exclusive with the four above).
pub const SOURCE_NEW_DATA: u8  = 0x10;

/// STATUS register bit: low-g latched.
pub const STATUS_LG_LATCHED: u8 = 0x08;
/// STATUS register bit: high-g latched.
pub const STATUS_HG_LATCHED: u8 = 0x04;

/// BMA150 minimal driver — 3-axis acceleration in *g*.
///
/// Performs CHIP_ID verification and writes sensible defaults at construction.
pub struct Bma150Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    range_g: u8,
}

impl<I2C: I2c> Bma150Minimal<I2C> {
    /// Create a new `Bma150Minimal` and verify the device identity.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit I²C address (typically `0x38`).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut buf = [0u8; 1];
        read_register(&mut i2c, addr, REG_CHIP_ID as u32, 1, &mut buf)?;
        if (buf[0] & CHIP_ID_MASK) != CHIP_ID_VALUE {
            panic!("BMA150 CHIP_ID: expected 0x{:02X}, got 0x{:02X}",
                   CHIP_ID_VALUE, buf[0] & CHIP_ID_MASK);
        }
        // Set range ±2 g, bandwidth 100 Hz; preserve calibration bits 7:5.
        read_register(&mut i2c, addr, REG_RANGE_BW as u32, 1, &mut buf)?;
        let out = (buf[0] & 0xE0) | RANGE_2G | BW_100;
        write_reg(&mut i2c, addr, REG_RANGE_BW, out)?;
        // Allow filtered data to replace stale registers (1/(2*100Hz) = 5 ms).
        // (delay_ms lives outside the no_std driver; the user can call
        // read() which the application can time after construction.)
        Ok(Self { i2c, addr, range_g: 2 })
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns (x, y, z) in *g*.
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut raw = [0u8; 6];
        read_register(&mut self.i2c, self.addr, REG_ACC_X_LSB as u32, 1, &mut raw)?;
        let rx = decode_axis(&raw[0..2]);
        let ry = decode_axis(&raw[2..4]);
        let rz = decode_axis(&raw[4..6]);
        let scale = match self.range_g {
            4 => SCALE_4G,
            8 => SCALE_8G,
            _ => SCALE_2G,
        };
        Ok((rx as f32 / scale, ry as f32 / scale, rz as f32 / scale))
    }
}

fn decode_axis(p: &[u8]) -> i16 {
    let v = ((p[1] as u16) << 2) | ((p[0] >> 6) as u16);
    if v >= 512 { (v as i16) - 1024 } else { v as i16 }
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    write_register(i2c, addr, reg as u32, 1, &[value])
}

fn read_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    read_register(i2c, addr, reg as u32, 1, &mut buf)?;
    Ok(buf[0])
}

fn nearest_bandwidth(bw_hz: u16) -> u8 {
    let table: [(u16, u8); 7] = [
        (25, BW_25), (50, BW_50), (100, BW_100), (190, BW_190),
        (375, BW_375), (750, BW_750), (1500, BW_1500),
    ];
    let mut best = table[0];
    let mut best_diff = abs_diff(bw_hz, table[0].0);
    for &entry in &table[1..] {
        let d = abs_diff(bw_hz, entry.0);
        if d < best_diff {
            best = entry;
            best_diff = d;
        }
    }
    best.1
}

fn abs_diff(a: u16, b: u16) -> u16 {
    if a >= b { a - b } else { b - a }
}

/// BMA150 full driver — extends minimal with configuration, interrupt
/// sources, low-g / high-g / any-motion / alert logic, sleep, soft reset,
/// and self-test.
///
/// Re-exports [`Bma150Minimal::read`] as a one-line delegate (the Rust
/// equivalent of "Full never duplicates Minimal"), then adds Full-only
/// methods below.
pub struct Bma150Full<I2C> {
    inner: Bma150Minimal<I2C>,
    enabled_sources: u8,
    sleeping: bool,
}

impl<I2C: I2c> Bma150Full<I2C> {
    /// Create a new `Bma150Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (typically `0x38`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Bma150Minimal::new(i2c, addr)?;
        Ok(Self { inner, enabled_sources: 0, sleeping: false })
    }

    /// Read 3-axis linear acceleration in *g*.
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read()
    }

    /// Set the measurement range to ±2/±4/±8 *g*.
    pub fn set_range(&mut self, range_g: u8) -> Result<(), I2C::Error> {
        let range_mask = match range_g {
            4 => RANGE_4G,
            8 => RANGE_8G,
            _ => RANGE_2G,
        };
        self.inner.range_g = if range_g == 4 { 4 } else if range_g == 8 { 8 } else { 2 };
        let rb = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_RANGE_BW)?;
        let out = (rb & 0xE0) | range_mask | (rb & 0x07);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RANGE_BW, out)
    }

    /// Set the digital low-pass bandwidth to the nearest supported value.
    pub fn set_bandwidth(&mut self, bandwidth_hz: u16) -> Result<(), I2C::Error> {
        let bw_code = nearest_bandwidth(bandwidth_hz);
        let rb = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_RANGE_BW)?;
        let out = (rb & 0xF8) | bw_code;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RANGE_BW, out)
    }

    /// Read raw 10-bit two's-complement acceleration counts.
    pub fn read_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut raw = [0u8; 6];
        read_register(&mut self.inner.i2c, self.inner.addr, REG_ACC_X_LSB as u32, 1, &mut raw)?;
        Ok((decode_axis(&raw[0..2]), decode_axis(&raw[2..4]), decode_axis(&raw[4..6])))
    }

    /// Read on-chip temperature in °C.
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        let raw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_TEMP)?;
        Ok((raw as f32) * 0.5 - 30.0)
    }

    /// Return true if all three new_data_X/Y/Z bits are set.
    pub fn new_data_available(&mut self) -> Result<bool, I2C::Error> {
        let x = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ACC_X_LSB)?;
        let y = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ACC_Y_LSB)?;
        let z = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ACC_Z_LSB)?;
        Ok((x & 0x01) != 0 && (y & 0x01) != 0 && (z & 0x01) != 0)
    }

    /// Enable or disable MSB-only reads (`shadow_dis`).
    pub fn set_shadow(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let cfg = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        let out = if enabled { cfg | 0x08 } else { cfg & !0x08 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, out)
    }

    /// Configure the low-g (free-fall) interrupt and enable it.
    pub fn set_low_g(&mut self, threshold_g: f32, duration_ms: u16,
                     hysteresis_g: f32, counter: u8) -> Result<(), I2C::Error> {
        self.write_threshold(REG_LG_THRES, threshold_g)?;
        let dur = if duration_ms > 255 { 255 } else { duration_ms as u8 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_LG_DUR, dur)?;
        self.write_hyst(b'h', hysteresis_g)?;
        self.write_int_counter(b'l', counter)?;
        self.enable_source(SOURCE_LOW_G)
    }

    /// Configure the high-g (shock) interrupt and enable it.
    pub fn set_high_g(&mut self, threshold_g: f32, duration_ms: u16,
                      hysteresis_g: f32, counter: u8) -> Result<(), I2C::Error> {
        self.write_threshold(REG_HG_THRES, threshold_g)?;
        let dur = if duration_ms > 255 { 255 } else { duration_ms as u8 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_HG_DUR, dur)?;
        self.write_hyst(b'g', hysteresis_g)?;
        self.write_int_counter(b'g', counter)?;
        self.enable_source(SOURCE_HIGH_G)
    }

    /// Configure the any-motion interrupt and enable it.
    pub fn set_any_motion(&mut self, threshold_g: f32, samples: u8) -> Result<(), I2C::Error> {
        let scale = match self.inner.range_g {
            4 => SCALE_4G / 256.0,
            8 => SCALE_8G / 256.0,
            _ => SCALE_2G / 256.0,
        };
        let code = libm::roundf(threshold_g / (0.0156 * scale)) as i32;
        let code = if code < 0 { 0 } else if code > 255 { 255 } else { code } as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ANY_MOTION_THRES, code)?;

        let dur_code: u8 = match samples {
            3 => 0x40,
            5 => 0x80,
            7 => 0xC0,
            _ => 0x00,
        };
        let hd = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_HYST_DUR)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_HYST_DUR, (hd & 0x3F) | dur_code)?;

        let cfg = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, cfg | 0x40)?;

        self.enable_source(SOURCE_ANY_MOTION)
    }

    /// Toggle alert mode (mutually exclusive with any-motion).
    pub fn set_alert(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        if enabled {
            self.enabled_sources &= !SOURCE_ANY_MOTION;
            let cfg = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, cfg | 0x40)?;
            self.enable_source(SOURCE_ALERT)
        } else {
            self.disable_source(SOURCE_ALERT)
        }
    }

    /// Enable latched interrupts (cleared by [`Self::clear_interrupt`]).
    pub fn set_latch(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let cfg = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        let out = if enabled { cfg | 0x10 } else { cfg & !0x10 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, out)
    }

    /// Clear latched interrupts.
    pub fn clear_interrupt(&mut self) -> Result<(), I2C::Error> {
        if self.sleeping { return Ok(()); }
        let ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL, ctrl | 0x40)
    }

    /// Enable one interrupt source. Disables mutually exclusive partners.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        if source == SOURCE_NEW_DATA {
            self.enabled_sources &= 0x0F;
        } else {
            self.enabled_sources &= !SOURCE_NEW_DATA;
            if source == SOURCE_ANY_MOTION {
                self.enabled_sources &= !SOURCE_ALERT;
            } else if source == SOURCE_ALERT {
                self.enabled_sources &= !SOURCE_ANY_MOTION;
            }
        }
        self.enable_source(source)
    }

    /// Disable one interrupt source.
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        self.disable_source(source)
    }

    /// Read STATUS without clearing latched bits.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)
    }

    /// Configure self-wake-up mode.
    pub fn set_wake_up(&mut self, enabled: bool, pause_ms: u16) -> Result<(), I2C::Error> {
        let pause_code: u8 = match pause_ms {
            80 => 0x02,
            320 => 0x04,
            2560 => 0x06,
            _ => 0x00,
        };
        let cfg = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        let out = (cfg & 0xF8) | pause_code | if enabled { 0x01 } else { 0x00 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, out)
    }

    /// Enter sleep mode.
    pub fn sleep(&mut self) -> Result<(), I2C::Error> {
        if self.sleeping { return Ok(()); }
        let ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL, ctrl | 0x01)?;
        self.sleeping = true;
        Ok(())
    }

    /// Leave sleep mode.
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        if !self.sleeping { return Ok(()); }
        let ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL, ctrl & !0x01)?;
        self.sleeping = false;
        Ok(())
    }

    /// Issue a power-on-equivalent reset; range/bandwidth restored.
    pub fn soft_reset(&mut self) -> Result<(), I2C::Error> {
        let ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL, ctrl | 0x02)?;
        let range_mask = match self.inner.range_g {
            4 => RANGE_4G,
            8 => RANGE_8G,
            _ => RANGE_2G,
        };
        let rb = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_RANGE_BW)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RANGE_BW, (rb & 0xE0) | range_mask | BW_100)?;
        self.sleeping = false;
        Ok(())
    }

    /// Run the electrostatic self-test, return true on pass.
    pub fn self_test(&mut self) -> Result<bool, I2C::Error> {
        let ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL, ctrl | 0x04)?;
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL, ctrl)?;
        Ok((status & 0x80) != 0)
    }

    /// Read the STATUS register.
    pub fn read_status(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)
    }

    /// Read VERSION split into (al_version, ml_version).
    pub fn read_version(&mut self) -> Result<(u8, u8), I2C::Error> {
        let raw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_VERSION)?;
        Ok(((raw >> 4) & 0x0F, raw & 0x0F))
    }

    /// Read one of the two CUSTOMER scratch bytes.
    pub fn read_customer(&mut self, index: u8) -> Result<u8, I2C::Error> {
        let reg = if index == 0 { REG_CUSTOMER_1 } else { REG_CUSTOMER_2 };
        read_reg8(&mut self.inner.i2c, self.inner.addr, reg)
    }

    /// Write one of the two CUSTOMER scratch bytes.
    pub fn write_customer(&mut self, index: u8, value: u8) -> Result<(), I2C::Error> {
        let reg = if index == 0 { REG_CUSTOMER_1 } else { REG_CUSTOMER_2 };
        write_reg(&mut self.inner.i2c, self.inner.addr, reg, value)
    }

    fn write_threshold(&mut self, reg: u8, threshold_g: f32) -> Result<(), I2C::Error> {
        let code = libm::roundf(threshold_g * 255.0 / self.inner.range_g as f32) as i32;
        let code = if code < 0 { 0 } else if code > 255 { 255 } else { code } as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, reg, code)
    }

    fn write_hyst(&mut self, kind: u8, hysteresis_g: f32) -> Result<(), I2C::Error> {
        if hysteresis_g < 0.0 { return Ok(()); }
        let code = libm::roundf(hysteresis_g * 255.0 / self.inner.range_g as f32 / 32.0) as i32;
        let code = if code < 0 { 0 } else if code > 7 { 7 } else { code } as u8;
        let hd = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_HYST_DUR)?;
        let out = if kind == b'h' {  // 'lg' (low-g)
            (hd & 0xF8) | code
        } else {  // 'hg' (high-g)
            (hd & 0xC7) | (code << 3)
        };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_HYST_DUR, out)
    }

    fn write_int_counter(&mut self, kind: u8, counter: u8) -> Result<(), I2C::Error> {
        if counter > 3 { return Ok(()); }
        let code = (counter & 0x03) << 2;  // LG bits 3:2; HG shifts 2 more (bits 5:4)
        let ic = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_CTRL)?;
        let out = if kind == b'l' {  // counter_LG: bits 3:2
            (ic & 0xF3) | code
        } else {  // counter_HG: bits 5:4
            (ic & 0xCF) | (code << 2)
        };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_CTRL, out)
    }

    fn enable_source(&mut self, source: u8) -> Result<(), I2C::Error> {
        if self.sleeping { return Ok(()); }
        self.enabled_sources |= source;
        if source == SOURCE_NEW_DATA {
            let cfg = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
            return write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, cfg | 0x20);
        }
        let ic = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_CTRL)?;
        let mut out = ic;
        if source == SOURCE_LOW_G      { out |= 0x01; }
        if source == SOURCE_HIGH_G     { out |= 0x02; }
        if source == SOURCE_ANY_MOTION { out |= 0x40; }
        if source == SOURCE_ALERT     { out |= 0x80; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_CTRL, out)
    }

    fn disable_source(&mut self, source: u8) -> Result<(), I2C::Error> {
        self.enabled_sources &= !source;
        if source == SOURCE_NEW_DATA {
            let cfg = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
            return write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, cfg & !0x20);
        }
        let ic = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_CTRL)?;
        let mut out = ic;
        if source == SOURCE_LOW_G      { out &= !0x01; }
        if source == SOURCE_HIGH_G     { out &= !0x02; }
        if source == SOURCE_ANY_MOTION { out &= !0x40; }
        if source == SOURCE_ALERT     { out &= !0x80; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_CTRL, out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x38;

    fn new_accel(extra: &[I2cTransaction]) -> Bma150Full<I2cMock> {
        let mut init = vec![
            I2cTransaction::write_read(ADDR, vec![REG_CHIP_ID], vec![CHIP_ID_VALUE]),
            I2cTransaction::write_read(ADDR, vec![REG_RANGE_BW], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_RANGE_BW, 0x02]),
        ];
        init.extend_from_slice(extra);
        let i2c = I2cMock::new(&init);
        Bma150Full::new(i2c, ADDR).expect("new")
    }

    #[test]
    fn construction_and_read() {
        let mut accel = new_accel(&[
            I2cTransaction::write_read(ADDR, vec![REG_ACC_X_LSB], vec![0x00, 0x80, 0x00, 0x00, 0x00, 0x40]),
            I2cTransaction::write_read(ADDR, vec![REG_ACC_X_LSB], vec![0x00, 0x80, 0x00, 0x00, 0x00, 0x40]),
        ]);
        let (x, y, z) = accel.read().unwrap();
        assert!((x - (-2.0)).abs() < 1e-6);
        assert!((y - 0.0).abs() < 1e-6);
        assert!((z - 1.0).abs() < 1e-6);
        let (rx, ry, rz) = accel.read_raw().unwrap();
        assert_eq!(rx, -512);
        assert_eq!(ry, 0);
        assert_eq!(rz, 256);
        accel.inner.i2c.done();
    }

    #[test]
    fn range_and_bandwidth() {
        let mut accel = new_accel(&[
            // set_range(4): read 0x00, write (0x00 & 0xE0) | 0x08 | (0x00 & 0x07) = 0x08.
            I2cTransaction::write_read(ADDR, vec![REG_RANGE_BW], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_RANGE_BW, 0x08]),
            // set_bandwidth(190): read 0x08, write (0x08 & 0xF8) | 0x03 = 0x0B.
            I2cTransaction::write_read(ADDR, vec![REG_RANGE_BW], vec![0x08]),
            I2cTransaction::write(ADDR, vec![REG_RANGE_BW, 0x0B]),
        ]);
        accel.set_range(4).unwrap();
        accel.set_bandwidth(190).unwrap();
        accel.inner.i2c.done();
    }

    #[test]
    fn low_g_and_high_g() {
        let mut accel = new_accel(&[
            // set_low_g(0.4, 40): with range=2 -> round(0.4*255/2) = 51.
            I2cTransaction::write(ADDR, vec![REG_LG_THRES, 51]),
            I2cTransaction::write(ADDR, vec![REG_LG_DUR, 40]),
            I2cTransaction::write_read(ADDR, vec![REG_HYST_DUR], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_HYST_DUR, 0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_CTRL], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_INT_CTRL, 0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_CTRL], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_INT_CTRL, 0x01]),
            // set_high_g(4.0, 2): with range=2 -> round(4.0*255/2) = 510 -> 255.
            I2cTransaction::write(ADDR, vec![REG_HG_THRES, 255]),
            I2cTransaction::write(ADDR, vec![REG_HG_DUR, 2]),
            I2cTransaction::write_read(ADDR, vec![REG_HYST_DUR], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_HYST_DUR, 0x00]),
            // Low-g is already enabled (0x01); the counter write and enable preserve it.
            I2cTransaction::write_read(ADDR, vec![REG_INT_CTRL], vec![0x01]),
            I2cTransaction::write(ADDR, vec![REG_INT_CTRL, 0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_CTRL], vec![0x01]),
            I2cTransaction::write(ADDR, vec![REG_INT_CTRL, 0x03]),
        ]);
        accel.set_low_g(0.4, 40, 0.0, 0).unwrap();
        accel.set_high_g(4.0, 2, 0.0, 0).unwrap();
        accel.inner.i2c.done();
    }

    #[test]
    fn debounce_counter_bits() {
        // counter_LG is INT_CTRL bits 3:2, counter_HG is bits 5:4.
        let mut accel = new_accel(&[
            // set_low_g(0.4, 40, 0.0, 2): counter write 0x08, then enable_LG -> 0x09.
            I2cTransaction::write(ADDR, vec![REG_LG_THRES, 51]),
            I2cTransaction::write(ADDR, vec![REG_LG_DUR, 40]),
            I2cTransaction::write_read(ADDR, vec![REG_HYST_DUR], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_HYST_DUR, 0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_CTRL], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_INT_CTRL, 0x08]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_CTRL], vec![0x08]),
            I2cTransaction::write(ADDR, vec![REG_INT_CTRL, 0x09]),
            // set_high_g(2.0, 2, 0.0, 2): counter write keeps 0x09 and adds 0x20, then enable_HG.
            I2cTransaction::write(ADDR, vec![REG_HG_THRES, 255]),
            I2cTransaction::write(ADDR, vec![REG_HG_DUR, 2]),
            I2cTransaction::write_read(ADDR, vec![REG_HYST_DUR], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_HYST_DUR, 0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_CTRL], vec![0x09]),
            I2cTransaction::write(ADDR, vec![REG_INT_CTRL, 0x29]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_CTRL], vec![0x29]),
            I2cTransaction::write(ADDR, vec![REG_INT_CTRL, 0x2B]),
        ]);
        accel.set_low_g(0.4, 40, 0.0, 2).unwrap();
        accel.set_high_g(2.0, 2, 0.0, 2).unwrap();
        accel.inner.i2c.done();
    }

    #[test]
    fn version_and_temperature() {
        let mut accel = new_accel(&[
            I2cTransaction::write_read(ADDR, vec![REG_VERSION], vec![0xAB]),
            I2cTransaction::write_read(ADDR, vec![REG_TEMP], vec![0x40]),
        ]);
        let (al, ml) = accel.read_version().unwrap();
        assert_eq!(al, 0x0A);
        assert_eq!(ml, 0x0B);
        let temp = accel.read_temperature().unwrap();
        assert!((temp - 2.0).abs() < 1e-6);
        accel.inner.i2c.done();
    }
}
