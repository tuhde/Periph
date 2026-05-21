//! BMP280 — piezo-resistive pressure + temperature sensor (Bosch Sensortec).
//!
//! Communicates over I²C (addresses 0x76 or 0x77). The compensation algorithm
//! is the 64-bit integer path from datasheet section 3.11.3.
//!
//! ## Constants
//!
//! Oversampling: [`OSRS_SKIP`], [`OSRS_X1`], [`OSRS_X2`], [`OSRS_X4`], [`OSRS_X8`], [`OSRS_X16`]
//! Mode: [`MODE_SLEEP`], [`MODE_FORCED`], [`MODE_NORMAL`]
//! Filter: [`FILTER_OFF`], [`FILTER_2`], [`FILTER_4`], [`FILTER_8`], [`FILTER_16`]
//! Standby: [`T_SB_0_5_MS`] … [`T_SB_4000_MS`]

use embedded_hal::i2c::I2c;

const REG_ID: u8 = 0xD0;
const REG_RESET: u8 = 0xE0;
const REG_STATUS: u8 = 0xF3;
const REG_CTRL_MEAS: u8 = 0xF4;
const REG_CONFIG: u8 = 0xF5;
const REG_CAL_START: u8 = 0x88;
const REG_DATA: u8 = 0xF7;

const CHIP_ID: u8 = 0x58;
const RESET_CMD: u8 = 0xB6;
const CTRL_MEAS_DEFAULT: u8 = 0x29;

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

fn write_reg_u8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

fn read_reg_u8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(buf[0])
}

/// Temperature oversampling: skipped.
pub const OSRS_SKIP: u8 = 0;
/// Temperature oversampling: ×1 (ultra low power).
pub const OSRS_X1: u8 = 1;
/// Temperature oversampling: ×2 (low power).
pub const OSRS_X2: u8 = 2;
/// Temperature oversampling: ×4 (standard).
pub const OSRS_X4: u8 = 3;
/// Temperature oversampling: ×8 (high resolution).
pub const OSRS_X8: u8 = 4;
/// Temperature oversampling: ×16 (ultra high resolution).
pub const OSRS_X16: u8 = 5;

/// Power mode: sleep.
pub const MODE_SLEEP: u8 = 0;
/// Power mode: forced (single-shot).
pub const MODE_FORCED: u8 = 1;
/// Power mode: normal (continuous).
pub const MODE_NORMAL: u8 = 3;

/// IIR filter off.
pub const FILTER_OFF: u8 = 0;
/// IIR filter coefficient ×2.
pub const FILTER_2: u8 = 1;
/// IIR filter coefficient ×4.
pub const FILTER_4: u8 = 2;
/// IIR filter coefficient ×8.
pub const FILTER_8: u8 = 3;
/// IIR filter coefficient ×16.
pub const FILTER_16: u8 = 4;

/// Standby 0.5 ms (normal mode).
pub const T_SB_0_5_MS: u8 = 0;
/// Standby 62.5 ms (normal mode).
pub const T_SB_62_5_MS: u8 = 1;
/// Standby 125 ms (normal mode).
pub const T_SB_125_MS: u8 = 2;
/// Standby 250 ms (normal mode).
pub const T_SB_250_MS: u8 = 3;
/// Standby 500 ms (normal mode).
pub const T_SB_500_MS: u8 = 4;
/// Standby 1000 ms (normal mode).
pub const T_SB_1000_MS: u8 = 5;
/// Standby 2000 ms (normal mode).
pub const T_SB_2000_MS: u8 = 6;
/// Standby 4000 ms (normal mode).
pub const T_SB_4000_MS: u8 = 7;

/// Status bit: chip is performing a conversion.
pub const STATUS_MEASURING: u8 = 0x08;
/// Status bit: NVM image is being updated.
pub const STATUS_IM_UPDATE: u8 = 0x01;

/// BMP280 minimal driver — temperature (°C) and pressure (hPa) in forced mode.
///
/// Default: ×1 oversampling both channels, IIR filter off, forced mode.
/// Each call triggers a single-shot conversion.
pub struct Bmp280Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    t_fine: i32,
    dig_T1: u16, dig_T2: i16, dig_T3: i16,
    dig_P1: u16, dig_P2: i16, dig_P3: i16,
    dig_P4: i16, dig_P5: i16, dig_P6: i16,
    dig_P7: i16, dig_P8: i16, dig_P9: i16,
}

impl<I2C: I2c> Bmp280Minimal<I2C> {
    /// Create a new `Bmp280Minimal` and read calibration coefficients.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit I²C address (default 0x76, alternate 0x77).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut s = Self { i2c, addr, t_fine: 0,
            dig_T1: 0, dig_T2: 0, dig_T3: 0,
            dig_P1: 0, dig_P2: 0, dig_P3: 0,
            dig_P4: 0, dig_P5: 0, dig_P6: 0,
            dig_P7: 0, dig_P8: 0, dig_P9: 0,
        };
        s.load_calibration()?;
        write_reg_u8(&mut s.i2c, s.addr, REG_CTRL_MEAS, CTRL_MEAS_DEFAULT)?;
        write_reg_u8(&mut s.i2c, s.addr, REG_CONFIG, 0)?;
        Ok(s)
    }

    fn load_calibration(&mut self) -> Result<(), I2C::Error> {
        let mut raw = [0u8; 24];
        read_reg(&mut self.i2c, self.addr, REG_CAL_START, &mut raw)?;
        self.dig_T1 = u16::from_le_bytes([raw[0], raw[1]]);
        self.dig_T2 = i16::from_le_bytes([raw[2], raw[3]]);
        self.dig_T3 = i16::from_le_bytes([raw[4], raw[5]]);
        self.dig_P1 = u16::from_le_bytes([raw[6], raw[7]]);
        self.dig_P2 = i16::from_le_bytes([raw[8], raw[9]]);
        self.dig_P3 = i16::from_le_bytes([raw[10], raw[11]]);
        self.dig_P4 = i16::from_le_bytes([raw[12], raw[13]]);
        self.dig_P5 = i16::from_le_bytes([raw[14], raw[15]]);
        self.dig_P6 = i16::from_le_bytes([raw[16], raw[17]]);
        self.dig_P7 = i16::from_le_bytes([raw[18], raw[19]]);
        self.dig_P8 = i16::from_le_bytes([raw[20], raw[21]]);
        self.dig_P9 = i16::from_le_bytes([raw[22], raw[23]]);
        Ok(())
    }

    fn trigger_read_burst(&mut self) -> Result<(i32, i32), I2C::Error> {
        let mut raw = [0u8; 6];
        read_reg(&mut self.i2c, self.addr, REG_DATA, &mut raw)?;
        let adc_P = ((raw[0] as i32) << 12) | ((raw[1] as i32) << 4) | ((raw[2] as i32) >> 4);
        let adc_T = ((raw[3] as i32) << 12) | ((raw[4] as i32) << 4) | ((raw[5] as i32) >> 4);
        Ok((adc_T, adc_P))
    }

    fn trigger_measurement(&mut self) -> Result<(), I2C::Error> {
        write_reg_u8(&mut self.i2c, self.addr, REG_CTRL_MEAS, 0x25 | (1 << 5))?;
        delay_ms(7);
        Ok(())
    }

    fn compensate_temp(&mut self, adc_T: i32) -> f32 {
        let T1 = self.dig_T1 as i64;
        let T2 = self.dig_T2 as i64;
        let T3 = self.dig_T3 as i64;
        let mut var1 = (((adc_T >> 3) - (T1 << 1)) * T2) >> 11;
        let mut var2 = (((((adc_T >> 4) - T1) * ((adc_T >> 4) - T1)) >> 12) * T3) >> 14;
        self.t_fine = (var1 + var2) as i32;
        ((self.t_fine as i64 * 5 + 128) >> 8) as f32 / 100.0
    }

    fn compensate_pressure(&mut self, adc_P: i32) -> f32 {
        let tf = self.t_fine as i64;
        let P1 = self.dig_P1 as i64;
        let P2 = self.dig_P2 as i64;
        let P3 = self.dig_P3 as i64;
        let P4 = self.dig_P4 as i64;
        let P5 = self.dig_P5 as i64;
        let P6 = self.dig_P6 as i64;
        let P7 = self.dig_P7 as i64;
        let P8 = self.dig_P8 as i64;
        let P9 = self.dig_P9 as i64;

        let mut var1 = tf - 128000;
        let mut var2 = var1 * var1 * P6;
        var2 = var2 + ((var1 * P5) << 17);
        var2 = var2 + (P4 << 35);
        var1 = ((var1 * var1 * P3) >> 8) + ((var1 * P2) << 12);
        var1 = ((((1 << 47) + var1) * P1) >> 33);

        if var1 == 0 { return 0.0; }
        let mut p = 1048576 - adc_P as i64;
        p = (((p << 31) - var2) * 3125) / var1;
        var1 = (P9 * (p >> 13) * (p >> 13)) >> 25;
        var2 = (P8 * p) >> 19;
        p = ((p + var1 + var2) >> 8) + (P7 << 4);
        (p as f32 / 256.0) / 100.0
    }

    /// Read calibrated temperature.
    ///
    /// Triggers a forced-mode conversion and returns temperature in °C.
    /// Caches t_fine for use in subsequent pressure() calls.
    ///
    /// Returns temperature in degrees Celsius.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        self.trigger_measurement()?;
        let (adc_T, adc_P) = self.trigger_read_burst()?;
        let _ = adc_P;
        Ok(self.compensate_temp(adc_T))
    }

    /// Read calibrated pressure.
    ///
    /// Triggers a forced-mode conversion and returns pressure in hPa.
    /// Re-reads the temperature ADC alongside pressure to refresh t_fine.
    ///
    /// Returns pressure in hPa.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        self.trigger_measurement()?;
        let (adc_T, adc_P) = self.trigger_read_burst()?;
        self.compensate_temp(adc_T);
        Ok(self.compensate_pressure(adc_P))
    }
}

/// BMP280 full driver — extends minimal with full configuration and altitude helpers.
pub struct Bmp280Full<I2C> {
    inner: Bmp280Minimal<I2C>,
    osrs_t: u8,
    osrs_p: u8,
    mode: u8,
    filter: u8,
    t_sb: u8,
}

impl<I2C: I2c> Bmp280Full<I2C> {
    /// Create a new `Bmp280Full`.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus.
    /// * `addr` — 7-bit I²C address (default 0x76).
    /// * `osrs_t` — Temperature oversampling 0–5 (default 1 = ×1).
    /// * `osrs_p` — Pressure oversampling 0–5 (default 1 = ×1).
    /// * `mode` — Power mode (default 1 = forced).
    /// * `filter` — IIR filter coefficient 0–4 (default 0 = off).
    /// * `t_sb` — Standby time in normal mode 0–7 (default 0 = 0.5 ms).
    pub fn new(i2c: I2C, addr: u8, osrs_t: u8, osrs_p: u8, mode: u8, filter: u8, t_sb: u8) -> Result<Self, I2C::Error> {
        let mut inner = Bmp280Minimal::new(i2c, addr)?;
        write_reg_u8(&mut inner.i2c, addr, REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | mode)?;
        write_reg_u8(&mut inner.i2c, addr, REG_CONFIG, (t_sb << 5) | (filter << 2))?;
        Ok(Self { inner, osrs_t, osrs_p, mode, filter, t_sb })
    }

    fn ctrl_meas_value(&self) -> u8 {
        (self.osrs_t << 5) | (self.osrs_p << 2) | self.mode
    }

    fn config_value(&self) -> u8 {
        (self.t_sb << 5) | (self.filter << 2)
    }

    /// Update chip configuration.
    ///
    /// Writes both ctrl_meas and config registers.
    ///
    /// # Arguments
    /// * `osrs_t` — Temperature oversampling 0–5.
    /// * `osrs_p` — Pressure oversampling 0–5.
    /// * `mode` — Power mode (0=sleep, 1=forced, 3=normal).
    /// * `filter` — IIR filter coefficient (0=off, 1, 2, 3, 4=×16).
    /// * `t_sb` — Standby time in normal mode (0–7).
    pub fn configure(&mut self, osrs_t: u8, osrs_p: u8, mode: u8, filter: u8, t_sb: u8) -> Result<(), I2C::Error> {
        self.osrs_t = osrs_t;
        self.osrs_p = osrs_p;
        self.mode = mode;
        self.filter = filter;
        self.t_sb = t_sb;
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS, self.ctrl_meas_value())?;
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, self.config_value())?;
        Ok(())
    }

    /// Update oversampling settings.
    ///
    /// # Arguments
    /// * `osrs_t` — Temperature oversampling 0–5.
    /// * `osrs_p` — Pressure oversampling 0–5.
    pub fn set_oversampling(&mut self, osrs_t: u8, osrs_p: u8) -> Result<(), I2C::Error> {
        self.osrs_t = osrs_t;
        self.osrs_p = osrs_p;
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS, self.ctrl_meas_value())
    }

    /// Update power mode.
    ///
    /// # Arguments
    /// * `mode` — 0=sleep, 1=forced, 3=normal.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        self.mode = mode;
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS, self.ctrl_meas_value())
    }

    /// Update IIR filter coefficient.
    ///
    /// # Arguments
    /// * `coeff` — 0=off, 1=×2, 2=×4, 3=×8, 4=×16.
    pub fn set_filter(&mut self, coeff: u8) -> Result<(), I2C::Error> {
        self.filter = coeff;
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, self.config_value())
    }

    /// Update standby time (only relevant in normal mode).
    ///
    /// # Arguments
    /// * `t_sb` — 0=0.5ms, 1=62.5ms, 2=125ms, 3=250ms, 4=500ms, 5=1s, 6=2s, 7=4s.
    pub fn set_standby(&mut self, t_sb: u8) -> Result<(), I2C::Error> {
        self.t_sb = t_sb;
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, self.config_value())
    }

    /// Read status register.
    ///
    /// Returns status byte; check [`STATUS_MEASURING`] and [`STATUS_IM_UPDATE`] bits.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        read_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)
    }

    /// Compute altitude above sea level from current pressure.
    ///
    /// # Arguments
    /// * `sea_level_hpa` — Reference sea-level pressure in hPa (default 1013.25).
    ///
    /// Returns altitude in metres.
    pub fn altitude(&mut self, sea_level_hpa: f32) -> Result<f32, I2C::Error> {
        let p = self.inner.pressure()?;
        Ok(44330.0 * (1.0 - libm::powf(p / sea_level_hpa, 1.0 / 5.255)))
    }

    /// Compute sea-level pressure for a known altitude.
    ///
    /// # Arguments
    /// * `altitude_m` — Altitude in metres.
    ///
    /// Returns sea-level pressure in hPa.
    pub fn sea_level_pressure(&mut self, altitude_m: f32) -> Result<f32, I2C::Error> {
        let p = self.inner.pressure()?;
        Ok(p / libm::powf(1.0 - altitude_m / 44330.0, 5.255))
    }

    /// Read chip ID register.
    ///
    /// Returns chip ID; expect 0x58.
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        read_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_ID)
    }

    /// Perform soft reset and re-read calibration coefficients.
    ///
    /// Re-applies the current ctrl_meas and config settings.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_RESET, RESET_CMD)?;
        delay_ms(2);
        self.inner.load_calibration()?;
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS, self.ctrl_meas_value())?;
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, self.config_value())?;
        Ok(())
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