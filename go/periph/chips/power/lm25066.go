package power

import (
	"errors"
	"fmt"
	"math"
	"strings"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LM25066 PMBus command codes.
const (
	lm25066CmdOperation     uint8 = 0x01
	lm25066CmdClearFaults   uint8 = 0x03
	lm25066CmdCapability    uint8 = 0x19
	lm25066CmdVoutUVWarn    uint8 = 0x43
	lm25066CmdOTFault       uint8 = 0x4F
	lm25066CmdOTWarn        uint8 = 0x51
	lm25066CmdVinOVWarn     uint8 = 0x57
	lm25066CmdVinUVWarn     uint8 = 0x58
	lm25066CmdStatusByte    uint8 = 0x78
	lm25066CmdStatusWord    uint8 = 0x79
	lm25066CmdStatusVout    uint8 = 0x7A
	lm25066CmdStatusInput   uint8 = 0x7C
	lm25066CmdStatusTemp    uint8 = 0x7D
	lm25066CmdStatusCML     uint8 = 0x7E
	lm25066CmdStatusMfr     uint8 = 0x80
	lm25066CmdReadVin       uint8 = 0x88
	lm25066CmdReadVout      uint8 = 0x8B
	lm25066CmdReadTemp      uint8 = 0x8D
	lm25066CmdMfrID         uint8 = 0x99
	lm25066CmdMfrModel      uint8 = 0x9A
	lm25066CmdMfrRevision   uint8 = 0x9B
	lm25066CmdReadVaux      uint8 = 0xD0
	lm25066CmdReadIin       uint8 = 0xD1
	lm25066CmdReadPin       uint8 = 0xD2
	lm25066CmdIinWarn       uint8 = 0xD3
	lm25066CmdPinWarn       uint8 = 0xD4
	lm25066CmdReadPinPeak   uint8 = 0xD5
	lm25066CmdClearPinPeak  uint8 = 0xD6
	lm25066CmdGateMask      uint8 = 0xD7
	lm25066CmdAlertMask     uint8 = 0xD8
	lm25066CmdDeviceSetup   uint8 = 0xD9
	lm25066CmdBlockRead     uint8 = 0xDA
	lm25066CmdSamplesForAvg uint8 = 0xDB
	lm25066CmdReadAvgVin    uint8 = 0xDC
	lm25066CmdReadAvgVout   uint8 = 0xDD
	lm25066CmdReadAvgIin    uint8 = 0xDE
	lm25066CmdReadAvgPin    uint8 = 0xDF
	lm25066CmdBlackBox      uint8 = 0xE0
	lm25066CmdDiagnostic    uint8 = 0xE1
	lm25066CmdAvgBlockRead  uint8 = 0xE2
)

// ALERT_MASK bit positions used with LM25066Full.EnableInterrupt / DisableInterrupt (1 = masked).
const (
	LM25066SourceVoutUVWarn   uint16 = 0x8000
	LM25066SourceIinWarn      uint16 = 0x4000
	LM25066SourceVinUVWarn    uint16 = 0x2000
	LM25066SourceVinOVWarn    uint16 = 0x1000
	LM25066SourcePowerGood    uint16 = 0x0800
	LM25066SourceOTWarn       uint16 = 0x0400
	LM25066SourcePinOPWarn    uint16 = 0x0100
	LM25066SourceMosfetShort  uint16 = 0x0040
	LM25066SourceVinUVFault   uint16 = 0x0020
	LM25066SourceVinOVFault   uint16 = 0x0010
	LM25066SourceIinFault     uint16 = 0x0008
	LM25066SourceOTFault      uint16 = 0x0004
	LM25066SourceCMLFault     uint16 = 0x0002
	LM25066SourceCircuitBreak uint16 = 0x0001
)

// ErrLM25066BlockLength is returned when a block read has an unexpected count byte.
var ErrLM25066BlockLength = errors.New("lm25066: unexpected block length")

// ErrLM25066InvalidArgument is returned for a value the chip does not accept.
var ErrLM25066InvalidArgument = errors.New("lm25066: invalid argument")

// lm25066Coef holds PMBus DIRECT coefficients (m, b, R).
type lm25066Coef struct {
	m, b float64
	r    int
}

var (
	lm25066Volt = lm25066Coef{22070, -1800, -2}
	lm25066Vaux = lm25066Coef{3546, -3, 0}
	lm25066Temp = lm25066Coef{16000, 0, -3}
)

func (c lm25066Coef) fromY(y uint16) float64 {
	return (float64(y)*math.Pow(10, float64(-c.r)) - c.b) / c.m
}

func (c lm25066Coef) toY(x float64) uint16 {
	y := math.Round((c.m*x + c.b) * math.Pow(10, float64(c.r)))
	if y < 0 {
		y = 0
	}
	if y > 0x0FFE {
		y = 0x0FFE
	}
	return uint16(y)
}

func lm25066TempC(word uint16) float64 { return float64(int16(word)) / 16.0 }

// LM25066Block is the converted BLOCK_READ / AVG_BLOCK_READ / BLACK_BOX_READ telemetry.
type LM25066Block struct {
	Diagnostic  uint16  // diagnostic word at the time of the block
	Current     float64 // input current in A
	VoltageOut  float64 // output voltage in V
	VoltageIn   float64 // input voltage in V
	Power       float64 // input power in W
	Temperature float64 // MOSFET temperature in °C
}

// LM25066Limits holds all warning / fault thresholds; a nil pointer means disabled.
type LM25066Limits struct {
	VinOVWarn  *float64 // V
	VinUVWarn  *float64 // V
	VoutUVWarn *float64 // V
	OTWarn     *float64 // °C
	OTFault    *float64 // °C
	IinWarn    *float64 // A
	PinWarn    *float64 // W
}

// LM25066Minimal is the LM25066 hot-swap controller / PMBus power monitor driver — minimal interface.
//
// It reads the monitored rail: input voltage, output voltage, input current,
// input power and MOSFET temperature. The constructor performs no bus access
// and never writes the chip. Telemetry is PMBus DIRECT; the current and power
// coefficients depend on the sense resistor and the CL pin setting.
type LM25066Minimal struct {
	connection connection.RegisterConnection
	rs         float64
	amp, watt  lm25066Coef
}

// NewLM25066Minimal creates a new LM25066Minimal. connection must be an I²C or
// SMBus connection bound to the chip's address (0x10–0x17, 0x40–0x47, 0x50–0x5A).
// rSenseMohm is the sense resistor in milliohms; currentLimitHigh is false for
// the CL pin at GND (25 mV) and true for VDD (46 mV).
func NewLM25066Minimal(connection connection.RegisterConnection, rSenseMohm float64, currentLimitHigh bool) *LM25066Minimal {
	d := &LM25066Minimal{connection: connection, rs: rSenseMohm}
	d.setCL(currentLimitHigh)
	return d
}

func (d *LM25066Minimal) setCL(high bool) {
	if high {
		d.amp = lm25066Coef{6854 * d.rs, -3100, -2}
		d.watt = lm25066Coef{369 * d.rs, -1900, -2}
	} else {
		d.amp = lm25066Coef{13661 * d.rs, -5200, -2}
		d.watt = lm25066Coef{736 * d.rs, -3300, -2}
	}
}

func (d *LM25066Minimal) readWord(cmd uint8) (uint16, error) {
	buf, err := d.connection.ReadReg(uint32(cmd), 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0]) | uint16(buf[1])<<8, nil
}

func (d *LM25066Minimal) writeWord(cmd uint8, value uint16) error {
	return d.connection.WriteReg(uint32(cmd), []byte{byte(value), byte(value >> 8)})
}

func (d *LM25066Minimal) readByte(cmd uint8) (uint8, error) {
	buf, err := d.connection.ReadReg(uint32(cmd), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *LM25066Minimal) readY(cmd uint8, c lm25066Coef) (float64, error) {
	w, err := d.readWord(cmd)
	if err != nil {
		return 0, err
	}
	return c.fromY(w & 0x0FFF), nil
}

// VoltageIn reads the input voltage in volts (READ_VIN).
func (d *LM25066Minimal) VoltageIn() (float64, error) { return d.readY(lm25066CmdReadVin, lm25066Volt) }

// VoltageOut reads the output voltage in volts (READ_VOUT).
func (d *LM25066Minimal) VoltageOut() (float64, error) {
	return d.readY(lm25066CmdReadVout, lm25066Volt)
}

// Current reads the instantaneous input current in amperes (MFR_READ_IIN).
func (d *LM25066Minimal) Current() (float64, error) { return d.readY(lm25066CmdReadIin, d.amp) }

// Power reads the instantaneous input power in watts (MFR_READ_PIN).
func (d *LM25066Minimal) Power() (float64, error) { return d.readY(lm25066CmdReadPin, d.watt) }

// Temperature reads the external MOSFET temperature in °C (READ_TEMPERATURE_1).
func (d *LM25066Minimal) Temperature() (float64, error) {
	w, err := d.readWord(lm25066CmdReadTemp)
	if err != nil {
		return 0, err
	}
	return lm25066TempC(w), nil
}

// LM25066Full extends LM25066Minimal with averaging, limits, status, control and alerts.
//
// WARNING: OperationOff cuts the load rail and SetGateMask can stop hardware
// faults from switching the MOSFET off (which may destroy it); neither is
// ever called by the constructor.
type LM25066Full struct {
	LM25066Minimal

	mu          sync.Mutex
	callback    func(diagnostic uint16)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewLM25066Full creates a new LM25066Full. Arguments as for NewLM25066Minimal.
func NewLM25066Full(connection connection.RegisterConnection, rSenseMohm float64, currentLimitHigh bool) *LM25066Full {
	return &LM25066Full{LM25066Minimal: *NewLM25066Minimal(connection, rSenseMohm, currentLimitHigh)}
}

func (d *LM25066Full) block(cmd uint8) (LM25066Block, error) {
	raw, err := d.connection.ReadReg(uint32(cmd), 13)
	if err != nil {
		return LM25066Block{}, err
	}
	if raw[0] != 12 {
		return LM25066Block{}, ErrLM25066BlockLength
	}
	w := func(i int) uint16 { return uint16(raw[1+2*i]) | uint16(raw[2+2*i])<<8 }
	return LM25066Block{
		Diagnostic:  w(0),
		Current:     d.amp.fromY(w(1) & 0x0FFF),
		VoltageOut:  lm25066Volt.fromY(w(2) & 0x0FFF),
		VoltageIn:   lm25066Volt.fromY(w(3) & 0x0FFF),
		Power:       d.watt.fromY(w(4) & 0x0FFF),
		Temperature: lm25066TempC(w(5)),
	}, nil
}

func (d *LM25066Full) text(cmd uint8, n int) (string, error) {
	raw, err := d.connection.ReadReg(uint32(cmd), n+1)
	if err != nil {
		return "", err
	}
	if int(raw[0]) != n {
		return "", ErrLM25066BlockLength
	}
	return strings.ReplaceAll(string(raw[1:]), "\x00", ""), nil
}

// VoltageAux reads the VAUX pin voltage in volts (READ_VAUX, 0 … 1.16 V).
func (d *LM25066Full) VoltageAux() (float64, error) { return d.readY(lm25066CmdReadVaux, lm25066Vaux) }

// ReadBlock reads the time-aligned BLOCK_READ telemetry.
func (d *LM25066Full) ReadBlock() (LM25066Block, error) { return d.block(lm25066CmdBlockRead) }

// ReadAverage reads the averaged AVG_BLOCK_READ telemetry.
func (d *LM25066Full) ReadAverage() (LM25066Block, error) { return d.block(lm25066CmdAvgBlockRead) }

// BlackBox reads the snapshot latched at the first SMBA since ClearFaults.
func (d *LM25066Full) BlackBox() (LM25066Block, error) { return d.block(lm25066CmdBlackBox) }

// VoltageInAvg reads the averaged VIN in volts.
func (d *LM25066Full) VoltageInAvg() (float64, error) {
	return d.readY(lm25066CmdReadAvgVin, lm25066Volt)
}

// VoltageOutAvg reads the averaged VOUT in volts.
func (d *LM25066Full) VoltageOutAvg() (float64, error) {
	return d.readY(lm25066CmdReadAvgVout, lm25066Volt)
}

// CurrentAvg reads the averaged input current in amperes.
func (d *LM25066Full) CurrentAvg() (float64, error) { return d.readY(lm25066CmdReadAvgIin, d.amp) }

// PowerAvg reads the averaged input power in watts.
func (d *LM25066Full) PowerAvg() (float64, error) { return d.readY(lm25066CmdReadAvgPin, d.watt) }

// PowerPeak reads the peak input power in watts since reset or ClearPowerPeak.
func (d *LM25066Full) PowerPeak() (float64, error) { return d.readY(lm25066CmdReadPinPeak, d.watt) }

// ClearPowerPeak resets the peak-power register (CLEAR_PIN_PEAK, send byte).
func (d *LM25066Full) ClearPowerPeak() error {
	return d.connection.WriteReg(uint32(lm25066CmdClearPinPeak), nil)
}

// SetAveraging averages over 2^exponent samples; exponent must be 0–12,
// otherwise ErrLM25066InvalidArgument is returned.
func (d *LM25066Full) SetAveraging(exponent uint8) error {
	if exponent > 12 {
		return fmt.Errorf("%w: averaging exponent %d", ErrLM25066InvalidArgument, exponent)
	}
	return d.connection.WriteReg(uint32(lm25066CmdSamplesForAvg), []byte{exponent})
}

// GetAveraging reads SAMPLES_FOR_AVG (the exponent).
func (d *LM25066Full) GetAveraging() (uint8, error) { return d.readByte(lm25066CmdSamplesForAvg) }

func (d *LM25066Full) setLimit(cmd uint8, value *float64, c lm25066Coef, disabled uint16) error {
	y := disabled
	if value != nil {
		y = c.toY(*value)
	}
	return d.writeWord(cmd, y)
}

func (d *LM25066Full) getLimit(cmd uint8, c lm25066Coef, disabled uint16) (*float64, error) {
	w, err := d.readWord(cmd)
	if err != nil {
		return nil, err
	}
	y := w & 0x0FFF
	if y == disabled {
		return nil, nil
	}
	v := c.fromY(y)
	return &v, nil
}

// SetVinOVWarn sets the VIN over-voltage warning in volts; nil disables.
func (d *LM25066Full) SetVinOVWarn(volts *float64) error {
	return d.setLimit(lm25066CmdVinOVWarn, volts, lm25066Volt, 0x0FFF)
}

// SetVinUVWarn sets the VIN under-voltage warning in volts; nil disables.
func (d *LM25066Full) SetVinUVWarn(volts *float64) error {
	return d.setLimit(lm25066CmdVinUVWarn, volts, lm25066Volt, 0x0000)
}

// SetVoutUVWarn sets the VOUT under-voltage warning in volts; nil disables.
func (d *LM25066Full) SetVoutUVWarn(volts *float64) error {
	return d.setLimit(lm25066CmdVoutUVWarn, volts, lm25066Volt, 0x0000)
}

// SetOTWarn sets the over-temperature warning in °C; nil disables.
func (d *LM25066Full) SetOTWarn(celsius *float64) error {
	return d.setLimit(lm25066CmdOTWarn, celsius, lm25066Temp, 0x0FFF)
}

// SetOTFault sets the over-temperature fault in °C; nil disables.
func (d *LM25066Full) SetOTFault(celsius *float64) error {
	return d.setLimit(lm25066CmdOTFault, celsius, lm25066Temp, 0x0FFF)
}

// SetIinWarn sets the input over-current warning in amperes; nil disables.
func (d *LM25066Full) SetIinWarn(amps *float64) error {
	return d.setLimit(lm25066CmdIinWarn, amps, d.amp, 0x0FFF)
}

// SetPinWarn sets the input over-power warning in watts; nil disables.
func (d *LM25066Full) SetPinWarn(watts *float64) error {
	return d.setLimit(lm25066CmdPinWarn, watts, d.watt, 0x0FFF)
}

// GetLimits reads all warning / fault thresholds (nil where disabled).
func (d *LM25066Full) GetLimits() (LM25066Limits, error) {
	var l LM25066Limits
	var err error
	if l.VinOVWarn, err = d.getLimit(lm25066CmdVinOVWarn, lm25066Volt, 0x0FFF); err != nil {
		return l, err
	}
	if l.VinUVWarn, err = d.getLimit(lm25066CmdVinUVWarn, lm25066Volt, 0x0000); err != nil {
		return l, err
	}
	if l.VoutUVWarn, err = d.getLimit(lm25066CmdVoutUVWarn, lm25066Volt, 0x0000); err != nil {
		return l, err
	}
	if l.OTWarn, err = d.getLimit(lm25066CmdOTWarn, lm25066Temp, 0x0FFF); err != nil {
		return l, err
	}
	if l.OTFault, err = d.getLimit(lm25066CmdOTFault, lm25066Temp, 0x0FFF); err != nil {
		return l, err
	}
	if l.IinWarn, err = d.getLimit(lm25066CmdIinWarn, d.amp, 0x0FFF); err != nil {
		return l, err
	}
	l.PinWarn, err = d.getLimit(lm25066CmdPinWarn, d.watt, 0x0FFF)
	return l, err
}

// StatusByte reads STATUS_BYTE.
func (d *LM25066Full) StatusByte() (uint8, error) { return d.readByte(lm25066CmdStatusByte) }

// StatusWord reads STATUS_WORD.
func (d *LM25066Full) StatusWord() (uint16, error) { return d.readWord(lm25066CmdStatusWord) }

// StatusVout reads STATUS_VOUT.
func (d *LM25066Full) StatusVout() (uint8, error) { return d.readByte(lm25066CmdStatusVout) }

// StatusInput reads STATUS_INPUT.
func (d *LM25066Full) StatusInput() (uint8, error) { return d.readByte(lm25066CmdStatusInput) }

// StatusTemperature reads STATUS_TEMPERATURE.
func (d *LM25066Full) StatusTemperature() (uint8, error) { return d.readByte(lm25066CmdStatusTemp) }

// StatusCML reads STATUS_CML.
func (d *LM25066Full) StatusCML() (uint8, error) { return d.readByte(lm25066CmdStatusCML) }

// StatusMfr reads STATUS_MFR_SPECIFIC.
func (d *LM25066Full) StatusMfr() (uint8, error) { return d.readByte(lm25066CmdStatusMfr) }

// DiagnosticWord reads READ_DIAGNOSTIC_WORD (all faults and warnings).
func (d *LM25066Full) DiagnosticWord() (uint16, error) { return d.readWord(lm25066CmdDiagnostic) }

// ClearFaults sends CLEAR_FAULTS (send byte); it does not switch the MOSFET on.
func (d *LM25066Full) ClearFaults() error {
	return d.connection.WriteReg(uint32(lm25066CmdClearFaults), nil)
}

// OperationOn writes OPERATION = 0x80: switches the load on.
func (d *LM25066Full) OperationOn() error {
	return d.connection.WriteReg(uint32(lm25066CmdOperation), []byte{0x80})
}

// OperationOff writes OPERATION = 0x00: CUTS THE LOAD RAIL.
func (d *LM25066Full) OperationOff() error {
	return d.connection.WriteReg(uint32(lm25066CmdOperation), []byte{0x00})
}

// IsOn reports whether OPERATION reads 0x80 and the STATUS_BYTE OFF bit is clear.
func (d *LM25066Full) IsOn() (bool, error) {
	op, err := d.readByte(lm25066CmdOperation)
	if err != nil {
		return false, err
	}
	st, err := d.readByte(lm25066CmdStatusByte)
	if err != nil {
		return false, err
	}
	return op == 0x80 && st&0x40 == 0, nil
}

// DeviceSetup reads DEVICE_SETUP.
func (d *LM25066Full) DeviceSetup() (uint8, error) { return d.readByte(lm25066CmdDeviceSetup) }

// ConfigureDevice overrides the RETRY / CL / CB pin settings from software;
// only the non-nil arguments are overridden. retryCode is 0–7 (bits 7:5: 0 pin,
// 1 none, 2 = 1 retry, 3 = 2, 4 = 4, 5 = 8, 6 = 16, 7 = unlimited); a larger
// value returns ErrLM25066InvalidArgument. The driver coefficients are updated
// when currentLimitHigh is given.
func (d *LM25066Full) ConfigureDevice(retryCode *uint8, currentLimitHigh, cbRatioHigh *bool) error {
	var value uint8
	if retryCode != nil {
		if *retryCode > 7 {
			return fmt.Errorf("%w: retry code %d", ErrLM25066InvalidArgument, *retryCode)
		}
		value |= *retryCode << 5
	}
	if currentLimitHigh != nil {
		value |= 0x04
		if *currentLimitHigh {
			value |= 0x10
		}
	}
	if cbRatioHigh != nil {
		value |= 0x02
		if *cbRatioHigh {
			value |= 0x08
		}
	}
	if err := d.connection.WriteReg(uint32(lm25066CmdDeviceSetup), []byte{value}); err != nil {
		return err
	}
	if currentLimitHigh != nil {
		d.setCL(*currentLimitHigh)
	}
	return nil
}

// SetGateMask writes GATE_MASK. DANGEROUS: disabling fault shut-off can destroy
// the MOSFET. Only bits 5, 4 and 2 may be set, otherwise ErrLM25066InvalidArgument.
func (d *LM25066Full) SetGateMask(mask uint8) error {
	if mask&^0x34 != 0 {
		return fmt.Errorf("%w: gate mask 0x%02X", ErrLM25066InvalidArgument, mask)
	}
	return d.connection.WriteReg(uint32(lm25066CmdGateMask), []byte{mask})
}

// GetGateMask reads GATE_MASK.
func (d *LM25066Full) GetGateMask() (uint8, error) { return d.readByte(lm25066CmdGateMask) }

// Capability reads CAPABILITY.
func (d *LM25066Full) Capability() (uint8, error) { return d.readByte(lm25066CmdCapability) }

// ManufacturerID reads MFR_ID as a string.
func (d *LM25066Full) ManufacturerID() (string, error) { return d.text(lm25066CmdMfrID, 3) }

// Model reads MFR_MODEL as a string.
func (d *LM25066Full) Model() (string, error) { return d.text(lm25066CmdMfrModel, 8) }

// Revision reads MFR_REVISION as a string.
func (d *LM25066Full) Revision() (string, error) { return d.text(lm25066CmdMfrRevision, 2) }

// EnableInterrupt unmasks the given LM25066Source* bit(s) in ALERT_MASK (read-modify-write).
func (d *LM25066Full) EnableInterrupt(source uint16) error {
	m, err := d.readWord(lm25066CmdAlertMask)
	if err != nil {
		return err
	}
	return d.writeWord(lm25066CmdAlertMask, m&^source)
}

// DisableInterrupt masks the given LM25066Source* bit(s) in ALERT_MASK (read-modify-write).
func (d *LM25066Full) DisableInterrupt(source uint16) error {
	m, err := d.readWord(lm25066CmdAlertMask)
	if err != nil {
		return err
	}
	return d.writeWord(lm25066CmdAlertMask, m|source)
}

// OnInterrupt subscribes callback to SMBA (falling edge): it receives the
// READ_DIAGNOSTIC_WORD value. Uses the connection's InputPin if wired,
// otherwise a polling goroutine (1 s tick).
func (d *LM25066Full) OnInterrupt(callback func(diagnostic uint16)) error {
	d.mu.Lock()
	if d.unsubscribe != nil {
		d.unsubscribe()
		d.unsubscribe = nil
	}
	if d.pollPin != nil {
		_ = d.pollPin.Close()
		d.pollPin = nil
	}
	d.callback = callback
	pin := d.connection.IntPin()
	if pin == nil {
		poll := connection.NewPollingInputPin(time.Second)
		d.pollPin = poll
		pin = poll
	}
	d.mu.Unlock()

	unsub := pin.OnEdge(connection.Falling, d.handleEdge)
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes interrupt delivery.
func (d *LM25066Full) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	d.callback = nil
	poll := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (d *LM25066Full) handleEdge() {
	status, err := d.PollInterrupt()
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if err == nil && status != 0 && cb != nil {
		cb(status)
	}
}

// PollInterrupt reads READ_DIAGNOSTIC_WORD (it does not clear; call ClearFaults
// after servicing).
func (d *LM25066Full) PollInterrupt() (uint16, error) { return d.readWord(lm25066CmdDiagnostic) }
