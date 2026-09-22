//! L3GD20H — three-axis MEMS gyroscope (STMicroelectronics).
//!
//! Communicates over I²C (address 0x6A or 0x6B) or SPI (Mode 3, MSB first).
//! Provides angular rate on X, Y, Z with selectable full-scale (±250 /
//! ±500 / ±2000 dps) and output data rate (95 / 190 / 380 / 760 Hz).
//! The chip also has a 32-slot FIFO, a high-pass filter,
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
const REG_INT1_TSH_XH: u8   = 0x32;
const REG_INT1_TSH_XL: u8   = 0x33;
const REG_INT1_TSH_YH: u8   = 0x34;
const REG_INT1_TSH_YL: u8   = 0x35;
const REG_INT1_TSH_ZH: u8   = 0x36;
const REG_INT1_TSH_ZL: u8   = 0x37;
const REG_INT1_DURATION: u8 = 0x38;

const WHO_AM_I_L3GD20: u8   = 0xD4;
const WHO_AM_I_L3GD20H: u8  = 0xD7;
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

/// L3GD20H minimal driver — angular rate on X, Y, Z.
///
/// Default configuration: 95 Hz ODR, default bandwidth, ±250 dps
/// full scale, BDU=1, all axes enabled, 250 ms startup delay.
pub struct L3gd20hMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    spi: bool,
    full_scale: u16,
}

impl<I2C: I2c> L3gd20hMinimal<I2C> {
    /// Create a new `L3gd20hMinimal` and run the init sequence.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x6A or 0x6B).
    /// * `spi` — Pass `true` for SPI bus.
    pub fn new(mut i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        let mut s = Self { i2c, addr, spi, full_scale: 250 };
        let mut buf = [0u8; 1];
        let _ = s.read_reg_bytes(REG_WHO_AM_I, &mut buf);
        if buf[0] != WHO_AM_I_L3GD20 && buf[0] != WHO_AM_I_L3GD20H {
            return Ok(s);
        }
        s.write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT)?;
        s.write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)?;
        // Note: 250 ms startup delay must be handled by caller in embedded context
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
    pub fn gyro(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut buf = [0u8; 6];
        self.read_reg_bytes(REG_OUT_X_L, &mut buf)?;
        let sens = sensitivity(self.full_scale);
        let x_dps = int16_le(&buf[0..2]) as f32 * sens;
        let y_dps = int16_le(&buf[2..4]) as f32 * sens;
        let z_dps = int16_le(&buf[4..6]) as f32 * sens;
        Ok((x_dps * DPS_TO_RAD, y_dps * DPS_TO_RAD, z_dps * DPS_TO_RAD))
    }
}

/// L3GD20H full driver — extends minimal with full configuration,
/// FIFO, high-pass filter, interrupts, axis-enable, and power-mode control.
///
/// Implements the Rust analog of "Full never duplicates Minimal" via
/// composition: `inner` owns the Minimal driver, all inherited methods
/// are one-line delegates, and Full-only methods follow.
pub struct L3gd20hFull<I2C> {
    inner: L3gd20hMinimal<I2C>,
    odr: u8,
    bw: u8,
}

/// Output data rate code (DR[1:0] in CTRL_REG1).
pub const ODR_95_HZ: u8  = 0;
/// Output data rate code (DR[1:0] in CTRL_REG1).
pub const ODR_190_HZ: u8 = 1;
/// Output data rate code (DR[1:0] in CTRL_REG1).
pub const ODR_380_HZ: u8 = 2;
/// Output data rate code (DR[1:0] in CTRL_REG1).
pub const ODR_760_HZ: u8 = 3;

/// Full-scale code: ±250 dps.
pub const FS_250_DPS: u8  = 0;
/// Full-scale code: ±500 dps.
pub const FS_500_DPS: u8  = 1;
/// Full-scale code: ±2000 dps.
pub const FS_2000_DPS: u8 = 2;

/// FIFO mode: bypass (disables the FIFO).
pub const FIFO_BYPASS: u8             = 0;
/// FIFO mode: FIFO.
pub const FIFO_FIFO: u8               = 1;
/// FIFO mode: stream.
pub const FIFO_STREAM: u8             = 2;
/// FIFO mode: bypass-to-stream.
pub const FIFO_BYPASS_TO_STREAM: u8   = 3;
/// FIFO mode: stream-to-FIFO.
pub const FIFO_STREAM_TO_FIFO: u8     = 7;

/// HPF mode: normal (reset by reading REFERENCE).
pub const HPM_NORMAL: u8      = 0;
/// HPF mode: reference signal.
pub const HPM_REFERENCE: u8   = 1;
/// HPF mode: normal (alternate).
pub const HPM_NORMAL_ALT: u8  = 2;
/// HPF mode: autoreset on interrupt.
pub const HPM_AUTORESET: u8   = 3;

/// Power mode: normal (PD=1, all axes on).
pub const POWER_NORMAL: &str     = "normal";
/// Power mode: sleep (PD=1, all axes off).
pub const POWER_SLEEP: &str      = "sleep";
/// Power mode: power-down (PD=0).
pub const POWER_POWERDOWN: &str  = "power_down";

impl<I2C: I2c> L3gd20hFull<I2C> {
    /// Create a new `L3gd20hFull` and run the init sequence.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x6A or 0x6B).
    /// * `spi` — Pass `true` for SPI bus.
    pub fn new(i2c: I2C, addr: u8, spi: bool) -> Result<Self, I2C::Error> {
        let inner = L3gd20hMinimal::new(i2c, addr, spi)?;
        Ok(Self { inner, odr: 0, bw: 0 })
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(reg, value)
    }

    fn read_reg(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        self.inner.read_reg_bytes(reg, buf)
    }

    /// Configure ODR, bandwidth, and full scale in one call.
    ///
    /// * `odr` — ODR code (`ODR_95_HZ` through `ODR_760_HZ`).
    /// * `bw` — Bandwidth code 0-3 (ODR-dependent; see datasheet Table 21).
    /// * `full_scale` — Full-scale code (`FS_250_DPS`, `FS_500_DPS`, or `FS_2000_DPS`).
    pub fn configure(&mut self, odr: u8, bw: u8, full_scale: u8) -> Result<(), I2C::Error> {
        if odr > 3 || bw > 3 || full_scale > 2 {
            return Ok(());
        }
        self.odr = odr;
        self.bw = bw;
        let fs_map = [250, 500, 2000];
        self.inner.full_scale = fs_map[full_scale as usize];
        let ctrl1 = CTRL_REG1_DEFAULT | ((self.odr & 0x3) << 6) | ((self.bw & 0x3) << 4);
        self.write_reg(REG_CTRL_REG1, ctrl1)?;
        self.write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT | ((full_scale & 0x3) << 4))?;
        Ok(())
    }

    /// Read raw 16-bit signed angular rate values.
    pub fn gyro_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut buf = [0u8; 6];
        self.read_reg(REG_OUT_X_L, &mut buf)?;
        Ok((int16_le(&buf[0..2]), int16_le(&buf[2..4]), int16_le(&buf[4..6])))
    }

    /// Read the relative temperature count.
    ///
    /// OUT_TEMP is an 8-bit signed value (1 LSB/°C); no absolute
    /// calibration — represents change from power-on baseline.
    pub fn temperature(&mut self) -> Result<i8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_OUT_TEMP, &mut buf)?;
        Ok(buf[0] as i8)
    }

    /// Check whether a new X/Y/Z sample is ready (STATUS_REG.ZYXDA bit 3).
    pub fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_STATUS, &mut buf)?;
        Ok((buf[0] & 0x08) != 0)
    }

    /// Configure the high-pass filter (CTRL_REG2).
    ///
    /// * `mode` — HPF mode 0-3 (`HPM_NORMAL`, `HPM_REFERENCE`, `HPM_NORMAL_ALT`, `HPM_AUTORESET`).
    /// * `cutoff` — HPF cutoff code 0-15 (HPCF[3:0] in CTRL_REG2).
    pub fn configure_hp_filter(&mut self, mode: u8, cutoff: u8) -> Result<(), I2C::Error> {
        if mode > 3 || cutoff > 15 {
            return Ok(());
        }
        let ctrl2 = ((mode & 0x3) << 4) | (cutoff & 0x0F);
        self.write_reg(REG_CTRL_REG2, ctrl2)
    }

    /// Enable or disable the high-pass filter on the output path.
    pub fn enable_hp_filter(&mut self, enable: bool) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG5, &mut buf)?;
        let ctrl5 = if enable { buf[0] | 0x10 } else { buf[0] & !0x10 };
        self.write_reg(REG_CTRL_REG5, ctrl5)
    }

    /// Configure the FIFO (FIFO_CTRL_REG).
    ///
    /// * `mode` — FIFO mode (`FIFO_BYPASS`, `FIFO_FIFO`, `FIFO_STREAM`,
    ///   `FIFO_BYPASS_TO_STREAM`, `FIFO_STREAM_TO_FIFO`).
    /// * `watermark` — Watermark threshold 0-31 (WTM[4:0]).
    pub fn configure_fifo(&mut self, mode: u8, watermark: u8) -> Result<(), I2C::Error> {
        let valid_modes = [0, 1, 2, 3, 7];
        if !valid_modes.contains(&mode) || watermark > 31 {
            return Ok(());
        }
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG5, &mut buf)?;
        self.write_reg(REG_CTRL_REG5, buf[0] | 0x40)?;
        let fifo_ctrl = ((mode & 0x7) << 5) | (watermark & 0x1F);
        self.write_reg(REG_FIFO_CTRL, fifo_ctrl)
    }

    /// Enable or disable the FIFO (FIFO_EN bit in CTRL_REG5).
    pub fn enable_fifo(&mut self, enable: bool) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG5, &mut buf)?;
        let ctrl5 = if enable { buf[0] | 0x40 } else { buf[0] & !0x40 };
        self.write_reg(REG_CTRL_REG5, ctrl5)?;
        if !enable {
            self.write_reg(REG_FIFO_CTRL, 0x00)?;
        }
        Ok(())
    }

    /// Read number of unread samples in FIFO (FIFO_SRC_REG FSS[4:0]).
    pub fn fifo_level(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_FIFO_SRC, &mut buf)?;
        Ok(buf[0] & 0x1F)
    }

    /// Read all available FIFO samples and return as rad/s tuples.
    ///
    /// The FIFO holds at most 32 samples (FSS is a 5-bit field), so the
    /// result is a fixed-capacity `heapless::Vec` rather than a heap-backed
    /// one -- this crate is `no_std` by default.
    pub fn read_fifo(&mut self) -> Result<heapless::Vec<(f32, f32, f32), 32>, I2C::Error> {
        let n = self.fifo_level()? as usize;
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

    /// Set the power mode (CTRL_REG1 PD and axis enable bits).
    ///
    /// * `mode` — "normal" (PD=1, all axes on), "sleep" (PD=1, all axes off),
    ///   or "power_down" (PD=0).
    pub fn set_power_mode(&mut self, mode: &str) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 1];
        self.read_reg(REG_CTRL_REG1, &mut buf)?;
        let ctrl1 = match mode {
            "normal" => (buf[0] & 0xF0) | 0x0F,
            "sleep" => (buf[0] & 0xF8) | 0x08,
            "power_down" => buf[0] & 0xF7,
            _ => return Ok(()),
        };
        self.write_reg(REG_CTRL_REG1, ctrl1)
    }

    // Minimal one-line delegates.
    /// Read angular rate on all three axes. Delegates to [`L3gd20hMinimal::gyro`].
    pub fn gyro(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.gyro()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x6A;

    #[test]
    fn full_api() {
        let transactions = vec![
            // Minimal init: WHO_AM_I=0xD7, CTRL_REG4=0x80, CTRL_REG1=0x0F
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![0xD7]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, CTRL_REG4_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, CTRL_REG1_DEFAULT]),
            // gyro(): burst read 6 bytes from OUT_X_L with auto-increment
            // X=+16, Y=0, Z=-16 (0xFFF0)
            I2cTransaction::write_read(ADDR, vec![REG_OUT_X_L | 0x80],
                                        vec![0x10, 0x00,
                                             0x00, 0x00,
                                             0xF0, 0xFF]),
            // configure(ODR_190_HZ=1, bw=0, full_scale=FS_500_DPS=1): CTRL_REG1=(1<<6)|0x0F=0x4F, CTRL_REG4=(1<<4)|0x80=0x90
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x4F]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x90]),
            // gyro_raw(): read OUT_X_L with auto-increment
            I2cTransaction::write_read(ADDR, vec![REG_OUT_X_L | 0x80],
                                        vec![0x00, 0x80,
                                             0xFF, 0x7F,
                                             0x00, 0x00]),
            // temperature(): read OUT_TEMP = 0x80 (signed: -128)
            I2cTransaction::write_read(ADDR, vec![REG_OUT_TEMP], vec![0x80]),
            // data_ready(): read STATUS = 0x08
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x08]),
            // configure_hp_filter(mode=1, cutoff=5): write CTRL_REG2 = (1<<4)|5 = 0x15
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x15]),
            // enable_hp_filter(true): read CTRL_REG5, write with HPen
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x10]),
            // enable_hp_filter(false): read CTRL_REG5, write with HPen cleared
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x10]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x00]),
            // configure_fifo(mode=FIFO_FIFO=1, watermark=10): read CTRL_REG5, write FIFO_EN; write FIFO_CTRL
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x40]),
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, (1u8 << 5) | 10]),
            // enable_fifo(false): read CTRL_REG5, clear FIFO_EN; write FIFO_CTRL=0
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x40]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, 0x00]),
            // fifo_level(): read FIFO_SRC = 0x05 (5 samples)
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_SRC], vec![0x05]),
            // read_fifo: read 30 bytes from OUT_X_L
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
            // set_power_mode("normal"): read CTRL_REG1, write with all axes on
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x4F]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x4F]),
            // set_power_mode("sleep"): read CTRL_REG1, write with axes off
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x4F]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x48]),
            // set_power_mode("power_down"): read CTRL_REG1, write with PD=0
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x48]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x40]),
        ];
        let i2c = I2cMock::new(&transactions);

        let mut sensor = L3gd20hFull::new(i2c, ADDR, false).expect("init");

        let k = core::f32::consts::PI / 180.0;
        let expected_x = 16.0 * 0.00875 * k;
        let expected_z = -16.0 * 0.00875 * k;

        let (x, y, z) = sensor.gyro().unwrap();
        assert!((x - expected_x).abs() < 1e-6, "x: got {}", x);
        assert_eq!(y, 0.0);
        assert!((z - expected_z).abs() < 1e-6, "z: got {}", z);

        sensor.configure(ODR_190_HZ, 0, FS_500_DPS).unwrap();
        assert_eq!(sensor.inner.full_scale, 500);

        let (x, y, z) = sensor.gyro_raw().unwrap();
        assert_eq!(x, -32768);
        assert_eq!(y, 32767);
        assert_eq!(z, 0);

        assert_eq!(sensor.temperature().unwrap(), -128);
        assert!(sensor.data_ready().unwrap());

        sensor.configure_hp_filter(HPM_REFERENCE, 5).unwrap();
        sensor.enable_hp_filter(true).unwrap();
        sensor.enable_hp_filter(false).unwrap();

        sensor.configure_fifo(FIFO_FIFO, 10).unwrap();
        sensor.enable_fifo(false).unwrap();

        assert_eq!(sensor.fifo_level().unwrap(), 5);

        let samples = sensor.read_fifo().unwrap();
        assert_eq!(samples.len(), 5);

        sensor.set_power_mode(POWER_NORMAL).unwrap();
        sensor.set_power_mode(POWER_SLEEP).unwrap();
        sensor.set_power_mode(POWER_POWERDOWN).unwrap();

        sensor.inner.i2c.done();
    }
}