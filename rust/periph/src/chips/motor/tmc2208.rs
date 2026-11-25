//! TMC2208 -- Trinamic TMC2202 / TMC2208 / TMC2224 stepper driver over single-wire UART.
//!
//! One driver covers all three parts (identical register set and UART
//! protocol). [`Tmc2208Minimal`] brings the driver up in UART mode with
//! StealthChop, 16 microsteps and 256-step interpolation and spins the motor
//! with the internal step generator (`VACTUAL`) -- no STEP pulses required.
//! [`Tmc2208Full`] adds raw register access, chopper/StealthChop tuning, GPIO
//! STEP/DIR pulsing and every status read-back.
//!
//! The driver is generic over `embedded_io::Read + Write` for the UART and over
//! `embedded_hal` [`OutputPin`] / [`DelayNs`] for ENN / STEP / DIR and waits.
//! Pass [`NoPin`] (`None::<NoPin>`) for pins that are not wired. Datagram
//! framing (sync byte, CRC8, host echo) is done here; the UART needs no
//! protocol knowledge.
//!
//! ```rust,ignore
//! use periph::chips::motor::{Tmc2208Minimal, NoPin};
//!
//! let mut motor = Tmc2208Minimal::new(uart, &mut delay, 0.11, None::<NoPin>, true)?;
//! motor.set_current(0.8)?;
//! motor.run(200.0)?;
//! ```

use embedded_hal::delay::DelayNs;
use embedded_hal::digital::OutputPin;
use embedded_io::{Read, Write};
use libm::{roundf, sqrtf};

const REG_GCONF: u8 = 0x00;
const REG_GSTAT: u8 = 0x01;
const REG_IFCNT: u8 = 0x02;
const REG_NODECONF: u8 = 0x03;
const REG_OTP_READ: u8 = 0x05;
const REG_IOIN: u8 = 0x06;
const REG_IHOLD_IRUN: u8 = 0x10;
const REG_TPOWERDOWN: u8 = 0x11;
const REG_TSTEP: u8 = 0x12;
const REG_TPWMTHRS: u8 = 0x13;
const REG_VACTUAL: u8 = 0x22;
const REG_MSCNT: u8 = 0x6A;
const REG_MSCURACT: u8 = 0x6B;
const REG_CHOPCONF: u8 = 0x6C;
const REG_DRV_STATUS: u8 = 0x6F;
const REG_PWMCONF: u8 = 0x70;
const REG_PWM_SCALE: u8 = 0x71;
const REG_PWM_AUTO: u8 = 0x72;

const READABLE: [u8; 14] = [0x00, 0x01, 0x02, 0x05, 0x06, 0x07, 0x12, 0x6A, 0x6B, 0x6C, 0x6F, 0x70, 0x71, 0x72];
const WRITABLE: [u8; 9] = [0x00, 0x01, 0x03, 0x10, 0x11, 0x13, 0x22, 0x6C, 0x70];

const GCONF_I_SCALE_ANALOG: u32 = 1 << 0;
const GCONF_INTERNAL_RSENSE: u32 = 1 << 1;
const GCONF_EN_SPREADCYCLE: u32 = 1 << 2;
const GCONF_SHAFT: u32 = 1 << 3;
const GCONF_INDEX_OTPW: u32 = 1 << 4;
const GCONF_INDEX_STEP: u32 = 1 << 5;
const GCONF_PDN_DISABLE: u32 = 1 << 6;
const GCONF_MSTEP_REG_SELECT: u32 = 1 << 7;
const GCONF_MULTISTEP_FILT: u32 = 1 << 8;

const PWMCONF_DEFAULT: u32 = 0xC10D_0024;

/// `DRV_STATUS.otpw` -- over-temperature pre-warning.
pub const TMC2208_FAULT_OVERTEMP_WARNING: u8 = 0x01;
/// `DRV_STATUS.ot` -- over-temperature shutdown.
pub const TMC2208_FAULT_OVERTEMP: u8 = 0x02;
/// `s2ga` / `s2gb` -- short to GND.
pub const TMC2208_FAULT_SHORT_GND: u8 = 0x04;
/// `s2vsa` / `s2vsb` -- low-side short.
pub const TMC2208_FAULT_SHORT_SUPPLY: u8 = 0x08;
/// `ola` / `olb` -- open load (informative only).
pub const TMC2208_FAULT_OPEN_LOAD: u8 = 0x10;

/// Placeholder for an unwired ENN / STEP / DIR pin (`None::<NoPin>`).
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

/// Errors from the TMC2208 drivers.
#[derive(Debug)]
pub enum Tmc2208Error<E> {
    /// The UART returned an error.
    Io(E),
    /// The UART returned no data within its timeout.
    Timeout,
    /// The echoed bytes did not match what was sent (bus collision).
    EchoMismatch,
    /// A reply failed its CRC8 check.
    Crc,
    /// A reply had a wrong sync byte, address or register.
    Malformed,
    /// `IOIN` did not identify a TMC220x / TMC222x.
    NotFound,
    /// `IFCNT` did not advance by the number of datagrams written.
    IfcntMismatch,
    /// An argument was out of range or the register is not accessible.
    InvalidArgument,
    /// The operation is unsupported in the current configuration.
    Unsupported,
    /// An ENN / STEP / DIR pin returned an error, or a required pin is missing.
    Pin,
}

/// Decoded `DRV_STATUS`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc2208Status {
    /// Standstill.
    pub stst: bool,
    /// StealthChop active (false = SpreadCycle).
    pub stealth: bool,
    /// Actual current scale 0..=31.
    pub cs_actual: u8,
    /// Over-temperature pre-warning.
    pub otpw: bool,
    /// Over-temperature shutdown.
    pub ot: bool,
    /// Short to GND on either phase.
    pub short_gnd: bool,
    /// Low-side short on either phase.
    pub short_supply: bool,
    /// Open load on either phase.
    pub open_load: bool,
    /// 120 degC comparator.
    pub t120: bool,
    /// 143 degC comparator.
    pub t143: bool,
    /// 150 degC comparator.
    pub t150: bool,
    /// 157 degC comparator.
    pub t157: bool,
}

/// Decoded `GSTAT`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc2208GlobalStatus {
    /// IC was reset since the last clear.
    pub reset: bool,
    /// Driver shut down by over-temperature or short.
    pub drv_err: bool,
    /// Charge-pump undervoltage.
    pub uv_cp: bool,
}

/// Decoded `IOIN` identity fields.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc2208Ioin {
    /// Raw 32-bit word.
    pub raw: u32,
    /// `SEL_A`: true for TMC2202 / TMC2208, false for TMC2224.
    pub sel_a: bool,
    /// Silicon version.
    pub version: u8,
}

/// StealthChop auto-tuning results (`PWM_SCALE` / `PWM_AUTO`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc2208PwmAuto {
    /// `PWM_SCALE_SUM`.
    pub pwm_scale_sum: u8,
    /// `PWM_SCALE_AUTO` (signed 9 bit).
    pub pwm_scale_auto: i16,
    /// `PWM_OFS_AUTO`.
    pub pwm_ofs_auto: u8,
    /// `PWM_GRAD_AUTO`.
    pub pwm_grad_auto: u8,
}

/// CRC-8 (polynomial 0x07, init 0, LSB first) over a datagram body.
pub fn tmc2208_crc8(data: &[u8]) -> u8 {
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

fn sign_extend(value: u32, bits: u32) -> i16 {
    let v = value & ((1u32 << bits) - 1);
    if v & (1 << (bits - 1)) != 0 {
        (v as i32 - (1i32 << bits)) as i16
    } else {
        v as i16
    }
}

/// TMC2208 -- minimal interface: current, internal-generator motion, faults.
pub struct Tmc2208Minimal<U, EN> {
    uart: U,
    en_pin: Option<EN>,
    r_sense: f32,
    echo: bool,
    fclk_hz: u32,
    expect_sel_a: bool,
    gconf: u32,
    chopconf: u32,
    pwmconf: u32,
    microsteps: u16,
    vsense: bool,
    internal_rsense: bool,
    toff: u8,
    run_cs: u8,
    hold_cs: u8,
    ihold_delay: u8,
}

impl<U: Read + Write, EN: OutputPin> Tmc2208Minimal<U, EN> {
    /// Construct the driver, probe the chip and apply the default configuration.
    ///
    /// `r_sense_ohm` is the external sense resistor (0.11 ohm on common boards);
    /// `echo` is true when TX and RX share `PDN_UART`. With an `en_pin` the
    /// power stage stays off until [`enable`](Self::enable).
    pub fn new<D: DelayNs>(
        uart: U,
        delay: &mut D,
        r_sense_ohm: f32,
        en_pin: Option<EN>,
        echo: bool,
    ) -> Result<Self, Tmc2208Error<U::Error>> {
        Self::with_options(uart, delay, r_sense_ohm, en_pin, echo, 12_000_000, true)
    }

    pub(crate) fn with_options<D: DelayNs>(
        uart: U,
        delay: &mut D,
        r_sense_ohm: f32,
        en_pin: Option<EN>,
        echo: bool,
        fclk_hz: u32,
        expect_sel_a: bool,
    ) -> Result<Self, Tmc2208Error<U::Error>> {
        let mut s = Self {
            uart,
            en_pin,
            r_sense: r_sense_ohm,
            echo,
            fclk_hz,
            expect_sel_a,
            gconf: 0,
            chopconf: 0,
            pwmconf: 0,
            microsteps: 16,
            vsense: false,
            internal_rsense: false,
            toff: 3,
            run_cs: 0,
            hold_cs: 0,
            ihold_delay: 6,
        };
        s.initialise(delay)?;
        Ok(s)
    }

    fn io_write(&mut self, data: &[u8]) -> Result<(), Tmc2208Error<U::Error>> {
        self.uart.write_all(data).map_err(Tmc2208Error::Io)?;
        self.uart.flush().map_err(Tmc2208Error::Io)
    }

    fn io_read(&mut self, buf: &mut [u8]) -> Result<(), Tmc2208Error<U::Error>> {
        let mut filled = 0;
        while filled < buf.len() {
            let n = self.uart.read(&mut buf[filled..]).map_err(Tmc2208Error::Io)?;
            if n == 0 {
                return Err(Tmc2208Error::Timeout);
            }
            filled += n;
        }
        Ok(())
    }

    fn write_datagram(&mut self, reg: u8, value: u32) -> Result<(), Tmc2208Error<U::Error>> {
        let v = value.to_be_bytes();
        let mut dg = [0x05, 0x00, reg | 0x80, v[0], v[1], v[2], v[3], 0];
        dg[7] = tmc2208_crc8(&dg[..7]);
        self.io_write(&dg)?;
        if self.echo {
            let mut echoed = [0u8; 8];
            self.io_read(&mut echoed)?;
            if echoed != dg {
                return Err(Tmc2208Error::EchoMismatch);
            }
        }
        Ok(())
    }

    fn read_datagram(&mut self, reg: u8) -> Result<u32, Tmc2208Error<U::Error>> {
        let mut req = [0x05, 0x00, reg & 0x7F, 0];
        req[3] = tmc2208_crc8(&req[..3]);
        self.io_write(&req)?;
        if self.echo {
            let mut echoed = [0u8; 4];
            self.io_read(&mut echoed)?;
            if echoed != req {
                return Err(Tmc2208Error::EchoMismatch);
            }
        }
        let mut reply = [0u8; 8];
        self.io_read(&mut reply)?;
        if reply[0] != 0x05 || reply[1] != 0xFF || reply[2] != (reg & 0x7F) {
            return Err(Tmc2208Error::Malformed);
        }
        if tmc2208_crc8(&reply[..7]) != reply[7] {
            return Err(Tmc2208Error::Crc);
        }
        Ok(u32::from_be_bytes([reply[3], reply[4], reply[5], reply[6]]))
    }

    fn write_reg(&mut self, reg: u8, value: u32) -> Result<(), Tmc2208Error<U::Error>> {
        if self.write_datagram(reg, value).is_err() {
            // Chip needs >= 12 bit times idle after an error; retry once.
            self.write_datagram(reg, value)?;
        }
        match reg {
            REG_GCONF => self.gconf = value,
            REG_CHOPCONF => self.chopconf = value,
            REG_PWMCONF => self.pwmconf = value,
            _ => {}
        }
        Ok(())
    }

    fn read_reg(&mut self, reg: u8) -> Result<u32, Tmc2208Error<U::Error>> {
        match self.read_datagram(reg) {
            Ok(v) => Ok(v),
            Err(_) => self.read_datagram(reg),
        }
    }

    fn modify(&mut self, reg: u8, mask: u32, value: u32) -> Result<(), Tmc2208Error<U::Error>> {
        let current = match reg {
            REG_GCONF => self.gconf,
            REG_CHOPCONF => self.chopconf,
            _ => self.pwmconf,
        };
        self.write_reg(reg, (current & !mask) | (value & mask))
    }

    fn current_to_cs(&self, rms_a: f32) -> Result<u8, Tmc2208Error<U::Error>> {
        if self.internal_rsense {
            return Err(Tmc2208Error::Unsupported);
        }
        let vfs = if self.vsense { 0.180 } else { 0.325 };
        let cs = roundf(rms_a * sqrtf(2.0) * (self.r_sense + 0.030) * 32.0 / vfs) as i32 - 1;
        Ok(cs.clamp(0, 31) as u8)
    }

    fn write_ihold_irun(&mut self) -> Result<(), Tmc2208Error<U::Error>> {
        let v = (self.hold_cs as u32 & 0x1F) | ((self.run_cs as u32 & 0x1F) << 8) | ((self.ihold_delay as u32 & 0xF) << 16);
        self.write_reg(REG_IHOLD_IRUN, v)
    }

    fn initialise<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Tmc2208Error<U::Error>> {
        if let Some(p) = self.en_pin.as_mut() {
            p.set_high().map_err(|_| Tmc2208Error::Pin)?;
        }
        delay.delay_ms(10);
        let baseline = (self.read_reg(REG_IFCNT)? & 0xFF) as u8;
        let ioin = self.read_reg(REG_IOIN)?;
        let version = (ioin >> 24) as u8;
        let sel_a = ioin & (1 << 8) != 0;
        if version == 0x00 || version == 0xFF || sel_a != self.expect_sel_a {
            return Err(Tmc2208Error::NotFound);
        }
        self.write_reg(REG_GCONF, GCONF_I_SCALE_ANALOG | GCONF_PDN_DISABLE | GCONF_MSTEP_REG_SELECT | GCONF_MULTISTEP_FILT)?;
        self.write_reg(REG_GSTAT, 0x07)?;
        self.run_cs = self.current_to_cs(0.5)?;
        self.hold_cs = self.current_to_cs(0.5 / 3.0)?;
        self.write_ihold_irun()?;
        self.write_reg(REG_TPOWERDOWN, 20)?;
        let mut chop = self.read_reg(REG_CHOPCONF)?;
        chop &= !(0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (0xF << 24) | (1 << 17));
        chop |= 3 | (2 << 15) | (5 << 4) | (4 << 24) | (1 << 28);
        self.write_reg(REG_CHOPCONF, chop)?;
        self.write_reg(REG_PWMCONF, PWMCONF_DEFAULT)?;
        let ifcnt = (self.read_reg(REG_IFCNT)? & 0xFF) as u8;
        if ifcnt != baseline.wrapping_add(6) {
            return Err(Tmc2208Error::IfcntMismatch);
        }
        Ok(())
    }

    /// Switch the power stage on (ENN low, or `TOFF` restored without a pin).
    pub fn enable(&mut self) -> Result<(), Tmc2208Error<U::Error>> {
        if let Some(p) = self.en_pin.as_mut() {
            p.set_low().map_err(|_| Tmc2208Error::Pin)
        } else {
            let v = (self.chopconf & !0xF) | self.toff as u32;
            self.write_reg(REG_CHOPCONF, v)
        }
    }

    /// Switch the power stage off; the motor freewheels.
    pub fn disable(&mut self) -> Result<(), Tmc2208Error<U::Error>> {
        if let Some(p) = self.en_pin.as_mut() {
            return p.set_high().map_err(|_| Tmc2208Error::Pin);
        }
        if self.chopconf & 0xF != 0 {
            self.toff = (self.chopconf & 0xF) as u8;
        }
        let v = self.chopconf & !0xF;
        self.write_reg(REG_CHOPCONF, v)
    }

    /// Set the run current in A RMS (at most 1.2); hold current becomes one third of it.
    ///
    /// Returns [`Tmc2208Error::Unsupported`] when the internal sense resistors are in use.
    pub fn set_current(&mut self, rms_a: f32) -> Result<(), Tmc2208Error<U::Error>> {
        self.run_cs = self.current_to_cs(rms_a)?;
        self.hold_cs = self.current_to_cs(rms_a / 3.0)?;
        self.write_ihold_irun()
    }

    /// Move with the internal step generator (`VACTUAL`). `steps_per_s` is in full steps per
    /// second at the configured microstepping; the sign gives the direction, 0 stops.
    pub fn run(&mut self, steps_per_s: f32) -> Result<(), Tmc2208Error<U::Error>> {
        let v = roundf(steps_per_s * self.microsteps as f32 * 16_777_216.0 / self.fclk_hz as f32) as i32;
        let v = v.clamp(-8_388_607, 8_388_607);
        self.write_reg(REG_VACTUAL, (v as u32) & 0xFF_FFFF)
    }

    /// Stop the internal step generator (`VACTUAL` = 0).
    pub fn stop(&mut self) -> Result<(), Tmc2208Error<U::Error>> {
        self.write_reg(REG_VACTUAL, 0)
    }

    /// True when `DRV_STATUS.stst` is set.
    pub fn is_standstill(&mut self) -> Result<bool, Tmc2208Error<U::Error>> {
        Ok(self.read_reg(REG_DRV_STATUS)? >> 31 == 1)
    }

    /// Bitmask of `TMC2208_FAULT_*` constants, 0 when none.
    pub fn read_fault(&mut self) -> Result<u8, Tmc2208Error<U::Error>> {
        let s = self.read_reg(REG_DRV_STATUS)?;
        let mut f = 0;
        if s & 0x01 != 0 {
            f |= TMC2208_FAULT_OVERTEMP_WARNING;
        }
        if s & 0x02 != 0 {
            f |= TMC2208_FAULT_OVERTEMP;
        }
        if s & 0x0C != 0 {
            f |= TMC2208_FAULT_SHORT_GND;
        }
        if s & 0x30 != 0 {
            f |= TMC2208_FAULT_SHORT_SUPPLY;
        }
        if s & 0xC0 != 0 {
            f |= TMC2208_FAULT_OPEN_LOAD;
        }
        Ok(f)
    }
}

/// TMC2208 -- complete interface. Wraps a [`Tmc2208Minimal`] (composition).
pub struct Tmc2208Full<U, EN, STEP, DIR> {
    core: Tmc2208Minimal<U, EN>,
    step_pin: Option<STEP>,
    dir_pin: Option<DIR>,
}

impl<U: Read + Write, EN: OutputPin, STEP: OutputPin, DIR: OutputPin> Tmc2208Full<U, EN, STEP, DIR> {
    /// Construct the driver, probe the chip and apply the default configuration.
    ///
    /// `fclk_hz` is the chip clock (12 MHz internal); `tmc2224` selects the
    /// TMC2224 identity (`IOIN.SEL_A` = 0).
    #[allow(clippy::too_many_arguments)]
    pub fn new<D: DelayNs>(
        uart: U,
        delay: &mut D,
        r_sense_ohm: f32,
        en_pin: Option<EN>,
        echo: bool,
        step_pin: Option<STEP>,
        dir_pin: Option<DIR>,
        fclk_hz: u32,
        tmc2224: bool,
    ) -> Result<Self, Tmc2208Error<U::Error>> {
        let core = Tmc2208Minimal::with_options(uart, delay, r_sense_ohm, en_pin, echo, fclk_hz, !tmc2224)?;
        Ok(Self { core, step_pin, dir_pin })
    }

    /// Switch the power stage on.
    pub fn enable(&mut self) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.enable()
    }

    /// Switch the power stage off.
    pub fn disable(&mut self) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.disable()
    }

    /// Set the run current (A RMS); hold current becomes one third.
    pub fn set_current(&mut self, rms_a: f32) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.set_current(rms_a)
    }

    /// Move with the internal step generator (full steps per second, signed).
    pub fn run(&mut self, steps_per_s: f32) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.run(steps_per_s)
    }

    /// Stop the internal step generator.
    pub fn stop(&mut self) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.stop()
    }

    /// True when the motor is at standstill.
    pub fn is_standstill(&mut self) -> Result<bool, Tmc2208Error<U::Error>> {
        self.core.is_standstill()
    }

    /// Bitmask of `TMC2208_FAULT_*` constants, 0 when none.
    pub fn read_fault(&mut self) -> Result<u8, Tmc2208Error<U::Error>> {
        self.core.read_fault()
    }

    /// Raw 32-bit register read; write-only and reserved registers return [`Tmc2208Error::InvalidArgument`].
    pub fn read_register(&mut self, reg: u8) -> Result<u32, Tmc2208Error<U::Error>> {
        if !READABLE.contains(&reg) {
            return Err(Tmc2208Error::InvalidArgument);
        }
        self.core.read_reg(reg)
    }

    /// Raw 32-bit register write; read-only registers, `OTP_PROG` and `FACTORY_CONF` are rejected.
    pub fn write_register(&mut self, reg: u8, value: u32) -> Result<(), Tmc2208Error<U::Error>> {
        if !WRITABLE.contains(&reg) {
            return Err(Tmc2208Error::InvalidArgument);
        }
        self.core.write_reg(reg, value)
    }

    /// Independent hold current in A RMS.
    pub fn set_hold_current(&mut self, rms_a: f32) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.hold_cs = self.core.current_to_cs(rms_a)?;
        self.core.write_ihold_irun()
    }

    /// `IHOLDDELAY` 0..=15 and `TPOWERDOWN` 0..=255.
    pub fn set_current_delay(&mut self, ihold_delay: u8, tpowerdown: u8) -> Result<(), Tmc2208Error<U::Error>> {
        if ihold_delay > 15 {
            return Err(Tmc2208Error::InvalidArgument);
        }
        self.core.ihold_delay = ihold_delay;
        self.core.write_ihold_irun()?;
        self.core.write_reg(REG_TPOWERDOWN, tpowerdown as u32)
    }

    /// Microstep resolution: 1, 2, 4, ..., 256.
    pub fn set_microsteps(&mut self, n: u16) -> Result<(), Tmc2208Error<U::Error>> {
        let mres: u32 = match n {
            256 => 0,
            128 => 1,
            64 => 2,
            32 => 3,
            16 => 4,
            8 => 5,
            4 => 6,
            2 => 7,
            1 => 8,
            _ => return Err(Tmc2208Error::InvalidArgument),
        };
        self.core.modify(REG_CHOPCONF, 0xF << 24, mres << 24)?;
        self.core.microsteps = n;
        Ok(())
    }

    /// `CHOPCONF.intpol`.
    pub fn set_interpolation(&mut self, enabled: bool) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.modify(REG_CHOPCONF, 1 << 28, if enabled { 1 << 28 } else { 0 })
    }

    /// `GCONF.en_SpreadCycle`: true = SpreadCycle, false = StealthChop.
    pub fn set_chopper_mode(&mut self, spreadcycle: bool) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.modify(REG_GCONF, GCONF_EN_SPREADCYCLE, if spreadcycle { GCONF_EN_SPREADCYCLE } else { 0 })
    }

    /// `TPWMTHRS` (20 bit); 0 disables the threshold.
    pub fn set_stealthchop_threshold(&mut self, tpwmthrs: u32) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.write_reg(REG_TPWMTHRS, tpwmthrs & 0xF_FFFF)
    }

    /// `CHOPCONF` timing fields; `hend` is the raw field 0..=15 (offset -3).
    pub fn configure_chopper(&mut self, toff: u8, tbl: u8, hstrt: u8, hend: u8) -> Result<(), Tmc2208Error<U::Error>> {
        let mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7);
        let value = (toff as u32 & 0xF) | ((tbl as u32 & 3) << 15) | ((hstrt as u32 & 7) << 4) | ((hend as u32 & 0xF) << 7);
        if toff != 0 {
            self.core.toff = toff & 0xF;
        }
        self.core.modify(REG_CHOPCONF, mask, value)
    }

    /// `PWMCONF` StealthChop fields.
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
    ) -> Result<(), Tmc2208Error<U::Error>> {
        let mask = (0xF << 28) | (0xF << 24) | (1 << 19) | (1 << 18) | (3 << 16) | (0xFF << 8) | 0xFF;
        let value = ((pwm_lim as u32 & 0xF) << 28)
            | ((pwm_reg as u32 & 0xF) << 24)
            | if autograd { 1 << 19 } else { 0 }
            | if autoscale { 1 << 18 } else { 0 }
            | ((pwm_freq as u32 & 3) << 16)
            | ((pwm_grad as u32) << 8)
            | pwm_ofs as u32;
        self.core.modify(REG_PWMCONF, mask, value)
    }

    /// `PWMCONF.freewheel` 0..=3.
    pub fn set_freewheel(&mut self, mode: u8) -> Result<(), Tmc2208Error<U::Error>> {
        if mode > 3 {
            return Err(Tmc2208Error::InvalidArgument);
        }
        self.core.modify(REG_PWMCONF, 3 << 20, (mode as u32) << 20)
    }

    /// `CHOPCONF.vsense` (also changes the current conversion).
    pub fn set_vsense(&mut self, high_sensitivity: bool) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.modify(REG_CHOPCONF, 1 << 17, if high_sensitivity { 1 << 17 } else { 0 })?;
        self.core.vsense = high_sensitivity;
        Ok(())
    }

    /// `GCONF.internal_Rsense` ([`set_current`](Self::set_current) then fails).
    pub fn set_internal_rsense(&mut self, enabled: bool) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.modify(REG_GCONF, GCONF_INTERNAL_RSENSE, if enabled { GCONF_INTERNAL_RSENSE } else { 0 })?;
        self.core.internal_rsense = enabled;
        Ok(())
    }

    /// `GCONF.I_scale_analog`.
    pub fn set_analog_scaling(&mut self, enabled: bool) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.modify(REG_GCONF, GCONF_I_SCALE_ANALOG, if enabled { GCONF_I_SCALE_ANALOG } else { 0 })
    }

    /// `GCONF.shaft`.
    pub fn set_direction_inverted(&mut self, inverted: bool) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.modify(REG_GCONF, GCONF_SHAFT, if inverted { GCONF_SHAFT } else { 0 })
    }

    /// INDEX output: 0 first microstep, 1 `otpw`, 2 step pulses.
    pub fn set_index_output(&mut self, mode: u8) -> Result<(), Tmc2208Error<U::Error>> {
        let bits = match mode {
            0 => 0,
            1 => GCONF_INDEX_OTPW,
            2 => GCONF_INDEX_STEP,
            _ => return Err(Tmc2208Error::InvalidArgument),
        };
        self.core.modify(REG_GCONF, GCONF_INDEX_OTPW | GCONF_INDEX_STEP, bits)
    }

    /// Keep (true) or disable (false) the low-side-short / short-to-GND protections.
    pub fn set_driver_protection(&mut self, short_ls: bool, short_gnd: bool) -> Result<(), Tmc2208Error<U::Error>> {
        let value = (if short_ls { 0 } else { 1u32 << 31 }) | (if short_gnd { 0 } else { 1 << 30 });
        self.core.modify(REG_CHOPCONF, (1u32 << 31) | (1 << 30), value)
    }

    /// `CHOPCONF.dedge`.
    pub fn set_double_edge(&mut self, enabled: bool) -> Result<(), Tmc2208Error<U::Error>> {
        self.core.modify(REG_CHOPCONF, 1 << 29, if enabled { 1 << 29 } else { 0 })
    }

    /// `NODECONF.SENDDELAY` 0..=15.
    pub fn set_send_delay(&mut self, n: u8) -> Result<(), Tmc2208Error<U::Error>> {
        if n > 15 {
            return Err(Tmc2208Error::InvalidArgument);
        }
        self.core.write_reg(REG_NODECONF, (n as u32) << 8)
    }

    /// Emit `count.abs()` STEP pulses (negative reverses via DIR); each pulse is high and low for `delay_us`.
    pub fn step<D: DelayNs>(&mut self, count: i32, delay_us: u32, delay: &mut D) -> Result<(), Tmc2208Error<U::Error>> {
        let step = self.step_pin.as_mut().ok_or(Tmc2208Error::Pin)?;
        if let Some(dir) = self.dir_pin.as_mut() {
            if count >= 0 {
                dir.set_high().map_err(|_| Tmc2208Error::Pin)?;
            } else {
                dir.set_low().map_err(|_| Tmc2208Error::Pin)?;
            }
        } else if count < 0 {
            return Err(Tmc2208Error::Pin);
        }
        for _ in 0..count.unsigned_abs() {
            step.set_high().map_err(|_| Tmc2208Error::Pin)?;
            delay.delay_us(delay_us);
            step.set_low().map_err(|_| Tmc2208Error::Pin)?;
            delay.delay_us(delay_us);
        }
        Ok(())
    }

    /// `MSCNT` 0..=1023.
    pub fn microstep_position(&mut self) -> Result<u16, Tmc2208Error<U::Error>> {
        Ok((self.core.read_reg(REG_MSCNT)? & 0x3FF) as u16)
    }

    /// `DRV_STATUS.CS_ACTUAL` 0..=31.
    pub fn read_current_scale(&mut self) -> Result<u8, Tmc2208Error<U::Error>> {
        Ok(((self.core.read_reg(REG_DRV_STATUS)? >> 16) & 0x1F) as u8)
    }

    /// Signed `MSCURACT` table values in register order.
    pub fn read_coil_currents(&mut self) -> Result<(i16, i16), Tmc2208Error<U::Error>> {
        let raw = self.core.read_reg(REG_MSCURACT)?;
        Ok((sign_extend(raw, 9), sign_extend(raw >> 16, 9)))
    }

    /// `TSTEP` (20 bit).
    pub fn read_tstep(&mut self) -> Result<u32, Tmc2208Error<U::Error>> {
        Ok(self.core.read_reg(REG_TSTEP)? & 0xF_FFFF)
    }

    /// `DRV_STATUS` t120..t157 as bits 0..=3.
    pub fn read_temperature_flags(&mut self) -> Result<u8, Tmc2208Error<U::Error>> {
        Ok(((self.core.read_reg(REG_DRV_STATUS)? >> 8) & 0xF) as u8)
    }

    /// Decoded `DRV_STATUS`.
    pub fn read_status(&mut self) -> Result<Tmc2208Status, Tmc2208Error<U::Error>> {
        let s = self.core.read_reg(REG_DRV_STATUS)?;
        Ok(Tmc2208Status {
            stst: s >> 31 & 1 == 1,
            stealth: s >> 30 & 1 == 1,
            cs_actual: ((s >> 16) & 0x1F) as u8,
            otpw: s & 0x01 != 0,
            ot: s & 0x02 != 0,
            short_gnd: s & 0x0C != 0,
            short_supply: s & 0x30 != 0,
            open_load: s & 0xC0 != 0,
            t120: s & (1 << 8) != 0,
            t143: s & (1 << 9) != 0,
            t150: s & (1 << 10) != 0,
            t157: s & (1 << 11) != 0,
        })
    }

    /// Decoded `GSTAT`; `clear` writes the flags back.
    pub fn read_global_status(&mut self, clear: bool) -> Result<Tmc2208GlobalStatus, Tmc2208Error<U::Error>> {
        let g = self.core.read_reg(REG_GSTAT)?;
        if clear {
            self.core.write_reg(REG_GSTAT, 0x07)?;
        }
        Ok(Tmc2208GlobalStatus { reset: g & 1 != 0, drv_err: g & 2 != 0, uv_cp: g & 4 != 0 })
    }

    /// `IFCNT` write counter.
    pub fn read_ifcnt(&mut self) -> Result<u8, Tmc2208Error<U::Error>> {
        Ok((self.core.read_reg(REG_IFCNT)? & 0xFF) as u8)
    }

    /// `IOIN` raw word, `SEL_A` and `VERSION`.
    pub fn read_ioin(&mut self) -> Result<Tmc2208Ioin, Tmc2208Error<U::Error>> {
        let raw = self.core.read_reg(REG_IOIN)?;
        Ok(Tmc2208Ioin { raw, sel_a: raw & (1 << 8) != 0, version: (raw >> 24) as u8 })
    }

    /// `OTP_READ` 24-bit word.
    pub fn read_otp(&mut self) -> Result<u32, Tmc2208Error<U::Error>> {
        Ok(self.core.read_reg(REG_OTP_READ)? & 0xFF_FFFF)
    }

    /// `PWM_SCALE` / `PWM_AUTO` results.
    pub fn read_pwm_auto(&mut self) -> Result<Tmc2208PwmAuto, Tmc2208Error<U::Error>> {
        let scale = self.core.read_reg(REG_PWM_SCALE)?;
        let auto = self.core.read_reg(REG_PWM_AUTO)?;
        Ok(Tmc2208PwmAuto {
            pwm_scale_sum: (scale & 0xFF) as u8,
            pwm_scale_auto: sign_extend(scale >> 16, 9),
            pwm_ofs_auto: (auto & 0xFF) as u8,
            pwm_grad_auto: ((auto >> 16) & 0xFF) as u8,
        })
    }
}

#[cfg(all(test, feature = "std"))]
mod tests {
    use super::*;
    use crate::connection::uart_mock::UartMock;

    struct NoDelay;
    impl DelayNs for NoDelay {
        fn delay_ns(&mut self, _ns: u32) {}
    }

    #[derive(Default)]
    struct TestPin {
        levels: std::vec::Vec<bool>,
    }
    impl embedded_hal::digital::ErrorType for TestPin {
        type Error = core::convert::Infallible;
    }
    impl OutputPin for TestPin {
        fn set_low(&mut self) -> Result<(), Self::Error> {
            self.levels.push(false);
            Ok(())
        }
        fn set_high(&mut self) -> Result<(), Self::Error> {
            self.levels.push(true);
            Ok(())
        }
    }

    fn minimal(mock: UartMock) -> Tmc2208Minimal<UartMock, NoPin> {
        Tmc2208Minimal::new(mock, &mut NoDelay, 0.11, None::<NoPin>, true).unwrap()
    }

    fn full(mock: UartMock) -> Tmc2208Full<UartMock, NoPin, NoPin, NoPin> {
        Tmc2208Full::new(mock, &mut NoDelay, 0.11, None::<NoPin>, true, None::<NoPin>, None::<NoPin>, 12_000_000, false).unwrap()
    }

    #[test]
    fn crc_vectors() {
        assert_eq!(tmc2208_crc8(&[5, 0, 0x80, 0, 0, 1, 0xC1]), 0x7F);
        assert_eq!(tmc2208_crc8(&[5, 0, 6]), 0x6F);
    }

    #[test]
    fn init_writes_defaults() {
        let m = minimal(UartMock::new(true));
        let u = &m.uart;
        assert_eq!(u.last_write(0x00), Some(0x1C1));
        assert_eq!(u.last_write(0x01), Some(0x07));
        assert_eq!(u.last_write(0x11), Some(20));
        assert_eq!(u.last_write(0x70), Some(0xC10D_0024));
        assert!(u.datagrams.contains(&vec![5, 0, 0x80, 0, 0, 1, 0xC1, 0x7F]));
        assert!(u.datagrams.contains(&vec![5, 0, 6, 0x6F]));
        let chop = u.last_write(0x6C).unwrap();
        assert_eq!(chop & 0xF, 3);
        assert_eq!((chop >> 15) & 3, 2);
        assert_eq!((chop >> 4) & 7, 5);
        assert_eq!((chop >> 24) & 0xF, 4);
        assert_eq!((chop >> 28) & 1, 1);
        let ih = u.last_write(0x10).unwrap();
        assert_eq!((ih >> 8) & 0x1F, 9);
        assert_eq!(ih & 0x1F, 2);
        assert_eq!((ih >> 16) & 0xF, 6);
    }

    #[test]
    fn current_and_motion() {
        let mut m = minimal(UartMock::new(true));
        m.set_current(1.0).unwrap();
        assert_eq!((m.uart.last_write(0x10).unwrap() >> 8) & 0x1F, 18);
        m.set_current(5.0).unwrap();
        assert_eq!((m.uart.last_write(0x10).unwrap() >> 8) & 0x1F, 31);
        m.run(200.0).unwrap();
        assert_eq!(m.uart.last_write(0x22), Some(4474));
        m.run(-200.0).unwrap();
        assert_eq!(m.uart.last_write(0x22), Some((-4474i32 as u32) & 0xFF_FFFF));
        m.stop().unwrap();
        assert_eq!(m.uart.last_write(0x22), Some(0));
    }

    #[test]
    fn enable_disable_without_pin_toggles_toff() {
        let mut m = minimal(UartMock::new(true));
        m.disable().unwrap();
        assert_eq!(m.uart.last_write(0x6C).unwrap() & 0xF, 0);
        m.enable().unwrap();
        assert_eq!(m.uart.last_write(0x6C).unwrap() & 0xF, 3);
    }

    #[test]
    fn en_pin_high_at_init_low_on_enable() {
        let mut m = Tmc2208Minimal::new(UartMock::new(true), &mut NoDelay, 0.11, Some(TestPin::default()), true).unwrap();
        assert_eq!(m.en_pin.as_ref().unwrap().levels, vec![true]);
        m.enable().unwrap();
        assert_eq!(m.en_pin.as_ref().unwrap().levels.last(), Some(&false));
    }

    #[test]
    fn status_and_faults() {
        let mut m = minimal(UartMock::new(true));
        m.uart.regs.insert(0x6F, (1 << 31) | 0x01 | 0x08 | 0x20);
        assert!(m.is_standstill().unwrap());
        assert_eq!(m.read_fault().unwrap(), TMC2208_FAULT_OVERTEMP_WARNING | TMC2208_FAULT_SHORT_GND | TMC2208_FAULT_SHORT_SUPPLY);
    }

    #[test]
    fn no_echo_and_errors() {
        let m = Tmc2208Minimal::new(UartMock::new(false), &mut NoDelay, 0.11, None::<NoPin>, false).unwrap();
        assert_eq!(m.uart.last_write(0x00), Some(0x1C1));

        let mut bad = UartMock::new(true);
        bad.regs.insert(0x06, 0);
        assert!(matches!(Tmc2208Minimal::new(bad, &mut NoDelay, 0.11, None::<NoPin>, true), Err(Tmc2208Error::NotFound)));

        let mut flaky = UartMock::new(true);
        flaky.corrupt_next_reply = true;
        let m = minimal(flaky);
        assert_eq!(m.uart.last_write(0x00), Some(0x1C1));
    }

    #[test]
    fn full_configuration() {
        let mut f = full(UartMock::new(true));
        f.set_microsteps(256).unwrap();
        assert_eq!((f.core.uart.last_write(0x6C).unwrap() >> 24) & 0xF, 0);
        f.set_microsteps(1).unwrap();
        assert_eq!((f.core.uart.last_write(0x6C).unwrap() >> 24) & 0xF, 8);
        assert!(matches!(f.set_microsteps(3), Err(Tmc2208Error::InvalidArgument)));
        f.set_microsteps(32).unwrap();
        f.run(100.0).unwrap();
        assert_eq!(f.core.uart.last_write(0x22), Some(4474));
        f.set_interpolation(false).unwrap();
        assert_eq!((f.core.uart.last_write(0x6C).unwrap() >> 28) & 1, 0);
        f.set_chopper_mode(true).unwrap();
        assert_eq!(f.core.uart.last_write(0x00).unwrap() & 0x4, 0x4);
        f.set_direction_inverted(true).unwrap();
        assert_eq!(f.core.uart.last_write(0x00).unwrap() & 0x8, 0x8);
        f.set_index_output(1).unwrap();
        assert_eq!(f.core.uart.last_write(0x00).unwrap() & 0x30, 0x10);
        f.set_double_edge(true).unwrap();
        assert_eq!((f.core.uart.last_write(0x6C).unwrap() >> 29) & 1, 1);
        f.set_driver_protection(false, true).unwrap();
        let c = f.core.uart.last_write(0x6C).unwrap();
        assert_eq!((c >> 31) & 1, 1);
        assert_eq!((c >> 30) & 1, 0);
        f.set_vsense(true).unwrap();
        assert_eq!((f.core.uart.last_write(0x6C).unwrap() >> 17) & 1, 1);
        f.set_hold_current(0.2).unwrap();
        assert_eq!(f.core.uart.last_write(0x10).unwrap() & 0x1F, 6);
        f.set_current_delay(2, 100).unwrap();
        assert_eq!((f.core.uart.last_write(0x10).unwrap() >> 16) & 0xF, 2);
        assert_eq!(f.core.uart.last_write(0x11), Some(100));
        f.set_stealthchop_threshold(300).unwrap();
        assert_eq!(f.core.uart.last_write(0x13), Some(300));
        f.configure_chopper(4, 1, 3, 2).unwrap();
        let c = f.core.uart.last_write(0x6C).unwrap();
        assert_eq!((c & 0xF, (c >> 15) & 3, (c >> 4) & 7, (c >> 7) & 0xF), (4, 1, 3, 2));
        f.configure_stealthchop(true, false, 40, 5, 1, 4, 12).unwrap();
        let p = f.core.uart.last_write(0x70).unwrap();
        assert_eq!((p & 0xFF, (p >> 8) & 0xFF, (p >> 16) & 3), (40, 5, 1));
        assert_eq!(((p >> 18) & 1, (p >> 19) & 1, (p >> 24) & 0xF, p >> 28), (1, 0, 4, 12));
        f.set_freewheel(1).unwrap();
        assert_eq!((f.core.uart.last_write(0x70).unwrap() >> 20) & 3, 1);
        f.set_send_delay(4).unwrap();
        assert_eq!(f.core.uart.last_write(0x03), Some(4 << 8));
        assert!(matches!(f.set_send_delay(16), Err(Tmc2208Error::InvalidArgument)));
        f.set_internal_rsense(true).unwrap();
        assert!(matches!(f.set_current(0.5), Err(Tmc2208Error::Unsupported)));
        f.set_internal_rsense(false).unwrap();
    }

    #[test]
    fn full_readbacks() {
        let mut f = full(UartMock::new(true));
        let regs = &mut f.core.uart.regs;
        regs.insert(0x6A, 513);
        regs.insert(0x6B, (0x1FF << 16) | 0x100);
        regs.insert(0x12, 1234);
        regs.insert(0x6F, (1 << 31) | (1 << 30) | (17 << 16) | (0x5 << 8) | 0x02);
        regs.insert(0x71, (0x1FE << 16) | 77);
        regs.insert(0x72, (9 << 16) | 33);
        regs.insert(0x01, 0x03);
        regs.insert(0x05, 0x123456);
        assert_eq!(f.microstep_position().unwrap(), 513);
        assert_eq!(f.read_coil_currents().unwrap(), (-256, -1));
        assert_eq!(f.read_tstep().unwrap(), 1234);
        assert_eq!(f.read_current_scale().unwrap(), 17);
        assert_eq!(f.read_temperature_flags().unwrap(), 5);
        let st = f.read_status().unwrap();
        assert!(st.stst && st.stealth && st.ot && st.t120 && st.t150 && !st.t143);
        assert_eq!(st.cs_actual, 17);
        assert_eq!(f.read_global_status(false).unwrap(), Tmc2208GlobalStatus { reset: true, drv_err: true, uv_cp: false });
        f.read_global_status(true).unwrap();
        assert_eq!(f.core.uart.last_write(0x01), Some(0x07));
        assert_eq!(
            f.read_pwm_auto().unwrap(),
            Tmc2208PwmAuto { pwm_scale_sum: 77, pwm_scale_auto: -2, pwm_ofs_auto: 33, pwm_grad_auto: 9 }
        );
        let io = f.read_ioin().unwrap();
        assert!(io.sel_a);
        assert_eq!(io.version, 0x21);
        assert_eq!(f.read_otp().unwrap(), 0x123456);
        let ifcnt = f.read_ifcnt().unwrap();
        assert_eq!(ifcnt as u32, f.core.uart.regs[&0x02]);
        assert_eq!(f.read_register(0x6A).unwrap(), 513);
        assert!(matches!(f.read_register(0x10), Err(Tmc2208Error::InvalidArgument)));
        f.write_register(0x13, 5).unwrap();
        assert_eq!(f.core.uart.last_write(0x13), Some(5));
        assert!(matches!(f.write_register(0x04, 1), Err(Tmc2208Error::InvalidArgument)));
    }

    #[test]
    fn step_pulses() {
        let mut f = Tmc2208Full::new(
            UartMock::new(true), &mut NoDelay, 0.11, None::<NoPin>, true,
            Some(TestPin::default()), Some(TestPin::default()), 12_000_000, false,
        ).unwrap();
        f.step(3, 1, &mut NoDelay).unwrap();
        assert_eq!(f.step_pin.as_ref().unwrap().levels.iter().filter(|b| **b).count(), 3);
        assert_eq!(f.dir_pin.as_ref().unwrap().levels.last(), Some(&true));
        f.step(-2, 1, &mut NoDelay).unwrap();
        assert_eq!(f.dir_pin.as_ref().unwrap().levels.last(), Some(&false));
        let mut nopin = full(UartMock::new(true));
        assert!(matches!(nopin.step(1, 1, &mut NoDelay), Err(Tmc2208Error::Pin)));
    }

    #[test]
    fn tmc2224_identity() {
        let mut m = UartMock::new(true);
        m.regs.insert(0x06, 0x2100_0000);
        assert!(Tmc2208Full::<_, NoPin, NoPin, NoPin>::new(m, &mut NoDelay, 0.11, None, true, None, None, 12_000_000, true).is_ok());
    }
}
