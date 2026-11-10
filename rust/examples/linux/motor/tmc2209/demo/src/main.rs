//! Demo for the TMC2209 -- sensorless homing of a linear axis with StallGuard4.
//!
//! Enables the driver at 0.8 A with 16 microsteps, proves the UART link by
//! reading IOIN.VERSION (0x21) and IFCNT, then drives toward the end stop with
//! the internal step generator at 200 full steps/s. When SG_RESULT falls to
//! 2 * SGTHRS the axis has hit the stop: the demo stops, reverses for 2 s to
//! back off and finishes with a CoolStep move. Any fault stops the motor,
//! disables the driver and exits.

use linux_embedded_hal::{CdevPin, Delay};
use periph::chips::motor::{Tmc2209Full, Tmc2209NoPin};
use periph::connection::uart_linux::LinuxUart;
use std::time::Duration;

type Motor = Tmc2209Full<LinuxUart, Tmc2209NoPin, Tmc2209NoPin, Tmc2209NoPin, Tmc2209NoPin>;

const MICROSTEPS: u16 = 16;
const HOMING_SPEED: f32 = 200.0;
const SGTHRS: u8 = 60;

fn guard(motor: &mut Motor) {
    // --- Stop on any driver fault ---
    // Short circuits and over-temperature latch until the driver is disabled,
    // so the demo shuts everything down and reports which fault fired.
    let fault = motor.read_fault().expect("fault"); // Read fault bitmask, () → Result<u8, Error>
    if fault != 0 {
        motor.stop().expect("stop"); // Stop the motor, () → Result<(), Error>
        motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
        println!("fault {:#04x}", fault);
        std::process::exit(1);
    }
}

fn main() {
    let port = std::env::var("UART_PORT").unwrap_or_else(|_| "/dev/ttyS0".to_string());
    let uart = LinuxUart::open(&port, 115_200).expect("open serial port");
    let mut delay = Delay;
    let mut motor: Motor = Tmc2209Full::new(
        uart, &mut delay, 0.11, 0, None, true, None, None, None, false, 12_000_000,
    ).expect("init"); // Create TMC2209 Full driver, (uart, delay, r_sense_ohm=0.11 Ω, address=0, en_pin, echo, step_pin, dir_pin, diag_pin, multi_node, fclk_hz) → Result
    let _: Option<CdevPin> = None;

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
    // before any motion is attempted.
    let ioin = motor.read_ioin().expect("ioin"); // Read IOIN, () → Result<Tmc2209Ioin, Error>
    println!("version {:#04x}, IFCNT {}", ioin.version, motor.read_ifcnt().expect("ifcnt")); // Read write counter, () → Result<u8, Error>
    motor.set_current(0.8).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
    motor.set_microsteps(MICROSTEPS).expect("microsteps"); // Set microstep resolution, (n 1–256) → Result<(), Error>
    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>

    // --- Arm StallGuard4 for the homing speed ---
    // StallGuard needs StealthChop and TCOOLTHRS >= TSTEP > TPWMTHRS; TCOOLTHRS
    // is set just below the TSTEP of the homing speed so detection is active.
    motor.set_stall_threshold(SGTHRS).expect("sgthrs"); // Set StallGuard4 threshold, (sgthrs 0–255) → Result<(), Error>
    motor.set_coolstep_threshold(12_000_000 / (HOMING_SPEED as u32 * 256 / 2)).expect("tcoolthrs"); // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → Result<(), Error>

    // --- Drive toward the end stop ---
    // The first 0.5 s is spin-up, where SG_RESULT is not yet valid; afterwards
    // the demo polls SG_RESULT every 100 ms until it drops to 2 * SGTHRS.
    motor.run(HOMING_SPEED).expect("run"); // Spin with the internal generator, (steps_per_s full steps/s) → Result<(), Error>
    std::thread::sleep(Duration::from_millis(500));
    let sg = loop {
        let sg = motor.read_stallguard().expect("sg"); // Read StallGuard4 result, () → Result<u16, Error> 0–510
        println!("SG_RESULT {}", sg);
        if sg <= 2 * SGTHRS as u16 {
            break sg;
        }
        guard(&mut motor);
        std::thread::sleep(Duration::from_millis(100));
    };

    // --- End stop found: stop and back off ---
    // Reversing for 2 s moves the axis clear of the stop.
    motor.stop().expect("stop"); // Stop the motor, () → Result<(), Error>
    println!("stalled at SG_RESULT {}", sg);
    motor.run(-HOMING_SPEED).expect("run"); // Spin with the internal generator, (steps_per_s full steps/s) → Result<(), Error>
    std::thread::sleep(Duration::from_secs(2));
    motor.stop().expect("stop"); // Stop the motor, () → Result<(), Error>

    // --- Final move with CoolStep ---
    // CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
    motor.configure_coolstep(5, 2, 1, 1, 0).expect("coolstep"); // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → Result<(), Error>
    motor.run(HOMING_SPEED).expect("run"); // Spin with the internal generator, (steps_per_s full steps/s) → Result<(), Error>
    for _ in 0..10 {
        std::thread::sleep(Duration::from_millis(500));
        println!("CS_ACTUAL {}", motor.read_current_scale().expect("cs")); // Read actual current scale, () → Result<u8, Error> 0–31
        guard(&mut motor);
    }
    motor.stop().expect("stop"); // Stop the motor, () → Result<(), Error>

    // --- Shut down and report ---
    // Temperature comparators and GSTAT show whether the run stressed the driver.
    println!("temperature flags {}", motor.read_temperature_flags().expect("temp")); // Read temperature flags, () → Result<u8, Error> 0–15
    println!("global status {:?}", motor.read_global_status(false).expect("gstat")); // Read GSTAT, (clear) → Result<Tmc2209GlobalStatus, Error>
    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
    println!("===DONE: 0 passed, 0 failed===");
}
