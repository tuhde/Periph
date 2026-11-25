use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay};
use periph::chips::motor::Tmc2209Full;
use periph::connection::uart_linux::LinuxUart;

fn pin(chip: &mut Chip, line: u32, name: &str) -> CdevPin {
    let handle = chip.get_line(line).expect("get line")
        .request(LineRequestFlags::OUTPUT, 0, name).expect("request line");
    CdevPin::new(handle).expect("pin")
}

fn input_pin(chip: &mut Chip, line: u32, name: &str) -> CdevPin {
    let handle = chip.get_line(line).expect("get line")
        .request(LineRequestFlags::INPUT, 0, name).expect("request line");
    CdevPin::new(handle).expect("pin")
}

fn main() {
    let port = std::env::var("UART_PORT").unwrap_or_else(|_| "/dev/ttyS0".to_string());
    let uart = LinuxUart::open(&port, 115_200).expect("open serial port");
    let mut delay = Delay;
    let mut chip = Chip::new("/dev/gpiochip0").expect("open gpio chip");
    let en = pin(&mut chip, 17, "tmc2209_en");
    let step = pin(&mut chip, 27, "tmc2209_step");
    let dir = pin(&mut chip, 22, "tmc2209_dir");
    let diag = input_pin(&mut chip, 23, "tmc2209_diag");

    let mut motor = Tmc2209Full::<_, CdevPin, CdevPin, CdevPin, CdevPin>::new(
        uart, &mut delay, 0.11, 0, Some(en), true, Some(step), Some(dir), Some(diag), false, 12_000_000,
    ).expect("init"); // Create TMC2209 Full driver, (uart, delay, r_sense_ohm=0.11 Ω, address=0, en_pin, echo=true, step_pin, dir_pin, diag_pin, multi_node=false, fclk_hz=12000000 Hz) → Result
      // reads IOIN to verify the chip, writes the default configuration and checks IFCNT

    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>
                                     // drives ENN low
    motor.set_current(0.8).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
                                              // converts amperes to the 0–31 scale; hold current becomes a third
    motor.set_hold_current(0.2).expect("hold"); // Set hold current, (rms_a A RMS) → Result<(), Error>
                                                // overrides the automatic one-third hold current
    motor.set_current_delay(6, 20).expect("delay"); // Set power-down timing, (ihold_delay 0–15, tpowerdown 0–255) → Result<(), Error>
                                                    // IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
    motor.set_microsteps(32).expect("microsteps"); // Set microstep resolution, (n 1–256) → Result<(), Error>
                                                   // writes CHOPCONF.MRES
    motor.set_interpolation(true).expect("intpol"); // Interpolate to 256 microsteps, (enabled) → Result<(), Error>
                                                    // sets CHOPCONF.intpol
    motor.set_chopper_mode(false).expect("chopper"); // Choose chopper, (spreadcycle) → Result<(), Error>
                                                     // false selects StealthChop
    motor.set_stealthchop_threshold(300).expect("tpwmthrs"); // Set StealthChop velocity limit, (tpwmthrs 20 bit) → Result<(), Error>
                                                             // faster than this the chip switches to SpreadCycle
    motor.configure_chopper(3, 2, 5, 0).expect("chop"); // Configure chopper timing, (toff, tbl, hstrt, hend) → Result<(), Error>
                                                        // direct CHOPCONF fields
    motor.configure_stealthchop(true, true, 36, 14, 1, 4, 12).expect("pwm"); // Configure StealthChop, (autoscale, autograd, pwm_ofs, pwm_grad, pwm_freq, pwm_reg, pwm_lim) → Result<(), Error>
                                                                             // direct PWMCONF fields
    motor.set_freewheel(0).expect("freewheel"); // Set standstill mode, (mode 0–3) → Result<(), Error>
                                                // PWMCONF.freewheel, only effective when IHOLD is 0
    motor.set_vsense(false).expect("vsense"); // Select sense range, (high_sensitivity) → Result<(), Error>
                                              // false keeps the 0.325 V full scale
    motor.set_analog_scaling(true).expect("analog"); // Current reference source, (enabled) → Result<(), Error>
                                                     // true takes the reference from VREF
    motor.set_direction_inverted(false).expect("shaft"); // Invert direction, (inverted) → Result<(), Error>
                                                         // GCONF.shaft
    motor.set_index_output(0).expect("index"); // Select INDEX output, (mode 0–2) → Result<(), Error>
                                               // 0 first microstep, 1 pre-warning, 2 step pulses
    motor.set_driver_protection(true, true).expect("protection"); // Short protections, (short_ls, short_gnd) → Result<(), Error>
                                                                  // true keeps the protection enabled
    motor.set_double_edge(false).expect("dedge"); // Step on both edges, (enabled) → Result<(), Error>
                                                  // CHOPCONF.dedge
    motor.set_stall_threshold(60).expect("sgthrs"); // Set StallGuard4 threshold, (sgthrs 0–255) → Result<(), Error>
                                                    // stall when SG_RESULT <= 2 * SGTHRS
    motor.set_coolstep_threshold(1000).expect("tcoolthrs"); // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → Result<(), Error>
                                                            // active while TCOOLTHRS >= TSTEP > TPWMTHRS
    motor.configure_coolstep(5, 2, 1, 1, 0).expect("coolstep"); // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → Result<(), Error>
                                                                // semin = 0 would disable CoolStep
    motor.set_send_delay(0).expect("senddelay"); // Set reply delay, (n 0–15) → Result<(), Error>
                                                 // NODECONF.SENDDELAY

    motor.run(300.0).expect("run"); // Spin with the internal generator, (steps_per_s full steps/s) → Result<(), Error>
                                    // converts to VACTUAL using the 12 MHz clock
    std::thread::sleep(std::time::Duration::from_secs(2));
    println!("position {}", motor.microstep_position().unwrap()); // Read microstep counter, () → Result<u16, Error> 0–1023
                                                                  // MSCNT
    println!("currents {:?}", motor.read_coil_currents().unwrap()); // Read coil currents, () → Result<(i16, i16), Error> table values
                                                                    // two signed 9-bit MSCURACT fields
    println!("sg_result {}", motor.read_stallguard().unwrap()); // Read StallGuard4 result, () → Result<u16, Error> 0–510
                                                                // SG_RESULT, higher = less load
    println!("stalled {}", motor.is_stalled().unwrap()); // Read DIAG pin, () → Result<bool, Error>
                                                         // true on a stall or a driver error
    println!("tstep {}", motor.read_tstep().unwrap()); // Read step interval, () → Result<u32, Error> clocks
                                                       // time between 1/256 microsteps
    println!("scale {}", motor.read_current_scale().unwrap()); // Read actual current scale, () → Result<u8, Error> 0–31
                                                               // DRV_STATUS.CS_ACTUAL
    println!("temp {}", motor.read_temperature_flags().unwrap()); // Read temperature flags, () → Result<u8, Error> 0–15
                                                                  // bit 0 t120 … bit 3 t157
    println!("status {:?}", motor.read_status().unwrap()); // Read driver status, () → Result<Tmc2209Status, Error>
                                                           // decoded DRV_STATUS
    println!("standstill {}", motor.is_standstill().unwrap()); // Read standstill flag, () → Result<bool, Error>
                                                               // DRV_STATUS.stst
    println!("fault {}", motor.read_fault().unwrap()); // Read fault bitmask, () → Result<u8, Error>
                                                       // FAULT_* constants, 0 = none
    motor.stop().expect("stop"); // Stop the motor, () → Result<(), Error>
                                 // VACTUAL = 0, STEP input takes over again

    motor.step(400, 1000, &mut delay).expect("step"); // Pulse the STEP pin, (count, delay_us µs, delay) → Result<(), Error>
                                                      // negative counts reverse via DIR
    println!("gstat {:?}", motor.read_global_status(true).unwrap()); // Read and clear GSTAT, (clear) → Result<Tmc2209GlobalStatus, Error>
                                                                     // reset, drv_err, uv_cp
    println!("ifcnt {}", motor.read_ifcnt().unwrap()); // Read write counter, () → Result<u8, Error>
                                                       // IFCNT
    println!("ioin {:?}", motor.read_ioin().unwrap()); // Read IOIN, () → Result<Tmc2209Ioin, Error>
                                                       // raw word, decoded pins and silicon version
    println!("otp {:#x}", motor.read_otp().unwrap()); // Read OTP image, () → Result<u32, Error>
                                                      // OTP_READ
    println!("pwm_auto {:?}", motor.read_pwm_auto().unwrap()); // Read StealthChop tuning, () → Result<Tmc2209PwmAuto, Error>
                                                               // PWM_SCALE and PWM_AUTO results
    println!("gconf {:#x}", motor.read_register(0x00).unwrap()); // Read a raw register, (reg) → Result<u32, Error>
                                                                 // any readable register
    motor.write_register(0x13, 300).expect("write"); // Write a raw register, (reg, value) → Result<(), Error>
                                                     // here TPWMTHRS again
    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
                                       // drives ENN high; the motor freewheels
    println!("===DONE: 0 passed, 0 failed===");
}
