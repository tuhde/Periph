package gas

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// CCS811 register addresses.
const (
	ccs811RegStatus        uint8 = 0x00
	ccs811RegMeasMode      uint8 = 0x01
	ccs811RegAlgResultData uint8 = 0x02
	ccs811RegRawData       uint8 = 0x03
	ccs811RegEnvData       uint8 = 0x05
	ccs811RegNTC           uint8 = 0x06
	ccs811RegThresholds    uint8 = 0x10
	ccs811RegBaseline      uint8 = 0x11
	ccs811RegHWID          uint8 = 0x20
	ccs811RegHWVersion     uint8 = 0x21
	ccs811RegFWBootVersion uint8 = 0x23
	ccs811RegFWAppVersion  uint8 = 0x24
	ccs811RegErrorID       uint8 = 0xE0
	ccs811RegAppStart      uint8 = 0xF4
	ccs811RegSWReset       uint8 = 0xFF
)

const (
	ccs811HWID       uint8 = 0x81
	ccs811Mode1      uint8 = 0x10
	ccs811IntDataRdy uint8 = 0x08
	ccs811IntThresh  uint8 = 0x04
)

var ccs811ResetKey = []byte{0x11, 0xE5, 0x72, 0x8A}

// Sample interval per drive mode (0 = idle).
var ccs811Interval = [5]time.Duration{0, time.Second, 10 * time.Second, 60 * time.Second, 250 * time.Millisecond}

// CCS811 STATUS bits.
const (
	// CCS811StatusFWMode is set in application mode.
	CCS811StatusFWMode uint8 = 0x80
	// CCS811StatusAppValid is set when valid application firmware is loaded.
	CCS811StatusAppValid uint8 = 0x10
	// CCS811StatusDataReady is set when a new sample waits in ALG_RESULT_DATA.
	CCS811StatusDataReady uint8 = 0x08
	// CCS811StatusError is set on an error; see ERROR_ID.
	CCS811StatusError uint8 = 0x01
)

// CCS811 interrupt sources.
const (
	// CCS811SourceDataReady asserts nINT on every new sample (MEAS_MODE.INT_DATARDY).
	CCS811SourceDataReady uint8 = 0x01
	// CCS811SourceThreshold asserts nINT on an eCO₂ zone change (INT_DATARDY + INT_THRESH).
	CCS811SourceThreshold uint8 = 0x02
)

// Errors returned by the CCS811 driver.
var (
	// ErrCCS811ChipID is returned by Init when HW_ID is not 0x81.
	ErrCCS811ChipID = errors.New("ccs811: unexpected HW_ID")
	// ErrCCS811NoApplication is returned by Init when STATUS.APP_VALID is 0.
	ErrCCS811NoApplication = errors.New("ccs811: no valid application firmware")
	// ErrCCS811AppStart is returned by Init when the chip does not enter application mode.
	ErrCCS811AppStart = errors.New("ccs811: did not enter application mode")
	// ErrCCS811Timeout is returned when no sample arrives in time.
	ErrCCS811Timeout = errors.New("ccs811: data ready timeout")
	// ErrCCS811Argument is returned for an argument out of range.
	ErrCCS811Argument = errors.New("ccs811: argument out of range")
	// ErrCCS811Sensor wraps a chip-reported error; the message carries ERROR_ID.
	ErrCCS811Sensor = errors.New("ccs811: sensor error")
)

// CCS811Measurement is one algorithm sample.
type CCS811Measurement struct {
	ECO2ppm uint16 // equivalent CO₂ in ppm (400–8192)
	TVOCppb uint16 // total VOC in ppb (0–1187)
}

// CCS811Version is a firmware version (major.minor.trivial).
type CCS811Version struct {
	Major, Minor, Trivial uint8
}

// CCS811Minimal is the CCS811 indoor air quality gas sensor driver (ScioSense / ams) —
// minimal interface: one call returns eCO₂ and TVOC.
//
// The constructor does no bus access. Init checks HW_ID (0x81), writes APP_START if the
// chip is still in boot mode and selects drive mode 1 (MEAS_MODE = 0x10: one sample per
// second, interrupts off). No environment data is written (the chip assumes 50 %RH,
// 25 °C). The chip stretches the I²C clock; tie nWAKE low. New sensors need a 48 h
// burn-in and values settle about 20 min after each power-up. I²C address 0x5A (ADDR
// low) or 0x5B (ADDR high).
type CCS811Minimal struct {
	conn     connection.RegisterConnection
	measMode uint8
}

// NewCCS811Minimal creates the driver. conn must be a register-addressed I²C connection
// bound to address 0x5A or 0x5B. It never returns an error (no bus access).
func NewCCS811Minimal(conn connection.RegisterConnection) (*CCS811Minimal, error) {
	return &CCS811Minimal{conn: conn, measMode: ccs811Mode1}, nil
}

func (d *CCS811Minimal) readByte(reg uint8) (uint8, error) {
	b, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *CCS811Minimal) read16(reg uint8) (uint16, error) {
	b, err := d.conn.ReadReg(uint32(reg), 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

func (d *CCS811Minimal) write(reg uint8, data ...byte) error {
	return d.conn.WriteReg(uint32(reg), data)
}

// Init verifies the chip, starts the application firmware and selects drive mode 1.
func (d *CCS811Minimal) Init() error {
	id, err := d.readByte(ccs811RegHWID)
	if err != nil {
		return err
	}
	if id != ccs811HWID {
		return ErrCCS811ChipID
	}
	if err := d.startApp(); err != nil {
		return err
	}
	d.measMode = ccs811Mode1
	return d.write(ccs811RegMeasMode, d.measMode)
}

func (d *CCS811Minimal) startApp() error {
	status, err := d.readByte(ccs811RegStatus)
	if err != nil {
		return err
	}
	if status&CCS811StatusAppValid == 0 {
		return ErrCCS811NoApplication
	}
	if status&CCS811StatusFWMode != 0 {
		return nil
	}
	if err := d.write(ccs811RegAppStart); err != nil {
		return err
	}
	time.Sleep(2 * time.Millisecond)
	if status, err = d.readByte(ccs811RegStatus); err != nil {
		return err
	}
	if status&CCS811StatusFWMode == 0 {
		return ErrCCS811AppStart
	}
	return nil
}

func (d *CCS811Minimal) waitDataReady() error {
	mode := int(d.measMode>>4) & 0x07
	limit := 1500 * time.Millisecond
	if mode < len(ccs811Interval) && ccs811Interval[mode]*3/2 > limit {
		limit = ccs811Interval[mode] * 3 / 2
	}
	deadline := time.Now().Add(limit)
	for {
		s, err := d.readByte(ccs811RegStatus)
		if err != nil {
			return err
		}
		if s&CCS811StatusDataReady != 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return ErrCCS811Timeout
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func (d *CCS811Minimal) readResult() (CCS811Measurement, error) {
	b, err := d.conn.ReadReg(uint32(ccs811RegAlgResultData), 5)
	if err != nil {
		return CCS811Measurement{}, err
	}
	if b[4]&CCS811StatusError != 0 {
		id, err := d.readByte(ccs811RegErrorID)
		if err != nil {
			return CCS811Measurement{}, err
		}
		return CCS811Measurement{}, fmt.Errorf("%w: ERROR_ID 0x%02X", ErrCCS811Sensor, id)
	}
	return CCS811Measurement{ECO2ppm: uint16(b[0])<<8 | uint16(b[1]), TVOCppb: uint16(b[2])<<8 | uint16(b[3])}, nil
}

// Read waits for the next sample (DATA_READY) and returns eCO₂ and TVOC. A chip-reported
// error wraps ErrCCS811Sensor with ERROR_ID.
func (d *CCS811Minimal) Read() (CCS811Measurement, error) {
	if err := d.waitDataReady(); err != nil {
		return CCS811Measurement{}, err
	}
	return d.readResult()
}

// CCS811Full is the full CCS811 driver: drive modes, raw sensor data, environment
// compensation, the NTC input, thresholds, baseline save/restore, versions, soft reset
// and the nINT interrupt (active low, open-drain, released when ALG_RESULT_DATA is read).
type CCS811Full struct {
	CCS811Minimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewCCS811Full creates the full driver; same argument as NewCCS811Minimal.
func NewCCS811Full(conn connection.RegisterConnection) (*CCS811Full, error) {
	return &CCS811Full{CCS811Minimal: CCS811Minimal{conn: conn, measMode: ccs811Mode1}}, nil
}

func (d *CCS811Full) writeMeasMode() error { return d.write(ccs811RegMeasMode, d.measMode) }

// ReadECO2 waits for the next sample and returns the equivalent CO₂ in ppm.
func (d *CCS811Full) ReadECO2() (uint16, error) {
	m, err := d.Read()
	return m.ECO2ppm, err
}

// ReadTVOC waits for the next sample and returns the total VOC in ppb.
func (d *CCS811Full) ReadTVOC() (uint16, error) {
	m, err := d.Read()
	return m.TVOCppb, err
}

// ReadRaw reads RAW_DATA: the sense current (0–63 µA) and the voltage across the sensor
// in volts (ADC × 1.65 / 1023).
func (d *CCS811Full) ReadRaw() (currentUA uint8, voltageV float64, err error) {
	raw, err := d.read16(ccs811RegRawData)
	if err != nil {
		return 0, 0, err
	}
	return uint8(raw >> 10), float64(raw&0x3FF) * 1.65 / 1023, nil
}

// DataReady reports STATUS.DATA_READY (a new sample is waiting).
func (d *CCS811Full) DataReady() (bool, error) {
	s, err := d.Status()
	return s&CCS811StatusDataReady != 0, err
}

// SetMode selects the drive mode, keeping the interrupt bits: 0 idle, 1 every second,
// 2 every 10 s, 3 every 60 s, 4 every 250 ms (raw data only). When moving to a slower
// mode, idle (mode 0) first; the first sample at the new rate may be stale.
func (d *CCS811Full) SetMode(mode int) error {
	if mode < 0 || mode > 4 {
		return ErrCCS811Argument
	}
	d.measMode = d.measMode&0x0C | uint8(mode)<<4
	return d.writeMeasMode()
}

// SetEnvironment writes ambient humidity (%, 0–100) and temperature (°C, -25 to 102)
// to ENV_DATA for the compensation.
func (d *CCS811Full) SetEnvironment(humidityRH, temperatureC float64) error {
	if !(humidityRH >= 0 && humidityRH <= 100) || !(temperatureC >= -25 && temperatureC <= 102) {
		return ErrCCS811Argument
	}
	h := uint16(humidityRH*512 + 0.5)
	t := uint16((temperatureC+25)*512 + 0.5)
	return d.write(ccs811RegEnvData, byte(h>>8), byte(h), byte(t>>8), byte(t))
}

// ReadNTC reads the NTC circuit voltages across R_REF and R_NTC in mV.
func (d *CCS811Full) ReadNTC() (vRefMV, vNTCMV uint16, err error) {
	b, err := d.conn.ReadReg(uint32(ccs811RegNTC), 4)
	if err != nil {
		return 0, 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), uint16(b[2])<<8 | uint16(b[3]), nil
}

// NTCResistance returns the NTC resistance in ohms, V_NTC × R_REF / V_REF. It returns
// ErrCCS811Argument when V_REF reads 0 (no NTC circuit).
func (d *CCS811Full) NTCResistance(rRefOhm float64) (float64, error) {
	vRef, vNTC, err := d.ReadNTC()
	if err != nil {
		return 0, err
	}
	if vRef == 0 {
		return 0, ErrCCS811Argument
	}
	return float64(vNTC) * rRefOhm / float64(vRef), nil
}

// SetThresholds sets the eCO₂ boundaries (ppm) and hysteresis for the threshold
// interrupt (spec defaults 1500 / 2500 / 50). lowMediumPpm must be below mediumHighPpm.
func (d *CCS811Full) SetThresholds(lowMediumPpm, mediumHighPpm uint16, hysteresisPpm uint8) error {
	if lowMediumPpm >= mediumHighPpm {
		return ErrCCS811Argument
	}
	return d.write(ccs811RegThresholds, byte(lowMediumPpm>>8), byte(lowMediumPpm),
		byte(mediumHighPpm>>8), byte(mediumHighPpm), hysteresisPpm)
}

// GetBaseline reads the encoded algorithm baseline (save it to non-volatile storage).
func (d *CCS811Full) GetBaseline() (uint16, error) { return d.read16(ccs811RegBaseline) }

// SetBaseline restores a saved baseline (only after about 20 min of operation).
func (d *CCS811Full) SetBaseline(baseline uint16) error {
	return d.write(ccs811RegBaseline, byte(baseline>>8), byte(baseline))
}

// Status reads STATUS (CCS811Status* bits).
func (d *CCS811Full) Status() (uint8, error) { return d.readByte(ccs811RegStatus) }

// Error reads ERROR_ID (bit 0 write-invalid … bit 5 heater supply).
func (d *CCS811Full) Error() (uint8, error) { return d.readByte(ccs811RegErrorID) }

// HardwareVersion reads HW_ID (0x81) and HW_VERSION (0x1X).
func (d *CCS811Full) HardwareVersion() (hwID, hwVersion uint8, err error) {
	if hwID, err = d.readByte(ccs811RegHWID); err != nil {
		return 0, 0, err
	}
	hwVersion, err = d.readByte(ccs811RegHWVersion)
	return hwID, hwVersion, err
}

func (d *CCS811Full) version(reg uint8) (CCS811Version, error) {
	v, err := d.read16(reg)
	return CCS811Version{Major: uint8(v >> 12), Minor: uint8(v>>8) & 0x0F, Trivial: uint8(v)}, err
}

// FirmwareVersion reads the bootloader and application firmware versions.
func (d *CCS811Full) FirmwareVersion() (boot, app CCS811Version, err error) {
	if boot, err = d.version(ccs811RegFWBootVersion); err != nil {
		return
	}
	app, err = d.version(ccs811RegFWAppVersion)
	return
}

// SoftReset writes the SW_RESET key (into boot mode), then starts the application and
// mode 1 again (Init). Drive mode, interrupt bits, thresholds and environment data
// return to their defaults.
func (d *CCS811Full) SoftReset() error {
	_ = d.write(ccs811RegSWReset, ccs811ResetKey...) // the chip may not acknowledge while it resets
	time.Sleep(20 * time.Millisecond)
	return d.Init()
}

func ccs811IntBits(source uint8) uint8 {
	var bits uint8
	if source&CCS811SourceDataReady != 0 {
		bits |= ccs811IntDataRdy
	}
	if source&CCS811SourceThreshold != 0 {
		bits |= ccs811IntDataRdy | ccs811IntThresh
	}
	return bits
}

// EnableInterrupt enables nINT for a source (CCS811SourceThreshold also sets
// INT_DATARDY), keeping the drive mode.
func (d *CCS811Full) EnableInterrupt(source uint8) error {
	d.measMode |= ccs811IntBits(source)
	return d.writeMeasMode()
}

// DisableInterrupt disables nINT for a source, keeping the drive mode.
func (d *CCS811Full) DisableInterrupt(source uint8) error {
	d.measMode &^= ccs811IntBits(source)
	return d.writeMeasMode()
}

// PollInterrupt reads STATUS; CCS811StatusDataReady marks a new sample. It does not
// release nINT (reading ALG_RESULT_DATA does).
func (d *CCS811Full) PollInterrupt() (uint8, error) { return d.Status() }

// OnInterrupt subscribes callback; it receives the STATUS byte when DATA_READY is set and
// should read the sample to release nINT. With the connection's IntPin wired it fires on
// the falling edge; otherwise a polling goroutine checks STATUS.
func (d *CCS811Full) OnInterrupt(callback func(status uint8)) error {
	_ = d.OffInterrupt()
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()
	ip := d.conn.IntPin()
	if ip == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		ip = poll
	}
	unsub := ip.OnEdge(connection.Falling, func() { d.handleEdge() })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *CCS811Full) OffInterrupt() error {
	d.mu.Lock()
	unsub, poll := d.unsubscribe, d.pollPin
	d.unsubscribe, d.pollPin, d.callback = nil, nil, nil
	d.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (d *CCS811Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil || status&CCS811StatusDataReady == 0 {
		return
	}
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}
