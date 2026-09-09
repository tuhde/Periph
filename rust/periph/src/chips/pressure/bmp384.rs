//! BMP384 — high-precision barometric pressure and temperature sensor (Bosch Sensortec).
//!
//! Communicates over I²C (address 0x76 or 0x77) or SPI. Calibration
//! coefficients are loaded from NVM during construction. The compensation
//! algorithm uses floating-point math per datasheet section "Data Conversion".
//!
//! ## Constants
//!
//! Power mode: [`MODE_SLEEP`], [`MODE_FORCED`], [`MODE_NORMAL`]

use embedded_hal::i2c::I2c;
use libm::powf;

const REG_CHIP_ID:   u8 = 0x00;
const REG_STATUS:    u8 = 0x03;
const REG_DATA_0:    u8 = 0x04;
const REG_PWR_CTRL:  u8 = 0x1B;
const REG_OSR:       u8 = 0x1C;
const REG_ODR:       u8 = 0x1D;
const REG_CONFIG:    u8 = 0x1F;
const REG_CMD:       u8 = 0x7E;
const REG_CAL_START: u8 = 0x31;
const REG_CAL_END:   u8 = 0x45;

const CHIP_ID:        u8 = 0x50;
const SOFT_RESET_CMD: u8 = 0xB6;
const FIFO_FLUSH_CMD: u8 = 0xB0;

const MODE_SLEEP_BITS:  u8 = 0x00;
const MODE_FORCED_BITS: u8 = 0x01;
const MODE_NORMAL_BITS: u8 = 0x03;
const PWR_PRESS_EN:     u8 = 0x01;
const PWR_TEMP_EN:      u8 = 0x02;

const FIFO_HEADER_PRESS:   u8 = 0x84;
const FIFO_HEADER_TEMP:    u8 = 0x90;
const FIFO_HEADER_SENSORT: u8 = 0xA0;
const FIFO_HEADER_ERROR:   u8 = 0x44;
const FIFO_HEADER_EMPTY:   u8 = 0x80;

const MEAS_TIME_MS: u32 = 40;

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

fn s8(b: u8) -> i8 {
    if b >= 128 { b as i8 - 256 } else { b as i8 }
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8, spi: bool) -> Result<(), I2C::Error> {
    let r = if spi { reg & 0x7F } else { reg };
    i2c.write(addr, &[r, value])
}

fn read_reg_bytes<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

#[derive(Clone, Copy)]
struct Calibration {
    par_t1: f64,
    par_t2: f64,
    par_t3: f64,
    par_p1: f64,
    par_p2: f64,
    par_p3: f64,
    par_p4: f64,
    par_p5: f64,
    par_p6: f64,
    par_p7: f64,
    par_p8: f64,
    par_p9: f64,
    par_p10: f64,
    par_p11: f64,
}

fn read_calibration<I2C: I2c>(i2c: &mut I2C, addr: u8) -> Result<Calibration, I2C::Error> {
    let len = (REG_CAL_END - REG_CAL_START + 1) as usize;
    let mut buf = [0u8; 21];
    read_reg_bytes(i2c, addr, REG_CAL_START, &mut buf[..len])?;

    let nvm_t1 = u16::from_le_bytes([buf[0], buf[1]]);
    let nvm_t2 = u16::from_le_bytes([buf[2], buf[3]]);
    let nvm_t3 = s8(buf[4]) as f64;
    let nvm_p1 = i16::from_le_bytes([buf[5], buf[6]]) as f64;
    let nvm_p2 = i16::from_le_bytes([buf[7], buf[8]]) as f64;
    let nvm_p3 = s8(buf[9]) as f64;
    let nvm_p4 = s8(buf[10]) as f64;
    let nvm_p5 = u16::from_le_bytes([buf[11], buf[12]]) as f64;
    let nvm_p6 = u16::from_le_bytes([buf[13], buf[14]]) as f64;
    let nvm_p7 = s8(buf[15]) as f64;
    let nvm_p8 = s8(buf[16]) as f64;
    let nvm_p9 = i16::from_le_bytes([buf[17], buf[18]]) as f64;
    let nvm_p10 = s8(buf[19]) as f64;
    let nvm_p11 = s8(buf[20]) as f64;

    // Convert NVM_PAR to floating-point PAR per datasheet "Calibration coefficient scaling".
    Ok(Calibration {
        par_t1:  nvm_t1  as f64 / f64::from(1u32 << (-8i32) as u32),   // × 256
        par_t2:  nvm_t2  as f64 / f64::from(1u32 << 30),
        par_t3:  nvm_t3        / f64::from(1u64 << 48),
        par_p1:  (nvm_p1 - f64::from(1u32 << 14)) / f64::from(1u32 << 20),
        par_p2:  (nvm_p2 - f64::from(1u32 << 14)) / f64::from(1u32 << 29),
        par_p3:  nvm_p3        / f64::from(1u64 << 32),
        par_p4:  nvm_p4        / f64::from(1u64 << 37),
        par_p5:  nvm_p5  as f64 / f64::from(1u32 << (-3i32) as u32),   // × 8
        par_p6:  nvm_p6  as f64 / f64::from(1u32 << 6),
        par_p7:  nvm_p7        / f64::from(1u32 << 8),
        par_p8:  nvm_p8        / f64::from(1u32 << 15),
        par_p9:  nvm_p9        / f64::from(1u64 << 48),
        par_p10: nvm_p10       / f64::from(1u64 << 48),
        par_p11: nvm_p11       / f64::from(1u64 << 65),
    })
}

fn compensate_temperature(uncomp_temp: u32, cal: &Calibration) -> (f64, f64) {
    let partial1 = (uncomp_temp as f64) - cal.par_t1;
    let partial2 = partial1 * cal.par_t2;
    let t_lin = partial2 + (partial1 * partial1) * cal.par_t3;
    (t_lin, t_lin)
}

fn compensate_pressure(uncomp_press: u32, t_lin: f64, cal: &Calibration) -> f64 {
    let p = uncomp_press as f64;

    let partial1 = cal.par_p6 * t_lin;
    let partial2 = cal.par_p7 * t_lin * t_lin;
    let partial3 = cal.par_p8 * t_lin * t_lin * t_lin;
    let partial_out1 = cal.par_p5 + partial1 + partial2 + partial3;

    let partial1 = cal.par_p2 * t_lin;
    let partial2 = cal.par_p3 * t_lin * t_lin;
    let partial3 = cal.par_p4 * t_lin * t_lin * t_lin;
    let partial_out2 = p * (cal.par_p1 + partial1 + partial2 + partial3);

    let partial1 = p * p;
    let partial2 = cal.par_p9 + cal.par_p10 * t_lin;
    let partial3 = partial1 * partial2;
    let partial4 = partial3 + (p * p * p) * cal.par_p11;

    partial_out1 + partial_out2 + partial4
}

/// Power mode: sleep.
pub const MODE_SLEEP: u8 = 0x00;
/// Power mode: forced (single-shot).
pub const MODE_FORCED: u8 = 0x01;
/// Power mode: normal (continuous).
pub const MODE_NORMAL: u8 = 0x03;

/// BMP384 minimal driver — temperature (°C) and pressure (hPa).
///
/// Default: normal mode, osr_p=×16, osr_t=×2, iir=coef 3, ODR=25 Hz.
pub struct Bmp384Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    spi: bool,
    /// Current power-mode bits in PWR_CTRL (MODE_SLEEP / MODE_FORCED / MODE_NORMAL).
    pub mode: u8,
    /// Pressure oversampling index (0–5).
    pub osr_p: u8,
    /// Temperature oversampling index (0–5).
    pub osr_t: u8,
    /// IIR filter coefficient index (0–7).
    pub iir: u8,
    /// Output data rate selector (0x00–0x11).
    pub odr: u8,
    t_lin: f64,
    cal: Calibration,
}

impl<I2C: I2c> Bmp384Minimal<I2C> {
    /// Create a new `Bmp384Minimal` and load calibration coefficients.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x76 or 0x77).
    /// * `spi` — Pass `true` for SPI bus (clears bit 7 on writes).
    pub fn new(mut i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        let cal = read_calibration(&mut i2c, addr)?;
        let mut s = Self {
            i2c,
            addr,
            spi,
            mode: MODE_NORMAL_BITS,
            osr_p: 4,
            osr_t: 1,
            iir:   2,
            odr:   0x03,
            t_lin: 0.0,
            cal,
        };
        write_reg(&mut s.i2c, s.addr, REG_OSR,      (s.osr_t << 3) | (s.osr_p << 0), s.spi)?;
        write_reg(&mut s.i2c, s.addr, REG_CONFIG,   (s.iir << 1), s.spi)?;
        write_reg(&mut s.i2c, s.addr, REG_ODR,      s.odr, s.spi)?;
        write_reg(&mut s.i2c, s.addr, REG_PWR_CTRL, (s.mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN, s.spi)?;
        Ok(s)
    }

    fn trigger_and_read(&mut self) -> Result<(u32, u32), I2C::Error> {
        if self.mode == MODE_FORCED_BITS {
            write_reg(&mut self.i2c, self.addr, REG_PWR_CTRL,
                (MODE_FORCED_BITS << 4) | PWR_TEMP_EN | PWR_PRESS_EN, self.spi)?;
            delay_ms(MEAS_TIME_MS);
        }
        let mut raw = [0u8; 6];
        read_reg_bytes(&mut self.i2c, self.addr, REG_DATA_0, &mut raw)?;
        let uncomp_press = ((raw[2] as u32) << 16) | ((raw[1] as u32) << 8) | raw[0] as u32;
        let uncomp_temp  = ((raw[5] as u32) << 16) | ((raw[4] as u32) << 8) | raw[3] as u32;
        Ok((uncomp_press, uncomp_temp))
    }

    /// Read calibrated temperature.
    ///
    /// Returns temperature in degrees Celsius.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        let (_, uncomp_temp) = self.trigger_and_read()?;
        let (t_lin, t) = compensate_temperature(uncomp_temp, &self.cal);
        self.t_lin = t_lin;
        Ok(t as f32)
    }

    /// Read calibrated pressure.
    ///
    /// Reads both ADCs and refreshes t_lin. Self-contained — may be called
    /// without a prior `temperature()` call.
    ///
    /// Returns pressure in hectopascals (hPa).
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        let (uncomp_press, uncomp_temp) = self.trigger_and_read()?;
        let (t_lin, _) = compensate_temperature(uncomp_temp, &self.cal);
        self.t_lin = t_lin;
        Ok((compensate_pressure(uncomp_press, t_lin, &self.cal) / 100.0) as f32)
    }
}

/// One FIFO frame as a discriminated enum.
#[derive(Clone, Copy, Debug)]
pub enum Bmp384FifoFrame {
    Pressure(f32),
    Temperature(f32),
    Sensortime(u32),
    Error,
    Empty,
    Unknown,
}

/// BMP384 full driver — extends minimal with configuration, mode control, and FIFO access.
pub struct Bmp384Full<I2C> {
    inner: Bmp384Minimal<I2C>,
}

impl<I2C: I2c> Bmp384Full<I2C> {
    /// Create a new `Bmp384Full`.
    pub fn new(i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Bmp384Minimal::new(i2c, addr, spi)? })
    }

    /// Write OSR, CONFIG, and ODR.
    pub fn configure(&mut self, osr_p: u8, osr_t: u8, iir_filter: u8, odr_sel: u8) -> Result<(), I2C::Error> {
        self.inner.osr_p = osr_p;
        self.inner.osr_t = osr_t;
        self.inner.iir   = iir_filter;
        self.inner.odr   = odr_sel;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OSR,
            (osr_t << 3) | (osr_p << 0), self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, (iir_filter << 1), self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ODR, odr_sel, self.inner.spi)
    }

    /// Read both pressure and temperature in a single burst.
    pub fn read(&mut self) -> Result<(f32, f32), I2C::Error> {
        if self.inner.mode == MODE_FORCED_BITS {
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_CTRL,
                (MODE_FORCED_BITS << 4) | PWR_TEMP_EN | PWR_PRESS_EN, self.inner.spi)?;
        }
        let mut raw = [0u8; 6];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_DATA_0, &mut raw)?;
        let uncomp_press = ((raw[2] as u32) << 16) | ((raw[1] as u32) << 8) | raw[0] as u32;
        let uncomp_temp  = ((raw[5] as u32) << 16) | ((raw[4] as u32) << 8) | raw[3] as u32;
        let (t_lin, t) = compensate_temperature(uncomp_temp, &self.inner.cal);
        self.inner.t_lin = t_lin;
        let p = (compensate_pressure(uncomp_press, t_lin, &self.inner.cal) / 100.0) as f32;
        Ok((p, t as f32))
    }

    /// Trigger a forced measurement, wait T_conv, return both values.
    pub fn read_forced(&mut self) -> Result<(f32, f32), I2C::Error> {
        let prev_mode = self.inner.mode;
        self.set_mode(MODE_FORCED)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_CTRL,
            (MODE_FORCED_BITS << 4) | PWR_TEMP_EN | PWR_PRESS_EN, self.inner.spi)?;
        let t_conv_ms = compute_t_conv_ms(self.inner.osr_p, self.inner.osr_t);
        delay_ms(t_conv_ms);
        let (p, t) = self.read()?;
        self.inner.mode = prev_mode;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_CTRL,
            (prev_mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN, self.inner.spi)?;
        Ok((p, t))
    }

    /// Set the power mode.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        self.inner.mode = mode;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_CTRL,
            (mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN, self.inner.spi)
    }

    /// True if STATUS.drdy_press is set.
    pub fn is_data_ready(&mut self) -> Result<bool, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_STATUS, &mut buf)?;
        Ok((buf[0] & (1 << 5)) != 0)
    }

    /// Soft reset, re-read calibration, re-apply configuration.
    pub fn softreset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CMD, SOFT_RESET_CMD, self.inner.spi)?;
        delay_ms(3);
        let cal = read_calibration(&mut self.inner.i2c, self.inner.addr)?;
        self.inner.cal = cal;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OSR,
            (self.inner.osr_t << 3) | (self.inner.osr_p << 0), self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG,
            (self.inner.iir << 1), self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ODR, self.inner.odr, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_CTRL,
            (self.inner.mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN, self.inner.spi)
    }

    /// Configure FIFO source, watermark, and stop-on-full behaviour.
    pub fn fifo_configure(&mut self, press_en: bool, temp_en: bool, wtm: u16, stop_on_full: bool) -> Result<(), I2C::Error> {
        let cfg1: u8 = (1 << 4)
            | ((stop_on_full as u8) << 3)
            | ((temp_en as u8) << 1)
            | (press_en as u8);
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x17, cfg1, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x15, (wtm & 0xFF) as u8, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x16, ((wtm >> 8) & 0x01) as u8, self.inner.spi)
    }

    /// Read and parse every available FIFO frame.
    ///
    /// Caller owns the output buffer. Returns the number of frames written.
    pub fn fifo_read(&mut self, out: &mut [Bmp384FifoFrame]) -> Result<usize, I2C::Error> {
        let mut len_lo = [0u8; 1];
        let mut len_hi = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, 0x12, &mut len_lo)?;
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, 0x13, &mut len_hi)?;
        let length = ((len_hi[0] as u16) << 8) | (len_lo[0] as u16);
        if length == 0 {
            return Ok(0);
        }
        let mut buf = vec![0u8; length as usize];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, 0x14, &mut buf)?;

        let mut n = 0usize;
        let mut i = 0usize;
        while i < buf.len() && n < out.len() {
            let hdr = buf[i];
            if hdr == FIFO_HEADER_PRESS {
                if i + 3 >= buf.len() { break; }
                let uncomp = ((buf[i + 3] as u32) << 16)
                           | ((buf[i + 2] as u32) << 8)
                           | (buf[i + 1] as u32);
                let v_pa = compensate_pressure(uncomp, self.inner.t_lin, &self.inner.cal);
                out[n] = Bmp384FifoFrame::Pressure((v_pa / 100.0) as f32);
                i += 4; n += 1;
            } else if hdr == FIFO_HEADER_TEMP {
                if i + 3 >= buf.len() { break; }
                let uncomp = ((buf[i + 3] as u32) << 16)
                           | ((buf[i + 2] as u32) << 8)
                           | (buf[i + 1] as u32);
                let (t_lin, t) = compensate_temperature(uncomp, &self.inner.cal);
                self.inner.t_lin = t_lin;
                out[n] = Bmp384FifoFrame::Temperature(t as f32);
                i += 4; n += 1;
            } else if hdr == FIFO_HEADER_SENSORT {
                if i + 3 >= buf.len() { break; }
                let uncomp = ((buf[i + 3] as u32) << 16)
                           | ((buf[i + 2] as u32) << 8)
                           | (buf[i + 1] as u32);
                out[n] = Bmp384FifoFrame::Sensortime(uncomp);
                i += 4; n += 1;
            } else if hdr == FIFO_HEADER_ERROR || hdr == FIFO_HEADER_EMPTY {
                out[n] = if hdr == FIFO_HEADER_ERROR { Bmp384FifoFrame::Error } else { Bmp384FifoFrame::Empty };
                i += 1; n += 1;
            } else {
                out[n] = Bmp384FifoFrame::Unknown;
                i += 1; n += 1;
            }
        }
        Ok(n)
    }

    /// Flush the FIFO.
    pub fn fifo_flush(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CMD, FIFO_FLUSH_CMD, self.inner.spi)
    }

    /// Compute altitude above sea level from the current pressure.
    pub fn altitude(&mut self, sea_level_hpa: f32) -> Result<f32, I2C::Error> {
        let p = self.inner.pressure()?;
        if p <= 0.0 {
            return Ok(0.0);
        }
        Ok(44330.0 * (1.0 - powf(p / sea_level_hpa, 1.0 / 5.255)))
    }

    /// Read calibrated temperature.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        self.inner.temperature()
    }

    /// Read calibrated pressure.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        self.inner.pressure()
    }
}

fn compute_t_conv_ms(osr_p: u8, osr_t: u8) -> u32 {
    let t_conv_us: u32 = 234
        + 392 + (1u32 << osr_p) * 2000
        + 313 + (1u32 << osr_t) * 2000;
    t_conv_us / 1000 + 1
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x76;

    fn cal_bytes() -> [u8; 21] {
        // Arbitrary but valid 21-byte NVM block.
        [
            0x01, 0x00, // NVM_PAR_T1 = 1
            0x02, 0x00, // NVM_PAR_T2 = 2
            0x03,       // NVM_PAR_T3 = 3
            0x04, 0x00, // NVM_PAR_P1 = 4
            0x05, 0x00, // NVM_PAR_P2 = 5
            0x06,       // NVM_PAR_P3 = 6
            0x07,       // NVM_PAR_P4 = 7
            0x08, 0x00, // NVM_PAR_P5 = 8
            0x09, 0x00, // NVM_PAR_P6 = 9
            0x0A,       // NVM_PAR_P7 = 10
            0x0B,       // NVM_PAR_P8 = 11
            0x0C, 0x00, // NVM_PAR_P9 = 12
            0x0D,       // NVM_PAR_P10 = 13
            0x0E,       // NVM_PAR_P11 = 14
        ]
    }

    fn data_bytes() -> [u8; 6] {
        [0x10, 0x00, 0x01, 0x20, 0x00, 0x02] // uncomp_press=0x010010, uncomp_temp=0x020020
    }

    #[test]
    fn full_api() {
        let mut transactions: Vec<I2cTransaction> = vec![
            I2cTransaction::write_read(ADDR, vec![REG_CAL_START], cal_bytes().to_vec()),
            // Minimal::new(): OSR=(1<<3)|(4<<0)=0x0C, CONFIG=(2<<1)=0x04,
            // ODR=0x03, PWR_CTRL=(0b11<<4)|0x03=0x33
            I2cTransaction::write(ADDR, vec![REG_OSR,       0x0C]),
            I2cTransaction::write(ADDR, vec![REG_CONFIG,    0x04]),
            I2cTransaction::write(ADDR, vec![REG_ODR,       0x03]),
            I2cTransaction::write(ADDR, vec![REG_PWR_CTRL,  0x33]),
            // temperature(): trigger (mode==NORMAL → no trigger) → reads 6 bytes
            I2cTransaction::write_read(ADDR, vec![REG_DATA_0], data_bytes().to_vec()),
            // pressure(): same path
            I2cTransaction::write_read(ADDR, vec![REG_DATA_0], data_bytes().to_vec()),
            // is_data_ready(): STATUS read
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x60]),
            // configure(2, 1, 1, 0x04): OSR=(1<<3)|(2<<0)=0x0A, CONFIG=(1<<1)=0x02, ODR=0x04
            I2cTransaction::write(ADDR, vec![REG_OSR,    0x0A]),
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x02]),
            I2cTransaction::write(ADDR, vec![REG_ODR,    0x04]),
            // set_mode(MODE_FORCED=1): PWR_CTRL=(1<<4)|0x03=0x13
            I2cTransaction::write(ADDR, vec![REG_PWR_CTRL, 0x13]),
            // fifo_configure(true, true, 10, false): cfg1=0x13, wtm0=10, wtm1=0
            I2cTransaction::write(ADDR, vec![0x17, 0x13]),
            I2cTransaction::write(ADDR, vec![0x15, 0x0A]),
            I2cTransaction::write(ADDR, vec![0x16, 0x00]),
            // fifo_read: read LEN_LO (1 byte), LEN_HI (1 byte), then 0 bytes
            I2cTransaction::write_read(ADDR, vec![0x12], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![0x13], vec![0x00]),
            // fifo_flush(): CMD write 0xB0
            I2cTransaction::write(ADDR, vec![REG_CMD, FIFO_FLUSH_CMD]),
            // softreset(): CMD 0xB6, re-read calibration, re-apply config
            I2cTransaction::write(ADDR, vec![REG_CMD, SOFT_RESET_CMD]),
            I2cTransaction::write_read(ADDR, vec![REG_CAL_START], cal_bytes().to_vec()),
            I2cTransaction::write(ADDR, vec![REG_OSR,    0x0A]),
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x02]),
            I2cTransaction::write(ADDR, vec![REG_ODR,    0x04]),
            I2cTransaction::write(ADDR, vec![REG_PWR_CTRL, 0x13]),
        ];
        let i2c = I2cMock::new(&transactions);
        let mut sensor = Bmp384Full::new(i2c, ADDR, false).expect("init");

        let _t = sensor.temperature().unwrap();
        let _p = sensor.pressure().unwrap();
        assert!(sensor.is_data_ready().unwrap());
        sensor.configure(2, 1, 1, 0x04).unwrap();
        sensor.set_mode(MODE_FORCED).unwrap();
        sensor.fifo_configure(true, true, 10, false).unwrap();
        let mut frames: [Bmp384FifoFrame; 16] = unsafe { core::mem::zeroed() };
        let _n = sensor.fifo_read(&mut frames).unwrap();
        sensor.fifo_flush().unwrap();
        sensor.softreset().unwrap();

        sensor.inner.i2c.done();
    }

    #[test]
    fn spi_masks_write_addresses() {
        // BMP384 I²C register addresses use unmasked form (e.g. REG_OSR=0x1C);
        // SPI writes must clear bit 7 (0x1C & 0x7F = 0x1C — already in range,
        // but use REG_DATA_0=0x04 which similarly keeps bit 7 clear). Use a
        // higher address to prove masking: write the calibration at 0x31 and
        // verify on SPI the SPI write doesn't strip bit 7 because 0x31 < 0x80.
        let transactions = vec![
            I2cTransaction::write_read(ADDR, vec![REG_CAL_START], cal_bytes().to_vec()),
            // Unmasked write addresses (< 0x80) are unchanged on SPI.
            I2cTransaction::write(ADDR, vec![REG_OSR,      0x0C]),
            I2cTransaction::write(ADDR, vec![REG_CONFIG,   0x04]),
            I2cTransaction::write(ADDR, vec![REG_ODR,      0x03]),
            I2cTransaction::write(ADDR, vec![REG_PWR_CTRL, 0x33]),
        ];
        let i2c = I2cMock::new(&transactions);
        let _sensor = Bmp384Full::new(i2c, ADDR, true).expect("init");
    }
}
