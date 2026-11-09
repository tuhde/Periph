//! TMC5160 -- Trinamic TMC5160 stepper controller/driver over SPI or single-wire UART.
//!
//! [`Tmc5160Minimal`] brings the chip up with the internal SixPoint ramp
//! generator in positioning mode (256 microsteps, StealthChop below
//! `TPWMTHRS = 500`, 0.5 A RMS run current, 200 full steps/s, 400 full
//! steps/s^2) and moves the motor with register writes -- no STEP pulses.
//! [`Tmc5160Full`] adds raw register access, the full ramp, reference switches,
//! chopper / StealthChop tuning, StallGuard2 / CoolStep, DcStep, the ABN
//! encoder and every status read-back.
//!
//! The transport is chosen by the constructor: [`Tmc5160Minimal::new_spi`]
//! takes an `embedded_hal` [`SpiDevice`] (Mode 3, the implementation owns CS)
//! and [`Tmc5160Minimal::new_uart`] an `embedded_io` `Read + Write` UART. Both
//! are wrapped in a private [`Tmc5160Bus`] that builds the 40-bit pipelined SPI
//! datagrams or the CRC-protected UART datagrams. Positions are in full steps,
//! velocities in full steps per second, accelerations in full steps per second
//! squared.
//!
//! ```rust,ignore
//! use periph::chips::motor::tmc5160::{Tmc5160Minimal, NoPin};
//!
//! let mut motor = Tmc5160Minimal::new_spi(spi, delay, 0.075, None::<NoPin>)?;
//! motor.set_current(1.5)?;
//! motor.enable()?;
//! motor.move_to(400.0)?;
//! motor.wait_until_reached(None)?;
//! ```

use embedded_hal::delay::DelayNs;
use embedded_hal::digital::{InputPin, OutputPin};
use embedded_hal::spi::{Operation, SpiDevice};
use embedded_io::{Read, Write};
use libm::{roundf, sqrtf};

const REG_GCONF: u8 = 0x00;
const REG_GSTAT: u8 = 0x01;
const REG_IFCNT: u8 = 0x02;
const REG_SLAVECONF: u8 = 0x03;
const REG_IOIN: u8 = 0x04;
const REG_OUTPUT: u8 = 0x04;
const REG_X_COMPARE: u8 = 0x05;
const REG_SHORT_CONF: u8 = 0x09;
const REG_DRV_CONF: u8 = 0x0A;
const REG_GLOBALSCALER: u8 = 0x0B;
const REG_OFFSET_READ: u8 = 0x0C;
const REG_IHOLD_IRUN: u8 = 0x10;
const REG_TPOWERDOWN: u8 = 0x11;
const REG_TSTEP: u8 = 0x12;
const REG_TPWMTHRS: u8 = 0x13;
const REG_TCOOLTHRS: u8 = 0x14;
const REG_THIGH: u8 = 0x15;
const REG_RAMPMODE: u8 = 0x20;
const REG_XACTUAL: u8 = 0x21;
const REG_VACTUAL: u8 = 0x22;
const REG_VSTART: u8 = 0x23;
const REG_A1: u8 = 0x24;
const REG_V1: u8 = 0x25;
const REG_AMAX: u8 = 0x26;
const REG_VMAX: u8 = 0x27;
const REG_DMAX: u8 = 0x28;
const REG_D1: u8 = 0x2A;
const REG_VSTOP: u8 = 0x2B;
const REG_TZEROWAIT: u8 = 0x2C;
const REG_XTARGET: u8 = 0x2D;
const REG_VDCMIN: u8 = 0x33;
const REG_SW_MODE: u8 = 0x34;
const REG_RAMP_STAT: u8 = 0x35;
const REG_XLATCH: u8 = 0x36;
const REG_ENCMODE: u8 = 0x38;
const REG_X_ENC: u8 = 0x39;
const REG_ENC_CONST: u8 = 0x3A;
const REG_ENC_STATUS: u8 = 0x3B;
const REG_ENC_LATCH: u8 = 0x3C;
const REG_ENC_DEVIATION: u8 = 0x3D;
const REG_MSLUT0: u8 = 0x60;
const REG_MSLUTSEL: u8 = 0x68;
const REG_MSLUTSTART: u8 = 0x69;
const REG_MSCNT: u8 = 0x6A;
const REG_MSCURACT: u8 = 0x6B;
const REG_CHOPCONF: u8 = 0x6C;
const REG_COOLCONF: u8 = 0x6D;
const REG_DCCTRL: u8 = 0x6E;
const REG_DRV_STATUS: u8 = 0x6F;
const REG_PWMCONF: u8 = 0x70;
const REG_PWM_SCALE: u8 = 0x71;
const REG_PWM_AUTO: u8 = 0x72;

const READABLE: [u8; 25] = [
    0x00, 0x01, 0x02, 0x04, 0x07, 0x0C, 0x12, 0x20, 0x21, 0x22, 0x2D, 0x34, 0x35, 0x36, 0x38, 0x39, 0x3B,
    0x3C, 0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x72, 0x73,
];
const WRITABLE: [u8; 47] = [
    0x00, 0x01, 0x03, 0x04, 0x05, 0x09, 0x0A, 0x0B, 0x10, 0x11, 0x13, 0x14, 0x15, 0x20, 0x21, 0x23, 0x24, 0x25,
    0x26, 0x27, 0x28, 0x2A, 0x2B, 0x2C, 0x2D, 0x33, 0x34, 0x35, 0x38, 0x39, 0x3A, 0x3B, 0x3D, 0x60, 0x61, 0x62,
    0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6C, 0x6D, 0x6E, 0x70,
];

const VERSION: u8 = 0x30;
const V_FS: f32 = 0.325;
const CHOPCONF_DEFAULT: u32 = 0x0001_00C3;
const PWMCONF_RESET: u32 = 0xC40C_001E;
const GCONF_EN_PWM_MODE: u32 = 1 << 2;
const GCONF_SHAFT: u32 = 1 << 4;
const GCONF_SMALL_HYSTERESIS: u32 = 1 << 14;
const GCONF_STOP_ENABLE: u32 = 1 << 15;
const GCONF_DIRECT_MODE: u32 = 1 << 16;
const SW_SG_STOP: u32 = 1 << 10;
const SW_SOFTSTOP: u32 = 1 << 11;

/// `DRV_STATUS.otpw` -- over-temperature pre-warning.
pub const TMC5160_FAULT_OVERTEMP_WARNING: u8 = 0x01;
/// `DRV_STATUS.ot` -- over-temperature shutdown.
pub const TMC5160_FAULT_OVERTEMP: u8 = 0x02;
/// `s2ga` / `s2gb` -- short to GND.
pub const TMC5160_FAULT_SHORT_GND: u8 = 0x04;
/// `s2vsa` / `s2vsb` -- short to supply.
pub const TMC5160_FAULT_SHORT_SUPPLY: u8 = 0x08;
/// `ola` / `olb` -- open load (informative only).
pub const TMC5160_FAULT_OPEN_LOAD: u8 = 0x10;

/// Placeholder for an unwired ENN / DIAG pin (`None::<NoPin>`).
pub struct NoPin;

impl embedded_hal::digital::ErrorType for NoPin {
    type Error = core::convert::Infallible;
}

impl OutputPin for NoPin {
    fn set_low(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
    fn set_high(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
}

impl InputPin for NoPin {
    fn is_high(&mut self) -> Result<bool, Self::Error> {
        Ok(false)
    }
    fn is_low(&mut self) -> Result<bool, Self::Error> {
        Ok(true)
    }
}

/// Errors from the TMC5160 drivers.
#[derive(Debug)]
pub enum Tmc5160Error<E> {
    /// The bus (SPI or UART) returned an error.
    Bus(E),
    /// The UART returned no data within its timeout.
    Timeout,
    /// The echoed bytes did not match what was sent (bus collision).
    EchoMismatch,
    /// A UART reply failed its CRC8 check.
    Crc,
    /// A UART reply had a wrong sync byte, address or register.
    Malformed,
    /// `IOIN.VERSION` was not 0x30.
    NotFound,
    /// The chip is strapped for STEP/DIR operation (`IOIN.SD_MODE = 1`).
    StepDirMode,
    /// An SPI write mirror, UART `IFCNT` or readback check failed.
    WriteNotAcknowledged,
    /// An argument was out of range or the register is not accessible.
    InvalidArgument,
    /// The operation is unsupported in the current configuration.
    Unsupported,
    /// The operation needs the UART bus.
    UartOnly,
    /// Direct mode (`GCONF.direct_mode`) is required.
    DirectModeRequired,
    /// `wait_until_reached` timed out.
    TimeoutReached,
    /// A pin returned an error, or a required pin is missing.
    Pin,
}

/// Transport layer: 40-bit pipelined SPI datagrams or CRC-protected UART datagrams.
pub trait Tmc5160Bus {
    /// Error type of the underlying bus.
    type Error;
    /// True for the UART transport.
    fn is_uart(&self) -> bool;
    /// Write a 32-bit register.
    fn write_reg<D: DelayNs>(&mut self, reg: u8, value: u32, delay: &mut D) -> Result<(), Tmc5160Error<Self::Error>>;
    /// Read a 32-bit register.
    fn read_reg<D: DelayNs>(&mut self, reg: u8, delay: &mut D) -> Result<u32, Tmc5160Error<Self::Error>>;
    /// Set the UART node address (no effect on SPI).
    fn set_address(&mut self, _address: u8) {}
}

/// SPI transport.
pub struct SpiBus<SPI> {
    spi: SPI,
}

impl<SPI: SpiDevice> SpiBus<SPI> {
    fn second(&mut self) -> Result<u32, Tmc5160Error<SPI::Error>> {
        let req = [REG_GCONF];
        let mut buf = [0u8; 4];
        self.spi.transaction(&mut [Operation::Write(&req), Operation::Read(&mut buf)]).map_err(Tmc5160Error::Bus)?;
        Ok(u32::from_be_bytes(buf))
    }
}

impl<SPI: SpiDevice> Tmc5160Bus for SpiBus<SPI> {
    type Error = SPI::Error;
    fn is_uart(&self) -> bool {
        false
    }
    fn write_reg<D: DelayNs>(&mut self, reg: u8, value: u32, _delay: &mut D) -> Result<(), Tmc5160Error<SPI::Error>> {
        let v = value.to_be_bytes();
        self.spi.write(&[reg | 0x80, v[0], v[1], v[2], v[3]]).map_err(Tmc5160Error::Bus)
    }
    fn read_reg<D: DelayNs>(&mut self, reg: u8, _delay: &mut D) -> Result<u32, Tmc5160Error<SPI::Error>> {
        let req = [reg & 0x7F];
        let mut buf = [0u8; 4];
        self.spi.transaction(&mut [Operation::Write(&req), Operation::Read(&mut buf)]).map_err(Tmc5160Error::Bus)?;
        self.second()
    }
}

/// CRC-8 (polynomial 0x07, init 0, LSB first) over a datagram body.
pub fn tmc5160_crc8(data: &[u8]) -> u8 {
    let mut crc = 0u8;
    for &b in data {
        let mut byte = b;
        for _ in 0..8 {
            if ((crc >> 7) ^ (byte & 1)) != 0 {
                crc = (crc << 1) ^ 0x07;
            } else {
                crc <<= 1;
            }
            byte >>= 1;
        }
    }
    crc
}

/// UART transport.
pub struct UartBus<U> {
    uart: U,
    address: u8,
    echo: bool,
}

impl<U: Read + Write> UartBus<U> {
    fn io_write(&mut self, data: &[u8]) -> Result<(), Tmc5160Error<U::Error>> {
        self.uart.write_all(data).map_err(Tmc5160Error::Bus)?;
        self.uart.flush().map_err(Tmc5160Error::Bus)
    }

    fn io_read(&mut self, buf: &mut [u8]) -> Result<(), Tmc5160Error<U::Error>> {
        let mut filled = 0;
        while filled < buf.len() {
            let n = self.uart.read(&mut buf[filled..]).map_err(Tmc5160Error::Bus)?;
            if n == 0 {
                return Err(Tmc5160Error::Timeout);
            }
            filled += n;
        }
        Ok(())
    }

    fn write_once(&mut self, reg: u8, value: u32) -> Result<(), Tmc5160Error<U::Error>> {
        let v = value.to_be_bytes();
        let mut dg = [0x05, self.address, reg | 0x80, v[0], v[1], v[2], v[3], 0];
        dg[7] = tmc5160_crc8(&dg[..7]);
        self.io_write(&dg)?;
        if self.echo {
            let mut echoed = [0u8; 8];
            self.io_read(&mut echoed)?;
            if echoed != dg {
                return Err(Tmc5160Error::EchoMismatch);
            }
        }
        Ok(())
    }

    fn read_once(&mut self, reg: u8) -> Result<u32, Tmc5160Error<U::Error>> {
        let mut req = [0x05, self.address, reg & 0x7F, 0];
        req[3] = tmc5160_crc8(&req[..3]);
        self.io_write(&req)?;
        if self.echo {
            let mut echoed = [0u8; 4];
            self.io_read(&mut echoed)?;
            if echoed != req {
                return Err(Tmc5160Error::EchoMismatch);
            }
        }
        let mut reply = [0u8; 8];
        self.io_read(&mut reply)?;
        if reply[0] != 0x05 || reply[1] != 0xFF || reply[2] != (reg & 0x7F) {
            return Err(Tmc5160Error::Malformed);
        }
        if tmc5160_crc8(&reply[..7]) != reply[7] {
            return Err(Tmc5160Error::Crc);
        }
        Ok(u32::from_be_bytes([reply[3], reply[4], reply[5], reply[6]]))
    }
}

impl<U: Read + Write> Tmc5160Bus for UartBus<U> {
    type Error = U::Error;
    fn is_uart(&self) -> bool {
        true
    }
    fn write_reg<D: DelayNs>(&mut self, reg: u8, value: u32, delay: &mut D) -> Result<(), Tmc5160Error<U::Error>> {
        if self.write_once(reg, value).is_err() {
            delay.delay_ms(2);
            self.write_once(reg, value)?;
        }
        Ok(())
    }
    fn read_reg<D: DelayNs>(&mut self, reg: u8, delay: &mut D) -> Result<u32, Tmc5160Error<U::Error>> {
        match self.read_once(reg) {
            Ok(v) => Ok(v),
            Err(_) => {
                delay.delay_ms(2);
                self.read_once(reg)
            }
        }
    }
    fn set_address(&mut self, address: u8) {
        self.address = address;
    }
}

/// Decoded `DRV_STATUS`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc5160Status {
    /// Standstill.
    pub stst: bool,
    /// StealthChop active.
    pub stealth: bool,
    /// Driver switched to full-step operation.
    pub fsactive: bool,
    /// Actual current scale 0..=31.
    pub cs_actual: u8,
    /// StallGuard2 result 0..=1023.
    pub sg_result: u16,
    /// Stall detected.
    pub stallguard: bool,
    /// Over-temperature pre-warning.
    pub otpw: bool,
    /// Over-temperature shutdown.
    pub ot: bool,
    /// Short to GND on either phase.
    pub short_gnd: bool,
    /// Short to supply on either phase.
    pub short_supply: bool,
    /// Open load on either phase.
    pub open_load: bool,
}

/// Decoded `RAMP_STAT`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc5160RampStatus {
    /// Left reference switch active.
    pub status_stop_l: bool,
    /// Right reference switch active.
    pub status_stop_r: bool,
    /// Left latch ready.
    pub status_latch_l: bool,
    /// Right latch ready.
    pub status_latch_r: bool,
    /// Active left stop condition.
    pub event_stop_l: bool,
    /// Active right stop condition.
    pub event_stop_r: bool,
    /// StallGuard2 stop event.
    pub event_stop_sg: bool,
    /// Target position reached event.
    pub event_pos_reached: bool,
    /// `VACTUAL = VMAX`.
    pub velocity_reached: bool,
    /// `XACTUAL = XTARGET`.
    pub position_reached: bool,
    /// `VACTUAL = 0`.
    pub vzero: bool,
    /// `TZEROWAIT` running.
    pub t_zerowait_active: bool,
    /// The ramp had to reverse direction.
    pub second_move: bool,
    /// StallGuard2 active from CoolStep / DcStep.
    pub status_sg: bool,
}

/// Decoded `GSTAT`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc5160GlobalStatus {
    /// IC was reset since the last clear.
    pub reset: bool,
    /// Driver shut down by over-temperature or short.
    pub drv_err: bool,
    /// Charge-pump undervoltage.
    pub uv_cp: bool,
}

/// Decoded `IOIN`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc5160Ioin {
    /// Raw 32-bit word.
    pub raw: u32,
    /// State of `REFL`.
    pub refl: bool,
    /// State of `REFR`.
    pub refr: bool,
    /// State of `ENCB`.
    pub encb: bool,
    /// State of `ENCA`.
    pub enca: bool,
    /// State of `DRV_ENN`.
    pub drv_enn: bool,
    /// State of `ENCN`.
    pub encn: bool,
    /// `SD_MODE` strap (must be false).
    pub sd_mode: bool,
    /// Single-wire comparator output.
    pub swcomp_in: bool,
    /// Silicon version (0x30).
    pub version: u8,
}

fn sign_extend(value: u32, bits: u32) -> i32 {
    let v = value & ((1u32 << bits) - 1);
    if v & (1 << (bits - 1)) != 0 {
        v as i32 - (1i32 << bits)
    } else {
        v as i32
    }
}

/// TMC5160 -- minimal interface: current, ramp-generator motion, faults.
pub struct Tmc5160Minimal<B, EN, D> {
    pub(crate) bus: B,
    pub(crate) en_pin: Option<EN>,
    pub(crate) delay: D,
    r_sense: f32,
    fclk_hz: f32,
    pub(crate) shadow: [u32; 128],
    pub(crate) seen: [bool; 128],
    pub(crate) usc: u32,
    toff: u8,
    rampmode: u8,
    speed: f32,
    speed_reg: u32,
    accel: f32,
    pub(crate) irun: u8,
    pub(crate) ihold: u8,
    pub(crate) ihold_delay: u8,
    pub(crate) senddelay: u8,
    last_move: (f32, f32, f32),
    writes: u8,
}

impl<SPI: SpiDevice, EN: OutputPin, D: DelayNs> Tmc5160Minimal<SpiBus<SPI>, EN, D> {
    /// Construct the driver on SPI (Mode 3), probe the chip and apply the default configuration.
    ///
    /// `r_sense_ohm` is the external sense resistor (0.075 ohm on common boards). With an
    /// `en_pin` the power stage stays off until [`enable`](Self::enable).
    pub fn new_spi(spi: SPI, delay: D, r_sense_ohm: f32, en_pin: Option<EN>) -> Result<Self, Tmc5160Error<SPI::Error>> {
        Self::with_options(SpiBus { spi }, delay, r_sense_ohm, en_pin, 12_000_000, false)
    }
}

impl<U: Read + Write, EN: OutputPin, D: DelayNs> Tmc5160Minimal<UartBus<U>, EN, D> {
    /// Construct the driver on the single-wire UART, probe the chip and apply the default configuration.
    ///
    /// `address` is the node address 0..=253, `echo` is true when TX and RX share the wire,
    /// `multi_node` writes `SLAVECONF.SENDDELAY = 2` first.
    #[allow(clippy::too_many_arguments)]
    pub fn new_uart(
        uart: U,
        delay: D,
        r_sense_ohm: f32,
        en_pin: Option<EN>,
        address: u8,
        echo: bool,
        multi_node: bool,
    ) -> Result<Self, Tmc5160Error<U::Error>> {
        if address > 253 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        Self::with_options(UartBus { uart, address, echo }, delay, r_sense_ohm, en_pin, 12_000_000, multi_node)
    }
}

impl<B: Tmc5160Bus, EN: OutputPin, D: DelayNs> Tmc5160Minimal<B, EN, D> {
    pub(crate) fn with_options(
        bus: B,
        delay: D,
        r_sense_ohm: f32,
        en_pin: Option<EN>,
        fclk_hz: u32,
        multi_node: bool,
    ) -> Result<Self, Tmc5160Error<B::Error>> {
        let mut s = Self {
            bus,
            en_pin,
            delay,
            r_sense: r_sense_ohm,
            fclk_hz: fclk_hz as f32,
            shadow: [0; 128],
            seen: [false; 128],
            usc: 256,
            toff: 3,
            rampmode: 0,
            speed: 200.0,
            speed_reg: 0,
            accel: 400.0,
            irun: 0,
            ihold: 0,
            ihold_delay: 6,
            senddelay: 0,
            last_move: (0.0, 200.0, 400.0),
            writes: 0,
        };
        s.initialise(multi_node)?;
        Ok(s)
    }

    pub(crate) fn write_reg(&mut self, reg: u8, value: u32) -> Result<(), Tmc5160Error<B::Error>> {
        self.bus.write_reg(reg, value, &mut self.delay)?;
        self.writes = self.writes.wrapping_add(1);
        self.shadow[reg as usize] = value;
        self.seen[reg as usize] = true;
        Ok(())
    }

    /// Write and check the acknowledge (SPI mirror: next datagram; UART: `IFCNT` +1).
    pub(crate) fn write_verified(&mut self, reg: u8, value: u32) -> Result<(), Tmc5160Error<B::Error>> {
        if self.bus.is_uart() {
            let before = (self.read_reg(REG_IFCNT)? & 0xFF) as u8;
            self.write_reg(reg, value)?;
            if (self.read_reg(REG_IFCNT)? & 0xFF) as u8 != before.wrapping_add(1) {
                return Err(Tmc5160Error::WriteNotAcknowledged);
            }
            Ok(())
        } else {
            self.write_reg(reg, value)?;
            if self.bus.read_reg(REG_GCONF, &mut self.delay).is_err() {
                return Err(Tmc5160Error::WriteNotAcknowledged);
            }
            Ok(())
        }
    }

    pub(crate) fn read_reg(&mut self, reg: u8) -> Result<u32, Tmc5160Error<B::Error>> {
        self.bus.read_reg(reg, &mut self.delay)
    }

    pub(crate) fn modify(&mut self, reg: u8, mask: u32, value: u32) -> Result<(), Tmc5160Error<B::Error>> {
        let cur = self.shadow[reg as usize];
        self.write_reg(reg, (cur & !mask) | (value & mask))
    }

    fn v_reg(&self, v: f32) -> i64 {
        roundf(v * self.usc as f32 * 16_777_216.0 / self.fclk_hz) as i64
    }

    fn a_reg(&self, a: f32) -> i64 {
        roundf(a * self.usc as f32 * 2_199_023_255_552.0 / (self.fclk_hz * self.fclk_hz)) as i64
    }

    fn check(v: i64, limit: i64) -> Result<u32, Tmc5160Error<B::Error>> {
        if v < 0 || v > limit {
            Err(Tmc5160Error::InvalidArgument)
        } else {
            Ok(v as u32)
        }
    }

    fn i_max(&self) -> f32 {
        V_FS / (self.r_sense * sqrtf(2.0))
    }

    fn apply_current(&mut self, rms_a: f32) -> Result<(), Tmc5160Error<B::Error>> {
        let r = rms_a / self.i_max();
        if r > 1.0 || r < 1.0 / 64.0 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        let (irun, scaler) = if r >= 0.5 {
            (31u8, roundf(256.0 * r) as u32)
        } else {
            ((roundf(64.0 * r) as i32 - 1).clamp(0, 31) as u8, 128)
        };
        self.write_reg(REG_GLOBALSCALER, if scaler >= 256 { 0 } else { scaler })?;
        self.irun = irun;
        self.ihold = irun / 3;
        self.ihold_delay = 6;
        self.write_ihold_irun()
    }

    pub(crate) fn write_ihold_irun(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        let v = (self.ihold as u32 & 0x1F) | ((self.irun as u32 & 0x1F) << 8) | ((self.ihold_delay as u32 & 0xF) << 16);
        self.write_reg(REG_IHOLD_IRUN, v)
    }

    fn initialise(&mut self, multi_node: bool) -> Result<(), Tmc5160Error<B::Error>> {
        if let Some(p) = self.en_pin.as_mut() {
            p.set_high().map_err(|_| Tmc5160Error::Pin)?;
        }
        self.delay.delay_ms(10);
        let uart = self.bus.is_uart();
        let base = if uart { (self.read_reg(REG_IFCNT)? & 0xFF) as u8 } else { 0 };
        let ioin = self.read_reg(REG_IOIN)?;
        if (ioin >> 24) as u8 != VERSION {
            return Err(Tmc5160Error::NotFound);
        }
        if ioin & (1 << 6) != 0 {
            return Err(Tmc5160Error::StepDirMode);
        }
        self.writes = 0;
        if uart && multi_node {
            self.senddelay = 2;
            self.write_verified(REG_SLAVECONF, 2 << 8)?;
            self.writes = 0;
        }
        self.read_reg(REG_GSTAT)?;
        self.write_reg(REG_GSTAT, 0x07)?;
        self.write_reg(REG_GLOBALSCALER, 0)?;
        let rms = if 0.5 < self.i_max() { 0.5 } else { self.i_max() };
        self.apply_current(rms)?;
        self.write_reg(REG_TPOWERDOWN, 10)?;
        self.write_reg(REG_CHOPCONF, CHOPCONF_DEFAULT)?;
        self.write_reg(REG_GCONF, GCONF_EN_PWM_MODE)?;
        self.write_reg(REG_TPWMTHRS, 500)?;
        self.write_reg(REG_RAMPMODE, 0)?;
        self.write_reg(REG_VSTART, 0)?;
        self.write_reg(REG_V1, 0)?;
        self.write_reg(REG_VSTOP, 10)?;
        self.write_reg(REG_TZEROWAIT, 0)?;
        self.set_speed(200.0)?;
        self.set_acceleration(400.0)?;
        if uart {
            if (self.read_reg(REG_IFCNT)? & 0xFF) as u8 != base.wrapping_add(self.writes) {
                return Err(Tmc5160Error::WriteNotAcknowledged);
            }
        } else if self.read_reg(REG_GCONF)? != self.shadow[REG_GCONF as usize]
            || self.read_reg(REG_CHOPCONF)? != self.shadow[REG_CHOPCONF as usize]
        {
            return Err(Tmc5160Error::WriteNotAcknowledged);
        }
        self.rampmode = 0;
        Ok(())
    }

    /// Switch the power stage on and wait 150 ms for StealthChop to settle.
    pub fn enable(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        if let Some(p) = self.en_pin.as_mut() {
            p.set_low().map_err(|_| Tmc5160Error::Pin)?;
        } else {
            let v = (self.shadow[REG_CHOPCONF as usize] & !0xF) | self.toff as u32;
            self.write_reg(REG_CHOPCONF, v)?;
        }
        self.delay.delay_ms(150);
        Ok(())
    }

    /// Switch the power stage off; the motor outputs float.
    pub fn disable(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        if let Some(p) = self.en_pin.as_mut() {
            return p.set_high().map_err(|_| Tmc5160Error::Pin);
        }
        let chop = self.shadow[REG_CHOPCONF as usize];
        if chop & 0xF != 0 {
            self.toff = (chop & 0xF) as u8;
        }
        self.write_reg(REG_CHOPCONF, chop & !0xF)
    }

    /// Set the run current in A RMS (hold becomes one third): `GLOBALSCALER` and `IHOLD_IRUN`.
    /// Returns [`Tmc5160Error::InvalidArgument`] above the sense-resistor maximum or below 1/64 of it.
    pub fn set_current(&mut self, rms_a: f32) -> Result<(), Tmc5160Error<B::Error>> {
        self.apply_current(rms_a)
    }

    /// Set `VMAX` in full steps per second (applied immediately in positioning mode).
    pub fn set_speed(&mut self, full_steps_per_s: f32) -> Result<(), Tmc5160Error<B::Error>> {
        if full_steps_per_s <= 0.0 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        let reg = Self::check(self.v_reg(full_steps_per_s), (1 << 23) - 512)?;
        self.speed = full_steps_per_s;
        self.speed_reg = reg;
        if self.rampmode == 0 {
            self.write_reg(REG_VMAX, reg)?;
        }
        Ok(())
    }

    /// Set a single trapezoid: `A1 = AMAX = DMAX = D1` (with `V1 = 0`), full steps per second squared.
    pub fn set_acceleration(&mut self, full_steps_per_s2: f32) -> Result<(), Tmc5160Error<B::Error>> {
        if full_steps_per_s2 <= 0.0 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        let reg = Self::check(self.a_reg(full_steps_per_s2), 0xFFFF)?;
        if reg < 1 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.accel = full_steps_per_s2;
        for r in [REG_A1, REG_AMAX, REG_DMAX, REG_D1] {
            self.write_reg(r, reg)?;
        }
        Ok(())
    }

    /// Start a positioning move to an absolute position in full steps (non-blocking).
    pub fn move_to(&mut self, position: f32) -> Result<(), Tmc5160Error<B::Error>> {
        let target = roundf(position * self.usc as f32) as i32;
        let dist = (position - self.position()?).abs();
        self.last_move = (dist, self.speed, self.accel);
        self.write_reg(REG_VMAX, self.speed_reg)?;
        self.write_reg(REG_RAMPMODE, 0)?;
        self.rampmode = 0;
        self.write_reg(REG_XTARGET, target as u32)
    }

    /// Blocking relative move; returns when the target is reached (optional speed override, restored afterwards).
    pub fn move_steps(&mut self, full_steps: f32, full_steps_per_s: Option<f32>) -> Result<(), Tmc5160Error<B::Error>> {
        let saved = self.speed;
        if let Some(v) = full_steps_per_s {
            self.set_speed(v)?;
        }
        let target = self.position()? + full_steps;
        let result = self.move_to(target).and_then(|_| self.wait_until_reached(None));
        if full_steps_per_s.is_some() {
            self.set_speed(saved)?;
        }
        result.map(|_| ())
    }

    /// Velocity mode: ramp to a velocity (sign = direction); 0 ramps to standstill.
    pub fn run(&mut self, full_steps_per_s: f32) -> Result<(), Tmc5160Error<B::Error>> {
        let reg = Self::check(self.v_reg(full_steps_per_s.abs()), (1 << 23) - 512)?;
        let mode = if full_steps_per_s < 0.0 { 2 } else { 1 };
        self.write_reg(REG_RAMPMODE, mode)?;
        self.rampmode = mode as u8;
        self.write_reg(REG_VMAX, reg)
    }

    /// Controlled stop: velocity mode with `VMAX = 0`.
    pub fn stop(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        self.write_reg(REG_RAMPMODE, 1)?;
        self.rampmode = 1;
        self.write_reg(REG_VMAX, 0)
    }

    /// `XACTUAL` in full steps.
    pub fn position(&mut self) -> Result<f32, Tmc5160Error<B::Error>> {
        Ok(self.read_reg(REG_XACTUAL)? as i32 as f32 / self.usc as f32)
    }

    /// Define the current position (no motion): writes `XACTUAL` and `XTARGET`.
    pub fn set_position(&mut self, position: f32) -> Result<(), Tmc5160Error<B::Error>> {
        let v = roundf(position * self.usc as f32) as i32 as u32;
        self.write_reg(REG_XACTUAL, v)?;
        self.write_reg(REG_XTARGET, v)
    }

    /// `VACTUAL` in full steps per second, signed.
    pub fn velocity(&mut self) -> Result<f32, Tmc5160Error<B::Error>> {
        let raw = sign_extend(self.read_reg(REG_VACTUAL)?, 24);
        Ok(raw as f32 * (self.fclk_hz / 2.0 / 8_388_608.0) / self.usc as f32)
    }

    /// `RAMP_STAT.position_reached`.
    pub fn is_position_reached(&mut self) -> Result<bool, Tmc5160Error<B::Error>> {
        Ok(self.read_reg(REG_RAMP_STAT)? & (1 << 9) != 0)
    }

    /// Poll `RAMP_STAT` every 5 ms until `position_reached` (positioning) or `vzero` (velocity mode).
    ///
    /// `timeout_s = None` estimates it from the last move. Returns [`Tmc5160Error::TimeoutReached`] on expiry.
    pub fn wait_until_reached(&mut self, timeout_s: Option<f32>) -> Result<bool, Tmc5160Error<B::Error>> {
        let timeout = timeout_s.unwrap_or_else(|| {
            let (d, s, a) = self.last_move;
            2.0 * d / s + 2.0 * s / a + 1.0
        });
        let bit = if self.rampmode == 0 { 1 << 9 } else { 1 << 10 };
        self.delay.delay_ms(1);
        let mut waited = 0.0f32;
        loop {
            if self.read_reg(REG_RAMP_STAT)? & bit != 0 {
                return Ok(true);
            }
            if waited >= timeout {
                return Err(Tmc5160Error::TimeoutReached);
            }
            self.delay.delay_ms(5);
            waited += 0.005;
        }
    }

    /// True when `DRV_STATUS.stst` is set.
    pub fn is_standstill(&mut self) -> Result<bool, Tmc5160Error<B::Error>> {
        Ok(self.read_reg(REG_DRV_STATUS)? >> 31 == 1)
    }

    /// Bitmask of `TMC5160_FAULT_*` constants, 0 when none.
    pub fn read_fault(&mut self) -> Result<u8, Tmc5160Error<B::Error>> {
        let s = self.read_reg(REG_DRV_STATUS)?;
        let mut f = 0;
        if s & (1 << 26) != 0 {
            f |= TMC5160_FAULT_OVERTEMP_WARNING;
        }
        if s & (1 << 25) != 0 {
            f |= TMC5160_FAULT_OVERTEMP;
        }
        if s & (3 << 27) != 0 {
            f |= TMC5160_FAULT_SHORT_GND;
        }
        if s & (3 << 12) != 0 {
            f |= TMC5160_FAULT_SHORT_SUPPLY;
        }
        if s & (3 << 29) != 0 {
            f |= TMC5160_FAULT_OPEN_LOAD;
        }
        Ok(f)
    }
}

/// TMC5160 -- complete interface. Wraps a [`Tmc5160Minimal`] (composition).
pub struct Tmc5160Full<B, EN, D, DIAG0, DIAG1> {
    pub(crate) core: Tmc5160Minimal<B, EN, D>,
    diag0: Option<DIAG0>,
    diag1: Option<DIAG1>,
}

impl<SPI: SpiDevice, EN: OutputPin, D: DelayNs, DIAG0: InputPin, DIAG1: InputPin>
    Tmc5160Full<SpiBus<SPI>, EN, D, DIAG0, DIAG1>
{
    /// Construct the driver on SPI (Mode 3); `diag0` / `diag1` read the DIAG pins.
    pub fn new_spi(
        spi: SPI,
        delay: D,
        r_sense_ohm: f32,
        en_pin: Option<EN>,
        diag0: Option<DIAG0>,
        diag1: Option<DIAG1>,
    ) -> Result<Self, Tmc5160Error<SPI::Error>> {
        let core = Tmc5160Minimal::new_spi(spi, delay, r_sense_ohm, en_pin)?;
        Ok(Self { core, diag0, diag1 })
    }
}

impl<U: Read + Write, EN: OutputPin, D: DelayNs> Tmc5160Full<UartBus<U>, EN, D, NoPin, NoPin> {
    /// Construct the driver on the single-wire UART (the DIAG pins carry the UART and are unavailable).
    #[allow(clippy::too_many_arguments)]
    pub fn new_uart(
        uart: U,
        delay: D,
        r_sense_ohm: f32,
        en_pin: Option<EN>,
        address: u8,
        echo: bool,
        multi_node: bool,
    ) -> Result<Self, Tmc5160Error<U::Error>> {
        let core = Tmc5160Minimal::new_uart(uart, delay, r_sense_ohm, en_pin, address, echo, multi_node)?;
        Ok(Self { core, diag0: None, diag1: None })
    }
}

impl<B: Tmc5160Bus, EN: OutputPin, D: DelayNs, DIAG0: InputPin, DIAG1: InputPin> Tmc5160Full<B, EN, D, DIAG0, DIAG1> {
    fn first_use(&mut self, reg: u8, reset: u32) {
        if !self.core.seen[reg as usize] {
            self.core.shadow[reg as usize] = reset;
            self.core.seen[reg as usize] = true;
        }
    }

    /// Switch the power stage on.
    pub fn enable(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.enable()
    }
    /// Switch the power stage off.
    pub fn disable(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.disable()
    }
    /// Set the run current in A RMS.
    pub fn set_current(&mut self, rms_a: f32) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.set_current(rms_a)
    }
    /// Set `VMAX` in full steps per second.
    pub fn set_speed(&mut self, v: f32) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.set_speed(v)
    }
    /// Set a single-trapezoid acceleration.
    pub fn set_acceleration(&mut self, a: f32) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.set_acceleration(a)
    }
    /// Start a positioning move (non-blocking).
    pub fn move_to(&mut self, position: f32) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.move_to(position)
    }
    /// Blocking relative move.
    pub fn move_steps(&mut self, full_steps: f32, speed: Option<f32>) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.move_steps(full_steps, speed)
    }
    /// Velocity mode.
    pub fn run(&mut self, v: f32) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.run(v)
    }
    /// Controlled stop.
    pub fn stop(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.stop()
    }
    /// Actual position in full steps.
    pub fn position(&mut self) -> Result<f32, Tmc5160Error<B::Error>> {
        self.core.position()
    }
    /// Define the current position.
    pub fn set_position(&mut self, p: f32) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.set_position(p)
    }
    /// Actual velocity in full steps per second.
    pub fn velocity(&mut self) -> Result<f32, Tmc5160Error<B::Error>> {
        self.core.velocity()
    }
    /// `RAMP_STAT.position_reached`.
    pub fn is_position_reached(&mut self) -> Result<bool, Tmc5160Error<B::Error>> {
        self.core.is_position_reached()
    }
    /// Wait for the end of the move (see [`Tmc5160Minimal::wait_until_reached`]).
    pub fn wait_until_reached(&mut self, timeout_s: Option<f32>) -> Result<bool, Tmc5160Error<B::Error>> {
        self.core.wait_until_reached(timeout_s)
    }
    /// True at standstill.
    pub fn is_standstill(&mut self) -> Result<bool, Tmc5160Error<B::Error>> {
        self.core.is_standstill()
    }
    /// Fault bitmask.
    pub fn read_fault(&mut self) -> Result<u8, Tmc5160Error<B::Error>> {
        self.core.read_fault()
    }

    /// Raw 32-bit read of a readable register.
    pub fn read_register(&mut self, reg: u8) -> Result<u32, Tmc5160Error<B::Error>> {
        if !READABLE.contains(&reg) {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.read_reg(reg)
    }

    /// Raw 32-bit write; `verify` adds the SPI mirror / UART `IFCNT` check.
    pub fn write_register(&mut self, reg: u8, value: u32, verify: bool) -> Result<(), Tmc5160Error<B::Error>> {
        if !WRITABLE.contains(&reg) {
            return Err(Tmc5160Error::InvalidArgument);
        }
        if verify {
            self.core.write_verified(reg, value)
        } else {
            self.core.write_reg(reg, value)
        }
    }

    /// Independent hold current in A RMS (not above the run current).
    pub fn set_hold_current(&mut self, rms_a: f32) -> Result<(), Tmc5160Error<B::Error>> {
        let gs = self.core.shadow[REG_GLOBALSCALER as usize];
        let gs = if gs == 0 { 256.0 } else { gs as f32 };
        let full = gs / 256.0 * self.core.i_max();
        let ihold = roundf(rms_a / full * 32.0) as i32 - 1;
        if rms_a <= 0.0 || ihold > self.core.irun as i32 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.ihold = ihold.max(0) as u8;
        self.core.write_ihold_irun()
    }

    /// Raw `GLOBALSCALER`: 0 or 32..=256 (256 is written as 0).
    pub fn set_global_scaler(&mut self, value: u16) -> Result<(), Tmc5160Error<B::Error>> {
        if !(value == 0 || (32..=256).contains(&value)) {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.write_reg(REG_GLOBALSCALER, if value == 256 { 0 } else { value as u32 })
    }

    /// Raw `IHOLD_IRUN` fields: `irun` 0..=31, `ihold` 0..=31, `ihold_delay` 0..=15.
    pub fn set_current_scale(&mut self, irun: u8, ihold: u8, ihold_delay: u8) -> Result<(), Tmc5160Error<B::Error>> {
        if irun > 31 || ihold > 31 || ihold_delay > 15 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.irun = irun;
        self.core.ihold = ihold;
        self.core.ihold_delay = ihold_delay;
        self.core.write_ihold_irun()
    }

    /// `TPOWERDOWN` (values below 2 break StealthChop auto-tuning).
    pub fn set_power_down_delay(&mut self, tpowerdown: u8) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.write_reg(REG_TPOWERDOWN, tpowerdown as u32)
    }

    /// Program the full SixPoint ramp in physical units (full steps/s and full steps/s^2).
    #[allow(clippy::too_many_arguments)]
    pub fn configure_ramp(
        &mut self,
        vstart: f32,
        a1: f32,
        v1: f32,
        amax: f32,
        vmax: f32,
        dmax: f32,
        d1: f32,
        vstop: f32,
    ) -> Result<(), Tmc5160Error<B::Error>> {
        let c = &self.core;
        let raw = [c.v_reg(vstart), c.a_reg(a1), c.v_reg(v1), c.a_reg(amax), c.v_reg(vmax), c.a_reg(dmax), c.a_reg(d1), c.v_reg(vstop)];
        if raw.iter().any(|v| *v < 0 || *v > u32::MAX as i64) {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.set_ramp_raw(raw[0] as u32, raw[1] as u32, raw[2] as u32, raw[3] as u32, raw[4] as u32, raw[5] as u32, raw[6] as u32, raw[7] as u32)?;
        self.core.speed = vmax;
        self.core.accel = amax;
        Ok(())
    }

    /// Program the full SixPoint ramp in raw register units.
    #[allow(clippy::too_many_arguments)]
    pub fn set_ramp_raw(
        &mut self,
        vstart: u32,
        a1: u32,
        v1: u32,
        amax: u32,
        vmax: u32,
        dmax: u32,
        d1: u32,
        vstop: u32,
    ) -> Result<(), Tmc5160Error<B::Error>> {
        if vstart >= 1 << 18 || a1 > 0xFFFF || v1 >= 1 << 20 || amax > 0xFFFF || vmax > (1 << 23) - 512
            || dmax > 0xFFFF || d1 > 0xFFFF || vstop >= 1 << 18 || d1 < 1 || vstop < 1 || vstop < vstart
        {
            return Err(Tmc5160Error::InvalidArgument);
        }
        for (r, v) in [(REG_VSTART, vstart), (REG_A1, a1), (REG_V1, v1), (REG_AMAX, amax), (REG_VMAX, vmax), (REG_DMAX, dmax), (REG_D1, d1), (REG_VSTOP, vstop)] {
            self.core.write_reg(r, v)?;
        }
        self.core.speed_reg = vmax;
        Ok(())
    }

    /// `TZEROWAIT` in seconds (0 to about 2.8 s at 12 MHz).
    pub fn set_zero_wait(&mut self, seconds: f32) -> Result<(), Tmc5160Error<B::Error>> {
        let v = Tmc5160Minimal::<B, EN, D>::check(roundf(seconds * self.core.fclk_hz / 512.0) as i64, 0xFFFF)?;
        self.core.write_reg(REG_TZEROWAIT, v)
    }

    /// `RAMPMODE` 0..=3 (3 = hold).
    pub fn set_ramp_mode(&mut self, mode: u8) -> Result<(), Tmc5160Error<B::Error>> {
        if mode > 3 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.write_reg(REG_RAMPMODE, mode as u32)?;
        self.core.rampmode = mode;
        Ok(())
    }

    /// Hold mode: keep the present velocity until a stop event.
    pub fn hold(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        self.set_ramp_mode(3)
    }

    /// Write `XTARGET` in microsteps (starts positioning in mode 0).
    pub fn set_target_raw(&mut self, xtarget: i32) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.write_reg(REG_XTARGET, xtarget as u32)
    }

    /// Decoded `RAMP_STAT` (one read).
    pub fn read_ramp_status(&mut self) -> Result<Tmc5160RampStatus, Tmc5160Error<B::Error>> {
        let s = self.core.read_reg(REG_RAMP_STAT)?;
        let b = |i: u32| s & (1 << i) != 0;
        Ok(Tmc5160RampStatus {
            status_stop_l: b(0), status_stop_r: b(1), status_latch_l: b(2), status_latch_r: b(3), event_stop_l: b(4),
            event_stop_r: b(5), event_stop_sg: b(6), event_pos_reached: b(7), velocity_reached: b(8),
            position_reached: b(9), vzero: b(10), t_zerowait_active: b(11), second_move: b(12), status_sg: b(13),
        })
    }

    /// Write 1 to the clear-on-write bits of `RAMP_STAT`.
    pub fn clear_ramp_events(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.write_reg(REG_RAMP_STAT, (1 << 2) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 12))
    }

    /// `X_COMPARE` in full steps (`DIAG1` goes high while `XACTUAL` equals it).
    pub fn set_position_compare(&mut self, position: f32) -> Result<(), Tmc5160Error<B::Error>> {
        let v = roundf(position * self.core.usc as f32) as i32 as u32;
        self.core.write_reg(REG_X_COMPARE, v)
    }

    /// `SW_MODE` bits 0-4 and 11 (latch and `sg_stop` bits are kept).
    pub fn configure_switches(
        &mut self,
        stop_left: bool,
        stop_right: bool,
        left_active_low: bool,
        right_active_low: bool,
        swap: bool,
        soft_stop: bool,
    ) -> Result<(), Tmc5160Error<B::Error>> {
        if soft_stop && self.core.shadow[REG_SW_MODE as usize] & SW_SG_STOP != 0 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        let v = stop_left as u32 | (stop_right as u32) << 1 | (left_active_low as u32) << 2 | (right_active_low as u32) << 3
            | (swap as u32) << 4 | if soft_stop { SW_SOFTSTOP } else { 0 };
        self.core.modify(REG_SW_MODE, 0x1F | SW_SOFTSTOP, v)
    }

    /// `SW_MODE` latch bits 5-9.
    pub fn configure_latch(
        &mut self,
        left_active: bool,
        left_inactive: bool,
        right_active: bool,
        right_inactive: bool,
        latch_encoder: bool,
    ) -> Result<(), Tmc5160Error<B::Error>> {
        let v = (left_active as u32) << 5 | (left_inactive as u32) << 6 | (right_active as u32) << 7
            | (right_inactive as u32) << 8 | (latch_encoder as u32) << 9;
        self.core.modify(REG_SW_MODE, 0x1F << 5, v)
    }

    /// `XLATCH` in full steps.
    pub fn read_latched_position(&mut self) -> Result<f32, Tmc5160Error<B::Error>> {
        Ok(self.core.read_reg(REG_XLATCH)? as i32 as f32 / self.core.usc as f32)
    }

    /// `(status_stop_l, status_stop_r)`.
    pub fn read_switches(&mut self) -> Result<(bool, bool), Tmc5160Error<B::Error>> {
        let s = self.core.read_reg(REG_RAMP_STAT)?;
        Ok((s & 1 != 0, s & 2 != 0))
    }

    /// Microstep resolution 1, 2, 4, ..., 256 (the ramp registers are not rewritten).
    pub fn set_microsteps(&mut self, n: u16) -> Result<(), Tmc5160Error<B::Error>> {
        if !n.is_power_of_two() || n > 256 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.modify(REG_CHOPCONF, 0xF << 24, (8 - n.trailing_zeros()) << 24)?;
        self.core.usc = n as u32;
        Ok(())
    }

    /// `CHOPCONF.intpol`.
    pub fn set_interpolation(&mut self, enabled: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.modify(REG_CHOPCONF, 1 << 28, if enabled { 1 << 28 } else { 0 })
    }

    /// SpreadCycle only (`true`) or StealthChop (`false`); switch at standstill.
    pub fn set_chopper_mode(&mut self, spreadcycle: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.modify(REG_GCONF, GCONF_EN_PWM_MODE, if spreadcycle { 0 } else { GCONF_EN_PWM_MODE })
    }

    /// `TPWMTHRS` (20 bit); 0 disables.
    pub fn set_stealthchop_threshold(&mut self, v: u32) -> Result<(), Tmc5160Error<B::Error>> {
        self.threshold(REG_TPWMTHRS, v)
    }

    /// `THIGH` (20 bit).
    pub fn set_high_velocity_threshold(&mut self, v: u32) -> Result<(), Tmc5160Error<B::Error>> {
        self.threshold(REG_THIGH, v)
    }

    fn threshold(&mut self, reg: u8, v: u32) -> Result<(), Tmc5160Error<B::Error>> {
        if v > 0xF_FFFF {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.write_reg(reg, v)
    }

    /// SpreadCycle (`chm = 0`) fields: `toff` 0..=15, `tbl` 0..=3, `hstrt` 0..=7, raw `hend` 0..=15, `tpfd` 0..=15.
    pub fn configure_chopper(&mut self, toff: u8, tbl: u8, hstrt: u8, hend: u8, tpfd: u8) -> Result<(), Tmc5160Error<B::Error>> {
        if toff > 15 || tbl > 3 || hstrt > 7 || hend > 15 || tpfd > 15 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        if toff != 0 {
            self.core.toff = toff;
        }
        let mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (0xF << 20) | (1 << 14) | (1 << 11);
        let v = toff as u32 | (tbl as u32) << 15 | (hstrt as u32) << 4 | (hend as u32) << 7 | (tpfd as u32) << 20;
        self.core.modify(REG_CHOPCONF, mask, v)
    }

    /// Constant-off-time (`chm = 1`) fields: `tfd` 0..=15, `offset` -3..=12.
    pub fn configure_constant_off_time(&mut self, toff: u8, tbl: u8, tfd: u8, offset: i8, disfdcc: bool, tpfd: u8) -> Result<(), Tmc5160Error<B::Error>> {
        if toff > 15 || tbl > 3 || tfd > 15 || !(-3..=12).contains(&offset) || tpfd > 15 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        if toff != 0 {
            self.core.toff = toff;
        }
        let mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (0xF << 20) | (1 << 14) | (1 << 12) | (1 << 11);
        let v = toff as u32 | (tbl as u32) << 15 | ((tfd & 7) as u32) << 4 | ((offset + 3) as u32) << 7 | (tpfd as u32) << 20
            | 1 << 14 | if disfdcc { 1 << 12 } else { 0 } | (((tfd >> 3) & 1) as u32) << 11;
        self.core.modify(REG_CHOPCONF, mask, v)
    }

    /// `CHOPCONF.vhighchm` / `vhighfs`.
    pub fn configure_high_velocity(&mut self, vhighchm: bool, vhighfs: bool) -> Result<(), Tmc5160Error<B::Error>> {
        let v = if vhighchm { 1 << 19 } else { 0 } | if vhighfs { 1 << 18 } else { 0 };
        self.core.modify(REG_CHOPCONF, (1 << 19) | (1 << 18), v)
    }

    /// `PWMCONF` fields (`freewheel` kept): `pwm_freq` 0..=3, `pwm_reg` 1..=15, `pwm_lim` 0..=15.
    #[allow(clippy::too_many_arguments)]
    pub fn configure_stealthchop(
        &mut self,
        autoscale: bool,
        autograd: bool,
        pwm_ofs: u8,
        pwm_grad: u8,
        pwm_freq: u8,
        pwm_reg: u8,
        pwm_lim: u8,
    ) -> Result<(), Tmc5160Error<B::Error>> {
        if pwm_freq > 3 || !(1..=15).contains(&pwm_reg) || pwm_lim > 15 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.first_use(REG_PWMCONF, PWMCONF_RESET);
        let mask = (0xF << 28) | (0xF << 24) | (1 << 19) | (1 << 18) | (3 << 16) | (0xFF << 8) | 0xFF;
        let v = (pwm_lim as u32) << 28 | (pwm_reg as u32) << 24 | if autograd { 1 << 19 } else { 0 } | if autoscale { 1 << 18 } else { 0 }
            | (pwm_freq as u32) << 16 | (pwm_grad as u32) << 8 | pwm_ofs as u32;
        self.core.modify(REG_PWMCONF, mask, v)
    }

    /// `PWMCONF.freewheel` 0..=3.
    pub fn set_freewheel(&mut self, mode: u8) -> Result<(), Tmc5160Error<B::Error>> {
        if mode > 3 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.first_use(REG_PWMCONF, PWMCONF_RESET);
        self.core.modify(REG_PWMCONF, 3 << 20, (mode as u32) << 20)
    }

    /// `CHOPCONF.diss2g` / `diss2vs`: `true` keeps the protection.
    pub fn set_short_protection(&mut self, short_gnd: bool, short_supply: bool) -> Result<(), Tmc5160Error<B::Error>> {
        let v = if short_gnd { 0 } else { 1 << 30 } | if short_supply { 0 } else { 1 << 31 };
        self.core.modify(REG_CHOPCONF, (1 << 30) | (1 << 31), v)
    }

    /// `CHOPCONF.dedge` (external STEP/DIR only).
    pub fn set_double_edge(&mut self, enabled: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.modify(REG_CHOPCONF, 1 << 29, if enabled { 1 << 29 } else { 0 })
    }

    fn gconf(&mut self, mask: u32, on: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.modify(REG_GCONF, mask, if on { mask } else { 0 })
    }

    /// `GCONF.shaft`.
    pub fn set_direction_inverted(&mut self, inverted: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.gconf(GCONF_SHAFT, inverted)
    }
    /// `GCONF.faststandstill`.
    pub fn set_fast_standstill(&mut self, enabled: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.gconf(1 << 1, enabled)
    }
    /// `GCONF.recalibrate`.
    pub fn set_recalibrate(&mut self, enabled: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.gconf(1, enabled)
    }
    /// `GCONF.small_hysteresis`.
    pub fn set_small_hysteresis(&mut self, enabled: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.gconf(GCONF_SMALL_HYSTERESIS, enabled)
    }
    /// `GCONF.stop_enable`.
    pub fn set_emergency_stop(&mut self, enabled: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.gconf(GCONF_STOP_ENABLE, enabled)
    }

    /// `GCONF` bits 7, 8, 12, 13 (`SD_MODE = 0` meanings).
    pub fn configure_diag(&mut self, diag0_step_output: bool, diag1_dir_output: bool, diag0_pushpull: bool, diag1_pushpull: bool) -> Result<(), Tmc5160Error<B::Error>> {
        let v = (diag0_step_output as u32) << 7 | (diag1_dir_output as u32) << 8 | (diag0_pushpull as u32) << 12 | (diag1_pushpull as u32) << 13;
        self.core.modify(REG_GCONF, (1 << 7) | (1 << 8) | (1 << 12) | (1 << 13), v)
    }

    /// `COOLCONF.sgt` -64..=63 (lower = more sensitive); other fields are kept.
    pub fn set_stall_threshold(&mut self, sgt: i8) -> Result<(), Tmc5160Error<B::Error>> {
        if !(-64..=63).contains(&sgt) {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.modify(REG_COOLCONF, 0x7F << 16, ((sgt as u32) & 0x7F) << 16)
    }

    /// `COOLCONF.sfilt`.
    pub fn set_stallguard_filter(&mut self, enabled: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.modify(REG_COOLCONF, 1 << 24, if enabled { 1 << 24 } else { 0 })
    }

    /// `TCOOLTHRS` (20 bit); 0 disables.
    pub fn set_coolstep_threshold(&mut self, v: u32) -> Result<(), Tmc5160Error<B::Error>> {
        self.threshold(REG_TCOOLTHRS, v)
    }

    /// `COOLCONF` CoolStep fields (`sgt` / `sfilt` kept).
    pub fn configure_coolstep(&mut self, semin: u8, semax: u8, seup: u8, sedn: u8, seimin: u8) -> Result<(), Tmc5160Error<B::Error>> {
        if semin > 15 || semax > 15 || seup > 3 || sedn > 3 || seimin > 1 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        let mask = 0xF | (3 << 5) | (0xF << 8) | (3 << 13) | (1 << 15);
        let v = semin as u32 | (seup as u32) << 5 | (semax as u32) << 8 | (sedn as u32) << 13 | (seimin as u32) << 15;
        self.core.modify(REG_COOLCONF, mask, v)
    }

    /// `SW_MODE.sg_stop`: stop the motor on a StallGuard2 event (needs `TCOOLTHRS`; not with soft stop).
    pub fn enable_stall_stop(&mut self, enabled: bool) -> Result<(), Tmc5160Error<B::Error>> {
        if enabled && self.core.shadow[REG_SW_MODE as usize] & SW_SOFTSTOP != 0 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.modify(REG_SW_MODE, SW_SG_STOP, if enabled { SW_SG_STOP } else { 0 })
    }

    /// `SG_RESULT` 0..=1023.
    pub fn read_stallguard(&mut self) -> Result<u16, Tmc5160Error<B::Error>> {
        Ok((self.core.read_reg(REG_DRV_STATUS)? & 0x3FF) as u16)
    }

    /// `DRV_STATUS.StallGuard`.
    pub fn is_stalled(&mut self) -> Result<bool, Tmc5160Error<B::Error>> {
        Ok((self.core.read_reg(REG_DRV_STATUS)? >> 24) & 1 == 1)
    }

    /// Level of `DIAG0` (`n` = 0) or `DIAG1` (`n` = 1); [`Tmc5160Error::Pin`] if not wired (or UART).
    pub fn read_diag(&mut self, n: u8) -> Result<bool, Tmc5160Error<B::Error>> {
        match n {
            0 => self.diag0.as_mut().ok_or(Tmc5160Error::Pin)?.is_high().map_err(|_| Tmc5160Error::Pin),
            1 => self.diag1.as_mut().ok_or(Tmc5160Error::Pin)?.is_high().map_err(|_| Tmc5160Error::Pin),
            _ => Err(Tmc5160Error::InvalidArgument),
        }
    }

    /// `VDCMIN` (0..=2^23-1), `DCCTRL.DC_TIME` (0..=1023), `DC_SG` (0..=255).
    pub fn set_dcstep(&mut self, vdcmin: u32, dc_time: u16, dc_sg: u8) -> Result<(), Tmc5160Error<B::Error>> {
        if vdcmin > 0x7F_FFFF || dc_time > 1023 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.write_reg(REG_VDCMIN, vdcmin)?;
        self.core.write_reg(REG_DCCTRL, dc_time as u32 | (dc_sg as u32) << 16)
    }

    /// `GCONF.direct_mode` (the ramp generator is bypassed while set; leaving it rewrites `XTARGET`).
    pub fn set_direct_mode(&mut self, enabled: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.gconf(GCONF_DIRECT_MODE, enabled)?;
        if !enabled {
            let t = self.core.shadow[REG_XTARGET as usize];
            self.core.write_reg(REG_XTARGET, t)?;
        }
        Ok(())
    }

    /// `XDIRECT` coil currents -255..=255 each; requires direct mode.
    pub fn set_coil_currents(&mut self, coil_a: i16, coil_b: i16) -> Result<(), Tmc5160Error<B::Error>> {
        if self.core.shadow[REG_GCONF as usize] & GCONF_DIRECT_MODE == 0 {
            return Err(Tmc5160Error::DirectModeRequired);
        }
        if !(-255..=255).contains(&coil_a) || !(-255..=255).contains(&coil_b) {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.write_reg(REG_XTARGET, (coil_a as u32 & 0x1FF) | ((coil_b as u32 & 0x1FF) << 16))
    }

    /// Program `MSLUT[0..8]`, `MSLUTSEL`, `MSLUTSTART`; call at `MSCNT = 0`.
    pub fn write_microstep_table(&mut self, mslut: &[u32; 8], mslutsel: u32, mslutstart: u32) -> Result<(), Tmc5160Error<B::Error>> {
        for (i, w) in mslut.iter().enumerate() {
            self.core.write_reg(REG_MSLUT0 + i as u8, *w)?;
        }
        self.core.write_reg(REG_MSLUTSEL, mslutsel)?;
        self.core.write_reg(REG_MSLUTSTART, mslutstart)
    }

    /// `DRV_CONF` -- board-specific, wrong values can destroy the MOSFETs.
    pub fn configure_gate_driver(&mut self, bbmtime: u8, bbmclks: u8, otselect: u8, drvstrength: u8, filt_isense: u8) -> Result<(), Tmc5160Error<B::Error>> {
        if bbmtime > 24 || bbmclks > 15 || otselect > 3 || drvstrength > 3 || filt_isense > 3 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        let v = bbmtime as u32 | (bbmclks as u32) << 8 | (otselect as u32) << 16 | (drvstrength as u32) << 18 | (filt_isense as u32) << 20;
        self.core.write_reg(REG_DRV_CONF, v)
    }

    /// `SHORT_CONF` -- board-specific: `s2vs_level` 4..=15, `s2g_level` 2..=15, `shortfilter` 0..=3, `shortdelay` 0|1.
    pub fn configure_short_detection(&mut self, s2vs_level: u8, s2g_level: u8, shortfilter: u8, shortdelay: u8) -> Result<(), Tmc5160Error<B::Error>> {
        if !(4..=15).contains(&s2vs_level) || !(2..=15).contains(&s2g_level) || shortfilter > 3 || shortdelay > 1 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        let v = s2vs_level as u32 | (s2g_level as u32) << 8 | (shortfilter as u32) << 16 | (shortdelay as u32) << 18;
        self.core.write_reg(REG_SHORT_CONF, v)
    }

    /// `ENCMODE`; `flags` is, in register bit order: `pol_a, pol_b, pol_n, ignore_ab, clr_cont, clr_once, pos_edge, neg_edge, clr_enc_x, latch_x_act, decimal`.
    pub fn configure_encoder(&mut self, flags: [bool; 11]) -> Result<(), Tmc5160Error<B::Error>> {
        let mut v = 0u32;
        for (i, f) in flags.iter().enumerate() {
            if *f {
                v |= 1 << i;
            }
        }
        self.core.write_reg(REG_ENCMODE, v)
    }

    /// `ENC_CONST`: signed 16.16 (or integer + 4-digit decimal fraction when `ENCMODE.enc_sel_decimal` is set).
    pub fn set_encoder_constant(&mut self, value: f32) -> Result<(), Tmc5160Error<B::Error>> {
        if value.abs() > 32767.9999 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        let raw = if self.core.shadow[REG_ENCMODE as usize] & (1 << 10) != 0 {
            let a = value.abs();
            let whole = a as u32;
            let r = whole << 16 | roundf((a - whole as f32) * 10000.0) as u32;
            if value < 0.0 { r.wrapping_neg() } else { r }
        } else {
            roundf(value * 65536.0) as i32 as u32
        };
        self.core.write_reg(REG_ENC_CONST, raw)
    }

    /// `X_ENC`.
    pub fn set_encoder_position(&mut self, counts: i32) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.write_reg(REG_X_ENC, counts as u32)
    }
    /// `X_ENC` signed.
    pub fn read_encoder_position(&mut self) -> Result<i32, Tmc5160Error<B::Error>> {
        Ok(self.core.read_reg(REG_X_ENC)? as i32)
    }
    /// `ENC_LATCH` signed.
    pub fn read_encoder_latch(&mut self) -> Result<i32, Tmc5160Error<B::Error>> {
        Ok(self.core.read_reg(REG_ENC_LATCH)? as i32)
    }
    /// `ENC_DEVIATION` 0..=2^20-1 (0 = off).
    pub fn set_encoder_deviation(&mut self, steps: u32) -> Result<(), Tmc5160Error<B::Error>> {
        self.threshold(REG_ENC_DEVIATION, steps)
    }
    /// `(n_event, deviation_warn)`.
    pub fn read_encoder_status(&mut self) -> Result<(bool, bool), Tmc5160Error<B::Error>> {
        let s = self.core.read_reg(REG_ENC_STATUS)?;
        Ok((s & 1 != 0, s & 2 != 0))
    }
    /// Write 1 to the `ENC_STATUS` event bits.
    pub fn clear_encoder_status(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.write_reg(REG_ENC_STATUS, 3)
    }

    fn require_uart(&self) -> Result<(), Tmc5160Error<B::Error>> {
        if self.core.bus.is_uart() { Ok(()) } else { Err(Tmc5160Error::UartOnly) }
    }

    /// `SLAVECONF.SLAVEADDR` 0..=253 (UART only); later datagrams use the new address.
    pub fn set_slave_address(&mut self, slaveaddr: u8) -> Result<(), Tmc5160Error<B::Error>> {
        self.require_uart()?;
        if slaveaddr > 253 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        let v = slaveaddr as u32 | (self.core.senddelay as u32) << 8;
        self.core.write_reg(REG_SLAVECONF, v)?;
        self.core.bus.set_address(slaveaddr);
        Ok(())
    }

    /// `SLAVECONF.SENDDELAY` 0..=15 (UART only; at least 2 on multi-node buses).
    pub fn set_send_delay(&mut self, sd: u8) -> Result<(), Tmc5160Error<B::Error>> {
        self.require_uart()?;
        if sd > 15 {
            return Err(Tmc5160Error::InvalidArgument);
        }
        self.core.senddelay = sd;
        let v = (self.core.shadow[REG_SLAVECONF as usize] & 0xFF) | (sd as u32) << 8;
        self.core.write_reg(REG_SLAVECONF, v)
    }

    /// `OUTPUT` bit 0, the `NAO` polarity (UART only).
    pub fn set_nao_polarity(&mut self, level: bool) -> Result<(), Tmc5160Error<B::Error>> {
        self.require_uart()?;
        self.core.write_reg(REG_OUTPUT, level as u32)
    }

    /// `IFCNT` (UART only).
    pub fn read_ifcnt(&mut self) -> Result<u8, Tmc5160Error<B::Error>> {
        self.require_uart()?;
        Ok((self.core.read_reg(REG_IFCNT)? & 0xFF) as u8)
    }

    /// `MSCNT` 0..=1023.
    pub fn microstep_position(&mut self) -> Result<u16, Tmc5160Error<B::Error>> {
        Ok((self.core.read_reg(REG_MSCNT)? & 0x3FF) as u16)
    }
    /// `DRV_STATUS.CS_ACTUAL` 0..=31.
    pub fn read_current_scale(&mut self) -> Result<u8, Tmc5160Error<B::Error>> {
        Ok(((self.core.read_reg(REG_DRV_STATUS)? >> 16) & 0x1F) as u8)
    }
    /// Signed `(CUR_A, CUR_B)` from `MSCURACT`.
    pub fn read_coil_currents(&mut self) -> Result<(i16, i16), Tmc5160Error<B::Error>> {
        let raw = self.core.read_reg(REG_MSCURACT)?;
        Ok((sign_extend(raw >> 16, 9) as i16, sign_extend(raw, 9) as i16))
    }
    /// `TSTEP` (20 bit).
    pub fn read_tstep(&mut self) -> Result<u32, Tmc5160Error<B::Error>> {
        Ok(self.core.read_reg(REG_TSTEP)? & 0xF_FFFF)
    }
    /// `(PWM_SCALE_SUM, PWM_SCALE_AUTO)`.
    pub fn read_pwm_scale(&mut self) -> Result<(u8, i16), Tmc5160Error<B::Error>> {
        let raw = self.core.read_reg(REG_PWM_SCALE)?;
        Ok(((raw & 0xFF) as u8, sign_extend(raw >> 16, 9) as i16))
    }
    /// `(PWM_OFS_AUTO, PWM_GRAD_AUTO)`.
    pub fn read_pwm_auto(&mut self) -> Result<(u8, u8), Tmc5160Error<B::Error>> {
        let raw = self.core.read_reg(REG_PWM_AUTO)?;
        Ok(((raw & 0xFF) as u8, ((raw >> 16) & 0xFF) as u8))
    }
    /// Signed `(phase A, phase B)` from `OFFSET_READ`.
    pub fn read_offset(&mut self) -> Result<(i8, i8), Tmc5160Error<B::Error>> {
        let raw = self.core.read_reg(REG_OFFSET_READ)?;
        Ok((((raw >> 8) & 0xFF) as u8 as i8, (raw & 0xFF) as u8 as i8))
    }

    /// Decoded `DRV_STATUS` (one read).
    pub fn read_status(&mut self) -> Result<Tmc5160Status, Tmc5160Error<B::Error>> {
        let s = self.core.read_reg(REG_DRV_STATUS)?;
        Ok(Tmc5160Status {
            stst: s >> 31 == 1,
            stealth: s & (1 << 14) != 0,
            fsactive: s & (1 << 15) != 0,
            cs_actual: ((s >> 16) & 0x1F) as u8,
            sg_result: (s & 0x3FF) as u16,
            stallguard: s & (1 << 24) != 0,
            otpw: s & (1 << 26) != 0,
            ot: s & (1 << 25) != 0,
            short_gnd: s & (3 << 27) != 0,
            short_supply: s & (3 << 12) != 0,
            open_load: s & (3 << 29) != 0,
        })
    }

    /// Decoded `GSTAT` (does not clear).
    pub fn read_global_status(&mut self) -> Result<Tmc5160GlobalStatus, Tmc5160Error<B::Error>> {
        let g = self.core.read_reg(REG_GSTAT)?;
        Ok(Tmc5160GlobalStatus { reset: g & 1 != 0, drv_err: g & 2 != 0, uv_cp: g & 4 != 0 })
    }

    /// Write `0x07` to `GSTAT`.
    pub fn clear_global_status(&mut self) -> Result<(), Tmc5160Error<B::Error>> {
        self.core.write_reg(REG_GSTAT, 7)
    }

    /// Decoded `IOIN`.
    pub fn read_ioin(&mut self) -> Result<Tmc5160Ioin, Tmc5160Error<B::Error>> {
        let raw = self.core.read_reg(REG_IOIN)?;
        Ok(Tmc5160Ioin {
            raw,
            refl: raw & 1 != 0,
            refr: raw & 2 != 0,
            encb: raw & 4 != 0,
            enca: raw & 8 != 0,
            drv_enn: raw & 16 != 0,
            encn: raw & 32 != 0,
            sd_mode: raw & 64 != 0,
            swcomp_in: raw & 128 != 0,
            version: (raw >> 24) as u8,
        })
    }
}

#[cfg(all(test, feature = "std"))]
mod tests {
    use super::*;
    use embedded_hal::spi::ErrorType;
    use std::collections::BTreeMap;

    /// Scripted TMC5160 (SPI): pipelined 40-bit datagrams, instant ramp generator.
    #[derive(Default)]
    struct SpiSim {
        regs: BTreeMap<u8, u32>,
        writes: Vec<(u8, u32)>,
        pending: u32,
        version: u8,
    }

    impl SpiSim {
        fn new() -> Self {
            let mut s = Self { version: 0x30, ..Default::default() };
            s.regs.insert(0x04, 0x3000_0000);
            s
        }
        fn last(&self, reg: u8) -> Option<u32> {
            self.writes.iter().rev().find(|w| w.0 == reg).map(|w| w.1)
        }
    }

    impl ErrorType for SpiSim {
        type Error = embedded_hal::spi::ErrorKind;
    }

    impl SpiDevice for SpiSim {
        fn transaction(&mut self, ops: &mut [Operation<'_, u8>]) -> Result<(), Self::Error> {
            let mut f = Vec::new();
            for op in ops.iter() {
                if let Operation::Write(w) = op {
                    f.extend_from_slice(w);
                }
            }
            f.resize(5, 0);
            let reply = self.pending;
            let reg = f[0] & 0x7F;
            if f[0] & 0x80 != 0 {
                let v = u32::from_be_bytes([f[1], f[2], f[3], f[4]]);
                self.regs.insert(reg, v);
                self.writes.push((reg, v));
                if reg == 0x2D {
                    self.regs.insert(0x21, v);
                    self.regs.insert(0x35, 1 << 9);
                }
                self.pending = v;
            } else {
                self.pending = self.regs.get(&reg).copied().unwrap_or(0);
            }
            let _ = self.version;
            let b = reply.to_be_bytes();
            for op in ops.iter_mut() {
                if let Operation::Read(r) = op {
                    r.copy_from_slice(&b[..r.len()]);
                }
            }
            Ok(())
        }
    }

    /// Scripted TMC5160 (UART) with echo, CRC and IFCNT.
    #[derive(Default)]
    struct UartSim {
        regs: BTreeMap<u8, u32>,
        out: std::collections::VecDeque<u8>,
        ifcnt: u8,
        writes: Vec<(u8, u32)>,
    }

    #[derive(Debug)]
    struct UErr;
    impl embedded_io::Error for UErr {
        fn kind(&self) -> embedded_io::ErrorKind {
            embedded_io::ErrorKind::Other
        }
    }
    impl embedded_io::ErrorType for UartSim {
        type Error = UErr;
    }
    impl Write for UartSim {
        fn write(&mut self, buf: &[u8]) -> Result<usize, UErr> {
            self.out.extend(buf.iter().copied());
            if buf.len() == 8 {
                let reg = buf[2] & 0x7F;
                let v = u32::from_be_bytes([buf[3], buf[4], buf[5], buf[6]]);
                self.regs.insert(reg, v);
                self.writes.push((reg, v));
                self.ifcnt = self.ifcnt.wrapping_add(1);
            } else if buf.len() == 4 {
                let reg = buf[2] & 0x7F;
                let v = if reg == 0x02 { self.ifcnt as u32 } else { self.regs.get(&reg).copied().unwrap_or(0) };
                let b = v.to_be_bytes();
                let mut r = [0x05, 0xFF, reg, b[0], b[1], b[2], b[3], 0];
                r[7] = tmc5160_crc8(&r[..7]);
                self.out.extend(r.iter().copied());
            }
            Ok(buf.len())
        }
        fn flush(&mut self) -> Result<(), UErr> {
            Ok(())
        }
    }
    impl Read for UartSim {
        fn read(&mut self, buf: &mut [u8]) -> Result<usize, UErr> {
            let mut n = 0;
            while n < buf.len() {
                match self.out.pop_front() {
                    Some(b) => {
                        buf[n] = b;
                        n += 1;
                    }
                    None => break,
                }
            }
            Ok(n)
        }
    }

    struct NoDelay;
    impl DelayNs for NoDelay {
        fn delay_ns(&mut self, _ns: u32) {}
    }

    fn spi_min() -> Tmc5160Minimal<SpiBus<SpiSim>, NoPin, NoDelay> {
        Tmc5160Minimal::new_spi(SpiSim::new(), NoDelay, 0.075, None::<NoPin>).unwrap()
    }

    fn uart_sim() -> UartSim {
        let mut s = UartSim::default();
        s.regs.insert(0x04, 0x3000_0000);
        s
    }

    #[test]
    fn crc_known_vector() {
        assert_eq!(tmc5160_crc8(&[0x05, 0x00, 0x00]), 0x48);
    }

    #[test]
    fn spi_init_defaults() {
        let m = spi_min();
        let sim = &m.bus.spi;
        assert_eq!(sim.last(0x6C), Some(0x0001_00C3));
        assert_eq!(sim.last(0x00), Some(0x4));
        assert_eq!(sim.last(0x13), Some(500));
        assert_eq!(sim.last(0x01), Some(0x07));
        assert_eq!(sim.last(0x27), Some(71583));
        assert_eq!(sim.last(0x26), Some(1564));
    }

    #[test]
    fn rejects_wrong_chip_and_stepdir() {
        let mut s = SpiSim::new();
        s.regs.insert(0x04, 0x1100_0000);
        assert!(matches!(Tmc5160Minimal::new_spi(s, NoDelay, 0.075, None::<NoPin>), Err(Tmc5160Error::NotFound)));
        let mut s = SpiSim::new();
        s.regs.insert(0x04, 0x3000_0040);
        assert!(matches!(Tmc5160Minimal::new_spi(s, NoDelay, 0.075, None::<NoPin>), Err(Tmc5160Error::StepDirMode)));
    }

    #[test]
    fn motion_and_position() {
        let mut m = spi_min();
        m.move_to(400.0).unwrap();
        assert_eq!(m.bus.spi.last(0x2D), Some(400 * 256));
        assert!(m.wait_until_reached(Some(1.0)).unwrap());
        assert_eq!(m.position().unwrap(), 400.0);
        m.run(-200.0).unwrap();
        assert_eq!(m.bus.spi.last(0x20), Some(2));
        m.stop().unwrap();
        assert_eq!(m.bus.spi.last(0x27), Some(0));
        assert!(m.set_current(10.0).is_err());
        m.set_current(1.0).unwrap();
        assert!(m.set_speed(0.0).is_err());
    }

    #[test]
    fn full_ramp_and_uart_only() {
        let mut f: Tmc5160Full<SpiBus<SpiSim>, NoPin, NoDelay, NoPin, NoPin> =
            Tmc5160Full::new_spi(SpiSim::new(), NoDelay, 0.075, None::<NoPin>, None::<NoPin>, None::<NoPin>).unwrap();
        f.configure_ramp(0.0, 800.0, 400.0, 400.0, 200.0, 400.0, 800.0, 1.0).unwrap();
        assert_eq!(f.core.bus.spi.last(0x24), Some(3127));
        assert!(matches!(f.read_ifcnt(), Err(Tmc5160Error::UartOnly)));
        f.set_microsteps(16).unwrap();
        assert_eq!((f.core.bus.spi.last(0x6C).unwrap() >> 24) & 0xF, 4);
        assert!(f.set_microsteps(3).is_err());
        assert!(matches!(f.set_coil_currents(1, 1), Err(Tmc5160Error::DirectModeRequired)));
    }

    #[test]
    fn uart_init_and_ifcnt() {
        let m = Tmc5160Minimal::new_uart(uart_sim(), NoDelay, 0.075, None::<NoPin>, 0, true, false).unwrap();
        assert!(m.bus.uart.writes.iter().any(|w| *w == (0x6C, 0x0001_00C3)));
        let mut f: Tmc5160Full<UartBus<UartSim>, NoPin, NoDelay, NoPin, NoPin> =
            Tmc5160Full::new_uart(uart_sim(), NoDelay, 0.075, None::<NoPin>, 0, true, false).unwrap();
        let n = f.read_ifcnt().unwrap();
        assert!(n > 0);
        f.set_send_delay(2).unwrap();
        assert_eq!(f.core.bus.uart.regs[&0x03], 2 << 8);
    }
}
