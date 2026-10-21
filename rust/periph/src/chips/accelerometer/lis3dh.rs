//! LIS3DH — 3-axis ultra-low-power MEMS accelerometer (STMicroelectronics).
//!
//! Ultra-low-power three-axis MEMS accelerometer with 16-bit left-justified
//! output, four selectable full-scale ranges (±2 / ±4 / ±8 / ±16 *g*) and
//! three operating modes that trade resolution for current (low-power 8-bit,
//! normal 10-bit, high-resolution 12-bit) across output data rates from 1 Hz
//! to 5.376 kHz. On top of the conversions the chip has a 32-level FIFO, a
//! selectable high-pass filter, two independent inertial interrupt
//! generators (wake-up, free-fall, 6D/4D orientation), single/double-click
//! detection, a sleep-to-wake activity function, a self-test actuator and an
//! auxiliary three-channel 10-bit ADC whose third channel can be switched to
//! the on-chip temperature sensor.
//!
//! ## Transport
//!
//! I²C at 0x18 (SA0 = GND) or 0x19 (SA0 = VDD_IO). After the slave address
//! the master sends an 8-bit sub-address: the 7 LSbs are the register address
//! and the MSb enables address auto-increment. **Every multi-byte transfer
//! therefore ORs 0x80 into the register address** (e.g. 0xA8 to burst-read
//! the six output registers); a single-register access does not.
//!
//! ## Default configuration
//!
//! - ±2 *g* (1 mg/LSB), 100 Hz output data rate
//! - High-resolution (12-bit) mode, all three axes enabled
//! - `BDU` = 1 (block data update), high-pass filter bypassed, FIFO bypassed
//! - All interrupt routing cleared, aux ADC and temperature sensor off
//! - `CTRL_REG0` untouched
//!
//! ## Timing
//!
//! The converter needs time to settle after a mode or data-rate change, so
//! the driver owns an [`embedded_hal::delay::DelayNs`] and waits the longer
//! of the mode's turn-on time and one ODR period (datasheet Table 10)
//! before it returns. `self_test()` follows the datasheet procedure
//! including its sample delays.
//!
//! ## Interrupts
//!
//! Rust drivers expose `poll_interrupt()` only — the caller wires it into an
//! ISR or a polling loop and decides what to do with the status bits.
//! Reading `INT1_SRC` / `INT2_SRC` / `CLICK_SRC` clears the latched flags.
//!
//! ## Data conversion
//!
//! The output registers are left-justified: sign-extend the 16-bit pair
//! first, then arithmetic-shift right by 4 (12-bit high-resolution), 6
//! (10-bit normal) or 8 (8-bit low-power).

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::Connection;
use crate::connection::register::to_signed;

// --- Register map (file-private) ------------------------------------------

const REG_STATUS_AUX: u8 = 0x07;
const REG_OUT_ADC1_L: u8 = 0x08;
const REG_OUT_ADC3_H: u8 = 0x0D;
const REG_WHO_AM_I: u8 = 0x0F;
const REG_CTRL_REG0: u8 = 0x1E;
const REG_TEMP_CFG: u8 = 0x1F;
const REG_CTRL_REG1: u8 = 0x20;
const REG_CTRL_REG2: u8 = 0x21;
const REG_CTRL_REG3: u8 = 0x22;
const REG_CTRL_REG4: u8 = 0x23;
const REG_CTRL_REG5: u8 = 0x24;
const REG_CTRL_REG6: u8 = 0x25;
const REG_REFERENCE: u8 = 0x26;
const REG_STATUS: u8 = 0x27;
const REG_OUT_X_L: u8 = 0x28;
const REG_FIFO_CTRL: u8 = 0x2E;
const REG_FIFO_SRC: u8 = 0x2F;
const REG_INT1_CFG: u8 = 0x30;
const REG_INT1_SRC: u8 = 0x31;
const REG_INT1_THS: u8 = 0x32;
const REG_INT1_DUR: u8 = 0x33;
const REG_INT2_CFG: u8 = 0x34;
const REG_INT2_SRC: u8 = 0x35;
const REG_INT2_THS: u8 = 0x36;
const REG_INT2_DUR: u8 = 0x37;
const REG_CLICK_CFG: u8 = 0x38;
const REG_CLICK_SRC: u8 = 0x39;
const REG_CLICK_THS: u8 = 0x3A;
const REG_TIME_LIMIT: u8 = 0x3B;
const REG_TIME_LATENCY: u8 = 0x3C;
const REG_TIME_WINDOW: u8 = 0x3D;
const REG_ACT_THS: u8 = 0x3E;
const REG_ACT_DUR: u8 = 0x3F;

/// `WHO_AM_I` (0x0F) reset value.
const WHO_AM_I_VALUE: u8 = 0x33;
/// Initialization value of `CTRL_REG4`: `BDU` = 1, ±2 *g*, high-resolution.
const CTRL_REG4_DEFAULT: u8 = 0x88;
/// Initialization value of `CTRL_REG1`: 100 Hz, `LPen` = 0, X/Y/Z enabled.
const CTRL_REG1_DEFAULT: u8 = 0x57;

/// Default 7-bit I²C address (SA0 = GND).
pub const LIS3DH_I2C_ADDRESS: u8 = 0x18;
/// Alternate 7-bit I²C address (SA0 = VDD_IO).
pub const LIS3DH_I2C_ADDRESS_SA0_VDD: u8 = 0x19;

/// Interrupt source: a new X/Y/Z sample set is ready (`STATUS_REG.ZYXDA`).
pub const SOURCE_DATA_READY: u16 = 0x0001;
/// Interrupt source: new auxiliary-ADC sample (`STATUS_REG_AUX.321DA`).
pub const SOURCE_AUX_DATA_READY: u16 = 0x0002;
/// Interrupt source: the FIFO content exceeds the watermark (`FIFO_SRC_REG.WTM`).
pub const SOURCE_WATERMARK: u16 = 0x0004;
/// Interrupt source: the FIFO overran (`FIFO_SRC_REG.OVRN_FIFO`).
pub const SOURCE_OVERRUN: u16 = 0x0008;
/// Interrupt source: inertial generator 1 event (`INT1_SRC.IA`).
pub const SOURCE_IA1: u16 = 0x0010;
/// Interrupt source: inertial generator 2 event (`INT2_SRC.IA`).
pub const SOURCE_IA2: u16 = 0x0020;
/// Interrupt source: single or double click (`CLICK_SRC.IA`).
pub const SOURCE_CLICK: u16 = 0x0040;
/// Interrupt source: sleep-to-wake activity — pin-only, never reported by
/// `poll_interrupt()`.
pub const SOURCE_ACTIVITY: u16 = 0x0080;
/// Interrupt source: boot procedure running — pin-only, never reported by
/// `poll_interrupt()`.
pub const SOURCE_BOOT: u16 = 0x0100;

/// Operating mode: low-power, 8-bit output.
pub const MODE_LOW_POWER: u8 = 0;
/// Operating mode: normal, 10-bit output.
pub const MODE_NORMAL: u8 = 1;
/// Operating mode: high-resolution, 12-bit output.
pub const MODE_HIGH_RESOLUTION: u8 = 2;

/// FIFO mode: bypass — the buffer is not used and is emptied.
pub const FIFO_BYPASS: u8 = 0;
/// FIFO mode: FIFO — the buffer stops filling when it is full.
pub const FIFO_FIFO: u8 = 1;
/// FIFO mode: stream — the oldest sample is overwritten when the buffer is full.
pub const FIFO_STREAM: u8 = 2;
/// FIFO mode: stream-to-FIFO — fills the buffer on an interrupt trigger.
pub const FIFO_STREAM_TO_FIFO: u8 = 3;

/// Default full-scale range in *g* (1 mg/LSB).
const DEFAULT_RANGE_G: u8 = 2;
/// Default output data rate in Hz.
const DEFAULT_RATE_HZ: u16 = 100;
/// Default operating mode.
const DEFAULT_MODE: u8 = MODE_HIGH_RESOLUTION;

/// LIS3DH minimal driver — 3-axis acceleration in *g*.
///
/// Runs the datasheet Initialization Sequence at construction: verifies
/// `WHO_AM_I` (0x33), puts the control registers in a known state (the chip
/// has no soft reset), then configures ±2 *g*, 100 Hz, high-resolution 12-bit
/// mode with `BDU` on and waits for the converter to turn on.
pub struct Lis3dhMinimal<I2C, D> {
    conn: Connection<I2C>,
    delay: D,
    addr: u8,
    range_g: u8,
    mode: u8,
    rate_hz: u16,
}

impl<I2C: I2c, D: DelayNs> Lis3dhMinimal<I2C, D> {
    /// Create a new `Lis3dhMinimal` and run the Initialization Sequence.
    ///
    /// # Arguments
    /// * `conn` — [`Connection`] bound to the chip's I²C bus.
    /// * `addr` — 7-bit I²C address (0x18 with SA0 = GND, 0x19 with
    ///   SA0 = VDD_IO).
    /// * `delay` — delay source used for the converter's turn-on time and
    ///   for the self-test sample delays.
    ///
    /// # Panics
    /// Panics if `WHO_AM_I` does not read 0x33 — wrong chip, wrong address
    /// or a wiring problem.
    pub fn new(conn: Connection<I2C>, addr: u8, delay: D) -> Result<Self, I2C::Error> {
        let mut chip = Self {
            conn,
            delay,
            addr,
            range_g: DEFAULT_RANGE_G,
            mode: DEFAULT_MODE,
            rate_hz: DEFAULT_RATE_HZ,
        };
        chip.run_init_sequence()?;
        Ok(chip)
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Burst-reads the six output bytes (0x28–0x2D) with auto-increment so
    /// X, Y and Z come from a single sample (`BDU` = 1), sign-extends the
    /// left-justified 16-bit pairs and scales them with the sensitivity of
    /// the current mode and range.
    ///
    /// Returns `(x, y, z)` in *g*.
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut raw = [0u8; 6];
        self.read_burst(REG_OUT_X_L, &mut raw)?;
        let scale = sensitivity_mg(self.mode, self.range_g) / 1000.0;
        let shift = resolution_shift(self.mode);
        Ok((
            decode_axis(&raw[0..2], shift) as f32 * scale,
            decode_axis(&raw[2..4], shift) as f32 * scale,
            decode_axis(&raw[4..6], shift) as f32 * scale,
        ))
    }

    /// Read one register.
    fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.conn.read(self.addr, reg, &mut buf)?;
        Ok(buf[0])
    }

    /// Write one register.
    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.conn.write(self.addr, reg, &[value])
    }

    /// Read `buf.len()` bytes starting at `reg`.
    ///
    /// Multi-byte transfers set the MSb of the sub-address, which enables the
    /// chip's address auto-increment.
    fn read_burst(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        let sub = if buf.len() > 1 { reg | 0x80 } else { reg };
        self.conn.read(self.addr, sub, buf)
    }

    /// Steps 2-6 of the datasheet Initialization Sequence.
    fn run_init_sequence(&mut self) -> Result<(), I2C::Error> {
        // Step 2: verify the device identity.
        let chip_id = self.read_reg(REG_WHO_AM_I)?;
        if chip_id != WHO_AM_I_VALUE {
            panic!(
                "LIS3DH WHO_AM_I: expected 0x{:02X}, got 0x{:02X}",
                WHO_AM_I_VALUE, chip_id
            );
        }

        // Step 3: the chip has no soft reset — put the control registers in a
        // known state. `CTRL_REG0` and the interrupt-generator registers are
        // left untouched (the reserved registers are never written).
        self.write_reg(REG_CTRL_REG2, 0x00)?;
        self.write_reg(REG_CTRL_REG3, 0x00)?;
        self.write_reg(REG_CTRL_REG5, 0x00)?;
        self.write_reg(REG_CTRL_REG6, 0x00)?;
        self.write_reg(REG_FIFO_CTRL, 0x00)?;
        self.write_reg(REG_TEMP_CFG, 0x00)?;

        // Step 4: block data update, ±2 g, high-resolution 12-bit mode.
        self.write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT)?;

        // Step 5: leave power-down — this starts the conversions.
        self.write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)?;

        // Step 6: wait for the high-resolution turn-on (7/ODR = 70 ms at
        // 100 Hz) — the longer of the turn-on time and one ODR period.
        let settle = self.settle_ms();
        if settle > 0 {
            self.delay.delay_ms(settle);
        }
        Ok(())
    }

    /// Milliseconds to wait after an ODR / mode change: the longer of the
    /// mode's turn-on time (datasheet Table 10) and one ODR period.
    fn settle_ms(&self) -> u32 {
        if self.rate_hz == 0 {
            return 0;
        }
        let period_ms = 1000.0 / self.rate_hz as f32;
        let turn_on_ms = match self.mode {
            MODE_LOW_POWER => 1.0,
            MODE_NORMAL => 1.6,
            _ => 7.0 * period_ms,
        };
        ceil_f32(turn_on_ms.max(period_ms))
    }
}

/// LIS3DH full driver — extends the minimal driver with the complete chip
/// functionality.
///
/// Adds range / data-rate / mode selection, raw counts, status and
/// temperature reads, the auxiliary ADC, the high-pass filter, the 32-level
/// FIFO, both inertial interrupt generators (free-fall, 6D / 4D
/// orientation), single / double click detection, the sleep-to-wake activity
/// function, interrupt routing, memory reboot, SDO pull-up control and the
/// electrostatic self-test.
///
/// The Rust analog of "Full never duplicates Minimal" is composition:
/// `inner` owns the minimal driver, every inherited method is a one-line
/// delegate, and the Full-only methods follow.
pub struct Lis3dhFull<I2C, D> {
    inner: Lis3dhMinimal<I2C, D>,
    enabled1: u16,
    enabled2: u16,
}

impl<I2C: I2c, D: DelayNs> Lis3dhFull<I2C, D> {
    /// Create a new `Lis3dhFull` and run the Initialization Sequence.
    ///
    /// # Arguments
    /// * `conn` — [`Connection`] bound to the chip's I²C bus.
    /// * `addr` — 7-bit I²C address (0x18 with SA0 = GND, 0x19 with
    ///   SA0 = VDD_IO).
    /// * `delay` — delay source used for the converter's turn-on time and
    ///   for the self-test sample delays.
    pub fn new(conn: Connection<I2C>, addr: u8, delay: D) -> Result<Self, I2C::Error> {
        let inner = Lis3dhMinimal::new(conn, addr, delay)?;
        Ok(Self { inner, enabled1: 0, enabled2: 0 })
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns `(x, y, z)` in *g*. Delegates to
    /// [`Lis3dhMinimal::read`].
    pub fn read(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.read()
    }

    /// Set the full-scale measurement range.
    ///
    /// Read-modify-writes the `FS` field of `CTRL_REG4` and updates the
    /// cached sensitivity used by the conversions.
    ///
    /// `range_g` must be 2, 4, 8 or 16 (*g*); any other value is ignored
    /// (no register is written).
    pub fn set_range(&mut self, range_g: u8) -> Result<(), I2C::Error> {
        let fs = match range_g {
            2 => 0x00,
            4 => 0x10,
            8 => 0x20,
            16 => 0x30,
            _ => return Ok(()),
        };
        let ctrl4 = self.inner.read_reg(REG_CTRL_REG4)?;
        self.inner.write_reg(REG_CTRL_REG4, (ctrl4 & !0x30) | fs)?;
        self.inner.range_g = range_g;
        Ok(())
    }

    /// Set the output data rate (or power down).
    ///
    /// Read-modify-writes the `ODR` field of `CTRL_REG1` and waits for the
    /// turn-on time before returning.
    ///
    /// `rate_hz` must be 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344,
    /// 1620 or 5376. 1620 and 5376 Hz exist in low-power mode only, 1344 Hz
    /// in normal / high-resolution mode only; an illegal combination with the
    /// current mode is ignored (no register is written).
    pub fn set_data_rate(&mut self, rate_hz: u16) -> Result<(), I2C::Error> {
        let code = match rate_code(rate_hz) {
            Some(code) => code,
            None => return Ok(()),
        };
        if is_low_power_only(rate_hz) && self.inner.mode != MODE_LOW_POWER {
            return Ok(());
        }
        if is_normal_hr_only(rate_hz) && self.inner.mode == MODE_LOW_POWER {
            return Ok(());
        }
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        self.inner.write_reg(REG_CTRL_REG1, (ctrl1 & 0x0F) | code)?;
        self.inner.rate_hz = rate_hz;
        let settle = self.inner.settle_ms();
        if settle > 0 {
            self.inner.delay.delay_ms(settle);
        }
        Ok(())
    }

    /// Set the operating mode (resolution / power trade-off).
    ///
    /// Read-modify-writes `LPen` in `CTRL_REG1` and `HR` in `CTRL_REG4`,
    /// updates the cached resolution and waits for the mode's turn-on time.
    ///
    /// `mode` must be [`MODE_LOW_POWER`], [`MODE_NORMAL`] or
    /// [`MODE_HIGH_RESOLUTION`]. `LPen` = 1 with `HR` = 1 is never written,
    /// and a data rate that is illegal in the new mode is rejected (no
    /// register is written).
    pub fn set_mode(&mut self, mode: u8) -> Result<(), I2C::Error> {
        if mode > MODE_HIGH_RESOLUTION {
            return Ok(());
        }
        if mode == MODE_LOW_POWER && is_normal_hr_only(self.inner.rate_hz) {
            return Ok(());
        }
        if mode != MODE_LOW_POWER && is_low_power_only(self.inner.rate_hz) {
            return Ok(());
        }
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        let ctrl4 = self.inner.read_reg(REG_CTRL_REG4)?;
        let (ctrl1, ctrl4) = if mode == MODE_LOW_POWER {
            (ctrl1 | 0x08, ctrl4 & !0x08) // LPen = 1, HR = 0
        } else {
            let hr = if mode == MODE_HIGH_RESOLUTION { 0x08 } else { 0x00 };
            (ctrl1 & !0x08, (ctrl4 & !0x08) | hr)
        };
        self.inner.write_reg(REG_CTRL_REG1, ctrl1)?;
        self.inner.write_reg(REG_CTRL_REG4, ctrl4)?;
        self.inner.mode = mode;
        let settle = self.inner.settle_ms();
        if settle > 0 {
            self.inner.delay.delay_ms(settle);
        }
        Ok(())
    }

    /// Enable or disable the individual measurement axes.
    ///
    /// Sets `Xen` / `Yen` / `Zen` in `CTRL_REG1`.
    pub fn set_axes(&mut self, x: bool, y: bool, z: bool) -> Result<(), I2C::Error> {
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        let axes = (x as u8) | ((y as u8) << 1) | ((z as u8) << 2);
        self.inner.write_reg(REG_CTRL_REG1, (ctrl1 & 0xF8) | axes)
    }

    /// Stop conversions by setting `ODR` = 0000.
    ///
    /// The register contents are retained.
    pub fn power_down(&mut self) -> Result<(), I2C::Error> {
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        self.inner.write_reg(REG_CTRL_REG1, ctrl1 & 0x0F)?;
        self.inner.rate_hz = 0;
        Ok(())
    }

    /// Read the raw acceleration counts at the current resolution.
    ///
    /// Returns `(x, y, z)` signed counts — 12-bit in high-resolution mode,
    /// 10-bit in normal mode, 8-bit in low-power mode.
    pub fn read_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut raw = [0u8; 6];
        self.inner.read_burst(REG_OUT_X_L, &mut raw)?;
        let shift = resolution_shift(self.inner.mode);
        Ok((
            decode_axis(&raw[0..2], shift),
            decode_axis(&raw[2..4], shift),
            decode_axis(&raw[4..6], shift),
        ))
    }

    /// Check whether a complete X/Y/Z sample set is ready.
    ///
    /// Returns `STATUS_REG.ZYXDA` — true when all three axes have new data.
    pub fn new_data_available(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_STATUS)? & 0x08 != 0)
    }

    /// Read the STATUS register.
    ///
    /// Returns the `STATUS_REG` byte — the overrun (`ZYXOR`..`XOR`) and
    /// data-available (`ZYXDA`..`XDA`) flags.
    pub fn read_status(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_STATUS)
    }

    /// Read the relative on-chip temperature.
    ///
    /// Enables `ADC_EN` and `TEMP_EN` in `TEMP_CFG_REG` on first use (the
    /// channel-3 conversions run through the same ADC) and waits one ODR
    /// period for the first conversion.
    ///
    /// The datasheet specifies only 1 digit/°C with no zero-degree
    /// reference, so the value is a relative delta, never an absolute
    /// temperature.
    ///
    /// Returns the relative temperature in °C (8-bit two's complement).
    pub fn read_temperature_delta(&mut self) -> Result<i8, I2C::Error> {
        let cfg = self.inner.read_reg(REG_TEMP_CFG)?;
        if cfg & 0xC0 != 0xC0 {
            self.inner.write_reg(REG_TEMP_CFG, cfg | 0xC0)?;
            if self.inner.rate_hz > 0 {
                let period = ceil_f32(1000.0 / self.inner.rate_hz as f32);
                self.inner.delay.delay_ms(period);
            }
        }
        Ok(self.inner.read_reg(REG_OUT_ADC3_H)? as i8)
    }

    /// Read one auxiliary ADC channel.
    ///
    /// `channel` must be 1, 2 or 3. Channel 3 is the temperature-sensor
    /// input while `TEMP_EN` = 1.
    ///
    /// Returns the input voltage in V (1.200 V centre, ±400 mV range; the
    /// mapping is derived from the datasheet's input range). Returns
    /// `f32::NAN` for an invalid channel or for channel 3 while `TEMP_EN`
    /// = 1 — use [`Lis3dhFull::read_temperature_delta`] instead.
    pub fn read_adc(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        if !(1..=3).contains(&channel) {
            return Ok(f32::NAN);
        }
        if channel == 3 {
            let cfg = self.inner.read_reg(REG_TEMP_CFG)?;
            if cfg & 0x40 != 0 {
                return Ok(f32::NAN);
            }
        }
        let base = REG_OUT_ADC1_L + (channel - 1) * 2;
        let mut raw = [0u8; 2];
        self.inner.read_burst(base, &mut raw)?;
        let counts = decode_axis(&raw[0..2], adc_shift(self.inner.mode));
        Ok(1.200 + counts as f32 * (0.400 / 512.0))
    }

    /// Enable or disable the auxiliary ADC (`ADC_EN` in `TEMP_CFG_REG`).
    pub fn enable_adc(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let cfg = self.inner.read_reg(REG_TEMP_CFG)?;
        let out = if enabled { cfg | 0x80 } else { cfg & !0x80 };
        self.inner.write_reg(REG_TEMP_CFG, out)
    }

    /// Configure the high-pass filter (`CTRL_REG2`).
    ///
    /// * `mode` — `HPM`: 0 normal (reset by reading `REFERENCE`), 1
    ///   reference signal for filtering, 2 normal, 3 auto-reset on interrupt
    ///   event.
    /// * `cutoff` — `HPCF<2:1>` code 0-3 (the datasheet tabulates no Hz
    ///   values; they scale with the ODR).
    /// * `filter_output` — route the filtered data to the output registers
    ///   and the FIFO (`FDS`).
    /// * `on_ia1` / `on_ia2` / `on_click` — apply the filter to interrupt
    ///   generator 1 / generator 2 / the click function.
    ///
    /// A `mode` or `cutoff` outside 0-3 is ignored (no register is written).
    pub fn set_high_pass(
        &mut self,
        mode: u8,
        cutoff: u8,
        filter_output: bool,
        on_ia1: bool,
        on_ia2: bool,
        on_click: bool,
    ) -> Result<(), I2C::Error> {
        if mode > 3 || cutoff > 3 {
            return Ok(());
        }
        let mut value = (mode << 6) | (cutoff << 4);
        if filter_output {
            value |= 0x08;
        }
        if on_click {
            value |= 0x04;
        }
        if on_ia2 {
            value |= 0x02;
        }
        if on_ia1 {
            value |= 0x01;
        }
        self.inner.write_reg(REG_CTRL_REG2, value)
    }

    /// Reset the high-pass filter by reading `REFERENCE` (`HPM` = 00).
    ///
    /// Returns the `REFERENCE` register value that was read.
    pub fn reset_high_pass(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_REFERENCE)
    }

    /// Write the high-pass reference value (`HPM` = 01).
    pub fn set_reference(&mut self, value: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_REFERENCE, value)
    }

    /// Configure the 32-level FIFO (`FIFO_CTRL_REG`).
    ///
    /// * `mode` — [`FIFO_BYPASS`], [`FIFO_FIFO`], [`FIFO_STREAM`] or
    ///   [`FIFO_STREAM_TO_FIFO`].
    /// * `watermark` — the `FTH` level, 0-31.
    /// * `trigger_int2` — the stream-to-FIFO trigger source: false = INT1,
    ///   true = INT2 (`TR`).
    ///
    /// `FIFO_EN` in `CTRL_REG5` is set for the modes 1-3; returning to
    /// bypass clears the buffer. Only bypass empties the buffer — restart a
    /// FIFO mode through bypass after a full drain. A `watermark` above 31
    /// is ignored (no register is written).
    pub fn set_fifo(
        &mut self,
        mode: u8,
        watermark: u8,
        trigger_int2: bool,
    ) -> Result<(), I2C::Error> {
        if mode > FIFO_STREAM_TO_FIFO || watermark > 31 {
            return Ok(());
        }
        let value = (mode << 6) | (if trigger_int2 { 0x20 } else { 0x00 }) | watermark;
        self.inner.write_reg(REG_FIFO_CTRL, value)?;
        let ctrl5 = self.inner.read_reg(REG_CTRL_REG5)?;
        let out = if mode == FIFO_BYPASS { ctrl5 & !0x40 } else { ctrl5 | 0x40 };
        self.inner.write_reg(REG_CTRL_REG5, out)
    }

    /// Read the number of unread sample sets in the FIFO.
    ///
    /// Returns `FSS<4:0>` — 0-32 unread sample sets.
    pub fn fifo_level(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.inner.read_reg(REG_FIFO_SRC)? & 0x1F)
    }

    /// Drain sample sets from the FIFO.
    ///
    /// Each sample set is one 6-byte burst of 0x28-0x2D; the address
    /// auto-increment wraps back to 0x28, so a full drain is 32 reads.
    ///
    /// `count` caps the number of sample sets to read — `None` reads all
    /// the `FSS` entries currently in the FIFO. The FIFO is never read
    /// beyond its actual content.
    ///
    /// Returns the samples as `(x, y, z)` tuples in *g*, oldest sample
    /// first.
    pub fn read_fifo(
        &mut self,
        count: Option<u8>,
    ) -> Result<heapless::Vec<(f32, f32, f32), 32>, I2C::Error> {
        let level = self.fifo_level()? as usize;
        let n = match count {
            Some(count) => core::cmp::min(count as usize, level),
            None => level,
        };
        let scale = sensitivity_mg(self.inner.mode, self.inner.range_g) / 1000.0;
        let shift = resolution_shift(self.inner.mode);
        let mut out = heapless::Vec::new();
        for _ in 0..n {
            let mut raw = [0u8; 6];
            self.inner.read_burst(REG_OUT_X_L, &mut raw)?;
            let _ = out.push((
                decode_axis(&raw[0..2], shift) as f32 * scale,
                decode_axis(&raw[2..4], shift) as f32 * scale,
                decode_axis(&raw[4..6], shift) as f32 * scale,
            ));
        }
        Ok(out)
    }

    /// Decode `FIFO_SRC_REG`.
    ///
    /// Returns `(watermark, overrun, empty, level)` — the three flags are
    /// booleans, `level` is `FSS<4:0>` (0-32).
    pub fn read_fifo_status(&mut self) -> Result<(bool, bool, bool, u8), I2C::Error> {
        let raw = self.inner.read_reg(REG_FIFO_SRC)?;
        Ok((raw & 0x80 != 0, raw & 0x40 != 0, raw & 0x20 != 0, raw & 0x1F))
    }

    /// Configure an inertial interrupt generator (motion / threshold).
    ///
    /// * `generator` — 1 or 2, the `INT1` or `INT2` register block.
    /// * `events` — the 6-bit `ZHIE`..`XLIE` event-enable mask (0-0x3F).
    /// * `threshold_g` — the threshold in *g*, scaled with the current
    ///   full-scale range (16 / 32 / 62 / 186 mg per LSb).
    /// * `duration_ms` — the minimum duration the events must hold, in ms
    ///   (`INTx_DURATION`, 1 LSb = 1/ODR).
    /// * `and_mode` — true for an AND combination of the enabled events
    ///   (`AOI` = 1), false for OR.
    ///
    /// An invalid generator or an `events` mask above 0x3F is ignored (no
    /// register is written).
    pub fn set_motion_interrupt(
        &mut self,
        generator: u8,
        events: u8,
        threshold_g: f32,
        duration_ms: f32,
        and_mode: bool,
    ) -> Result<(), I2C::Error> {
        let (cfg_reg, ths_reg, dur_reg) = match generator_regs(generator) {
            Some(regs) => regs,
            None => return Ok(()),
        };
        if events > 0x3F {
            return Ok(());
        }
        let cfg = (if and_mode { 0x80 } else { 0x00 }) | (events & 0x3F);
        self.inner.write_reg(cfg_reg, cfg)?;
        let ths = self.threshold_code(threshold_g);
        self.inner.write_reg(ths_reg, ths)?;
        let dur = self.duration_code(duration_ms, 127);
        self.inner.write_reg(dur_reg, dur)
    }

    /// Configure the free-fall detector: all three axes low, AND mode.
    ///
    /// Convenience wrapper around the generator's `INTx_CFG` = 0x95 (`AOI`
    /// plus `XLIE` / `YLIE` / `ZLIE`).
    ///
    /// * `generator` — 1 or 2.
    /// * `threshold_g` — the free-fall threshold in *g*.
    /// * `duration_ms` — the minimum time below the threshold, in ms.
    ///
    /// An invalid generator is ignored (no register is written).
    pub fn set_free_fall(
        &mut self,
        generator: u8,
        threshold_g: f32,
        duration_ms: f32,
    ) -> Result<(), I2C::Error> {
        let (cfg_reg, ths_reg, dur_reg) = match generator_regs(generator) {
            Some(regs) => regs,
            None => return Ok(()),
        };
        self.inner.write_reg(cfg_reg, 0x95)?;
        let ths = self.threshold_code(threshold_g);
        self.inner.write_reg(ths_reg, ths)?;
        let dur = self.duration_code(duration_ms, 127);
        self.inner.write_reg(dur_reg, dur)
    }

    /// Configure 6D / 4D orientation detection.
    ///
    /// `AOI` / `6D` = 01 is *movement* recognition (a one-ODR pulse), 11 is
    /// *position* recognition (a level that stays active while the
    /// orientation is inside a known zone).
    ///
    /// * `generator` — 1 or 2.
    /// * `position` — false = 6D movement recognition, true = 6D position
    ///   recognition.
    /// * `four_d` — enable 4D detection (`D4D_INTx`), which disables the
    ///   Z-axis zones.
    ///
    /// An invalid generator is ignored (no register is written).
    pub fn set_orientation(
        &mut self,
        generator: u8,
        position: bool,
        four_d: bool,
    ) -> Result<(), I2C::Error> {
        let (cfg_reg, _, _) = match generator_regs(generator) {
            Some(regs) => regs,
            None => return Ok(()),
        };
        let axis_zones = if four_d { 0x0F } else { 0x3F };
        let cfg = (if position { 0xC0 } else { 0x40 }) | axis_zones;
        self.inner.write_reg(cfg_reg, cfg)?;
        let d4d_bit = if generator == 1 { 0x04 } else { 0x01 };
        let ctrl5 = self.inner.read_reg(REG_CTRL_REG5)?;
        let out = if four_d { ctrl5 | d4d_bit } else { ctrl5 & !d4d_bit };
        self.inner.write_reg(REG_CTRL_REG5, out)
    }

    /// Latch a generator's interrupt until its `INTx_SRC` is read.
    ///
    /// * `generator` — 1 or 2.
    /// * `enabled` — true to latch (`LIR_INTx`), false for a self-resetting
    ///   interrupt.
    ///
    /// An invalid generator is ignored (no register is written).
    pub fn set_latch(&mut self, generator: u8, enabled: bool) -> Result<(), I2C::Error> {
        let bit = match generator {
            1 => 0x08,
            2 => 0x02,
            _ => return Ok(()),
        };
        let ctrl5 = self.inner.read_reg(REG_CTRL_REG5)?;
        let out = if enabled { ctrl5 | bit } else { ctrl5 & !bit };
        self.inner.write_reg(REG_CTRL_REG5, out)
    }

    /// Read a generator's `INTx_SRC` — **clears** the interrupt.
    ///
    /// * `generator` — 1 or 2.
    ///
    /// Returns the `INTx_SRC` byte (`IA` = bit 6, per-axis event flags in
    /// bits 5-0). An invalid generator returns 0.
    pub fn read_interrupt_source(&mut self, generator: u8) -> Result<u8, I2C::Error> {
        let reg = match generator {
            1 => REG_INT1_SRC,
            2 => REG_INT2_SRC,
            _ => return Ok(0),
        };
        self.inner.read_reg(reg)
    }

    /// Configure single / double click detection (`CLICK_*`).
    ///
    /// * `single` — the axis mask of the single-click enables (X = 1,
    ///   Y = 2, Z = 4).
    /// * `double` — the axis mask of the double-click enables (X = 1,
    ///   Y = 2, Z = 4).
    /// * `threshold_g` — the click threshold in *g* (the same scale as
    ///   `INTx_THS`).
    /// * `time_limit_ms` — the maximum time between the two clicks of a
    ///   double click.
    /// * `latency_ms` — the minimum time between two consecutive clicks.
    /// * `window_ms` — the maximum time after the first click of a double
    ///   click.
    /// * `latch` — keep the interrupt high until `CLICK_SRC` is read
    ///   (`LIR_Click`).
    ///
    /// An axis mask above 7 is ignored (no register is written).
    pub fn set_click(
        &mut self,
        single: u8,
        double: u8,
        threshold_g: f32,
        time_limit_ms: f32,
        latency_ms: f32,
        window_ms: f32,
        latch: bool,
    ) -> Result<(), I2C::Error> {
        if single > 7 || double > 7 {
            return Ok(());
        }
        let cfg = click_cfg(single, double);
        let ths = self.threshold_code(threshold_g);
        self.inner.write_reg(REG_CLICK_CFG, cfg)?;
        self.inner
            .write_reg(REG_CLICK_THS, ths | if latch { 0x80 } else { 0x00 })?;
        let limit = self.duration_code(time_limit_ms, 127);
        self.inner.write_reg(REG_TIME_LIMIT, limit)?;
        let latency = self.duration_code(latency_ms, 255);
        self.inner.write_reg(REG_TIME_LATENCY, latency)?;
        let window = self.duration_code(window_ms, 255);
        self.inner.write_reg(REG_TIME_WINDOW, window)
    }

    /// Read `CLICK_SRC` — **clears** the click interrupt.
    ///
    /// Returns the `CLICK_SRC` byte (`IA` = bit 6, `DCLICK` = bit 5,
    /// `SCLICK` = bit 4, `Sign` = bit 3, the axis flags in bits 2-0).
    pub fn read_click_source(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_CLICK_SRC)
    }

    /// Configure the sleep-to-wake / return-to-sleep activity function.
    ///
    /// While the function has dropped the chip into its 10 Hz low-power
    /// state the `ODR`, `LPen` and `HR` settings are ignored; the chip
    /// restores them on activity. Pair with [`SOURCE_ACTIVITY`].
    ///
    /// * `threshold_g` — the activation threshold in *g* (`ACT_THS`).
    /// * `duration_ms` — the activation duration in ms (`ACT_DUR`, where
    ///   1 LSb = (8·ACT_DUR + 1)/ODR).
    ///
    /// A threshold that rounds to 0 counts is ignored (`ACT_THS` must be
    /// non-zero).
    pub fn set_activity(&mut self, threshold_g: f32, duration_ms: f32) -> Result<(), I2C::Error> {
        let code = self.threshold_code(threshold_g);
        if code == 0 {
            return Ok(());
        }
        let dur = if self.inner.rate_hz == 0 {
            0
        } else {
            let raw = libm::roundf(
                (duration_ms * self.inner.rate_hz as f32 / 1000.0 - 1.0) / 8.0,
            ) as i32;
            raw.clamp(0, 255) as u8
        };
        self.inner.write_reg(REG_ACT_THS, code)?;
        self.inner.write_reg(REG_ACT_DUR, dur)
    }

    /// Set the shared INT1 / INT2 output polarity (`INT_POLARITY`).
    ///
    /// `active_low` = true selects active-low, false (the reset default)
    /// active-high.
    pub fn set_interrupt_polarity(&mut self, active_low: bool) -> Result<(), I2C::Error> {
        let ctrl6 = self.inner.read_reg(REG_CTRL_REG6)?;
        let out = if active_low { ctrl6 | 0x02 } else { ctrl6 & !0x02 };
        self.inner.write_reg(REG_CTRL_REG6, out)
    }

    /// Route one interrupt source to INT`pin`.
    ///
    /// Only routes the source — the generator or the function behind it is
    /// configured with the `set_*` methods. `pin` is ignored for the
    /// INT1-only (data-ready, aux data-ready, watermark, overrun) and the
    /// INT2-only (activity, boot) sources.
    ///
    /// * `source` — one of the `SOURCE_*` constants.
    /// * `pin` — 1 or 2.
    ///
    /// An unknown source or an invalid pin is ignored (no register is
    /// written).
    pub fn enable_interrupt(&mut self, source: u16, pin: u8) -> Result<(), I2C::Error> {
        let (reg, mask, track) = match route_interrupt(source, pin) {
            Some(route) => route,
            None => return Ok(()),
        };
        let value = self.inner.read_reg(reg)?;
        self.inner.write_reg(reg, value | mask)?;
        let bit = 1u16 << source.trailing_zeros();
        if track == 1 {
            self.enabled1 |= bit;
        } else {
            self.enabled2 |= bit;
        }
        Ok(())
    }

    /// Remove one interrupt source from INT`pin`.
    ///
    /// * `source` — one of the `SOURCE_*` constants.
    /// * `pin` — 1 or 2.
    ///
    /// An unknown source or an invalid pin is ignored (no register is
    /// written).
    pub fn disable_interrupt(&mut self, source: u16, pin: u8) -> Result<(), I2C::Error> {
        let (reg, mask, track) = match route_interrupt(source, pin) {
            Some(route) => route,
            None => return Ok(()),
        };
        let value = self.inner.read_reg(reg)?;
        self.inner.write_reg(reg, value & !mask)?;
        let bit = 1u16 << source.trailing_zeros();
        if track == 1 {
            self.enabled1 &= !bit;
        } else {
            self.enabled2 &= !bit;
        }
        Ok(())
    }

    /// Read the source registers of every currently enabled source.
    ///
    /// **Reading `INT1_SRC` / `INT2_SRC` / `CLICK_SRC` clears their latched
    /// flags** (and the INT pin).
    ///
    /// The caller is responsible for wiring this into an ISR or a polling
    /// loop — the driver has no interrupt delivery of its own.
    ///
    /// Returns the combined status: bit 0 [`SOURCE_DATA_READY`], bit 1
    /// [`SOURCE_AUX_DATA_READY`], bit 2 [`SOURCE_WATERMARK`], bit 3
    /// [`SOURCE_OVERRUN`], bit 4 [`SOURCE_IA1`], bit 5 [`SOURCE_IA2`], bit 6
    /// [`SOURCE_CLICK`]. Sources that are not enabled read as 0;
    /// [`SOURCE_ACTIVITY`] and [`SOURCE_BOOT`] have no register flag and are
    /// never reported.
    pub fn poll_interrupt(&mut self) -> Result<u16, I2C::Error> {
        let e1 = self.enabled1;
        let e2 = self.enabled2;
        let enabled = e1 | e2;
        let mut status = 0u16;
        // The INT1-only sources are routed through CTRL_REG3.
        if e1 & 0x0001 != 0 && self.inner.read_reg(REG_STATUS)? & 0x08 != 0 {
            status |= SOURCE_DATA_READY;
        }
        if e1 & 0x0002 != 0 && self.inner.read_reg(REG_STATUS_AUX)? & 0x08 != 0 {
            status |= SOURCE_AUX_DATA_READY;
        }
        if e1 & 0x0004 != 0 || e1 & 0x0008 != 0 {
            let fifo = self.inner.read_reg(REG_FIFO_SRC)?;
            if e1 & 0x0004 != 0 && fifo & 0x80 != 0 {
                status |= SOURCE_WATERMARK;
            }
            if e1 & 0x0008 != 0 && fifo & 0x40 != 0 {
                status |= SOURCE_OVERRUN;
            }
        }
        // The generator flags live in their own source register, no matter
        // which pin the generator is routed to.
        if enabled & 0x0010 != 0 && self.inner.read_reg(REG_INT1_SRC)? & 0x40 != 0 {
            status |= SOURCE_IA1;
        }
        if enabled & 0x0020 != 0 && self.inner.read_reg(REG_INT2_SRC)? & 0x40 != 0 {
            status |= SOURCE_IA2;
        }
        if enabled & 0x0040 != 0 && self.inner.read_reg(REG_CLICK_SRC)? & 0x40 != 0 {
            status |= SOURCE_CLICK;
        }
        Ok(status)
    }

    /// Reboot the memory content (`BOOT` = 1) and re-run the Initialization
    /// Sequence.
    ///
    /// Reloads the factory trim and waits 5 ms. The datasheet does not say
    /// whether the control registers survive, so the Initialization Sequence
    /// runs again afterwards — the configuration returns to the minimal
    /// defaults.
    pub fn reboot(&mut self) -> Result<(), I2C::Error> {
        let ctrl5 = self.inner.read_reg(REG_CTRL_REG5)?;
        self.inner.write_reg(REG_CTRL_REG5, ctrl5 | 0x80)?;
        self.inner.delay.delay_ms(5);
        self.inner.range_g = DEFAULT_RANGE_G;
        self.inner.mode = DEFAULT_MODE;
        self.inner.rate_hz = DEFAULT_RATE_HZ;
        self.enabled1 = 0;
        self.enabled2 = 0;
        self.inner.run_init_sequence()
    }

    /// Connect or disconnect the pull-up on SDO/SA0 (`CTRL_REG0`).
    ///
    /// `CTRL_REG0` is not a normal register: bit 4 is forced to 1 and the
    /// reserved bits stay 0. Only bit 7 (`SDO_PU_DISC`) is written.
    ///
    /// `connected` = true connects the pull-up (`SDO_PU_DISC` = 0), false
    /// disconnects it.
    pub fn set_sdo_pullup(&mut self, connected: bool) -> Result<(), I2C::Error> {
        let value = 0x10 | if connected { 0x00 } else { 0x80 };
        self.inner.write_reg(REG_CTRL_REG0, value)
    }

    /// Run the datasheet electrostatic self-test.
    ///
    /// Configures ±2 *g*, normal (10-bit) mode, 50 Hz with `BDU` on,
    /// averages a few samples with `ST` = 00, enables `ST` (01 = self-test
    /// 0, 10 = self-test 1), waits 2 samples and averages again. Every axis
    /// must show a 17-360 LSb change (1 LSb = 4 mg at 10-bit / ±2 *g*).
    /// The previous `CTRL_REG1` / `CTRL_REG4` configuration is restored
    /// afterwards.
    ///
    /// * `mode` — 1 = self-test 0, 2 = self-test 1.
    ///
    /// Returns true if every axis showed a change inside 17-360 LSb. An
    /// invalid mode returns false (no register is written).
    pub fn self_test(&mut self, mode: u8) -> Result<bool, I2C::Error> {
        if mode != 1 && mode != 2 {
            return Ok(false);
        }
        let saved_ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        let saved_ctrl4 = self.inner.read_reg(REG_CTRL_REG4)?;
        let saved_range = self.inner.range_g;
        let saved_mode = self.inner.mode;
        let saved_rate = self.inner.rate_hz;

        let result = self.self_test_run(mode);

        // Restore the previous configuration, whatever the outcome.
        let _ = self.inner.write_reg(REG_CTRL_REG1, saved_ctrl1);
        let _ = self.inner.write_reg(REG_CTRL_REG4, saved_ctrl4);
        self.inner.range_g = saved_range;
        self.inner.mode = saved_mode;
        self.inner.rate_hz = saved_rate;

        result
    }

    /// Run the self-test measurement.
    ///
    /// Averages a few samples with `ST` = 00, enables `ST`, waits 2 samples
    /// and averages again.
    fn self_test_run(&mut self, mode: u8) -> Result<bool, I2C::Error> {
        self.self_test_configure(0x00)?;
        self.inner.delay.delay_ms(10);
        let before = self.average_raw(5, 20)?;

        self.self_test_configure(if mode == 1 { 0x02 } else { 0x04 })?;
        self.inner.delay.delay_ms(40); // 2 samples at 50 Hz
        let after = self.average_raw(5, 20)?;

        let mut ok = true;
        for axis in 0..3 {
            let delta = (after[axis] - before[axis]).abs();
            if !(17.0..=360.0).contains(&delta) {
                ok = false;
            }
        }
        Ok(ok)
    }

    /// Switch the chip into the self-test configuration.
    ///
    /// Writes `CTRL_REG4` = 0x80 | `st_bits` (±2 *g*, `BDU`) and `CTRL_REG1`
    /// = 0x47 (50 Hz, `LPen` = 0, X/Y/Z enabled) and updates the cached
    /// range, mode and rate.
    fn self_test_configure(&mut self, st_bits: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CTRL_REG4, 0x80 | st_bits)?;
        self.inner.write_reg(REG_CTRL_REG1, 0x47)?;
        self.inner.range_g = DEFAULT_RANGE_G;
        self.inner.mode = MODE_NORMAL;
        self.inner.rate_hz = 50;
        Ok(())
    }

    /// Average `samples` raw readings, `sample_ms` apart.
    fn average_raw(&mut self, samples: u8, sample_ms: u32) -> Result<[f32; 3], I2C::Error> {
        let mut total = [0f32; 3];
        for _ in 0..samples {
            let (x, y, z) = self.read_raw()?;
            total[0] += x as f32;
            total[1] += y as f32;
            total[2] += z as f32;
            self.inner.delay.delay_ms(sample_ms);
        }
        let n = samples as f32;
        Ok([total[0] / n, total[1] / n, total[2] / n])
    }

    /// Scale a threshold in *g* into the `INTx_THS` / `ACT_THS` /
    /// `CLICK_THS` code (7 bits).
    fn threshold_code(&self, threshold_g: f32) -> u8 {
        let lsb_mg = threshold_lsb_mg(self.inner.range_g);
        let code = libm::roundf(threshold_g * 1000.0 / lsb_mg) as i32;
        code.clamp(0, 127) as u8
    }

    /// Scale a duration in ms into the `INTx_DURATION` / `TIME_*` code
    /// (1 LSb = 1/ODR).
    fn duration_code(&self, duration_ms: f32, limit: u8) -> u8 {
        if self.inner.rate_hz == 0 {
            return 0;
        }
        let code = libm::roundf(duration_ms * self.inner.rate_hz as f32 / 1000.0) as i32;
        code.clamp(0, limit as i32) as u8
    }
}

// --- Free helper functions -------------------------------------------------

/// Decode a left-justified 16-bit output pair.
///
/// The 16-bit value is sign-extended first, then arithmetic-shifted right
/// by `shift` (4 for 12-bit, 6 for 10-bit, 8 for 8-bit output).
fn decode_axis(p: &[u8], shift: u32) -> i16 {
    let raw = (p[0] as u32) | ((p[1] as u32) << 8);
    (to_signed(raw, 16) as i16) >> shift
}

/// Sensitivity in mg/LSB for the given mode and full-scale range
/// (datasheet Table 4).
fn sensitivity_mg(mode: u8, range_g: u8) -> f32 {
    match (mode, range_g) {
        (MODE_HIGH_RESOLUTION, 2) => 1.0,
        (MODE_HIGH_RESOLUTION, 4) => 2.0,
        (MODE_HIGH_RESOLUTION, 8) => 4.0,
        (MODE_HIGH_RESOLUTION, 16) => 12.0,
        (MODE_NORMAL, 2) => 4.0,
        (MODE_NORMAL, 4) => 8.0,
        (MODE_NORMAL, 8) => 16.0,
        (MODE_NORMAL, 16) => 48.0,
        (MODE_LOW_POWER, 2) => 16.0,
        (MODE_LOW_POWER, 4) => 32.0,
        (MODE_LOW_POWER, 8) => 64.0,
        (MODE_LOW_POWER, 16) => 192.0,
        _ => 1.0,
    }
}

/// Right-shift of the left-justified output pair for the given mode.
fn resolution_shift(mode: u8) -> u32 {
    match mode {
        MODE_LOW_POWER => 8,
        MODE_NORMAL => 6,
        _ => 4,
    }
}

/// Right-shift of the left-justified auxiliary-ADC output pair.
fn adc_shift(mode: u8) -> u32 {
    match mode {
        MODE_LOW_POWER => 8,
        _ => 6,
    }
}

/// Interrupt-threshold LSB in mg for the given full-scale range.
fn threshold_lsb_mg(range_g: u8) -> f32 {
    match range_g {
        4 => 32.0,
        8 => 62.0,
        16 => 186.0,
        _ => 16.0,
    }
}

/// The `ODR<3:0>` code of an output data rate, or `None` for an unknown rate.
fn rate_code(rate_hz: u16) -> Option<u8> {
    Some(match rate_hz {
        0 => 0x00,    // power-down
        1 => 0x10,
        10 => 0x20,
        25 => 0x30,
        50 => 0x40,
        100 => 0x50,
        200 => 0x60,
        400 => 0x70,
        1620 => 0x80, // low-power mode only
        1344 => 0x90, // normal / high-resolution mode only
        5376 => 0x90, // low-power mode only
        _ => return None,
    })
}

/// True for the rates that exist in low-power mode only.
fn is_low_power_only(rate_hz: u16) -> bool {
    rate_hz == 1620 || rate_hz == 5376
}

/// True for the rates that exist in normal / high-resolution mode only.
fn is_normal_hr_only(rate_hz: u16) -> bool {
    rate_hz == 1344
}

/// The `(INTx_CFG, INTx_THS, INTx_DURATION)` register triple of a generator.
fn generator_regs(generator: u8) -> Option<(u8, u8, u8)> {
    match generator {
        1 => Some((REG_INT1_CFG, REG_INT1_THS, REG_INT1_DUR)),
        2 => Some((REG_INT2_CFG, REG_INT2_THS, REG_INT2_DUR)),
        _ => None,
    }
}

/// The `(register, mask, tracked pin)` triple that routes a source.
///
/// The tracked pin is 1 for INT1 and 2 for INT2 — it selects the bitmap the
/// driver uses to remember which sources are enabled.
fn route_interrupt(source: u16, pin: u8) -> Option<(u8, u8, u8)> {
    // 0 = INT1 only, 1 = either pin, 2 = INT2 only.
    let (kind, mask) = match source {
        SOURCE_DATA_READY => (0, 0x10),
        SOURCE_AUX_DATA_READY => (0, 0x08),
        SOURCE_WATERMARK => (0, 0x04),
        SOURCE_OVERRUN => (0, 0x02),
        SOURCE_IA1 => (1, 0x40),
        SOURCE_IA2 => (1, 0x20),
        SOURCE_CLICK => (1, 0x80),
        SOURCE_ACTIVITY => (2, 0x08),
        SOURCE_BOOT => (2, 0x10),
        _ => return None,
    };
    match kind {
        0 => Some((REG_CTRL_REG3, mask, 1)),
        2 => Some((REG_CTRL_REG6, mask, 2)),
        _ => match pin {
            1 => Some((REG_CTRL_REG3, mask, 1)),
            2 => Some((REG_CTRL_REG6, mask, 2)),
            _ => None,
        },
    }
}

/// Build the `CLICK_CFG` byte from the single- and double-click axis masks.
fn click_cfg(single: u8, double: u8) -> u8 {
    (single & 0x01)
        | ((single & 0x02) << 1)
        | ((single & 0x04) << 2)
        | ((double & 0x01) << 1)
        | ((double & 0x02) << 2)
        | ((double & 0x04) << 3)
}

/// Round a positive value up to a whole millisecond.
fn ceil_f32(value: f32) -> u32 {
    if value <= 0.0 {
        return 0;
    }
    libm::ceilf(value) as u32
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x18;

    /// The six-byte output burst: X = 1000 counts, Y = 0, Z = -500 counts
    /// (12-bit high-resolution, 1 mg/LSB).
    const BURST: [u8; 6] = [0x80, 0x3E, 0x00, 0x00, 0xC0, 0xE0];

    /// The Initialization Sequence: the identity read and the eight writes.
    fn init_transactions() -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![WHO_AM_I_VALUE]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG3, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG6, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_TEMP_CFG, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, CTRL_REG4_DEFAULT]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, CTRL_REG1_DEFAULT]),
        ]
    }

    /// Build a Full driver on a mock preloaded with `extra` transactions.
    fn new_full(extra: &[I2cTransaction]) -> Lis3dhFull<I2cMock, NoopDelay> {
        let mut transactions = init_transactions();
        transactions.extend_from_slice(extra);
        let i2c = I2cMock::new(&transactions);
        Lis3dhFull::new(Connection::new(i2c), ADDR, NoopDelay::new()).expect("new")
    }

    #[test]
    fn init_sequence_and_read() {
        let mut accel = new_full(&[I2cTransaction::write_read(
            ADDR,
            vec![REG_OUT_X_L | 0x80],
            BURST.to_vec(),
        )]);
        // The burst read sets the auto-increment bit of the sub-address.
        let (x, y, z) = accel.read().unwrap();
        assert!((x - 1.0).abs() < 1e-6, "x: {}", x);
        assert!((y - 0.0).abs() < 1e-6, "y: {}", y);
        assert!((z - (-0.5)).abs() < 1e-6, "z: {}", z);
        accel.inner.conn.release().done();
    }

    #[test]
    fn read_raw_counts() {
        let mut accel = new_full(&[I2cTransaction::write_read(
            ADDR,
            vec![REG_OUT_X_L | 0x80],
            BURST.to_vec(),
        )]);
        let (rx, ry, rz) = accel.read_raw().unwrap();
        assert_eq!(rx, 1000);
        assert_eq!(ry, 0);
        assert_eq!(rz, -500);
        accel.inner.conn.release().done();
    }

    #[test]
    fn range_mode_and_rate() {
        let mut accel = new_full(&[
            // set_range(4): read CTRL_REG4 = 0x88, write (0x88 & !0x30) | 0x10.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG4], vec![0x88]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x98]),
            // set_data_rate(50): read CTRL_REG1 = 0x57, write (0x57 & 0x0F) | 0x40.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x57]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x47]),
            // set_mode(MODE_LOW_POWER): read CTRL_REG1 and CTRL_REG4, write both.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x47]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG4], vec![0x98]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x4F]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x90]),
            // set_mode(MODE_HIGH_RESOLUTION): LPen = 0, HR = 1.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x4F]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG4], vec![0x90]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x47]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x98]),
            // set_axes(true, false, true): read CTRL_REG1, write 0x47 | 0x05.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x47]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x45]),
            // power_down(): read CTRL_REG1, write 0x45 & 0x0F.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x45]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x05]),
            // set_data_rate(100): read CTRL_REG1, write (0x05 & 0x0F) | 0x50.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x05]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x55]),
        ]);
        accel.set_range(4).unwrap();
        assert_eq!(accel.inner.range_g, 4);
        accel.set_data_rate(50).unwrap();
        assert_eq!(accel.inner.rate_hz, 50);
        accel.set_mode(MODE_LOW_POWER).unwrap();
        assert_eq!(accel.inner.mode, MODE_LOW_POWER);
        accel.set_mode(MODE_HIGH_RESOLUTION).unwrap();
        assert_eq!(accel.inner.mode, MODE_HIGH_RESOLUTION);
        accel.set_axes(true, false, true).unwrap();
        accel.power_down().unwrap();
        assert_eq!(accel.inner.rate_hz, 0);
        accel.set_data_rate(100).unwrap();
        accel.inner.conn.release().done();
    }

    #[test]
    fn illegal_rate_and_mode_are_ignored() {
        let mut accel = new_full(&[
            // set_mode(MODE_LOW_POWER) at 100 Hz is legal: LPen = 1, HR = 0.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x57]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG4], vec![0x88]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x5F]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x80]),
            // set_data_rate(1620) in low-power mode is legal.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x5F]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x8F]),
        ]);
        // 1620 Hz is low-power only and the chip is in high-resolution mode.
        accel.set_data_rate(1620).unwrap();
        assert_eq!(accel.inner.rate_hz, 100);
        accel.set_mode(MODE_LOW_POWER).unwrap();
        assert_eq!(accel.inner.mode, MODE_LOW_POWER);
        // 1344 Hz does not exist in low-power mode.
        accel.set_data_rate(1344).unwrap();
        assert_eq!(accel.inner.rate_hz, 100);
        // 1620 Hz is legal in low-power mode.
        accel.set_data_rate(1620).unwrap();
        assert_eq!(accel.inner.rate_hz, 1620);
        // Leaving low-power mode is illegal at 1620 Hz.
        accel.set_mode(MODE_NORMAL).unwrap();
        assert_eq!(accel.inner.mode, MODE_LOW_POWER);
        // An unknown rate and an unknown mode change nothing.
        accel.set_data_rate(123).unwrap();
        accel.set_mode(3).unwrap();
        accel.inner.conn.release().done();
    }

    #[test]
    fn thresholds_and_durations() {
        let mut accel = new_full(&[
            // set_motion_interrupt(1, 0x3F, 0.5, 30, true):
            //   INT1_CFG = 0x80 | 0x3F, INT1_THS = round(0.5 * 1000 / 16) = 31,
            //   INT1_DUR = round(30 * 100 / 1000) = 3.
            I2cTransaction::write(ADDR, vec![REG_INT1_CFG, 0xBF]),
            I2cTransaction::write(ADDR, vec![REG_INT1_THS, 31]),
            I2cTransaction::write(ADDR, vec![REG_INT1_DUR, 3]),
            // set_free_fall(1, 0.35, 60): INT1_CFG = 0x95, THS = round(21.875) = 22,
            //   DUR = round(60 * 100 / 1000) = 6.
            I2cTransaction::write(ADDR, vec![REG_INT1_CFG, 0x95]),
            I2cTransaction::write(ADDR, vec![REG_INT1_THS, 22]),
            I2cTransaction::write(ADDR, vec![REG_INT1_DUR, 6]),
            // set_motion_interrupt(2, 0x01, 0.5, 10): generator 2's registers.
            I2cTransaction::write(ADDR, vec![REG_INT2_CFG, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_INT2_THS, 31]),
            I2cTransaction::write(ADDR, vec![REG_INT2_DUR, 1]),
            // set_activity(0.5, 100): ACT_THS = 31,
            //   ACT_DUR = round((100 * 100 / 1000 - 1) / 8) = round(1.125) = 1.
            I2cTransaction::write(ADDR, vec![REG_ACT_THS, 31]),
            I2cTransaction::write(ADDR, vec![REG_ACT_DUR, 1]),
            // set_click(0, 0x04, 0.8, 20, 40, 200, true):
            //   CLICK_CFG = 0x20, CLICK_THS = 50 | 0x80, TIME_LIMIT = 2,
            //   TIME_LATENCY = 4, TIME_WINDOW = 20.
            I2cTransaction::write(ADDR, vec![REG_CLICK_CFG, 0x20]),
            I2cTransaction::write(ADDR, vec![REG_CLICK_THS, 0xB2]),
            I2cTransaction::write(ADDR, vec![REG_TIME_LIMIT, 2]),
            I2cTransaction::write(ADDR, vec![REG_TIME_LATENCY, 4]),
            I2cTransaction::write(ADDR, vec![REG_TIME_WINDOW, 20]),
            // set_click(0x07, 0x01, 0.5, 10, 20, 50, false): CLICK_CFG = 0x17.
            I2cTransaction::write(ADDR, vec![REG_CLICK_CFG, 0x17]),
            I2cTransaction::write(ADDR, vec![REG_CLICK_THS, 31]),
            I2cTransaction::write(ADDR, vec![REG_TIME_LIMIT, 1]),
            I2cTransaction::write(ADDR, vec![REG_TIME_LATENCY, 2]),
            I2cTransaction::write(ADDR, vec![REG_TIME_WINDOW, 5]),
        ]);
        accel
            .set_motion_interrupt(1, 0x3F, 0.5, 30.0, true)
            .unwrap();
        accel.set_free_fall(1, 0.35, 60.0).unwrap();
        accel.set_motion_interrupt(2, 0x01, 0.5, 10.0, false).unwrap();
        accel.set_activity(0.5, 100.0).unwrap();
        accel
            .set_click(0, 0x04, 0.8, 20.0, 40.0, 200.0, true)
            .unwrap();
        accel.set_click(0x07, 0x01, 0.5, 10.0, 20.0, 50.0, false).unwrap();
        accel.inner.conn.release().done();
    }

    #[test]
    fn high_pass_and_fifo() {
        let mut accel = new_full(&[
            // set_high_pass(1, 2, true, true, false, true) -> 0x6D.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG2, 0x6D]),
            // reset_high_pass(): read REFERENCE.
            I2cTransaction::write_read(ADDR, vec![REG_REFERENCE], vec![0x42]),
            // set_reference(0x55).
            I2cTransaction::write(ADDR, vec![REG_REFERENCE, 0x55]),
            // set_fifo(FIFO_FIFO, 16, false): FIFO_CTRL = 0x50, then CTRL_REG5
            // gets FIFO_EN.
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, 0x50]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x40]),
            // fifo_level(): read FIFO_SRC = 0x15 -> 21.
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_SRC], vec![0x15]),
            // read_fifo(None): the level is read again, then one 6-byte burst
            // per sample set (one sample in the FIFO).
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_SRC], vec![0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_OUT_X_L | 0x80], BURST.to_vec()),
            // read_fifo_status(): read FIFO_SRC = 0x85.
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_SRC], vec![0x85]),
            // set_fifo(FIFO_BYPASS, 0, false): FIFO_CTRL = 0x00, CTRL_REG5
            // loses FIFO_EN.
            I2cTransaction::write(ADDR, vec![REG_FIFO_CTRL, 0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x40]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x00]),
        ]);
        accel
            .set_high_pass(1, 2, true, true, false, true)
            .unwrap();
        assert_eq!(accel.reset_high_pass().unwrap(), 0x42);
        accel.set_reference(0x55).unwrap();
        accel.set_fifo(FIFO_FIFO, 16, false).unwrap();
        assert_eq!(accel.fifo_level().unwrap(), 21);
        let samples = accel.read_fifo(None).unwrap();
        assert_eq!(samples.len(), 1);
        assert!((samples[0].0 - 1.0).abs() < 1e-6);
        let (watermark, overrun, empty, level) = accel.read_fifo_status().unwrap();
        assert!(watermark && !overrun && !empty);
        assert_eq!(level, 5);
        accel.set_fifo(FIFO_BYPASS, 0, false).unwrap();
        accel.inner.conn.release().done();
    }

    #[test]
    fn temperature_and_aux_adc() {
        let mut accel = new_full(&[
            // read_temperature_delta(): read TEMP_CFG = 0x00, write 0xC0, then
            // read OUT_ADC3_H = 0xFB -> -5 °C.
            I2cTransaction::write_read(ADDR, vec![REG_TEMP_CFG], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_TEMP_CFG, 0xC0]),
            I2cTransaction::write_read(ADDR, vec![REG_OUT_ADC3_H], vec![0xFB]),
            // enable_adc(true): read TEMP_CFG = 0xC0, write 0xC0 | 0x80.
            I2cTransaction::write_read(ADDR, vec![REG_TEMP_CFG], vec![0xC0]),
            I2cTransaction::write(ADDR, vec![REG_TEMP_CFG, 0xC0]),
            // read_adc(1): OUT_ADC1 = 0x0100 -> 4 counts -> 1.200 + 4 * 0.4/512.
            I2cTransaction::write_read(ADDR, vec![REG_OUT_ADC1_L | 0x80], vec![0x00, 0x01]),
            // read_adc(3) with TEMP_EN = 1 is not available.
            I2cTransaction::write_read(ADDR, vec![REG_TEMP_CFG], vec![0xC0]),
        ]);
        assert_eq!(accel.read_temperature_delta().unwrap(), -5);
        accel.enable_adc(true).unwrap();
        let v = accel.read_adc(1).unwrap();
        assert!((v - (1.200 + 4.0 * 0.4 / 512.0)).abs() < 1e-6, "v: {}", v);
        assert!(accel.read_adc(3).unwrap().is_nan());
        accel.inner.conn.release().done();
    }

    #[test]
    fn interrupt_routing_and_polling() {
        let mut accel = new_full(&[
            // enable_interrupt(SOURCE_DATA_READY, 1): CTRL_REG3 |= 0x10.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG3], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG3, 0x10]),
            // enable_interrupt(SOURCE_IA1, 2): CTRL_REG6 |= 0x40.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG6], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG6, 0x40]),
            // enable_interrupt(SOURCE_CLICK, 1): CTRL_REG3 |= 0x80.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG3], vec![0x10]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG3, 0x90]),
            // poll_interrupt(): STATUS_REG.ZYXDA is set, INT1_SRC.IA is clear
            // and CLICK_SRC.IA is set. The generator flags live in their own
            // source register, no matter which pin they are routed to.
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x08]),
            I2cTransaction::write_read(ADDR, vec![REG_INT1_SRC], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_CLICK_SRC], vec![0x40]),
            // disable_interrupt(SOURCE_DATA_READY, 1): CTRL_REG3 &= !0x10.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG3], vec![0x90]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG3, 0x80]),
        ]);
        accel.enable_interrupt(SOURCE_DATA_READY, 1).unwrap();
        accel.enable_interrupt(SOURCE_IA1, 2).unwrap();
        accel.enable_interrupt(SOURCE_CLICK, 1).unwrap();
        let status = accel.poll_interrupt().unwrap();
        assert_eq!(status, SOURCE_DATA_READY | SOURCE_CLICK);
        accel.disable_interrupt(SOURCE_DATA_READY, 1).unwrap();
        accel.inner.conn.release().done();
    }

    #[test]
    fn orientation_latch_and_polarity() {
        let mut accel = new_full(&[
            // set_orientation(1, true, true): INT1_CFG = 0xC0 | 0x0F, then
            // CTRL_REG5 |= D4D_INT1 (0x04).
            I2cTransaction::write(ADDR, vec![REG_INT1_CFG, 0xCF]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x04]),
            // set_latch(1, true): CTRL_REG5 |= 0x08.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x04]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x0C]),
            // set_latch(2, true): CTRL_REG5 |= 0x02.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x0C]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x0E]),
            // set_interrupt_polarity(true): CTRL_REG6 |= 0x02.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG6], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG6, 0x02]),
            // read_interrupt_source(1): read INT1_SRC.
            I2cTransaction::write_read(ADDR, vec![REG_INT1_SRC], vec![0x40]),
            // read_click_source(): read CLICK_SRC.
            I2cTransaction::write_read(ADDR, vec![REG_CLICK_SRC], vec![0x40]),
            // set_sdo_pullup(false): CTRL_REG0 = 0x90.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG0, 0x90]),
            // set_sdo_pullup(true): CTRL_REG0 = 0x10.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG0, 0x10]),
        ]);
        accel.set_orientation(1, true, true).unwrap();
        accel.set_latch(1, true).unwrap();
        accel.set_latch(2, true).unwrap();
        accel.set_interrupt_polarity(true).unwrap();
        assert_eq!(accel.read_interrupt_source(1).unwrap(), 0x40);
        assert_eq!(accel.read_click_source().unwrap(), 0x40);
        accel.set_sdo_pullup(false).unwrap();
        accel.set_sdo_pullup(true).unwrap();
        accel.inner.conn.release().done();
    }

    #[test]
    fn status_and_new_data() {
        let mut accel = new_full(&[
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x08]),
            I2cTransaction::write_read(ADDR, vec![REG_STATUS], vec![0x88]),
        ]);
        assert!(accel.new_data_available().unwrap());
        assert_eq!(accel.read_status().unwrap(), 0x88);
        accel.inner.conn.release().done();
    }

    #[test]
    fn reboot_reruns_the_init_sequence() {
        let mut transactions = init_transactions();
        transactions.extend_from_slice(&[
            // reboot(): read CTRL_REG5, write it with the BOOT bit, then the
            // Initialization Sequence runs again.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG5], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG5, 0x80]),
        ]);
        transactions.extend(init_transactions());
        let i2c = I2cMock::new(&transactions);
        let mut accel = Lis3dhFull::new(Connection::new(i2c), ADDR, NoopDelay::new()).expect("new");
        accel.reboot().unwrap();
        assert_eq!(accel.inner.range_g, DEFAULT_RANGE_G);
        assert_eq!(accel.inner.mode, DEFAULT_MODE);
        assert_eq!(accel.inner.rate_hz, DEFAULT_RATE_HZ);
        accel.inner.conn.release().done();
    }

    #[test]
    fn self_test_detects_the_step() {
        // The five samples before the self-test are enabled and the five
        // after it show a step of 25 / -37 / 63 counts.
        let mut transactions = init_transactions();
        transactions.extend_from_slice(&[
            // The saved configuration.
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG1], vec![0x57]),
            I2cTransaction::write_read(ADDR, vec![REG_CTRL_REG4], vec![0x88]),
            // Self-test configuration with ST = 00.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x80]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x47]),
        ]);
        for _ in 0..5 {
            transactions.push(I2cTransaction::write_read(
                ADDR,
                vec![REG_OUT_X_L | 0x80],
                vec![0x40, 0x06, 0x80, 0xF3, 0x20, 0x03],
            ));
        }
        transactions.extend_from_slice(&[
            // Self-test configuration with ST = 01 (self-test 0).
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x82]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x47]),
        ]);
        for _ in 0..5 {
            transactions.push(I2cTransaction::write_read(
                ADDR,
                vec![REG_OUT_X_L | 0x80],
                vec![0x80, 0x0C, 0x60, 0xEA, 0xC0, 0x12],
            ));
        }
        transactions.extend_from_slice(&[
            // The previous configuration is restored.
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG1, 0x57]),
            I2cTransaction::write(ADDR, vec![REG_CTRL_REG4, 0x88]),
        ]);
        let i2c = I2cMock::new(&transactions);
        let mut accel = Lis3dhFull::new(Connection::new(i2c), ADDR, NoopDelay::new()).expect("new");
        assert!(accel.self_test(1).unwrap());
        assert_eq!(accel.inner.mode, DEFAULT_MODE);
        accel.inner.conn.release().done();
    }
}
