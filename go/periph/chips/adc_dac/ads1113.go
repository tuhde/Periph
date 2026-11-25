package adcdac

import "github.com/tuhde/Periph/go/periph/connection"

// ADS1113I2CAddress is the default 7-bit I²C address (ADDR = GND). The ADDR strap
// selects any address in 0x48-0x4B.
const ADS1113I2CAddress uint8 = 0x48

// ADS1113 interrupt sources.
const (
	ADS1113SourceConversionReady uint8 = ADS111xSourceConversionReady
	ADS1113SourceComparator      uint8 = ADS111xSourceComparator
)

// ADS1113 data-rate codes (DR[2:0]).
const (
	ADS1113DR8   uint8 = 0
	ADS1113DR16  uint8 = 1
	ADS1113DR32  uint8 = 2
	ADS1113DR64  uint8 = 3
	ADS1113DR128 uint8 = 4 // default
	ADS1113DR250 uint8 = 5
	ADS1113DR475 uint8 = 6
	ADS1113DR860 uint8 = 7
)

// ADS1113Minimal is the minimal ADS1113 interface: single-shot reads at ±2.048 V,
// 128 SPS.
//
// ADS1113 is a 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), fixed ±2.048 V) from Texas Instruments, with four I²C
// addresses (0x48-0x4B) via the ADDR strap and no ID register. Registers are
// 16-bit big-endian behind a write-only Address Pointer. The implementation is
// shared with its siblings in ads111x.go.
type ADS1113Minimal struct {
	*ads111xBase
}

// NewADS1113Minimal creates a ADS1113Minimal. No bus traffic: every conversion writes
// the full Config word and leaves the chip in power-down.
func NewADS1113Minimal(conn connection.RegisterConnection) (*ADS1113Minimal, error) {
	return &ADS1113Minimal{newADS111xBase(conn)}, nil
}

// ReadRaw runs a single-shot conversion of AIN0 - AIN1 and returns the signed
// 16-bit code.
func (d *ADS1113Minimal) ReadRaw() (int16, error) {
	return d.convert(0)
}

// ReadVoltage runs a single-shot conversion and returns volts (raw × 62.5 µV
// at the default range).
func (d *ADS1113Minimal) ReadVoltage() (float32, error) {
	raw, err := d.ReadRaw()
	return float32(raw) * 0.0000625, err
}

// ADS1113Full extends ADS1113Minimal with data rate, continuous conversion, status and
// reset.
type ADS1113Full struct {
	*ADS1113Minimal
}

// NewADS1113Full creates a ADS1113Full. No bus traffic.
func NewADS1113Full(conn connection.RegisterConnection) (*ADS1113Full, error) {
	m, err := NewADS1113Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &ADS1113Full{m}, nil
}

// ReadVoltage runs a single-shot conversion and returns volts at the fixed ±2.048 V range.
func (d *ADS1113Full) ReadVoltage() (float32, error) {
	raw, err := d.ReadRaw()
	return float32(raw) * d.lsb(), err
}

// StartContinuous starts continuous conversion at the current data rate
// (MODE <- 0).
func (d *ADS1113Full) StartContinuous() error { return d.startContinuous(0) }

// FullScale returns ±FSR of the current gain in volts.
func (d *ADS1113Full) FullScale() float32 { return d.fullScale() }

// LSB returns the size of one code in volts (FullScale / 32768).
func (d *ADS1113Full) LSB() float32 { return d.lsb() }

// SetDataRate sets the data rate (ADS1113DR*, 0-7).
func (d *ADS1113Full) SetDataRate(dr uint8) error { return d.setDataRate(dr) }

// GetDataRate returns the configured DR code.
func (d *ADS1113Full) GetDataRate() uint8 { return d.dr }

// DataRateSps returns the configured data rate in samples per second.
func (d *ADS1113Full) DataRateSps() uint16 { return ads111xRates[d.dr] }

// ReadContinuousRaw reads the most recent completed result without touching
// Config.
func (d *ADS1113Full) ReadContinuousRaw() (int16, error) { return d.readContinuousRaw() }

// ReadContinuous reads the most recent continuous result in volts.
func (d *ADS1113Full) ReadContinuous() (float32, error) {
	raw, err := d.readContinuousRaw()
	return float32(raw) * d.lsb(), err
}

// StopContinuous returns to single-shot / power-down mode (MODE <- 1).
func (d *ADS1113Full) StopContinuous() error { return d.stopContinuous() }

// IsConverting reports whether CONFIG.OS reads 0 (single-shot mode only).
func (d *ADS1113Full) IsConverting() (bool, error) { return d.isConverting() }

// ReadConfig returns the raw 16-bit CONFIG register.
func (d *ADS1113Full) ReadConfig() (uint16, error) { return d.readReg(ads111xRegConfig) }

// Reset writes the reset values back to CONFIG and clears the
// cached settings. It is not an I²C general-call reset.
func (d *ADS1113Full) Reset() error { return d.reset(false) }
