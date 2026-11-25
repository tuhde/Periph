//! BMA180 — 3-axis MEMS accelerometer (Bosch Sensortec).
//!
//! Triaxial low-g accelerometer with 14-bit digital output and seven selectable
//! full-scale ranges (±1 to ±16 *g*). Communicates over I²C at address
//! 0x40 (SDO = GND) or 0x41 (SDO = VDDIO). The chip also supports 4-wire
//! SPI; out of scope here.
//!
//! ## Default configuration
//!
//! - Range ±2 *g* (4096 LSB/g)
//! - Bandwidth 150 Hz low-pass
//! - mode_config = 00 (low-noise, factory-calibrated)
//! - 14-bit readout, shadow_dis = 0
//! - All interrupt enables left untouched
//! - Calibration bits preserved everywhere
//!
//! ## Constants
//!
//! Interrupts: [`SOURCE_LOW_G`], [`SOURCE_HIGH_G`], [`SOURCE_SLOPE`],
//! [`SOURCE_ALERT`], [`SOURCE_TAP`], [`SOURCE_NEW_DATA`]
//! Status register latched bits: [`STATUS_HIGH_G`], [`STATUS_LOW_G`],
//! [`STATUS_SLOPE`], [`STATUS_TAP`]

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
const REG_STATUS_REG1: u8      = 0x09;
const REG_STATUS_REG2: u8      = 0x0A;
const REG_STATUS_REG3: u8      = 0x0B;
const REG_STATUS_REG4: u8      = 0x0C;
const REG_CTRL_REG0: u8        = 0x0D;
const REG_CTRL_REG1: u8        = 0x0E;
const REG_RESET: u8            = 0x10;
const REG_BW_TCS: u8           = 0x20;
const REG_CTRL_REG3: u8        = 0x21;
const REG_CTRL_REG4: u8        = 0x22;
const REG_HY: u8               = 0x23;
const REG_SLOPE_TAPSENS: u8    = 0x24;
const REG_HIGH_LOW_INFO: u8    = 0x25;
const REG_LOW_DUR: u8          = 0x26;
const REG_HIGH_DUR: u8         = 0x27;
const REG_TAPSENS_TH: u8       = 0x28;
const REG_LOW_TH: u8           = 0x29;
const REG_HIGH_TH: u8          = 0x2A;
const REG_SLOPE_TH: u8         = 0x2B;
const REG_CD1: u8              = 0x2C;
const REG_CD2: u8              = 0x2D;
const REG_TCO_X: u8            = 0x2E;
const REG_TCO_Y: u8            = 0x2F;
const REG_TCO_Z: u8            = 0x30;
const REG_GAIN_T: u8           = 0x31;
const REG_GAIN_Y: u8           = 0x33;
const REG_GAIN_Z: u8           = 0x34;
const REG_OFFSET_LSB1: u8      = 0x35;
const REG_OFFSET_T: u8         = 0x37;

const CHIP_ID_VALUE: u8 = 0x03;
const CHIP_ID_MASK: u8  = 0x07;

// CTRL_REG0 bits.
const CTRL_REG0_EE_W: u8       = 0x10;
const CTRL_REG0_RESET_INT: u8  = 0x40;
const CTRL_REG0_ST0: u8          = 0x04;
const CTRL_REG0_SLEEP: u8      = 0x02;

// Soft-reset code.
const SOFT_RESET_CMD: u8 = 0xB6;

// Range bits in OFFSET_LSB1 (0x35) bits 3:1 — 111 not authorised.
const RANGE_1G: u8    = 0x00;
const RANGE_1_5G: u8  = 0x02;
const RANGE_2G: u8    = 0x04;
const RANGE_3G: u8    = 0x06;
const RANGE_4G: u8    = 0x08;
const RANGE_8G: u8    = 0x0A;
const RANGE_16G: u8   = 0x0C;

// Sensitivity (LSB/g) by range.
const SCALE_1G: f32   = 8192.0;
const SCALE_1_5G: f32 = 5460.0;
const SCALE_2G: f32   = 4096.0;
const SCALE_3G: f32   = 2730.0;
const SCALE_4G: f32   = 2048.0;
const SCALE_8G: f32   = 1024.0;
const SCALE_16G: f32  = 512.0;

// Low-pass bandwidth codes in BW_TCS (0x20) bits 7:4.
const BW_10: u8   = 0x00;
const BW_20: u8   = 0x10;
const BW_40: u8   = 0x20;
const BW_75: u8   = 0x30;
const BW_150: u8  = 0x40;
const BW_300: u8  = 0x50;
const BW_600: u8  = 0x60;
const BW_1200: u8 = 0x70;
const BW_HIGH_PASS_1HZ: u8 = 0x80;
const BW_BAND_PASS: u8     = 0x90;

/// Interrupt source: low-g (free-fall).
pub const SOURCE_LOW_G: u8    = 0x01;
/// Interrupt source: high-g (shock).
pub const SOURCE_HIGH_G: u8   = 0x02;
/// Interrupt source: slope (any-motion).
pub const SOURCE_SLOPE: u8    = 0x04;
/// Interrupt source: alert (slope drives alert phase; exclusive with slope).
pub const SOURCE_ALERT: u8    = 0x08;
/// Interrupt source: double-tap.
pub const SOURCE_TAP: u8      = 0x10;
/// Interrupt source: new data.
pub const SOURCE_NEW_DATA: u8 = 0x20;

/// STATUS_REG3 bit: high-g latched.
pub const STATUS_HIGH_G: u8  = 0x80;
/// STATUS_REG3 bit: low-g latched.
pub const STATUS_LOW_G: u8   = 0x40;
/// STATUS_REG3 bit: slope latched.
pub const STATUS_SLOPE: u8  = 0x20;
/// STATUS_REG3 bit: tap latched.
pub const STATUS_TAP: u8    = 0x10;
/// STATUS_REG3 bit: X axis triggered first.
pub const STATUS_X_FIRST: u8 = 0x04;
/// STATUS_REG3 bit: Y axis triggered first.
pub const STATUS_Y_FIRST: u8 = 0x02;
/// STATUS_REG3 bit: Z axis triggered first.
pub const STATUS_Z_FIRST: u8 = 0x01;

/// Duration time base: T_update = 417 µs, *dur = 5 * T_update ≈ 2.085 ms/LSB.
const DUR_LSB_MS: f32 = 2.085;

/// BMA180 minimal driver — 3-axis acceleration in *g*.
pub struct Bma180Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    range_g: f32,
    range_bits: u8,
}

impl<I2C: I2c> Bma180Minimal<I2C> {
    /// Create a new `Bma180Minimal` and verify the device identity.
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut buf = [0u8; 1];
        // First transaction must be something other than an acc LSB read.
        read_register(&mut i2c, addr, REG_CHIP_ID as u32, 1, &mut buf)?;
        if (buf[0] & CHIP_ID_MASK) != CHIP_ID_VALUE {
            panic!("BMA180 CHIP_ID: expected 0x{:02X}, got 0x{:02X}",
                   CHIP_ID_VALUE, buf[0] & CHIP_ID_MASK);
        }
        // Unlock image registers (0x20-0x3B) by setting ee_w = 1.
        let ctrl0 = read_reg8(&mut i2c, addr, REG_CTRL_REG0)?;
        write_reg(&mut i2c, addr, REG_CTRL_REG0, ctrl0 | CTRL_REG0_EE_W)?;
        // Set range = ±2 g (OFFSET_LSB1 bits 3:1 = 010), preserving cal/smp_skip.
        let olsb1 = read_reg8(&mut i2c, addr, REG_OFFSET_LSB1)?;
        write_reg(&mut i2c, addr, REG_OFFSET_LSB1, (olsb1 & 0xF1) | RANGE_2G)?;
        // Set bw = 150 Hz (BW_TCS bits 7:4 = 0100), preserving tcs.
        let bw = read_reg8(&mut i2c, addr, REG_BW_TCS)?;
        write_reg(&mut i2c, addr, REG_BW_TCS, (bw & 0x0F) | BW_150)?;
        Ok(Self { i2c, addr, range_g: 2.0, range_bits: RANGE_2G })
    }

    /// Read 3-axis linear acceleration in *g*.
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut raw = [0u8; 6];
        read_register(&mut self.i2c, self.addr, REG_ACC_X_LSB as u32, 1, &mut raw)?;
        let (rx, ry, rz) = (
            decode_axis(&raw[0..2]),
            decode_axis(&raw[2..4]),
            decode_axis(&raw[4..6]),
        );
        let scale = match self.range_g as u32 {
            1 => SCALE_1G,
            3 => SCALE_3G,
            4 => SCALE_4G,
            8 => SCALE_8G,
            16 => SCALE_16G,
            _ => SCALE_2G,
        };
        Ok((rx as f32 / scale, ry as f32 / scale, rz as f32 / scale))
    }
}

fn decode_axis(p: &[u8]) -> i16 {
    let v = ((p[1] as u16) << 6) | ((p[0] >> 2) as u16);
    if v >= 8192 { (v as i16) - 16384 } else { v as i16 }
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
    let table: [(u16, u8); 8] = [
        (10, BW_10), (20, BW_20), (40, BW_40), (75, BW_75),
        (150, BW_150), (300, BW_300), (600, BW_600), (1200, BW_1200),
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

fn nearest_tap_dur(window_ms: u16) -> u8 {
    let table: [(u16, u8); 8] = [
        (50, 0x00), (75, 0x01), (100, 0x02), (150, 0x03),
        (250, 0x04), (500, 0x05), (750, 0x06), (1000, 0x07),
    ];
    // Snap to the smallest table entry ≥ window_ms; fall back to the largest below it.
    let mut best_below = table[0];
    let mut best_diff_below = abs_diff(window_ms, table[0].0);
    for &entry in &table[1..] {
        let d = abs_diff(window_ms, entry.0);
        if entry.0 >= window_ms && d < abs_diff(window_ms, best_below.0) {
            return entry.1;
        }
        if d < best_diff_below {
            best_below = entry;
            best_diff_below = d;
        }
    }
    best_below.1
}

fn abs_diff(a: u16, b: u16) -> u16 {
    if a >= b { a - b } else { b - a }
}

/// BMA180 full driver — extends minimal with configuration, interrupt
/// sources, low-g / high-g / slope / alert / tap logic, sleep, soft reset,
/// self-test, and offset regulation.
pub struct Bma180Full<I2C> {
    inner: Bma180Minimal<I2C>,
    enabled_sources: u8,
    sleeping: bool,
}

impl<I2C: I2c> Bma180Full<I2C> {
    /// Create a new `Bma180Full`.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Bma180Minimal::new(i2c, addr)?;
        Ok(Self { inner, enabled_sources: 0, sleeping: false })
    }

    /// Read 3-axis linear acceleration in *g*.
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read()
    }

    /// Set the measurement range to ±1/±1.5/±2/±3/±4/±8/±16 *g*.
    pub fn set_range(&mut self, range_g: f32) -> Result<(), I2C::Error> {
        let bits = match range_g as u32 {
            1 => RANGE_1G,
            3 => RANGE_3G,
            4 => RANGE_4G,
            8 => RANGE_8G,
            16 => RANGE_16G,
            _ => RANGE_2G,
        };
        self.inner.range_g = range_g;
        self.inner.range_bits = bits;
        let olsb1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_OFFSET_LSB1)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OFFSET_LSB1, (olsb1 & 0xF1) | bits)
    }

    /// Set the low-pass bandwidth to the nearest supported value (10..1200 Hz).
    pub fn set_bandwidth(&mut self, bandwidth_hz: u16) -> Result<(), I2C::Error> {
        let bw_code = nearest_bandwidth(bandwidth_hz);
        let bw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_BW_TCS)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_BW_TCS, (bw & 0x0F) | bw_code)
    }

    /// Set the filter mode: 0 = low-pass (use set_bandwidth), 1 = high-pass 1 Hz, 2 = band-pass 0.2..300 Hz.
    pub fn set_filter_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        let code = match mode {
            1 => BW_HIGH_PASS_1HZ,
            2 => BW_BAND_PASS,
            _ => return Ok(()),
        };
        let bw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_BW_TCS)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_BW_TCS, (bw & 0x0F) | code)
    }

    /// Set the noise/power sub-mode (0..3).
    pub fn set_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        if mode > 3 { return Ok(()); }
        let tcoz = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_TCO_Z)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TCO_Z, (tcoz & 0xFC) | (mode & 0x03))
    }

    /// Set the data resolution: 12 or 14 bits.
    pub fn set_resolution(&mut self, bits: u8) -> Result<(), I2C::Error> {
        let ot = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_OFFSET_T)?;
        let out = match bits {
            12 => ot | 0x01,
            14 => ot & !0x01,
            _ => return Ok(()),
        };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OFFSET_T, out)
    }

    /// Read raw 14-bit two's-complement acceleration counts.
    pub fn read_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut raw = [0u8; 6];
        read_register(&mut self.inner.i2c, self.inner.addr, REG_ACC_X_LSB as u32, 1, &mut raw)?;
        Ok((decode_axis(&raw[0..2]), decode_axis(&raw[2..4]), decode_axis(&raw[4..6])))
    }

    /// Read on-chip temperature in °C.
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        let raw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_TEMP)?;
        let s = if raw < 128 { raw as i8 } else { (raw as i8).wrapping_sub(0) };
        let signed = if raw < 128 { raw as i32 } else { (raw as i32) - 256 };
        Ok(25.0 + (signed as f32 - 2.0) * 0.5)
    }

    /// Return true if all three new_data_X/Y/Z bits are set.
    pub fn new_data_available(&mut self) -> Result<bool, I2C::Error> {
        let x = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ACC_X_LSB)?;
        let y = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ACC_Y_LSB)?;
        let z = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ACC_Z_LSB)?;
        Ok((x & 0x01) != 0 && (y & 0x01) != 0 && (z & 0x01) != 0)
    }

    /// Enable or disable MSB-only reads (shadow_dis = not enabled).
    pub fn set_shadow(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let gy = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_GAIN_Y)?;
        let out = if enabled { gy & !0x01 } else { gy | 0x01 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GAIN_Y, out)
    }

    /// Toggle the sample-skip bit (only useful with the new-data interrupt).
    pub fn set_sample_skip(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let olsb1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_OFFSET_LSB1)?;
        let out = if enabled { olsb1 | 0x01 } else { olsb1 & !0x01 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OFFSET_LSB1, out)
    }

    /// Configure the low-g (free-fall) interrupt and enable it.
    pub fn set_low_g(&mut self, threshold_g: f32, duration_ms: u16,
                     hysteresis_g: f32, axes: u8, counter: u8, filtered: bool) -> Result<(), I2C::Error> {
        self.write_threshold(REG_LOW_TH, threshold_g)?;
        self.write_low_dur(duration_ms)?;
        self.write_low_hy(hysteresis_g)?;
        self.write_low_axes(axes)?;
        self.write_filt_bit(REG_HIGH_LOW_INFO, 0x01, filtered)?;
        self.write_debounce(b'l', counter)?;
        self.enable_source(SOURCE_LOW_G)
    }

    /// Configure the high-g (shock) interrupt and enable it.
    pub fn set_high_g(&mut self, threshold_g: f32, duration_ms: u16,
                      hysteresis_g: f32, axes: u8, counter: u8, filtered: bool) -> Result<(), I2C::Error> {
        self.write_threshold(REG_HIGH_TH, threshold_g)?;
        self.write_high_dur(duration_ms)?;
        self.write_high_hy(hysteresis_g)?;
        self.write_high_axes(axes)?;
        self.write_filt_bit(REG_HIGH_LOW_INFO, 0x10, filtered)?;
        self.write_debounce(b'h', counter)?;
        self.enable_source(SOURCE_HIGH_G)
    }

    /// Configure the slope (any-motion) interrupt and enable it.
    pub fn set_slope(&mut self, threshold_g: f32, samples: u8,
                     axes: u8, filtered: bool) -> Result<(), I2C::Error> {
        self.write_slope_threshold(REG_SLOPE_TH, threshold_g)?;
        self.write_slope_dur(samples)?;
        self.write_slope_axes(axes)?;
        self.write_filt_bit(REG_SLOPE_TAPSENS, 0x10, filtered)?;
        self.write_cr3_bit(0x40, true)?;   // slope_int
        self.write_cr3_bit(0x80, false)?;  // slope_alert
        self.write_cr3_bit(0x04, true)?;   // adv_int
        self.enable_source(SOURCE_SLOPE)
    }

    /// Enable alert mode (exclusive with slope).
    pub fn set_alert(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        if enabled {
            self.enabled_sources &= !SOURCE_SLOPE;
            self.write_cr3_bit(0x40, false)?;
            self.write_cr3_bit(0x80, true)?;
            self.write_cr3_bit(0x04, true)?;
            self.enable_source(SOURCE_ALERT)
        } else {
            self.disable_source(SOURCE_ALERT)?;
            self.write_cr3_bit(0x80, false)
        }
    }

    /// Configure the double-tap interrupt and enable it.
    pub fn set_tap(&mut self, threshold_g: f32, window_ms: u16,
                   axes: u8, filtered: bool) -> Result<(), I2C::Error> {
        self.write_slope_threshold(REG_TAPSENS_TH, threshold_g)?;
        self.write_tap_dur(window_ms)?;
        self.write_tap_axes(axes)?;
        self.write_filt_bit(REG_SLOPE_TAPSENS, 0x01, filtered)?;
        self.enable_source(SOURCE_TAP)
    }

    /// Enable latched interrupts (cleared by [`Self::clear_interrupt`]).
    pub fn set_latch(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.write_cr3_bit(0x01, enabled)
    }

    /// Clear latched interrupts (writes reset_INT to CTRL_REG0).
    pub fn clear_interrupt(&mut self) -> Result<(), I2C::Error> {
        if self.sleeping { return Ok(()); }
        let ctrl0 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0, ctrl0 | CTRL_REG0_RESET_INT)
    }

    /// Enable one interrupt source.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        if source == SOURCE_NEW_DATA {
            self.write_cr3_bit(0x02, true)?;
        } else {
            self.write_cr3_bit(0x02, false)?;
            match source {
                SOURCE_SLOPE => {
                    self.write_cr3_bit(0x80, false)?;
                    self.write_cr3_bit(0x40, true)?;
                    self.write_cr3_bit(0x04, true)?;
                    self.disable_source(SOURCE_ALERT)?;
                }
                SOURCE_ALERT => {
                    self.write_cr3_bit(0x40, false)?;
                    self.write_cr3_bit(0x80, true)?;
                    self.write_cr3_bit(0x04, true)?;
                    self.disable_source(SOURCE_SLOPE)?;
                }
                SOURCE_HIGH_G => { self.write_cr3_bit(0x20, true)?; }
                SOURCE_LOW_G  => { self.write_cr3_bit(0x10, true)?; }
                SOURCE_TAP    => { self.write_cr3_bit(0x08, true)?; }
                _ => {}
            }
        }
        self.enable_source(source)
    }

    /// Disable one interrupt source.
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        match source {
            SOURCE_NEW_DATA => { self.write_cr3_bit(0x02, false)?; }
            SOURCE_SLOPE    => { self.write_cr3_bit(0x40, false)?; }
            SOURCE_ALERT    => {
                self.write_cr3_bit(0x80, false)?;
                self.write_cr3_bit(0x04, false)?;
            }
            SOURCE_HIGH_G   => { self.write_cr3_bit(0x20, false)?; }
            SOURCE_LOW_G    => { self.write_cr3_bit(0x10, false)?; }
            SOURCE_TAP      => { self.write_cr3_bit(0x08, false)?; }
            _ => {}
        }
        self.disable_source(source)
    }

    /// Read STATUS_REG3 (latched flags) without clearing.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS_REG3)
    }

    /// Read all four status registers.
    pub fn read_status(&mut self) -> Result<(u8, u8, u8, u8), I2C::Error> {
        let s1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS_REG1)?;
        let s2 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS_REG2)?;
        let s3 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS_REG3)?;
        let s4 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS_REG4)?;
        Ok((s1, s2, s3, s4))
    }

    /// Configure self-wake-up mode.
    pub fn set_wake_up(&mut self, enabled: bool, pause_ms: u16) -> Result<(), I2C::Error> {
        let code: u8 = match pause_ms {
            80 => 0x01,
            320 => 0x02,
            2560 => 0x03,
            _ => 0x00,
        };
        let tcoy = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_TCO_Y)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TCO_Y, (tcoy & 0xFC) | code)?;
        let gz = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_GAIN_Z)?;
        let out = if enabled { gz | 0x01 } else { gz & !0x01 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GAIN_Z, out)
    }

    /// Enter sleep mode.
    pub fn sleep(&mut self) -> Result<(), I2C::Error> {
        if self.sleeping { return Ok(()); }
        let ctrl0 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0, ctrl0 | CTRL_REG0_SLEEP)?;
        self.sleeping = true;
        Ok(())
    }

    /// Leave sleep mode.
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        if !self.sleeping { return Ok(()); }
        let ctrl0 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0, ctrl0 & !CTRL_REG0_SLEEP)?;
        self.sleeping = false;
        Ok(())
    }

    /// Issue a power-on-equivalent reset; restores defaults.
    pub fn soft_reset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RESET, SOFT_RESET_CMD)?;
        let mut buf = [0u8; 1];
        read_register(&mut self.inner.i2c, self.inner.addr, REG_CHIP_ID as u32, 1, &mut buf)?;
        if (buf[0] & CHIP_ID_MASK) != CHIP_ID_VALUE {
            panic!("BMA180 CHIP_ID after reset: expected 0x{:02X}, got 0x{:02X}",
                   CHIP_ID_VALUE, buf[0] & CHIP_ID_MASK);
        }
        let ctrl0 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0, ctrl0 | CTRL_REG0_EE_W)?;
        let olsb1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_OFFSET_LSB1)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OFFSET_LSB1, (olsb1 & 0xF1) | RANGE_2G)?;
        let bw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_BW_TCS)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_BW_TCS, (bw & 0x0F) | BW_150)?;
        self.inner.range_g = 2.0;
        self.inner.range_bits = RANGE_2G;
        self.sleeping = false;
        Ok(())
    }

    /// Run the electrostatic self-test.
    pub fn self_test(&mut self) -> Result<bool, I2C::Error> {
        let ctrl0 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0, ctrl0 | CTRL_REG0_ST0)?;
        let (x, y, z) = self.read_raw()?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG0, ctrl0)?;
        let ok = x.abs() > 200 && y.abs() > 200 && z.abs() > 200;
        self.soft_reset()?;
        Ok(ok)
    }

    /// Run the in-field zero-g offset calibration (volatile).
    pub fn calibrate_offset(&mut self, axes: u8, mode: u8) -> Result<(), I2C::Error> {
        if mode > 3 { return Ok(()); }
        let cr4 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG4)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG4, (cr4 & 0xFC) | (mode & 0x03))?;
        let ax: [(u8, u8); 3] = [(0x01, 0x80), (0x02, 0x40), (0x04, 0x20)];
        for (mask, ctrl1_bit) in ax.iter() {
            if (axes & mask) == 0 { continue; }
            let ctrl1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, ctrl1 | ctrl1_bit)?;
            for _ in 0..100u8 {
                let s1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS_REG1)?;
                if s1 & 0x02 != 0 { break; }
            }
            let ctrl1 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, ctrl1 & !ctrl1_bit)?;
        }
        let cr4 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG4)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG4, cr4 & 0xFC)
    }

    /// Read VERSION split into (al_version, ml_version).
    pub fn read_version(&mut self) -> Result<(u8, u8), I2C::Error> {
        let raw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_VERSION)?;
        Ok(((raw >> 4) & 0x0F, raw & 0x0F))
    }

    /// Read one of the two CUSTOMER scratch bytes.
    pub fn read_customer(&mut self, index: u8) -> Result<u8, I2C::Error> {
        let reg = if index == 0 { REG_CD1 } else { REG_CD2 };
        read_reg8(&mut self.inner.i2c, self.inner.addr, reg)
    }

    /// Write one of the two CUSTOMER scratch bytes.
    pub fn write_customer(&mut self, index: u8, value: u8) -> Result<(), I2C::Error> {
        let reg = if index == 0 { REG_CD1 } else { REG_CD2 };
        write_reg(&mut self.inner.i2c, self.inner.addr, reg, value)
    }

    fn write_threshold(&mut self, reg: u8, threshold_g: f32) -> Result<(), I2C::Error> {
        let code = libm::roundf(threshold_g / self.inner.range_g * 255.0) as i32;
        let code = if code < 0 { 0 } else if code > 255 { 255 } else { code } as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, reg, code)
    }

    fn write_slope_threshold(&mut self, reg: u8, threshold_g: f32) -> Result<(), I2C::Error> {
        let code = libm::roundf(threshold_g / (0.0156 * self.inner.range_g / 2.0)) as i32;
        let code = if code < 0 { 0 } else if code > 255 { 255 } else { code } as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, reg, code)
    }

    fn write_low_dur(&mut self, duration_ms: u16) -> Result<(), I2C::Error> {
        let code = libm::roundf(duration_ms as f32 / DUR_LSB_MS) as i32;
        let code = if code < 0 { 0 } else if code > 127 { 127 } else { code } as u8;
        let ld = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_LOW_DUR)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_LOW_DUR, (ld & 0x01) | ((code & 0x7F) << 1))
    }

    fn write_high_dur(&mut self, duration_ms: u16) -> Result<(), I2C::Error> {
        let code = libm::roundf(duration_ms as f32 / DUR_LSB_MS) as i32;
        let code = if code < 0 { 0 } else if code > 127 { 127 } else { code } as u8;
        let hd = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_HIGH_DUR)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_HIGH_DUR, (hd & 0x01) | ((code & 0x7F) << 1))
    }

    fn write_low_hy(&mut self, hysteresis_g: f32) -> Result<(), I2C::Error> {
        let code = libm::roundf(hysteresis_g / self.inner.range_g * 255.0 / 32.0) as i32;
        let code = if code < 0 { 0 } else if code > 31 { 31 } else { code } as u8;
        let hy = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_HY)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_HY, (hy & 0xF8) | (code & 0x07))?;
        let cr4 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG4)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG4, (cr4 & !(0x03 << 6)) | (((code >> 3) & 0x03) << 6))
    }

    fn write_high_hy(&mut self, hysteresis_g: f32) -> Result<(), I2C::Error> {
        let code = libm::roundf(hysteresis_g / self.inner.range_g * 255.0 / 32.0) as i32;
        let code = if code < 0 { 0 } else if code > 31 { 31 } else { code } as u8;
        let hy = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_HY)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_HY, (hy & 0x07) | ((code & 0x1F) << 3))
    }

    fn write_low_axes(&mut self, axes: u8) -> Result<(), I2C::Error> {
        let hli = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_HIGH_LOW_INFO)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_HIGH_LOW_INFO, (hli & 0xF1) | ((axes & 0x07) << 1))
    }

    fn write_high_axes(&mut self, axes: u8) -> Result<(), I2C::Error> {
        let hli = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_HIGH_LOW_INFO)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_HIGH_LOW_INFO, (hli & 0x0F) | ((axes & 0x07) << 5))
    }

    fn write_slope_axes(&mut self, axes: u8) -> Result<(), I2C::Error> {
        let st = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_SLOPE_TAPSENS)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_SLOPE_TAPSENS, (st & 0x0F) | ((axes & 0x07) << 5))
    }

    fn write_tap_axes(&mut self, axes: u8) -> Result<(), I2C::Error> {
        let st = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_SLOPE_TAPSENS)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_SLOPE_TAPSENS, (st & 0xF1) | ((axes & 0x07) << 1))
    }

    fn write_filt_bit(&mut self, reg: u8, bit: u8, enabled: bool) -> Result<(), I2C::Error> {
        let v = read_reg8(&mut self.inner.i2c, self.inner.addr, reg)?;
        let out = if enabled { v | bit } else { v & !bit };
        write_reg(&mut self.inner.i2c, self.inner.addr, reg, out)
    }

    fn write_debounce(&mut self, kind: u8, counter: u8) -> Result<(), I2C::Error> {
        if counter > 3 { return Ok(()); }
        let code = (counter & 0x03) << 2;  // LG pos (bits 3:2); HG shifts by 2 more (bits 5:4)
        let cr4 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG4)?;
        let out = if kind == b'l' {
            (cr4 & !(0x03 << 2)) | (code & (0x03 << 2))
        } else {
            (cr4 & !(0x03 << 4)) | ((code << 2) & (0x03 << 4))
        };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG4, out)
    }

    fn write_slope_dur(&mut self, samples: u8) -> Result<(), I2C::Error> {
        let code: u8 = match samples {
            3 => 0x01,
            5 => 0x02,
            7 => 0x03,
            _ => 0x00,
        };
        let tcox = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_TCO_X)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TCO_X, (tcox & 0xFC) | code)
    }

    fn write_tap_dur(&mut self, window_ms: u16) -> Result<(), I2C::Error> {
        let code = nearest_tap_dur(window_ms);
        let gt = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_GAIN_T)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GAIN_T, (gt & 0xF8) | code)
    }

    fn write_cr3_bit(&mut self, bit: u8, enabled: bool) -> Result<(), I2C::Error> {
        if self.sleeping { return Ok(()); }
        let cr3 = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG3)?;
        let out = if enabled { cr3 | bit } else { cr3 & !bit };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG3, out)
    }

    fn enable_source(&mut self, source: u8) -> Result<(), I2C::Error> {
        if self.sleeping { return Ok(()); }
        self.enabled_sources |= source;
        Ok(())
    }

    fn disable_source(&mut self, source: u8) -> Result<(), I2C::Error> {
        self.enabled_sources &= !source;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x40;

    fn new_accel(extra: &[I2cTransaction]) -> Bma180Full<I2cMock> {
        let mut init = vec![
            // First transaction: CHIP_ID = 0x03.
            I2cTransaction::write_read(ADDR, vec![REG_CHIP_ID], vec![CHIP_ID_VALUE]),
            // ee_w (read CTRL_REG0=0x00 -> write 0x10).
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG0], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG0, CTRL_REG0_EE_W]),
            // range ±2 g (read OFFSET_LSB1=0x00 -> write (0x00 & 0xF1) | 0x04 = 0x04).
            I2cTransaction::write_read(ADDR, vec![REG_OFFSET_LSB1], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_OFFSET_LSB1, RANGE_2G]),
            // bw 150 Hz (read BW_TCS=0x00 -> write 0x40).
            I2cTransaction::write_read(ADDR, vec![REG_BW_TCS], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_BW_TCS, BW_150]),
        ];
        init.extend_from_slice(extra);
        let i2c = I2cMock::new(&init);
        Bma180Full::new(i2c, ADDR).expect("new")
    }

    #[test]
    fn construction_and_read() {
        // raw_x = +0x200, raw_y = -0x200, raw_z = 0.
        let mut accel = new_accel(&[
            I2cTransaction::write_read(ADDR, vec![REG_ACC_X_LSB], vec![0x00, 0x08, 0x00, 0xF8, 0x00, 0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_ACC_X_LSB], vec![0x00, 0x08, 0x00, 0xF8, 0x00, 0x00]),
        ]);
        let (x, y, z) = accel.read().unwrap();
        assert!((x - 0.125).abs() < 1e-6);
        assert!((y - (-0.125)).abs() < 1e-6);
        assert!(z.abs() < 1e-6);
        let (rx, ry, rz) = accel.read_raw().unwrap();
        assert_eq!(rx, 512);
        assert_eq!(ry, -512);
        assert_eq!(rz, 0);
        accel.inner.i2c.done();
    }

    #[test]
    fn range_and_bandwidth() {
        let mut accel = new_accel(&[
            // set_range(8): read 0x04, write (0x04 & 0xF1) | 0x0A = 0x0A.
            I2cTransaction::write_read(ADDR, vec![REG_OFFSET_LSB1], vec![RANGE_2G]),
            I2cTransaction::write(ADDR, vec![REG_OFFSET_LSB1, RANGE_8G]),
            // set_bandwidth(40): read 0x40, write (0x40 & 0x0F) | 0x20 = 0x20.
            I2cTransaction::write_read(ADDR, vec![REG_BW_TCS], vec![BW_150]),
            I2cTransaction::write(ADDR, vec![REG_BW_TCS, BW_40]),
        ]);
        accel.set_range(8.0).unwrap();
        accel.set_bandwidth(40).unwrap();
        accel.inner.i2c.done();
    }

    #[test]
    fn temperature() {
        let mut accel = new_accel(&[
            I2cTransaction::write_read(ADDR, vec![REG_TEMP], vec![0x02]),
            I2cTransaction::write_read(ADDR, vec![REG_TEMP], vec![0x82]),
        ]);
        assert!((accel.read_temperature().unwrap() - 25.0).abs() < 1e-6);
        assert!((accel.read_temperature().unwrap() - (-39.0)).abs() < 1e-6);
        accel.inner.i2c.done();
    }

    #[test]
    fn version_and_customer() {
        let mut accel = new_accel(&[
            I2cTransaction::write_read(ADDR, vec![REG_VERSION], vec![0xAB]),
            I2cTransaction::write_read(ADDR, vec![REG_CD1], vec![0xA5]),
            I2cTransaction::write(ADDR, vec![REG_CD2, 0x5A]),
        ]);
        let (al, ml) = accel.read_version().unwrap();
        assert_eq!(al, 0x0A);
        assert_eq!(ml, 0x0B);
        assert_eq!(accel.read_customer(0).unwrap(), 0xA5);
        accel.write_customer(1, 0x5A).unwrap();
        accel.inner.i2c.done();
    }

    #[test]
    fn soft_reset_and_sleep() {
        let mut accel = new_accel(&[
            // sleep(): read CTRL_REG0=0x10 (after init), write 0x10 | 0x02 = 0x12.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG0], vec![0x10]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG0, 0x12]),
            // wake(): read 0x12, write 0x12 & !0x02 = 0x10.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG0], vec![0x12]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG0, 0x10]),
            // soft_reset(): write RESET 0xB6; then CHIP_ID=0x03; then CTRL_REG0 ee_w; etc.
            I2cTransaction::write(ADDR, vec![REG_RESET, SOFT_RESET_CMD]),
            I2cTransaction::write_read(ADDR, vec![REG_CHIP_ID], vec![CHIP_ID_VALUE]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG0], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG0, CTRL_REG0_EE_W]),
            I2cTransaction::write_read(ADDR, vec![REG_OFFSET_LSB1], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_OFFSET_LSB1, RANGE_2G]),
            I2cTransaction::write_read(ADDR, vec![REG_BW_TCS], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_BW_TCS, BW_150]),
        ]);
        accel.sleep().unwrap();
        accel.wake().unwrap();
        accel.soft_reset().unwrap();
        accel.inner.i2c.done();
    }
}