//! Demo for the TMC2130 -- sensorless homing of a linear axis with StallGuard2.
//!
//! Enables the driver at 0.8 A with 16 microsteps, proves the SPI link by
//! reading IOIN.VERSION (0x11), switches to SpreadCycle (StallGuard2 does not
//! work in StealthChop) and drives toward the end stop in bursts of 20 full
//! steps at 150 full steps/s. When SG_RESULT reaches 0 the demo stops, prints
//! the SG_RESULT history, backs off 200 full steps and finishes with a
//! CoolStep move. Any fault disables the driver and exits.

use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::motor::{Tmc2130Full};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn pin(chip: &mut Chip, line: u32, name: &str, flags: LineRequestFlags) -> CdevPin {
    let handle = chip.get_line(line).expect("get line")
        .request(flags, 0, name).expect("request line");
    CdevPin::new(handle).expect("pin")
}

fn open_spi() -> ExclusiveDevice<SpidevBus, NullCs, embedded_hal_bus::spi::NoDelay> {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", bus, dev)).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_3)
        .build()).expect("configure spi");
    ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).expect("spi device")
}

const HOMING_SPEED: f32 = 150.0;
const BURST: i32 = 20;

fn main() {
    let mut chip = Chip::new("/dev/gpiochip0").expect("open gpio chip");
    let step = pin(&mut chip, 27, "tmc2130_step", LineRequestFlags::OUTPUT);
    let dir = pin(&mut chip, 22, "tmc2130_dir", LineRequestFlags::OUTPUT);
    let en = pin(&mut chip, 17, "tmc2130_en", LineRequestFlags::OUTPUT);
    let mut motor = Tmc2130Full::new(open_spi(), step, dir, Delay, 0.11, Some(en), None::<CdevPin>, None::<CdevPin>)
        .expect("init"); // Create TMC2130 Full driver, (spi, step_pin, dir_pin, delay, r_sense_ohm=0.11 Ω, en_pin, diag0_pin, diag1_pin) → Result

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION proves the SPI wiring and Mode 3 setting work before any
    // motion is attempted.
    let ioin = motor.read_ioin().expect("ioin"); // Read IOIN, () → Result<Tmc2130Ioin, Error>
    println!("version {:#04x}", ioin.version);
    motor.set_current(0.8).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
    motor.set_microsteps(16).expect("microsteps"); // Set microstep resolution, (n 1–256) → Result<(), Error>
    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>

    // --- Arm StallGuard2 for the homing speed ---
    // StallGuard2 needs SpreadCycle and TSTEP <= TCOOLTHRS, so TCOOLTHRS is set
    // just above the TSTEP of the homing speed; sgt = 0 is the usual start value.
    motor.set_chopper_mode(true).expect("spreadcycle"); // Choose chopper, (spreadcycle) → Result<(), Error>
    motor.set_stall_threshold(0).expect("sgt"); // Set StallGuard2 threshold, (sgt -64..63) → Result<(), Error>
    motor.set_coolstep_threshold(12_000_000 / (HOMING_SPEED as u32 * 256 / 2)).expect("tcoolthrs"); // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → Result<(), Error>

    // --- Drive toward the end stop in short bursts ---
    // The first burst is spin-up, where SG_RESULT is not yet valid; after each
    // burst the demo reads SG_RESULT until the stall flag is set.
    let mut history: Vec<u16> = Vec::new();
    motor.move_steps(BURST, HOMING_SPEED).expect("move"); // Move, (full_steps, full_steps_per_s full steps/s) → Result<(), Error>
    loop {
        motor.move_steps(BURST, HOMING_SPEED).expect("move"); // Move, (full_steps, full_steps_per_s full steps/s) → Result<(), Error>
        let sg = motor.read_stallguard().expect("sg"); // Read StallGuard2 result, () → Result<u16, Error> 0–1023
        history.push(sg);
        println!("SG_RESULT {}", sg);
        if motor.is_stalled().expect("stalled") { // Read stall flag, () → Result<bool, Error>
            break;
        }
        guard(&mut motor);
    }

    // --- End stop found: report and back off ---
    // The last few SG_RESULT values show how the load built up before the stall.
    println!("history {:?}", &history[history.len().saturating_sub(5)..]);
    motor.move_steps(-200, HOMING_SPEED).expect("move"); // Move, (full_steps, full_steps_per_s full steps/s) → Result<(), Error>

    // --- Final move with CoolStep ---
    // CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
    motor.configure_coolstep(5, 2, 1, 1, 0).expect("coolstep"); // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → Result<(), Error>
    for _ in 0..10 {
        motor.move_steps(20, HOMING_SPEED).expect("move"); // Move, (full_steps, full_steps_per_s full steps/s) → Result<(), Error>
        println!("CS_ACTUAL {}", motor.read_current_scale().expect("cs")); // Read actual current scale, () → Result<u8, Error> 0–31
        guard(&mut motor);
    }

    // --- Shut down and report ---
    // GSTAT shows whether the run stressed the driver; the chip clears it on read.
    println!("status {:?}", motor.read_status().expect("status")); // Read driver status, () → Result<Tmc2130Status, Error>
    println!("global status {:?}", motor.read_global_status().expect("gstat")); // Read GSTAT, () → Result<Tmc2130GlobalStatus, Error>
    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
    println!("===DONE: 0 passed, 0 failed===");
}

fn guard<SPI, EN>(motor: &mut Tmc2130Full<SPI, CdevPin, CdevPin, EN, Delay, CdevPin, CdevPin>)
where
    SPI: embedded_hal::spi::SpiDevice,
    EN: embedded_hal::digital::OutputPin,
{
    // --- Stop on any driver fault ---
    // Short circuits and over-temperature latch until the driver is disabled,
    // so the demo shuts everything down and reports which fault fired.
    let fault = motor.read_fault().expect("fault"); // Read fault bitmask, () → Result<u8, Error>
    if fault != 0 {
        motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
        println!("fault {:#04x}", fault);
        std::process::exit(1);
    }
}
