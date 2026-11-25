use linux_embedded_hal::Delay;
use periph::chips::motor::{NoPin, Tmc2208Full};
use periph::connection::uart_linux::LinuxUart;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let port = std::env::var("UART_PORT").unwrap_or_else(|_| "/dev/ttyS0".to_string());
    let baud: u32 = std::env::var("UART_BAUD").ok().and_then(|v| v.parse().ok()).unwrap_or(115_200);
    let uart = LinuxUart::open(&port, baud).expect("open serial port");
    let mut delay = Delay;

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut motor = Tmc2208Full::<_, NoPin, NoPin, NoPin>::new(uart, &mut delay, 0.11, None, true, None, None, 12_000_000, false)
        .expect("init"); // Create TMC2208 Full driver, (uart, delay, r_sense_ohm=0.11 Ω, en_pin, echo, step_pin, dir_pin, fclk_hz, tmc2224) → Result
    check_true!(true, "init", passed, failed);

    let ioin = motor.read_ioin().unwrap(); // Read IOIN, () → Result<Tmc2208Ioin, Error>
    check_true!(ioin.version != 0x00 && ioin.version != 0xFF, "ioin_version", passed, failed);
    check_true!(ioin.sel_a, "ioin_sel_a", passed, failed);
    let before = motor.read_ifcnt().unwrap(); // Read write counter, () → Result<u8, Error>
    motor.set_current(0.5).unwrap(); // Set run current, (rms_a A RMS) → Result<(), Error>
    check_true!(motor.read_ifcnt().unwrap() == before.wrapping_add(2), "ifcnt_advances", passed, failed);
    motor.set_microsteps(16).unwrap(); // Set microstep resolution, (n 1–256) → Result<(), Error>
    check_true!((motor.read_register(0x6C).unwrap() >> 24) & 0xF == 4, "mres_readback", passed, failed);
    check_true!(motor.read_register(0x00).unwrap() & 0x40 == 0x40, "gconf_pdn_disable", passed, failed);
    motor.enable().unwrap(); // Power stage on, () → Result<(), Error>
    motor.run(100.0).unwrap(); // Spin with the internal generator, (steps_per_s) → Result<(), Error>
    std::thread::sleep(std::time::Duration::from_secs(1));
    check_true!(!motor.is_standstill().unwrap(), "moving", passed, failed);
    check_true!(motor.read_tstep().unwrap() > 0, "tstep_nonzero", passed, failed);
    check_true!(motor.read_fault().unwrap() == 0, "no_fault", passed, failed);
    motor.stop().unwrap(); // Stop the motor, () → Result<(), Error>
    std::thread::sleep(std::time::Duration::from_millis(500));
    motor.disable().unwrap(); // Power stage off, () → Result<(), Error>
    check_true!(motor.read_status().unwrap().cs_actual <= 31, "status_decoded", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 {
        std::process::exit(1);
    }
}
