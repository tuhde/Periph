//! L3G4200D — three-axis MEMS gyroscope (STMicroelectronics).
//!
//! Communicates over I²C (address 0x68 or 0x69) or SPI (Mode 3, MSB first).
//! Provides angular rate on X, Y, Z with selectable full-scale (±250 /
//! ±500 / ±2000 dps) and output data rate (100 / 200 / 400 / 800 Hz).
//! The chip also has a 32-slot FIFO per axis, a high-pass filter,
//! per-axis interrupt generation, and an 8-bit relative temperature sensor.

use embedded_hal::i2c::I2c;

const REG_WHO_AM_I: u8      = 0x0F;
const REG_CTRL_REG1: u8     = 0x20;
const REG_CTRL_REG2: u8     = 0x21;
const REG_CTRL_REG3: u8     = 0x22;
const REG_CTRL_REG4: u8     = 0x23;
const REG_CTRL_REG5: u8     = 0x24;
const REG_OUT_TEMP: u8      = 0x26;
const REG_STATUS: u8        = 0x27;
const REG_OUT_X_L: u8       = 0x28;
const REG_OUT_X_H: u8       = 0x29;
const REG_OUT_Y_L: u8       = 0x2A;
const REG_OUT_Y_H: u8       = 0x2B;
const REG_OUT_Z_L: u8       = 0x2C;
const REG_OUT_Z_H: u8       = 0x2D;
const REG_FIFO_CTRL: u8     = 0x2E;
const REG_FIFO_SRC: u8      = 0x2F;
const REG_INT1_CFG: u8      = 0x30;
const REG_INT1_SRC: u8      = 0x31;
const REG_INT1_THS_XH: u8   = 0x32;
const REG_INT1_THS_XL: u8   = 0x33;
const REG_INT1_THS_YH: u8   = 0x34;
const REG_INT1_THS_YL: u8   = 0x35;
const REG_INT1_THS_ZH: u8   = 0x36;
const REG_INT1_THS_ZL: u8   = 0x37;
const REG_INT1_DURATION: u8 = 0x38;

const WHO_AM_I_EXPECTED: u8 = 0xD3;
const CTRL_REG1_DEFAULT: u8 = 0x0F;
const CTRL_REG4_DEFAULT: u8 = 0x80;

const DPS_TO_RAD: f32 = core::f32::consts::PI / 180.0;

fn sensitivity(full_scale: u16) -> f32 {
    match full_scale {
        250 => 8.75e-3,
        500 => 17.5e-3,
        2000 => 70.0e-3,
        _ => 8.75e-3,
    }
}

fn int16_le(data: &[u8]) -> i16 {
    let v = ((data[1] as i16) << 8) | (data[0] as i16);
    v
}

/// L3G4200D minimal driver — angular rate on X, Y, Z.
///
/// Default configuration: 100 Hz ODR, 12.5 Hz LPF2 cutoff, ±250 dps
/// full scale, BDU=1, all axes enabled, FIFO disabled, HPF disabled.
pub struct L3g4200dMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    spi: bool,
    full_scale: u16,
}

impl<I2C: I2c> L3g4200dMinimal<I2C> {
    /// Create a new `L3g4200dMinimal` and run the init sequence.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x68 or 0x69).
    /// * `spi` — Pass `true` for SPI bus.
    pub fn new(mut i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        let mut s = Self { i2c, addr, spi, full_scale: 250 };
        let mut buf = [0u8; 1];
        let _ = s.read_reg_bytes(REG_WHO_AM_I, &mut buf);
        if buf[0] != WHO_AM_I_EXPECTED {
            return Ok(s);
        }
        s.write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT)?;
        s.write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)?;
        Ok(s)
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        let r = if self.spi { reg & 0x3F } else { reg };
        self.i2c.write(self.addr, &[r, value])
    }

    fn read_reg_bytes(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        let addr = if self.spi {
            reg | 0xC0  // READ=1, MS=1 (auto-increment)
        } else if buf.len() > 1 {
            reg | 0x80  // I²C multi-byte auto-increment
        } else {
            reg
        };
        self.i2c.write_read(self.addr, &[addr & 0xFF], buf)
    }

    /// Read angular rate on all three axes as a single burst transaction.
    ///
    /// Returns `(x_rad_s, y_rad_s, z_rad_s)`.
    pub fn angular_rate(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut buf = [0u8; 6];
        self.read_reg_bytes(REG_OUT_X_L, &mut buf)?;
        let sens = sensitivity(self.full_scale);
        let x_dps = int16_le(&buf[0..2]) as f32 * sens;
        let y_dps = int16_le(&buf[2..4]) as f32 * sens;
        let z_dps = int16_le(&buf[4..6]) as f32 * sens;
        Ok((x_dps * DPS_TO_RAD, y_dps * DPS_TO_RAD, z_dps * DPS_TO_RAD))
    }
}

/// L3G4200D full driver — extends minimal with full configuration,
/// FIFO, high-pass filter, interrupts, axis-enable, and power-mode control.
///
/// Implements the Rust analog of "Full never duplicates Minimal" via
/// composition: `inner` owns the Minimal driver, all inherited methods
/// are one-line delegates, and Full-only methods follow.
pub struct L3g4200dFull<I2C> {
    inner: L3g4200dMinimal<I2C>,
    odr: u8,
    bw: u8,
}

/// Output data rate code (DR[1:0] in CTRL_REG1).
pub const ODR_100_HZ: u8 = 0;
/// Output data rate code (DR[1:0] in CTRL_REG1).
pub const ODR_200_HZ: u8 = 1;
/// Output data rate code (DR[1:0] in CTRL_REG1).
pub const ODR_400_HZ: u8 = 2;
/// Output data rate code (DR[1:0] in CTRL_REG1).
pub const ODR_800_HZ: u8 = 3;

/// Full-scale range: ±250 dps.
pub const FS_250_DPS: u16  = 250;
/// Full-scale range: ±500 dps.
pub const FS_500_DPS: u16  = 500;
/// Full-scale range: ±2000 dps.
pub const FS_2000_DPS: u16 = 2000;

/// FIFO mode: bypass (disables the FIFO).
pub const FIFO_BYPASS: u8           = 0;
/// FIFO mode: collect up to 32 samples, halt on full unless mode set.
pub const FIFO_FIFO: u8             = 1;
/// FIFO mode: stream — oldest sample overwritten when full.
pub const FIFO_STREAM: u8           = 2;
/// FIFO mode: stream-to-FIFO — stream until interrupt then halt.
pub const FIFO_STREAM_TO_FIFO: u8   = 3;
/// FIFO mode: bypass-to-stream — fill FIFO after bypass trigger.
pub const FIFO_BYPASS_TO_STREAM: u8 = 4;

impl<I2C: I2c> L3g4200dFull<I2C> {
    /// Create a new `L3g4200dFull` and run the init sequence.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x68 or 0x69).
    /// * `spi` — Pass `true` for SPI bus.
    pub fn new(i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        let inner = L3g4200dMinimal::new(i2c, addr, spi)?;
        Ok(Self { inner, odr: 0, bw: 0 })
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(reg, value)
    }

    fn read_reg(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        self.inner.read_reg_bytes(reg, buf)
    }

    /// Configure ODR, LPF2 bandwidth, and full scale.
    ///
    /// * `odr` — ODR code (`ODR_100_HZ` through `ODR_800_HZ`).
    /// * `bandwidth` — LPF2 bandwidth code 0-3.
    /// * `full_scale` — Full-scale in dps (`FS_250_DPS`, `FS_500_DPS`, or `FS_2000_DPS`).
    pub fn configure(&mut self, odr: u8, bandwidth: u8, full_scale: u16) -> Result<(), I2C::Error> {
        if !matches!(full_scale, 250 | 500 | 2000) {
            return Ok(());
        }
        self.odr = odr & 0x3;
        self.bw = bandwidth & 0x3;
        self.inner.full_scale = full_scale;
        let ctrl1 = CTRL_REG1_DEFAULT | ((self.odr & 0x3) << 6) | ((self.bw & 0x3) << 4);
        self.write_reg(REG_CTRL_REG1, ctrl1)?;
        let fs_bits: u8 = match full_scale { 250 => 0, 500 => 1, _ => 2 };
        self.write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT | ((fs_bits & 0x3) << 4))?;
        Ok(())
    }

    /// Update the full-scale range.
    pub fn set_full_scale(&mut self, full_scale: u16) -> Result<(), I2C::Error> {
        if !matches!(full_scale, 250 | 500 | 2000) {
            return Ok(());
        }
        self.inner.full_scale = full_scale;
        let fs_bits: u8 = match full_scale { 250 => 0, 500 => 1, _ => 2 };
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG4, &mut buf)?;
        let ctrl4 = (buf[0] & 0xCF) | ((fs_bits & 0x3) << 4);
        self.write_reg(REG_CTRL_REG4, ctrl4)?;
        Ok(())
    }

    /// Read WHO_AM_I (0x0F). Returns 0xD3 for a genuine L3G4200D.
    pub fn who_am_i(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_WHO_AM_I, &mut buf)?;
        Ok(buf[0])
    }

    /// Read STATUS_REG (0x27) raw byte.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_STATUS, &mut buf)?;
        Ok(buf[0])
    }

    /// Check whether a new X/Y/Z sample is ready (STATUS_REG.ZYXDA bit 3).
    pub fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok((self.status()? & 0x08) != 0)
    }

    /// Read the relative temperature count.
    ///
    /// OUT_TEMP is an 8-bit signed value (−1 °C/digit); no absolute
    /// calibration — useful only for tracking drift.
    pub fn temperature(&mut self) -> Result<i8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_OUT_TEMP, &mut buf)?;
        Ok(buf[0] as i8)
    }

    /// Enter power-down mode (PD=0 in CTRL_REG1).
    pub fn power_down(&mut self) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG1, &mut buf)?;
        self.write_reg(REG_CTRL_REG1, buf[0] & 0xF7)
    }

    /// Wake from power-down (PD=1); previously enabled axes restored.
    pub fn wake_up(&mut self) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG1, &mut buf)?;
        self.write_reg(REG_CTRL_REG1, buf[0] | 0x08)
    }

    /// Enter sleep mode (PD=1, all axes disabled).
    pub fn sleep(&mut self) -> Result<(), I2C::Error> {
        self.write_reg(REG_CTRL_REG1, 0x08)
    }

    /// Enable or disable individual axes (Xen/Yen/Zen in CTRL_REG1).
    pub fn enable_axes(&mut self, x: bool, y: bool, z: bool) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG1, &mut buf)?;
        let mut val = buf[0] & 0xF8;
        if z { val |= 0x04; }
        if y { val |= 0x02; }
        if x { val |= 0x01; }
        self.write_reg(REG_CTRL_REG1, val)
    }

    /// Configure and enable the FIFO.
    ///
    /// * `mode` — FIFO mode 0-4 (`FIFO_BYPASS`, `FIFO_FIFO`, `FIFO_STREAM`,
    ///   `FIFO_STREAM_TO_FIFO`, `FIFO_BYPASS_TO_STREAM`).
    /// * `watermark` — Watermark threshold 0-31.
    pub fn enable_fifo(&mut self, mode: u8, watermark: u8) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG5, &mut buf)?;
        self.write_reg(REG_CTRL_REG5, buf[0] | 0x40)?;
        let fifo_ctrl = ((mode & 0x7) << 5) | (watermark & 0x1F);
        self.write_reg(REG_FIFO_CTRL, fifo_ctrl)
    }

    /// Disable the FIFO.
    pub fn disable_fifo(&mut self) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG5, &mut buf)?;
        self.write_reg(REG_CTRL_REG5, buf[0] & !0x40)?;
        self.write_reg(REG_FIFO_CTRL, 0x00)
    }

    /// Read FSS[4:0] from FIFO_SRC_REG (number of stored samples, 0-31).
    pub fn fifo_samples(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_FIFO_SRC, &mut buf)?;
        Ok(buf[0] & 0x1F)
    }

    /// Drain all stored FIFO samples and return them as rad/s tuples.
    ///
    /// The FIFO holds at most 32 samples (FSS is a 5-bit field), so the
    /// result is a fixed-capacity `heapless::Vec` rather than a heap-backed
    /// one -- this crate is `no_std` by default.
    pub fn read_fifo(&mut self) -> Result<heapless::Vec<(f32, f32, f32), 32>, I2C::Error> {
        let n = self.fifo_samples()? as usize;
        if n == 0 { return Ok(heapless::Vec::new()); }
        let sens = sensitivity(self.inner.full_scale);
        let mut buf = [0u8; 31 * 6];
        let buf = &mut buf[..n * 6];
        self.read_reg(REG_OUT_X_L, buf)?;
        let mut out = heapless::Vec::new();
        for i in 0..n {
            let o = i * 6;
            let x = int16_le(&buf[o..o + 2]) as f32 * sens * DPS_TO_RAD;
            let y = int16_le(&buf[o + 2..o + 4]) as f32 * sens * DPS_TO_RAD;
            let z = int16_le(&buf[o + 4..o + 6]) as f32 * sens * DPS_TO_RAD;
            let _ = out.push((x, y, z));
        }
        Ok(out)
    }

    /// Enable the high-pass filter on the output path.
    ///
    /// * `mode` — HPF mode 0-3 (HPM field in CTRL_REG2).
    /// * `cutoff` — HPF cutoff code 0-9 (HPCF field in CTRL_REG2).
    pub fn enable_highpass(&mut self, mode: u8, cutoff: u8) -> Result<(), I2C::Error> {
        let ctrl2 = ((mode & 0x3) << 4) | (cutoff & 0x0F);
        self.write_reg(REG_CTRL_REG2, ctrl2)?;
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG5, &mut buf)?;
        self.write_reg(REG_CTRL_REG5, buf[0] | 0x10)
    }

    /// Clear HPen in CTRL_REG5.
    pub fn disable_highpass(&mut self) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG5, &mut buf)?;
        self.write_reg(REG_CTRL_REG5, buf[0] & !0x10)
    }

    /// Configure INT1_CFG axis/direction events.
    #[allow(clippy::too_many_arguments)]
    pub fn set_interrupt(&mut self,
                          x_high: bool, x_low: bool,
                          y_high: bool, y_low: bool,
                          z_high: bool, z_low: bool,
                          and_mode: bool, latch: bool) -> Result<(), I2C::Error> {
        let mut cfg = 0u8;
        if and_mode { cfg |= 0x80; }
        if latch    { cfg |= 0x40; }
        if z_high   { cfg |= 0x20; }
        if z_low    { cfg |= 0x10; }
        if y_high   { cfg |= 0x08; }
        if y_low    { cfg |= 0x04; }
        if x_high   { cfg |= 0x02; }
        if x_low    { cfg |= 0x01; }
        self.write_reg(REG_INT1_CFG, cfg)?;
        if cfg & 0x3F != 0 {
            let mut buf = [0u8; 1];
            self.read_reg(REG_CTRL_REG3, &mut buf)?;
            self.write_reg(REG_CTRL_REG3, buf[0] | 0x80)?;
        }
        Ok(())
    }

    /// Set the interrupt threshold for one axis.
    ///
    /// * `axis` — 'x', 'y', or 'z'.
    /// * `threshold_dps` — Threshold in dps. 15-bit raw value.
    pub fn set_threshold(&mut self, axis: char, threshold_dps: f32) -> Result<(), I2C::Error> {
        let raw = (threshold_dps / sensitivity(self.inner.full_scale)) as i32 & 0x7FFF;
        let (hi, lo) = match axis {
            'x' => (REG_INT1_THS_XH, REG_INT1_THS_XL),
            'y' => (REG_INT1_THS_YH, REG_INT1_THS_YL),
            'z' => (REG_INT1_THS_ZH, REG_INT1_THS_ZL),
            _ => return Ok(()),
        };
        self.write_reg(hi, ((raw >> 8) & 0x7F) as u8)?;
        self.write_reg(lo, (raw & 0xFF) as u8)?;
        Ok(())
    }

    /// Set INT1_DURATION.
    ///
    /// * `samples` — Duration 0-127 (ODR cycles before INT1 fires).
    /// * `wait` — If true, INT1 stays asserted until INT1_SRC is read.
    pub fn set_duration(&mut self, samples: u8, wait: bool) -> Result<(), I2C::Error> {
        let val = ((if wait { 1 } else { 0 }) << 7) | (samples & 0x7F);
        self.write_reg(REG_INT1_DURATION, val)
    }

    /// Read INT1_SRC; reading clears the interrupt-active bit.
    pub fn read_int_source(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_INT1_SRC, &mut buf)?;
        Ok(buf[0])
    }

    /// Route the data-ready signal to the DRDY/INT2 pin.
    pub fn set_data_ready_pin(&mut self, enable: bool) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG3, &mut buf)?;
        let ctrl3 = if enable { buf[0] | 0x08 } else { buf[0] & !0x08 };
        self.write_reg(REG_CTRL_REG3, ctrl3)
    }

    // Minimal one-line delegates.
    /// Read angular rate on all three axes. Delegates to [`L3g4200dMinimal::angular_rate`].
    pub fn angular_rate(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.angular_rate()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x68;

    #[test]
    fn full_api() {
        // Minimal init reads WHO_AM_I, writes CTRL_REG4=0x80, CTRL_REG1=0x0F.
        let transactions = vec![
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![0xD3]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, CTRL_REG4_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, CTRL_REG1_DEFAULT]),
            // angular_rate(): burst read of 6 bytes from OUT_X_L with I²C
            // multi-byte auto-increment (bit 7 set in sub-address).
            // Bytes are LE: X=+16, Y=0, Z=-16 (0xFFF0).
            I2cTransaction::write_read(ADDR, vec![REG_OUT_X_L | 0x80],
                                        vec![0x10, 0x00,
                                             0x00, 0x00,
                                             0xF0, 0xFF]),
            // configure(ODR_200_HZ=1, bw=0, full_scale=500): CTRL_REG1=(1<<6)|0x0F=0x4F, CTRL_REG4=(1<<4)|0x80=0x90.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x4F]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x90]),
            // set_full_scale(2000): read CTRL_REG4, write with FS=10.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG4], vec![0x90]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0xA0]),
            // data_ready(): read STATUS.
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x08]),
            // status(): same.
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x08]),
            // temperature(): read OUT_TEMP = 0x80 (signed: -128).
            I2cTransaction::write_read(ADDR, vec![REG_OUT_TEMP], vec![0x80]),
            // power_down(): read CTRL_REG1, write with PD cleared.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x4F]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x47]),
            // wake_up(): read CTRL_REG1, write with PD set.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x47]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x4F]),
            // sleep(): write CTRL_REG1 = 0x08.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x08]),
            // enable_axes(x=False, y=True, z=False): read CTRL_REG1, write with only Yen set.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x08]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x0A]),
            // enable_fifo(FIFO_STREAM=2, watermark=10): read CTRL_REG5, write FIFO_EN; write FIFO_CTRL.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x40]),
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, (2u8 << 5) | 10]),
            // fifo_samples(): read FIFO_SRC.
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_SRC], vec![0x1A]),
            // enable_highpass(mode=2, cutoff=5): write CTRL_REG2, read CTRL_REG5, write with HPen.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, (2u8 << 4) | 5]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x40]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x50]),
            // disable_highpass(): read CTRL_REG5, write with HPen cleared.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x50]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x40]),
            // set_interrupt(...): write INT1_CFG = 0x40|0x20|0x08|0x02 = 0x6A; read CTRL_REG3, write with I1_Int1.
            I2cTransaction::write(ADDR, vec![REG_INT1_CFG, 0x6A]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG3], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG3, 0x80]),
            // set_threshold('x', 87.5): current full_scale=2000, sens=0.07, raw=int(87.5/0.07)=1250=0x04E2.
            //   XH = (1250>>8)&0x7F = 0x04, XL = 0xE2.
            I2cTransaction::write(ADDR, vec![REG_INT1_THS_XH, 0x04]),
            I2cTransaction::write(ADDR, vec![REG_INT1_THS_XL, 0xE2]),
            // set_duration(4, wait=true): write INT1_DURATION = 0x80|4 = 0x84.
            I2cTransaction::write(ADDR, vec![REG_INT1_DURATION, 0x84]),
            // read_int_source(): read INT1_SRC.
            I2cTransaction::write_read(ADDR, vec![REG_INT1_SRC], vec![0x7F]),
            // set_data_ready_pin(true): read CTRL_REG3, write with bit set.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG3], vec![0x80]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG3, 0x88]),
        ];
        let i2c = I2cMock::new(&transactions);

        let mut sensor = L3g4200dFull::new(i2c, ADDR, false).expect("init");

        let k = core::f32::consts::PI / 180.0;
        let expected_x = 16.0 * 0.00875 * k;
        let expected_z = -16.0 * 0.00875 * k;

        let (x, y, z) = sensor.angular_rate().unwrap();
        assert!((x - expected_x).abs() < 1e-6, "x: got {}", x);
        assert_eq!(y, 0.0);
        assert!((z - expected_z).abs() < 1e-6, "z: got {}", z);

        sensor.configure(ODR_200_HZ, 0, FS_500_DPS).unwrap();
        assert_eq!(sensor.inner.full_scale, 500);
        sensor.set_full_scale(2000).unwrap();
        assert_eq!(sensor.inner.full_scale, 2000);
        assert!(sensor.data_ready().unwrap());
        assert_eq!(sensor.status().unwrap(), 0x08);
        assert_eq!(sensor.temperature().unwrap(), -128);
        sensor.power_down().unwrap();
        sensor.wake_up().unwrap();
        sensor.sleep().unwrap();
        sensor.enable_axes(false, true, false).unwrap();
        sensor.enable_fifo(FIFO_STREAM, 10).unwrap();
        assert_eq!(sensor.fifo_samples().unwrap(), 26);
        sensor.enable_highpass(2, 5).unwrap();
        sensor.disable_highpass().unwrap();
        sensor.set_interrupt(true, false, true, false, true, false, false, true).unwrap();
        sensor.set_threshold('x', 87.5).unwrap();
        sensor.set_duration(4, true).unwrap();
        assert_eq!(sensor.read_int_source().unwrap(), 0x7F);
        sensor.set_data_ready_pin(true).unwrap();

        sensor.inner.i2c.done();
    }

    #[test]
    fn read_fifo_three_samples() {
        // FIFO drain: 3 samples × 6 bytes. Sub-address has bit 7 set for
        // I²C multi-byte auto-increment.
        // Bytes are LE; _fullScale is set explicitly to 2000 by configure.
        let transactions = vec![
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![0xD3]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, CTRL_REG4_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, CTRL_REG1_DEFAULT]),
            // configure(1, 0, 2000) -> CTRL_REG1=(1<<6)|0x0F=0x4F, CTRL_REG4=(2<<4)|0x80=0xA0.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x4F]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0xA0]),
            // enable_fifo(stream=2, watermark=10) -> CTRL_REG5 read, FIFO_EN set, FIFO_CTRL write.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x40]),
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, (2u8 << 5) | 10]),
            // fifo_samples(): read FIFO_SRC.
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_SRC], vec![0x03]),
            // read_fifo: read 18 bytes from OUT_X_L.
            I2cTransaction::write_read(ADDR, vec![REG_OUT_X_L | 0x80],
                                        vec![0x10, 0x00,   // sample 0: X=+16
                                             0x20, 0x00,   // sample 0: Y=+32
                                             0x30, 0x00,   // sample 0: Z=+48
                                             0x40, 0x00,   // sample 1: X=+64
                                             0x50, 0x00,   // sample 1: Y=+80
                                             0x60, 0x00,   // sample 1: Z=+96
                                             0x70, 0x00,   // sample 2: X=+112
                                             0x80, 0x00,   // sample 2: Y=+128
                                             0x90, 0x00]), // sample 2: Z=+144
        ];
        let i2c = I2cMock::new(&transactions);

        let mut sensor = L3g4200dFull::new(i2c, ADDR, false).expect("init");
        sensor.configure(ODR_200_HZ, 0, FS_2000_DPS).unwrap();
        sensor.enable_fifo(FIFO_STREAM, 10).unwrap();

        let samples = sensor.read_fifo().unwrap();
        assert_eq!(samples.len(), 3);

        let k = core::f32::consts::PI / 180.0;
        let sens = sensitivity(FS_2000_DPS);
        let e1 = 16.0 * sens * k;
        let e2 = 32.0 * sens * k;
        let e3 = 48.0 * sens * k;

        assert!((samples[0].0 - e1).abs() < 1e-6);
        assert!((samples[0].1 - e2).abs() < 1e-6);
        assert!((samples[0].2 - e3).abs() < 1e-6);

        sensor.inner.i2c.done();
    }
}
