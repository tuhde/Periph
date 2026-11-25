//! TMC2130 -- Trinamic TMC2130 stepper driver over SPI (Mode 3).
//!
//! [`Tmc2130Minimal`] brings the driver up in SPI mode (internal reference,
//! StealthChop below `TPWMTHRS = 500`, SpreadCycle above it, 16 microsteps
//! with 256-step interpolation, 0.5 A RMS run current) and moves the motor with
//! STEP/DIR pulses -- the chip has no internal step generator.
//! [`Tmc2130Full`] adds raw register access, chopper / StealthChop tuning,
//! StallGuard2 and CoolStep, DIAG routing, DcStep, direct coil-current mode,
//! the microstep table and every status read-back.
//!
//! The driver is generic over `embedded_hal` [`SpiDevice`] (Mode 3, MSB first,
//! the implementation owns CS), [`OutputPin`] / [`InputPin`] and [`DelayNs`].
//! Pass [`NoPin`] (`None::<NoPin>`) for pins that are not wired. The 40-bit
//! datagram framing and the pipelined two-datagram read are done here: a write
//! is one 5-byte `write`, a read is a request transaction followed by a
//! `GCONF` request whose reply carries the data.
//!
//! ```rust,ignore
//! use periph::chips::motor::tmc2130::{Tmc2130Minimal, NoPin};
//!
//! let mut motor = Tmc2130Minimal::new(spi, step, dir, delay, 0.11, None::<NoPin>)?;
//! motor.set_current(0.8)?;
//! motor.enable()?;
//! motor.move_steps(400, 200.0)?;
//! ```

use embedded_hal::delay::DelayNs;
use embedded_hal::digital::{InputPin, OutputPin};
use embedded_hal::spi::{Operation, SpiDevice};
use libm::{roundf, sqrtf};

const REG_GCONF: u8 = 0x00;
const REG_GSTAT: u8 = 0x01;
const REG_IOIN: u8 = 0x04;
const REG_IHOLD_IRUN: u8 = 0x10;
const REG_TPOWERDOWN: u8 = 0x11;
const REG_TSTEP: u8 = 0x12;
const REG_TPWMTHRS: u8 = 0x13;
const REG_TCOOLTHRS: u8 = 0x14;
const REG_THIGH: u8 = 0x15;
const REG_XDIRECT: u8 = 0x2D;
const REG_VDCMIN: u8 = 0x33;
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
const REG_LOST_STEPS: u8 = 0x73;

const READABLE: [u8; 11] = [0x00, 0x01, 0x04, 0x12, 0x2D, 0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x73];
const WRITABLE: [u8; 22] = [
    0x00, 0x10, 0x11, 0x13, 0x14, 0x15, 0x2D, 0x33, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67,
    0x68, 0x69, 0x6C, 0x6D, 0x6E, 0x70,
];

const GCONF_I_SCALE_ANALOG: u32 = 1 << 0;
const GCONF_INTERNAL_RSENSE: u32 = 1 << 1;
const GCONF_EN_PWM_MODE: u32 = 1 << 2;
const GCONF_SHAFT: u32 = 1 << 4;
const GCONF_SMALL_HYSTERESIS: u32 = 1 << 14;
const GCONF_STOP_ENABLE: u32 = 1 << 15;
const GCONF_DIRECT_MODE: u32 = 1 << 16;

const VERSION: u8 = 0x11;
const CHOPCONF_DEFAULT: u32 = 0x1401_00C3;
const PWMCONF_DEFAULT: u32 = 0x0004_01C8;

/// `DRV_STATUS.otpw` -- over-temperature pre-warning.
pub const TMC2130_FAULT_OVERTEMP_WARNING: u8 = 0x01;
/// `DRV_STATUS.ot` -- over-temperature shutdown.
pub const TMC2130_FAULT_OVERTEMP: u8 = 0x02;
/// `s2ga` / `s2gb` -- short to GND.
pub const TMC2130_FAULT_SHORT_GND: u8 = 0x04;
/// `ola` / `olb` -- open load (informative only).
pub const TMC2130_FAULT_OPEN_LOAD: u8 = 0x10;

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

/// Errors from the TMC2130 drivers.
#[derive(Debug)]
pub enum Tmc2130Error<E> {
    /// The SPI bus returned an error.
    Spi(E),
    /// `IOIN` did not identify a TMC2130 (`VERSION` 0x11, bit 6 set).
    NotFound,
    /// A write was not mirrored back, or the `GCONF` / `CHOPCONF` readback differs.
    MirrorMismatch,
    /// An argument was out of range or the register is not accessible.
    InvalidArgument,
    /// The operation is unsupported with the internal sense resistors.
    Unsupported,
    /// Direct mode (`GCONF.direct_mode`) is required.
    DirectModeRequired,
    /// A STEP / DIR / ENN / DIAG pin returned an error, or a required pin is missing.
    Pin,
}

/// Decoded `DRV_STATUS`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc2130Status {
    /// Standstill.
    pub stst: bool,
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
    /// Open load on either phase.
    pub open_load: bool,
}

/// Decoded `GSTAT`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc2130GlobalStatus {
    /// IC was reset since the last read.
    pub reset: bool,
    /// Driver shut down by over-temperature or short.
    pub drv_err: bool,
    /// Charge-pump undervoltage.
    pub uv_cp: bool,
}

/// Decoded `IOIN`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tmc2130Ioin {
    /// Raw 32-bit word.
    pub raw: u32,
    /// State of STEP.
    pub step: bool,
    /// State of DIR.
    pub dir: bool,
    /// State of DCEN.
    pub dcen: bool,
    /// State of DCIN.
    pub dcin: bool,
    /// State of ENN.
    pub enn: bool,
    /// State of DCO.
    pub dco: bool,
    /// Silicon version (0x11).
    pub version: u8,
}

fn sign_extend(value: u32, bits: u32) -> i16 {
    let v = value & ((1u32 << bits) - 1);
    if v & (1 << (bits - 1)) != 0 {
        (v as i32 - (1i32 << bits)) as i16
    } else {
        v as i16
    }
}

/// TMC2130 -- minimal interface: current, STEP/DIR motion, faults.
pub struct Tmc2130Minimal<SPI, STEP, DIR, EN, D> {
    spi: SPI,
    step_pin: STEP,
    dir_pin: DIR,
    en_pin: Option<EN>,
    delay: D,
    r_sense: f32,
    gconf: u32,
    chopconf: u32,
    pwmconf: u32,
    coolconf: u32,
    microsteps: u16,
    vsense: bool,
    auto_vsense: bool,
    internal_rsense: bool,
    toff: u8,
    run_cs: u8,
    hold_cs: u8,
    ihold_delay: u8,
}

impl<SPI, STEP, DIR, EN, D> Tmc2130Minimal<SPI, STEP, DIR, EN, D>
where
    SPI: SpiDevice,
    STEP: OutputPin,
    DIR: OutputPin,
    EN: OutputPin,
    D: DelayNs,
{
    /// Construct the driver, probe the chip and apply the default configuration.
    ///
    /// `spi` must be configured for SPI Mode 3. `r_sense_ohm` is the external
    /// sense resistor (0.11 ohm on common boards). With an `en_pin` the power
    /// stage stays off until [`enable`](Self::enable).
    pub fn new(
        spi: SPI,
        step_pin: STEP,
        dir_pin: DIR,
        delay: D,
        r_sense_ohm: f32,
        en_pin: Option<EN>,
    ) -> Result<Self, Tmc2130Error<SPI::Error>> {
        let mut s = Self {
            spi,
            step_pin,
            dir_pin,
            en_pin,
            delay,
            r_sense: r_sense_ohm,
            gconf: 0,
            chopconf: 0,
            pwmconf: 0,
            coolconf: 0,
            microsteps: 16,
            vsense: false,
            auto_vsense: true,
            internal_rsense: false,
            toff: 3,
            run_cs: 0,
            hold_cs: 0,
            ihold_delay: 6,
        };
        s.initialise()?;
        Ok(s)
    }

    fn write_reg(&mut self, reg: u8, value: u32, verify: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        let v = value.to_be_bytes();
        let frame = [reg | 0x80, v[0], v[1], v[2], v[3]];
        self.spi.write(&frame).map_err(Tmc2130Error::Spi)?;
        match reg {
            REG_GCONF => self.gconf = value,
            REG_CHOPCONF => self.chopconf = value,
            REG_PWMCONF => self.pwmconf = value,
            REG_COOLCONF => self.coolconf = value,
            _ => {}
        }
        if verify && self.second_datagram()? != value {
            return Err(Tmc2130Error::MirrorMismatch);
        }
        Ok(())
    }

    // A harmless GCONF read request whose reply is the previous datagram's
    // answer (pipelined protocol). Never GSTAT, which clears on read.
    fn second_datagram(&mut self) -> Result<u32, Tmc2130Error<SPI::Error>> {
        let req = [REG_GCONF];
        let mut buf = [0u8; 4];
        self.spi
            .transaction(&mut [Operation::Write(&req), Operation::Read(&mut buf)])
            .map_err(Tmc2130Error::Spi)?;
        Ok(u32::from_be_bytes(buf))
    }

    pub(crate) fn read_reg(&mut self, reg: u8) -> Result<u32, Tmc2130Error<SPI::Error>> {
        let req = [reg & 0x7F];
        let mut buf = [0u8; 4];
        self.spi
            .transaction(&mut [Operation::Write(&req), Operation::Read(&mut buf)])
            .map_err(Tmc2130Error::Spi)?;
        self.second_datagram()
    }

    pub(crate) fn modify(&mut self, reg: u8, mask: u32, value: u32) -> Result<(), Tmc2130Error<SPI::Error>> {
        let current = match reg {
            REG_GCONF => self.gconf,
            REG_CHOPCONF => self.chopconf,
            REG_COOLCONF => self.coolconf,
            _ => self.pwmconf,
        };
        self.write_reg(reg, (current & !mask) | (value & mask), false)
    }

    fn cs_for(&self, rms_a: f32, vsense: bool) -> u8 {
        let vfs = if vsense { 0.180 } else { 0.325 };
        let cs = roundf(rms_a * sqrtf(2.0) * (self.r_sense + 0.020) * 32.0 / vfs) as i32 - 1;
        cs.clamp(0, 31) as u8
    }

    /// Returns `(cs, vsense)`; picks `vsense` automatically unless given or disabled.
    pub(crate) fn current_to_cs(&self, rms_a: f32, vsense: Option<bool>) -> Result<(u8, bool), Tmc2130Error<SPI::Error>> {
        if self.internal_rsense {
            return Err(Tmc2130Error::Unsupported);
        }
        if let Some(v) = vsense {
            return Ok((self.cs_for(rms_a, v), v));
        }
        if !self.auto_vsense {
            return Ok((self.cs_for(rms_a, self.vsense), self.vsense));
        }
        let cs = self.cs_for(rms_a, false);
        if cs < 16 {
            let high = self.cs_for(rms_a, true);
            if high <= 31 {
                return Ok((high, true));
            }
        }
        Ok((cs, false))
    }

    pub(crate) fn write_ihold_irun(&mut self, verify: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        let v = (self.hold_cs as u32 & 0x1F) | ((self.run_cs as u32 & 0x1F) << 8) | ((self.ihold_delay as u32 & 0xF) << 16);
        self.write_reg(REG_IHOLD_IRUN, v, verify)
    }

    fn initialise(&mut self) -> Result<(), Tmc2130Error<SPI::Error>> {
        if let Some(p) = self.en_pin.as_mut() {
            p.set_high().map_err(|_| Tmc2130Error::Pin)?;
        }
        self.delay.delay_ms(10);
        let ioin = self.read_reg(REG_IOIN)?;
        if (ioin >> 24) as u8 != VERSION || ioin & (1 << 6) == 0 {
            return Err(Tmc2130Error::NotFound);
        }
        self.read_reg(REG_GSTAT)?;
        let (cs, vsense) = self.current_to_cs(0.5, None)?;
        self.run_cs = cs;
        self.hold_cs = cs / 3;
        self.vsense = vsense;
        self.write_reg(REG_CHOPCONF, CHOPCONF_DEFAULT | if vsense { 1 << 17 } else { 0 }, true)?;
        self.write_ihold_irun(true)?;
        self.write_reg(REG_TPOWERDOWN, 10, true)?;
        self.write_reg(REG_GCONF, GCONF_EN_PWM_MODE, true)?;
        self.write_reg(REG_TPWMTHRS, 500, true)?;
        self.write_reg(REG_PWMCONF, PWMCONF_DEFAULT, true)?;
        if self.read_reg(REG_GCONF)? != self.gconf || self.read_reg(REG_CHOPCONF)? != self.chopconf {
            return Err(Tmc2130Error::MirrorMismatch);
        }
        Ok(())
    }

    /// Switch the power stage on and wait 100 ms for StealthChop to settle.
    pub fn enable(&mut self) -> Result<(), Tmc2130Error<SPI::Error>> {
        if let Some(p) = self.en_pin.as_mut() {
            p.set_low().map_err(|_| Tmc2130Error::Pin)?;
        } else {
            let v = (self.chopconf & !0xF) | self.toff as u32;
            self.write_reg(REG_CHOPCONF, v, false)?;
        }
        self.delay.delay_ms(100);
        Ok(())
    }

    /// Switch the power stage off; the motor freewheels.
    pub fn disable(&mut self) -> Result<(), Tmc2130Error<SPI::Error>> {
        if let Some(p) = self.en_pin.as_mut() {
            return p.set_high().map_err(|_| Tmc2130Error::Pin);
        }
        if self.chopconf & 0xF != 0 {
            self.toff = (self.chopconf & 0xF) as u8;
        }
        let v = self.chopconf & !0xF;
        self.write_reg(REG_CHOPCONF, v, false)
    }

    /// Set the run current in A RMS (at most 1.4); hold current becomes one third of it.
    ///
    /// Selects `vsense` automatically unless `Tmc2130Full::set_vsense` was called.
    /// Returns [`Tmc2130Error::InvalidArgument`] above 1.4 A and
    /// [`Tmc2130Error::Unsupported`] with the internal sense resistors.
    pub fn set_current(&mut self, rms_a: f32) -> Result<(), Tmc2130Error<SPI::Error>> {
        if rms_a > 1.4 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        let (cs, vsense) = self.current_to_cs(rms_a, None)?;
        self.run_cs = cs;
        self.hold_cs = cs / 3;
        if vsense != self.vsense {
            self.vsense = vsense;
            self.modify(REG_CHOPCONF, 1 << 17, if vsense { 1 << 17 } else { 0 })?;
        }
        self.write_ihold_irun(false)
    }

    /// Move `full_steps` full steps (sign = direction) at `full_steps_per_s`
    /// (blocking, software-timed STEP pulses).
    pub fn move_steps(&mut self, full_steps: i32, full_steps_per_s: f32) -> Result<(), Tmc2130Error<SPI::Error>> {
        if full_steps_per_s <= 0.0 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        let pulses = full_steps.unsigned_abs() * self.microsteps as u32;
        let half_ns = (1.0e9 / (2.0 * full_steps_per_s * self.microsteps as f32)) as u32;
        if full_steps >= 0 {
            self.dir_pin.set_high().map_err(|_| Tmc2130Error::Pin)?;
        } else {
            self.dir_pin.set_low().map_err(|_| Tmc2130Error::Pin)?;
        }
        for _ in 0..pulses {
            self.step_pin.set_high().map_err(|_| Tmc2130Error::Pin)?;
            self.delay.delay_ns(half_ns);
            self.step_pin.set_low().map_err(|_| Tmc2130Error::Pin)?;
            self.delay.delay_ns(half_ns);
        }
        Ok(())
    }

    /// True when `DRV_STATUS.stst` is set.
    pub fn is_standstill(&mut self) -> Result<bool, Tmc2130Error<SPI::Error>> {
        Ok(self.read_reg(REG_DRV_STATUS)? >> 31 == 1)
    }

    /// Bitmask of `TMC2130_FAULT_*` constants, 0 when none.
    pub fn read_fault(&mut self) -> Result<u8, Tmc2130Error<SPI::Error>> {
        let s = self.read_reg(REG_DRV_STATUS)?;
        let mut f = 0;
        if s & (1 << 26) != 0 {
            f |= TMC2130_FAULT_OVERTEMP_WARNING;
        }
        if s & (1 << 25) != 0 {
            f |= TMC2130_FAULT_OVERTEMP;
        }
        if s & (3 << 27) != 0 {
            f |= TMC2130_FAULT_SHORT_GND;
        }
        if s & (3 << 29) != 0 {
            f |= TMC2130_FAULT_OPEN_LOAD;
        }
        Ok(f)
    }
}

/// TMC2130 -- complete interface. Wraps a [`Tmc2130Minimal`] (composition).
pub struct Tmc2130Full<SPI, STEP, DIR, EN, D, DIAG0, DIAG1> {
    core: Tmc2130Minimal<SPI, STEP, DIR, EN, D>,
    diag0: Option<DIAG0>,
    diag1: Option<DIAG1>,
}

impl<SPI, STEP, DIR, EN, D, DIAG0, DIAG1> Tmc2130Full<SPI, STEP, DIR, EN, D, DIAG0, DIAG1>
where
    SPI: SpiDevice,
    STEP: OutputPin,
    DIR: OutputPin,
    EN: OutputPin,
    D: DelayNs,
    DIAG0: InputPin,
    DIAG1: InputPin,
{
    /// Construct the driver, probe the chip and apply the default configuration.
    ///
    /// `diag0` / `diag1` read the DIAG pins (pass `None::<NoPin>` when unwired).
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        spi: SPI,
        step_pin: STEP,
        dir_pin: DIR,
        delay: D,
        r_sense_ohm: f32,
        en_pin: Option<EN>,
        diag0: Option<DIAG0>,
        diag1: Option<DIAG1>,
    ) -> Result<Self, Tmc2130Error<SPI::Error>> {
        let core = Tmc2130Minimal::new(spi, step_pin, dir_pin, delay, r_sense_ohm, en_pin)?;
        Ok(Self { core, diag0, diag1 })
    }

    /// Switch the power stage on.
    pub fn enable(&mut self) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.enable()
    }

    /// Switch the power stage off.
    pub fn disable(&mut self) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.disable()
    }

    /// Set the run current in A RMS (see [`Tmc2130Minimal::set_current`]).
    pub fn set_current(&mut self, rms_a: f32) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.set_current(rms_a)
    }

    /// Blocking move of `full_steps` at `full_steps_per_s` (see [`Tmc2130Minimal::move_steps`]).
    pub fn move_steps(&mut self, full_steps: i32, full_steps_per_s: f32) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.move_steps(full_steps, full_steps_per_s)
    }

    /// True when `DRV_STATUS.stst` is set.
    pub fn is_standstill(&mut self) -> Result<bool, Tmc2130Error<SPI::Error>> {
        self.core.is_standstill()
    }

    /// Bitmask of `TMC2130_FAULT_*` constants.
    pub fn read_fault(&mut self) -> Result<u8, Tmc2130Error<SPI::Error>> {
        self.core.read_fault()
    }

    /// Raw 32-bit pipelined read of a readable register.
    pub fn read_register(&mut self, reg: u8) -> Result<u32, Tmc2130Error<SPI::Error>> {
        if !READABLE.contains(&reg) {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.read_reg(reg)
    }

    /// Raw 32-bit write; `verify` adds the mirror check.
    pub fn write_register(&mut self, reg: u8, value: u32, verify: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        if !WRITABLE.contains(&reg) {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.write_reg(reg, value, verify)
    }

    /// Independent hold current in A RMS.
    pub fn set_hold_current(&mut self, rms_a: f32) -> Result<(), Tmc2130Error<SPI::Error>> {
        let vsense = self.core.vsense;
        self.core.hold_cs = self.core.current_to_cs(rms_a, Some(vsense))?.0;
        self.core.write_ihold_irun(false)
    }

    /// `IHOLDDELAY` 0..=15 and `TPOWERDOWN` 0..=255.
    pub fn set_current_delay(&mut self, ihold_delay: u8, tpowerdown: u8) -> Result<(), Tmc2130Error<SPI::Error>> {
        if ihold_delay > 15 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.ihold_delay = ihold_delay;
        self.core.write_ihold_irun(false)?;
        self.core.write_reg(REG_TPOWERDOWN, tpowerdown as u32, false)
    }

    /// Microstep resolution 1, 2, 4, ..., 256.
    pub fn set_microsteps(&mut self, n: u16) -> Result<(), Tmc2130Error<SPI::Error>> {
        if !n.is_power_of_two() || n > 256 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        let mres = 8 - n.trailing_zeros();
        self.core.modify(REG_CHOPCONF, 0xF << 24, mres << 24)?;
        self.core.microsteps = n;
        Ok(())
    }

    /// `CHOPCONF.intpol`.
    pub fn set_interpolation(&mut self, enabled: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_CHOPCONF, 1 << 28, if enabled { 1 << 28 } else { 0 })
    }

    /// SpreadCycle only (`true`) or StealthChop (`false`); switch at standstill.
    pub fn set_chopper_mode(&mut self, spreadcycle: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_GCONF, GCONF_EN_PWM_MODE, if spreadcycle { 0 } else { GCONF_EN_PWM_MODE })
    }

    /// `TPWMTHRS` (20 bit); 0 disables.
    pub fn set_stealthchop_threshold(&mut self, tpwmthrs: u32) -> Result<(), Tmc2130Error<SPI::Error>> {
        if tpwmthrs > 0xF_FFFF {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.write_reg(REG_TPWMTHRS, tpwmthrs, false)
    }

    /// `THIGH` (20 bit).
    pub fn set_high_velocity_threshold(&mut self, thigh: u32) -> Result<(), Tmc2130Error<SPI::Error>> {
        if thigh > 0xF_FFFF {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.write_reg(REG_THIGH, thigh, false)
    }

    /// SpreadCycle (`chm = 0`) fields: `toff` 0..=15, `tbl` 0..=3, `hstrt` 0..=7, raw `hend` 0..=15.
    pub fn configure_chopper(&mut self, toff: u8, tbl: u8, hstrt: u8, hend: u8) -> Result<(), Tmc2130Error<SPI::Error>> {
        if toff > 15 || tbl > 3 || hstrt > 7 || hend > 15 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        if toff != 0 {
            self.core.toff = toff;
        }
        let mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (1 << 14) | (1 << 11);
        let v = toff as u32 | ((tbl as u32) << 15) | ((hstrt as u32) << 4) | ((hend as u32) << 7);
        self.core.modify(REG_CHOPCONF, mask, v)
    }

    /// Constant-off-time (`chm = 1`) fields: `tfd` 0..=15, `offset` -3..=12.
    pub fn configure_constant_off_time(
        &mut self,
        toff: u8,
        tbl: u8,
        tfd: u8,
        offset: i8,
        disfdcc: bool,
        rndtf: bool,
    ) -> Result<(), Tmc2130Error<SPI::Error>> {
        if toff > 15 || tbl > 3 || tfd > 15 || !(-3..=12).contains(&offset) {
            return Err(Tmc2130Error::InvalidArgument);
        }
        if toff != 0 {
            self.core.toff = toff;
        }
        let mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (1 << 14) | (1 << 13) | (1 << 12) | (1 << 11);
        let v = toff as u32
            | ((tbl as u32) << 15)
            | (((tfd & 7) as u32) << 4)
            | (((offset + 3) as u32) << 7)
            | (1 << 14)
            | if rndtf { 1 << 13 } else { 0 }
            | if disfdcc { 1 << 12 } else { 0 }
            | (((tfd >> 3) as u32 & 1) << 11);
        self.core.modify(REG_CHOPCONF, mask, v)
    }

    /// `CHOPCONF.vhighchm` / `vhighfs`.
    pub fn configure_high_velocity(&mut self, vhighchm: bool, vhighfs: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        let v = if vhighchm { 1 << 19 } else { 0 } | if vhighfs { 1 << 18 } else { 0 };
        self.core.modify(REG_CHOPCONF, (1 << 19) | (1 << 18), v)
    }

    /// `CHOPCONF.SYNC` 0..=15.
    pub fn set_chopper_sync(&mut self, sync: u8) -> Result<(), Tmc2130Error<SPI::Error>> {
        if sync > 15 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.modify(REG_CHOPCONF, 0xF << 20, (sync as u32) << 20)
    }

    /// `PWMCONF` fields (`freewheel` is kept): `pwm_freq` 0..=3.
    pub fn configure_stealthchop(
        &mut self,
        autoscale: bool,
        symmetric: bool,
        pwm_ampl: u8,
        pwm_grad: u8,
        pwm_freq: u8,
    ) -> Result<(), Tmc2130Error<SPI::Error>> {
        if pwm_freq > 3 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        let mask = (1 << 19) | (1 << 18) | (3 << 16) | (0xFF << 8) | 0xFF;
        let v = if symmetric { 1 << 19 } else { 0 }
            | if autoscale { 1 << 18 } else { 0 }
            | ((pwm_freq as u32) << 16)
            | ((pwm_grad as u32) << 8)
            | pwm_ampl as u32;
        self.core.modify(REG_PWMCONF, mask, v)
    }

    /// `PWMCONF.freewheel` 0..=3.
    pub fn set_freewheel(&mut self, mode: u8) -> Result<(), Tmc2130Error<SPI::Error>> {
        if mode > 3 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.modify(REG_PWMCONF, 3 << 20, (mode as u32) << 20)
    }

    /// `CHOPCONF.vsense`; disables the automatic selection in `set_current`.
    pub fn set_vsense(&mut self, high_sensitivity: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_CHOPCONF, 1 << 17, if high_sensitivity { 1 << 17 } else { 0 })?;
        self.core.vsense = high_sensitivity;
        self.core.auto_vsense = false;
        Ok(())
    }

    /// `GCONF.internal_Rsense` (`set_current` then fails).
    pub fn set_internal_rsense(&mut self, enabled: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_GCONF, GCONF_INTERNAL_RSENSE, if enabled { GCONF_INTERNAL_RSENSE } else { 0 })?;
        self.core.internal_rsense = enabled;
        Ok(())
    }

    /// `GCONF.I_scale_analog`.
    pub fn set_analog_scaling(&mut self, enabled: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_GCONF, GCONF_I_SCALE_ANALOG, if enabled { GCONF_I_SCALE_ANALOG } else { 0 })
    }

    /// `GCONF.shaft`.
    pub fn set_direction_inverted(&mut self, inverted: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_GCONF, GCONF_SHAFT, if inverted { GCONF_SHAFT } else { 0 })
    }

    /// Route events to DIAG0 / DIAG1 (`GCONF` bits 5..=13): `flags` is, in order,
    /// `diag0_error, diag0_otpw, diag0_stall, diag1_stall, diag1_index, diag1_onstate`.
    pub fn configure_diag(&mut self, flags: [bool; 6], diag0_pushpull: bool, diag1_pushpull: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        let mut v = 0u32;
        for (i, f) in flags.iter().enumerate() {
            if *f {
                v |= 1 << (5 + i);
            }
        }
        if diag0_pushpull {
            v |= 1 << 12;
        }
        if diag1_pushpull {
            v |= 1 << 13;
        }
        self.core.modify(REG_GCONF, 0x3FE0 & !(1 << 11), v)
    }

    /// `CHOPCONF.dedge`.
    pub fn set_double_edge(&mut self, enabled: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_CHOPCONF, 1 << 29, if enabled { 1 << 29 } else { 0 })
    }

    /// `CHOPCONF.diss2g`: `true` keeps the short-to-GND protection.
    pub fn set_short_protection(&mut self, short_gnd: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_CHOPCONF, 1 << 30, if short_gnd { 0 } else { 1 << 30 })
    }

    /// `GCONF.small_hysteresis`.
    pub fn set_small_hysteresis(&mut self, enabled: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_GCONF, GCONF_SMALL_HYSTERESIS, if enabled { GCONF_SMALL_HYSTERESIS } else { 0 })
    }

    /// `GCONF.stop_enable`.
    pub fn set_emergency_stop(&mut self, enabled: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_GCONF, GCONF_STOP_ENABLE, if enabled { GCONF_STOP_ENABLE } else { 0 })
    }

    /// `COOLCONF.sgt` -64..=63 (lower = more sensitive); other fields are kept.
    pub fn set_stall_threshold(&mut self, sgt: i8) -> Result<(), Tmc2130Error<SPI::Error>> {
        if !(-64..=63).contains(&sgt) {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.modify(REG_COOLCONF, 0x7F << 16, ((sgt as u32) & 0x7F) << 16)
    }

    /// `COOLCONF.sfilt`.
    pub fn set_stallguard_filter(&mut self, enabled: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_COOLCONF, 1 << 24, if enabled { 1 << 24 } else { 0 })
    }

    /// `TCOOLTHRS` (20 bit); 0 disables.
    pub fn set_coolstep_threshold(&mut self, tcoolthrs: u32) -> Result<(), Tmc2130Error<SPI::Error>> {
        if tcoolthrs > 0xF_FFFF {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.write_reg(REG_TCOOLTHRS, tcoolthrs, false)
    }

    /// `COOLCONF` CoolStep fields (`sgt` / `sfilt` kept): `semin` 0..=15 (0 disables), `semax` 0..=15,
    /// `seup` 0..=3, `sedn` 0..=3, `seimin` 0 or 1.
    pub fn configure_coolstep(&mut self, semin: u8, semax: u8, seup: u8, sedn: u8, seimin: u8) -> Result<(), Tmc2130Error<SPI::Error>> {
        if semin > 15 || semax > 15 || seup > 3 || sedn > 3 || seimin > 1 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        let mask = 0xF | (3 << 5) | (0xF << 8) | (3 << 13) | (1 << 15);
        let v = semin as u32 | ((seup as u32) << 5) | ((semax as u32) << 8) | ((sedn as u32) << 13) | ((seimin as u32) << 15);
        self.core.modify(REG_COOLCONF, mask, v)
    }

    /// `SG_RESULT` 0..=1023.
    pub fn read_stallguard(&mut self) -> Result<u16, Tmc2130Error<SPI::Error>> {
        Ok((self.core.read_reg(REG_DRV_STATUS)? & 0x3FF) as u16)
    }

    /// `DRV_STATUS.StallGuard`.
    pub fn is_stalled(&mut self) -> Result<bool, Tmc2130Error<SPI::Error>> {
        Ok((self.core.read_reg(REG_DRV_STATUS)? >> 24) & 1 == 1)
    }

    /// Level of `DIAG0` (`n` = 0) or `DIAG1` (`n` = 1); [`Tmc2130Error::Pin`] if not wired.
    pub fn read_diag(&mut self, n: u8) -> Result<bool, Tmc2130Error<SPI::Error>> {
        match n {
            0 => self.diag0.as_mut().ok_or(Tmc2130Error::Pin)?.is_high().map_err(|_| Tmc2130Error::Pin),
            1 => self.diag1.as_mut().ok_or(Tmc2130Error::Pin)?.is_high().map_err(|_| Tmc2130Error::Pin),
            _ => Err(Tmc2130Error::InvalidArgument),
        }
    }

    /// Emit `count.abs()` STEP pulses (negative reverses via DIR); each edge waits `delay_us`.
    pub fn step(&mut self, count: i32, delay_us: u32) -> Result<(), Tmc2130Error<SPI::Error>> {
        if count >= 0 {
            self.core.dir_pin.set_high().map_err(|_| Tmc2130Error::Pin)?;
        } else {
            self.core.dir_pin.set_low().map_err(|_| Tmc2130Error::Pin)?;
        }
        for _ in 0..count.unsigned_abs() {
            self.core.step_pin.set_high().map_err(|_| Tmc2130Error::Pin)?;
            self.core.delay.delay_us(delay_us);
            self.core.step_pin.set_low().map_err(|_| Tmc2130Error::Pin)?;
            self.core.delay.delay_us(delay_us);
        }
        Ok(())
    }

    /// `GCONF.direct_mode`.
    pub fn set_direct_mode(&mut self, enabled: bool) -> Result<(), Tmc2130Error<SPI::Error>> {
        self.core.modify(REG_GCONF, GCONF_DIRECT_MODE, if enabled { GCONF_DIRECT_MODE } else { 0 })
    }

    /// `XDIRECT` coil currents -255..=255 each; requires direct mode.
    pub fn set_coil_currents(&mut self, coil_a: i16, coil_b: i16) -> Result<(), Tmc2130Error<SPI::Error>> {
        if self.core.gconf & GCONF_DIRECT_MODE == 0 {
            return Err(Tmc2130Error::DirectModeRequired);
        }
        if !(-255..=255).contains(&coil_a) || !(-255..=255).contains(&coil_b) {
            return Err(Tmc2130Error::InvalidArgument);
        }
        let v = (coil_a as u32 & 0x1FF) | ((coil_b as u32 & 0x1FF) << 16);
        self.core.write_reg(REG_XDIRECT, v, false)
    }

    /// `VDCMIN` (0..=2^23-1), `DCCTRL.DC_TIME` (0..=1023), `DC_SG` (0..=255).
    pub fn set_dcstep(&mut self, vdcmin: u32, dc_time: u16, dc_sg: u8) -> Result<(), Tmc2130Error<SPI::Error>> {
        if vdcmin > 0x7F_FFFF || dc_time > 1023 {
            return Err(Tmc2130Error::InvalidArgument);
        }
        self.core.write_reg(REG_VDCMIN, vdcmin, false)?;
        self.core.write_reg(REG_DCCTRL, dc_time as u32 | ((dc_sg as u32) << 16), false)
    }

    /// Program `MSLUT[0..8]`, `MSLUTSEL`, `MSLUTSTART`; call at `MSCNT = 0`.
    pub fn write_microstep_table(&mut self, mslut: &[u32; 8], mslutsel: u32, mslutstart: u32) -> Result<(), Tmc2130Error<SPI::Error>> {
        for (i, w) in mslut.iter().enumerate() {
            self.core.write_reg(REG_MSLUT0 + i as u8, *w, false)?;
        }
        self.core.write_reg(REG_MSLUTSEL, mslutsel, false)?;
        self.core.write_reg(REG_MSLUTSTART, mslutstart, false)
    }

    /// `MSCNT` 0..=1023.
    pub fn microstep_position(&mut self) -> Result<u16, Tmc2130Error<SPI::Error>> {
        Ok((self.core.read_reg(REG_MSCNT)? & 0x3FF) as u16)
    }

    /// `DRV_STATUS.CS_ACTUAL` 0..=31.
    pub fn read_current_scale(&mut self) -> Result<u8, Tmc2130Error<SPI::Error>> {
        Ok(((self.core.read_reg(REG_DRV_STATUS)? >> 16) & 0x1F) as u8)
    }

    /// Signed `(CUR_A, CUR_B)` from `MSCURACT`.
    pub fn read_coil_currents(&mut self) -> Result<(i16, i16), Tmc2130Error<SPI::Error>> {
        let raw = self.core.read_reg(REG_MSCURACT)?;
        Ok((sign_extend(raw >> 16, 9), sign_extend(raw, 9)))
    }

    /// `TSTEP` (20 bit).
    pub fn read_tstep(&mut self) -> Result<u32, Tmc2130Error<SPI::Error>> {
        Ok(self.core.read_reg(REG_TSTEP)? & 0xF_FFFF)
    }

    /// `PWM_SCALE` 0..=255.
    pub fn read_pwm_scale(&mut self) -> Result<u8, Tmc2130Error<SPI::Error>> {
        Ok((self.core.read_reg(REG_PWM_SCALE)? & 0xFF) as u8)
    }

    /// `LOST_STEPS` (20 bit, wraps).
    pub fn read_lost_steps(&mut self) -> Result<u32, Tmc2130Error<SPI::Error>> {
        Ok(self.core.read_reg(REG_LOST_STEPS)? & 0xF_FFFF)
    }

    /// Decoded `DRV_STATUS` (one read).
    pub fn read_status(&mut self) -> Result<Tmc2130Status, Tmc2130Error<SPI::Error>> {
        let s = self.core.read_reg(REG_DRV_STATUS)?;
        Ok(Tmc2130Status {
            stst: s >> 31 == 1,
            fsactive: s & (1 << 15) != 0,
            cs_actual: ((s >> 16) & 0x1F) as u8,
            sg_result: (s & 0x3FF) as u16,
            stallguard: s & (1 << 24) != 0,
            otpw: s & (1 << 26) != 0,
            ot: s & (1 << 25) != 0,
            short_gnd: s & (3 << 27) != 0,
            open_load: s & (3 << 29) != 0,
        })
    }

    /// Decoded `GSTAT`; the chip clears the flags on read.
    pub fn read_global_status(&mut self) -> Result<Tmc2130GlobalStatus, Tmc2130Error<SPI::Error>> {
        let g = self.core.read_reg(REG_GSTAT)?;
        Ok(Tmc2130GlobalStatus { reset: g & 1 != 0, drv_err: g & 2 != 0, uv_cp: g & 4 != 0 })
    }

    /// Decoded `IOIN`.
    pub fn read_ioin(&mut self) -> Result<Tmc2130Ioin, Tmc2130Error<SPI::Error>> {
        let raw = self.core.read_reg(REG_IOIN)?;
        Ok(Tmc2130Ioin {
            raw,
            step: raw & 1 != 0,
            dir: raw & 2 != 0,
            dcen: raw & 4 != 0,
            dcin: raw & 8 != 0,
            enn: raw & 16 != 0,
            dco: raw & 32 != 0,
            version: (raw >> 24) as u8,
        })
    }
}

#[cfg(all(test, feature = "std"))]
mod tests {
    use super::*;
    use embedded_hal::spi::ErrorType;
    use std::collections::BTreeMap;

    /// Scripted TMC2130 implementing the pipelined 40-bit datagram protocol.
    #[derive(Default)]
    struct ChipSim {
        regs: BTreeMap<u8, u32>,
        frames: Vec<Vec<u8>>,
        writes: Vec<(u8, u32)>,
        pending: u32,
        mirror_broken: bool,
        gstat_reads: u32,
    }

    impl ChipSim {
        fn new() -> Self {
            let mut s = Self::default();
            s.regs.insert(0x04, 0x1100_0040);
            s.regs.insert(0x01, 0x01);
            s
        }
        fn last_write(&self, reg: u8) -> Option<u32> {
            self.writes.iter().rev().find(|w| w.0 == reg).map(|w| w.1)
        }
        fn datagram(&mut self, frame: &[u8]) -> u32 {
            let mut f = frame.to_vec();
            f.resize(5, 0);
            self.frames.push(f.clone());
            let reply = self.pending;
            let reg = f[0] & 0x7F;
            if f[0] & 0x80 != 0 {
                let v = u32::from_be_bytes([f[1], f[2], f[3], f[4]]);
                self.regs.insert(reg, v);
                self.writes.push((reg, v));
                self.pending = if self.mirror_broken { 0 } else { v };
            } else {
                if reg == 0x01 {
                    self.gstat_reads += 1;
                }
                self.pending = self.regs.get(&reg).copied().unwrap_or(0);
                if reg == 0x01 {
                    self.regs.insert(0x01, 0);
                }
            }
            reply
        }
    }

    impl ErrorType for ChipSim {
        type Error = embedded_hal::spi::ErrorKind;
    }

    impl SpiDevice for ChipSim {
        fn transaction(&mut self, ops: &mut [Operation<'_, u8>]) -> Result<(), Self::Error> {
            let mut tx = Vec::new();
            for op in ops.iter() {
                if let Operation::Write(w) = op {
                    tx.extend_from_slice(w);
                }
            }
            let reply = self.datagram(&tx).to_be_bytes();
            for op in ops.iter_mut() {
                if let Operation::Read(r) = op {
                    r.copy_from_slice(&reply[..r.len()]);
                }
            }
            Ok(())
        }
    }

    struct NoDelay;
    impl DelayNs for NoDelay {
        fn delay_ns(&mut self, _ns: u32) {}
    }

    #[derive(Default)]
    struct TestPin {
        levels: Vec<bool>,
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
    struct HighPin;
    impl embedded_hal::digital::ErrorType for HighPin {
        type Error = core::convert::Infallible;
    }
    impl InputPin for HighPin {
        fn is_high(&mut self) -> Result<bool, Self::Error> {
            Ok(true)
        }
        fn is_low(&mut self) -> Result<bool, Self::Error> {
            Ok(false)
        }
    }

    type Min = Tmc2130Minimal<ChipSim, TestPin, TestPin, NoPin, NoDelay>;
    type FullT = Tmc2130Full<ChipSim, TestPin, TestPin, NoPin, NoDelay, HighPin, NoPin>;

    fn minimal(sim: ChipSim) -> Result<Min, Tmc2130Error<embedded_hal::spi::ErrorKind>> {
        Tmc2130Minimal::new(sim, TestPin::default(), TestPin::default(), NoDelay, 0.11, None::<NoPin>)
    }

    fn full() -> FullT {
        Tmc2130Full::new(
            ChipSim::new(),
            TestPin::default(),
            TestPin::default(),
            NoDelay,
            0.11,
            None::<NoPin>,
            Some(HighPin),
            None::<NoPin>,
        )
        .unwrap()
    }

    #[test]
    fn init_writes_defaults() {
        let m = minimal(ChipSim::new()).unwrap();
        let s = &m.spi;
        assert!(s.frames.contains(&vec![0xEC, 0x14, 0x03, 0x00, 0xC3]));
        assert_eq!(s.last_write(0x11), Some(10));
        assert_eq!(s.last_write(0x00), Some(0x4));
        assert_eq!(s.last_write(0x13), Some(500));
        assert_eq!(s.last_write(0x70), Some(0x0004_01C8));
        let ih = s.last_write(0x10).unwrap();
        assert_eq!((ih >> 8) & 0x1F, 15);
        assert_eq!(ih & 0x1F, 5);
        assert_eq!((ih >> 16) & 0xF, 6);
        assert_eq!(s.gstat_reads, 1);
    }

    #[test]
    fn identity_and_mirror_failures() {
        let mut s = ChipSim::new();
        s.regs.insert(0x04, 0);
        assert!(matches!(minimal(s), Err(Tmc2130Error::NotFound)));
        let mut s = ChipSim::new();
        s.regs.insert(0x04, 0x1100_0000);
        assert!(matches!(minimal(s), Err(Tmc2130Error::NotFound)));
        let mut s = ChipSim::new();
        s.mirror_broken = true;
        assert!(matches!(minimal(s), Err(Tmc2130Error::MirrorMismatch)));
    }

    #[test]
    fn current_motion_and_faults() {
        let mut m = minimal(ChipSim::new()).unwrap();
        m.set_current(1.0).unwrap();
        assert_eq!((m.spi.last_write(0x10).unwrap() >> 8) & 0x1F, 17);
        assert_eq!(m.spi.last_write(0x6C).unwrap() & (1 << 17), 0);
        m.set_current(0.5).unwrap();
        assert_eq!((m.spi.last_write(0x10).unwrap() >> 8) & 0x1F, 15);
        assert_ne!(m.spi.last_write(0x6C).unwrap() & (1 << 17), 0);
        assert!(matches!(m.set_current(1.5), Err(Tmc2130Error::InvalidArgument)));
        m.move_steps(2, 1.0e6).unwrap();
        assert_eq!(m.step_pin.levels.iter().filter(|b| **b).count(), 32);
        assert_eq!(m.dir_pin.levels.last(), Some(&true));
        m.move_steps(-1, 1.0e6).unwrap();
        assert_eq!(m.dir_pin.levels.last(), Some(&false));
        assert!(matches!(m.move_steps(1, 0.0), Err(Tmc2130Error::InvalidArgument)));
        m.disable().unwrap();
        assert_eq!(m.spi.last_write(0x6C).unwrap() & 0xF, 0);
        m.enable().unwrap();
        assert_eq!(m.spi.last_write(0x6C).unwrap() & 0xF, 3);
        m.spi.regs.insert(0x6F, (1 << 31) | (1 << 26) | (1 << 28) | (1 << 30));
        assert!(m.is_standstill().unwrap());
        assert_eq!(m.read_fault().unwrap(), TMC2130_FAULT_OVERTEMP_WARNING | TMC2130_FAULT_SHORT_GND | TMC2130_FAULT_OPEN_LOAD);
    }

    #[test]
    fn en_pin_behaviour() {
        let mut m = Tmc2130Minimal::new(ChipSim::new(), TestPin::default(), TestPin::default(), NoDelay, 0.11, Some(TestPin::default())).unwrap();
        assert_eq!(m.en_pin.as_ref().unwrap().levels, vec![true]);
        m.enable().unwrap();
        assert_eq!(m.en_pin.as_ref().unwrap().levels.last(), Some(&false));
    }

    #[test]
    fn full_configuration() {
        let mut f = full();
        f.set_microsteps(256).unwrap();
        assert_eq!((f.core.spi.last_write(0x6C).unwrap() >> 24) & 0xF, 0);
        f.set_microsteps(1).unwrap();
        assert_eq!((f.core.spi.last_write(0x6C).unwrap() >> 24) & 0xF, 8);
        assert!(matches!(f.set_microsteps(3), Err(Tmc2130Error::InvalidArgument)));
        f.set_microsteps(32).unwrap();
        f.move_steps(1, 1.0e6).unwrap();
        assert_eq!(f.core.step_pin.levels.iter().filter(|b| **b).count(), 32);
        f.set_chopper_mode(true).unwrap();
        assert_eq!(f.core.spi.last_write(0x00).unwrap() & 0x4, 0);
        f.set_chopper_mode(false).unwrap();
        assert_eq!(f.core.spi.last_write(0x00).unwrap() & 0x4, 0x4);
        f.set_direction_inverted(true).unwrap();
        assert_eq!(f.core.spi.last_write(0x00).unwrap() & 0x10, 0x10);
        f.configure_diag([false, false, true, false, true, false], true, false).unwrap();
        let g = f.core.spi.last_write(0x00).unwrap();
        assert!((g >> 7) & 1 == 1 && (g >> 9) & 1 == 1 && (g >> 12) & 1 == 1 && (g >> 5) & 1 == 0 && g & 0x14 == 0x14);
        f.set_vsense(false).unwrap();
        f.set_current(0.5).unwrap();
        assert_eq!((f.core.spi.last_write(0x10).unwrap() >> 8) & 0x1F, 8);
        assert_eq!((f.core.spi.last_write(0x6C).unwrap() >> 17) & 1, 0);
        f.set_hold_current(0.2).unwrap();
        assert_eq!(f.core.spi.last_write(0x10).unwrap() & 0x1F, 3);
        f.set_current_delay(2, 100).unwrap();
        assert_eq!((f.core.spi.last_write(0x10).unwrap() >> 16) & 0xF, 2);
        assert_eq!(f.core.spi.last_write(0x11), Some(100));
        f.configure_chopper(4, 1, 3, 2).unwrap();
        let c = f.core.spi.last_write(0x6C).unwrap();
        assert_eq!((c & 0xF, (c >> 15) & 3, (c >> 4) & 7, (c >> 7) & 0xF, (c >> 14) & 1), (4, 1, 3, 2, 0));
        f.configure_constant_off_time(5, 2, 11, -3, true, true).unwrap();
        let c = f.core.spi.last_write(0x6C).unwrap();
        assert_eq!((c & 0xF, (c >> 4) & 7, (c >> 7) & 0xF, (c >> 11) & 1, (c >> 12) & 3, (c >> 14) & 1), (5, 3, 0, 1, 3, 1));
        f.configure_stealthchop(true, false, 40, 5, 1).unwrap();
        f.set_freewheel(1).unwrap();
        let p = f.core.spi.last_write(0x70).unwrap();
        assert_eq!((p & 0xFF, (p >> 8) & 0xFF, (p >> 16) & 3, (p >> 20) & 3), (40, 5, 1, 1));
        f.set_stall_threshold(-5).unwrap();
        f.set_stallguard_filter(true).unwrap();
        f.configure_coolstep(5, 2, 1, 1, 0).unwrap();
        let cc = f.core.spi.last_write(0x6D).unwrap();
        assert_eq!(cc & 0xF, 5);
        assert_eq!((cc >> 16) & 0x7F, (-5i32 as u32) & 0x7F);
        assert_eq!((cc >> 24) & 1, 1);
        assert!(matches!(f.set_stall_threshold(64), Err(Tmc2130Error::InvalidArgument)));
        assert!(matches!(f.configure_coolstep(16, 0, 0, 0, 0), Err(Tmc2130Error::InvalidArgument)));
        f.set_coolstep_threshold(500).unwrap();
        assert_eq!(f.core.spi.last_write(0x14), Some(500));
        assert!(matches!(f.set_coil_currents(1, 1), Err(Tmc2130Error::DirectModeRequired)));
        f.set_direct_mode(true).unwrap();
        f.set_coil_currents(100, -100).unwrap();
        assert_eq!(f.core.spi.last_write(0x2D), Some(100 | (((-100i32 as u32) & 0x1FF) << 16)));
        f.set_dcstep(1000, 200, 3).unwrap();
        assert_eq!(f.core.spi.last_write(0x6E), Some(200 | (3 << 16)));
        f.write_microstep_table(&[1, 2, 3, 4, 5, 6, 7, 8], 0x1234_5678, 0x00F7_0000).unwrap();
        assert_eq!(f.core.spi.last_write(0x67), Some(8));
        f.set_internal_rsense(true).unwrap();
        assert!(matches!(f.set_current(0.5), Err(Tmc2130Error::Unsupported)));
        f.set_internal_rsense(false).unwrap();
        f.step(3, 1).unwrap();
        assert_eq!(f.core.dir_pin.levels.last(), Some(&true));
        f.step(-2, 1).unwrap();
        assert_eq!(f.core.dir_pin.levels.last(), Some(&false));
    }

    #[test]
    fn full_readbacks() {
        let mut f = full();
        f.core.spi.regs.insert(0x6A, 513);
        f.core.spi.regs.insert(0x6B, (0x1FF << 16) | 0x100);
        f.core.spi.regs.insert(0x12, 1234);
        f.core.spi.regs.insert(0x71, 77);
        f.core.spi.regs.insert(0x73, 0x12_3456);
        f.core.spi.regs.insert(0x6F, 0x8000_0000 | (1 << 15) | (17 << 16) | (1 << 25) | (1 << 24) | 300);
        f.core.spi.regs.insert(0x04, 0x1100_0000 | 0x40 | 0x1 | 0x10);
        f.core.spi.regs.insert(0x01, 0x03);
        assert_eq!(f.microstep_position().unwrap(), 513);
        assert_eq!(f.read_coil_currents().unwrap(), (-1, -256));
        assert_eq!(f.read_tstep().unwrap(), 1234);
        assert_eq!(f.read_pwm_scale().unwrap(), 77);
        assert_eq!(f.read_lost_steps().unwrap(), 0x2_3456);
        assert_eq!(f.read_current_scale().unwrap(), 17);
        assert_eq!(f.read_stallguard().unwrap(), 300);
        assert!(f.is_stalled().unwrap());
        let st = f.read_status().unwrap();
        assert!(st.stst && st.fsactive && st.ot && st.stallguard && !st.otpw);
        assert_eq!((st.cs_actual, st.sg_result), (17, 300));
        assert_eq!(f.read_global_status().unwrap(), Tmc2130GlobalStatus { reset: true, drv_err: true, uv_cp: false });
        assert_eq!(f.core.spi.regs[&0x01], 0);
        let io = f.read_ioin().unwrap();
        assert!(io.step && io.enn && !io.dir);
        assert_eq!(io.version, 0x11);
        assert_eq!(f.read_register(0x6A).unwrap(), 513);
        assert!(matches!(f.read_register(0x10), Err(Tmc2130Error::InvalidArgument)));
        f.write_register(0x13, 5, true).unwrap();
        assert_eq!(f.core.spi.last_write(0x13), Some(5));
        assert!(matches!(f.write_register(0x72, 1, false), Err(Tmc2130Error::InvalidArgument)));
        assert!(f.read_diag(0).unwrap());
        assert!(matches!(f.read_diag(1), Err(Tmc2130Error::Pin)));
        assert!(matches!(f.read_diag(2), Err(Tmc2130Error::InvalidArgument)));
    }
}
