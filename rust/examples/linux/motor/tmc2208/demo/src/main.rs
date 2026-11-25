//! Demo for the TMC2208 -- a silent desk-fan / camera-slider controller.
//!
//! Enables the driver at 0.8 A with 16 microsteps, proves the UART link by
//! reading IOIN.VERSION and IFCNT, then ramps the internal step generator from
//! 0 to 600 full steps/s in 100 steps/s increments (the chip has no ramp of its
//! own) printing StealthChop/SpreadCycle and CS_ACTUAL at each step. TPWMTHRS
//! switches to SpreadCycle above 400 steps/s. After holding speed for 3 s it
//! ramps down, reverses and repeats once. Any fault stops the motor, disables
//! the driver and exits.

use linux_embedded_hal::{CdevPin, Delay};
use periph::chips::motor::{NoPin, Tmc2208Full};
use periph::connection::uart_linux::LinuxUart;
use std::time::Duration;

type Motor = Tmc2208Full<LinuxUart, NoPin, NoPin, NoPin>;

const MICROSTEPS: u16 = 16;
const SPREAD_ABOVE: u32 = 400;

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

fn ramp(motor: &mut Motor, from: i32, to: i32, sign: f32) {
    // --- Ramp the internal generator in 100 steps/s increments ---
    // VACTUAL has no acceleration of its own, so the demo steps the speed
    // and reports whether the chip is in StealthChop or SpreadCycle.
    let inc = if to >= from { 100 } else { -100 };
    let mut speed = from;
    loop {
        motor.run(sign * speed as f32).expect("run"); // Spin with the internal generator, (steps_per_s full steps/s) → Result<(), Error>
        std::thread::sleep(Duration::from_millis(500));
        let st = motor.read_status().expect("status"); // Read driver status, () → Result<Tmc2208Status, Error>
        println!("{} steps/s {} CS={}", speed, if st.stealth { "stealth" } else { "spread" }, st.cs_actual);
        guard(motor);
        if speed == to {
            break;
        }
        speed += inc;
    }
}

fn main() {
    let port = std::env::var("UART_PORT").unwrap_or_else(|_| "/dev/ttyS0".to_string());
    let uart = LinuxUart::open(&port, 115_200).expect("open serial port");
    let mut delay = Delay;
    let mut motor: Motor = Tmc2208Full::new(
        uart, &mut delay, 0.11, None, true, None, None, 12_000_000, false,
    ).expect("init"); // Create TMC2208 Full driver, (uart, delay, r_sense_ohm=0.11 Ω, en_pin, echo, step_pin, dir_pin, fclk_hz, tmc2224) → Result
    let _: Option<CdevPin> = None;

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
    // before any motion is attempted.
    let ioin = motor.read_ioin().expect("ioin"); // Read IOIN, () → Result<Tmc2208Ioin, Error>
    println!("version {:#04x}, IFCNT {}", ioin.version, motor.read_ifcnt().expect("ifcnt")); // Read write counter, () → Result<u8, Error>
    motor.set_current(0.8).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
    motor.set_microsteps(MICROSTEPS).expect("microsteps"); // Set microstep resolution, (n 1–256) → Result<(), Error>
    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>

    // --- Quiet at low speed, strong at high speed ---
    // StealthChop is silent but loses torque at speed; TPWMTHRS makes the chip
    // change to SpreadCycle once TSTEP drops below the value for 400 steps/s.
    motor.set_stealthchop_threshold(12_000_000 / (SPREAD_ABOVE * 256)).expect("tpwmthrs"); // Set StealthChop velocity limit, (tpwmthrs 20 bit) → Result<(), Error>

    for sign in [1.0_f32, -1.0] {
        ramp(&mut motor, 0, 600, sign);
        std::thread::sleep(Duration::from_secs(3));
        ramp(&mut motor, 600, 0, sign);
        motor.stop().expect("stop"); // Stop the motor, () → Result<(), Error>
        std::thread::sleep(Duration::from_secs(1));
    }

    // --- Shut down and report ---
    // Temperature comparators and GSTAT show whether the run stressed the driver.
    println!("temperature flags {}", motor.read_temperature_flags().expect("temp")); // Read temperature flags, () → Result<u8, Error> 0–15
    println!("global status {:?}", motor.read_global_status(false).expect("gstat")); // Read GSTAT, (clear) → Result<Tmc2208GlobalStatus, Error>
    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
    println!("===DONE: 0 passed, 0 failed===");
}
