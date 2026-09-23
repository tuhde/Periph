use linux_embedded_hal::I2cdev;
use periph::chips::motor::{Drv8830Full, DRV8830_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut motor = Drv8830Full::new(dev, DRV8830_I2C_ADDRESS).expect("init DRV8830"); // Create DRV8830 Full driver, (i2c, addr=0x60)
                                                                                       // one CONTROL read confirms presence; no writes

    motor.drive(2.5).expect("drive"); // Drive at regulated voltage, (voltage V, + = forward) → ()
                                      // maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
    sleep(Duration::from_secs(1));
    let out = motor.read_output().expect("read_output"); // Read back CONTROL, () → Output {voltage V, direction}
                                                         // decodes VSET to volts and IN1/IN2 to a Direction
    println!("commanded {:.2} V {:?}", out.voltage, out.direction);

    motor.drive(-1.5).expect("drive"); // Drive at regulated voltage, (voltage V, - = reverse) → ()
                                       // a negative voltage sets IN1=0, IN2=1
    sleep(Duration::from_secs(1));

    motor.set_output(37, true, false).expect("set_output"); // Write raw CONTROL fields, (vset 6–63, in1, in2) → Result<(), Drv8830Error>
                                                            // VSET 37 is ~2.97 V forward; codes 0–5 return InvalidVset
    sleep(Duration::from_secs(1));

    motor.brake().expect("brake"); // Short-brake, () → ()
                                   // IN1=IN2=1 drives both outputs high
    sleep(Duration::from_millis(500));
    motor.stop().expect("stop"); // Coast to standby, () → ()
                                 // IN1=IN2=0 leaves both outputs high-impedance

    let fault = motor.read_fault().expect("read_fault"); // Read fault status, () → Fault {fault, ocp, uvlo, ots, ilimit}
                                                         // does not clear — latched OCP/ILIMIT keep the bridge off
    println!("{:?}", fault);
    motor.clear_fault().expect("clear_fault"); // Clear fault bits, () → ()
                                               // writes CLEAR=1; re-enables a latched-off bridge

    let status = motor.poll_interrupt().expect("poll_interrupt"); // Poll fault status, () → Fault
                                                                  // same as read_fault(); never clears implicitly
    println!("poll {:?}", status);
}
