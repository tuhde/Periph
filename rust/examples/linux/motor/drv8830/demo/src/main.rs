//! Battery-powered toy motor controller: holds a regulated 3.0 V forward,
//! then 2.0 V reverse, printing the commanded output every second — the
//! DRV8830 keeps that average voltage constant as the battery sags. Brakes,
//! then coasts. After every drive() the fault register is checked; a fault
//! (e.g. a stalled motor tripping ILIMIT) stops the motor and clears it.

use linux_embedded_hal::I2cdev;
use periph::chips::motor::{Drv8830Full, DRV8830_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn check_fault(motor: &mut Drv8830Full<I2cdev>) {
    // --- Recover from a fault instead of leaving the bridge latched off ---
    // OCP and ILIMIT disable the H-bridge until CLEAR is written; stop first
    // so the motor does not lurch back to the old command on clear.
    let f = motor.read_fault().expect("read_fault"); // Read fault status, () → Fault
    if f.fault {
        println!("fault: ocp={} uvlo={} ots={} ilimit={}", f.ocp, f.uvlo, f.ots, f.ilimit);
        motor.stop().expect("stop"); // Coast to standby, () → ()
        motor.clear_fault().expect("clear_fault"); // Clear fault bits, () → ()
    }
}

fn run(motor: &mut Drv8830Full<I2cdev>, voltage: f32, seconds: u32) {
    // --- Hold a regulated voltage and watch it stay put ---
    // The chip PWM-regulates the bridge against VCC internally, so the
    // commanded voltage (and motor speed) holds while the battery discharges.
    motor.drive(voltage).expect("drive"); // Drive at regulated voltage, (voltage V, signed) → ()
    check_fault(motor);
    for _ in 0..seconds {
        sleep(Duration::from_secs(1));
        let out = motor.read_output().expect("read_output"); // Read back CONTROL, () → Output {voltage V, direction}
        println!("{:<7?} {:.2} V", out.direction, out.voltage);
    }
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut motor = Drv8830Full::new(dev, DRV8830_I2C_ADDRESS).expect("init DRV8830"); // Create DRV8830 Full driver, (i2c, addr=0x60)

    run(&mut motor, 3.0, 5);
    run(&mut motor, -2.0, 5);

    // --- Stop quickly, then release ---
    // Braking shorts the winding for a fast stop; coasting afterwards removes
    // the load so the motor does not sit shorted indefinitely.
    motor.brake().expect("brake"); // Short-brake, () → ()
    sleep(Duration::from_millis(500));
    motor.stop().expect("stop"); // Coast to standby, () → ()
}
