//! BME680 — 4-in-1 environmental sensor: temperature, pressure, humidity, gas resistance (Bosch Sensortec).
//!
//! Communicates over I²C (address 0x76 or 0x77). The compensation algorithms use
//! 32-bit integer arithmetic for temperature, pressure, and humidity; 64-bit for
//! gas resistance. Calibration is read from three non-contiguous register blocks.
//!
//! ## Constants
//!
//! Oversampling: [`OSRS_SKIP`], [`OSRS_X1`], [`OSRS_X2`], [`OSRS_X4`], [`OSRS_X8`], [`OSRS_X16`]
//! Mode: [`MODE_SLEEP`], [`MODE_FORCED`]
//! Filter: [`FILTER_0`] through [`FILTER_127`]
//! Status: [`STATUS_NEW_DATA`], [`STATUS_GAS_MEASURING`], [`STATUS_MEASURING`], [`STATUS_GAS_VALID`], [`STATUS_HEATER_STABLE`]

use embedded_hal::i2c::I2c;

const REG_RES_HEAT_VAL: u8 = 0x00;
const REG_RES_HEAT_RANGE: u8 = 0x02;
const REG_RANGE_SW_ERR: u8 = 0x04;
const REG_MEAS_STATUS: u8 = 0x1D;
const REG_PRESS_MSB: u8 = 0x1F;
const REG_CTRL_GAS_0: u8 = 0x70;
const REG_CTRL_GAS_1: u8 = 0x71;
const REG_CTRL_HUM: u8 = 0x72;
const REG_CTRL_MEAS: u8 = 0x74;
const REG_CONFIG: u8 = 0x75;
const REG_CAL_BLOCK1: u8 = 0x8A;
const REG_ID: u8 = 0xD0;
const REG_RESET: u8 = 0xE0;
const REG_CAL_BLOCK2: u8 = 0xE1;

const CHIP_ID: u8 = 0x61;
const RESET_CMD: u8 = 0xB6;

const MEAS_TIME_MS: u32 = 200;

const CONST_ARRAY1: [i64; 16] = [
    2147483647, 2147483647, 2147483647, 2147483647, 2147483647,
    2126008810, 2147483647, 2130303777, 2147483647, 2147483647,
    2143188679, 2136746228, 2147483647, 2126008810, 2147483647,
    2147483647,
];

const CONST_ARRAY2: [u64; 16] = [
    4096000000, 2048000000, 1024000000, 512000000, 255744255,
    127110228, 64000000, 32258064, 16016016, 8000000,
    4000000, 2000000, 1000000, 500000, 250000,
    125000,
];

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

/// IIR filter: off.
pub const FILTER_0: u8 = 0;
/// IIR filter: coefficient 1.
pub const FILTER_1: u8 = 1;
/// IIR filter: coefficient 3.
pub const FILTER_3: u8 = 2;
/// IIR filter: coefficient 7.
pub const FILTER_7: u8 = 3;
/// IIR filter: coefficient 15.
pub const FILTER_15: u8 = 4;
/// IIR filter: coefficient 31.
pub const FILTER_31: u8 = 5;
/// IIR filter: coefficient 63.
pub const FILTER_63: u8 = 6;
/// IIR filter: coefficient 127.
pub const FILTER_127: u8 = 7;

/// Status flag: new TPH data available.
pub const STATUS_NEW_DATA: u8 = 0x80;
/// Status flag: gas conversion in progress.
pub const STATUS_GAS_MEASURING: u8 = 0x40;
/// Status flag: any conversion in progress.
pub const STATUS_MEASURING: u8 = 0x20;
/// Gas status flag: gas reading is valid (in gas_r_lsb).
pub const STATUS_GAS_VALID: u8 = 0x20;
/// Gas status flag: heater reached target (in gas_r_lsb).
pub const STATUS_HEATER_STABLE: u8 = 0x10;

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_reg_bytes<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

#[derive(Clone, Copy)]
struct Calibration {
    par_t1: u16,
    par_t2: i16,
    par_t3: i8,
    par_p1: u16,
    par_p2: i16,
    par_p3: i8,
    par_p4: i16,
    par_p5: i16,
    par_p6: i8,
    par_p7: i8,
    par_p8: i16,
    par_p9: i16,
    par_p10: u8,
    par_h1: u16,
    par_h2: u16,
    par_h3: i8,
    par_h4: i8,
    par_h5: i8,
    par_h6: u8,
    par_h7: i8,
    par_g1: i8,
    par_g2: i16,
    par_g3: i8,
    res_heat_val: i8,
    res_heat_range: u8,
    range_switching_error: i8,
}

fn read_calibration<I2C: I2c>(i2c: &mut I2C, addr: u8) -> Result<Calibration, I2C::Error> {
    let mut b1 = [0u8; 23];
    let mut b2 = [0u8; 14];
    let mut s1 = [0u8; 1];
    let mut s2 = [0u8; 1];
    let mut s3 = [0u8; 1];

    read_reg_bytes(i2c, addr, REG_CAL_BLOCK1, &mut b1)?;
    read_reg_bytes(i2c, addr, REG_CAL_BLOCK2, &mut b2)?;
    read_reg_bytes(i2c, addr, REG_RES_HEAT_VAL, &mut s1)?;
    read_reg_bytes(i2c, addr, REG_RES_HEAT_RANGE, &mut s2)?;
    read_reg_bytes(i2c, addr, REG_RANGE_SW_ERR, &mut s3)?;

    let par_t2 = i16::from_le_bytes([b1[0], b1[1]]);
    let par_t3 = b1[2] as i8;
    let par_p1 = u16::from_le_bytes([b1[4], b1[5]]);
    let par_p2 = i16::from_le_bytes([b1[6], b1[7]]);
    let par_p3 = b1[8] as i8;
    let par_p4 = i16::from_le_bytes([b1[10], b1[11]]);
    let par_p5 = i16::from_le_bytes([b1[12], b1[13]]);
    let par_p7 = b1[14] as i8;
    let par_p6 = b1[15] as i8;
    let par_p8 = i16::from_le_bytes([b1[18], b1[19]]);
    let par_p9 = i16::from_le_bytes([b1[20], b1[21]]);
    let par_p10 = b1[22];

    let par_h2 = ((b2[0] as u16) << 4) | ((b2[1] as u16) >> 4);
    let par_h1 = ((b2[2] as u16) << 4) | ((b2[1] as u16) & 0x0F);
    let par_h3 = b2[3] as i8;
    let par_h4 = b2[4] as i8;
    let par_h5 = b2[5] as i8;
    let par_h6 = b2[6];
    let par_h7 = b2[7] as i8;
    let par_t1 = u16::from_le_bytes([b2[8], b2[9]]);
    let par_g2 = i16::from_le_bytes([b2[10], b2[11]]);
    let par_g1 = b2[12] as i8;
    let par_g3 = b2[13] as i8;

    let res_heat_val = s1[0] as i8;
    let res_heat_range = (s2[0] >> 4) & 0x03;
    let rse = (s3[0] >> 4) & 0x0F;
    let range_switching_error = if rse < 8 { rse as i8 } else { (rse as i8) - 16 };

    Ok(Calibration {
        par_t1, par_t2, par_t3,
        par_p1, par_p2, par_p3, par_p4, par_p5, par_p6, par_p7, par_p8, par_p9, par_p10,
        par_h1, par_h2, par_h3, par_h4, par_h5, par_h6, par_h7,
        par_g1, par_g2, par_g3,
        res_heat_val, res_heat_range, range_switching_error,
    })
}

fn compensate_temp(adc_t: u32, cal: &Calibration) -> (i32, f32) {
    let par_t1 = cal.par_t1 as i32;
    let par_t2 = cal.par_t2 as i32;
    let par_t3 = cal.par_t3 as i32;
    let adc = adc_t as i32;
    let var1 = (adc >> 3) - (par_t1 << 1);
    let var2 = (var1 * par_t2) >> 11;
    let var3 = (((var1 >> 1) * (var1 >> 1)) >> 12) * (par_t3 << 4) >> 14;
    let t_fine = var2 + var3;
    let temp = ((t_fine * 5 + 128) >> 8) as f32 / 100.0;
    (t_fine, temp)
}

fn compensate_pressure(adc_p: u32, t_fine: i32, cal: &Calibration) -> f32 {
    let par_p1 = cal.par_p1 as i32;
    let par_p2 = cal.par_p2 as i32;
    let par_p3 = cal.par_p3 as i32;
    let par_p4 = cal.par_p4 as i32;
    let par_p5 = cal.par_p5 as i32;
    let par_p6 = cal.par_p6 as i32;
    let par_p7 = cal.par_p7 as i32;
    let par_p8 = cal.par_p8 as i32;
    let par_p9 = cal.par_p9 as i32;
    let par_p10 = cal.par_p10 as i32;

    let mut var1 = (t_fine >> 1) - 64000;
    let mut var2 = ((((var1 >> 2) * (var1 >> 2)) >> 11) * par_p6) >> 2;
    var2 = var2 + ((var1 * par_p5) << 1);
    var2 = (var2 >> 2) + (par_p4 << 16);
    var1 = (((((var1 >> 2) * (var1 >> 2)) >> 13) * (par_p3 << 5)) >> 3) + ((par_p2 * var1) >> 1);
    var1 = var1 >> 18;
    var1 = ((32768 + var1) * par_p1) >> 15;
    let mut press_comp: i32 = 1048576 - adc_p as i32;
    press_comp = ((press_comp - (var2 >> 12)) as i64 * 3125) as i32;
    if press_comp >= (1 << 30) {
        press_comp = (press_comp / var1) << 1;
    } else {
        press_comp = (press_comp << 1) / var1;
    }
    var1 = (par_p9 * (((press_comp >> 3) * (press_comp >> 3)) >> 13)) >> 12;
    var2 = ((press_comp >> 2) * par_p8) >> 13;
    let var3 = ((press_comp >> 8) * (press_comp >> 8) * (press_comp >> 8) * par_p10) >> 17;
    press_comp = press_comp + ((var1 + var2 + var3 + (par_p7 << 7)) >> 4);
    press_comp as f32 / 100.0
}

fn compensate_humidity(hum_adc: u16, t_fine: i32, cal: &Calibration) -> f32 {
    let temp_scaled = t_fine as i64;
    let par_h1 = cal.par_h1 as i64;
    let par_h2 = cal.par_h2 as i64;
    let par_h3 = cal.par_h3 as i64;
    let par_h4 = cal.par_h4 as i64;
    let par_h5 = cal.par_h5 as i64;
    let par_h6 = cal.par_h6 as i64;
    let par_h7 = cal.par_h7 as i64;

    let var1 = hum_adc as i64 - ((par_h1 << 4) + (((temp_scaled * par_h3) / 100) >> 1));
    let var2 = (par_h2 * (((temp_scaled * par_h4) / 100) +
                          (((temp_scaled * ((temp_scaled * par_h5) / 100)) >> 6) / 100) +
                          (1 << 14))) >> 10;
    let var3 = var1 * var2;
    let var4 = ((par_h6 << 7) + ((temp_scaled * par_h7) / 100)) >> 4;
    let var5 = ((var3 >> 14) * (var3 >> 14)) >> 10;
    let var6 = (var4 * var5) >> 1;
    let mut hum_comp = (((var3 + var6) >> 10) * 1000) >> 12;
    if hum_comp < 0 { hum_comp = 0; }
    if hum_comp > 100000 { hum_comp = 100000; }
    hum_comp as f32 / 1000.0
}

fn compensate_gas(gas_adc: u16, gas_range: u8, range_switching_error: i8) -> f32 {
    let rse = range_switching_error as i64;
    let var1 = ((1340 + 5 * rse) * CONST_ARRAY1[gas_range as usize]) >> 16;
    let var2 = (((gas_adc as i64) << 15) - (1i64 << 24)) + var1;
    if var2 == 0 { return f32::NAN; }
    let gas_res = ((CONST_ARRAY2[gas_range as usize] as i64 * var1) >> 9) + (var2 >> 1);
    (gas_res / var2) as f32
}

fn calc_heater_resistance(target_temp: i16, ambient_temp: f32, cal: &Calibration) -> u8 {
    let par_g1 = cal.par_g1 as i32;
    let par_g2 = cal.par_g2 as i32;
    let par_g3 = cal.par_g3 as i32;
    let rhr = cal.res_heat_range as i32;
    let rhv = cal.res_heat_val as i32;

    let var1 = ((ambient_temp as i32 * par_g3) / 10) << 8;
    let var2 = (par_g1 + 784) * ((((par_g2 + 154009) * target_temp as i32 * 5 / 100) + 3276800) / 10);
    let var3 = var1 + (var2 >> 1);
    let var4 = var3 / (rhr + 4);
    let var5 = (131 * rhv) + 65536;
    let res_heat_x100 = ((var4 / var5) - 250) * 34;
    let res_heat_x = (res_heat_x100 + 50) / 100;
    (res_heat_x & 0xFF) as u8
}

fn calc_gas_wait(target_ms: u16) -> u8 {
    if target_ms <= 0x3F {
        target_ms as u8
    } else if target_ms <= 0x3F * 4 {
        ((1u16 << 6) | (target_ms / 4)) as u8
    } else if target_ms <= 0x3F * 16 {
        ((2u16 << 6) | (target_ms / 16)) as u8
    } else {
        let val = core::cmp::min(target_ms / 64, 0x3F);
        ((3u16 << 6) | val) as u8
    }
}

/// BME680 minimal driver — temperature (°C), pressure (hPa), humidity (%RH), gas resistance (Ω).
///
/// Default: forced mode, osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
/// heater profile 0 at 320 °C / 150 ms.
pub struct Bme680Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    osrs_t: u8,
    osrs_p: u8,
    osrs_h: u8,
    filter: u8,
    t_fine: i32,
    ambient_temp: f32,
    heat_temp: i16,
    heat_dur: u16,
    gas_enabled: bool,
    nb_conv: u8,
    cal: Calibration,
}

impl<I2C: I2c> Bme680Minimal<I2C> {
    /// Create a new `Bme680Minimal` and read calibration coefficients.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x76 or 0x77).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let cal = read_calibration(&mut i2c, addr)?;
        let mut s = Self {
            i2c, addr,
            osrs_t: 1, osrs_p: 1, osrs_h: 1,
            filter: 0, t_fine: 0, ambient_temp: 25.0,
            heat_temp: 320, heat_dur: 150,
            gas_enabled: true, nb_conv: 0, cal,
        };
        write_reg(&mut s.i2c, s.addr, REG_CTRL_HUM, s.osrs_h)?;
        write_reg(&mut s.i2c, s.addr, REG_CTRL_MEAS, (s.osrs_t << 5) | (s.osrs_p << 2) | 0)?;
        write_reg(&mut s.i2c, s.addr, REG_CONFIG, 0)?;
        s.setup_heater(0)?;
        write_reg(&mut s.i2c, s.addr, REG_CTRL_GAS_1, (1 << 4) | 0)?;
        Ok(s)
    }

    fn setup_heater(&mut self, index: u8) -> Result<(), I2C::Error> {
        let res = calc_heater_resistance(self.heat_temp, self.ambient_temp, &self.cal);
        let gw = calc_gas_wait(self.heat_dur);
        write_reg(&mut self.i2c, self.addr, 0x5A + index, res)?;
        write_reg(&mut self.i2c, self.addr, 0x64 + index, gw)
    }

    fn trigger_and_read(&mut self) -> Result<(u32, u32, u16, u16, u8, bool, bool), I2C::Error> {
        write_reg(&mut self.i2c, self.addr, REG_CTRL_HUM, self.osrs_h)?;
        let ctrl = (self.osrs_t << 5) | (self.osrs_p << 2) | 1;
        write_reg(&mut self.i2c, self.addr, REG_CTRL_MEAS, ctrl)?;
        delay_ms(MEAS_TIME_MS);
        let mut raw = [0u8; 13];
        read_reg_bytes(&mut self.i2c, self.addr, REG_PRESS_MSB, &mut raw)?;
        let press_adc = ((raw[0] as u32) << 12) | ((raw[1] as u32) << 4) | ((raw[2] as u32) >> 4);
        let temp_adc = ((raw[3] as u32) << 12) | ((raw[4] as u32) << 4) | ((raw[5] as u32) >> 4);
        let hum_adc = ((raw[6] as u16) << 8) | raw[7] as u16;
        let gas_adc = ((raw[11] as u16) << 2) | ((raw[12] as u16) >> 6);
        let gas_range = raw[12] & 0x0F;
        let gas_valid = (raw[12] >> 5) & 1 == 1;
        let heat_stab = (raw[12] >> 4) & 1 == 1;
        Ok((press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab))
    }

    /// Read calibrated temperature.
    ///
    /// Returns temperature in degrees Celsius.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        let (_, temp_adc, _, _, _, _, _) = self.trigger_and_read()?;
        let (t_fine, temp) = compensate_temp(temp_adc, &self.cal);
        self.t_fine = t_fine;
        self.ambient_temp = temp;
        Ok(temp)
    }

    /// Read calibrated pressure.
    ///
    /// Returns pressure in hPa.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        let (press_adc, temp_adc, _, _, _, _, _) = self.trigger_and_read()?;
        let (t_fine, _) = compensate_temp(temp_adc, &self.cal);
        self.t_fine = t_fine;
        Ok(compensate_pressure(press_adc, t_fine, &self.cal))
    }

    /// Read calibrated humidity.
    ///
    /// Returns relative humidity in %RH.
    pub fn humidity(&mut self) -> Result<f32, I2C::Error> {
        let (_, temp_adc, hum_adc, _, _, _, _) = self.trigger_and_read()?;
        let (t_fine, _) = compensate_temp(temp_adc, &self.cal);
        self.t_fine = t_fine;
        Ok(compensate_humidity(hum_adc, t_fine, &self.cal))
    }

    /// Read gas sensor resistance.
    ///
    /// Returns gas resistance in Ohms, or NaN on invalid reading.
    pub fn gas_resistance(&mut self) -> Result<f32, I2C::Error> {
        let (_, temp_adc, _, gas_adc, gas_range, gas_valid, heat_stab) = self.trigger_and_read()?;
        let (t_fine, _) = compensate_temp(temp_adc, &self.cal);
        self.t_fine = t_fine;
        if !gas_valid || !heat_stab { return Ok(f32::NAN); }
        Ok(compensate_gas(gas_adc, gas_range, self.cal.range_switching_error))
    }
}

/// BME680 full driver — extends minimal with configuration, multi-profile heater, and status.
pub struct Bme680Full<I2C> {
    inner: Bme680Minimal<I2C>,
}

impl<I2C: I2c> Bme680Full<I2C> {
    /// Create a new `Bme680Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x76 or 0x77).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Bme680Minimal::new(i2c, addr)?;
        Ok(Self { inner })
    }

    /// Write ctrl_hum, ctrl_meas, and config registers in the correct order.
    pub fn configure(&mut self, osrs_t: u8, osrs_p: u8, osrs_h: u8, mode: u8, filter: u8) -> Result<(), I2C::Error> {
        self.inner.osrs_t = osrs_t;
        self.inner.osrs_p = osrs_p;
        self.inner.osrs_h = osrs_h;
        self.inner.filter = filter;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_HUM, osrs_h)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, filter << 2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | mode)
    }

    /// Update oversampling for all three TPH channels.
    pub fn set_oversampling(&mut self, osrs_t: u8, osrs_p: u8, osrs_h: u8) -> Result<(), I2C::Error> {
        self.inner.osrs_t = osrs_t;
        self.inner.osrs_p = osrs_p;
        self.inner.osrs_h = osrs_h;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_HUM, osrs_h)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | 0)
    }

    /// Update IIR filter coefficient.
    pub fn set_filter(&mut self, coeff: u8) -> Result<(), I2C::Error> {
        self.inner.filter = coeff;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, coeff << 2)
    }

    /// Configure heater profile 0 and activate it.
    pub fn set_heater(&mut self, temp_c: i16, duration_ms: u16) -> Result<(), I2C::Error> {
        self.inner.heat_temp = temp_c;
        self.inner.heat_dur = duration_ms;
        self.inner.setup_heater(0)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_GAS_1, (1 << 4) | 0)
    }

    /// Configure one of the 10 heater profiles.
    pub fn set_heater_profile(&mut self, index: u8, temp_c: i16, duration_ms: u16) -> Result<(), I2C::Error> {
        let saved_temp = self.inner.heat_temp;
        let saved_dur = self.inner.heat_dur;
        self.inner.heat_temp = temp_c;
        self.inner.heat_dur = duration_ms;
        self.inner.setup_heater(index)?;
        self.inner.heat_temp = saved_temp;
        self.inner.heat_dur = saved_dur;
        Ok(())
    }

    /// Select which heater profile to use in the next forced cycle.
    pub fn select_heater_profile(&mut self, index: u8) -> Result<(), I2C::Error> {
        self.inner.nb_conv = index;
        let gas1 = if self.inner.gas_enabled { (1 << 4) | index } else { index };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_GAS_1, gas1)
    }

    /// Enable or disable gas conversion.
    pub fn set_gas_enabled(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.gas_enabled = enabled;
        let gas1 = if enabled { (1 << 4) | self.inner.nb_conv } else { self.inner.nb_conv };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_GAS_1, gas1)
    }

    /// Turn the heater off or on via ctrl_gas_0.
    pub fn set_heater_off(&mut self, off: bool) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_GAS_0, if off { 0x08 } else { 0x00 })
    }

    /// Override the ambient temperature used for heater-resistance calculation.
    pub fn set_ambient_temperature(&mut self, temp_c: f32) -> Result<(), I2C::Error> {
        self.inner.ambient_temp = temp_c;
        self.inner.setup_heater(self.inner.nb_conv)
    }

    /// Read all four sensor values from a single TPHG cycle.
    ///
    /// Returns `(temperature_C, pressure_hPa, humidity_RH, gas_resistance_Ohms)`.
    /// Gas resistance is NaN if the reading is invalid.
    pub fn read_all(&mut self) -> Result<(f32, f32, f32, f32), I2C::Error> {
        let (press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab) = self.inner.trigger_and_read()?;
        let (t_fine, t) = compensate_temp(temp_adc, &self.inner.cal);
        self.inner.t_fine = t_fine;
        self.inner.ambient_temp = t;
        let p = compensate_pressure(press_adc, t_fine, &self.inner.cal);
        let h = compensate_humidity(hum_adc, t_fine, &self.inner.cal);
        let g = if gas_valid && heat_stab {
            compensate_gas(gas_adc, gas_range, self.inner.cal.range_switching_error)
        } else {
            f32::NAN
        };
        Ok((t, p, h, g))
    }

    /// Check if the most recent gas reading is valid.
    pub fn gas_valid(&mut self) -> Result<bool, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, 0x2B, &mut buf)?;
        Ok((buf[0] >> 5) & 1 == 1)
    }

    /// Check if the heater reached its target temperature.
    pub fn heater_stable(&mut self) -> Result<bool, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, 0x2B, &mut buf)?;
        Ok((buf[0] >> 4) & 1 == 1)
    }

    /// Read the measurement status register.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_MEAS_STATUS, &mut buf)?;
        Ok(buf[0])
    }

    /// Read the chip ID register.
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_ID, &mut buf)?;
        Ok(buf[0])
    }

    /// Perform a soft reset, re-read calibration, and re-apply configuration.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RESET, RESET_CMD)?;
        delay_ms(2);
        self.inner.cal = read_calibration(&mut self.inner.i2c, self.inner.addr)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_HUM, self.inner.osrs_h)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, self.inner.filter << 2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_MEAS,
            (self.inner.osrs_t << 5) | (self.inner.osrs_p << 2) | 0)?;
        self.inner.setup_heater(self.inner.nb_conv)?;
        let gas1 = if self.inner.gas_enabled { (1 << 4) | self.inner.nb_conv } else { self.inner.nb_conv };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_GAS_1, gas1)
    }

    /// Read calibrated temperature.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        self.inner.temperature()
    }

    /// Read calibrated pressure.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        self.inner.pressure()
    }

    /// Read calibrated humidity.
    pub fn humidity(&mut self) -> Result<f32, I2C::Error> {
        self.inner.humidity()
    }

    /// Read gas sensor resistance.
    pub fn gas_resistance(&mut self) -> Result<f32, I2C::Error> {
        self.inner.gas_resistance()
    }
}
