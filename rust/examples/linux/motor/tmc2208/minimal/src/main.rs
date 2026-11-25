use linux_embedded_hal::Delay;
use periph::chips::motor::{NoPin, Tmc2208Minimal};
use periph::connection::uart_linux::LinuxUart;

fn main() {
    let port = std::env::var("UART_PORT").unwrap_or_else(|_| "/dev/ttyS0".to_string());
    let uart = LinuxUart::open(&port, 115_200).expect("open serial port");
    let mut delay = Delay;

    let mut motor = Tmc2208Minimal::new(uart, &mut delay, 0.11, None::<NoPin>, true).expect("init"); // Create TMC2208 driver, (uart, delay, r_sense_ohm=0.11 Ω, en_pin=None, echo=true) → Result

    motor.set_current(0.8).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
    for speed in [200.0_f32, -200.0] {
        motor.run(speed).expect("run"); // Spin with the internal generator, (steps_per_s full steps/s, sign = direction) → Result<(), Error>
        std::thread::sleep(std::time::Duration::from_secs(3));
        println!("standstill: {}", motor.is_standstill().expect("stst")); // Read standstill flag, () → Result<bool, Error>
        println!("fault: {}", motor.read_fault().expect("fault")); // Read fault bitmask, () → Result<u8, Error>
    }

    motor.stop().expect("stop"); // Stop the motor, () → Result<(), Error>
    println!("===DONE: 0 passed, 0 failed===");
}
