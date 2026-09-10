//! BMP581 — MEMS barometric pressure + temperature sensor (Bosch Sensortec).
//!
//! Communicates over I²C (address 0x46 or 0x47) or SPI. The chip performs
//! all factory calibration internally — there is no user-visible
//! calibration step. 24-bit two's-complement raw values map directly to
//! Pa and °C via fixed-point division (no integer compensation math
//! required).
//!
//! ## Constants
//!
//! Oversampling: [`OSR_1X`] through [`OSR_128X`]
//! Power mode: [`MODE_STANDBY`], [`MODE_NORMAL`], [`MODE_FORCED`], [`MODE_CONTINUOUS`]
//! IIR filter: [`IIR_BYPASS`], [`IIR_COEFF_1`], [`IIR_COEFF_3`], [`IIR_COEFF_7`], [`IIR_COEFF_15`], [`IIR_COEFF_31`], [`IIR_COEFF_63`], [`IIR_COEFF_127`]
//! FIFO frame selection: [`FIFO_DISABLED`], [`FIFO_TEMP`], [`FIFO_PRESS`], [`FIFO_BOTH`]
//! FIFO mode: [`FIFO_STREAM`], [`FIFO_STOP_ON_FULL`]
//! Interrupt sources: [`INT_SOURCE_DRDY`], [`INT_SOURCE_FIFO_FULL`], [`INT_SOURCE_FIFO_THS`], [`INT_SOURCE_OOR_P`]

use embedded_hal::i2c::I2c;

const REG_CHIP_ID: u8       = 0x01;
const REG_REV_ID: u8        = 0x02;
const REG_INT_SOURCE: u8    = 0x15;
const REG_INT_CONFIG: u8    = 0x14;
const REG_FIFO_SEL: u8      = 0x18;
const REG_FIFO_CONFIG: u8   = 0x16;
const REG_FIFO_COUNT: u8    = 0x17;
const REG_FIFO_DATA: u8     = 0x29;
const REG_TEMP_XLSB: u8     = 0x1D;
const REG_PRESS_XLSB: u8    = 0x20;
const REG_INT_STATUS: u8    = 0x27;
const REG_STATUS: u8        = 0x28;
const REG_DSP_CONFIG: u8    = 0x30;
const REG_DSP_IIR: u8       = 0x31;
const REG_OOR_THR_P_LSB: u8 = 0x32;
const REG_OOR_THR_P_MSB: u8 = 0x33;
const REG_OOR_RANGE: u8     = 0x34;
const REG_OOR_CONFIG: u8    = 0x35;
const REG_OSR_CONFIG: u8    = 0x36;
const REG_ODR_CONFIG: u8    = 0x37;
const REG_OSR_EFF: u8       = 0x38;
const REG_NVM_ADDR: u8      = 0x2B;
const REG_NVM_DATA_LSB: u8  = 0x2C;
const REG_NVM_DATA_MSB: u8  = 0x2D;
const REG_CMD: u8           = 0x7E;

const CHIP_ID_EXPECTED: u8  = 0x50;
const SOFT_RESET_CMD: u8    = 0xB6;
const STATUS_NVM_RDY: u8    = 0x02;
const STATUS_NVM_ERR: u8    = 0x04;
const INT_STATUS_DRDY: u8   = 0x01;

/// Pressure oversampling: ×1.
pub const OSR_1X: u8   = 0;
/// Pressure oversampling: ×2.
pub const OSR_2X: u8   = 1;
/// Pressure oversampling: ×4.
pub const OSR_4X: u8   = 2;
/// Pressure oversampling: ×8.
pub const OSR_8X: u8   = 3;
/// Pressure oversampling: ×16.
pub const OSR_16X: u8  = 4;
/// Pressure oversampling: ×32.
pub const OSR_32X: u8  = 5;
/// Pressure oversampling: ×64.
pub const OSR_64X: u8  = 6;
/// Pressure oversampling: ×128.
pub const OSR_128X: u8 = 7;

/// Power mode: standby.
pub const MODE_STANDBY: u8    = 0;
/// Power mode: normal (ODR-driven duty-cycled).
pub const MODE_NORMAL: u8     = 1;
/// Power mode: forced (single-shot, returns to standby).
pub const MODE_FORCED: u8     = 2;
/// Power mode: continuous.
pub const MODE_CONTINUOUS: u8 = 3;

/// IIR filter: bypass (no filter).
pub const IIR_BYPASS: u8    = 0;
/// IIR filter: coefficient 1.
pub const IIR_COEFF_1: u8   = 1;
/// IIR filter: coefficient 3.
pub const IIR_COEFF_3: u8   = 2;
/// IIR filter: coefficient 7.
pub const IIR_COEFF_7: u8   = 3;
/// IIR filter: coefficient 15.
pub const IIR_COEFF_15: u8  = 4;
/// IIR filter: coefficient 31.
pub const IIR_COEFF_31: u8  = 5;
/// IIR filter: coefficient 63.
pub const IIR_COEFF_63: u8  = 6;
/// IIR filter: coefficient 127.
pub const IIR_COEFF_127: u8 = 7;

/// FIFO frame selection: disabled.
pub const FIFO_DISABLED: u8 = 0;
/// FIFO frame selection: temperature only.
pub const FIFO_TEMP: u8     = 1;
/// FIFO frame selection: pressure only.
pub const FIFO_PRESS: u8    = 2;
/// FIFO frame selection: pressure + temperature.
pub const FIFO_BOTH: u8     = 3;

/// FIFO mode: stream (overwrites oldest when full).
pub const FIFO_STREAM: u8        = 0;
/// FIFO mode: stop-on-full.
pub const FIFO_STOP_ON_FULL: u8  = 1;

/// Interrupt source bit: data-ready.
pub const INT_SOURCE_DRDY: u8      = 0x01;
/// Interrupt source bit: FIFO full.
pub const INT_SOURCE_FIFO_FULL: u8 = 0x02;
/// Interrupt source bit: FIFO threshold.
pub const INT_SOURCE_FIFO_THS: u8  = 0x04;
/// Interrupt source bit: pressure out-of-range.
pub const INT_SOURCE_OOR_P: u8     = 0x08;

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8, spi: bool) -> Result<(), I2C::Error> {
    let r = if spi { reg & 0x7F } else { reg };
    i2c.write(addr, &[r, value])
}

fn read_reg_bytes<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

fn u24(data: &[u8]) -> i32 {
    let raw = ((data[2] as i32) << 16) | ((data[1] as i32) << 8) | (data[0] as i32);
    if raw & 0x800000 != 0 { raw - 0x1000000 } else { raw }
}

/// BMP581 minimal driver — pressure (Pa) and temperature (°C).
///
/// Default: NORMAL mode, ODR 1 Hz, press_en=1, osr_p=x1, osr_t=x1,
/// IIR bypass, FIFO disabled, INT_SOURCE=0.
pub struct Bmp581Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    spi: bool,
    odr: u8,
    pwr_mode: u8,
    osr_p: u8,
    osr_t: u8,
    press_en: bool,
}

impl<I2C: I2c> Bmp581Minimal<I2C> {
    /// Create a new `Bmp581Minimal` and run the init sequence.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x46 or 0x47).
    /// * `spi` — Pass `true` for SPI bus (masks bit 7 on writes).
    pub fn new(mut i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        let mut s = Self {
            i2c, addr, spi,
            odr: 0x1C, pwr_mode: 0x01,
            osr_p: 0, osr_t: 0, press_en: true,
        };
        if spi {
            let _ = s.i2c.write(s.addr, &[REG_CHIP_ID | 0x80]);
            let mut dummy = [0u8; 1];
            let _ = s.i2c.read(s.addr, &mut dummy);
        }
        let mut buf = [0u8; 1];
        let _ = read_reg_bytes(&mut s.i2c, s.addr, REG_CHIP_ID, &mut buf);
        for _ in 0..50 {
            let _ = read_reg_bytes(&mut s.i2c, s.addr, REG_STATUS, &mut buf);
            if (buf[0] & STATUS_NVM_RDY) != 0 && (buf[0] & STATUS_NVM_ERR) == 0 { break; }
            delay_ms(2);
        }
        let _ = read_reg_bytes(&mut s.i2c, s.addr, REG_INT_STATUS, &mut buf);
        let _ = write_reg(&mut s.i2c, s.addr, REG_CMD, SOFT_RESET_CMD, spi);
        delay_ms(2);
        for _ in 0..50 {
            let _ = read_reg_bytes(&mut s.i2c, s.addr, REG_STATUS, &mut buf);
            if (buf[0] & STATUS_NVM_RDY) != 0 && (buf[0] & STATUS_NVM_ERR) == 0 { break; }
            delay_ms(2);
        }
        let _ = read_reg_bytes(&mut s.i2c, s.addr, REG_INT_STATUS, &mut buf);
        write_reg(&mut s.i2c, s.addr, REG_OSR_CONFIG, 0x40, spi)?;
        write_reg(&mut s.i2c, s.addr, REG_ODR_CONFIG, 0x71, spi)?;
        Ok(s)
    }

    fn wait_forced(&mut self) -> Result<(), I2C::Error> {
        if self.pwr_mode != MODE_FORCED { return Ok(()); }
        for _ in 0..200 {
            let mut buf = [0u8; 1];
            read_reg_bytes(&mut self.i2c, self.addr, REG_INT_STATUS, &mut buf)?;
            if buf[0] & INT_STATUS_DRDY != 0 { return Ok(()); }
            delay_ms(5);
        }
        Ok(())
    }

    /// Read calibrated pressure.
    ///
    /// Returns pressure in Pa.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        self.wait_forced()?;
        let mut buf = [0u8; 3];
        read_reg_bytes(&mut self.i2c, self.addr, REG_PRESS_XLSB, &mut buf)?;
        Ok(u24(&buf) as f32 / 64.0)
    }

    /// Read calibrated temperature.
    ///
    /// Returns temperature in degrees Celsius.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        self.wait_forced()?;
        let mut buf = [0u8; 3];
        read_reg_bytes(&mut self.i2c, self.addr, REG_TEMP_XLSB, &mut buf)?;
        Ok(u24(&buf) as f32 / 65536.0)
    }

    /// Read both pressure and temperature atomically in a single burst.
    pub fn both(&mut self) -> Result<(f32, f32), I2C::Error> {
        self.wait_forced()?;
        let mut buf = [0u8; 6];
        read_reg_bytes(&mut self.i2c, self.addr, REG_TEMP_XLSB, &mut buf)?;
        Ok((u24(&buf[3..]) as f32 / 64.0, u24(&buf[..3]) as f32 / 65536.0))
    }
}

/// BMP581 full driver — extends minimal with configuration, FIFO,
/// interrupts, OOR detection, and NVM access.
pub struct Bmp581Full<I2C> {
    inner: Bmp581Minimal<I2C>,
}

impl<I2C: I2c> Bmp581Full<I2C> {
    /// Create a new `Bmp581Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x46 or 0x47).
    /// * `spi` — Pass `true` for SPI bus (masks bit 7 on writes).
    pub fn new(i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Bmp581Minimal::new(i2c, addr, spi)? })
    }

    /// Configure OSR and ODR atomically.
    ///
    /// # Arguments
    /// * `odr` — ODR field 0x00-0x1F (default 0x1C = 1 Hz).
    /// * `osr_p` — Pressure oversampling 0-7.
    /// * `osr_t` — Temperature oversampling 0-7.
    /// * `press_en` — Whether to enable pressure measurements.
    pub fn configure(&mut self, odr: u8, osr_p: u8, osr_t: u8, press_en: bool) -> Result<(), I2C::Error> {
        self.inner.odr = odr;
        self.inner.osr_p = osr_p;
        self.inner.osr_t = osr_t;
        self.inner.press_en = press_en;
        let osr = (if press_en { 0x40 } else { 0 }) | ((osr_p & 0x7) << 3) | (osr_t & 0x7);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OSR_CONFIG, osr, self.inner.spi)?;
        let odr_byte = ((odr & 0x1F) << 2) | (self.inner.pwr_mode & 0x3);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ODR_CONFIG, odr_byte, self.inner.spi)
    }

    /// Set the power mode (preserves the current ODR setting).
    pub fn set_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        self.inner.pwr_mode = mode;
        let odr_byte = ((self.inner.odr & 0x1F) << 2) | (mode & 0x3);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ODR_CONFIG, odr_byte, self.inner.spi)
    }

    /// Trigger a single FORCED measurement, wait for completion, return readings.
    pub fn forced(&mut self) -> Result<(f32, f32), I2C::Error> {
        let prev = self.inner.pwr_mode;
        if prev != MODE_FORCED { self.set_mode(MODE_FORCED)?; }
        for _ in 0..400 {
            let mut buf = [0u8; 1];
            read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_INT_STATUS, &mut buf)?;
            if buf[0] & INT_STATUS_DRDY != 0 { break; }
            delay_ms(5);
        }
        self.inner.both()
    }

    /// Compute altitude from the current pressure reading.
    pub fn altitude(&mut self, sea_level_pa: f32) -> Result<f32, I2C::Error> {
        let p = self.inner.pressure()?;
        if p <= 0.0 { return Ok(0.0); }
        Ok(44330.0 * (1.0 - libm::powf(p / sea_level_pa, 1.0 / 5.255)))
    }

    /// Issue a soft reset and re-initialise the chip.
    pub fn software_reset(&mut self) -> Result<(), I2C::Error> {
        let _ = write_reg(&mut self.inner.i2c, self.inner.addr, REG_CMD, SOFT_RESET_CMD, self.inner.spi);
        delay_ms(2);
        let prev_odr = self.inner.odr;
        let prev_mode = self.inner.pwr_mode;
        let prev_osr_p = self.inner.osr_p;
        let prev_osr_t = self.inner.osr_t;
        let prev_press_en = self.inner.press_en;
        // Replace the inner by re-running init.
        let mut buf = [0u8; 1];
        let _ = read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_CHIP_ID, &mut buf);
        for _ in 0..50 {
            let _ = read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_STATUS, &mut buf);
            if (buf[0] & STATUS_NVM_RDY) != 0 && (buf[0] & STATUS_NVM_ERR) == 0 { break; }
            delay_ms(2);
        }
        let _ = read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_INT_STATUS, &mut buf);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OSR_CONFIG, 0x40, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ODR_CONFIG, 0x71, self.inner.spi)?;
        self.inner.odr = prev_odr;
        self.inner.pwr_mode = prev_mode;
        self.inner.osr_p = prev_osr_p;
        self.inner.osr_t = prev_osr_t;
        self.inner.press_en = prev_press_en;
        self.configure(prev_odr, prev_osr_p, prev_osr_t, prev_press_en)
    }

    /// Read CHIP_ID.
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_CHIP_ID, &mut buf)?;
        Ok(buf[0])
    }

    /// Read REV_ID.
    pub fn rev_id(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_REV_ID, &mut buf)?;
        Ok(buf[0])
    }

    /// Read STATUS.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_STATUS, &mut buf)?;
        Ok(buf[0])
    }

    /// Read INT_STATUS (clear-on-read).
    pub fn interrupt_status(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_INT_STATUS, &mut buf)?;
        Ok(buf[0])
    }

    /// Check whether a new data sample is available.
    pub fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.interrupt_status()? & INT_STATUS_DRDY != 0)
    }

    /// Configure INT pin: latching, polarity, drive mode, pin enable.
    pub fn configure_interrupt(&mut self, mode: u8, polarity: u8, open_drain: bool, enable: bool) -> Result<(), I2C::Error> {
        let mut val = if enable { 0x08 } else { 0 };
        if open_drain { val |= 0x04; }
        if polarity != 0 { val |= 0x02; }
        if mode != 0 { val |= 0x01; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_CONFIG, val, self.inner.spi)
    }

    fn set_int_source(&mut self, source: u8, enable: bool) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_INT_SOURCE, &mut buf)?;
        let cur = if enable { buf[0] | source } else { buf[0] & !source };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_SOURCE, cur, self.inner.spi)
    }

    /// Enable/disable the data-ready interrupt source.
    pub fn enable_drdy_interrupt(&mut self, enable: bool) -> Result<(), I2C::Error> {
        self.set_int_source(INT_SOURCE_DRDY, enable)
    }

    /// Enable/disable FIFO threshold and FIFO-full interrupt sources.
    pub fn enable_fifo_interrupt(&mut self, threshold: bool, full: bool) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_INT_SOURCE, &mut buf)?;
        let mut cur = buf[0] & !(INT_SOURCE_FIFO_FULL | INT_SOURCE_FIFO_THS);
        if threshold { cur |= INT_SOURCE_FIFO_THS; }
        if full { cur |= INT_SOURCE_FIFO_FULL; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_SOURCE, cur, self.inner.spi)
    }

    /// Enable/disable the pressure out-of-range interrupt source.
    pub fn enable_oor_interrupt(&mut self, enable: bool) -> Result<(), I2C::Error> {
        self.set_int_source(INT_SOURCE_OOR_P, enable)
    }

    /// Set IIR filter coefficients for pressure and temperature.
    pub fn set_iir_filter(&mut self, coeff_p: u8, coeff_t: u8) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_DSP_CONFIG, &mut buf)?;
        let dsp = buf[0] | 0x28;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_DSP_CONFIG, dsp, self.inner.spi)?;
        let iir = ((coeff_p & 0x7) << 3) | (coeff_t & 0x7);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_DSP_IIR, iir, self.inner.spi)
    }

    /// Configure FIFO source, mode, and threshold. Must be called in STANDBY mode.
    pub fn configure_fifo(&mut self, frame_sel: u8, mode: u8, threshold: u8) -> Result<(), I2C::Error> {
        let prev = self.inner.pwr_mode;
        if prev != MODE_STANDBY { self.set_mode(MODE_STANDBY)?; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_SEL, frame_sel & 0x3, self.inner.spi)?;
        let cfg = ((mode & 0x1) << 5) | (threshold & 0x1F);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_CONFIG, cfg, self.inner.spi)?;
        if prev != MODE_STANDBY { self.set_mode(prev)?; }
        Ok(())
    }

    /// Read the number of frames currently in the FIFO.
    pub fn fifo_count(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_FIFO_COUNT, &mut buf)?;
        Ok(buf[0] & 0x3F)
    }

    /// Read OSR_EFF.
    pub fn effective_osr(&mut self) -> Result<(u8, u8), I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_OSR_EFF, &mut buf)?;
        Ok(((buf[0] >> 3) & 0x7, buf[0] & 0x7))
    }

    /// Check whether the current ODR/OSR combination is valid.
    pub fn odr_is_valid(&mut self) -> Result<bool, I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_OSR_EFF, &mut buf)?;
        Ok(buf[0] & 0x80 != 0)
    }

    /// Configure the out-of-range pressure detector.
    pub fn set_oor_threshold(&mut self, threshold_pa: f32, range_pa: f32, count_limit: u8) -> Result<(), I2C::Error> {
        let thr17 = ((threshold_pa * 64.0) as i32) >> 7;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OOR_THR_P_LSB, (thr17 & 0xFF) as u8, self.inner.spi)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OOR_THR_P_MSB, ((thr17 >> 8) & 0xFF) as u8, self.inner.spi)?;
        let range8 = (((range_pa * 64.0) as i32) >> 7) & 0xFF;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OOR_RANGE, range8 as u8, self.inner.spi)?;
        let cfg = ((count_limit & 0x3) << 6) | ((thr17 >> 16) & 0x01) as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OOR_CONFIG, cfg, self.inner.spi)
    }

    /// Read one user NVM row.
    pub fn nvm_read(&mut self, row: u8) -> Result<u16, I2C::Error> {
        let prev = self.inner.pwr_mode;
        if prev != MODE_STANDBY { self.set_mode(MODE_STANDBY)?; }
        let result = (|| -> Result<u16, I2C::Error> {
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_NVM_ADDR, 0x5D, self.inner.spi)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_CMD, 0xA5, self.inner.spi)?;
            delay_ms(2);
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_NVM_ADDR, 0x40 | (row & 0x3F), self.inner.spi)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_CMD, 0xA5, self.inner.spi)?;
            delay_ms(2);
            let mut buf = [0u8; 2];
            read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_NVM_DATA_LSB, &mut buf)?;
            Ok(((buf[1] as u16) << 8) | (buf[0] as u16))
        })();
        if prev != MODE_STANDBY { self.set_mode(prev)?; }
        result
    }

    /// Write one user NVM row. Limited to 10,000 total write cycles.
    pub fn nvm_write(&mut self, row: u8, value: u16) -> Result<(), I2C::Error> {
        let prev = self.inner.pwr_mode;
        if prev != MODE_STANDBY { self.set_mode(MODE_STANDBY)?; }
        let result = (|| -> Result<(), I2C::Error> {
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_NVM_ADDR, 0x40 | (row & 0x3F), self.inner.spi)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_NVM_DATA_LSB, (value & 0xFF) as u8, self.inner.spi)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_NVM_DATA_MSB, ((value >> 8) & 0xFF) as u8, self.inner.spi)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_NVM_ADDR, 0x5D, self.inner.spi)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_CMD, 0xA0, self.inner.spi)?;
            delay_ms(5);
            Ok(())
        })();
        if prev != MODE_STANDBY { self.set_mode(prev)?; }
        result
    }

    /// Read calibrated pressure.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        self.inner.pressure()
    }

    /// Read calibrated temperature.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        self.inner.temperature()
    }

    /// Read both atomically.
    pub fn both(&mut self) -> Result<(f32, f32), I2C::Error> {
        self.inner.both()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x46;

    fn chip_id_data() -> Vec<I2cTransaction> {
        // CHPID_ID read returns 0x50, STATUS returns nvm_rdy=1.
        vec![
            I2cTransaction::write_read(ADDR, vec![REG_CHIP_ID], vec![0x50]),
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x02]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_STATUS], vec![0x00]),
            // soft reset
            I2cTransaction::write(ADDR, vec![REG_CMD, SOFT_RESET_CMD]),
            // status check after reset
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x02]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_STATUS], vec![0x00]),
            // OSR_CONFIG = 0x40, ODR_CONFIG = 0x71
            I2cTransaction::write(ADDR, vec![REG_OSR_CONFIG, 0x40]),
            I2cTransaction::write(ADDR, vec![REG_ODR_CONFIG, 0x71]),
        ]
    }

    #[test]
    fn full_api() {
        let mut transactions = chip_id_data();
        transactions.extend(vec![
            // temperature(): pressure register bytes -> 0.0625 Pa
            I2cTransaction::write_read(ADDR, vec![REG_PRESS_XLSB], vec![0x04, 0x00, 0x00]),
            // temperature(): temp register bytes -> 0.0625 °C
            I2cTransaction::write_read(ADDR, vec![REG_TEMP_XLSB], vec![0x00, 0x10, 0x00]),
            // pressure(): same
            I2cTransaction::write_read(ADDR, vec![REG_PRESS_XLSB], vec![0x04, 0x00, 0x00]),
            // chip_id
            I2cTransaction::write_read(ADDR, vec![REG_CHIP_ID], vec![0x50]),
            // rev_id
            I2cTransaction::write_read(ADDR, vec![REG_REV_ID], vec![0x32]),
            // status
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x09]),
            // interrupt_status
            I2cTransaction::write_read(ADDR, vec![REG_INT_STATUS], vec![0x11]),
            // data_ready
            I2cTransaction::write_read(ADDR, vec![REG_INT_STATUS], vec![0x01]),
            // configure(0x17, 4, 2, true): OSR=0x40|(4<<3)|2=0x62, ODR=(0x17<<2)|0x01=0x5D
            I2cTransaction::write(ADDR, vec![REG_OSR_CONFIG, 0x62]),
            I2cTransaction::write(ADDR, vec![REG_ODR_CONFIG, 0x5D]),
            // set_mode(STANDBY=0): ODR=(0x17<<2)|0=0x5C
            I2cTransaction::write(ADDR, vec![REG_ODR_CONFIG, 0x5C]),
            // set_mode(CONTINUOUS=3): ODR=(0x17<<2)|3=0x5F
            I2cTransaction::write(ADDR, vec![REG_ODR_CONFIG, 0x5F]),
            // set_iir_filter: DSP_CONFIG read, then DSP_CONFIG write, then DSP_IIR write
            I2cTransaction::write_read(ADDR, vec![REG_DSP_CONFIG], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_DSP_CONFIG, 0x28]),
            I2cTransaction::write(ADDR, vec![REG_DSP_IIR, (IIR_COEFF_3 << 3) | IIR_BYPASS]),
            // enable_drdy_interrupt(true): read INT_SOURCE, write with bit set
            I2cTransaction::write_read(ADDR, vec![REG_INT_SOURCE], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_INT_SOURCE, 0x01]),
            // enable_fifo_interrupt(true, false)
            I2cTransaction::write_read(ADDR, vec![REG_INT_SOURCE], vec![0x01]),
            I2cTransaction::write(ADDR, vec![REG_INT_SOURCE, 0x05]),
            // enable_oor_interrupt(true)
            I2cTransaction::write_read(ADDR, vec![REG_INT_SOURCE], vec![0x05]),
            I2cTransaction::write(ADDR, vec![REG_INT_SOURCE, 0x0D]),
            // configure_interrupt(mode=1, pol=1, od=true, enable=true) = 0x0F
            I2cTransaction::write(ADDR, vec![REG_INT_CONFIG, 0x0F]),
            // set_oor_threshold
            I2cTransaction::write(ADDR, vec![REG_OOR_THR_P_LSB, 0x70]),
            I2cTransaction::write(ADDR, vec![REG_OOR_THR_P_MSB, 0x0B]),
            I2cTransaction::write(ADDR, vec![REG_OOR_RANGE, 0x0C]),
            I2cTransaction::write(ADDR, vec![REG_OOR_CONFIG, 0x80]),
            // configure_fifo(FIFO_BOTH, FIFO_STREAM, 8): set_mode(0) first
            I2cTransaction::write(ADDR, vec![REG_ODR_CONFIG, 0x5C]),
            I2cTransaction::write(ADDR, vec![REG_FIFO_SEL, 0x03]),
            I2cTransaction::write(ADDR, vec![REG_FIFO_CONFIG, 0x08]),
            I2cTransaction::write(ADDR, vec![REG_ODR_CONFIG, 0x5F]),
            // fifo_count
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_COUNT], vec![0x04]),
            // effective_osr
            I2cTransaction::write_read(ADDR, vec![REG_OSR_EFF], vec![0xA0]),
            // odr_is_valid
            I2cTransaction::write_read(ADDR, vec![REG_OSR_EFF], vec![0xA0]),
            // altitude -> pressure() again
            I2cTransaction::write_read(ADDR, vec![REG_PRESS_XLSB], vec![0x04, 0x00, 0x00]),
        ]);
        let i2c = I2cMock::new(&transactions);

        let mut sensor = Bmp581Full::new(i2c, ADDR, false).expect("init");

        assert_eq!(sensor.temperature().unwrap(), 0.0625);
        assert_eq!(sensor.pressure().unwrap(), 0.0625);
        let (p, t) = sensor.both().unwrap();
        assert_eq!(p, 0.0625);
        assert_eq!(t, 0.0625);
        assert_eq!(sensor.chip_id().unwrap(), 0x50);
        assert_eq!(sensor.rev_id().unwrap(), 0x32);
        assert_eq!(sensor.status().unwrap(), 0x09);
        assert_eq!(sensor.interrupt_status().unwrap(), 0x11);
        assert_eq!(sensor.data_ready().unwrap(), true);

        sensor.configure(0x17, 4, 2, true).unwrap();
        sensor.set_mode(MODE_STANDBY).unwrap();
        sensor.set_mode(MODE_CONTINUOUS).unwrap();
        sensor.set_iir_filter(IIR_COEFF_3, IIR_BYPASS).unwrap();
        sensor.enable_drdy_interrupt(true).unwrap();
        sensor.enable_fifo_interrupt(true, false).unwrap();
        sensor.enable_oor_interrupt(true).unwrap();
        sensor.configure_interrupt(1, 1, true, true).unwrap();
        sensor.set_oor_threshold(110000.0, 200.0, 2).unwrap();
        sensor.configure_fifo(FIFO_BOTH, FIFO_STREAM, 8).unwrap();
        assert_eq!(sensor.fifo_count().unwrap(), 4);
        let (op, ot) = sensor.effective_osr().unwrap();
        assert_eq!(op, 4);
        assert_eq!(ot, 0);
        assert_eq!(sensor.odr_is_valid().unwrap(), true);

        let alt = sensor.altitude(101325.0).unwrap();
        assert!(alt >= -500.0 && alt <= 9000.0, "altitude = {}", alt);

        sensor.inner.inner.i2c.done();
    }
}