//! AD7706 3-channel, 16-bit sigma-delta ADC driver (Analog Devices).
//!
//! Three pseudo-differential channels (AIN1, AIN2, AIN3), each measured
//! relative to a single shared `COMMON` pin. Communicates over SPI (Mode 3,
//! up to 5 MHz, MSB first). Two-phase register access: write the
//! Communication Register (selects target register + read/write direction +
//! channel), then transfer the data bytes in a single CS-held transaction.
//! `DRDY` is polled over SPI by inspecting bit 7 of the Communication
//! Register, matching the datasheet's 3-wire microcontroller interface
//! technique (no dedicated `DRDY` GPIO required).
//!
//! Default configuration baked into Minimal:
//! - Channel 1 selected
//! - Gain 1, bipolar, unbuffered analog input
//! - 50 Hz output rate on a 2.4576/4.9152 MHz clock or 20 Hz on 1/2 MHz
//! - Self-calibration run once at construction
//!
//! Generic over [`SpiDevice`] from `embedded-hal` 1.0; chip drivers never
//! assert or deassert CS themselves — the `SpiDevice` implementation owns CS.

use embedded_hal::spi::{Operation, SpiDevice};

/// Master clock frequency 1 MHz.
pub const MCLK_1MHZ: u32 = 1_000_000;
/// Master clock frequency 2 MHz.
pub const MCLK_2MHZ: u32 = 2_000_000;
/// Master clock frequency 2.4576 MHz.
pub const MCLK_2_4576MHZ: u32 = 2_457_600;
/// Master clock frequency 4.9152 MHz.
pub const MCLK_4_9152MHZ: u32 = 4_915_200;

/// PGA gain setting 1.
pub const GAIN_1: u8 = 0;
/// PGA gain setting 2.
pub const GAIN_2: u8 = 1;
/// PGA gain setting 4.
pub const GAIN_4: u8 = 2;
/// PGA gain setting 8.
pub const GAIN_8: u8 = 3;
/// PGA gain setting 16.
pub const GAIN_16: u8 = 4;
/// PGA gain setting 32.
pub const GAIN_32: u8 = 5;
/// PGA gain setting 64.
pub const GAIN_64: u8 = 6;
/// PGA gain setting 128.
pub const GAIN_128: u8 = 7;

const REG_COMM: u8   = 0x00;
const REG_SETUP: u8  = 0x10;
const REG_CLOCK: u8  = 0x20;
const REG_DATA: u8   = 0x30;
const REG_OFFSET: u8 = 0x60;
const REG_GAIN: u8   = 0x70;

const RW_WRITE: u8 = 0x00;
const RW_READ: u8  = 0x08;

const CH1: u8 = 0x00;
const CH2: u8 = 0x01;
const CH3: u8 = 0x03;

const MODE_NORMAL:   u8 = 0x00;
const MODE_SELF_CAL: u8 = 0x40;
const MODE_ZERO_SYS: u8 = 0x80;
const MODE_FULL_SYS: u8 = 0xC0;

const GAIN_BITS: [u8; 8] = [0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38];
const GAIN_TO_IDX: [Option<u8>; 129] = {
    let mut t = [None; 129];
    t[1]   = Some(0);
    t[2]   = Some(1);
    t[4]   = Some(2);
    t[8]   = Some(3);
    t[16]  = Some(4);
    t[32]  = Some(5);
    t[64]  = Some(6);
    t[128] = Some(7);
    t
};

const BIPOLAR:    u8 = 0x00;
const UNIPOLAR:   u8 = 0x04;
const UNBUFFERED: u8 = 0x00;
const BUFFERED:   u8 = 0x02;
const FSYNC_RUN:  u8 = 0x00;
const STBY_RUN:   u8 = 0x00;
const STBY_SLEEP: u8 = 0x04;
const DRDY_MASK:  u8 = 0x80;

const FS_RATES_1MHZ:   [u16; 4] = [20, 25, 100, 200];
const FS_RATES_2_4MHZ: [u16; 4] = [50, 60, 250, 500];

fn channel_const(c: u8) -> Option<u8> {
    match c {
        1 => Some(CH1),
        2 => Some(CH2),
        3 => Some(CH3),
        _ => None,
    }
}

fn comm_byte(reg: u8, read: bool, channel: u8) -> u8 {
    reg | if read { RW_READ } else { RW_WRITE } | (channel & 0x03)
}

fn write_reg_channel<SPI: SpiDevice>(
    spi: &mut SPI,
    reg: u8,
    value: u32,
    channel: u8,
    n_bytes: u8,
) -> Result<(), SPI::Error> {
    let mut buf = [0u8; 4];
    buf[0] = comm_byte(reg, false, channel);
    for i in (0..n_bytes as usize).rev() {
        buf[1 + (n_bytes as usize - 1 - i)] = ((value >> (8 * i)) & 0xFF) as u8;
    }
    spi.write(&buf[..1 + n_bytes as usize])?;
    Ok(())
}

fn read_reg_channel<SPI: SpiDevice>(
    spi: &mut SPI,
    reg: u8,
    channel: u8,
    n_bytes: u8,
) -> Result<u32, SPI::Error> {
    let comm = [comm_byte(reg, true, channel)];
    let mut buf = [0u8; 3];
    spi.transaction(&mut [
        Operation::Write(&comm),
        Operation::Read(&mut buf[..n_bytes as usize]),
    ])?;
    let mut value: u32 = 0;
    for i in 0..n_bytes as usize {
        value = (value << 8) | (buf[i] as u32);
    }
    Ok(value)
}

fn wait_drdy<SPI: SpiDevice>(spi: &mut SPI) -> Result<(), SPI::Error> {
    loop {
        let comm = [comm_byte(REG_COMM, true, CH1)];
        let mut buf = [0u8; 1];
        spi.transaction(&mut [
            Operation::Write(&comm),
            Operation::Read(&mut buf),
        ])?;
        if buf[0] & DRDY_MASK == 0 {
            return Ok(());
        }
    }
}

fn configure_clock<SPI: SpiDevice>(
    spi: &mut SPI,
    mclk_hz: u32,
    output_rate_hz: u16,
) -> Result<(), SPI::Error> {
    let clk_bit = if mclk_hz >= MCLK_2_4576MHZ { 0x04 } else { 0x00 };
    let clkdiv_bit = if mclk_hz == MCLK_2MHZ || mclk_hz == MCLK_4_9152MHZ { 0x08 } else { 0x00 };
    let rates = if mclk_hz >= MCLK_2_4576MHZ { &FS_RATES_2_4MHZ } else { &FS_RATES_1MHZ };
    let fs_bits = rates.iter().position(|&r| r == output_rate_hz).unwrap_or(0);
    write_reg_channel(spi, REG_CLOCK, (clkdiv_bit | clk_bit | fs_bits as u8) as u32, CH1, 1)
}

fn code_to_voltage(code: u16, gain: u8, bipolar: bool, vref: f32) -> f32 {
    if bipolar {
        ((code as i32 - 32768) as f32 / 32768.0) * (vref / gain as f32)
    } else {
        (code as f32 / 65536.0) * (vref / gain as f32)
    }
}

/// AD7706 minimal driver — Channel 1 voltage read with sensible defaults.
pub struct AD7706Minimal<SPI: SpiDevice> {
    spi: SPI,
    vref: f32,
    mclk_hz: u32,
    gain: u8,
    bipolar: bool,
    buffered: bool,
}

impl<SPI: SpiDevice> AD7706Minimal<SPI> {
    /// Construct and initialise the AD7706.
    ///
    /// # Arguments
    /// * `spi` — fully configured `SpiDevice` bound to the chip's CS pin.
    /// * `vref` — reference voltage in V.
    /// * `mclk_hz` — master clock frequency in Hz. Must be one of
    ///   [`MCLK_1MHZ`], [`MCLK_2MHZ`], [`MCLK_2_4576MHZ`], or [`MCLK_4_9152MHZ`].
    pub fn new(spi: SPI, vref: f32, mclk_hz: u32) -> Result<Self, SPI::Error> {
        let mut s = Self {
            spi,
            vref,
            mclk_hz,
            gain: 1,
            bipolar: true,
            buffered: false,
        };
        s.init()?;
        Ok(s)
    }

    /// Re-run the initialisation sequence (Clock Register, self-calibrate Channel 1).
    pub fn init(&mut self) -> Result<(), SPI::Error> {
        let default_rate = if self.mclk_hz >= MCLK_2_4576MHZ {
            FS_RATES_2_4MHZ[0]
        } else {
            FS_RATES_1MHZ[0]
        };
        configure_clock(&mut self.spi, self.mclk_hz, default_rate)?;
        let setup = MODE_SELF_CAL | GAIN_BITS[0] | BIPOLAR | UNBUFFERED | FSYNC_RUN;
        write_reg_channel(&mut self.spi, REG_SETUP, setup as u32, CH1, 1)?;
        wait_drdy(&mut self.spi)?;
        Ok(())
    }

    /// Block until DRDY, then read and return the raw 16-bit Data Register code on Channel 1.
    pub fn read_raw(&mut self) -> Result<u16, SPI::Error> {
        wait_drdy(&mut self.spi)?;
        Ok(read_reg_channel(&mut self.spi, REG_DATA, CH1, 2)? as u16)
    }

    /// Block until DRDY, then return the input voltage on Channel 1 in V.
    pub fn read_voltage(&mut self) -> Result<f32, SPI::Error> {
        let code = self.read_raw()?;
        Ok(code_to_voltage(code, self.gain, self.bipolar, self.vref))
    }
}

/// AD7706 full driver — adds per-channel configuration, calibration, and power control.
///
/// Wraps [`AD7706Minimal`] via composition (Rust has no inheritance); one-line
/// forwards expose the inherited API. No register-level logic is duplicated.
pub struct AD7706Full<SPI: SpiDevice> {
    inner: AD7706Minimal<SPI>,
}

impl<SPI: SpiDevice> AD7706Full<SPI> {
    /// Construct and initialise the AD7706.
    pub fn new(spi: SPI, vref: f32, mclk_hz: u32) -> Result<Self, SPI::Error> {
        Ok(Self { inner: AD7706Minimal::new(spi, vref, mclk_hz)? })
    }

    /// Block until DRDY, then read the raw 16-bit code on Channel 1.
    pub fn read_raw(&mut self) -> Result<u16, SPI::Error> { self.inner.read_raw() }

    /// Block until DRDY, then return the input voltage on Channel 1 in V.
    pub fn read_voltage(&mut self) -> Result<f32, SPI::Error> { self.inner.read_voltage() }

    /// Write the Setup and Clock Registers for the given channel.
    ///
    /// Does not calibrate — call [`Self::self_calibrate`] (or one of the
    /// system-calibration methods) afterward.
    pub fn configure(&mut self, channel: u8, gain: u8, bipolar: bool, buffered: bool, output_rate_hz: u16) -> Result<(), SPI::Error> {
        let ch = match channel_const(channel) {
            Some(c) => c,
            None => return Ok(()),
        };
        let rates = if self.inner.mclk_hz >= MCLK_2_4576MHZ { &FS_RATES_2_4MHZ } else { &FS_RATES_1MHZ };
        if !rates.contains(&output_rate_hz) {
            return Ok(());
        }
        configure_clock(&mut self.inner.spi, self.inner.mclk_hz, output_rate_hz)?;
        let bu_bit = if bipolar { BIPOLAR } else { UNIPOLAR };
        let buf_bit = if buffered { BUFFERED } else { UNBUFFERED };
        let gain_idx = match GAIN_TO_IDX[gain as usize] {
            Some(i) => i,
            None => return Ok(()),
        };
        let setup = MODE_NORMAL | GAIN_BITS[gain_idx as usize] | bu_bit | buf_bit | FSYNC_RUN;
        write_reg_channel(&mut self.inner.spi, REG_SETUP, setup as u32, ch, 1)?;
        if channel == 1 {
            self.inner.gain = gain;
            self.inner.bipolar = bipolar;
            self.inner.buffered = buffered;
        }
        Ok(())
    }

    /// Block until DRDY, then read the raw 16-bit code for the channel.
    pub fn read_raw_channel(&mut self, channel: u8) -> Result<u16, SPI::Error> {
        let ch = match channel_const(channel) {
            Some(c) => c,
            None => return Ok(0),
        };
        wait_drdy(&mut self.inner.spi)?;
        Ok(read_reg_channel(&mut self.inner.spi, REG_DATA, ch, 2)? as u16)
    }

    /// Block until DRDY, then return the input voltage on the channel in V.
    pub fn read_voltage_channel(&mut self, channel: u8) -> Result<f32, SPI::Error> {
        let code = self.read_raw_channel(channel)?;
        Ok(code_to_voltage(code, self.inner.gain, self.inner.bipolar, self.inner.vref))
    }

    /// Run an internal self-calibration on the channel.
    pub fn self_calibrate(&mut self, channel: u8) -> Result<(), SPI::Error> {
        let ch = match channel_const(channel) {
            Some(c) => c,
            None => return Ok(()),
        };
        let setup = MODE_SELF_CAL | GAIN_BITS[GAIN_TO_IDX[self.inner.gain as usize].unwrap_or(0) as usize] | (if self.inner.bipolar { BIPOLAR } else { UNIPOLAR }) | (if self.inner.buffered { BUFFERED } else { UNBUFFERED }) | FSYNC_RUN;
        write_reg_channel(&mut self.inner.spi, REG_SETUP, setup as u32, ch, 1)?;
        wait_drdy(&mut self.inner.spi)?;
        Ok(())
    }

    /// Run a zero-scale system calibration. The caller must present the zero-scale voltage at AIN first.
    pub fn system_calibrate_zero(&mut self, channel: u8) -> Result<(), SPI::Error> {
        let ch = match channel_const(channel) {
            Some(c) => c,
            None => return Ok(()),
        };
        let setup = MODE_ZERO_SYS | GAIN_BITS[GAIN_TO_IDX[self.inner.gain as usize].unwrap_or(0) as usize] | (if self.inner.bipolar { BIPOLAR } else { UNIPOLAR }) | (if self.inner.buffered { BUFFERED } else { UNBUFFERED }) | FSYNC_RUN;
        write_reg_channel(&mut self.inner.spi, REG_SETUP, setup as u32, ch, 1)?;
        wait_drdy(&mut self.inner.spi)?;
        Ok(())
    }

    /// Run a full-scale system calibration. The caller must present the full-scale voltage at AIN first.
    pub fn system_calibrate_full(&mut self, channel: u8) -> Result<(), SPI::Error> {
        let ch = match channel_const(channel) {
            Some(c) => c,
            None => return Ok(()),
        };
        let setup = MODE_FULL_SYS | GAIN_BITS[GAIN_TO_IDX[self.inner.gain as usize].unwrap_or(0) as usize] | (if self.inner.bipolar { BIPOLAR } else { UNIPOLAR }) | (if self.inner.buffered { BUFFERED } else { UNBUFFERED }) | FSYNC_RUN;
        write_reg_channel(&mut self.inner.spi, REG_SETUP, setup as u32, ch, 1)?;
        wait_drdy(&mut self.inner.spi)?;
        Ok(())
    }

    /// Read the 24-bit Zero-Scale Calibration Register for the channel.
    pub fn get_offset_calibration(&mut self, channel: u8) -> Result<u32, SPI::Error> {
        let ch = match channel_const(channel) {
            Some(c) => c,
            None => return Ok(0),
        };
        read_reg_channel(&mut self.inner.spi, REG_OFFSET, ch, 3)
    }

    /// Write a 24-bit Zero-Scale Calibration Register for the channel.
    pub fn set_offset_calibration(&mut self, value: u32, channel: u8) -> Result<(), SPI::Error> {
        let ch = match channel_const(channel) {
            Some(c) => c,
            None => return Ok(()),
        };
        write_reg_channel(&mut self.inner.spi, REG_OFFSET, value & 0xFFFFFF, ch, 3)
    }

    /// Read the 24-bit Full-Scale Calibration Register for the channel.
    pub fn get_gain_calibration(&mut self, channel: u8) -> Result<u32, SPI::Error> {
        let ch = match channel_const(channel) {
            Some(c) => c,
            None => return Ok(0),
        };
        read_reg_channel(&mut self.inner.spi, REG_GAIN, ch, 3)
    }

    /// Write a 24-bit Full-Scale Calibration Register for the channel.
    pub fn set_gain_calibration(&mut self, value: u32, channel: u8) -> Result<(), SPI::Error> {
        let ch = match channel_const(channel) {
            Some(c) => c,
            None => return Ok(()),
        };
        write_reg_channel(&mut self.inner.spi, REG_GAIN, value & 0xFFFFFF, ch, 3)
    }

    /// Enter standby (~10 µA, registers retained).
    pub fn standby(&mut self) -> Result<(), SPI::Error> {
        let comm = [comm_byte(REG_COMM, false, CH1) | STBY_SLEEP];
        self.inner.spi.write(&comm)?;
        Ok(())
    }

    /// Exit standby and block until a fresh conversion is available.
    pub fn wakeup(&mut self) -> Result<(), SPI::Error> {
        let comm = [comm_byte(REG_COMM, false, CH1) | STBY_RUN];
        self.inner.spi.write(&comm)?;
        wait_drdy(&mut self.inner.spi)?;
        Ok(())
    }
}
