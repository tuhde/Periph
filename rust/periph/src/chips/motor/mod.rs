pub mod drv8830;
pub use drv8830::{
    Drv8830Minimal, Drv8830Full, Drv8830Error, Direction as Drv8830Direction,
    Output as Drv8830Output, Fault as Drv8830Fault,
    DRV8830_I2C_ADDRESS, DRV8830_VREF, DRV8830_VSET_MIN, DRV8830_VSET_MAX,
};
