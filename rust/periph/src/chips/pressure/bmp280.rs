//! BMP280 — piezo-resistive pressure + temperature sensor (Bosch Sensortec).
//!
//! Communicates over I²C (address 0x76 or 0x77) or SPI. The compensation
//! algorithm uses 64-bit integer arithmetic as documented in the datasheet
//! section 3.11.3.
//!
//! ## Constants
//!
//! Oversampling: [`OSRS_SKIP`], [`OSRS_X1`], [`OSRS_X2`], [`OSRS_X4`], [`OSRS_X8`], [`OSRS_X16`]
//! Mode: [`MODE_SLEEP`], [`MODE_FORCED`], [`MODE_NORMAL`]
//! Filter: [`FILTER_OFF`], [`FILTER_2`], [`FILTER_4`], [`FILTER_8`], [`FILTER_16`]
//! Standby: [`T_SB_0_5_MS`] through [`T_SB_4000_MS`]
//! Status: [`STATUS_MEASURING`], [`STATUS_IM_UPDATE`]

use embedded_hal::i2c::I2c;

const REG_CAL_START: u8 = 0x88;
const REG_ID: u8 = 0xD0;
const REG_RESET: u8 = 0xE0;
const REG_STATUS: u8 = 0xF3;
const REG_CTRL_MEAS: u8 = 0xF4;
const REG_CONFIG: u8 = 0xF5;
const REG_DATA_START: u8 = 0xF7;

const CHIP_ID: u8 = 0x58;
const RESET_CMD: u8 = 0xB6;

const MEAS_TIME_MS: u32 = 7;

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

/// Temperature oversampling: skipped.
pub const OSRS_SKIP: u8 = 0;
/// Temperature oversampling: ×1.
pub const OSRS_X1: u8 = 1;
/// Temperature oversampling: ×2.
pub const OSRS_X2: u8 = 2;
/// Temperature oversampling: ×4.
pub const OSRS_X4: u8 = 3;
/// Temperature oversampling: ×8.
pub const OSRS_X8: u8 = 4;
/// Temperature oversampling: ×16.
pub const OSRS_X16: u8 = 5;

/// Power mode: sleep.
pub const MODE_SLEEP: u8 = 0;
/// Power mode: forced (single-shot).
pub const MODE_FORCED: u8 = 1;
/// Power mode: normal (continuous).
pub const MODE_NORMAL: u8 = 3;

/// IIR filter: off.
pub const FILTER_OFF: u8 = 0;
/// IIR filter: coefficient 2.
pub const FILTER_2: u8 = 1;
/// IIR filter: coefficient 4.
pub const FILTER_4: u8 = 2;
/// IIR filter: coefficient 8.
pub const FILTER_8: u8 = 3;
/// IIR filter: coefficient 16.
pub const FILTER_16: u8 = 4;

/// Standby time: 0.5 ms.
pub const T_SB_0_5_MS: u8 = 0;
/// Standby time: 62.5 ms.
pub const T_SB_62_5_MS: u8 = 1;
/// Standby time: 125 ms.
pub const T_SB_125_MS: u8 = 2;
/// Standby time: 250 ms.
pub const T_SB_250_MS: u8 = 3;
/// Standby time: 500 ms.
pub const T_SB_500_MS: u8 = 4;
/// Standby time: 1000 ms.
pub const T_SB_1000_MS: u8 = 5;
/// Standby time: 2000 ms.
pub const T_SB_2000_MS: u8 = 6;
/// Standby time: 4000 ms.
pub const T_SB_4000_MS: u8 = 7;

/// Status flag: measurement in progress.
pub const STATUS_MEASURING: u8 = 0x08;
/// Status flag: NVM image update in progress.
pub const STATUS_IM_UPDATE: u8 = 0x01;

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8, spi: bool) -> Result<(), I2C::Error> {
    let r = if spi { reg & 0x7F } else { reg };
    i2c.write(addr, &[r, value])
}

fn read_reg_bytes<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

#[derive(Clone, Copy)]
struct Calibration {
    dig_t1: u16,
    dig_t2: i16,
    dig_t3: i16,
    dig_p1: u16,
    dig_p2: i16,
    dig_p3: i16,
    dig_p4: i16,
    dig_p5: i16,
    dig_p6: i16,
    dig_p7: i16,
    dig_p8: i16,
    dig_p9: i16,
}

fn read_calibration<I2C: I2c>(i2c: &mut I2C, addr: u8) -> Result<Calibration, I2C::Error> {
    let mut buf = [0u8; 24];
    read_reg_bytes(i2c, addr, REG_CAL_START, &mut buf)?;
    Ok(Calibration {
        dig_t1: u16::from_le_bytes([buf[0], buf[1]]),
        dig_t2: i16::from_le_bytes([buf[2], buf[3]]),
        dig_t3: i16::from_le_bytes([buf[4], buf[5]]),
        dig_p1: u16::from_le_bytes([buf[6], buf[7]]),
        dig_p2: i16::from_le_bytes([buf[8], buf[9]]),
        dig_p3: i16::from_le_bytes([buf[10], buf[11]]),
        dig_p4: i16::from_le_bytes([buf[12], buf[13]]),
        dig_p5: i16::from_le_bytes([buf[14], buf[15]]),
        dig_p6: i16::from_le_bytes([buf[16], buf[17]]),
        dig_p7: i16::from_le_bytes([buf[18], buf[19]]),
        dig_p8: i16::from_le_bytes([buf[20], buf[21]]),
        dig_p9: i16::from_le_bytes([buf[22], buf[23]]),
    })
}

fn compensate_temp(adc_t: u32, cal: &Calibration) -> (i32, f32) {
    let dig_t1 = cal.dig_t1 as i64;
    let dig_t2 = cal.dig_t2 as i64;
    let dig_t3 = cal.dig_t3 as i64;
    let adc = adc_t as i64;
    let var1 = (((adc >> 3) - (dig_t1 << 1)) * dig_t2) >> 11;
    let var2 = (((((adc >> 4) - dig_t1) * ((adc >> 4) - dig_t1)) >> 12) * dig_t3) >> 14;
    let t_fine = (var1 + var2) as i32;
    let temp = ((t_fine as i64 * 5 + 128) >> 8) as f32 / 100.0;
    (t_fine, temp)
}

fn compensate_pressure(adc_p: u32, t_fine: i32, cal: &Calibration) -> f32 {
    let t = t_fine as i64;
    let dig_p1 = cal.dig_p1 as i64;
    let dig_p2 = cal.dig_p2 as i64;
    let dig_p3 = cal.dig_p3 as i64;
    let dig_p4 = cal.dig_p4 as i64;
    let dig_p5 = cal.dig_p5 as i64;
    let dig_p6 = cal.dig_p6 as i64;
    let dig_p7 = cal.dig_p7 as i64;
    let dig_p8 = cal.dig_p8 as i64;
    let dig_p9 = cal.dig_p9 as i64;

    let mut var1 = t - 128000;
    let mut var2 = var1 * var1 * dig_p6;
    var2 = var2 + ((var1 * dig_p5) << 17);
    var2 = var2 + (dig_p4 << 35);
    var1 = ((var1 * var1 * dig_p3) >> 8) + ((var1 * dig_p2) << 12);
    var1 = (((1i64 << 47) + var1) * dig_p1) >> 33;
    if var1 == 0 {
        return 0.0;
    }
    let mut p: i64 = 1048576 - adc_p as i64;
    p = (((p << 31) - var2) * 3125) / var1;
    var1 = (dig_p9 * (p >> 13) * (p >> 13)) >> 25;
    var2 = (dig_p8 * p) >> 19;
    p = ((p + var1 + var2) >> 8) + (dig_p7 << 4);
    (p as f64 / 256.0 / 100.0) as f32
}

/// BMP280 minimal driver — temperature (°C) and pressure (hPa).
///
/// Default: forced mode, osrs_t=×1, osrs_p=×1, IIR filter off.
pub struct Bmp280Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    spi: bool,
    mode: u8,
    osrs_t: u8,
    osrs_p: u8,
    filter: u8,
    t_sb: u8,
    t_fine: i32,
    cal: Calibration,
}

impl<I2C: I2c> Bmp280Minimal<I2C> {
    /// Create a new `Bmp280Minimal` and read calibration coefficients.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x76 or 0x77).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let cal = read_calibration(&mut i2c, addr)?;
        let mut s = Self {
            i2c, addr, spi: false, mode: 0,
            osrs_t: 1, osrs_p: 1, filter: 0, t_sb: 0,
            t_fine: 0, cal,
        };
        write_reg(&mut s.i2c, s.addr, REG_CTRL_MEAS, (1 << 5) | (1 << 2) | 0, false)?;
        write_reg(&mut s.i2c, s.addr, REG_CONFIG, 0, false)?;
        Ok(s)
    }

    fn trigger_and_read(&mut self) -> Result<(u32, u32), I2C::Error> {
        if self.mode != MODE_NORMAL {
            let ctrl = (self.osrs_t << 5) | (self.osrs_p << 2) | 1;
            write_reg(&mut self.i2c, self.addr, REG_CTRL_MEAS, ctrl, self.spi)?;
            delay_ms(MEAS_TIME_MS);
        }
        let mut raw = [0u8; 6];
        read_reg_bytes(&mut self.i2c, self.addr, REG_DATA_START, &mut raw)?;
        let adc_p = ((raw[0] as u32) << 12) | ((raw[1] as u32) << 4) | ((raw[2] as u32) >> 4);
        let adc_t = ((raw[3] as u32) << 12) | ((raw[4] as u32) << 4) | ((raw[5] as u32) >> 4);
        Ok((adc_p, adc_t))
    }

    /// Read calibrated temperature.
    ///
    /// Returns temperature in degrees Celsius.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        let (adc_p, adc_t) = self.trigger_and_read()?;
        let (t_fine, temp) = compensate_temp(adc_t, &self.cal);
        self.t_fine = t_fine;
        Ok(temp)
    }

    /// Read calibrated pressure.
    ///
    /// Reads both ADCs and refreshes t_fine.
    /// Self-contained — may be called without a prior `temperature()` call.
    ///
    /// Returns pressure in hPa.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        let (adc_p, adc_t) = self.trigger_and_read()?;
        let (t_fine, _) = compensate_temp(adc_t, &self.cal);
        self.t_fine = t_fine;
        Ok(compensate_pressure(adc_p, t_fine, &self.cal))
    }
}

/// BMP280 full driver — extends minimal with configuration and altitude helpers.
pub struct Bmp280Full<I2C> {
    inner: Bmp280Minimal<I2C>,
}

impl<I2C: I2c> Bmp280Full<I2C> {
    /// Create a new `Bmp280Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x76 or 0x77).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Bmp280Minimal::new(i2c, addr)?;
        Ok(Self { inner })
    }

    /// Write both ctrl_meas and config registers.
    ///
    /// # Arguments
    /// * `osrs_t` — Temperature oversampling (0–5).
    /// * `osrs_p` — Pressure oversampling (0–5).
    /// * `mode` — Power mode (0=sleep, 1=forced, 3=normal).
    /// * `filter` — IIR filter coefficient (0–4).
    /// * `t_sb` — Standby time in normal mode (0–7).
    pub fn configure(&mut self, osrs_t: u8, osrs_p: u8, mode: u8, filter: u8, t_sb: u8) -> Result<(), I2C::Error> {
        self.inner.osrs_t = osrs_t;
        self.inner.osrs_p = osrs_p;
        self.inner.mode = mode;
        self.inner.filter = filter;
        self.inner.t_sb = t_sb;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, (t_sb << 5) | (filter << 2), self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | mode, self.inner.spi)
    }

    /// Update temperature and pressure oversampling.
    pub fn set_oversampling(&mut self, osrs_t: u8, osrs_p: u8) -> Result<(), I2C::Error> {
        self.inner.osrs_t = osrs_t;
        self.inner.osrs_p = osrs_p;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS,
            (osrs_t << 5) | (osrs_p << 2) | self.inner.mode, self.inner.spi)
    }

    /// Update power mode.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        self.inner.mode = mode;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS,
            (self.inner.osrs_t << 5) | (self.inner.osrs_p << 2) | mode, self.inner.spi)
    }

    /// Update IIR filter coefficient.
    pub fn set_filter(&mut self, coeff: u8) -> Result<(), I2C::Error> {
        self.inner.filter = coeff;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG,
            (self.inner.t_sb << 5) | (coeff << 2), self.inner.spi)
    }

    /// Update standby time for normal mode.
    pub fn set_standby(&mut self, t_sb: u8) -> Result<(), I2C::Error> {
        self.inner.t_sb = t_sb;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG,
            (t_sb << 5) | (self.inner.filter << 2), self.inner.spi)
    }

    /// Read the status register.
    ///
    /// Returns status byte; bit 3 = measuring, bit 0 = im_update.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_STATUS, &mut buf)?;
        Ok(buf[0])
    }

    /// Compute altitude above sea level from the current pressure.
    ///
    /// # Arguments
    /// * `sea_level_hpa` — Reference sea-level pressure in hPa.
    ///
    /// Returns altitude in metres.
    pub fn altitude(&mut self, sea_level_hpa: f32) -> Result<f32, I2C::Error> {
        let p = self.inner.pressure()?;
        Ok(44330.0 * (1.0 - libm::powf(p / sea_level_hpa, 1.0 / 5.255)))
    }

    /// Compute sea-level pressure from current pressure and known altitude.
    ///
    /// # Arguments
    /// * `altitude_m` — Altitude in metres.
    ///
    /// Returns sea-level pressure in hPa.
    pub fn sea_level_pressure(&mut self, altitude_m: f32) -> Result<f32, I2C::Error> {
        let p = self.inner.pressure()?;
        Ok(p / libm::powf(1.0 - altitude_m / 44330.0, 5.255))
    }

    /// Read the chip ID register.
    ///
    /// Returns chip ID; expect 0x58.
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_ID, &mut buf)?;
        Ok(buf[0])
    }

    /// Perform a soft reset, re-read calibration, and re-apply configuration.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RESET, RESET_CMD, self.inner.spi)?;
        delay_ms(2);
        self.inner.cal = read_calibration(&mut self.inner.i2c, self.inner.addr)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG,
            (self.inner.t_sb << 5) | (self.inner.filter << 2), self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS,
            (self.inner.osrs_t << 5) | (self.inner.osrs_p << 2) | self.inner.mode, self.inner.spi)
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
