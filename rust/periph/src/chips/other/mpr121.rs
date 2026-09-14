//! MPR121 — proximity capacitive touch sensor controller (Freescale/NXP).
//!
//! Communicates over I²C at up to 400 kHz (4 selectable addresses: 0x5A
//! default, 0x5B / 0x5C / 0x5D via the ADDR pin). Provides 12 electrode
//! touch/release inputs plus a 13th virtual ELEPROX electrode that sums
//! all 12 for proximity-style detection. Each electrode has independent
//! baseline tracking, configurable touch/release thresholds with
//! hysteresis, and a debounce counter. An active-low open-drain INT pin
//! asserts on any touch or release event.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const REG_ELE0_7_TOUCH: u8 = 0x00;
const REG_ELE8_PROX_TCH: u8 = 0x01;
const REG_ELE0_7_OOR: u8 = 0x02;
const REG_MHDR: u8 = 0x2B;
const REG_NHDR: u8 = 0x2C;
const REG_MHDF: u8 = 0x2F;
const REG_NHDF: u8 = 0x30;
const REG_E0TTH: u8 = 0x41;
const REG_E0RTH: u8 = 0x42;
const REG_EPROXTTH: u8 = 0x59;
const REG_EPROXRTH: u8 = 0x5A;
const REG_DEBOUNCE: u8 = 0x5B;
const REG_CDC_CONFIG: u8 = 0x5C;
const REG_CDT_CONFIG: u8 = 0x5D;
const REG_ECR: u8 = 0x5E;
const REG_AUTOCONFIG0: u8 = 0x7B;
const REG_AUTOCONFIG1: u8 = 0x7C;
const REG_USL: u8 = 0x7D;
const REG_LSL: u8 = 0x7E;
const REG_TL: u8 = 0x7F;
const REG_SRST: u8 = 0x80;

const SOFT_RESET_KEY: u8 = 0x63;
const TOUCH_DEFAULT: u8 = 12;
const RELEASE_DEFAULT: u8 = 6;
const CDC_CONFIG_DEFAULT: u8 = 0x10;
const CDT_CONFIG_DEFAULT: u8 = 0x24;
const AUTOCONFIG0_DEFAULT: u8 = 0x0B;
const ECR_DEFAULT: u8 = 0x8C;
const USL_3V3: u8 = 0xC9;
const TL_3V3: u8 = 0xB4;
const LSL_3V3: u8 = 0x82;

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(buf[0])
}

fn read_reg16<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8, 0];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok((buf[0] as u16) | (((buf[1] & 0x03) as u16) << 8))
}

fn read_touched(i2c: &mut I2C, addr: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8, 0];
    i2c.write_read(addr, &[REG_ELE0_7_TOUCH], &mut buf)?;
    Ok((buf[0] as u16) | (((buf[1] & 0x0F) as u16) << 8))
}

fn read_oor(i2c: &mut I2C, addr: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8, 0];
    i2c.write_read(addr, &[REG_ELE0_7_OOR], &mut buf)?;
    Ok((buf[0] as u16) | (((buf[1] & 0x1F) as u16) << 8))
}

/// MPR121 minimal driver — 12-electrode touch/release detection.
///
/// Writes sensible defaults at construction: touch threshold 12 / release
/// threshold 6 for all 12 electrodes, MHDR/NHDR/MHDF/NHDF=1 baseline filter
/// defaults, CDC_CONFIG=0x10 (16 µA, FFI=6 samples), CDT_CONFIG=0x24
/// (CDT=1 µS, ESI=16 ms sample interval), AUTOCONFIG0=0x0B,
/// USL=0xC9/TL=0xB4/LSL=0x82 (3.3 V VDD), ECR=0x8C (all 12 electrodes).
pub struct Mpr121Minimal<I2C> {
    pub(crate) i2c: I2C,
    pub(crate) addr: u8,
}

impl<I2C: I2C> Mpr121Minimal<I2C> {
    /// Create a new `Mpr121Minimal` and run the default initialization sequence.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (typically `0x5A`).
    /// * `delay` — Delay provider; used for the 1 ms soft-reset wait.
    pub fn new(mut i2c: I2C, addr: u8, delay: &mut impl DelayNs) -> Result<Self, I2C::Error> {
        write_reg(&mut i2c, addr, REG_SRST, SOFT_RESET_KEY)?;
        delay.delay_ms(1);
        write_reg(&mut i2c, addr, REG_MHDR, 0x01)?;
        write_reg(&mut i2c, addr, REG_NHDR, 0x01)?;
        write_reg(&mut i2c, addr, REG_MHDF, 0x01)?;
        write_reg(&mut i2c, addr, REG_NHDF, 0x01)?;
        write_reg(&mut i2c, addr, REG_CDC_CONFIG, CDC_CONFIG_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_CDT_CONFIG, CDT_CONFIG_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_USL, USL_3V3)?;
        write_reg(&mut i2c, addr, REG_TL,  TL_3V3)?;
        write_reg(&mut i2c, addr, REG_LSL, LSL_3V3)?;
        write_reg(&mut i2c, addr, REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT)?;
        for n in 0..12u8 {
            write_reg(&mut i2c, addr, REG_E0TTH + 2 * n, TOUCH_DEFAULT)?;
            write_reg(&mut i2c, addr, REG_E0RTH + 2 * n, RELEASE_DEFAULT)?;
        }
        write_reg(&mut i2c, addr, REG_ECR, ECR_DEFAULT)?;
        Ok(Self { i2c, addr })
    }

    /// Read the 12-bit electrode touch bitmask.
    ///
    /// Reads ELE0_7_TOUCH and ELE8_PROX_TOUCH as a coherent two-byte
    /// snapshot from register 0x00; ELEPROX is masked out.
    ///
    /// Returns the 12-bit bitmask; bit n = 1 if ELEn is currently touched.
    pub fn touched(&mut self) -> Result<u16, I2C::Error> {
        read_touched(&mut self.i2c, self.addr)
    }

    /// Check whether a single electrode is currently touched.
    ///
    /// # Arguments
    /// * `electrode` — Electrode index 0-11.
    pub fn is_touched(&mut self, electrode: u8) -> Result<bool, I2C::Error> {
        assert!(electrode < 12);
        Ok((self.touched()? & (1u16 << electrode)) != 0)
    }
}

/// MPR121 full driver — extends [`Mpr121Minimal`] with Stop/Run control,
/// per-electrode and per-proximity threshold configuration, filtered and
/// baseline access, baseline-filter and AFE configuration, debounce,
/// autoconfig recomputation, OOR status, and over-current clear.
pub struct Mpr121Full<I2C> {
    inner: Mpr121Minimal<I2C>,
}

impl<I2C: I2C> Mpr121Full<I2C> {
    pub const SOURCE_OOR: u8 = 0x04;
    pub const SOURCE_ARF: u8 = 0x02;
    pub const SOURCE_ACF: u8 = 0x01;

    /// Create a new `Mpr121Full` and run the default initialization sequence.
    ///
    /// Same arguments as [`Mpr121Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, delay: &mut impl DelayNs) -> Result<Self, I2C::Error> {
        let inner = Mpr121Minimal::new(i2c, addr, delay)?;
        Ok(Self { inner })
    }

    /// Software-reset the chip and re-apply Minimal defaults.
    pub fn reset(&mut self, delay: &mut impl DelayNs) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_SRST, SOFT_RESET_KEY)?;
        delay.delay_ms(1);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MHDR, 0x01)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_NHDR, 0x01)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MHDF, 0x01)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_NHDF, 0x01)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CDC_CONFIG, CDC_CONFIG_DEFAULT)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CDT_CONFIG, CDT_CONFIG_DEFAULT)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_USL, USL_3V3)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TL,  TL_3V3)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_LSL, LSL_3V3)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT)?;
        for n in 0..12u8 {
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_E0TTH + 2 * n, TOUCH_DEFAULT)?;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_E0RTH + 2 * n, RELEASE_DEFAULT)?;
        }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ECR, ECR_DEFAULT)
    }

    /// Enter Stop Mode (ECR=0x00).
    pub fn stop(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ECR, 0x00)
    }

    /// Enter Run Mode with the given electrode configuration.
    ///
    /// # Arguments
    /// * `n_electrodes` — Number of electrodes to enable 1-12.
    /// * `cl`           — Calibration lock / baseline init 0-3.
    /// * `eleprox_en`   — Proximity enable 0-3.
    pub fn start(&mut self, n_electrodes: u8, cl: u8, eleprox_en: u8) -> Result<(), I2C::Error> {
        assert!(n_electrodes >= 1 && n_electrodes <= 12);
        assert!(cl <= 3);
        assert!(eleprox_en <= 3);
        let ecr = ((cl & 0x03) << 6) | ((eleprox_en & 0x03) << 4) | (n_electrodes & 0x0F);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ECR, ecr)
    }

    /// Set touch and release thresholds for a single electrode.
    ///
    /// # Arguments
    /// * `electrode` — Electrode index 0-11.
    /// * `touch`     — Touch threshold 0-255.
    /// * `release`   — Release threshold 0-255.
    pub fn configure_thresholds(&mut self, electrode: u8, touch: u8, release: u8) -> Result<(), I2C::Error> {
        assert!(electrode < 12);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_E0TTH + 2 * electrode, touch)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_E0RTH + 2 * electrode, release)
    }

    /// Apply the same touch and release thresholds to all 12 electrodes.
    pub fn configure_all_thresholds(&mut self, touch: u8, release: u8) -> Result<(), I2C::Error> {
        for n in 0..12u8 {
            self.configure_thresholds(n, touch, release)?;
        }
        Ok(())
    }

    /// Set ELEPROX touch and release thresholds.
    pub fn configure_proximity_thresholds(&mut self, touch: u8, release: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_EPROXTTH, touch)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_EPROXRTH, release)
    }

    /// Read the 10-bit filtered capacitance data for an electrode.
    ///
    /// # Arguments
    /// * `electrode` — 0-11 for ELE0-ELE11, 12 for ELEPROX.
    pub fn filtered(&mut self, electrode: u8) -> Result<u16, I2C::Error> {
        assert!(electrode <= 12);
        let addr = if electrode == 12 { 0x1C } else { 0x04 + 2 * electrode };
        read_reg16(&mut self.inner.i2c, self.inner.addr, addr)
    }

    /// Read the 10-bit baseline for an electrode.
    ///
    /// # Arguments
    /// * `electrode` — 0-11 for ELE0-ELE11, 12 for ELEPROX.
    pub fn baseline(&mut self, electrode: u8) -> Result<u16, I2C::Error> {
        assert!(electrode <= 12);
        let addr = if electrode == 12 { 0x2A } else { 0x1E + electrode };
        Ok((read_reg(&mut self.inner.i2c, self.inner.addr, addr)? as u16) << 2)
    }

    /// Write a baseline value (Stop Mode only).
    ///
    /// # Arguments
    /// * `electrode` — 0-11 for ELE0-ELE11, 12 for ELEPROX.
    /// * `value`     — 10-bit value 0-1023 (only the 8 MSBs are stored).
    pub fn set_baseline(&mut self, electrode: u8, value: u16) -> Result<(), I2C::Error> {
        assert!(electrode <= 12);
        let addr = if electrode == 12 { 0x2A } else { 0x1E + electrode };
        write_reg(&mut self.inner.i2c, self.inner.addr, addr, (value >> 2) as u8)
    }

    /// Read the 13-bit out-of-range bitmask.
    pub fn oor_status(&mut self) -> Result<u16, I2C::Error> {
        read_oor(&mut self.inner.i2c, self.inner.addr)
    }

    /// Set the global baseline filter parameters (Stop Mode).
    #[allow(clippy::too_many_arguments)]
    pub fn configure_baseline_filter(&mut self, mhdr: u8, nhdr: u8, nclr: u8, fdlr: u8,
                                     mhdf: u8, nhdf: u8, nclf: u8, fdlf: u8,
                                     nhdt: u8, nclt: u8, fdlt: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MHDR, mhdr & 0x3F)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_NHDR, nhdr & 0x3F)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x2D, nclr)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x2E, fdlr)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MHDF, mhdf & 0x3F)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_NHDF, nhdf & 0x3F)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x31, nclf)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x32, fdlf)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x33, nhdt & 0x3F)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x34, nclt)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, 0x35, fdlt)
    }

    /// Set global AFE (sampling) configuration (Stop Mode).
    ///
    /// # Arguments
    /// * `cdc` — Global CDC 0-63 µA.
    /// * `cdt` — Global CDT 0-7.
    /// * `ffi` — First Filter Iterations 0-3.
    /// * `sfi` — Second Filter Iterations 0-3.
    /// * `esi` — Electrode Sample Interval 0-7.
    pub fn configure_sampling(&mut self, cdc: u8, cdt: u8, ffi: u8, sfi: u8, esi: u8) -> Result<(), I2C::Error> {
        let cdc_cfg = ((ffi & 0x03) << 6) | (cdc & 0x3F);
        let cdt_cfg = ((cdt & 0x07) << 5) | ((sfi & 0x03) << 2) | (esi & 0x07);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CDC_CONFIG, cdc_cfg)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CDT_CONFIG, cdt_cfg)
    }

    /// Set debounce counts (Stop Mode).
    pub fn configure_debounce(&mut self, touch: u8, release: u8) -> Result<(), I2C::Error> {
        let deb = ((release & 0x07) << 4) | (touch & 0x07);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_DEBOUNCE, deb)
    }

    /// Compute USL/TL/LSL from VDD and write autoconfig registers (Stop Mode).
    #[allow(clippy::too_many_arguments)]
    pub fn configure_autoconfig(&mut self, vdd_mv: u16, retry: u8,
                                 scts: bool, are: bool, ace: bool) -> Result<(), I2C::Error> {
        let usl = (((vdd_mv - 700) as u32 * 256) / vdd_mv as u32) as u8;
        let tl  = ((usl as f32) * 0.9) as u8;
        let lsl = ((usl as f32) * 0.65) as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_USL, usl)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TL,  tl)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_LSL, lsl)?;
        let ffi = (read_reg(&mut self.inner.i2c, self.inner.addr, REG_CDC_CONFIG)? >> 6) & 0x03;
        let autoconfig0 = ((ffi & 0x03) << 6) | ((retry & 0x03) << 4)
                        | (if are { 0x08 } else { 0 }) | (if ace { 0x01 } else { 0 });
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AUTOCONFIG0, autoconfig0)?;
        let autoconfig1 = if scts { 0x80 } else { 0x00 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AUTOCONFIG1, autoconfig1)
    }

    /// Return true if the ELEPROX virtual electrode is touched.
    pub fn proximity_touched(&mut self) -> Result<bool, I2C::Error> {
        Ok((read_reg(&mut self.inner.i2c, self.inner.addr, REG_ELE8_PROX_TCH)? & 0x10) != 0)
    }

    /// Clear the OVCF bit in register 0x01.
    pub fn clear_overcurrent(&mut self) -> Result<(), I2C::Error> {
        let raw = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ELE8_PROX_TCH)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ELE8_PROX_TCH, raw & 0x7F)
    }

    /// Enable one of the AUTOCONFIG1-based interrupt sources.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        let cur = read_reg(&mut self.inner.i2c, self.inner.addr, REG_AUTOCONFIG1)? & 0x00;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AUTOCONFIG1, cur | (source & 0x07))
    }

    /// Disable one of the AUTOCONFIG1-based interrupt sources.
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        let cur = read_reg(&mut self.inner.i2c, self.inner.addr, REG_AUTOCONFIG1)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AUTOCONFIG1, cur & !(source & 0x07))
    }
}

// Forward Minimal methods to Full via the inner driver.
impl<I2C: I2C> Mpr121Full<I2C> {
    /// Read the 12-bit touch bitmask. Delegates to inner.
    pub fn touched(&mut self) -> Result<u16, I2C::Error> { self.inner.touched() }
    /// Check whether a single electrode is touched. Delegates to inner.
    pub fn is_touched(&mut self, electrode: u8) -> Result<bool, I2C::Error> { self.inner.is_touched(electrode) }
}

#[cfg(test)]
#[cfg(feature = "std")]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1_mock::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x5A;

    #[test]
    fn minimal_construction_writes_expected_registers() {
        let transactions = [
            I2cTransaction::write(ADDR, vec![REG_SRST, SOFT_RESET_KEY]),
            I2cTransaction::write(ADDR, vec![REG_MHDR, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_NHDR, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_MHDF, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_NHDF, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_CDC_CONFIG, CDC_CONFIG_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CDT_CONFIG, CDT_CONFIG_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_USL, USL_3V3]),
            I2cTransaction::write(ADDR, vec![REG_TL,  TL_3V3]),
            I2cTransaction::write(ADDR, vec![REG_LSL, LSL_3V3]),
            I2cTransaction::write(ADDR, vec![REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 0,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 0,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 2,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 2,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 4,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 4,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 6,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 6,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 8,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 8,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 10, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 10, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 12, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 12, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 14, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 14, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 16, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 16, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 18, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 18, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 20, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 20, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 22, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 22, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_ECR, ECR_DEFAULT]),
            I2cTransaction::write_read(ADDR, vec![REG_ELE0_7_TOUCH], vec![0x5A, 0x05]),
        ];
        let i2c = I2cMock::new(&transactions);
        let mut delay = Delay;
        let mut chip = Mpr121Minimal::new(i2c, ADDR, &mut delay).expect("init");
        assert_eq!(chip.touched().unwrap(), 0x5A | ((0x05 & 0x0F) << 8));
        chip.inner.i2c.done();
    }

    #[test]
    fn filtered_baseline_and_set_baseline() {
        let transactions = [
            // init
            I2cTransaction::write(ADDR, vec![REG_SRST, SOFT_RESET_KEY]),
            I2cTransaction::write(ADDR, vec![REG_MHDR, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_NHDR, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_MHDF, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_NHDF, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_CDC_CONFIG, CDC_CONFIG_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CDT_CONFIG, CDT_CONFIG_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_USL, USL_3V3]),
            I2cTransaction::write(ADDR, vec![REG_TL,  TL_3V3]),
            I2cTransaction::write(ADDR, vec![REG_LSL, LSL_3V3]),
            I2cTransaction::write(ADDR, vec![REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 0,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 0,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 2,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 2,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 4,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 4,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 6,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 6,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 8,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 8,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 10, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 10, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 12, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 12, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 14, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 14, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 16, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 16, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 18, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 18, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 20, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 20, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 22, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 22, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_ECR, ECR_DEFAULT]),
            I2cTransaction::write_read(ADDR, vec![0x04], vec![0x80, 0x02]),
            I2cTransaction::write_read(ADDR, vec![0x1E], vec![0x80]),
            I2cTransaction::write(ADDR, vec![0x1E, 0xC0]),
        ];
        let i2c = I2cMock::new(&transactions);
        let mut delay = Delay;
        let mut chip = Mpr121Full::new(i2c, ADDR, &mut delay).expect("init");
        assert_eq!(chip.filtered(0).unwrap(), 0x280);
        assert_eq!(chip.baseline(0).unwrap(), 0x200);
        chip.set_baseline(0, 0x300).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn configure_sampling_and_debounce() {
        let transactions = [
            I2cTransaction::write(ADDR, vec![REG_SRST, SOFT_RESET_KEY]),
            I2cTransaction::write(ADDR, vec![REG_MHDR, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_NHDR, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_MHDF, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_NHDF, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_CDC_CONFIG, CDC_CONFIG_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CDT_CONFIG, CDT_CONFIG_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_USL, USL_3V3]),
            I2cTransaction::write(ADDR, vec![REG_TL,  TL_3V3]),
            I2cTransaction::write(ADDR, vec![REG_LSL, LSL_3V3]),
            I2cTransaction::write(ADDR, vec![REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 0,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 0,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 2,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 2,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 4,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 4,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 6,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 6,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 8,  TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 8,  RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 10, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 10, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 12, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 12, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 14, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 14, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 16, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 16, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 18, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 18, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 20, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 20, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0TTH + 22, TOUCH_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_E0RTH + 22, RELEASE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_ECR, ECR_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CDC_CONFIG, 0x4A]),
            I2cTransaction::write(ADDR, vec![REG_CDT_CONFIG, 0x4D]),
            I2cTransaction::write(ADDR, vec![REG_DEBOUNCE, 0x53]),
        ];
        let i2c = I2cMock::new(&transactions);
        let mut delay = Delay;
        let mut chip = Mpr121Full::new(i2c, ADDR, &mut delay).expect("init");
        chip.configure_sampling(10, 2, 1, 2, 5).unwrap();
        chip.configure_debounce(3, 5).unwrap();
        chip.inner.i2c.done();
    }

    use linux_embedded_hal::Delay;
}
