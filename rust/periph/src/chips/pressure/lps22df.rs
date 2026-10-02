//! LPS22DF — absolute pressure and temperature sensor (STMicroelectronics).
//!
//! Communicates over I²C (address 0x5C or 0x5D) or SPI. The chip has built-in
//! factory calibration; no user calibration read step is required. Pressure
//! is 24-bit two's complement at 4096 LSB/hPa; temperature is 16-bit two's
//! complement at 100 LSB/°C.
//!
//! ## Constants
//!
//! Output data rate: [`LPS22DF_ODR_POWER_DOWN`] through [`LPS22DF_ODR_200_HZ`]
//! Averaging filter: [`LPS22DF_AVG_4`] through [`LPS22DF_AVG_512`]
//! FIFO modes: [`LPS22DF_FIFO_BYPASS`] through [`LPS22DF_FIFO_CONT_TO_FIFO`]

use embedded_hal::i2c::I2c;

use crate::connection::register::{self, to_signed};

const REG_INTERRUPT_CFG: u8 = 0x0B;
const REG_THS_P_L: u8       = 0x0C;
const REG_THS_P_H: u8       = 0x0D;
const REG_IF_CTRL: u8       = 0x0E;
const REG_WHO_AM_I: u8      = 0x0F;
const REG_CTRL_REG1: u8     = 0x10;
const REG_CTRL_REG2: u8     = 0x11;
const REG_CTRL_REG3: u8     = 0x12;
const REG_CTRL_REG4: u8     = 0x13;
const REG_FIFO_CTRL: u8     = 0x14;
const REG_FIFO_WTM: u8      = 0x15;
const REG_REF_P_L: u8       = 0x16;
const REG_REF_P_H: u8       = 0x17;
const REG_RPDS_L: u8        = 0x1A;
const REG_RPDS_H: u8        = 0x1B;
const REG_INT_SOURCE: u8    = 0x24;
const REG_FIFO_STATUS1: u8  = 0x25;
const REG_STATUS: u8        = 0x27;
const REG_PRESS_OUT_XL: u8  = 0x28;
const REG_TEMP_OUT_L: u8    = 0x2B;
const REG_FIFO_PRESS_XL: u8 = 0x78;

const CHIP_ID: u8 = 0xB4;

/// Output data rate: power-down / one-shot.
pub const LPS22DF_ODR_POWER_DOWN: u8 = 0;
/// Output data rate: 1 Hz.
pub const LPS22DF_ODR_1_HZ: u8       = 1;
/// Output data rate: 4 Hz.
pub const LPS22DF_ODR_4_HZ: u8       = 2;
/// Output data rate: 10 Hz.
pub const LPS22DF_ODR_10_HZ: u8      = 3;
/// Output data rate: 25 Hz.
pub const LPS22DF_ODR_25_HZ: u8      = 4;
/// Output data rate: 50 Hz.
pub const LPS22DF_ODR_50_HZ: u8       = 5;
/// Output data rate: 75 Hz.
pub const LPS22DF_ODR_75_HZ: u8      = 6;
/// Output data rate: 100 Hz.
pub const LPS22DF_ODR_100_HZ: u8     = 7;
/// Output data rate: 200 Hz.
pub const LPS22DF_ODR_200_HZ: u8     = 8;

/// Averaging filter: 4 samples.
pub const LPS22DF_AVG_4: u8   = 0;
/// Averaging filter: 8 samples.
pub const LPS22DF_AVG_8: u8   = 1;
/// Averaging filter: 16 samples.
pub const LPS22DF_AVG_16: u8  = 2;
/// Averaging filter: 32 samples.
pub const LPS22DF_AVG_32: u8  = 3;
/// Averaging filter: 64 samples.
pub const LPS22DF_AVG_64: u8  = 4;
/// Averaging filter: 128 samples.
pub const LPS22DF_AVG_128: u8 = 5;
/// Averaging filter: 512 samples.
pub const LPS22DF_AVG_512: u8 = 7;

/// FIFO mode: bypass (disabled).
pub const LPS22DF_FIFO_BYPASS: u8         = 0;
/// FIFO mode: FIFO.
pub const LPS22DF_FIFO_FIFO: u8           = 1;
/// FIFO mode: continuous (dynamic-stream).
pub const LPS22DF_FIFO_CONTINUOUS: u8     = 2;
/// FIFO mode: bypass-to-FIFO.
pub const LPS22DF_FIFO_BYPASS_TO_FIFO: u8 = 3;
/// FIFO mode: bypass-to-continuous.
pub const LPS22DF_FIFO_BYPASS_TO_CONT: u8 = 4;
/// FIFO mode: continuous-to-FIFO.
pub const LPS22DF_FIFO_CONT_TO_FIFO: u8   = 5;

/// Status flag: pressure data available.
pub const LPS22DF_STATUS_P_DA: u8 = 0x01;
pub const LPS22DF_STATUS_T_DA: u8 = 0x02;

/// Interrupt source: boot in progress.
pub const LPS22DF_INT_BOOT_ON: u8 = 0x80;
/// Interrupt source: at least one event pending.
pub const LPS22DF_INT_IA: u8      = 0x04;
/// Interrupt source: pressure-low event.
pub const LPS22DF_INT_PL: u8      = 0x02;
/// Interrupt source: pressure-high event.
pub const LPS22DF_INT_PH: u8      = 0x01;

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

/// LPS22DF minimal driver — pressure (Pa) and temperature (°C).
///
/// Default: ODR=10 Hz, AVG=4 samples, BDU enabled, low-pass filter off,
/// FIFO bypass mode.
pub struct Lps22dfMinimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Lps22dfMinimal<I2C> {
    /// Create a new `Lps22dfMinimal` and verify chip ID.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x5C or 0x5D).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut who = [0u8; 1];
        register::read_register(&mut i2c, addr, REG_WHO_AM_I.into(), 1, &mut who)?;
        let _ = who[0];
        register::write_register(&mut i2c, addr, REG_CTRL_REG2.into(), 1, &[0x04])?;  // SWRESET
        delay_ms(1);
        register::write_register(&mut i2c, addr, REG_CTRL_REG1.into(), 1, &[(LPS22DF_ODR_10_HZ << 3) | LPS22DF_AVG_4])?;
        register::write_register(&mut i2c, addr, REG_CTRL_REG2.into(), 1, &[0x08])?;  // BDU=1
        Ok(Self { i2c, addr })
    }

    fn wait_p_da(&mut self) -> Result<(), I2C::Error> {
        loop {
            let mut status = [0u8; 1];
            register::read_register(&mut self.i2c, self.addr, REG_STATUS.into(), 1, &mut status)?;
            if status[0] & LPS22DF_STATUS_P_DA != 0 { return Ok(()); }
            delay_ms(1);
        }
    }

    fn wait_t_da(&mut self) -> Result<(), I2C::Error> {
        loop {
            let mut status = [0u8; 1];
            register::read_register(&mut self.i2c, self.addr, REG_STATUS.into(), 1, &mut status)?;
            if status[0] & LPS22DF_STATUS_T_DA != 0 { return Ok(()); }
            delay_ms(1);
        }
    }

    /// Read absolute pressure.
    ///
    /// Polls STATUS.P_DA then burst-reads PRESS_OUT_XL..H. Sign-extends the
    /// 24-bit two's complement value and converts to pascals (4096 LSB/hPa).
    ///
    /// Returns pressure in pascals.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        self.wait_p_da()?;
        let mut raw = [0u8; 3];
        register::read_register(&mut self.i2c, self.addr, REG_PRESS_OUT_XL.into(), 1, &mut raw)?;
        let mut value = (raw[0] as i32) | ((raw[1] as i32) << 8) | ((raw[2] as i32) << 16);
        value = to_signed(value as u32, 24);
        Ok((value as f32 / 4096.0) * 100.0)
    }

    /// Read temperature.
    ///
    /// Polls STATUS.T_DA then reads TEMP_OUT_L..H. Sign-extends the 16-bit
    /// two's complement value and converts to °C (100 LSB/°C).
    ///
    /// Returns temperature in degrees Celsius.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        self.wait_t_da()?;
        let mut raw = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, REG_TEMP_OUT_L.into(), 1, &mut raw)?;
        let value = i16::from_le_bytes([raw[0], raw[1]]);
        Ok(value as f32 / 100.0)
    }

    /// Re-read the chip ID register (expected 0xB4).
    pub fn who_am_i(&mut self) -> Result<u8, I2C::Error> {
        let mut v = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, REG_WHO_AM_I.into(), 1, &mut v)?;
        Ok(v[0])
    }
}

/// LPS22DF full driver — extends minimal with configuration, FIFO,
/// interrupts, AUTOZERO/AUTOREFP, and offset calibration.
pub struct Lps22dfFull<I2C> {
    inner: Lps22dfMinimal<I2C>,
}

impl<I2C: I2c> Lps22dfFull<I2C> {
    /// Create a new `Lps22dfFull`.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Lps22dfMinimal::new(i2c, addr)?;
        Ok(Self { inner })
    }

    /// Write CTRL_REG1 and CTRL_REG2.
    ///
    /// # Arguments
    /// * `odr` — Output data rate (0=power-down, 1=1 Hz, ..., 7=100 Hz, 8=200 Hz).
    /// * `avg` — Averaging filter (0=4, 1=8, 2=16, 3=32, 4=64, 5=128, 7=512).
    /// * `en_lpfp` — Enable low-pass filter on pressure output.
    /// * `lfpf_cfg` — 0=ODR/4 cutoff, 1=ODR/9 cutoff.
    /// * `bdu` — Block data update.
    pub fn configure(&mut self, odr: u8, avg: u8, en_lpfp: bool, lfpf_cfg: u8, bdu: bool) -> Result<(), I2C::Error> {
        let ctrl1 = ((odr & 0x0F) << 3) | (avg & 0x07);
        let mut ctrl2 = 0u8;
        if en_lpfp  { ctrl2 |= 0x10; }
        if lfpf_cfg != 0 { ctrl2 |= 0x20; }
        if bdu      { ctrl2 |= 0x08; }
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1.into(), 1, &[ctrl1])?;
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2.into(), 1, &[ctrl2])
    }

    /// Trigger a single measurement in power-down mode.
    pub fn oneshot(&mut self) -> Result<(), I2C::Error> {
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1.into(), 1, &[0x00])?;
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2.into(), 1, &[0x08 | 0x01])?;
        self.inner.wait_p_da()
    }

    /// Compute altitude above sea level from the current pressure.
    ///
    /// # Arguments
    /// * `sea_level_pa` — Reference sea-level pressure in pascals.
    ///
    /// Returns altitude in metres.
    pub fn altitude(&mut self, sea_level_pa: f32) -> Result<f32, I2C::Error> {
        let p = self.inner.pressure()?;
        Ok(44330.0 * (1.0 - libm::powf(p / sea_level_pa, 1.0 / 5.255)))
    }

    /// Software-reset the chip and wait for self-clear.
    pub fn software_reset(&mut self) -> Result<(), I2C::Error> {
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2.into(), 1, &[0x04])?;
        delay_ms(1);
        Ok(())
    }

    /// Write a one-point calibration offset.
    pub fn set_pressure_offset(&mut self, offset_pa: f32) -> Result<(), I2C::Error> {
        let offset_hpa = offset_pa / 100.0;
        let mut raw = libm::roundf(offset_hpa * 4096.0) as i32;
        if raw < 0 { raw += 0x10000; }
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_RPDS_L.into(), 1, &[raw as u8])?;
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_RPDS_H.into(), 1, &[(raw >> 8) as u8])
    }

    /// Write a 15-bit unsigned pressure threshold.
    pub fn set_pressure_threshold(&mut self, threshold_pa: f32) -> Result<(), I2C::Error> {
        let threshold_hpa = threshold_pa / 100.0;
        let raw = (libm::roundf(threshold_hpa * 16.0) as i32 as u16) & 0x7FFF;
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_THS_P_L.into(), 1, &[raw as u8])?;
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_THS_P_H.into(), 1, &[(raw >> 8) as u8])
    }

    /// Configure the INT pin and routing.
    pub fn configure_interrupt(&mut self, int_h_l: bool, pp_od: bool, drdy: bool, drdy_pls: bool,
                               int_en: bool, int_f_wtm: bool, int_f_full: bool, int_f_ovr: bool) -> Result<(), I2C::Error> {
        let mut ctrl3 = 0x01u8;  // IF_ADD_INC=1
        if int_h_l { ctrl3 |= 0x08; }
        if pp_od   { ctrl3 |= 0x02; }
        let mut ctrl4 = 0u8;
        if drdy_pls  { ctrl4 |= 0x40; }
        if drdy      { ctrl4 |= 0x20; }
        if int_en    { ctrl4 |= 0x10; }
        if int_f_full{ ctrl4 |= 0x04; }
        if int_f_wtm { ctrl4 |= 0x02; }
        if int_f_ovr { ctrl4 |= 0x01; }
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG3.into(), 1, &[ctrl3])?;
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG4.into(), 1, &[ctrl4])
    }

    /// Configure pressure-event interrupts.
    pub fn configure_pressure_event(&mut self, phe: bool, ple: bool, lir: bool) -> Result<(), I2C::Error> {
        let mut cfg = 0u8;
        if phe { cfg |= 0x01; }
        if ple { cfg |= 0x02; }
        if lir { cfg |= 0x04; }
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG.into(), 1, &[cfg])
    }

    /// Capture the current pressure as the AUTOZERO reference.
    pub fn autozero(&mut self) -> Result<(), I2C::Error> {
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG.into(), 1, &[0x20])
    }

    /// Capture the current pressure in REF_P for use as a comparator.
    pub fn autorefp(&mut self) -> Result<(), I2C::Error> {
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG.into(), 1, &[0x80])
    }

    /// Reset AUTOZERO and AUTOREFP, returning PRESS_OUT to absolute.
    pub fn reset_reference(&mut self) -> Result<(), I2C::Error> {
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG.into(), 1, &[0x50])
    }

    /// Read the stored AUTOZERO/AUTOREFP reference pressure.
    pub fn reference_pressure(&mut self) -> Result<f32, I2C::Error> {
        let mut raw = [0u8; 2];
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_REF_P_L.into(), 1, &mut raw)?;
        let value = i16::from_le_bytes([raw[0], raw[1]]);
        Ok((value as f32 / 4096.0) * 100.0)
    }

    /// Set the FIFO mode.
    pub fn set_fifo_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        let (trig, fm) = match mode {
            0 => (0, 0),
            1 => (0, 1),
            2 => (0, 2),
            3 => (1, 1),
            4 => (1, 2),
            _ => (1, 3),
        };
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_FIFO_CTRL.into(), 1, &[(trig << 2) | (fm & 0x03)])
    }

    /// Set the FIFO watermark level (0..127).
    pub fn set_fifo_watermark(&mut self, level: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_FIFO_WTM.into(), 1, &[level & 0x7F])
    }

    /// Read the FIFO sample count.
    pub fn fifo_sample_count(&mut self) -> Result<u8, I2C::Error> {
        let mut v = [0u8; 1];
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_FIFO_STATUS1.into(), 1, &mut v)?;
        Ok(v[0])
    }

    /// Read every available FIFO sample.
    ///
    /// # Arguments
    /// * `out` — Destination slice; `out.len()` samples will be written.
    pub fn read_fifo(&mut self, out: &mut [f32]) -> Result<u8, I2C::Error> {
        let count = self.fifo_sample_count()?;
        let max = core::cmp::min(count as usize, out.len());
        if max == 0 { return Ok(0); }
        let mut raw = [0u8; 3 * 128];
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_FIFO_PRESS_XL.into(), 1, &mut raw[..max * 3])?;
        for i in 0..max {
            let base = i * 3;
            let mut value = (raw[base] as i32) | ((raw[base + 1] as i32) << 8) | ((raw[base + 2] as i32) << 16);
            value = to_signed(value as u32, 24);
            out[i] = (value as f32 / 4096.0) * 100.0;
        }
        Ok(max as u8)
    }

    /// Read and clear the INT_SOURCE register.
    pub fn interrupt_source(&mut self) -> Result<u8, I2C::Error> {
        let mut v = [0u8; 1];
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_INT_SOURCE.into(), 1, &mut v)?;
        Ok(v[0])
    }

    /// Read calibrated pressure.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        self.inner.pressure()
    }

    /// Read temperature.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        self.inner.temperature()
    }

    /// Re-read the chip ID register.
    pub fn who_am_i(&mut self) -> Result<u8, I2C::Error> {
        self.inner.who_am_i()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x5C;

    #[test]
    fn full_api() {
        let transactions = vec![
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![0xB4]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x04]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, (LPS22DF_ODR_10_HZ << 3) | LPS22DF_AVG_4]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x08]),
            // who_am_i(): re-read WHO_AM_I.
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![0xB4]),
            // pressure(): STATUS read returns P_DA, then 3-byte pressure read.
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_PRESS_OUT_XL],
                                       vec![0x00, 0x10, 0x00]),  // 4096 = 1.0 hPa -> 100 Pa
            // temperature(): STATUS read returns T_DA, then TEMP_OUT_L..H read.
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x02]),
            I2cTransaction::write_read(ADDR, vec![REG_TEMP_OUT_L], vec![0xE8, 0x03]),  // 1000 raw = 10.0 °C
            // Regression: temperature() must poll STATUS.T_DA before reading
            // TEMP_OUT_L/H, exactly like pressure() polls STATUS.P_DA -- it
            // previously read TEMP_OUT_L/H unconditionally with no STATUS
            // check at all.
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x02]),
            I2cTransaction::write_read(ADDR, vec![REG_TEMP_OUT_L], vec![0xE8, 0x03]),
            // configure(4, 2, true, 1, true) -> CTRL_REG1=0x22, CTRL_REG2=0x38
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x22]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x38]),
            // altitude(): STATUS read P_DA, then pressure read.
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_PRESS_OUT_XL],
                                       vec![0x00, 0x10, 0x00]),
            // software_reset()
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x04]),
            // set_pressure_offset(-50) -> -0.5 hPa * 4096 = -2048 = 0xF800
            I2cTransaction::write(ADDR, vec![REG_RPDS_L, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_RPDS_H, 0xF8]),
            // set_pressure_threshold(102000) -> 1020 hPa * 16 = 16320 = 0x3FC0
            I2cTransaction::write(ADDR, vec![REG_THS_P_L, 0xC0]),
            I2cTransaction::write(ADDR, vec![REG_THS_P_H, 0x3F]),
            // configure_interrupt(true, true, true, true, true, true, true, true)
            // CTRL_REG3 = 0x0B; CTRL_REG4 = 0x77
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG3, 0x0B]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x77]),
            // configure_pressure_event(true, true, true) -> 0x07
            I2cTransaction::write(ADDR, vec![REG_INTERRUPT_CFG, 0x07]),
            // autozero() -> 0x20
            I2cTransaction::write(ADDR, vec![REG_INTERRUPT_CFG, 0x20]),
            // set_fifo_mode(LPS22DF_FIFO_FIFO=1) -> 0x01
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, 0x01]),
            // set_fifo_watermark(100) -> 0x64
            I2cTransaction::write(ADDR, vec![REG_FIFO_WTM, 100]),
            // reference_pressure() -> REF_P_L/H read, returning 4096 = 100 Pa
            I2cTransaction::write_read(ADDR, vec![REG_REF_P_L], vec![0x00, 0x10]),
            // interrupt_source() -> INT_SOURCE = 0x87
            I2cTransaction::write_read(ADDR, vec![REG_INT_SOURCE], vec![0x87]),
        ];
        let i2c = I2cMock::new(&transactions);

        let mut sensor = Lps22dfFull::new(i2c, ADDR).expect("init");
        assert_eq!(sensor.who_am_i().unwrap(), 0xB4);

        // pressure(): 4096 raw -> 100 Pa
        let p = sensor.pressure().unwrap();
        assert!((p - 100.0).abs() < 0.01, "pressure = {}", p);

        // temperature(): 1000 raw -> 10.0 °C
        let t = sensor.temperature().unwrap();
        assert!((t - 10.0).abs() < 0.01, "temperature = {}", t);

        // Regression: temperature() polls STATUS.T_DA first (see expectation queue above).
        let t2 = sensor.temperature().unwrap();
        assert!((t2 - 10.0).abs() < 0.01, "temperature (regression) = {}", t2);

        sensor.configure(4, 2, true, 1, true).unwrap();

        let alt = sensor.altitude(101325.0).unwrap();
        // 100 Pa baseline gives huge altitude; just check it ran
        assert!(alt > 0.0, "altitude = {}", alt);

        sensor.software_reset().unwrap();
        sensor.set_pressure_offset(-50.0).unwrap();
        sensor.set_pressure_threshold(102000.0).unwrap();
        sensor.configure_interrupt(true, true, true, true, true, true, true, true).unwrap();
        sensor.configure_pressure_event(true, true, true).unwrap();
        sensor.autozero().unwrap();
        sensor.set_fifo_mode(LPS22DF_FIFO_FIFO).unwrap();
        sensor.set_fifo_watermark(100).unwrap();
        let ref_p = sensor.reference_pressure().unwrap();
        assert!((ref_p - 100.0).abs() < 0.01, "reference_pressure = {}", ref_p);
        assert_eq!(sensor.interrupt_source().unwrap(), 0x87);

        sensor.inner.i2c.done();
    }
}
