pub mod apds9960;
pub use apds9960::{Apds9960Minimal, Apds9960Full};

pub mod apds_9930;
pub use apds_9930::{Apds9930Minimal, Apds9930Full, Status};

pub mod max44009;
pub use max44009::{
    MAX44009Minimal, MAX44009Full, MAX44009Config, Max44009Error, MAX44009_I2C_ADDRESS,
    MAX44009_I2C_ADDRESS_A0_HIGH, MAX44009_SOURCE_THRESHOLD,
};
