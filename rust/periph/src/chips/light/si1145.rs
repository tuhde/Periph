//! Si1145 — proximity, UV index and ambient light sensor (Silicon Labs).
//!
//! Communicates over I²C at the fixed address `0x60` (older silicon: `0x5A`). The chip drives one
//! IR LED (Si1146: two, Si1147: three), measures the reflected light on up to three proximity
//! channels (PS1–PS3) and the visible/infrared ambient light, and reports the UV index × 100 in
//! `AUX_DATA` when the UV coefficients `UCOEF0..3` are set. Configuration is split between I²C
//! registers and an indirect parameter RAM (`PARAM_SET` with the value in `PARAM_WR`,
//! `PARAM_QUERY` into `PARAM_RD`); every command increments the counter in `RESPONSE`.
//!
//! Construction does no bus access. `init()` waits 25 ms, checks `PART_ID` (`0x45`/`0x46`/`0x47`),
//! writes `HW_KEY = 0x17`, `UCOEF0..3 = 7B 6B 01 00`, `CHLIST = 0xB1` (UV, ALS IR, ALS VIS, PS1),
//! `PSLED12_SELECT = 0x01`, `PS_LED21 = 0x03` (LED1 at level 3 of 15) and
//! `IRQ_ENABLE = ALS_IE | PS1_IE`. Proximity and ambient values are raw counts.
//!
//! `INT` is active low, driven only after [`Si1145Full::enable_interrupt`], and released when the
//! `IRQ_STATUS` bits are written back. The Rust driver exposes [`Si1145Full::poll_interrupt`]
//! (no callback subscription): the caller wires the pin into an ISR or polling loop and calls
//! `poll_interrupt`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register::{read_register, write_register};

const REG_PART_ID: u8 = 0x00;
const REG_SEQ_ID: u8 = 0x02;
const REG_INT_CFG: u8 = 0x03;
const REG_IRQ_ENABLE: u8 = 0x04;
const REG_HW_KEY: u8 = 0x07;
const REG_MEAS_RATE0: u8 = 0x08;
const REG_PS_RATE: u8 = 0x0A;
const REG_PS_LED21: u8 = 0x0F;
const REG_PS_LED3: u8 = 0x10;
const REG_UCOEF0: u8 = 0x13;
const REG_PARAM_WR: u8 = 0x17;
const REG_COMMAND: u8 = 0x18;
const REG_RESPONSE: u8 = 0x20;
const REG_IRQ_STATUS: u8 = 0x21;
const REG_DATA: u8 = 0x22;
const REG_PARAM_RD: u8 = 0x2E;

const HW_KEY: u8 = 0x17;

const CMD_NOP: u8 = 0x00;
const CMD_RESET: u8 = 0x01;
const CMD_BUSADDR: u8 = 0x02;
const CMD_PS_FORCE: u8 = 0x05;
const CMD_ALS_FORCE: u8 = 0x06;
const CMD_PSALS_FORCE: u8 = 0x07;
const CMD_PSALS_PAUSE: u8 = 0x0B;
const CMD_PS_AUTO: u8 = 0x0D;
const CMD_ALS_AUTO: u8 = 0x0E;
const CMD_PSALS_AUTO: u8 = 0x0F;
const CMD_GET_CAL: u8 = 0x12;
const CMD_PARAM_QUERY: u8 = 0x80;
const CMD_PARAM_SET: u8 = 0xA0;

const PARAM_I2C_ADDR: u8 = 0x00;
const PARAM_CHLIST: u8 = 0x01;
const PARAM_PSLED12_SELECT: u8 = 0x02;
const PARAM_PSLED3_SELECT: u8 = 0x03;
const PARAM_PS_ENCODING: u8 = 0x05;
const PARAM_ALS_ENCODING: u8 = 0x06;
const PARAM_PS1_ADCMUX: u8 = 0x07;
const PARAM_PS_ADC_COUNTER: u8 = 0x0A;
const PARAM_PS_ADC_GAIN: u8 = 0x0B;
const PARAM_PS_ADC_MISC: u8 = 0x0C;
const PARAM_AUX_ADCMUX: u8 = 0x0F;
const PARAM_ALS_VIS_ADC_COUNTER: u8 = 0x10;
const PARAM_ALS_VIS_ADC_GAIN: u8 = 0x11;
const PARAM_ALS_VIS_ADC_MISC: u8 = 0x12;
const PARAM_ALS_IR_ADC_COUNTER: u8 = 0x1D;
const PARAM_ALS_IR_ADC_GAIN: u8 = 0x1E;
const PARAM_ALS_IR_ADC_MISC: u8 = 0x1F;

const SOURCE_MASK: u8 = 0x3D;
/// Command handshake polls (1 ms each).
const CMD_POLLS: u32 = 50;
/// Measurement polls (1 ms each); a gain-7 ALS measurement takes ~200 ms.
const MEASURE_POLLS: u32 = 1000;

/// `CHLIST.EN_PS1`.
pub const CH_PS1: u8 = 0x01;
/// `CHLIST.EN_PS2` (Si1146/47).
pub const CH_PS2: u8 = 0x02;
/// `CHLIST.EN_PS3` (Si1147).
pub const CH_PS3: u8 = 0x04;
/// `CHLIST.EN_ALS_VIS`.
pub const CH_ALS_VIS: u8 = 0x10;
/// `CHLIST.EN_ALS_IR`.
pub const CH_ALS_IR: u8 = 0x20;
/// `CHLIST.EN_AUX`.
pub const CH_AUX: u8 = 0x40;
/// `CHLIST.EN_UV`.
pub const CH_UV: u8 = 0x80;

/// Interrupt source: ALS/UV measurement complete.
pub const SOURCE_ALS: u8 = 0x01;
/// Interrupt source: PS1 complete.
pub const SOURCE_PS1: u8 = 0x04;
/// Interrupt source: PS2 complete (Si1146/47).
pub const SOURCE_PS2: u8 = 0x08;
/// Interrupt source: PS3 complete (Si1147).
pub const SOURCE_PS3: u8 = 0x10;
/// Interrupt source: command complete.
pub const SOURCE_CMD: u8 = 0x20;

/// `AUX_ADCMUX`: die temperature.
pub const AUX_TEMPERATURE: u8 = 0x65;
/// `AUX_ADCMUX`: supply voltage.
pub const AUX_VDD: u8 = 0x75;

/// Default UV coefficients: `AUX_DATA` = UV index × 100.
pub const UCOEF_DEFAULT: [u8; 4] = [0x7B, 0x6B, 0x01, 0x00];

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// `PART_ID` is not `0x45`, `0x46` or `0x47`.
    InvalidChip(u8),
    /// The chip reported an error in `RESPONSE` (`0x80` invalid setting, `0x88`–`0x8E` ADC
    /// overflow).
    Command(u8),
    /// A command or measurement did not complete in time.
    Timeout,
    /// The requested channel is not enabled in `CHLIST`.
    ChannelDisabled,
    /// An argument is outside the supported range (or not available on this part).
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// One forced PS + ALS measurement.
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct Reading {
    /// Raw PS1 count (rises as an object approaches).
    pub proximity: u16,
    /// UV index (`AUX_DATA / 100`).
    pub uv_index: f32,
    /// Raw visible ambient count.
    pub visible: u16,
    /// Raw infrared ambient count.
    pub infrared: u16,
}

/// Raw data registers `0x22`–`0x2D`.
#[derive(Debug, PartialEq, Eq, Clone, Copy, Default)]
pub struct Raw {
    /// `ALS_VIS_DATA`.
    pub visible: u16,
    /// `ALS_IR_DATA`.
    pub infrared: u16,
    /// `PS1_DATA`.
    pub ps1: u16,
    /// `PS2_DATA` (Si1146/47).
    pub ps2: u16,
    /// `PS3_DATA` (Si1147).
    pub ps3: u16,
    /// `AUX_DATA` (UV index × 100 while UV is enabled).
    pub aux: u16,
}

/// Decoded `RESPONSE`.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct Response {
    /// 4-bit command counter (0 on error).
    pub counter: u8,
    /// Error code: 0 = none, `0x80` invalid setting, `0x88`–`0x8E` ADC overflow.
    pub error: u8,
}

/// Si1145 minimal driver — one call returns proximity, UV index and ambient light.
pub struct Si1145Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    part_id: u8,
    seq_id: u8,
    chlist: u8,
    irq_enable: u8,
}

impl<I2C: I2c> Si1145Minimal<I2C> {
    /// Create a new `Si1145Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x60`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, part_id: 0x45, seq_id: 0, chlist: 0, irq_enable: 0 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Verify the chip, unlock it and configure UV, ALS and PS1.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        delay.delay_ms(25);
        let part = self.read_reg(REG_PART_ID)?;
        if !(0x45..=0x47).contains(&part) {
            return Err(Error::InvalidChip(part));
        }
        self.part_id = part;
        self.seq_id = self.read_reg(REG_SEQ_ID)?;
        self.apply_defaults(delay)
    }

    /// Run one forced PS + ALS measurement (`PSALS_FORCE`).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Reading, Error<I2C::Error>> {
        self.measure(delay, true, true)?;
        let v = self.read_data()?;
        Ok(Reading { proximity: v[2], uv_index: v[5] as f32 / 100.0, visible: v[0], infrared: v[1] })
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        read_register(&mut self.i2c, self.addr, reg as u32, 1, &mut b)?;
        Ok(b[0])
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        write_register(&mut self.i2c, self.addr, reg as u32, 1, &[value])?;
        Ok(())
    }

    fn apply_defaults<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.write_reg(REG_HW_KEY, HW_KEY)?;
        write_register(&mut self.i2c, self.addr, REG_UCOEF0 as u32, 1, &UCOEF_DEFAULT)?;
        let chlist = CH_UV | CH_ALS_IR | CH_ALS_VIS | CH_PS1;
        self.param_set(delay, PARAM_CHLIST, chlist)?;
        self.chlist = chlist;
        self.param_set(delay, PARAM_PSLED12_SELECT, 0x01)?;
        self.write_reg(REG_PS_LED21, 0x03)?;
        self.irq_enable = SOURCE_ALS | SOURCE_PS1;
        self.write_reg(REG_IRQ_ENABLE, self.irq_enable)
    }

    fn counter(&mut self) -> Result<u8, Error<I2C::Error>> {
        let mut r = self.read_reg(REG_RESPONSE)?;
        if r & 0x80 != 0 {
            self.write_reg(REG_COMMAND, CMD_NOP)?;
            r = self.read_reg(REG_RESPONSE)?;
        }
        Ok(r & 0x0F)
    }

    fn command<D: DelayNs>(&mut self, delay: &mut D, cmd: u8) -> Result<(), Error<I2C::Error>> {
        let before = self.counter()?;
        self.write_reg(REG_COMMAND, cmd)?;
        for _ in 0..CMD_POLLS {
            let r = self.read_reg(REG_RESPONSE)?;
            if r & 0x80 != 0 {
                self.write_reg(REG_COMMAND, CMD_NOP)?;
                return Err(Error::Command(r));
            }
            if r & 0x0F != before {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Error::Timeout)
    }

    fn param_set<D: DelayNs>(&mut self, delay: &mut D, offset: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        self.write_reg(REG_PARAM_WR, value)?;
        self.command(delay, CMD_PARAM_SET | (offset & 0x1F))
    }

    fn param_query<D: DelayNs>(&mut self, delay: &mut D, offset: u8) -> Result<u8, Error<I2C::Error>> {
        self.command(delay, CMD_PARAM_QUERY | (offset & 0x1F))?;
        self.read_reg(REG_PARAM_RD)
    }

    fn clear_irq(&mut self) -> Result<u8, Error<I2C::Error>> {
        let status = self.read_reg(REG_IRQ_STATUS)?;
        if status != 0 {
            self.write_reg(REG_IRQ_STATUS, status)?;
        }
        Ok(status)
    }

    fn measure<D: DelayNs>(&mut self, delay: &mut D, proximity: bool, als: bool) -> Result<(), Error<I2C::Error>> {
        let cmd = match (proximity, als) {
            (true, true) => CMD_PSALS_FORCE,
            (true, false) => CMD_PS_FORCE,
            _ => CMD_ALS_FORCE,
        };
        self.clear_irq()?;
        self.counter()?;
        self.write_reg(REG_COMMAND, cmd)?;
        let mut wait = 0u8;
        if als && self.chlist & 0xF0 != 0 {
            wait |= SOURCE_ALS;
        }
        if proximity {
            wait |= (self.chlist & 0x07) << 2;
        }
        wait &= self.irq_enable;
        if wait == 0 {
            delay.delay_ms(100);
            return Ok(());
        }
        let mut seen = 0u8;
        for _ in 0..MEASURE_POLLS {
            seen |= self.clear_irq()?;
            if seen & wait == wait {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Error::Timeout)
    }

    fn read_data(&mut self) -> Result<[u16; 6], Error<I2C::Error>> {
        let mut b = [0u8; 12];
        read_register(&mut self.i2c, self.addr, REG_DATA as u32, 1, &mut b)?;
        let mut v = [0u16; 6];
        for (i, x) in v.iter_mut().enumerate() {
            *x = b[2 * i] as u16 | ((b[2 * i + 1] as u16) << 8);
        }
        Ok(v)
    }
}

/// Si1145 full driver — per-channel reads, channel list, LEDs, photodiodes, gains, ranges,
/// alignment, UV coefficients, calibration, autonomous mode, I²C address, reset and interrupts.
pub struct Si1145Full<I2C> {
    inner: Si1145Minimal<I2C>,
}

impl<I2C: I2c> Si1145Full<I2C> {
    /// Create a new `Si1145Full`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x60`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Si1145Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    fn led_count(&self) -> u8 {
        self.inner.part_id - 0x44
    }

    /// Verify the chip, unlock it and configure UV, ALS and PS1.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Run one forced PS + ALS measurement (`PSALS_FORCE`).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Reading, Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Read `PART_ID`, `REV_ID` and `SEQ_ID` (`SEQ_ID = 0x01` swaps the `MEAS_RATE` bytes).
    pub fn read_id(&mut self) -> Result<(u8, u8, u8), Error<I2C::Error>> {
        let mut b = [0u8; 3];
        read_register(&mut self.inner.i2c, self.inner.addr, REG_PART_ID as u32, 1, &mut b)?;
        Ok((b[0], b[1], b[2]))
    }

    /// Forced PS measurement (`PS_FORCE`) and one proximity channel (1–3, must be enabled).
    pub fn read_proximity<D: DelayNs>(&mut self, delay: &mut D, channel: u8) -> Result<u16, Error<I2C::Error>> {
        if channel < 1 || channel > self.led_count() {
            return Err(Error::InvalidArgument);
        }
        if self.inner.chlist & (1 << (channel - 1)) == 0 {
            return Err(Error::ChannelDisabled);
        }
        self.inner.measure(delay, true, false)?;
        Ok(self.inner.read_data()?[1 + channel as usize])
    }

    /// Forced ALS measurement and the UV index (`AUX_DATA / 100`).
    pub fn read_uv_index<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.als_value(delay, CH_UV, 5)? as f32 / 100.0)
    }

    /// Forced ALS measurement and the raw visible count.
    pub fn read_visible<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.als_value(delay, CH_ALS_VIS, 0)
    }

    /// Forced ALS measurement and the raw infrared count.
    pub fn read_infrared<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.als_value(delay, CH_ALS_IR, 1)
    }

    fn als_value<D: DelayNs>(&mut self, delay: &mut D, ch: u8, index: usize) -> Result<u16, Error<I2C::Error>> {
        if self.inner.chlist & ch == 0 {
            return Err(Error::ChannelDisabled);
        }
        self.inner.measure(delay, false, true)?;
        Ok(self.inner.read_data()?[index])
    }

    /// Read the latest data registers without measuring.
    pub fn read_raw(&mut self) -> Result<Raw, Error<I2C::Error>> {
        let v = self.inner.read_data()?;
        Ok(Raw { visible: v[0], infrared: v[1], ps1: v[2], ps2: v[3], ps3: v[4], aux: v[5] })
    }

    fn read_aux<D: DelayNs>(&mut self, delay: &mut D, mux: u8) -> Result<u16, Error<I2C::Error>> {
        let saved = self.inner.chlist;
        self.inner.param_set(delay, PARAM_AUX_ADCMUX, mux)?;
        self.inner.param_set(delay, PARAM_CHLIST, CH_AUX)?;
        self.inner.chlist = CH_AUX;
        let result = self.inner.measure(delay, false, true).and_then(|_| self.inner.read_data());
        let restored = self.inner.param_set(delay, PARAM_CHLIST, saved);
        self.inner.chlist = saved;
        let v = result?;
        restored?;
        Ok(v[5])
    }

    /// Die temperature on the auxiliary channel (`AUX_ADCMUX 0x65`, relative); `CHLIST` restored.
    pub fn read_temperature_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.read_aux(delay, AUX_TEMPERATURE)
    }

    /// Supply voltage on the auxiliary channel (`AUX_ADCMUX 0x75`); `CHLIST` restored.
    pub fn read_vdd_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.read_aux(delay, AUX_VDD)
    }

    /// Select the measured channels (`CHLIST`). `uv` and `aux` share `AUX_DATA` and exclude each
    /// other; `ps2`/`ps3` need the Si1146/47.
    #[allow(clippy::too_many_arguments)]
    pub fn set_channels<D: DelayNs>(
        &mut self, delay: &mut D, uv: bool, visible: bool, infrared: bool, ps1: bool, ps2: bool, ps3: bool,
        aux: bool,
    ) -> Result<(), Error<I2C::Error>> {
        if (uv && aux) || (ps2 && self.led_count() < 2) || (ps3 && self.led_count() < 3) {
            return Err(Error::InvalidArgument);
        }
        let mask = if uv { CH_UV } else { 0 }
            | if aux { CH_AUX } else { 0 }
            | if infrared { CH_ALS_IR } else { 0 }
            | if visible { CH_ALS_VIS } else { 0 }
            | if ps3 { CH_PS3 } else { 0 }
            | if ps2 { CH_PS2 } else { 0 }
            | if ps1 { CH_PS1 } else { 0 };
        self.inner.param_set(delay, PARAM_CHLIST, mask)?;
        self.inner.chlist = mask;
        Ok(())
    }

    /// Set an LED drive current (`PS_LED21` / `PS_LED3`): `led` 1–3 (Si1145: 1), `level` 0 (off)
    /// … 15 (maximum, ~359 mA).
    pub fn set_led_current(&mut self, led: u8, level: u8) -> Result<(), Error<I2C::Error>> {
        if led < 1 || led > self.led_count() || level > 15 {
            return Err(Error::InvalidArgument);
        }
        if led == 3 {
            return self.inner.write_reg(REG_PS_LED3, level);
        }
        let cur = self.inner.read_reg(REG_PS_LED21)?;
        let v = if led == 1 { (cur & 0xF0) | level } else { (cur & 0x0F) | (level << 4) };
        self.inner.write_reg(REG_PS_LED21, v)
    }

    /// Select the LEDs a PS channel (1–3) drives (`PSLED12_SELECT` / `PSLED3_SELECT`); `leds` bit
    /// 0 = LED1, bit 1 = LED2, bit 2 = LED3, only the part's LEDs.
    pub fn set_ps_led<D: DelayNs>(&mut self, delay: &mut D, channel: u8, leds: u8) -> Result<(), Error<I2C::Error>> {
        if !(1..=3).contains(&channel) || leds > (1u8 << self.led_count()) - 1 {
            return Err(Error::InvalidArgument);
        }
        if channel == 3 {
            return self.inner.param_set(delay, PARAM_PSLED3_SELECT, leds);
        }
        let cur = self.inner.param_query(delay, PARAM_PSLED12_SELECT)?;
        let v = if channel == 1 { (cur & 0x70) | leds } else { (cur & 0x07) | (leds << 4) };
        self.inner.param_set(delay, PARAM_PSLED12_SELECT, v)
    }

    /// Select the photodiode of a PS channel (`PSn_ADCMUX`): large IR (`0x03`) or small (`0x00`).
    pub fn set_ps_photodiode<D: DelayNs>(&mut self, delay: &mut D, channel: u8, large: bool) -> Result<(), Error<I2C::Error>> {
        if !(1..=3).contains(&channel) {
            return Err(Error::InvalidArgument);
        }
        self.inner.param_set(delay, PARAM_PS1_ADCMUX + channel - 1, if large { 0x03 } else { 0x00 })
    }

    /// Set the ADC gains 0–7 (integration time × 2^n) and the recommended recovery counts (one's
    /// complement of the gain). Do not combine a high PS gain with a high LED current.
    pub fn set_gain<D: DelayNs>(&mut self, delay: &mut D, visible: u8, infrared: u8, proximity: u8) -> Result<(), Error<I2C::Error>> {
        if visible > 7 || infrared > 7 || proximity > 7 {
            return Err(Error::InvalidArgument);
        }
        for (gain, g_off, c_off) in [
            (visible, PARAM_ALS_VIS_ADC_GAIN, PARAM_ALS_VIS_ADC_COUNTER),
            (infrared, PARAM_ALS_IR_ADC_GAIN, PARAM_ALS_IR_ADC_COUNTER),
            (proximity, PARAM_PS_ADC_GAIN, PARAM_PS_ADC_COUNTER),
        ] {
            self.inner.param_set(delay, g_off, gain)?;
            self.inner.param_set(delay, c_off, ((!gain) & 0x07) << 4)?;
        }
        Ok(())
    }

    /// Select the high signal range (sensitivity ÷ 14.5): `VIS_RANGE`, `IR_RANGE`, `PS_RANGE`
    /// (`PS_ADC_MODE` stays 1).
    pub fn set_high_range<D: DelayNs>(&mut self, delay: &mut D, visible: bool, infrared: bool, proximity: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.param_set(delay, PARAM_ALS_VIS_ADC_MISC, if visible { 0x20 } else { 0 })?;
        self.inner.param_set(delay, PARAM_ALS_IR_ADC_MISC, if infrared { 0x20 } else { 0 })?;
        self.inner.param_set(delay, PARAM_PS_ADC_MISC, if proximity { 0x24 } else { 0x04 })
    }

    /// Report the 16 LSBs instead of the 16 MSBs of the ADC result (`ALS_ENCODING` /
    /// `PS_ENCODING` align bits).
    pub fn set_alignment<D: DelayNs>(
        &mut self, delay: &mut D, visible: bool, infrared: bool, ps1: bool, ps2: bool, ps3: bool,
    ) -> Result<(), Error<I2C::Error>> {
        let als = if infrared { 0x20 } else { 0 } | if visible { 0x10 } else { 0 };
        let ps = if ps3 { 0x40 } else { 0 } | if ps2 { 0x20 } else { 0 } | if ps1 { 0x10 } else { 0 };
        self.inner.param_set(delay, PARAM_ALS_ENCODING, als)?;
        self.inner.param_set(delay, PARAM_PS_ENCODING, ps)
    }

    /// Write `UCOEF0..3` ([`UCOEF_DEFAULT`] gives the UV index × 100).
    pub fn set_uv_coefficients(&mut self, ucoef: [u8; 4]) -> Result<(), Error<I2C::Error>> {
        write_register(&mut self.inner.i2c, self.inner.addr, REG_UCOEF0 as u32, 1, &ucoef)?;
        Ok(())
    }

    /// Factory calibration (`GET_CAL`, 12 bytes); overwrites the data registers.
    pub fn read_calibration<D: DelayNs>(&mut self, delay: &mut D) -> Result<[u8; 12], Error<I2C::Error>> {
        self.inner.command(delay, CMD_GET_CAL)?;
        let mut b = [0u8; 12];
        read_register(&mut self.inner.i2c, self.inner.addr, REG_DATA as u32, 1, &mut b)?;
        Ok(b)
    }

    /// Start autonomous measurements: `MEAS_RATE = round(interval / 31.25 µs)` (bytes swapped when
    /// `SEQ_ID = 0x01`), then `PS_AUTO` / `ALS_AUTO` / `PSALS_AUTO`.
    pub fn start_autonomous<D: DelayNs>(&mut self, delay: &mut D, interval_s: f32, proximity: bool, als: bool) -> Result<(), Error<I2C::Error>> {
        let ticks = interval_s / 31.25e-6 + 0.5;
        if !(1.0..65536.0).contains(&ticks) || !(proximity || als) {
            return Err(Error::InvalidArgument);
        }
        let rate = ticks as u16;
        if self.inner.seq_id == 0x01 {
            self.inner.write_reg(REG_PS_RATE, (rate & 0xFF) as u8)?;
            self.inner.write_reg(REG_MEAS_RATE0, (rate >> 8) as u8)?;
        } else {
            write_register(&mut self.inner.i2c, self.inner.addr, REG_MEAS_RATE0 as u32, 1, &rate.to_le_bytes())?;
        }
        let cmd = match (proximity, als) {
            (true, true) => CMD_PSALS_AUTO,
            (true, false) => CMD_PS_AUTO,
            _ => CMD_ALS_AUTO,
        };
        self.inner.command(delay, cmd)
    }

    /// Pause the autonomous loop (`PSALS_PAUSE`).
    pub fn pause<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(delay, CMD_PSALS_PAUSE)
    }

    /// Start a forced measurement without waiting (`PS_FORCE` / `ALS_FORCE` / `PSALS_FORCE`).
    pub fn force<D: DelayNs>(&mut self, delay: &mut D, proximity: bool, als: bool) -> Result<(), Error<I2C::Error>> {
        let cmd = match (proximity, als) {
            (true, true) => CMD_PSALS_FORCE,
            (true, false) => CMD_PS_FORCE,
            (false, true) => CMD_ALS_FORCE,
            _ => return Err(Error::InvalidArgument),
        };
        self.inner.command(delay, cmd)
    }

    /// Decode `RESPONSE`.
    pub fn read_response(&mut self) -> Result<Response, Error<I2C::Error>> {
        let r = self.inner.read_reg(REG_RESPONSE)?;
        Ok(if r & 0x80 != 0 { Response { counter: 0, error: r } } else { Response { counter: r & 0x0F, error: 0 } })
    }

    /// Change the I²C address (`I2C_ADDR`, then `BUSADDR`; volatile, 0x08–0x77). The chip answers
    /// on the new address at once; the driver keeps using it afterwards.
    pub fn set_i2c_address<D: DelayNs>(&mut self, delay: &mut D, address: u8) -> Result<(), Error<I2C::Error>> {
        if !(0x08..=0x77).contains(&address) {
            return Err(Error::InvalidArgument);
        }
        self.inner.param_set(delay, PARAM_I2C_ADDR, address)?;
        self.inner.write_reg(REG_COMMAND, CMD_BUSADDR)?;
        self.inner.addr = address;
        Ok(())
    }

    /// Software reset, 10 ms wait, `HW_KEY` and the Minimal defaults again.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.write_reg(REG_COMMAND, CMD_RESET)?;
        delay.delay_ms(10);
        self.inner.apply_defaults(delay)
    }

    /// Drive `INT` for sources (`INT_CFG.INT_OE` and `IRQ_ENABLE`, `SOURCE_*` mask).
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.irq_enable = (self.inner.irq_enable | source) & SOURCE_MASK;
        self.inner.write_reg(REG_INT_CFG, 0x01)?;
        self.inner.write_reg(REG_IRQ_ENABLE, self.inner.irq_enable)
    }

    /// Clear sources from `IRQ_ENABLE`. Forced reads only wait for enabled sources (none: a fixed
    /// 100 ms wait).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.irq_enable &= !source & SOURCE_MASK;
        self.inner.write_reg(REG_IRQ_ENABLE, self.inner.irq_enable)
    }

    /// Read `IRQ_STATUS` and write it back to clear it (releases `INT`); returns the pending
    /// `SOURCE_*` mask among the enabled sources, 0 = none.
    pub fn poll_interrupt(&mut self) -> Result<u8, Error<I2C::Error>> {
        Ok(self.inner.clear_irq()? & self.inner.irq_enable)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::{ErrorKind, ErrorType, Operation};
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    /// Simulated Si1145 command engine: RESPONSE counter, parameter RAM, forced measurements.
    struct Sim {
        regs: [u8; 0x40],
        params: [u8; 32],
        ps: [u16; 3],
        commands: Vec<u8>,
        ignore: bool,
        fail_param: Option<u8>,
    }

    impl Sim {
        fn new(part: u8, seq: u8) -> Self {
            let mut s = Sim { regs: [0; 0x40], params: [0; 32], ps: [1500, 1600, 1700], commands: Vec::new(), ignore: false, fail_param: None };
            s.regs[0] = part;
            s.regs[2] = seq;
            s.params[0x00] = 0x60;
            s.params[0x02] = 0x21;
            s.params[0x0C] = 0x04;
            s.params[0x0F] = 0x65;
            s
        }
        fn last(&self) -> u8 {
            *self.commands.last().unwrap()
        }
        fn put16(&mut self, r: usize, v: u16) {
            self.regs[r] = v as u8;
            self.regs[r + 1] = (v >> 8) as u8;
        }
        fn measure(&mut self, p: bool, a: bool) {
            let ch = self.params[1];
            let mut st = 0u8;
            if a && ch & 0xF0 != 0 {
                if ch & 0x10 != 0 { self.put16(0x22, 260); }
                if ch & 0x20 != 0 { self.put16(0x24, 300); }
                if ch & 0x80 != 0 {
                    self.put16(0x2C, 345);
                } else if ch & 0x40 != 0 {
                    let v = if self.params[0x0F] == 0x65 { 0x1111 } else { 0x2222 };
                    self.put16(0x2C, v);
                }
                st |= 0x01;
            }
            if p {
                for i in 0..3 {
                    if ch & (1 << i) != 0 {
                        self.put16(0x26 + 2 * i, self.ps[i]);
                        st |= 0x04 << i;
                    }
                }
            }
            self.regs[0x21] |= st & self.regs[0x04];
        }
        fn command(&mut self, cmd: u8) {
            self.commands.push(cmd);
            if self.ignore { return; }
            let resp = self.regs[0x20];
            if cmd == 0x00 { self.regs[0x20] = 0; return; }
            if cmd == 0x01 { self.regs[0x20] = 0; self.regs[0x07] = 0; return; }
            if resp & 0x80 != 0 { return; }
            if cmd & 0xE0 == 0xA0 {
                let off = cmd & 0x1F;
                if Some(off) == self.fail_param { self.regs[0x20] = 0x80; return; }
                self.params[off as usize] = self.regs[0x17];
                self.regs[0x2E] = self.regs[0x17];
            } else if cmd & 0xE0 == 0x80 {
                self.regs[0x2E] = self.params[(cmd & 0x1F) as usize];
            } else if (0x05..=0x07).contains(&cmd) {
                self.measure(cmd != 0x06, cmd != 0x05);
            } else if cmd == 0x12 {
                for i in 0..12 { self.regs[0x22 + i] = 0x10 + i as u8; }
            } else if ![0x02, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F].contains(&cmd) {
                self.regs[0x20] = 0x80;
                return;
            }
            self.regs[0x20] = (resp + 1) & 0x0F;
        }
    }

    impl ErrorType for Sim {
        type Error = ErrorKind;
    }

    impl I2c for Sim {
        fn transaction(&mut self, _addr: u8, ops: &mut [Operation<'_>]) -> Result<(), Self::Error> {
            let mut reg = 0usize;
            for op in ops.iter_mut() {
                match op {
                    Operation::Write(data) => {
                        reg = data[0] as usize;
                        for (i, &v) in data[1..].iter().enumerate() {
                            let r = reg + i;
                            if r == 0x18 { self.command(v); } else if r == 0x21 { self.regs[0x21] &= !v; } else { self.regs[r] = v; }
                        }
                    }
                    Operation::Read(buf) => {
                        for (i, b) in buf.iter_mut().enumerate() { *b = self.regs[(reg + i) & 0x3F]; }
                    }
                }
            }
            Ok(())
        }
    }

    fn sensor(part: u8, seq: u8) -> Si1145Full<Sim> {
        let mut s = Si1145Full::new(Sim::new(part, seq), 0x60);
        s.init(&mut NoopDelay::new()).unwrap();
        s
    }

    #[test]
    fn init_sequence_on_the_bus() {
        let a = 0x60;
        let expected = [
            T::write_read(a, vec![0x00], vec![0x45]),
            T::write_read(a, vec![0x02], vec![0x08]),
            T::write(a, vec![0x07, 0x17]),
            T::write(a, vec![0x13, 0x7B, 0x6B, 0x01, 0x00]),
            T::write(a, vec![0x17, 0xB1]),
            T::write_read(a, vec![0x20], vec![0x00]),
            T::write(a, vec![0x18, 0xA1]),
            T::write_read(a, vec![0x20], vec![0x01]),
            T::write(a, vec![0x17, 0x01]),
            T::write_read(a, vec![0x20], vec![0x01]),
            T::write(a, vec![0x18, 0xA2]),
            T::write_read(a, vec![0x20], vec![0x02]),
            T::write(a, vec![0x0F, 0x03]),
            T::write(a, vec![0x04, 0x05]),
        ];
        let mut i2c = I2cMock::new(&expected);
        let mut s = Si1145Minimal::new(i2c.clone(), a);
        s.init(&mut NoopDelay::new()).unwrap();
        i2c.done();
    }

    #[test]
    fn init_rejects_wrong_part() {
        let mut s = Si1145Minimal::new(Sim::new(0x32, 0x08), 0x60);
        assert_eq!(s.init(&mut NoopDelay::new()), Err(Error::InvalidChip(0x32)));
    }

    #[test]
    fn read_and_single_channels() {
        let mut d = NoopDelay::new();
        let mut s = sensor(0x45, 0x08);
        let r = s.read(&mut d).unwrap();
        assert_eq!((r.proximity, r.visible, r.infrared), (1500, 260, 300));
        assert!((r.uv_index - 3.45).abs() < 1e-4);
        assert_eq!(s.inner.i2c.regs[0x21], 0);
        assert_eq!(s.read_proximity(&mut d, 1).unwrap(), 1500);
        assert_eq!(s.inner.i2c.last(), 0x05);
        assert_eq!(s.read_proximity(&mut d, 2), Err(Error::InvalidArgument));
        assert!((s.read_uv_index(&mut d).unwrap() - 3.45).abs() < 1e-4);
        assert_eq!(s.read_visible(&mut d).unwrap(), 260);
        assert_eq!(s.read_infrared(&mut d).unwrap(), 300);
        assert_eq!(s.read_raw().unwrap(), Raw { visible: 260, infrared: 300, ps1: 1500, ps2: 0, ps3: 0, aux: 345 });
        assert_eq!(s.read_temperature_raw(&mut d).unwrap(), 0x1111);
        assert_eq!(s.inner.i2c.params[1], 0xB1);
        assert_eq!(s.read_vdd_raw(&mut d).unwrap(), 0x2222);
        s.set_channels(&mut d, false, true, false, true, false, false, false).unwrap();
        assert_eq!(s.inner.i2c.params[1], 0x11);
        assert_eq!(s.read_uv_index(&mut d), Err(Error::ChannelDisabled));
        assert_eq!(s.set_channels(&mut d, true, false, false, false, false, false, true), Err(Error::InvalidArgument));
        assert_eq!(s.set_channels(&mut d, false, false, false, true, true, false, false), Err(Error::InvalidArgument));
    }

    #[test]
    fn leds_photodiode_gain_range() {
        let mut d = NoopDelay::new();
        let mut s = sensor(0x45, 0x08);
        s.set_led_current(1, 15).unwrap();
        assert_eq!(s.inner.i2c.regs[0x0F], 0x0F);
        assert_eq!(s.set_led_current(2, 1), Err(Error::InvalidArgument));
        assert_eq!(s.set_led_current(1, 16), Err(Error::InvalidArgument));
        s.set_ps_led(&mut d, 1, 0).unwrap();
        assert_eq!(s.inner.i2c.params[2], 0);
        assert_eq!(s.set_ps_led(&mut d, 1, 2), Err(Error::InvalidArgument));
        s.set_ps_photodiode(&mut d, 1, false).unwrap();
        assert_eq!(s.inner.i2c.params[7], 0);
        s.set_gain(&mut d, 3, 2, 1).unwrap();
        let p = s.inner.i2c.params;
        assert_eq!((p[0x11], p[0x10], p[0x1E], p[0x1D], p[0x0B], p[0x0A]), (3, 0x40, 2, 0x50, 1, 0x60));
        assert_eq!(s.set_gain(&mut d, 8, 0, 0), Err(Error::InvalidArgument));
        s.set_high_range(&mut d, true, true, true).unwrap();
        let p = s.inner.i2c.params;
        assert_eq!((p[0x12], p[0x1F], p[0x0C]), (0x20, 0x20, 0x24));
        s.set_alignment(&mut d, true, false, true, false, false).unwrap();
        assert_eq!((s.inner.i2c.params[6], s.inner.i2c.params[5]), (0x10, 0x10));
        s.set_uv_coefficients([1, 2, 3, 4]).unwrap();
        assert_eq!(&s.inner.i2c.regs[0x13..0x17], &[1, 2, 3, 4]);
        let cal = s.read_calibration(&mut d).unwrap();
        assert_eq!((cal[0], cal[11]), (0x10, 0x1B));

        let mut s47 = sensor(0x47, 0x08);
        s47.set_led_current(2, 5).unwrap();
        s47.set_led_current(3, 9).unwrap();
        assert_eq!((s47.inner.i2c.regs[0x0F], s47.inner.i2c.regs[0x10]), (0x53, 0x09));
        s47.set_ps_led(&mut d, 2, 2).unwrap();
        s47.set_ps_led(&mut d, 3, 4).unwrap();
        assert_eq!((s47.inner.i2c.params[2], s47.inner.i2c.params[3]), (0x21, 0x04));
        s47.set_channels(&mut d, false, false, false, true, true, true, false).unwrap();
        assert_eq!(s47.read_proximity(&mut d, 3).unwrap(), 1700);
    }

    #[test]
    fn autonomous_force_response() {
        let mut d = NoopDelay::new();
        let mut s = sensor(0x45, 0x08);
        s.start_autonomous(&mut d, 0.1, true, true).unwrap();
        assert_eq!((s.inner.i2c.regs[0x08], s.inner.i2c.regs[0x09], s.inner.i2c.last()), (0x80, 0x0C, 0x0F));
        s.start_autonomous(&mut d, 0.01, false, true).unwrap();
        assert_eq!(s.inner.i2c.last(), 0x0E);
        assert_eq!(s.start_autonomous(&mut d, 3.0, true, true), Err(Error::InvalidArgument));
        s.pause(&mut d).unwrap();
        assert_eq!(s.inner.i2c.last(), 0x0B);
        s.force(&mut d, true, false).unwrap();
        assert_eq!(s.inner.i2c.last(), 0x05);
        let mut sw = sensor(0x45, 0x01);
        sw.start_autonomous(&mut d, 0.1, true, true).unwrap();
        assert_eq!((sw.inner.i2c.regs[0x0A], sw.inner.i2c.regs[0x08]), (0x80, 0x0C));
        assert_eq!(s.read_response().unwrap().error, 0);
        s.inner.i2c.fail_param = Some(0x0B);
        assert_eq!(s.set_gain(&mut d, 0, 0, 1), Err(Error::Command(0x80)));
        assert_eq!((s.inner.i2c.last(), s.inner.i2c.regs[0x20]), (0x00, 0));
        s.inner.i2c.fail_param = None;
        s.inner.i2c.regs[0x20] = 0x8C;
        assert_eq!(s.read_response().unwrap(), Response { counter: 0, error: 0x8C });
        s.set_gain(&mut d, 0, 0, 0).unwrap();
        assert_eq!(s.inner.i2c.regs[0x20] & 0x80, 0);
    }

    #[test]
    fn interrupts_address_reset() {
        let mut d = NoopDelay::new();
        let mut s = sensor(0x45, 0x08);
        s.enable_interrupt(SOURCE_CMD).unwrap();
        assert_eq!((s.inner.i2c.regs[0x03], s.inner.i2c.regs[0x04]), (0x01, 0x25));
        s.disable_interrupt(SOURCE_CMD).unwrap();
        assert_eq!(s.inner.i2c.regs[0x04], 0x05);
        s.inner.i2c.regs[0x21] = 0x05;
        assert_eq!(s.poll_interrupt().unwrap(), 0x05);
        assert_eq!(s.poll_interrupt().unwrap(), 0);
        s.disable_interrupt(0x3D).unwrap();
        assert_eq!(s.read(&mut d).unwrap().proximity, 1500);
        assert_eq!(s.read_id().unwrap(), (0x45, 0x00, 0x08));
        s.set_i2c_address(&mut d, 0x30).unwrap();
        assert_eq!((s.inner.i2c.params[0], s.inner.i2c.last()), (0x30, 0x02));
        assert_eq!(s.set_i2c_address(&mut d, 0x80), Err(Error::InvalidArgument));
        s.inner.i2c.regs[0x0F] = 0x0F;
        s.reset(&mut d).unwrap();
        assert!(s.inner.i2c.commands.contains(&0x01));
        assert_eq!((s.inner.i2c.regs[0x07], s.inner.i2c.params[1], s.inner.i2c.regs[0x0F]), (0x17, 0xB1, 0x03));
    }

    #[test]
    fn timeouts() {
        let mut d = NoopDelay::new();
        let mut s = sensor(0x45, 0x08);
        s.inner.i2c.ignore = true;
        assert_eq!(s.read(&mut d), Err(Error::Timeout));
        assert_eq!(s.pause(&mut d), Err(Error::Timeout));
    }
}
