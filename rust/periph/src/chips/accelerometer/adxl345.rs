//! ADXL345 — 3-axis MEMS accelerometer (Analog Devices).
//!
//! Communicates over I²C (address 0x53 or 0x1D) or SPI (CPOL=1/CPHA=1, mode 3,
//! up to 5 MHz). The driver performs DEVID verification and writes sensible
//! defaults at construction. SPI writes prepend the R/W|MB|A5..A0 command
//! byte to each transfer; multi-byte reads set MB=1 so the register pointer
//! auto-increments.
//!
//! ## Default configuration
//!
//! - Full-resolution mode (3.9 mg/LSB at any range)
//! - ±2 g measurement range
//! - 100 Hz output data rate, normal power
//! - FIFO bypass, all interrupts disabled, no offsets
//!
//! ## Constants
//!
//! Interrupts: [`INT_DATA_READY`], [`INT_SINGLE_TAP`], [`INT_DOUBLE_TAP`],
//! [`INT_ACTIVITY`], [`INT_INACTIVITY`], [`INT_FREE_FALL`],
//! [`INT_WATERMARK`], [`INT_OVERRUN`]
//! FIFO: [`FIFO_BYPASS`], [`FIFO_FIFO`], [`FIFO_STREAM`], [`FIFO_TRIGGER`]
//! Wakeup rates: [`WAKEUP_8_HZ`], [`WAKEUP_4_HZ`], [`WAKEUP_2_HZ`],
//! [`WAKEUP_1_HZ`]

use embedded_hal::i2c::I2c;

const REG_DEVID: u8 = 0x00;
const REG_THRESH_TAP: u8 = 0x1D;
const REG_OFSX: u8 = 0x1E;
const REG_OFSY: u8 = 0x1F;
const REG_OFSZ: u8 = 0x20;
const REG_DUR: u8 = 0x21;
const REG_LATENT: u8 = 0x22;
const REG_WINDOW: u8 = 0x23;
const REG_THRESH_ACT: u8 = 0x24;
const REG_THRESH_INACT: u8 = 0x25;
const REG_TIME_INACT: u8 = 0x26;
const REG_ACT_INACT_CTL: u8 = 0x27;
const REG_THRESH_FF: u8 = 0x28;
const REG_TIME_FF: u8 = 0x29;
const REG_TAP_AXES: u8 = 0x2A;
const REG_BW_RATE: u8 = 0x2C;
const REG_POWER_CTL: u8 = 0x2D;
const REG_INT_ENABLE: u8 = 0x2E;
const REG_INT_MAP: u8 = 0x2F;
const REG_INT_SOURCE: u8 = 0x30;
const REG_DATA_FORMAT: u8 = 0x31;
const REG_DATAX0: u8 = 0x32;
const REG_FIFO_CTL: u8 = 0x38;
const REG_FIFO_STATUS: u8 = 0x39;

const DEVID_VALUE: u8 = 0xE5;
const DATA_FORMAT_DEFAULT: u8 = 0x08;
const BW_RATE_DEFAULT: u8 = 0x0A;
const POWER_CTL_DEFAULT: u8 = 0x08;
const FULL_RES_SCALE_G_PER_LSB: f32 = 0.0039;

/// Interrupt source: data ready.
pub const INT_DATA_READY: u8 = 0x80;
/// Interrupt source: single tap.
pub const INT_SINGLE_TAP: u8 = 0x40;
/// Interrupt source: double tap.
pub const INT_DOUBLE_TAP: u8 = 0x20;
/// Interrupt source: activity.
pub const INT_ACTIVITY: u8 = 0x10;
/// Interrupt source: inactivity.
pub const INT_INACTIVITY: u8 = 0x08;
/// Interrupt source: free fall.
pub const INT_FREE_FALL: u8 = 0x04;
/// Interrupt source: FIFO watermark.
pub const INT_WATERMARK: u8 = 0x02;
/// Interrupt source: FIFO overrun.
pub const INT_OVERRUN: u8 = 0x01;

/// FIFO mode: bypass.
pub const FIFO_BYPASS: u8 = 0x00;
/// FIFO mode: FIFO (oldest entries discarded on overflow).
pub const FIFO_FIFO: u8 = 0x40;
/// FIFO mode: stream (newest entries discarded on overflow).
pub const FIFO_STREAM: u8 = 0x80;
/// FIFO mode: trigger.
pub const FIFO_TRIGGER: u8 = 0xC0;

/// Sleep-mode wakeup rate: 8 Hz.
pub const WAKEUP_8_HZ: u8 = 0x00;
/// Sleep-mode wakeup rate: 4 Hz.
pub const WAKEUP_4_HZ: u8 = 0x02;
/// Sleep-mode wakeup rate: 2 Hz.
pub const WAKEUP_2_HZ: u8 = 0x04;
/// Sleep-mode wakeup rate: 1 Hz.
pub const WAKEUP_1_HZ: u8 = 0x06;

/// ADXL345 minimal driver — 3-axis acceleration in *g*.
///
/// Performs DEVID verification and writes sensible defaults at construction.
pub struct Adxl345Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    spi: bool,
    range_bits: u8,
    full_res: bool,
}

impl<I2C: I2c> Adxl345Minimal<I2C> {
    /// Create a new `Adxl345Minimal` and verify the device identity.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit I²C address (typically `0x53` or `0x1D`).
    /// * `spi` — Pass `true` for SPI bus (prepends the R/W|MB|A5..A0 command byte).
    pub fn new(mut i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        write_reg(&mut i2c, addr, REG_DATA_FORMAT, DATA_FORMAT_DEFAULT, spi)?;
        write_reg(&mut i2c, addr, REG_BW_RATE, BW_RATE_DEFAULT, spi)?;
        write_reg(&mut i2c, addr, REG_POWER_CTL, POWER_CTL_DEFAULT, spi)?;
        let devid = read_reg8(&mut i2c, addr, REG_DEVID, spi)?;
        if devid != DEVID_VALUE {
            panic!("ADXL345 DEVID: expected 0x{:02X}, got 0x{:02X}", DEVID_VALUE, devid);
        }
        Ok(Self { i2c, addr, spi, range_bits: 0, full_res: true })
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns (x, y, z) in *g*.
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut raw = [0u8; 6];
        read_reg_bytes(&mut self.i2c, self.addr, REG_DATAX0, &mut raw, self.spi)?;
        let rx = i16::from_le_bytes([raw[0], raw[1]]) as f32;
        let ry = i16::from_le_bytes([raw[2], raw[3]]) as f32;
        let rz = i16::from_le_bytes([raw[4], raw[5]]) as f32;
        Ok((rx * FULL_RES_SCALE_G_PER_LSB,
            ry * FULL_RES_SCALE_G_PER_LSB,
            rz * FULL_RES_SCALE_G_PER_LSB))
    }
}

fn cmd_byte(reg: u8, read: bool, multi: bool) -> u8 {
    let mut addr = reg & 0x3F;
    if multi { addr |= 0x40; }
    if read  { addr |= 0x80; }
    addr
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8, spi: bool) -> Result<(), I2C::Error> {
    if spi {
        let cmd = cmd_byte(reg, false, false);
        i2c.write(addr, &[cmd, value])
    } else {
        i2c.write(addr, &[reg, value])
    }
}

fn read_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, spi: bool) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    if spi {
        let cmd = cmd_byte(reg, true, false);
        i2c.write_read(addr, &[cmd], &mut buf)?;
    } else {
        i2c.write_read(addr, &[reg], &mut buf)?;
    }
    Ok(buf[0])
}

fn read_reg_bytes<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8], spi: bool) -> Result<(), I2C::Error> {
    if spi {
        let cmd = cmd_byte(reg, true, buf.len() > 1);
        i2c.write_read(addr, &[cmd], buf)
    } else {
        i2c.write_read(addr, &[reg], buf)
    }
}

fn encode_offset(offset_g: f32) -> u8 {
    let mut raw = libm::roundf(offset_g / 0.0156) as i32;
    if raw >  127 { raw =  127; }
    if raw < -128 { raw = -128; }
    raw as u8
}

/// ADXL345 full driver — extends minimal with configuration, FIFO, tap /
/// activity / inactivity / free-fall detection, and interrupt routing.
///
/// Re-exports [`Adxl345Minimal::read`] as a one-line delegate (the Rust
/// equivalent of "Full never duplicates Minimal"), then adds Full-only
/// methods below.
pub struct Adxl345Full<I2C> {
    inner: Adxl345Minimal<I2C>,
}

impl<I2C: I2c> Adxl345Full<I2C> {
    /// Create a new `Adxl345Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x53 or 0x1D).
    /// * `spi` — Pass `true` for SPI bus.
    pub fn new(i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        let inner = Adxl345Minimal::new(i2c, addr, spi)?;
        Ok(Self { inner })
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns (x, y, z) in *g*. Delegates to [`Adxl345Minimal::read`].
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read()
    }

    /// Set the measurement range to ±2/±4/±8/±16 g.
    ///
    /// `range_g` must be 2, 4, 8, or 16. FULL_RES is preserved so the scale
    /// factor stays 3.9 mg/LSB regardless of range.
    pub fn set_range(&mut self, range_g: u8) -> Result<(), I2C::Error> {
        let code = match range_g {
            2 => 0, 4 => 1, 8 => 2, 16 => 3,
            _ => return Ok(()),
        };
        self.inner.range_bits = code;
        let mut df = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_DATA_FORMAT, self.inner.spi)?;
        df = (df & !0x03) | (code & 0x03);
        if self.inner.full_res { df |= 0x08; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_DATA_FORMAT, df, self.inner.spi)
    }

    /// Set the output data rate to the nearest supported value (6.25 Hz–3200 Hz).
    pub fn set_data_rate(&mut self, rate_hz: f32) -> Result<(), I2C::Error> {
        let rates: [(u8, f32); 10] = [
            (0x0F, 3200.0), (0x0E, 1600.0), (0x0D, 800.0), (0x0C, 400.0),
            (0x0B,  200.0), (0x0A,  100.0), (0x09,  50.0), (0x08,  25.0),
            (0x07,   12.5), (0x06,    6.25),
        ];
        let mut best_code = rates[0].0;
        let mut best_rate = rates[0].1;
        let mut best_diff = (best_rate - rate_hz).abs();
        for &(code, rate) in &rates {
            let diff = (rate - rate_hz).abs();
            if diff < best_diff {
                best_code = code;
                best_rate = rate;
                best_diff = diff;
            }
        }
        let mut bw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_BW_RATE, self.inner.spi)?;
        bw = (bw & !0x0F) | (best_code & 0x0F);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_BW_RATE, bw, self.inner.spi)
    }

    /// Enable or disable low-power mode (higher noise).
    pub fn set_low_power(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut bw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_BW_RATE, self.inner.spi)?;
        if enabled { bw |= 0x10; } else { bw &= !0x10; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_BW_RATE, bw, self.inner.spi)
    }

    /// Set per-axis offset in *g*.
    pub fn set_offset(&mut self, x: f32, y: f32, z: f32) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OFSX, encode_offset(x), self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OFSY, encode_offset(y), self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OFSZ, encode_offset(z), self.inner.spi)
    }

    /// Measure and write per-axis offsets to null sensor bias.
    ///
    /// Averages `samples` reads with the sensor stationary and writes the
    /// negative residual into the offset registers so subsequent readings
    /// track `target_x`, `target_y`, `target_z`.
    pub fn calibrate_offset(&mut self, target_x: f32, target_y: f32, target_z: f32, samples: u16) -> Result<(), I2C::Error> {
        let mut sx = 0.0f32;
        let mut sy = 0.0f32;
        let mut sz = 0.0f32;
        for _ in 0..samples {
            let (x, y, z) = self.inner.read()?;
            sx += x; sy += y; sz += z;
        }
        sx /= samples as f32;
        sy /= samples as f32;
        sz /= samples as f32;
        self.set_offset(target_x - sx, target_y - sy, target_z - sz)
    }

    /// Configure single-tap detection and enable the SINGLE_TAP interrupt.
    pub fn set_tap_detection(&mut self, threshold_g: f32, duration_ms: f32, axes: u8, suppress: bool) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_THRESH_TAP, (threshold_g / 0.0625) as u8, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_DUR, (duration_ms / 0.625) as u8, self.inner.spi)?;
        let tap_axes = (axes & 0x07) | if suppress { 0x08 } else { 0x00 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TAP_AXES, tap_axes, self.inner.spi)?;
        self.enable_interrupt(INT_SINGLE_TAP)
    }

    /// Configure double-tap latency and window; enable DOUBLE_TAP interrupt.
    pub fn set_double_tap(&mut self, latency_ms: f32, window_ms: f32) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_LATENT, (latency_ms / 1.25) as u8, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_WINDOW, (window_ms / 1.25) as u8, self.inner.spi)?;
        self.enable_interrupt(INT_DOUBLE_TAP)
    }

    /// Configure activity detection.
    pub fn set_activity(&mut self, threshold_g: f32, axes: u8, ac_coupled: bool) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_THRESH_ACT, (threshold_g / 0.0625) as u8, self.inner.spi)?;
        let mut aic = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ACT_INACT_CTL, self.inner.spi)?;
        aic &= !0xF0;
        if ac_coupled { aic |= 0x80; }
        aic |= axes & 0x70;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ACT_INACT_CTL, aic, self.inner.spi)?;
        self.enable_interrupt(INT_ACTIVITY)
    }

    /// Configure inactivity detection.
    pub fn set_inactivity(&mut self, threshold_g: f32, time_sec: f32, axes: u8, ac_coupled: bool) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_THRESH_INACT, (threshold_g / 0.0625) as u8, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TIME_INACT, time_sec as u8, self.inner.spi)?;
        let mut aic = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ACT_INACT_CTL, self.inner.spi)?;
        aic &= !0x0F;
        if ac_coupled { aic |= 0x08; }
        aic |= axes & 0x07;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ACT_INACT_CTL, aic, self.inner.spi)?;
        self.enable_interrupt(INT_INACTIVITY)
    }

    /// Configure free-fall detection and enable the FREE_FALL interrupt.
    pub fn set_free_fall(&mut self, threshold_g: f32, time_ms: f32) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_THRESH_FF, (threshold_g / 0.0625) as u8, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TIME_FF, (time_ms / 5.0) as u8, self.inner.spi)?;
        self.enable_interrupt(INT_FREE_FALL)
    }

    /// Enable or disable an interrupt source and route it to INT1 or INT2.
    ///
    /// `pin` is 1 for INT1 (default) or 2 for INT2.
    pub fn set_interrupt(&mut self, source: u8, enabled: bool, pin: u8) -> Result<(), I2C::Error> {
        let mut ie = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_ENABLE, self.inner.spi)?;
        let mut im = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_MAP, self.inner.spi)?;
        if enabled {
            ie |= source;
            if pin == 2 { im |= source; } else { im &= !source; }
        } else {
            ie &= !source;
        }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_ENABLE, ie, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_MAP, im, self.inner.spi)
    }

    fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        self.set_interrupt(source, true, 1)
    }

    /// Read the INT_SOURCE register; clears latched interrupts.
    pub fn read_interrupt_source(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_SOURCE, self.inner.spi)
    }

    /// Configure the FIFO.
    ///
    /// `mode` is one of [`FIFO_BYPASS`], [`FIFO_FIFO`], [`FIFO_STREAM`],
    /// [`FIFO_TRIGGER`]. `samples` is the watermark level for FIFO / Stream,
    /// or the number of samples to retain before triggering for Trigger.
    pub fn set_fifo_mode(&mut self, mode: u8, samples: u8) -> Result<(), I2C::Error> {
        let fifo_ctl = (mode & 0xC0) | (samples & 0x1F);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_CTL, fifo_ctl, self.inner.spi)
    }

    /// Number of FIFO entries currently available (0–32).
    pub fn fifo_count(&mut self) -> Result<u8, I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_FIFO_STATUS, self.inner.spi)?;
        Ok(status & 0x3F)
    }

    /// Drain the FIFO, returning all available (x, y, z) samples in *g*.
    ///
    /// Each sample is the same 6-byte burst as [`Adxl345Minimal::read`].
    /// The output buffer must be at least 32 entries — the chip's FIFO
    /// maximum depth.
    pub fn read_fifo(&mut self, out: &mut [(f32, f32, f32)]) -> Result<usize, I2C::Error> {
        let n = self.fifo_count()? as usize;
        let n = if n > out.len() { out.len() } else { n };
        for slot in out.iter_mut().take(n) {
            let (x, y, z) = self.inner.read()?;
            *slot = (x, y, z);
        }
        Ok(n)
    }

    /// Enter or leave sleep mode.
    pub fn set_sleep(&mut self, enabled: bool, wakeup_hz: u8) -> Result<(), I2C::Error> {
        let mut pwr = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_POWER_CTL, self.inner.spi)?;
        if enabled {
            let wakeup_code = match wakeup_hz {
                8 => WAKEUP_8_HZ, 4 => WAKEUP_4_HZ, 2 => WAKEUP_2_HZ, 1 => WAKEUP_1_HZ,
                _ => return Ok(()),
            };
            pwr = (pwr & !0x06) | wakeup_code | 0x08;
            pwr |= 0x04;
        } else {
            pwr &= !0x04;
        }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_POWER_CTL, pwr, self.inner.spi)
    }

    /// Enable or disable the activity/inactivity serial-link mode.
    pub fn set_link_mode(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut pwr = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_POWER_CTL, self.inner.spi)?;
        if enabled { pwr |= 0x40; } else { pwr &= !0x40; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_POWER_CTL, pwr, self.inner.spi)
    }

    /// Enable or disable auto-sleep on inactivity (requires Link=1).
    pub fn set_auto_sleep(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut pwr = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_POWER_CTL, self.inner.spi)?;
        if enabled { pwr |= 0x20; } else { pwr &= !0x20; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_POWER_CTL, pwr, self.inner.spi)
    }

    /// Enable or disable the electrostatic self-test force on all axes.
    pub fn self_test(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut df = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_DATA_FORMAT, self.inner.spi)?;
        if enabled { df |= 0x80; } else { df &= !0x80; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_DATA_FORMAT, df, self.inner.spi)
    }
}