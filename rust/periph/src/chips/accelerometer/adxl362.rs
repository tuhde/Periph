//! ADXL362 — 3-axis MEMS accelerometer (Analog Devices).
//!
//! Communicates over SPI (Mode 0, up to 8 MHz, MSB first). The chip is
//! SPI-only — there is no I²C mode. SPI command structure:
//!
//! ```text
//! WRITE register   0x0A + addr + data...
//! READ  register   0x0B + addr + data...
//! READ  FIFO       0x0D       + data...
//! ```
//!
//! ## Default configuration
//!
//! - ±2 *g* measurement range
//! - 100 Hz output data rate, ODR/4 antialiasing bandwidth
//! - Normal noise mode (`POWER_CTL.LOW_NOISE=00`)
//! - Continuous measurement mode (`POWER_CTL.MEASURE=10`)
//! - FIFO disabled
//! - No interrupts mapped; INT1/INT2 high-impedance
//!
//! ## Constants
//!
//! Interrupt sources: [`SOURCE_DATA_READY`], [`SOURCE_FIFO_READY`],
//! [`SOURCE_FIFO_WATERMARK`], [`SOURCE_FIFO_OVERRUN`], [`SOURCE_ACT`],
//! [`SOURCE_INACT`], [`SOURCE_AWAKE`]
//!
//! Noise modes: [`NOISE_NORMAL`], [`NOISE_LOW`], [`NOISE_ULTRALOW`]
//!
//! Link/loop modes: [`LINKLOOP_DEFAULT`], [`LINKLOOP_LINKED`], [`LINKLOOP_LOOP`]
//!
//! FIFO modes: [`FIFO_DISABLED`], [`FIFO_OLDEST_SAVED`], [`FIFO_STREAM`],
//! [`FIFO_TRIGGERED`]
//!
//! FIFO axis codes: [`AXIS_X`], [`AXIS_Y`], [`AXIS_Z`], [`AXIS_TEMP`]

use embedded_hal::spi::{Operation, SpiDevice};

// SPI command bytes.
const CMD_WRITE_REG: u8 = 0x0A;
const CMD_READ_REG:  u8 = 0x0B;
const CMD_READ_FIFO: u8 = 0x0D;

// Soft-reset key (ASCII 'R').
const SOFT_RESET_KEY: u8 = 0x52;

// Per-range sensitivity (g/LSB), typical.
const SENSITIVITY_G_PER_LSB: [f32; 3] = [0.001, 0.002, 0.004255];

// Register addresses.
const REG_DEVID_AD:       u8 = 0x00;
const REG_DEVID_MST:      u8 = 0x01;
const REG_PARTID:         u8 = 0x02;
const REG_XDATA:          u8 = 0x08;
const REG_YDATA:          u8 = 0x09;
const REG_ZDATA:          u8 = 0x0A;
const REG_STATUS:         u8 = 0x0B;
const REG_FIFO_ENTRIES_L: u8 = 0x0C;
const REG_FIFO_ENTRIES_H: u8 = 0x0D;
const REG_XDATA_L:        u8 = 0x0E;
const REG_XDATA_H:        u8 = 0x0F;
const REG_YDATA_L:        u8 = 0x10;
const REG_YDATA_H:        u8 = 0x11;
const REG_ZDATA_L:        u8 = 0x12;
const REG_ZDATA_H:        u8 = 0x13;
const REG_TEMP_L:         u8 = 0x14;
const REG_TEMP_H:         u8 = 0x15;
const REG_SOFT_RESET:     u8 = 0x1F;
const REG_THRESH_ACT_L:   u8 = 0x20;
const REG_THRESH_ACT_H:   u8 = 0x21;
const REG_TIME_ACT:       u8 = 0x22;
const REG_THRESH_INACT_L: u8 = 0x23;
const REG_THRESH_INACT_H: u8 = 0x24;
const REG_TIME_INACT_L:   u8 = 0x25;
const REG_TIME_INACT_H:   u8 = 0x26;
const REG_ACT_INACT_CTL:  u8 = 0x27;
const REG_FIFO_CONTROL:   u8 = 0x28;
const REG_FIFO_SAMPLES:   u8 = 0x29;
const REG_INTMAP1:        u8 = 0x2A;
const REG_INTMAP2:        u8 = 0x2B;
const REG_FILTER_CTL:     u8 = 0x2C;
const REG_POWER_CTL:      u8 = 0x2D;
const REG_SELF_TEST:      u8 = 0x2E;

const DEVID_AD_VALUE:  u8 = 0xAD;
const DEVID_MST_VALUE: u8 = 0x1D;
const PARTID_VALUE:    u8 = 0xF2;

// FILTER_CTL reset value: RANGE=±2 g, HALF_BW=1, ODR=100 Hz.
const FILTER_CTL_DEFAULT: u8 = 0x13;
// POWER_CTL measurement-mode value: MEASURE=10.
const POWER_CTL_MEASURE:  u8 = 0x02;

const STATUS_AWAKE:     u8 = 0x40;
const STATUS_DATA_READY: u8 = 0x01;

/// Interrupt source: data ready.
pub const SOURCE_DATA_READY: u8 = 0;
/// Interrupt source: FIFO ready.
pub const SOURCE_FIFO_READY: u8 = 1;
/// Interrupt source: FIFO watermark.
pub const SOURCE_FIFO_WATERMARK: u8 = 2;
/// Interrupt source: FIFO overrun.
pub const SOURCE_FIFO_OVERRUN: u8 = 3;
/// Interrupt source: activity.
pub const SOURCE_ACT: u8 = 4;
/// Interrupt source: inactivity.
pub const SOURCE_INACT: u8 = 5;
/// Interrupt source: AWAKE.
pub const SOURCE_AWAKE: u8 = 6;

/// Noise mode: normal (lowest power, highest noise).
pub const NOISE_NORMAL: u8 = 0;
/// Noise mode: low noise.
pub const NOISE_LOW: u8 = 1;
/// Noise mode: ultralow noise.
pub const NOISE_ULTRALOW: u8 = 2;

/// Link/loop mode: default (host clears via STATUS read).
pub const LINKLOOP_DEFAULT: u8 = 0;
/// Link/loop mode: linked.
pub const LINKLOOP_LINKED: u8 = 1;
/// Link/loop mode: loop.
pub const LINKLOOP_LOOP: u8 = 3;

/// FIFO mode: disabled.
pub const FIFO_DISABLED: u8 = 0;
/// FIFO mode: oldest-saved (oldest entries discarded on overflow).
pub const FIFO_OLDEST_SAVED: u8 = 1;
/// FIFO mode: stream (newest entries discarded on overflow).
pub const FIFO_STREAM: u8 = 2;
/// FIFO mode: triggered.
pub const FIFO_TRIGGERED: u8 = 3;

/// FIFO entry axis code: X.
pub const AXIS_X: u8 = 0;
/// FIFO entry axis code: Y.
pub const AXIS_Y: u8 = 1;
/// FIFO entry axis code: Z.
pub const AXIS_Z: u8 = 2;
/// FIFO entry axis code: temperature.
pub const AXIS_TEMP: u8 = 3;

/// ADXL362 minimal driver — 3-axis acceleration in *g*.
///
/// Performs DEVID verification and writes the reset FILTER_CTL and
/// POWER_CTL values at construction.
pub struct Adxl362Minimal<SPI: SpiDevice> {
    spi: SPI,
    range_bits: u8,
    odr_hz: f32,
}

impl<SPI: SpiDevice> Adxl362Minimal<SPI> {
    /// Create a new `Adxl362Minimal` and run the chip's power-up sequence.
    ///
    /// # Arguments
    /// * `spi` — Configured `SpiDevice` bound to the chip's CS pin.
    pub fn new(mut spi: SPI) -> Result<Self, SPI::Error> {
        let mut s = Self {
            spi,
            range_bits: 0x00,
            odr_hz: 100.0,
        };
        s.init()?;
        Ok(s)
    }

    /// Run the chip's full power-up sequence.
    pub fn init(&mut self) -> Result<(), SPI::Error> {
        delay_ms(5);
        let mut ids = [0u8; 3];
        read_burst(&mut self.spi, REG_DEVID_AD, &mut ids)?;
        if ids[0] != DEVID_AD_VALUE {
            panic!("ADXL362 DEVID_AD: expected 0x{:02X}, got 0x{:02X}", DEVID_AD_VALUE, ids[0]);
        }
        if ids[1] != DEVID_MST_VALUE {
            panic!("ADXL362 DEVID_MST: expected 0x{:02X}, got 0x{:02X}", DEVID_MST_VALUE, ids[1]);
        }
        if ids[2] != PARTID_VALUE {
            panic!("ADXL362 PARTID: expected 0x{:02X}, got 0x{:02X}", PARTID_VALUE, ids[2]);
        }
        write_reg(&mut self.spi, REG_FILTER_CTL, FILTER_CTL_DEFAULT)?;
        write_reg(&mut self.spi, REG_POWER_CTL, POWER_CTL_MEASURE)?;
        delay_ms(40);
        Ok(())
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns (x, y, z) in *g*.
    pub fn read(&mut self) -> Result<(f32, f32, f32), SPI::Error> {
        let mut raw = [0u8; 6];
        read_burst(&mut self.spi, REG_XDATA_L, &mut raw)?;
        let rx = sign_extend_12((((raw[1] & 0x0F) as u16) << 8) | raw[0] as u16);
        let ry = sign_extend_12((((raw[3] & 0x0F) as u16) << 8) | raw[2] as u16);
        let rz = sign_extend_12((((raw[5] & 0x0F) as u16) << 8) | raw[4] as u16);
        let sens = self.sensitivity();
        Ok((rx as f32 * sens, ry as f32 * sens, rz as f32 * sens))
    }

    fn sensitivity(&self) -> f32 {
        match self.range_bits {
            0x40 => SENSITIVITY_G_PER_LSB[1],
            0x80 | 0xC0 => SENSITIVITY_G_PER_LSB[2],
            _ => SENSITIVITY_G_PER_LSB[0],
        }
    }
}

fn sign_extend_12(v: u16) -> i16 {
    let v = v & 0x0FFF;
    if v & 0x0800 != 0 {
        (v | 0xF000) as i16
    } else {
        v as i16
    }
}

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    {
        let _ = ms;
    }
}

fn write_reg<SPI: SpiDevice>(spi: &mut SPI, reg: u8, value: u8) -> Result<(), SPI::Error> {
    spi.transaction(&mut [
        Operation::Write(&[CMD_WRITE_REG, reg & 0x3F, value]),
    ])
}

fn read_reg<SPI: SpiDevice>(spi: &mut SPI, reg: u8) -> Result<u8, SPI::Error> {
    let mut out = [0u8; 1];
    read_burst(spi, reg, &mut out)?;
    Ok(out[0])
}

fn read_burst<SPI: SpiDevice>(spi: &mut SPI, reg: u8, buf: &mut [u8]) -> Result<(), SPI::Error> {
    spi.transaction(&mut [
        Operation::Write(&[CMD_READ_REG, reg & 0x3F]),
        Operation::Read(buf),
    ])
}

fn read_fifo<SPI: SpiDevice>(spi: &mut SPI, buf: &mut [u8]) -> Result<(), SPI::Error> {
    spi.transaction(&mut [
        Operation::Write(&[CMD_READ_FIFO]),
        Operation::Read(buf),
    ])
}

fn intmap_bit(source: u8) -> u8 {
    match source {
        SOURCE_DATA_READY    => 0x01,
        SOURCE_FIFO_READY    => 0x02,
        SOURCE_FIFO_WATERMARK => 0x04,
        SOURCE_FIFO_OVERRUN  => 0x08,
        SOURCE_ACT           => 0x10,
        SOURCE_INACT         => 0x20,
        SOURCE_AWAKE         => 0x40,
        _ => 0,
    }
}

/// ADXL362 full driver — extends minimal with the full chip API.
///
/// Re-exports [`Adxl362Minimal::read`] as a one-line delegate, then adds
/// Full-only methods below.
pub struct Adxl362Full<SPI: SpiDevice> {
    inner: Adxl362Minimal<SPI>,
}

impl<SPI: SpiDevice> Adxl362Full<SPI> {
    /// Create a new `Adxl362Full`.
    pub fn new(spi: SPI) -> Result<Self, SPI::Error> {
        Ok(Self { inner: Adxl362Minimal::new(spi)? })
    }

    /// Re-run the chip's power-up sequence.
    pub fn init(&mut self) -> Result<(), SPI::Error> {
        self.inner.init()
    }

    /// Read 3-axis linear acceleration. Delegates to [`Adxl362Minimal::read`].
    pub fn read(&mut self) -> Result<(f32, f32, f32), SPI::Error> {
        self.inner.read()
    }

    /// Return raw device-ID bytes (DEVID_AD, DEVID_MST, PARTID, REVID).
    pub fn device_id(&mut self) -> Result<(u8, u8, u8, u8), SPI::Error> {
        let mut ids = [0u8; 4];
        read_burst(&mut self.inner.spi, REG_DEVID_AD, &mut ids)?;
        Ok((ids[0], ids[1], ids[2], ids[3]))
    }

    /// Soft-reset the chip (writes 0x52 to SOFT_RESET, waits ≥0.5 ms).
    pub fn soft_reset(&mut self) -> Result<(), SPI::Error> {
        write_reg(&mut self.inner.spi, REG_SOFT_RESET, SOFT_RESET_KEY)?;
        delay_ms(1);
        self.inner.range_bits = 0x00;
        self.inner.odr_hz = 100.0;
        Ok(())
    }

    /// Set the measurement range to ±2/±4/±8 g.
    pub fn set_range(&mut self, range_g: u8) -> Result<(), SPI::Error> {
        let code = match range_g {
            2 => 0x00,
            4 => 0x40,
            8 => 0x80,
            _ => return Ok(()),
        };
        let mut f = read_reg(&mut self.inner.spi, REG_FILTER_CTL)?;
        f = (f & 0x3F) | (code & 0xC0);
        write_reg(&mut self.inner.spi, REG_FILTER_CTL, f)?;
        self.inner.range_bits = code;
        if self.inner.odr_hz > 0.0 {
            delay_ms((1000.0 / self.inner.odr_hz + 1.0) as u32);
        }
        Ok(())
    }

    /// Set the output data rate to the nearest supported value (12.5–400 Hz).
    pub fn set_odr(&mut self, odr_hz: f32) -> Result<(), SPI::Error> {
        let codes: [(u8, f32); 8] = [
            (0x00, 12.5),
            (0x01, 25.0),
            (0x02, 50.0),
            (0x03, 100.0),
            (0x04, 200.0),
            (0x05, 400.0),
            (0x06, 400.0),
            (0x07, 400.0),
        ];
        let mut best_code = codes[0].0;
        let mut best_rate = codes[0].1;
        let mut best_diff = libm::fabsf(best_rate - odr_hz);
        for &(code, rate) in codes.iter() {
            let d = libm::fabsf(rate - odr_hz);
            if d < best_diff {
                best_code = code;
                best_rate = rate;
                best_diff = d;
            }
        }
        let mut f = read_reg(&mut self.inner.spi, REG_FILTER_CTL)?;
        f = (f & 0xF8) | (best_code & 0x07);
        write_reg(&mut self.inner.spi, REG_FILTER_CTL, f)?;
        self.inner.odr_hz = best_rate;
        Ok(())
    }

    /// Set FILTER_CTL.HALF_BW (antialiasing bandwidth = ODR/4 or ODR/2).
    pub fn set_half_bandwidth(&mut self, enabled: bool) -> Result<(), SPI::Error> {
        let mut f = read_reg(&mut self.inner.spi, REG_FILTER_CTL)?;
        if enabled { f |= 0x10; } else { f &= !0x10; }
        write_reg(&mut self.inner.spi, REG_FILTER_CTL, f)
    }

    /// Set POWER_CTL.LOW_NOISE (0=normal, 1=low, 2=ultralow noise).
    pub fn set_noise_mode(&mut self, mode: u8) -> Result<(), SPI::Error> {
        let mut p = read_reg(&mut self.inner.spi, REG_POWER_CTL)?;
        p = (p & 0xCF) | ((mode << 4) & 0x30);
        write_reg(&mut self.inner.spi, REG_POWER_CTL, p)
    }

    /// Set POWER_CTL.WAKEUP (270 nA idle mode).
    pub fn set_wakeup_mode(&mut self, enabled: bool) -> Result<(), SPI::Error> {
        let mut p = read_reg(&mut self.inner.spi, REG_POWER_CTL)?;
        if enabled { p |= 0x08; } else { p &= !0x08; }
        write_reg(&mut self.inner.spi, REG_POWER_CTL, p)
    }

    /// Set POWER_CTL.AUTOSLEEP; effective only in linked/loop mode.
    pub fn set_autosleep(&mut self, enabled: bool) -> Result<(), SPI::Error> {
        let mut p = read_reg(&mut self.inner.spi, REG_POWER_CTL)?;
        if enabled { p |= 0x04; } else { p &= !0x04; }
        write_reg(&mut self.inner.spi, REG_POWER_CTL, p)
    }

    /// Set POWER_CTL.EXT_CLK; INT1 is repurposed as clock input.
    pub fn set_external_clock(&mut self, enabled: bool) -> Result<(), SPI::Error> {
        let mut p = read_reg(&mut self.inner.spi, REG_POWER_CTL)?;
        if enabled { p |= 0x40; } else { p &= !0x40; }
        write_reg(&mut self.inner.spi, REG_POWER_CTL, p)
    }

    /// Set FILTER_CTL.EXT_SAMPLE; INT2 is repurposed as sync trigger input.
    pub fn set_external_sample_trigger(&mut self, enabled: bool) -> Result<(), SPI::Error> {
        let mut f = read_reg(&mut self.inner.spi, REG_FILTER_CTL)?;
        if enabled { f |= 0x08; } else { f &= !0x08; }
        write_reg(&mut self.inner.spi, REG_FILTER_CTL, f)
    }

    /// Read 3-axis acceleration using the 8-bit XDATA/YDATA/ZDATA registers.
    pub fn read_8bit(&mut self) -> Result<(f32, f32, f32), SPI::Error> {
        let mut raw = [0u8; 3];
        read_burst(&mut self.inner.spi, REG_XDATA, &mut raw)?;
        let s8 = |v: u8| -> i8 { if v & 0x80 != 0 { (v - 256) as i8 } else { v as i8 } };
        let sens = self.inner.sensitivity() * 16.0;
        Ok((s8(raw[0]) as f32 * sens, s8(raw[1]) as f32 * sens, s8(raw[2]) as f32 * sens))
    }

    /// Read the on-chip temperature sensor (typical bias/sensitivity).
    pub fn temperature(&mut self) -> Result<f32, SPI::Error> {
        let mut raw = [0u8; 2];
        read_burst(&mut self.inner.spi, REG_TEMP_L, &mut raw)?;
        let raw12 = sign_extend_12((((raw[1] & 0x0F) as u16) << 8) | raw[0] as u16);
        Ok(25.0 + (raw12 as f32 - 350.0) * 0.065)
    }

    /// Read the raw STATUS register byte.
    pub fn status(&mut self) -> Result<u8, SPI::Error> {
        read_reg(&mut self.inner.spi, REG_STATUS)
    }

    /// Return STATUS.AWAKE.
    pub fn awake(&mut self) -> Result<bool, SPI::Error> {
        Ok(self.status()? & STATUS_AWAKE != 0)
    }

    /// Return STATUS.DATA_READY.
    pub fn data_ready(&mut self) -> Result<bool, SPI::Error> {
        Ok(self.status()? & STATUS_DATA_READY != 0)
    }

    /// Return the 10-bit FIFO entry count (0–512).
    pub fn fifo_entries(&mut self) -> Result<u16, SPI::Error> {
        let lo = read_reg(&mut self.inner.spi, REG_FIFO_ENTRIES_L)?;
        let hi = read_reg(&mut self.inner.spi, REG_FIFO_ENTRIES_H)?;
        Ok((lo as u16) | (((hi & 0x03) as u16) << 8))
    }

    /// Configure the FIFO mode, optional temperature storage, and watermark.
    pub fn configure_fifo(&mut self, mode: u8, store_temp: bool, watermark: u16) -> Result<(), SPI::Error> {
        let fc = (mode & 0x03) | (((watermark >> 8) as u8) << 3) | (if store_temp { 0x04 } else { 0x00 });
        write_reg(&mut self.inner.spi, REG_FIFO_CONTROL, fc)?;
        write_reg(&mut self.inner.spi, REG_FIFO_SAMPLES, watermark as u8)
    }

    /// Read all available FIFO entries.
    pub fn read_fifo(&mut self) -> Result<heapless::Vec<(u8, f32), 512>, SPI::Error> {
        let n = self.fifo_entries()?;
        if n == 0 {
            return Ok(heapless::Vec::new());
        }
        let mut raw = [0u8; 1024];
        let bytes = (n as usize) * 2;
        let len = if bytes > raw.len() { raw.len() } else { bytes };
        read_fifo(&mut self.inner.spi, &mut raw[..len])?;
        let sens = self.inner.sensitivity();
        let mut out = heapless::Vec::new();
        for i in 0..(n as usize) {
            let raw16 = ((raw[2 * i + 1] as u16) << 8) | raw[2 * i] as u16;
            let axis = ((raw16 >> 14) & 0x03) as u8;
            let raw12 = sign_extend_12(raw16 & 0x0FFF);
            let value = if axis == AXIS_TEMP {
                25.0 + (raw12 as f32 - 350.0) * 0.065
            } else {
                raw12 as f32 * sens
            };
            let _ = out.push((axis, value));
        }
        Ok(out)
    }

    /// Set the activity threshold in *g* (clamped to 10-bit range).
    pub fn set_activity_threshold(&mut self, threshold_g: f32, referenced: bool) -> Result<(), SPI::Error> {
        let sens = self.inner.sensitivity();
        let mut raw = libm::roundf(threshold_g / sens) as i32;
        if raw < 0 { raw = 0; }
        if raw > 0x3FF { raw = 0x3FF; }
        write_reg(&mut self.inner.spi, REG_THRESH_ACT_L, raw as u8)?;
        write_reg(&mut self.inner.spi, REG_THRESH_ACT_H, ((raw >> 8) & 0x03) as u8)?;
        let mut aic = read_reg(&mut self.inner.spi, REG_ACT_INACT_CTL)?;
        if referenced { aic |= 0x02; } else { aic &= !0x02; }
        write_reg(&mut self.inner.spi, REG_ACT_INACT_CTL, aic)
    }

    /// Set the activity-time filter (0–255 samples).
    pub fn set_activity_time(&mut self, samples: u8) -> Result<(), SPI::Error> {
        write_reg(&mut self.inner.spi, REG_TIME_ACT, samples)
    }

    /// Set the inactivity threshold in *g* (clamped to 10-bit range).
    pub fn set_inactivity_threshold(&mut self, threshold_g: f32, referenced: bool) -> Result<(), SPI::Error> {
        let sens = self.inner.sensitivity();
        let mut raw = libm::roundf(threshold_g / sens) as i32;
        if raw < 0 { raw = 0; }
        if raw > 0x3FF { raw = 0x3FF; }
        write_reg(&mut self.inner.spi, REG_THRESH_INACT_L, raw as u8)?;
        write_reg(&mut self.inner.spi, REG_THRESH_INACT_H, ((raw >> 8) & 0x03) as u8)?;
        let mut aic = read_reg(&mut self.inner.spi, REG_ACT_INACT_CTL)?;
        if referenced { aic |= 0x08; } else { aic &= !0x08; }
        write_reg(&mut self.inner.spi, REG_ACT_INACT_CTL, aic)
    }

    /// Set the inactivity-time filter (0–65535 samples).
    pub fn set_inactivity_time(&mut self, samples: u16) -> Result<(), SPI::Error> {
        write_reg(&mut self.inner.spi, REG_TIME_INACT_L, (samples & 0xFF) as u8)?;
        write_reg(&mut self.inner.spi, REG_TIME_INACT_H, ((samples >> 8) & 0xFF) as u8)
    }

    /// Set ACT_INACT_CTL.ACT_EN.
    pub fn enable_activity_detection(&mut self, enabled: bool) -> Result<(), SPI::Error> {
        let mut aic = read_reg(&mut self.inner.spi, REG_ACT_INACT_CTL)?;
        if enabled { aic |= 0x01; } else { aic &= !0x01; }
        write_reg(&mut self.inner.spi, REG_ACT_INACT_CTL, aic)
    }

    /// Set ACT_INACT_CTL.INACT_EN.
    pub fn enable_inactivity_detection(&mut self, enabled: bool) -> Result<(), SPI::Error> {
        let mut aic = read_reg(&mut self.inner.spi, REG_ACT_INACT_CTL)?;
        if enabled { aic |= 0x04; } else { aic &= !0x04; }
        write_reg(&mut self.inner.spi, REG_ACT_INACT_CTL, aic)
    }

    /// Set ACT_INACT_CTL.LINKLOOP (0=default, 1=linked, 3=loop).
    pub fn set_link_loop_mode(&mut self, mode: u8) -> Result<(), SPI::Error> {
        let mut aic = read_reg(&mut self.inner.spi, REG_ACT_INACT_CTL)?;
        aic = (aic & 0xCF) | ((mode << 4) & 0x30);
        write_reg(&mut self.inner.spi, REG_ACT_INACT_CTL, aic)
    }

    /// Map one interrupt source to the named INT pin.
    pub fn set_interrupt(&mut self, pin: u8, source: u8, enabled: bool) -> Result<(), SPI::Error> {
        let reg = if pin == 1 { REG_INTMAP1 } else { REG_INTMAP2 };
        let mut cur = read_reg(&mut self.inner.spi, reg)?;
        let bit = intmap_bit(source);
        if enabled { cur |= bit; } else { cur &= !bit; }
        write_reg(&mut self.inner.spi, reg, cur)
    }

    /// Set the active-low polarity for one INT pin.
    pub fn set_interrupt_polarity(&mut self, pin: u8, active_low: bool) -> Result<(), SPI::Error> {
        let reg = if pin == 1 { REG_INTMAP1 } else { REG_INTMAP2 };
        let mut cur = read_reg(&mut self.inner.spi, reg)?;
        if active_low { cur |= 0x80; } else { cur &= !0x80; }
        write_reg(&mut self.inner.spi, reg, cur)
    }

    /// Enable or disable the electrostatic self-test force on all axes.
    pub fn self_test(&mut self, enabled: bool) -> Result<(), SPI::Error> {
        let mut st = read_reg(&mut self.inner.spi, REG_SELF_TEST)?;
        if enabled { st |= 0x01; } else { st &= !0x01; }
        write_reg(&mut self.inner.spi, REG_SELF_TEST, st)?;
        if enabled && self.inner.odr_hz > 0.0 {
            delay_ms((4000.0 / self.inner.odr_hz + 1.0) as u32);
        }
        Ok(())
    }
}