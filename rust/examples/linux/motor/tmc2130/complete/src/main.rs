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

fn main() {
    let mut chip = Chip::new("/dev/gpiochip0").expect("open gpio chip");
    let step = pin(&mut chip, 27, "tmc2130_step", LineRequestFlags::OUTPUT);
    let dir = pin(&mut chip, 22, "tmc2130_dir", LineRequestFlags::OUTPUT);
    let en = pin(&mut chip, 17, "tmc2130_en", LineRequestFlags::OUTPUT);
    let diag0 = pin(&mut chip, 23, "tmc2130_diag0", LineRequestFlags::INPUT);
    let diag1 = pin(&mut chip, 24, "tmc2130_diag1", LineRequestFlags::INPUT);

    let mut motor = Tmc2130Full::new(open_spi(), step, dir, Delay, 0.11, Some(en), Some(diag0), Some(diag1))
        .expect("init"); // Create TMC2130 Full driver, (spi, step_pin, dir_pin, delay, r_sense_ohm=0.11 Ω, en_pin, diag0_pin, diag1_pin) → Result
      // reads IOIN to verify the chip, writes the default configuration with mirror checks

    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>
                                     // drives ENN low, waits 100 ms
    motor.set_current(0.8).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
                                              // converts amperes to the 0–31 scale, picks vsense automatically
    motor.set_hold_current(0.2).expect("hold"); // Set hold current, (rms_a A RMS) → Result<(), Error>
                                                // overrides the automatic one-third hold current
    motor.set_current_delay(6, 10).expect("delay"); // Set power-down timing, (ihold_delay 0–15, tpowerdown 0–255) → Result<(), Error>
                                                    // IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
    motor.set_microsteps(32).expect("microsteps"); // Set microstep resolution, (n 1–256) → Result<(), Error>
                                                   // writes CHOPCONF.MRES
    motor.set_interpolation(true).expect("intpol"); // Interpolate to 256 microsteps, (enabled) → Result<(), Error>
                                                    // sets CHOPCONF.intpol
    motor.set_chopper_mode(false).expect("chopper"); // Choose chopper, (spreadcycle) → Result<(), Error>
                                                     // false enables StealthChop below TPWMTHRS
    motor.set_stealthchop_threshold(500).expect("tpwmthrs"); // Set StealthChop velocity limit, (tpwmthrs 20 bit) → Result<(), Error>
                                                             // faster than this the chip switches to SpreadCycle
    motor.set_high_velocity_threshold(0).expect("thigh"); // Set THIGH, (thigh 20 bit) → Result<(), Error>
                                                          // 0 keeps the high-velocity features off
    motor.configure_chopper(3, 2, 4, 1).expect("chopper fields"); // Configure SpreadCycle timing, (toff, tbl, hstrt, hend) → Result<(), Error>
                                                                  // direct CHOPCONF fields with chm = 0
    motor.configure_constant_off_time(3, 2, 4, 0, false, false).expect("chm1"); // Configure constant off time, (toff, tbl, tfd, offset, disfdcc, rndtf) → Result<(), Error>
                                                                                // chm = 1 with fast decay
    motor.configure_chopper(3, 2, 4, 1).expect("chopper fields"); // Back to SpreadCycle, (toff, tbl, hstrt, hend) → Result<(), Error>
                                                                  // chm = 0 again
    motor.configure_high_velocity(false, false).expect("vhigh"); // Behaviour above THIGH, (vhighchm, vhighfs) → Result<(), Error>
                                                                 // both off
    motor.set_chopper_sync(0).expect("sync"); // Set chopSync2, (sync 0–15) → Result<(), Error>
                                              // 0 = off
    motor.configure_stealthchop(true, false, 200, 1, 0).expect("pwm"); // Configure StealthChop, (autoscale, symmetric, pwm_ampl, pwm_grad, pwm_freq) → Result<(), Error>
                                                                       // direct PWMCONF fields
    motor.set_freewheel(0).expect("freewheel"); // Set standstill mode, (mode 0–3) → Result<(), Error>
                                                // PWMCONF.freewheel, only effective when IHOLD is 0
    motor.set_vsense(false).expect("vsense"); // Select sense range, (high_sensitivity) → Result<(), Error>
                                              // false keeps the 0.325 V full scale; disables automatic selection
    motor.set_internal_rsense(false).expect("rsense"); // Internal sense resistors, (enabled) → Result<(), Error>
                                                       // keep false on boards with external resistors
    motor.set_analog_scaling(false).expect("analog"); // Current reference source, (enabled) → Result<(), Error>
                                                      // false uses the internal reference
    motor.set_direction_inverted(false).expect("shaft"); // Invert direction, (inverted) → Result<(), Error>
                                                         // GCONF.shaft
    motor.configure_diag([false, false, true, false, true, false], false, false).expect("diag"); // Route events to DIAG pins, (flags [diag0_error, diag0_otpw, diag0_stall, diag1_stall, diag1_index, diag1_onstate], diag0_pushpull, diag1_pushpull) → Result<(), Error>
                                                                                                 // GCONF bits 5–13
    motor.set_double_edge(false).expect("dedge"); // Step on both edges, (enabled) → Result<(), Error>
                                                  // CHOPCONF.dedge
    motor.set_short_protection(true).expect("short"); // Short-to-GND protection, (short_gnd) → Result<(), Error>
                                                      // true keeps the protection enabled
    motor.set_small_hysteresis(false).expect("hyst"); // Comparison hysteresis, (enabled) → Result<(), Error>
                                                      // false = 1/16
    motor.set_emergency_stop(false).expect("stop"); // DCIN emergency stop, (enabled) → Result<(), Error>
                                                    // GCONF.stop_enable
    motor.set_stall_threshold(0).expect("sgt"); // Set StallGuard2 threshold, (sgt -64..63) → Result<(), Error>
                                                // lower = more sensitive
    motor.set_stallguard_filter(false).expect("sfilt"); // StallGuard2 filter, (enabled) → Result<(), Error>
                                                        // COOLCONF.sfilt
    motor.set_coolstep_threshold(1000).expect("tcoolthrs"); // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → Result<(), Error>
                                                            // active while TCOOLTHRS >= TSTEP >= THIGH
    motor.configure_coolstep(5, 2, 1, 1, 0).expect("coolstep"); // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → Result<(), Error>
                                                                // semin = 0 would disable CoolStep

    motor.move_steps(200, 150.0).expect("move"); // Move, (full_steps, full_steps_per_s full steps/s) → Result<(), Error>
                                                 // blocking, software-timed STEP pulses
    motor.step(400, 1000).expect("step"); // Pulse the STEP pin, (count, delay_us µs) → Result<(), Error>
                                          // negative counts reverse via DIR
    println!("position {}", motor.microstep_position().unwrap()); // Read microstep counter, () → Result<u16, Error> 0–1023
                                                                  // MSCNT
    println!("currents {:?}", motor.read_coil_currents().unwrap()); // Read coil currents, () → Result<(i16, i16), Error>
                                                                    // signed (CUR_A, CUR_B) from MSCURACT
    println!("tstep {}", motor.read_tstep().unwrap()); // Read step interval, () → Result<u32, Error> clocks
                                                       // time between 1/256 microsteps
    println!("scale {}", motor.read_current_scale().unwrap()); // Read actual current scale, () → Result<u8, Error> 0–31
                                                               // DRV_STATUS.CS_ACTUAL
    println!("sg_result {}", motor.read_stallguard().unwrap()); // Read StallGuard2 result, () → Result<u16, Error> 0–1023
                                                                // SG_RESULT, 0 = highest load
    println!("stalled {}", motor.is_stalled().unwrap()); // Read stall flag, () → Result<bool, Error>
                                                         // DRV_STATUS.StallGuard
    println!("diag0 {}", motor.read_diag(0).unwrap()); // Read DIAG0 pin, (n 0 or 1) → Result<bool, Error>
                                                       // needs the diag pin to be wired
    println!("pwm_scale {}", motor.read_pwm_scale().unwrap()); // Read PWM scaler, () → Result<u8, Error> 0–255
                                                               // PWM_SCALE
    println!("lost_steps {}", motor.read_lost_steps().unwrap()); // Read skipped DcStep steps, () → Result<u32, Error>
                                                                 // LOST_STEPS
    println!("status {:?}", motor.read_status().unwrap()); // Read driver status, () → Result<Tmc2130Status, Error>
                                                          // decoded DRV_STATUS
    println!("standstill {}", motor.is_standstill().unwrap()); // Read standstill flag, () → Result<bool, Error>
                                                               // DRV_STATUS.stst
    println!("fault {}", motor.read_fault().unwrap()); // Read fault bitmask, () → Result<u8, Error>
                                                       // TMC2130_FAULT_* constants, 0 = none
    println!("gstat {:?}", motor.read_global_status().unwrap()); // Read GSTAT, () → Result<Tmc2130GlobalStatus, Error>
                                                                 // reset, drv_err, uv_cp; the chip clears them on read
    println!("ioin {:?}", motor.read_ioin().unwrap()); // Read IOIN, () → Result<Tmc2130Ioin, Error>
                                                       // raw word, pin states and silicon version
    println!("gconf {:#x}", motor.read_register(0x00).unwrap()); // Read a raw register, (reg) → Result<u32, Error>
                                                                 // any readable register
    motor.write_register(0x13, 500, true).expect("raw write"); // Write a raw register, (reg, value, verify) → Result<(), Error>
                                                               // here TPWMTHRS again, with the mirror check

    motor.set_dcstep(0, 0, 0).expect("dcstep"); // Configure DcStep, (vdcmin, dc_time, dc_sg) → Result<(), Error>
                                                // all zero keeps DcStep off
    motor.set_direct_mode(true).expect("direct"); // Direct coil-current mode, (enabled) → Result<(), Error>
                                                  // GCONF.direct_mode
    motor.set_coil_currents(100, 0).expect("xdirect"); // Write XDIRECT, (coil_a, coil_b -255..255) → Result<(), Error>
                                                       // scaled by IHOLD
    std::thread::sleep(std::time::Duration::from_millis(500));
    motor.set_direct_mode(false).expect("direct off"); // Back to STEP/DIR, (enabled) → Result<(), Error>
                                                       // GCONF.direct_mode = 0
    let custom_table: Option<([u32; 8], u32, u32)> = None; // replace with Some((mslut, mslutsel, mslutstart)) to program a table
    if let Some((mslut, sel, start)) = custom_table {
        if motor.microstep_position().unwrap() == 0 {
            motor.write_microstep_table(&mslut, sel, start).expect("mslut"); // Program the microstep table, (mslut 8 words, mslutsel, mslutstart) → Result<(), Error>
                                                                             // MSLUT0..7, MSLUTSEL, MSLUTSTART; call at MSCNT = 0
        }
    }
    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
                                       // drives ENN high; the motor freewheels
    println!("===DONE: 0 passed, 0 failed===");
}
