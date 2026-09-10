//! LPS33HW — water-resistant MEMS absolute pressure sensor (STMicroelectronics).
//!
//! Communicates over I²C (address 0x5C / 0x5D) or SPI. Pressure output is a
//! 24-bit two's complement signed integer; temperature is 16-bit two's
//! complement. Conversions:
//!
//! - `pressure_Pa = raw_pressure × 100 / 4096`
//! - `temperature_C = raw_temperature / 100`
//!
//! Auto-increment (IF_ADD_INC=1, default) is used to burst-read PRESS_OUT_XL
//! through TEMP_OUT_H (5 bytes) in one transaction. With BDU=1 the output
//! register latch only releases after PRESS_OUT_H has been read, so reading
//! the burst sequence releases the latch correctly.
//!
//! ## Constants
//!
//! Output data rate: [`ODR_POWER_DOWN`], [`ODR_1_HZ`], [`ODR_10_HZ`],
//! [`ODR_25_HZ`], [`ODR_50_HZ`], [`ODR_75_HZ`]
//! FIFO modes: [`FIFO_MODE_BYPASS`], [`FIFO_MODE_FIFO`], [`FIFO_MODE_STREAM`],
//! [`FIFO_MODE_STREAM_TO_FIFO`], [`FIFO_MODE_BYPASS_TO_STREAM`],
//! [`FIFO_MODE_DYNAMIC_STREAM`], [`FIFO_MODE_BYPASS_TO_FIFO`]
//! INT_DRDY signal: [`INT_S_DATA_SIGNALS`], [`INT_S_PRESSURE_HIGH`],
//! [`INT_S_PRESSURE_LOW`], [`INT_S_PRESSURE_BOTH`]
//! Status flags: [`STATUS_P_DA`], [`STATUS_T_DA`], [`STATUS_P_OR`],
//! [`STATUS_T_OR`]

use embedded_hal::i2c::{ErrorKind, I2c};

const REG_INTERRUPT_CFG: u8 = 0x0B;
const REG_THS_P_L: u8      = 0x0C;
const REG_THS_P_H: u8      = 0x0D;
const REG_WHO_AM_I: u8     = 0x0F;
const REG_CTRL_REG1: u8    = 0x10;
const REG_CTRL_REG2: u8    = 0x11;
const REG_CTRL_REG3: u8    = 0x12;
const REG_FIFO_CTRL: u8    = 0x14;
const REG_REF_P_XL: u8     = 0x15;
const REG_REF_P_L: u8      = 0x16;
const REG_REF_P_H: u8      = 0x17;
const REG_RPDS_L: u8       = 0x18;
const REG_RPDS_H: u8       = 0x19;
const REG_RES_CONF: u8     = 0x1A;
const REG_INT_SOURCE: u8   = 0x25;
const REG_FIFO_STATUS: u8  = 0x26;
const REG_STATUS: u8       = 0x27;
const REG_PRESS_XL: u8     = 0x28;
const REG_PRESS_L: u8      = 0x29;
const REG_PRESS_H: u8      = 0x2A;
const REG_TEMP_L: u8       = 0x2B;
const REG_TEMP_H: u8       = 0x2C;
const REG_LPFP_RES: u8     = 0x33;

const CHIP_ID: u8 = 0xB1;

const CTRL_REG1_DEFAULT: u8 = 0x12;  // ODR=001 (1 Hz), BDU=1
const CTRL_REG2_RESET: u8   = 0x04;  // SWRESET=1
const CTRL_REG2_DEFAULT: u8 = 0x10;  // IF_ADD_INC=1

const STATUS_P_DA_BIT: u8 = 0x01;
const STATUS_T_DA_BIT: u8 = 0x02;

const STATUS_TIMEOUT_ITERATIONS: u32 = 50;
const STATUS_POLL_INTERVAL_MS: u32 = 5;

/// Output data rate: power-down / one-shot.
pub const ODR_POWER_DOWN: u8 = 0;
/// Output data rate: 1 Hz.
pub const ODR_1_HZ: u8       = 1;
/// Output data rate: 10 Hz.
pub const ODR_10_HZ: u8      = 2;
/// Output data rate: 25 Hz.
pub const ODR_25_HZ: u8      = 3;
/// Output data rate: 50 Hz.
pub const ODR_50_HZ: u8      = 4;
/// Output data rate: 75 Hz.
pub const ODR_75_HZ: u8      = 5;

/// Low-pass filter bandwidth: ODR / 9.
pub const LPFP_BW_ODR_9: u8  = 0;
/// Low-pass filter bandwidth: ODR / 20.
pub const LPFP_BW_ODR_20: u8 = 1;

/// FIFO mode: Bypass.
pub const FIFO_MODE_BYPASS: u8           = 0;
/// FIFO mode: FIFO (fill to 32, then stop).
pub const FIFO_MODE_FIFO: u8             = 1;
/// FIFO mode: Stream (circular).
pub const FIFO_MODE_STREAM: u8           = 2;
/// FIFO mode: Stream-to-FIFO.
pub const FIFO_MODE_STREAM_TO_FIFO: u8   = 3;
/// FIFO mode: Bypass-to-Stream.
pub const FIFO_MODE_BYPASS_TO_STREAM: u8 = 4;
/// FIFO mode: Dynamic-Stream.
pub const FIFO_MODE_DYNAMIC_STREAM: u8   = 6;
/// FIFO mode: Bypass-to-FIFO.
pub const FIFO_MODE_BYPASS_TO_FIFO: u8   = 7;

/// INT_DRDY signal: data signals (DRDY / F_FTH / F_OVR / F_FSS5).
pub const INT_S_DATA_SIGNALS: u8  = 0;
/// INT_DRDY signal: pressure high.
pub const INT_S_PRESSURE_HIGH: u8 = 1;
/// INT_DRDY signal: pressure low.
pub const INT_S_PRESSURE_LOW: u8  = 2;
/// INT_DRDY signal: pressure low or high.
pub const INT_S_PRESSURE_BOTH: u8 = 3;

/// Status bit: pressure data available.
pub const STATUS_P_DA_FLAG: u8 = 0x01;
/// Status bit: temperature data available.
pub const STATUS_T_DA_FLAG: u8 = 0x02;
/// Status bit: pressure data overrun.
pub const STATUS_P_OR_FLAG: u8 = 0x10;
/// Status bit: temperature data overrun.
pub const STATUS_T_OR_FLAG: u8 = 0x20;

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_reg_bytes<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

fn read_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    read_reg_bytes(i2c, addr, reg, &mut buf)?;
    Ok(buf[0])
}

/// LPS33HW minimal driver — pressure (Pa) and temperature (°C).
///
/// Default: ODR = 1 Hz, BDU = 1, EN_LPFP = 0, IF_ADD_INC = 1.
pub struct Lps33hwMinimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Lps33hwMinimal<I2C> {
    /// Create a new `Lps33hwMinimal`, verify the chip ID, software-reset,
    /// and apply the default configuration (ODR=1 Hz, BDU=1).
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x5C or 0x5D).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut s = Self { i2c, addr };
        let chip_id = read_reg8(&mut s.i2c, s.addr, REG_WHO_AM_I)?;
        if chip_id != CHIP_ID {
            return Err(ErrorKind::Other.into());
        }
        // Software reset, then restore IF_ADD_INC=1, then default CTRL_REG1.
        write_reg(&mut s.i2c, s.addr, REG_CTRL_REG2, CTRL_REG2_RESET)?;
        delay_ms(1);
        write_reg(&mut s.i2c, s.addr, REG_CTRL_REG2, CTRL_REG2_DEFAULT)?;
        write_reg(&mut s.i2c, s.addr, REG_CTRL_REG1, CTRL_REG1_DEFAULT)?;
        Ok(s)
    }

    fn wait_status(&mut self, mask: u8) -> Result<(), I2C::Error> {
        for _ in 0..STATUS_TIMEOUT_ITERATIONS {
            let s = read_reg8(&mut self.i2c, self.addr, REG_STATUS)?;
            if s & mask == mask {
                return Ok(());
            }
            delay_ms(STATUS_POLL_INTERVAL_MS);
        }
        Ok(())
    }

    fn read_press_temp(&mut self) -> Result<(f32, f32), I2C::Error> {
        // Wait for both pressure and temperature data to be available.
        self.wait_status(STATUS_P_DA_BIT | STATUS_T_DA_BIT)?;
        let mut raw = [0u8; 5];
        read_reg_bytes(&mut self.i2c, self.addr, REG_PRESS_XL, &mut raw)?;
        let raw_press: i32 = ((raw[2] as i32) << 16) | ((raw[1] as i32) << 8) | (raw[0] as i32);
        let raw_press = if raw_press >= 0x800000 { raw_press - 0x1000000 } else { raw_press };
        let raw_temp: i32 = ((raw[4] as i32) << 8) | (raw[3] as i32);
        let raw_temp = if raw_temp >= 0x8000 { raw_temp - 0x10000 } else { raw_temp };
        let pressure_Pa = raw_press as f32 * 100.0 / 4096.0;
        let temperature_C = raw_temp as f32 / 100.0;
        Ok((pressure_Pa, temperature_C))
    }

    /// Read the calibrated absolute pressure.
    ///
    /// Waits for STATUS.P_DA, then bursts 5 bytes from PRESS_OUT_XL
    /// through TEMP_OUT_H. With BDU=1 the latch releases once PRESS_OUT_H
    /// has been read, which falls inside the burst.
    ///
    /// Returns pressure in pascals.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        let (p, _) = self.read_press_temp()?;
        Ok(p)
    }

    /// Read the calibrated temperature.
    ///
    /// Waits for STATUS.T_DA, then bursts 5 bytes from PRESS_OUT_XL
    /// through TEMP_OUT_H to release the BDU latch cleanly.
    ///
    /// Returns temperature in °C.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        let (_, t) = self.read_press_temp()?;
        Ok(t)
    }
}

/// LPS33HW full driver — extends minimal with configuration, one-shot, FIFO,
/// interrupt routing, AUTOZERO / AUTORIFP, soft reset, reboot, and pressure
/// offset.
pub struct Lps33hwFull<I2C> {
    inner: Lps33hwMinimal<I2C>,
}

impl<I2C: I2c> Lps33hwFull<I2C> {
    /// Create a new `Lps33hwFull`.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Lps33hwMinimal::new(i2c, addr)?;
        Ok(Self { inner })
    }

    /// Write CTRL_REG1 (ODR/BDU/EN_LPFP/LPFP_CFG/SIM) and the LC_EN bit
    /// inside RES_CONF.
    ///
    /// # Arguments
    /// * `odr` — Output data rate (0–5, use [`ODR_*`]).
    /// * `bdu` — Block data update (true = hold until PRESS_OUT_H read).
    /// * `en_lpfp` — Enable additional low-pass filter on pressure.
    /// * `lpfp_cfg` — LPF bandwidth when enabled (0=ODR/9, 1=ODR/20).
    /// * `lc_en` — Low-current mode (only writable in power-down).
    /// * `sim` — SPI 3-wire mode (false=4-wire, true=3-wire).
    pub fn configure(&mut self, odr: u8, bdu: bool, en_lpfp: bool, lpfp_cfg: u8,
                     lc_en: bool, sim: bool) -> Result<(), I2C::Error> {
        let ctrl1 = ((odr & 7) << 4)
            | ((en_lpfp as u8) << 3)
            | ((lpfp_cfg & 1) << 2)
            | ((bdu as u8) << 1)
            | (sim as u8);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, ctrl1)?;

        let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_RES_CONF)?;
        let new_res = (current & 0xFE) | (lc_en as u8);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RES_CONF, new_res)?;
        Ok(())
    }

    /// Trigger a one-shot measurement and return the new pressure and
    /// temperature as a pair.
    ///
    /// Requires ODR=000 (power-down). Sets ONE_SHOT in CTRL_REG2 and polls
    /// STATUS until both P_DA and T_DA are set, then bursts 5 bytes.
    pub fn one_shot(&mut self) -> Result<(f32, f32), I2C::Error> {
        let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, current | 0x01)?;
        for _ in 0..STATUS_TIMEOUT_ITERATIONS {
            let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)?;
            if (status & (STATUS_P_DA_BIT | STATUS_T_DA_BIT)) == (STATUS_P_DA_BIT | STATUS_T_DA_BIT) {
                let mut raw = [0u8; 5];
                read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_PRESS_XL, &mut raw)?;
                let raw_press: i32 = ((raw[2] as i32) << 16) | ((raw[1] as i32) << 8) | (raw[0] as i32);
                let raw_press = if raw_press >= 0x800000 { raw_press - 0x1000000 } else { raw_press };
                let raw_temp: i32 = ((raw[4] as i32) << 8) | (raw[3] as i32);
                let raw_temp = if raw_temp >= 0x8000 { raw_temp - 0x10000 } else { raw_temp };
                let pressure_Pa = raw_press as f32 * 100.0 / 4096.0;
                let temperature_C = raw_temp as f32 / 100.0;
                return Ok((pressure_Pa, temperature_C));
            }
            delay_ms(STATUS_POLL_INTERVAL_MS);
        }
        Ok((0.0, 0.0))
    }

    /// Read the STATUS register.
    ///
    /// Returns the raw status byte; bit 0 = P_DA, bit 1 = T_DA,
    /// bit 4 = P_OR, bit 5 = T_OR.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)
    }

    /// Read the INT_SOURCE register.
    ///
    /// Returns the raw byte; bit 0 = PH, bit 1 = PL, bit 2 = IA,
    /// bit 7 = BOOT_STATUS.
    pub fn interrupt_status(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_SOURCE)
    }

    /// Software-reset via SWRESET, wait for self-clear, restore defaults.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, CTRL_REG2_RESET)?;
        for _ in 0..STATUS_TIMEOUT_ITERATIONS {
            let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2)?;
            if current & 0x04 == 0 {
                break;
            }
            delay_ms(1);
        }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, CTRL_REG2_DEFAULT)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG1, CTRL_REG1_DEFAULT)?;
        Ok(())
    }

    /// Reload factory trimming from internal Flash via BOOT bit.
    pub fn reboot(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, 0x80)?;
        for _ in 0..100 {
            let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_SOURCE)?;
            if status & 0x80 == 0 {
                break;
            }
            delay_ms(STATUS_POLL_INTERVAL_MS);
        }
        Ok(())
    }

    /// Write RPDS to apply a one-point calibration offset.
    ///
    /// # Arguments
    /// * `offset_hpa` — Pressure offset in hectopascals.
    ///   1 RPDS LSB = 1/16 hPa.
    pub fn set_pressure_offset(&mut self, offset_hpa: f32) -> Result<(), I2C::Error> {
        let mut raw = (offset_hpa * 16.0).round() as i32;
        if raw < 0 {
            raw += 0x10000;
        }
        let raw = raw as u16;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RPDS_L, (raw & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RPDS_H, ((raw >> 8) & 0xFF) as u8)?;
        Ok(())
    }

    /// Set AUTOZERO=1 — current pressure is stored in REF_P.
    pub fn set_autozero(&mut self) -> Result<(), I2C::Error> {
        let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG, current | 0x20)
    }

    /// Clear AUTOZERO mode and reset REF_P to 0.
    pub fn clear_autozero(&mut self) -> Result<(), I2C::Error> {
        let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG, current | 0x10)
    }

    /// Set AUTORIFP=1 — next measurement value is stored in RPDS.
    pub fn set_autorifp(&mut self) -> Result<(), I2C::Error> {
        let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG, current | 0x80)
    }

    /// Clear AUTORIFP mode and reset RPDS to 0.
    pub fn clear_autorifp(&mut self) -> Result<(), I2C::Error> {
        let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG, current | 0x40)
    }

    /// Route CTRL_REG3 events to the INT_DRDY pin.
    ///
    /// # Arguments
    /// * `drdy` — Route data-ready.
    /// * `f_fth` — Route FIFO threshold.
    /// * `f_ovr` — Route FIFO overrun.
    /// * `f_fss5` — Route FIFO full (32 samples).
    /// * `int_s` — Signal selection (0=data, 1=high, 2=low, 3=both).
    /// * `active_low` — INT_DRDY active-low polarity.
    /// * `open_drain` — INT_DRDY open-drain drive.
    pub fn configure_interrupt(&mut self, drdy: bool, f_fth: bool, f_ovr: bool,
                               f_fss5: bool, int_s: u8, active_low: bool,
                               open_drain: bool) -> Result<(), I2C::Error> {
        let ctrl3 = ((active_low as u8) << 7)
            | ((open_drain as u8) << 6)
            | ((f_fss5 as u8) << 5)
            | ((f_fth as u8) << 4)
            | ((f_ovr as u8) << 3)
            | ((drdy as u8) << 2)
            | (int_s & 0x03);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG3, ctrl3)
    }

    /// Configure the differential pressure threshold interrupt.
    ///
    /// # Arguments
    /// * `high_en` — Enable interrupt on pressure above threshold.
    /// * `low_en` — Enable interrupt on pressure below threshold.
    /// * `threshold_hpa` — Pressure threshold in hPa. 1 LSB = 1/16 hPa.
    /// * `latch` — Latch the interrupt request until INT_SOURCE is read.
    pub fn configure_pressure_interrupt(&mut self, high_en: bool, low_en: bool,
                                        threshold_hpa: f32, latch: bool) -> Result<(), I2C::Error> {
        let raw_ths = ((threshold_hpa * 16.0).round() as i32 & 0xFFFF) as u16;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_THS_P_L, (raw_ths & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_THS_P_H, ((raw_ths >> 8) & 0xFF) as u8)?;

        let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG)?;
        let new_cfg = (current & 0xF0)
            | ((latch as u8) << 2)
            | ((high_en as u8) << 1)
            | (low_en as u8);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INTERRUPT_CFG, new_cfg)
    }

    /// Enable the FIFO with the given mode and watermark.
    ///
    /// # Arguments
    /// * `mode` — FIFO mode (0–7, excluding reserved value 5).
    /// * `watermark` — FIFO watermark level (0–31).
    pub fn enable_fifo(&mut self, mode: u8, watermark: u8) -> Result<(), I2C::Error> {
        if mode == 5 {
            // FIFO mode 5 is reserved; per spec, refuse to set it.
            return Ok(());
        }
        let ctrl = ((mode & 7) << 5) | (watermark & 0x1F);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_CTRL, ctrl)?;
        let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, current | 0x40)
    }

    /// Disable the FIFO and reset to Bypass mode.
    pub fn disable_fifo(&mut self) -> Result<(), I2C::Error> {
        let current = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CTRL_REG2, current & !0x40)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_CTRL, 0)
    }

    /// Read the FIFO_STATUS register.
    ///
    /// Returns the raw byte; bit 7 = FTH_FIFO, bit 6 = OVR,
    /// bits [5:0] = FSS count.
    pub fn fifo_status(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_FIFO_STATUS)
    }

    /// Read LPFP_RES to flush any transitory LPF state.
    pub fn reset_lpf(&mut self) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        read_reg_bytes(&mut self.inner.i2c, self.inner.addr, REG_LPFP_RES, &mut buf)
    }

    /// Read calibrated temperature. Delegates to the embedded minimal driver.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        self.inner.temperature()
    }

    /// Read calibrated pressure. Delegates to the embedded minimal driver.
    pub fn pressure(&mut self) -> Result<f32, I2C::Error> {
        self.inner.pressure()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x5C;

    fn init_transactions() -> Vec<I2cTransaction> {
        vec![
            // Minimal::new(): WHO_AM_I read.
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![CHIP_ID]),
            // SWRESET write, then restore IF_ADD_INC=1, then default CTRL_REG1.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, CTRL_REG2_RESET]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, CTRL_REG2_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, CTRL_REG1_DEFAULT]),
        ]
    }

    #[test]
    fn minimal_temperature_and_pressure() {
        // STATUS returns P_DA | T_DA immediately so read_press_temp() doesn't
        // loop. The burst returns pressure=100 Pa and temperature = 25 °C.
        // raw_pressure = 4096 = 0x001000 -> [XL=0x00, L=0x10, H=0x00]
        // raw_temperature = 2500 = 0x09C4 -> [L=0xC4, H=0x09]
        let mut transactions = init_transactions();
        transactions.extend(vec![
            // pressure()
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![STATUS_P_DA_BIT | STATUS_T_DA_BIT]),
            I2cTransaction::write_read(ADDR, vec![REG_PRESS_XL],
                vec![0x00, 0x10, 0x00, 0xC4, 0x09]),
            // temperature() re-issues the same burst
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![STATUS_P_DA_BIT | STATUS_T_DA_BIT]),
            I2cTransaction::write_read(ADDR, vec![REG_PRESS_XL],
                vec![0x00, 0x10, 0x00, 0xC4, 0x09]),
        ]);
        let i2c = I2cMock::new(&transactions);

        let mut chip = Lps33hwMinimal::new(i2c, ADDR).expect("init");
        let p = chip.pressure().expect("pressure");
        assert!((p - 100.0).abs() < 1e-3, "pressure = {}", p);
        let t = chip.temperature().expect("temperature");
        assert!((t - 25.0).abs() < 1e-3, "temperature = {}", t);
        chip.i2c.done();
    }

    #[test]
    fn full_api() {
        let mut transactions = init_transactions();
        transactions.extend(vec![
            // configure(odr=2, bdu=true, en_lpfp=true, lpfp_cfg=1, lc_en=false, sim=false):
            //   ctrl1 = (2<<4)|(1<<3)|(1<<2)|(1<<1)|0 = 0x2E
            //   res_conf = (current & 0xFE) | 0 = 0x00
            I2cTransaction::write_read(ADDR, vec![REG_RES_CONF], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x2E]),
            I2cTransaction::write(ADDR, vec![REG_RES_CONF, 0x00]),
            // one_shot():
            //   ctrl2 (current default 0x10) | 0x01 = 0x11
            //   status read returns 0x03 (inside one_shot's poll loop)
            //   one_shot then enters its `if` branch and calls read_press_temp,
            //   which calls wait_status (re-reads STATUS = 0x03) and bursts
            //   PRESS_XL..TEMP_OUT_H.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG2], vec![0x10]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x11]),
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x03]),
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x03]),
            I2cTransaction::write_read(ADDR, vec![REG_PRESS_XL],
                vec![0x00, 0x10, 0x00, 0xC4, 0x09]),
            // status()
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x03]),
            // interrupt_status()
            I2cTransaction::write_read(ADDR, vec![REG_INT_SOURCE], vec![0x04]),
            // reset():
            //   SWRESET write, then poll loop reads ctrl_reg2 (returns 0x00),
            //   then IF_ADD_INC=1, then default CTRL_REG1.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, CTRL_REG2_RESET]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG2], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, CTRL_REG2_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, CTRL_REG1_DEFAULT]),
            // reboot(): BOOT write, then poll loop reads int_source (returns 0x00)
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x80]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_SOURCE], vec![0x00]),
            // set_pressure_offset(offset_hPa=16.0):
            //   raw = 16 * 16 = 256 = 0x0100 -> RPDS_L=0x00, RPDS_H=0x01
            I2cTransaction::write(ADDR, vec![REG_RPDS_L, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_RPDS_H, 0x01]),
            // set_autozero(): read INTERRUPT_CFG (returns 0x00), then write 0x20.
            I2cTransaction::write_read(ADDR, vec![REG_INTERRUPT_CFG], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_INTERRUPT_CFG, 0x20]),
            // clear_autozero(): read INTERRUPT_CFG (returns 0x20), then write 0x30.
            I2cTransaction::write_read(ADDR, vec![REG_INTERRUPT_CFG], vec![0x20]),
            I2cTransaction::write(ADDR, vec![REG_INTERRUPT_CFG, 0x30]),
            // set_autorifp(): read INTERRUPT_CFG (returns 0x30), then write 0xB0.
            I2cTransaction::write_read(ADDR, vec![REG_INTERRUPT_CFG], vec![0x30]),
            I2cTransaction::write(ADDR, vec![REG_INTERRUPT_CFG, 0xB0]),
            // clear_autorifp(): read INTERRUPT_CFG (returns 0xB0), then write 0xF0.
            I2cTransaction::write_read(ADDR, vec![REG_INTERRUPT_CFG], vec![0xB0]),
            I2cTransaction::write(ADDR, vec![REG_INTERRUPT_CFG, 0xF0]),
            // configure_interrupt(drdy=true, f_fth=true, f_ovr=true, f_fss5=true,
            //                    int_s=3, active_low=true, open_drain=true):
            //   ctrl3 = (1<<7)|(1<<6)|(1<<5)|(1<<4)|(1<<3)|(1<<2)|3 = 0xFF
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG3, 0xFF]),
            // configure_pressure_interrupt(high_en=true, low_en=false,
            //                             threshold_hPa=16.0, latch=true):
            //   raw_ths = 16*16 = 256 = 0x0100 -> THS_P_L=0x00, THS_P_H=0x01
            //   current INTERRUPT_CFG (returns 0xF0), new_cfg = 0xF0 | (1<<2)|(1<<1)|0 = 0xF6
            I2cTransaction::write(ADDR, vec![REG_THS_P_L, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_THS_P_H, 0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_INTERRUPT_CFG], vec![0xF0]),
            I2cTransaction::write(ADDR, vec![REG_INTERRUPT_CFG, 0xF6]),
            // enable_fifo(mode=1, watermark=16):
            //   fifo_ctrl = (1<<5)|16 = 0x30
            //   ctrl_reg2 (returns 0x10) -> 0x50
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, 0x30]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG2], vec![0x10]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x50]),
            // disable_fifo():
            //   ctrl_reg2 (returns 0x50) -> 0x10
            //   FIFO_CTRL <- 0
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG2], vec![0x50]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x10]),
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, 0x00]),
            // fifo_status()
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_STATUS], vec![0x80]),
            // reset_lpf(): read LPFP_RES
            I2cTransaction::write_read(ADDR, vec![REG_LPFP_RES], vec![0x00]),
        ]);
        let i2c = I2cMock::new(&transactions);

        let mut chip = Lps33hwFull::new(i2c, ADDR).expect("init");

        chip.configure(2, true, true, 1, false, false).expect("configure");
        let (p, t) = chip.one_shot().expect("one_shot");
        assert!((p - 100.0).abs() < 1e-3);
        assert!((t - 25.0).abs() < 1e-3);

        let st = chip.status().expect("status");
        assert_eq!(st, 0x03);
        let intsrc = chip.interrupt_status().expect("interrupt_status");
        assert_eq!(intsrc, 0x04);

        chip.reset().expect("reset");
        chip.reboot().expect("reboot");
        chip.set_pressure_offset(16.0).expect("offset");
        chip.set_autozero().expect("autozero");
        chip.clear_autozero().expect("clear_autozero");
        chip.set_autorifp().expect("autorifp");
        chip.clear_autorifp().expect("clear_autorifp");
        chip.configure_interrupt(true, true, true, true, 3, true, true).expect("interrupt");
        chip.configure_pressure_interrupt(true, false, 16.0, true).expect("press_interrupt");
        chip.enable_fifo(1, 16).expect("enable_fifo");
        chip.disable_fifo().expect("disable_fifo");
        let fst = chip.fifo_status().expect("fifo_status");
        assert_eq!(fst, 0x80);
        chip.reset_lpf().expect("reset_lpf");

        chip.inner.i2c.done();
    }
}