//! BMP180 — piezo-resistive pressure + temperature sensor (Bosch Sensortec).
//!
//! Communicates over I²C (fixed address 0x77). The compensation algorithm
//! is implemented exactly as documented in the datasheet (Figure 4).
//!
//! ## OSS constants
//!
//! Pass one of [`OSS_ULP`], [`OSS_STANDARD`], [`OSS_HIGH_RES`], or [`OSS_ULTRA_HIGH_RES`]
//! to [`Bmp180Full::new`] or [`Bmp180Full::set_oversampling`].

use embedded_hal::i2c::I2c;

const REG_ID: u8 = 0xD0;
const REG_CAL_START: u8 = 0xAA;
const REG_CAL_END: u8 = 0xBF;
const REG_CTRL_MEAS: u8 = 0xF4;
const REG_OUT_MSB: u8 = 0xF6;
const REG_OUT_LSB: u8 = 0xF7;
const REG_OUT_XLSB: u8 = 0xF8;
const REG_SOFT_RESET: u8 = 0xE0;

const CMD_TEMP: u8 = 0x2E;
const CMD_PRESSURE_OSS0: u8 = 0x34;
const CMD_PRESSURE_OSS1: u8 = 0x74;
const CMD_PRESSURE_OSS2: u8 = 0xB4;
const CMD_PRESSURE_OSS3: u8 = 0xF4;

const CHIP_ID: u8 = 0x55;
const SOFT_RESET_CMD: u8 = 0xB6;

const CONV_TIME_TEMP_MS: u32 = 4500;
const CONV_TIME_OSS0_MS: u32 = 4500;
const CONV_TIME_OSS1_MS: u32 = 7500;
const CONV_TIME_OSS2_MS: u32 = 13500;
const CONV_TIME_OSS3_MS: u32 = 25500;

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

/// Oversampling mode constant: Ultra Low Power (4.5 ms, oss=0).
pub const OSS_ULP: u8 = 0;
/// Oversampling mode constant: Standard (7.5 ms, oss=1).
pub const OSS_STANDARD: u8 = 1;
/// Oversampling mode constant: High Resolution (13.5 ms, oss=2).
pub const OSS_HIGH_RES: u8 = 2;
/// Oversampling mode constant: Ultra High Resolution (25.5 ms, oss=3).
pub const OSS_ULTRA_HIGH_RES: u8 = 3;

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok((buf[0] as u16) << 8 | buf[1] as u16)
}

fn read_reg_u8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(buf[0])
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u16) -> Result<(), I2C::Error> {
    let buf = [reg, (value >> 8) as u8, (value & 0xFF) as u8];
    i2c.write(addr, &buf)
}

fn write_reg_u8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_calibration<I2C: I2c>(i2c: &mut I2C, addr: u8) -> Result<(i32,i32,i32,i32,i32,i32,i32,i32,i32,i32,i32), I2C::Error> {
    let mut buf = [0u8; 22];
    i2c.write_read(addr, &[REG_CAL_START], &mut buf)?;

    let ac1 = i16::from_be_bytes([buf[0],  buf[1]])  as i32;
    let ac2 = i16::from_be_bytes([buf[2],  buf[3]])  as i32;
    let ac3 = i16::from_be_bytes([buf[4],  buf[5]])  as i32;
    let ac4 = u16::from_be_bytes([buf[6],  buf[7]])  as i32;
    let ac5 = u16::from_be_bytes([buf[8],  buf[9]])  as i32;
    let ac6 = u16::from_be_bytes([buf[10], buf[11]]) as i32;
    let b1  = i16::from_be_bytes([buf[12], buf[13]]) as i32;
    let b2  = i16::from_be_bytes([buf[14], buf[15]]) as i32;
    let mb  = i16::from_be_bytes([buf[16], buf[17]]) as i32;
    let mc  = i16::from_be_bytes([buf[18], buf[19]]) as i32;
    let md  = i16::from_be_bytes([buf[20], buf[21]]) as i32;

    if ac1 == 0 || ac1 == -1 || ac2 == 0 || ac2 == -1 || ac3 == 0 || ac3 == -1
        || ac4 == 0 || ac4 == -1 || ac5 == 0 || ac5 == -1 || ac6 == 0 || ac6 == -1
        || b1 == 0 || b1 == -1 || b2 == 0 || b2 == -1
        || mb == 0 || mb == -1 || mc == 0 || mc == -1 || md == 0 || md == -1 {
        panic!("BMP180 calibration invalid");
    }

    Ok((ac1, ac2, ac3, ac4, ac5, ac6, b1, b2, mb, mc, md))
}

fn compensate_temp(ut: i32, ac1: i32, ac2: i32, ac3: i32, ac5: i32, ac6: i32, mc: i32, md: i32) -> i32 {
    let x1 = ((ut - ac6) * ac5) >> 15;
    let x2 = (mc << 11) / (x1 + md);
    x1 + x2
}

fn compensate_pressure(up: i32, oss: i32, ac1: i32, ac2: i32, ac3: i32, ac4: i32, b1: i32, b2: i32, b5: i32) -> i32 {
    let b6 = b5 - 4000;
    let x1 = (b2 * ((b6 * b6) >> 12)) >> 11;
    let x2 = (ac2 * b6) >> 11;
    let x3 = x1 + x2;
    let b3 = ((((ac1 * 4) + x3) << oss) + 2) >> 2;
    let x1 = (ac3 * b6) >> 13;
    let x2 = (b1 * ((b6 * b6) >> 12)) >> 16;
    let x3 = ((x1 + x2) + 2) >> 2;
    let b4 = ((ac4 as u32 * (x3 + 32768) as u32) >> 15) as i32;
    let b7 = ((up - b3) as u32 * (50000u32 >> oss)) as i32;

    let p = if b7 >= 0 {
        ((b7 * 2) as u32 / b4 as u32) as i32
    } else {
        ((b7 as u32 / b4 as u32) * 2) as i32
    };

    let x1 = (p >> 8) * (p >> 8);
    let x1 = (x1 * 3038) >> 16;
    let x2 = (-7357 * p) >> 16;
    p + ((x1 + x2 + 3791) >> 4)
}

/// BMP180 minimal driver — temperature (°C) and pressure (hPa).
///
/// Default OSS = 0 (Ultra Low Power, 4.5 ms conversion).
pub struct Bmp180Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    oss: u8,
    ac1: i32, ac2: i32, ac3: i32, ac4: i32, ac5: i32, ac6: i32,
    b1: i32, b2: i32, mb: i32, mc: i32, md: i32,
    b5: i32,
}

impl<I2C: I2c> Bmp180Minimal<I2C> {
    /// Create a new `Bmp180Minimal` and read calibration coefficients.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let (ac1, ac2, ac3, ac4, ac5, ac6, b1, b2, mb, mc, md) = read_calibration(&mut i2c, addr)?;
        Ok(Self {
            i2c, addr, oss: 0,
            ac1, ac2, ac3, ac4, ac5, ac6, b1, b2, mb, mc, md, b5: 0
        })
    }

    /// Read calibrated temperature.
    ///
    /// Returns temperature in degrees Celsius.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        write_reg_u8(&mut self.i2c, self.addr, REG_CTRL_MEAS, CMD_TEMP)?;
        delay_ms(CONV_TIME_TEMP_MS);
        let mut buf = [0u8; 2];
        self.i2c.write_read(self.addr, &[REG_OUT_MSB], &mut buf)?;
        let ut = ((buf[0] as u16) << 8 | buf[1] as u16) as i32;
        self.b5 = compensate_temp(ut, self.ac1, self.ac2, self.ac3, self.ac5, self.ac6, self.mc, self.md);
        Ok(((self.b5 + 8) >> 4) as f32 / 10.0)
    }

    /// Read calibrated pressure.
    ///
    /// Reads temperature first to refresh B5, then reads pressure.
    /// Self-contained — may be called without a prior `temperature()` call.
    ///
    /// Returns pressure in hPa.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        write_reg_u8(&mut self.i2c, self.addr, REG_CTRL_MEAS, CMD_TEMP)?;
        delay_ms(CONV_TIME_TEMP_MS);
        let mut buf = [0u8; 2];
        self.i2c.write_read(self.addr, &[REG_OUT_MSB], &mut buf)?;
        let ut = ((buf[0] as u16) << 8 | buf[1] as u16) as i32;
        self.b5 = compensate_temp(ut, self.ac1, self.ac2, self.ac3, self.ac5, self.ac6, self.mc, self.md);

        let cmd = match self.oss {
            0 => CMD_PRESSURE_OSS0,
            1 => CMD_PRESSURE_OSS1,
            2 => CMD_PRESSURE_OSS2,
            _ => CMD_PRESSURE_OSS3,
        };
        let conv_ms = match self.oss {
            0 => CONV_TIME_OSS0_MS,
            1 => CONV_TIME_OSS1_MS,
            2 => CONV_TIME_OSS2_MS,
            _ => CONV_TIME_OSS3_MS,
        };
        write_reg_u8(&mut self.i2c, self.addr, REG_CTRL_MEAS, cmd)?;
        delay_ms(conv_ms);

        let mut buf = [0u8; 3];
        self.i2c.write_read(self.addr, &[REG_OUT_MSB], &mut buf)?;
        let up = (((buf[0] as u32) << 16 | (buf[1] as u32) << 8 | buf[2] as u32) >> (8 - self.oss as u32)) as i32;

        let p_pa = compensate_pressure(up, self.oss as i32, self.ac1, self.ac2, self.ac3, self.ac4,
            self.b1, self.b2, self.b5);
        Ok(p_pa as f32 / 100.0)
    }
}

/// BMP180 full driver — extends minimal with OSS control and altitude helpers.
pub struct Bmp180Full<I2C> {
    inner: Bmp180Minimal<I2C>,
}

impl<I2C: I2c> Bmp180Full<I2C> {
    /// Create a new `Bmp180Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `oss` — Oversampling mode 0–3 (default 0 = ULP).
    pub fn new(i2c: I2C, addr: u8, oss: u8) -> Result<Self, I2C::Error> {
        let mut inner = Bmp180Minimal::new(i2c, addr)?;
        inner.oss = oss & 0x03;
        Ok(Self { inner })
    }

    /// Read the current oversampling mode.
    ///
    /// Returns OSS value 0–3.
    pub fn oversampling(&self) -> u8 {
        self.inner.oss
    }

    /// Change the oversampling mode for subsequent `pressure()` calls.
    ///
    /// # Arguments
    /// * `oss` — New OSS value 0–3.
    pub fn set_oversampling(&mut self, oss: u8) {
        self.inner.oss = oss & 0x03;
    }

    /// Compute altitude above sea level from the current pressure.
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

    /// Read the chip ID register.
    ///
    /// Returns chip ID; expect 0x55.
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        read_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_ID)
    }

    /// Perform a soft reset and re-read calibration coefficients.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg_u8(&mut self.inner.i2c, self.inner.addr, REG_SOFT_RESET, SOFT_RESET_CMD)?;
        delay_ms(10);
        let (ac1, ac2, ac3, ac4, ac5, ac6, b1, b2, mb, mc, md) = read_calibration(&mut self.inner.i2c, self.inner.addr)?;
        self.inner.ac1 = ac1;
        self.inner.ac2 = ac2;
        self.inner.ac3 = ac3;
        self.inner.ac4 = ac4;
        self.inner.ac5 = ac5;
        self.inner.ac6 = ac6;
        self.inner.b1 = b1;
        self.inner.b2 = b2;
        self.inner.mb = mb;
        self.inner.mc = mc;
        self.inner.md = md;
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
