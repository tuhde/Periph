//! LPS28DFW — dual full-scale digital barometer (STMicroelectronics).
//!
//! Communicates over I²C at address 0x5C (SA0=GND) or 0x5D (SA0=VDD).
//! 24-bit pressure (signed) and 16-bit temperature (signed, 0.01 °C LSB).
//!
//! ## Constants
//!
//! ODR: [`ODR_POWER_DOWN`] through [`ODR_200_HZ`]
//! AVG: [`AVG_4`] through [`AVG_512`]
//! Full-scale: [`FS_MODE_1`], [`FS_MODE_2`]
//! IIR filter: [`LFPF_ODR_OVER_4`], [`LFPF_ODR_OVER_9`]
//! FIFO mode: [`FIFO_BYPASS`] through [`FIFO_CONTINUOUS_TO_FIFO`]
//! Status flags: [`STATUS_P_DA`], [`STATUS_T_DA`], [`STATUS_P_OR`], [`STATUS_T_OR`]

use embedded_hal::i2c::I2c;

const REG_INTERRUPT_CFG: u8 = 0x0B;
const REG_THS_P_L: u8 = 0x0C;
const REG_THS_P_H: u8 = 0x0D;
const REG_WHO_AM_I: u8 = 0x0F;
const REG_CTRL_REG1: u8 = 0x10;
const REG_CTRL_REG2: u8 = 0x11;
const REG_STATUS: u8 = 0x27;
const REG_PRESS_OUT_XL: u8 = 0x28;
const REG_TEMP_OUT_L: u8 = 0x2B;
const REG_RPDS_L: u8 = 0x1A;
const REG_RPDS_H: u8 = 0x1B;
const REG_FIFO_CTRL: u8 = 0x14;
const REG_FIFO_WTM: u8 = 0x15;
const REG_FIFO_STATUS1: u8 = 0x25;
const REG_FIFO_DATA_PRESS_XL: u8 = 0x78;

const CHIP_ID: u8 = 0xB4;
const BOOT_WAIT_MS: u32 = 2;

/// ODR code: power-down / one-shot.
pub const ODR_POWER_DOWN: u8 = 0x00;
/// ODR code: 1 Hz.
pub const ODR_1_HZ: u8 = 0x01;
/// ODR code: 4 Hz.
pub const ODR_4_HZ: u8 = 0x02;
/// ODR code: 10 Hz.
pub const ODR_10_HZ: u8 = 0x03;
/// ODR code: 25 Hz.
pub const ODR_25_HZ: u8 = 0x04;
/// ODR code: 50 Hz.
pub const ODR_50_HZ: u8 = 0x05;
/// ODR code: 75 Hz.
pub const ODR_75_HZ: u8 = 0x06;
/// ODR code: 100 Hz.
pub const ODR_100_HZ: u8 = 0x07;
/// ODR code: 200 Hz.
pub const ODR_200_HZ: u8 = 0x08;

/// Averaging: 4 samples.
pub const AVG_4: u8 = 0x00;
/// Averaging: 8 samples.
pub const AVG_8: u8 = 0x01;
/// Averaging: 16 samples.
pub const AVG_16: u8 = 0x02;
/// Averaging: 32 samples.
pub const AVG_32: u8 = 0x03;
/// Averaging: 64 samples.
pub const AVG_64: u8 = 0x04;
/// Averaging: 128 samples.
pub const AVG_128: u8 = 0x05;
/// Averaging: 512 samples.
pub const AVG_512: u8 = 0x07;

/// Full-scale mode 1 (0–1260 hPa, 4096 LSB/hPa).
pub const FS_MODE_1: u8 = 0;
/// Full-scale mode 2 (0–4060 hPa, 2048 LSB/hPa).
pub const FS_MODE_2: u8 = 1;

/// IIR low-pass filter bandwidth: ODR/4.
pub const LFPF_ODR_OVER_4: u8 = 0;
/// IIR low-pass filter bandwidth: ODR/9.
pub const LFPF_ODR_OVER_9: u8 = 1;

/// FIFO mode: bypass.
pub const FIFO_BYPASS: u8 = 0;
/// FIFO mode: FIFO.
pub const FIFO_FIFO: u8 = 1;
/// FIFO mode: continuous.
pub const FIFO_CONTINUOUS: u8 = 2;
/// FIFO mode: bypass-to-FIFO.
pub const FIFO_BYPASS_TO_FIFO: u8 = 4;
/// FIFO mode: bypass-to-continuous.
pub const FIFO_BYPASS_TO_CONTINUOUS: u8 = 5;
/// FIFO mode: continuous-to-FIFO.
pub const FIFO_CONTINUOUS_TO_FIFO: u8 = 6;

/// STATUS flag: new pressure data available.
pub const STATUS_P_DA: u8 = 0x01;
/// STATUS flag: new temperature data available.
pub const STATUS_T_DA: u8 = 0x02;
/// STATUS flag: pressure overrun.
pub const STATUS_P_OR: u8 = 0x10;
/// STATUS flag: temperature overrun.
pub const STATUS_T_OR: u8 = 0x20;

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_reg_bytes<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

/// LPS28DFW minimal driver — pressure (hPa) and temperature (°C).
///
/// Default configuration: Mode 1, AVG=16, ODR=25 Hz, BDU=1, IIR filter enabled.
pub struct Lps28dfwMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    fs_mode: u8,
    odr: u8,
    avg: u8,
    lpf_en: u8,
    lpf_cfg: u8,
    bdu: u8,
}

impl<I2C: I2c> Lps28dfwMinimal<I2C> {
    /// Create a new `Lps28dfwMinimal`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x5C or 0x5D).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut s = Self {
            i2c, addr,
            fs_mode: 0, odr: 0x04, avg: 0x02,
            lpf_en: 1, lpf_cfg: 0, bdu: 1,
        };
        let ctrl2 = (s.fs_mode << 6) | (s.lpf_cfg << 5) | (s.lpf_en << 4) | (s.bdu << 3);
        write_reg(&mut s.i2c, s.addr, REG_CTRL_REG2, ctrl2)?;
        let ctrl1 = (s.odr << 3) | (s.avg & 0x07);
        write_reg(&mut s.i2c, s.addr, REG_CTRL_REG1, ctrl1)?;
        Ok(s)
    }

    fn read_pressure_raw(&mut self) -> Result<i32, I2C::Error> {
        let mut buf = [0u8; 3];
        read_reg_bytes(&mut self.i2c, self.addr, REG_PRESS_OUT_XL, &mut buf)?;
        let v = ((buf[2] as i32) << 16) | ((buf[1] as i32) << 8) | (buf[0] as i32);
        Ok(if v & 0x800000 != 0 { v | -0x1000000 } else { v })
    }

    fn read_temperature_raw(&mut self) -> Result<i16, I2C::Error> {
        let mut buf = [0u8; 2];
        read_reg_bytes(&mut self.i2c, self.addr, REG_TEMP_OUT_L, &mut buf)?;
        Ok(i16::from_be_bytes([buf[0], buf[1]]))
    }

    /// Read absolute pressure in hPa.
    pub fn read_pressure(&mut self) -> Result<f32, I2C::Error> {
        let raw = self.read_pressure_raw()?;
        let sens = if self.fs_mode == 0 { 4096.0_f32 } else { 2048.0_f32 };
        Ok(raw as f32 / sens)
    }

    /// Read temperature in °C.
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        let raw = self.read_temperature_raw()?;
        Ok(raw as f32 / 100.0)
    }
}

/// LPS28DFW full driver — extends minimal with full configuration, FIFO, threshold, etc.
pub struct Lps28dfwFull<I2C> {
    inner: Lps28dfwMinimal<I2C>,
}

impl<I2C: I2c> Lps28dfwFull<I2C> {
    /// Create a new `Lps28dfwFull`.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Lps28dfwMinimal::new(i2c, addr)? })
    }

    /// Set output data rate, averaging, full-scale mode, and IIR filter.
    pub fn configure(&mut self, odr: u8, avg: u8, fs_mode: u8, lpf_en: bool, lpf_cfg: u8) -> Result<(), I2C::Error> {
        self.inner.odr = odr;
        self.inner.avg = avg;
        self.inner.fs_mode = fs_mode;
        self.inner.lpf_en = if lpf_en { 1 } else { 0 };
        self.inner.lpf_cfg = lpf_cfg;
        let ctrl2 = (self.inner.fs_mode << 6) | (self.inner.lpf_cfg << 5) | (self.inner.lpf_en << 4) | (self.inner.bdu << 3);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, ctrl2)?;
        let ctrl1 = (self.inner.odr << 3) | (self.inner.avg & 0x07);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, ctrl1)
    }

    /// Burst-read pressure and temperature.
    pub fn read(&mut self) -> Result<(f32, f32), I2C::Error> {
        let mut buf = [0u8; 5];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_PRESS_OUT_XL, &mut buf)?;
        let p = ((buf[2] as i32) << 16) | ((buf[1] as i32) << 8) | (buf[0] as i32);
        let p = if p & 0x800000 != 0 { p | -0x1000000 } else { p };
        let t = i16::from_be_bytes([buf[3], buf[4]]);
        let sens = if self.inner.fs_mode == 0 { 4096.0_f32 } else { 2048.0_f32 };
        Ok((p as f32 / sens, t as f32 / 100.0))
    }

    /// Check whether STATUS.P_DA is set.
    pub fn is_data_ready(&mut self) -> Result<bool, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_STATUS, &mut buf)?;
        Ok(buf[0] & STATUS_P_DA != 0)
    }

    /// Trigger a one-shot measurement (with ODR=0000) and read the result.
    pub fn read_oneshot(&mut self) -> Result<(f32, f32), I2C::Error> {
        let mut saved = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, &mut saved)?;
        let saved_odr = saved[0] >> 3;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, self.inner.avg & 0x07)?;
        let mut c2 = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, &mut c2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, c2[0] | 0x01)?;
        let result = self.read();
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, (saved_odr << 3) | (self.inner.avg & 0x07))?;
        result
    }

    /// Program the one-point calibration offset (RPDS).
    pub fn set_offset(&mut self, offset_hpa: f32) -> Result<(), I2C::Error> {
        let sens = if self.inner.fs_mode == 0 { 4096.0_f32 } else { 2048.0_f32 };
        let mut raw = (offset_hpa * sens) as i32;
        if raw < 0 { raw += 0x10000; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RPDS_L, (raw & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RPDS_H, ((raw >> 8) & 0xFF) as u8)
    }

    /// Issue a software reset and wait for the chip to reboot.
    pub fn softreset(&mut self) -> Result<(), I2C::Error> {
        let mut c2 = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, &mut c2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, c2[0] | 0x02)
    }

    /// Configure FIFO mode, watermark level, and stop-on-watermark.
    pub fn fifo_configure(&mut self, mode: u8, wtm: u8, stop_on_wtm: bool) -> Result<(), I2C::Error> {
        if mode == FIFO_BYPASS {
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_CTRL, 0x00)?;
        }
        let trig = if mode >= 4 { 1 } else { 0 };
        let f_mode = mode & 0x03;
        let ctrl = (trig << 2) | ((if stop_on_wtm { 1 } else { 0 }) << 3) | f_mode;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_CTRL, ctrl)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_WTM, wtm & 0x7F)
    }

    /// Drain up to `count` pressure samples from the FIFO.
    pub fn fifo_read(&mut self, count: u8, buf: &mut [f32]) -> Result<(), I2C::Error> {
        let n = if count as usize > buf.len() { buf.len() as u8 } else { count };
        if n == 0 { return Ok(()); }
        let mut raw = [0u8; 384];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_FIFO_DATA_PRESS_XL, &mut raw[..(n as usize) * 3])?;
        let sens = if self.inner.fs_mode == 0 { 4096.0_f32 } else { 2048.0_f32 };
        for i in 0..n as usize {
            let b = i * 3;
            let v = ((raw[b + 2] as i32) << 16) | ((raw[b + 1] as i32) << 8) | (raw[b] as i32);
            let v = if v & 0x800000 != 0 { v | -0x1000000 } else { v };
            buf[i] = v as f32 / sens;
        }
        Ok(())
    }

    /// Return the number of unread samples in the FIFO.
    pub fn fifo_level(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_FIFO_STATUS1, &mut buf)?;
        Ok(buf[0])
    }

    /// Program the pressure threshold and enable interrupt sources.
    pub fn set_threshold(&mut self, threshold_hpa: f32, high: bool, low: bool) -> Result<(), I2C::Error> {
        let sens = if self.inner.fs_mode == 0 { 16.0_f32 } else { 8.0_f32 };
        let mut raw = (threshold_hpa * sens) as i32;
        if raw < 0 { raw = 0; }
        if raw > 0x7FFF { raw = 0x7FFF; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_THS_P_L, (raw & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_THS_P_H, ((raw >> 8) & 0x7F) as u8)?;
        let mut cfg = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG, &mut cfg)?;
        let mut v = cfg[0] & !0x03;
        if high { v |= 0x01; }
        if low  { v |= 0x02; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG, v)
    }

    /// Read the WHO_AM_I register.
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_WHO_AM_I, &mut buf)?;
        Ok(buf[0])
    }

    /// Compute altitude above sea level from the current pressure.
    pub fn altitude(&mut self, sea_level_hpa: f32) -> Result<f32, I2C::Error> {
        let p = self.inner.read_pressure()?;
        Ok(44330.0 * (1.0 - libm::powf(p / sea_level_hpa, 1.0 / 5.255)))
    }

    /// Read calibrated pressure (delegated from Minimal).
    pub fn read_pressure(&mut self) -> Result<f32, I2C::Error> { self.inner.read_pressure() }

    /// Read calibrated temperature (delegated from Minimal).
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> { self.inner.read_temperature() }
}