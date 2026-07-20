//! APDS-9960 — digital proximity, ambient light, RGB and gesture sensor (Broadcom/Avago).
//!
//! Communicates over I²C (up to 400 kHz fast mode, address 0x39 fixed).
//! The ALS/Color engine delivers 16-bit RGBC channels; the proximity engine
//! returns an 8-bit count; the gesture engine captures directional hand
//! movement using four dedicated photodiodes and a 32-dataset FIFO.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const REG_ENABLE: u8 = 0x80;
const REG_ATIME: u8 = 0x81;
const REG_WTIME: u8 = 0x83;
const REG_AILTL: u8 = 0x84;
const REG_AILTH: u8 = 0x85;
const REG_AIHTL: u8 = 0x86;
const REG_AIHTH: u8 = 0x87;
const REG_PILT: u8 = 0x89;
const REG_PIHT: u8 = 0x8B;
const REG_PERS: u8 = 0x8C;
const REG_CONFIG1: u8 = 0x8D;
const REG_PPULSE: u8 = 0x8E;
const REG_CONTROL: u8 = 0x8F;
const REG_CONFIG2: u8 = 0x90;
const REG_ID: u8 = 0x92;
const REG_STATUS: u8 = 0x93;
const REG_CDATAL: u8 = 0x94;
const REG_RDATAL: u8 = 0x96;
const REG_GDATAL: u8 = 0x98;
const REG_BDATAL: u8 = 0x9A;
const REG_PDATA: u8 = 0x9C;
const REG_POFFSET_UR: u8 = 0x9D;
const REG_POFFSET_DL: u8 = 0x9E;
const REG_CONFIG3: u8 = 0x9F;
const REG_GPENTH: u8 = 0xA0;
const REG_GEXTH: u8 = 0xA1;
const REG_GCONF1: u8 = 0xA2;
const REG_GCONF2: u8 = 0xA3;
const REG_GPULSE: u8 = 0xA6;
const REG_GCONF4: u8 = 0xAB;
const REG_GFLVL: u8 = 0xAE;
const REG_GSTATUS: u8 = 0xAF;
const REG_PICLEAR: u8 = 0xE5;
const REG_CICLEAR: u8 = 0xE6;
const REG_AICLEAR: u8 = 0xE7;
const REG_GFIFO_U: u8 = 0xFC;

const ATIME_DEFAULT: u8 = 0xB6;
const CONTROL_DEFAULT: u8 = 0x01;
const CONFIG2_DEFAULT: u8 = 0x01;

/// APDS-9960 minimal driver — ambient light and color (RGBC) readings.
///
/// Writes sensible defaults at construction: ATIME=0xB6 (~200 ms integration),
/// AGAIN=4x, CONFIG2=0x01, PON+AEN enabled.
pub struct Apds9960Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Apds9960Minimal<I2C> {
    /// Create a new `Apds9960Minimal` and initialize the ALS/Color engine.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (always `0x39`).
    /// * `delay` — Delay provider; used for the 6 ms power-up wait and 200 ms
    ///             integration-cycle wait. Call [`chip_id`] after construction to
    ///             verify the device identity (expects `0xAB`).
    pub fn new(mut i2c: I2C, addr: u8, delay: &mut impl DelayNs) -> Result<Self, I2C::Error> {
        delay.delay_ms(6);
        write_reg(&mut i2c, addr, REG_ENABLE, 0x00)?;
        write_reg(&mut i2c, addr, REG_ATIME, ATIME_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_CONTROL, CONTROL_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_CONFIG2, CONFIG2_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_ENABLE, 0x03)?;
        delay.delay_ms(210);
        Ok(Self { i2c, addr })
    }

    /// Read the clear (unfiltered) channel.
    ///
    /// Returns raw clear channel count, 0–65535.
    pub fn color_clear(&mut self) -> Result<u16, I2C::Error> {
        read_reg16_le(&mut self.i2c, self.addr, REG_CDATAL)
    }

    /// Read the red channel.
    ///
    /// Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.
    /// Returns raw red channel count, 0–65535.
    pub fn color_red(&mut self) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 8];
        self.i2c.write_read(self.addr, &[REG_CDATAL], &mut buf)?;
        Ok((buf[2] as u16) | ((buf[3] as u16) << 8))
    }

    /// Read the green channel.
    ///
    /// Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.
    /// Returns raw green channel count, 0–65535.
    pub fn color_green(&mut self) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 8];
        self.i2c.write_read(self.addr, &[REG_CDATAL], &mut buf)?;
        Ok((buf[4] as u16) | ((buf[5] as u16) << 8))
    }

    /// Read the blue channel.
    ///
    /// Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.
    /// Returns raw blue channel count, 0–65535.
    pub fn color_blue(&mut self) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 8];
        self.i2c.write_read(self.addr, &[REG_CDATAL], &mut buf)?;
        Ok((buf[6] as u16) | ((buf[7] as u16) << 8))
    }

    /// Read all four RGBC channels in one burst.
    ///
    /// Reading CDATAL at 0x94 atomically latches all eight bytes 0x94–0x9B.
    ///
    /// Returns `(clear, red, green, blue)` each 0–65535.
    pub fn color(&mut self) -> Result<(u16, u16, u16, u16), I2C::Error> {
        let mut buf = [0u8; 8];
        self.i2c.write_read(self.addr, &[REG_CDATAL], &mut buf)?;
        let c = (buf[0] as u16) | ((buf[1] as u16) << 8);
        let r = (buf[2] as u16) | ((buf[3] as u16) << 8);
        let g = (buf[4] as u16) | ((buf[5] as u16) << 8);
        let b = (buf[6] as u16) | ((buf[7] as u16) << 8);
        Ok((c, r, g, b))
    }
}

/// APDS-9960 full driver — extends [`Apds9960Minimal`] with proximity, gesture, and configuration.
///
/// Provides access to proximity detection, gesture engine, wait engine,
/// threshold and interrupt configuration, status queries, and device identification.
pub struct Apds9960Full<I2C> {
    inner: Apds9960Minimal<I2C>,
}

impl<I2C: I2c> Apds9960Full<I2C> {
    /// Create a new `Apds9960Full` and initialize the ALS/Color engine.
    ///
    /// Same arguments as [`Apds9960Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, delay: &mut impl DelayNs) -> Result<Self, I2C::Error> {
        let inner = Apds9960Minimal::new(i2c, addr, delay)?;
        Ok(Self { inner })
    }

    /// Read the clear channel. Delegates to the inner [`Apds9960Minimal`].
    pub fn color_clear(&mut self) -> Result<u16, I2C::Error> {
        self.inner.color_clear()
    }

    /// Read the red channel. Delegates to the inner [`Apds9960Minimal`].
    pub fn color_red(&mut self) -> Result<u16, I2C::Error> {
        self.inner.color_red()
    }

    /// Read the green channel. Delegates to the inner [`Apds9960Minimal`].
    pub fn color_green(&mut self) -> Result<u16, I2C::Error> {
        self.inner.color_green()
    }

    /// Read the blue channel. Delegates to the inner [`Apds9960Minimal`].
    pub fn color_blue(&mut self) -> Result<u16, I2C::Error> {
        self.inner.color_blue()
    }

    /// Read all four RGBC channels. Delegates to the inner [`Apds9960Minimal`].
    pub fn color(&mut self) -> Result<(u16, u16, u16, u16), I2C::Error> {
        self.inner.color()
    }

    /// Enable or disable the proximity engine.
    pub fn enable_proximity(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut val = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)?;
        if enabled { val |= 0x04; } else { val &= !0x04; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, val)
    }

    /// Read the proximity count (0–255; higher means closer).
    pub fn proximity(&mut self) -> Result<u8, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_PDATA)
    }

    /// Enable or disable the wait engine.
    pub fn enable_wait(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut val = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)?;
        if enabled { val |= 0x08; } else { val &= !0x08; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, val)
    }

    /// Configure the wait time between ALS/proximity cycles.
    ///
    /// # Arguments
    /// * `wtime` — WTIME register value 0–255.
    /// * `wlong` — `true` to enable WLONG 12x multiplier.
    pub fn configure_wait(&mut self, wtime: u8, wlong: bool) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_WTIME, wtime)?;
        let mut c1 = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG1)?;
        if wlong { c1 |= 0x02; } else { c1 &= !0x02; }
        c1 = (c1 & 0x03) | 0x60;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG1, c1)
    }

    /// Configure ALS integration time and gain.
    ///
    /// # Arguments
    /// * `atime` — ATIME register value 0–255.
    /// * `again` — ALS gain 0–3 (0=1x, 1=4x, 2=16x, 3=64x).
    pub fn configure_als(&mut self, atime: u8, again: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ATIME, atime)?;
        let mut ctrl = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        ctrl = (ctrl & 0xFC) | (again & 0x03);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl)
    }

    /// Configure proximity LED drive, gain, pulse count and length.
    pub fn configure_proximity_led(&mut self, ldrive: u8, pgain: u8, ppulse: u8, pplen: u8) -> Result<(), I2C::Error> {
        let mut ctrl = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        ctrl = ((ldrive & 0x03) << 6) | ((pgain & 0x03) << 2) | (ctrl & 0x03);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PPULSE, ((pplen & 0x03) << 6) | (ppulse & 0x3F))
    }

    /// Set additional LED current boost (0=100%, 1=150%, 2=200%, 3=300%).
    pub fn set_led_boost(&mut self, boost: u8) -> Result<(), I2C::Error> {
        let mut c2 = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG2)?;
        c2 = (c2 & 0xCF) | ((boost & 0x03) << 4) | 0x01;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG2, c2)
    }

    /// Set ALS interrupt thresholds (16-bit LE).
    pub fn als_threshold(&mut self, low: u16, high: u16) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AILTL, (low & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AILTH, ((low >> 8) & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AIHTL, (high & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AIHTH, ((high >> 8) & 0xFF) as u8)
    }

    /// Set proximity interrupt thresholds.
    pub fn proximity_threshold(&mut self, low: u8, high: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PILT, low)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PIHT, high)
    }

    /// Set interrupt persistence filters.
    pub fn set_persistence(&mut self, ppers: u8, apers: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PERS, ((ppers & 0x0F) << 4) | (apers & 0x0F))
    }

    /// Enable or disable ALS interrupt.
    pub fn enable_als_interrupt(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut val = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)?;
        if enabled { val |= 0x10; } else { val &= !0x10; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, val)
    }

    /// Enable or disable proximity interrupt.
    pub fn enable_proximity_interrupt(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut val = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)?;
        if enabled { val |= 0x20; } else { val &= !0x20; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, val)
    }

    /// Clear the proximity interrupt via address-only write to PICLEAR.
    pub fn clear_proximity_interrupt(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(self.inner.addr, &[REG_PICLEAR])
    }

    /// Clear the ALS/color interrupt via address-only write to CICLEAR.
    pub fn clear_als_interrupt(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(self.inner.addr, &[REG_CICLEAR])
    }

    /// Clear all non-gesture interrupts via address-only write to AICLEAR.
    pub fn clear_all_interrupts(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(self.inner.addr, &[REG_AICLEAR])
    }

    /// Set proximity offset for UP/RIGHT and DOWN/LEFT photodiodes (sign-magnitude).
    pub fn set_proximity_offset(&mut self, ur: i8, dl: i8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_POFFSET_UR, encode_offset(ur))?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_POFFSET_DL, encode_offset(dl))
    }

    /// Mask individual photodiodes in proximity detection.
    pub fn set_proximity_mask(&mut self, u: bool, d: bool, l: bool, r: bool) -> Result<(), I2C::Error> {
        let mut c3 = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG3)? & 0xF0;
        if u { c3 |= 0x08; }
        if d { c3 |= 0x04; }
        if l { c3 |= 0x02; }
        if r { c3 |= 0x01; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG3, c3)
    }

    /// Enable or disable the gesture engine.
    pub fn enable_gesture(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut val = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)?;
        if enabled {
            val |= 0x40;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, val)?;
            let mut g4 = read_reg(&mut self.inner.i2c, self.inner.addr, REG_GCONF4)?;
            g4 |= 0x01;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_GCONF4, g4)
        } else {
            val &= !0x40;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, val)?;
            let mut g4 = read_reg(&mut self.inner.i2c, self.inner.addr, REG_GCONF4)?;
            g4 &= !0x01;
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_GCONF4, g4)
        }
    }

    /// Configure gesture engine parameters.
    pub fn configure_gesture(&mut self, ggain: u8, gldrive: u8, gpulse: u8, gplen: u8, gwtime: u8, gpenth: u8, gexth: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GPENTH, gpenth)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GEXTH, gexth)?;
        let g2 = ((ggain & 0x03) << 5) | ((gldrive & 0x03) << 3) | (gwtime & 0x07);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GCONF2, g2)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GPULSE, ((gplen & 0x03) << 6) | (gpulse & 0x3F))
    }

    /// Check if gesture data is available in the FIFO.
    pub fn gesture_available(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_GSTATUS)? & 0x01 != 0)
    }

    /// Read gesture datasets from the FIFO.
    ///
    /// Returns a list of `(U, D, L, R)` tuples, one per dataset.
    pub fn read_gesture_fifo(&mut self, buf: &mut [(u8, u8, u8, u8)]) -> Result<usize, I2C::Error> {
        let level = read_reg(&mut self.inner.i2c, self.inner.addr, REG_GFLVL)? as usize;
        let count = if level > buf.len() { buf.len() } else { level };
        for i in 0..count {
            let mut raw = [0u8; 4];
            self.inner.i2c.write_read(self.inner.addr, &[REG_GFIFO_U], &mut raw)?;
            buf[i] = (raw[0], raw[1], raw[2], raw[3]);
        }
        Ok(count)
    }

    /// Read the number of datasets in the gesture FIFO.
    pub fn gesture_fifo_level(&mut self) -> Result<u8, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_GFLVL)
    }

    /// Clear the gesture FIFO by setting GFIFO_CLR in GCONF4.
    pub fn clear_gesture_fifo(&mut self) -> Result<(), I2C::Error> {
        let mut g4 = read_reg(&mut self.inner.i2c, self.inner.addr, REG_GCONF4)?;
        g4 |= 0x04;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GCONF4, g4)
    }

    /// Enable or disable gesture interrupt.
    pub fn enable_gesture_interrupt(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut g4 = read_reg(&mut self.inner.i2c, self.inner.addr, REG_GCONF4)?;
        if enabled { g4 |= 0x02; } else { g4 &= !0x02; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GCONF4, g4)
    }

    /// Read the raw STATUS register.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_STATUS)
    }

    /// Check if ALS/color data is valid.
    pub fn is_als_valid(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_STATUS)? & 0x01 != 0)
    }

    /// Check if proximity data is valid.
    pub fn is_proximity_valid(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_STATUS)? & 0x02 != 0)
    }

    /// Check if the clear photodiode is saturated.
    pub fn is_als_saturated(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_STATUS)? & 0x80 != 0)
    }

    /// Check if analog saturation occurred during proximity.
    pub fn is_proximity_saturated(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_STATUS)? & 0x40 != 0)
    }

    /// Read the device ID register (expect 0xAB).
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_ID)
    }
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(buf[0])
}

fn read_reg16_le<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok((buf[0] as u16) | ((buf[1] as u16) << 8))
}

fn encode_offset(value: i8) -> u8 {
    if value < 0 {
        0x80 | ((-value) as u8 & 0x7F)
    } else {
        value as u8 & 0x7F
    }
}
