package adcdac

import "github.com/tuhde/Periph/go/periph/connection"

// ADS1115I2CAddress is the default 7-bit I²C address (ADDR = GND). The ADDR strap
// selects any address in 0x48-0x4B.
const ADS1115I2CAddress uint8 = 0x48

// ADS1115 interrupt sources.
const (
	ADS1115SourceConversionReady uint8 = ADS111xSourceConversionReady
	ADS1115SourceComparator      uint8 = ADS111xSourceComparator
)

// ADS1115 data-rate codes (DR[2:0]).
const (
	ADS1115DR8   uint8 = 0
	ADS1115DR16  uint8 = 1
	ADS1115DR32  uint8 = 2
	ADS1115DR64  uint8 = 3
	ADS1115DR128 uint8 = 4 // default
	ADS1115DR250 uint8 = 5
	ADS1115DR475 uint8 = 6
	ADS1115DR860 uint8 = 7
)

// ADS1115 full-scale range codes (PGA[2:0]).
const (
	ADS1115PGA6144 uint8 = 0 // ±6.144 V
	ADS1115PGA4096 uint8 = 1 // ±4.096 V
	ADS1115PGA2048 uint8 = 2 // ±2.048 V (default)
	ADS1115PGA1024 uint8 = 3 // ±1.024 V
	ADS1115PGA0512 uint8 = 4 // ±0.512 V
	ADS1115PGA0256 uint8 = 5 // ±0.256 V
)

// ADS1115 differential pairs for ReadDifferential*.
const (
	ADS1115Diff01 uint8 = 0
	ADS1115Diff03 uint8 = 1
	ADS1115Diff13 uint8 = 2
	ADS1115Diff23 uint8 = 3
)

// ADS1115 MUX codes for StartContinuous.
const (
	ADS1115MuxAIN0AIN1 uint8 = 0
	ADS1115MuxAIN0AIN3 uint8 = 1
	ADS1115MuxAIN1AIN3 uint8 = 2
	ADS1115MuxAIN2AIN3 uint8 = 3
	ADS1115MuxAIN0GND  uint8 = 4
	ADS1115MuxAIN1GND  uint8 = 5
	ADS1115MuxAIN2GND  uint8 = 6
	ADS1115MuxAIN3GND  uint8 = 7
)

// ADS1115 comparator mode, polarity and queue values.
const (
	ADS1115CompTraditional uint8 = 0
	ADS1115CompWindow      uint8 = 1
	ADS1115ActiveLow       uint8 = 0
	ADS1115ActiveHigh      uint8 = 1
	ADS1115Queue1          uint8 = 0
	ADS1115Queue2          uint8 = 1
	ADS1115Queue4          uint8 = 2
)

// ADS1115Minimal is the minimal ADS1115 interface: single-shot reads at ±2.048 V,
// 128 SPS.
//
// ADS1115 is a 16-bit ΔΣ ADC (four-channel multiplexer, PGA and comparator) from Texas Instruments, with four I²C
// addresses (0x48-0x4B) via the ADDR strap and no ID register. Registers are
// 16-bit big-endian behind a write-only Address Pointer. The implementation is
// shared with its siblings in ads111x.go.
type ADS1115Minimal struct {
	*ads111xBase
}

// NewADS1115Minimal creates a ADS1115Minimal. No bus traffic: every conversion writes
// the full Config word and leaves the chip in power-down.
func NewADS1115Minimal(conn connection.RegisterConnection) (*ADS1115Minimal, error) {
	return &ADS1115Minimal{newADS111xBase(conn)}, nil
}

// ReadRaw runs a single-shot conversion of AIN<channel> against GND (channel clamped to 0-3) and returns the signed
// 16-bit code.
func (d *ADS1115Minimal) ReadRaw(channel uint8) (int16, error) {
	return d.convert(4 + min(channel, 3))
}

// ReadVoltage runs a single-shot conversion and returns volts (raw × 62.5 µV
// at the default range).
func (d *ADS1115Minimal) ReadVoltage(channel uint8) (float32, error) {
	raw, err := d.ReadRaw(channel)
	return float32(raw) * 0.0000625, err
}

// ADS1115Full extends ADS1115Minimal with gain, data rate, differential reads, continuous conversion, status and
// reset, the comparator, thresholds and the ALERT/RDY interrupt API.
type ADS1115Full struct {
	*ADS1115Minimal
}

// NewADS1115Full creates a ADS1115Full. No bus traffic.
func NewADS1115Full(conn connection.RegisterConnection) (*ADS1115Full, error) {
	m, err := NewADS1115Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &ADS1115Full{m}, nil
}

// ReadVoltage runs a single-shot conversion and returns volts at the current gain.
func (d *ADS1115Full) ReadVoltage(channel uint8) (float32, error) {
	raw, err := d.ReadRaw(channel)
	return float32(raw) * d.lsb(), err
}

// ReadDifferentialRaw runs a single-shot signed differential conversion
// (pair = ADS1115Diff*, 0-3).
func (d *ADS1115Full) ReadDifferentialRaw(pair uint8) (int16, error) {
	if pair > 3 {
		return 0, ErrADS111xInvalidArgument
	}
	return d.convert(pair)
}

// ReadDifferential runs a single-shot differential conversion in volts.
func (d *ADS1115Full) ReadDifferential(pair uint8) (float32, error) {
	raw, err := d.ReadDifferentialRaw(pair)
	return float32(raw) * d.lsb(), err
}

// StartContinuous starts continuous conversion on any MUX code (0-7;
// ADS1115MuxAIN0GND ... ADS1115MuxAIN3GND single-ended).
func (d *ADS1115Full) StartContinuous(mux uint8) error { return d.startContinuous(mux) }

// SetGain sets the full-scale range (ADS1115PGA*, 0-5); thresholds are not
// rescaled.
func (d *ADS1115Full) SetGain(pga uint8) error { return d.setGain(pga) }

// GetGain returns the configured PGA code.
func (d *ADS1115Full) GetGain() uint8 { return d.pga }

// FullScale returns ±FSR of the current gain in volts.
func (d *ADS1115Full) FullScale() float32 { return d.fullScale() }

// LSB returns the size of one code in volts (FullScale / 32768).
func (d *ADS1115Full) LSB() float32 { return d.lsb() }

// SetDataRate sets the data rate (ADS1115DR*, 0-7).
func (d *ADS1115Full) SetDataRate(dr uint8) error { return d.setDataRate(dr) }

// GetDataRate returns the configured DR code.
func (d *ADS1115Full) GetDataRate() uint8 { return d.dr }

// DataRateSps returns the configured data rate in samples per second.
func (d *ADS1115Full) DataRateSps() uint16 { return ads111xRates[d.dr] }

// ReadContinuousRaw reads the most recent completed result without touching
// Config.
func (d *ADS1115Full) ReadContinuousRaw() (int16, error) { return d.readContinuousRaw() }

// ReadContinuous reads the most recent continuous result in volts.
func (d *ADS1115Full) ReadContinuous() (float32, error) {
	raw, err := d.readContinuousRaw()
	return float32(raw) * d.lsb(), err
}

// StopContinuous returns to single-shot / power-down mode (MODE <- 1).
func (d *ADS1115Full) StopContinuous() error { return d.stopContinuous() }

// IsConverting reports whether CONFIG.OS reads 0 (single-shot mode only).
func (d *ADS1115Full) IsConverting() (bool, error) { return d.isConverting() }

// ReadConfig returns the raw 16-bit CONFIG register.
func (d *ADS1115Full) ReadConfig() (uint16, error) { return d.readReg(ads111xRegConfig) }

// Reset writes the reset values back to CONFIG, LO_THRESH and HI_THRESH and clears the
// cached settings. It is not an I²C general-call reset.
func (d *ADS1115Full) Reset() error { return d.reset(true) }

// ConfigureComparator configures the comparator and routes it to ALERT/RDY.
// It returns ErrADS111xInvalidArgument for an out-of-range argument or when
// the last thresholds have high <= low.
func (d *ADS1115Full) ConfigureComparator(mode, polarity uint8, latch bool, queue uint8) error {
	return d.configureComparator(mode, polarity, latch, queue)
}

// DisableComparator sets COMP_QUE to 11; ALERT/RDY becomes high-impedance.
func (d *ADS1115Full) DisableComparator() error { return d.disableComparator() }

// SetLowThresholdRaw writes LO_THRESH (signed code, same scale as CONVERSION).
func (d *ADS1115Full) SetLowThresholdRaw(raw int16) error { return d.setLowThresholdRaw(raw) }

// SetHighThresholdRaw writes HI_THRESH (signed code, same scale as CONVERSION).
func (d *ADS1115Full) SetHighThresholdRaw(raw int16) error { return d.setHighThresholdRaw(raw) }

// SetThresholds writes both thresholds from volts at the current gain; it
// returns ErrADS111xInvalidArgument if high <= low.
func (d *ADS1115Full) SetThresholds(low, high float32) error { return d.setThresholds(low, high) }

// GetThresholdsRaw reads both thresholds back, sign-extended.
func (d *ADS1115Full) GetThresholdsRaw() (low, high int16, err error) { return d.getThresholdsRaw() }

// ClearAlert reads CONVERSION to release a latched comparator output.
func (d *ADS1115Full) ClearAlert() error { return d.clearAlert() }

// EnableInterrupt routes one source (ADS1115Source*) to ALERT/RDY; the other is
// disabled.
func (d *ADS1115Full) EnableInterrupt(source uint8) error { return d.enableInterrupt(source) }

// DisableInterrupt releases ALERT/RDY (COMP_QUE <- 11).
func (d *ADS1115Full) DisableInterrupt(source uint8) error { return d.disableComparator() }

// PollInterrupt returns the enabled source if ALERT/RDY is asserted, else 0.
// It samples the connection's IntPin when wired; without one,
// ADS1115SourceConversionReady falls back to CONFIG.OS (single-shot only). A
// latched comparator alert is cleared by reading CONVERSION.
func (d *ADS1115Full) PollInterrupt() (uint8, error) { return d.pollInterrupt() }

// OnInterrupt subscribes callback to ALERT/RDY events; it receives the
// PollInterrupt value. With the connection's IntPin wired it fires on every
// edge matching COMP_POL (configure the comparator first); otherwise a polling
// goroutine calls back when the status changes.
func (d *ADS1115Full) OnInterrupt(callback func(status uint8)) error { return d.onInterrupt(callback) }

// OffInterrupt unsubscribes and stops delivery.
func (d *ADS1115Full) OffInterrupt() error { return d.offInterrupt() }
