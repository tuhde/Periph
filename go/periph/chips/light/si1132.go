package light

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Si1132 register addresses, commands and parameter offsets.
const (
	si1132RegPartID          = 0x00
	si1132RegSeqID           = 0x02
	si1132RegIntCfg          = 0x03
	si1132RegIrqEnable       = 0x04
	si1132RegHwKey           = 0x07
	si1132RegMeasRate0       = 0x08
	si1132RegMeasRate1       = 0x09
	si1132RegMeasRateSwapped = 0x0A
	si1132RegUcoef           = 0x13
	si1132RegParamWr         = 0x17
	si1132RegCommand         = 0x18
	si1132RegResponse        = 0x20
	si1132RegIrqStatus       = 0x21
	si1132RegData            = 0x22

	si1132PartID  = 0x32
	si1132HwKey   = 0x17
	si1132Timeout = 500 // ms

	si1132CmdParamSet = 0xA0
	si1132CmdNop      = 0x00
	si1132CmdReset    = 0x01
	si1132CmdBusAddr  = 0x02
	si1132CmdAlsForce = 0x06
	si1132CmdAlsPause = 0x0A
	si1132CmdAlsAuto  = 0x0E
	si1132CmdGetCal   = 0x12

	si1132ParamI2CAddr     = 0x00
	si1132ParamChlist      = 0x01
	si1132ParamAlsEncoding = 0x06
	si1132ParamAuxAdcmux   = 0x0F
	si1132ParamVisCounter  = 0x10
	si1132ParamVisGain     = 0x11
	si1132ParamVisMisc     = 0x12
	si1132ParamIrCounter   = 0x1D
	si1132ParamIrGain      = 0x1E
	si1132ParamIrMisc      = 0x1F

	si1132ChlistAll = 0xB0 // EN_UV | EN_ALS_IR | EN_ALS_VIS
	si1132ChlistAux = 0x40
	si1132IrqAlsInt = 0x01
	si1132IrqClear  = 0x21 // ALS_INT | CMD_INT (write 1 to clear)
	si1132RespError = 0x80
)

// Si1132I2CAddress is the fixed 7-bit I²C address.
const Si1132I2CAddress = 0x60

// Si1132StatusALSInt is the IRQ_STATUS flag set when an ALS/UV measurement completes.
const Si1132StatusALSInt uint8 = 0x01

var si1132Ucoef = []byte{0x7B, 0x6B, 0x01, 0x00}

// ErrSi1132NotFound is returned when PART_ID is not 0x32.
var ErrSi1132NotFound = errors.New("si1132: PART_ID mismatch")

// ErrSi1132Timeout is returned when a measurement or command does not complete in time.
var ErrSi1132Timeout = errors.New("si1132: operation timed out")

// ErrSi1132InvalidArgument is returned for an unsupported gain, channel selection, interval or coefficient count.
var ErrSi1132InvalidArgument = errors.New("si1132: invalid argument")

// Si1132Minimal is the Si1132 UV index and ambient light sensor driver
// (Silicon Labs) — minimal interface.
//
// Init waits 25 ms, verifies PART_ID = 0x32, writes HW_KEY, the default UV
// coefficients and enables the UV, infrared and visible channels in forced
// mode (gain 0, normal range). Read starts a forced measurement, waits for
// ALS_INT (timeout 500 ms) and returns the UV index (AUX_DATA / 100) plus the
// raw 16-bit visible and infrared counts. The I²C address is 0x60. The
// constructor does no bus access.
type Si1132Minimal struct {
	conn   connection.RegisterConnection
	chlist byte
	seqID  byte
}

// NewSi1132Minimal creates the driver. conn must be a configured register
// connection bound to 0x60.
func NewSi1132Minimal(conn connection.RegisterConnection) *Si1132Minimal {
	return &Si1132Minimal{conn: conn, chlist: si1132ChlistAll}
}

// Init verifies PART_ID and configures forced UV + visible + infrared measurements.
func (d *Si1132Minimal) Init() error {
	time.Sleep(25 * time.Millisecond)
	id, err := d.readReg(si1132RegPartID)
	if err != nil {
		return err
	}
	if id != si1132PartID {
		return ErrSi1132NotFound
	}
	if d.seqID, err = d.readReg(si1132RegSeqID); err != nil {
		return err
	}
	if err := d.writeReg(si1132RegHwKey, si1132HwKey); err != nil {
		return err
	}
	if err := d.conn.WriteReg(si1132RegUcoef, si1132Ucoef); err != nil {
		return err
	}
	// ALS_IE lets IRQ_STATUS.ALS_INT flag completed measurements; INT stays released (INT_OE = 0).
	if err := d.writeReg(si1132RegIrqEnable, si1132IrqAlsInt); err != nil {
		return err
	}
	d.chlist = si1132ChlistAll
	return d.paramSet(si1132ParamChlist, d.chlist)
}

func (d *Si1132Minimal) writeReg(reg, value byte) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

func (d *Si1132Minimal) readReg(reg byte) (byte, error) {
	b, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *Si1132Minimal) waitResponse(before byte) error {
	for i := 0; i < 20; i++ {
		resp, err := d.readReg(si1132RegResponse)
		if err != nil {
			return err
		}
		if resp&si1132RespError != 0 {
			_ = d.writeReg(si1132RegCommand, si1132CmdNop)
			return fmt.Errorf("si1132: command error 0x%02X", resp)
		}
		if resp&0x0F != before {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrSi1132Timeout
}

func (d *Si1132Minimal) command(cmd byte) error {
	before, err := d.readReg(si1132RegResponse)
	if err != nil {
		return err
	}
	if err := d.writeReg(si1132RegCommand, cmd); err != nil {
		return err
	}
	return d.waitResponse(before & 0x0F)
}

func (d *Si1132Minimal) paramSet(offset, value byte) error {
	before, err := d.readReg(si1132RegResponse)
	if err != nil {
		return err
	}
	if err := d.writeReg(si1132RegParamWr, value); err != nil {
		return err
	}
	if err := d.writeReg(si1132RegCommand, si1132CmdParamSet|(offset&0x1F)); err != nil {
		return err
	}
	return d.waitResponse(before & 0x0F)
}

func (d *Si1132Minimal) startMeasurement() error {
	if err := d.writeReg(si1132RegIrqStatus, si1132IrqClear); err != nil {
		return err
	}
	return d.command(si1132CmdAlsForce)
}

func (d *Si1132Minimal) waitReady() error {
	for i := 0; i < si1132Timeout; i++ {
		st, err := d.readReg(si1132RegIrqStatus)
		if err != nil {
			return err
		}
		if st&si1132IrqAlsInt != 0 {
			return d.writeReg(si1132RegIrqStatus, si1132IrqClear)
		}
		time.Sleep(time.Millisecond)
	}
	return ErrSi1132Timeout
}

// readData reads the data block 0x22–0x2D and returns visible, infrared and aux.
func (d *Si1132Minimal) readData() (visible, infrared, aux uint16, err error) {
	b, err := d.conn.ReadReg(si1132RegData, 12)
	if err != nil {
		return 0, 0, 0, err
	}
	return uint16(b[0]) | uint16(b[1])<<8, uint16(b[2]) | uint16(b[3])<<8, uint16(b[10]) | uint16(b[11])<<8, nil
}

func (d *Si1132Minimal) measure() (visible, infrared, aux uint16, err error) {
	if err = d.startMeasurement(); err != nil {
		return
	}
	if err = d.waitReady(); err != nil {
		return
	}
	return d.readData()
}

// Read runs one forced measurement of the enabled channels and returns the UV
// index (AUX_DATA / 100) and the raw 16-bit visible and infrared counts.
func (d *Si1132Minimal) Read() (uvIndex float32, visible, infrared uint16, err error) {
	vis, ir, aux, err := d.measure()
	if err != nil {
		return 0, 0, 0, err
	}
	return float32(aux) / 100, vis, ir, nil
}

// Si1132Full is the Si1132 full interface — single channels, gain/range,
// autonomous mode, calibration, auxiliary channels and the INT pin. It extends
// Si1132Minimal.
type Si1132Full struct {
	*Si1132Minimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewSi1132Full creates the driver. conn must be a configured register
// connection bound to 0x60.
func NewSi1132Full(conn connection.RegisterConnection) *Si1132Full {
	return &Si1132Full{Si1132Minimal: NewSi1132Minimal(conn)}
}

// ReadUVIndex runs a forced measurement and returns the UV index (AUX_DATA / 100).
func (d *Si1132Full) ReadUVIndex() (float32, error) {
	_, _, aux, err := d.measure()
	return float32(aux) / 100, err
}

// ReadVisible runs a forced measurement and returns the raw 16-bit visible count.
func (d *Si1132Full) ReadVisible() (uint16, error) {
	v, _, _, err := d.measure()
	return v, err
}

// ReadInfrared runs a forced measurement and returns the raw 16-bit infrared count.
func (d *Si1132Full) ReadInfrared() (uint16, error) {
	_, ir, _, err := d.measure()
	return ir, err
}

// ReadRaw reads the latest data registers without starting a measurement.
func (d *Si1132Full) ReadRaw() (visible, infrared, aux uint16, err error) {
	return d.readData()
}

func (d *Si1132Full) readAux(mux byte) (uint16, error) {
	if err := d.paramSet(si1132ParamAuxAdcmux, mux); err != nil {
		return 0, err
	}
	if err := d.paramSet(si1132ParamChlist, si1132ChlistAux); err != nil {
		return 0, err
	}
	_, _, aux, err := d.measure()
	if rerr := d.paramSet(si1132ParamChlist, d.chlist); err == nil {
		err = rerr
	}
	return aux, err
}

// ReadTemperatureRaw measures the auxiliary channel as die temperature
// (AUX_ADCMUX = 0x65, relative only).
func (d *Si1132Full) ReadTemperatureRaw() (uint16, error) { return d.readAux(0x65) }

// ReadVddRaw measures the auxiliary channel as supply voltage (AUX_ADCMUX = 0x75).
func (d *Si1132Full) ReadVddRaw() (uint16, error) { return d.readAux(0x75) }

// SetChannels selects the measured channels (CHLIST). At least one channel is
// required and uv and aux cannot be combined.
func (d *Si1132Full) SetChannels(uv, visible, infrared, aux bool) error {
	if !(uv || visible || infrared || aux) || (uv && aux) {
		return ErrSi1132InvalidArgument
	}
	var chlist byte
	if uv {
		chlist |= 0x80
	}
	if aux {
		chlist |= 0x40
	}
	if infrared {
		chlist |= 0x20
	}
	if visible {
		chlist |= 0x10
	}
	if err := d.paramSet(si1132ParamChlist, chlist); err != nil {
		return err
	}
	d.chlist = chlist
	return nil
}

// SetGain sets the ADC gain (integration time x 2^n, 0–7 per channel) and the
// recommended recovery count.
func (d *Si1132Full) SetGain(visible, infrared uint8) error {
	if visible > 7 || infrared > 7 {
		return ErrSi1132InvalidArgument
	}
	for _, p := range [][2]byte{
		{si1132ParamVisGain, visible},
		{si1132ParamVisCounter, (^visible & 0x07) << 4},
		{si1132ParamIrGain, infrared},
		{si1132ParamIrCounter, (^infrared & 0x07) << 4},
	} {
		if err := d.paramSet(p[0], p[1]); err != nil {
			return err
		}
	}
	return nil
}

func si1132Flag(on bool, v byte) byte {
	if on {
		return v
	}
	return 0
}

// SetHighRange selects the high signal range (sensitivity ÷ 14.5, for direct sunlight).
func (d *Si1132Full) SetHighRange(visible, infrared bool) error {
	if err := d.paramSet(si1132ParamVisMisc, si1132Flag(visible, 0x20)); err != nil {
		return err
	}
	return d.paramSet(si1132ParamIrMisc, si1132Flag(infrared, 0x20))
}

// SetAlignment selects which 16 bits of the 17-bit ADC value are reported
// (true = least significant 16 bits, false = the 16 MSBs).
func (d *Si1132Full) SetAlignment(visibleLow, infraredLow bool) error {
	return d.paramSet(si1132ParamAlsEncoding, si1132Flag(visibleLow, 0x10)|si1132Flag(infraredLow, 0x20))
}

// SetUVCoefficients writes the four UV index coefficients UCOEF0..3.
func (d *Si1132Full) SetUVCoefficients(ucoef [4]byte) error {
	return d.conn.WriteReg(si1132RegUcoef, ucoef[:])
}

// ReadCalibration issues GET_CAL and returns the 12 factory calibration bytes
// from registers 0x22–0x2D.
func (d *Si1132Full) ReadCalibration() ([12]byte, error) {
	var out [12]byte
	if err := d.command(si1132CmdGetCal); err != nil {
		return out, err
	}
	b, err := d.conn.ReadReg(si1132RegData, 12)
	if err != nil {
		return out, err
	}
	copy(out[:], b)
	return out, nil
}

// StartAutonomous starts autonomous measurements with the given wake-up interval in
// seconds (multiples of 31.25 µs, at most about 2.05 s); 0 returns to forced mode.
func (d *Si1132Full) StartAutonomous(intervalS float32) error {
	if intervalS < 0 {
		return ErrSi1132InvalidArgument
	}
	counts := int(math.Round(float64(intervalS) / 31.25e-6))
	if counts > 0xFFFF {
		return ErrSi1132InvalidArgument
	}
	lo, hi := byte(counts&0xFF), byte(counts>>8)
	var err error
	if d.seqID == 0x01 { // erratum: MEAS_RATE bytes swapped
		if err = d.writeReg(si1132RegMeasRateSwapped, lo); err == nil {
			err = d.writeReg(si1132RegMeasRate0, hi)
		}
	} else {
		if err = d.writeReg(si1132RegMeasRate0, lo); err == nil {
			err = d.writeReg(si1132RegMeasRate1, hi)
		}
	}
	if err != nil {
		return err
	}
	if counts == 0 {
		return d.command(si1132CmdAlsPause)
	}
	return d.command(si1132CmdAlsAuto)
}

// Pause pauses autonomous measurements (ALS_PAUSE).
func (d *Si1132Full) Pause() error { return d.command(si1132CmdAlsPause) }

// Force starts one forced measurement without waiting for it (ALS_FORCE).
func (d *Si1132Full) Force() error { return d.startMeasurement() }

// MeasurementReady reports whether IRQ_STATUS.ALS_INT is set.
func (d *Si1132Full) MeasurementReady() (bool, error) {
	st, err := d.readReg(si1132RegIrqStatus)
	return st&si1132IrqAlsInt != 0, err
}

// ReadResponse decodes RESPONSE into the 4-bit command counter and the error code (0 = none).
func (d *Si1132Full) ReadResponse() (counter, errCode uint8, err error) {
	resp, err := d.readReg(si1132RegResponse)
	if err != nil {
		return 0, 0, err
	}
	if resp&si1132RespError != 0 {
		return 0, resp, nil
	}
	return resp & 0x0F, 0, nil
}

// SetI2CAddress changes the I²C address (volatile; lost at reset): the I2C_ADDR
// parameter is written with the 7-bit address, then BUSADDR is issued.
func (d *Si1132Full) SetI2CAddress(address uint8) error {
	if err := d.paramSet(si1132ParamI2CAddr, address); err != nil {
		return err
	}
	return d.command(si1132CmdBusAddr)
}

// Reset issues RESET, waits 2 ms and writes HW_KEY again.
func (d *Si1132Full) Reset() error {
	if err := d.writeReg(si1132RegCommand, si1132CmdReset); err != nil {
		return err
	}
	time.Sleep(2 * time.Millisecond)
	return d.writeReg(si1132RegHwKey, si1132HwKey)
}

// EnableInterrupt drives INT low on completed measurements (INT_OE and ALS_IE).
func (d *Si1132Full) EnableInterrupt() error {
	if err := d.writeReg(si1132RegIrqEnable, si1132IrqAlsInt); err != nil {
		return err
	}
	return d.writeReg(si1132RegIntCfg, 0x01)
}

// DisableInterrupt releases INT (INT_OE = 0); ALS_IE stays set because Read polls ALS_INT.
func (d *Si1132Full) DisableInterrupt() error { return d.writeReg(si1132RegIntCfg, 0x00) }

// PollInterrupt reads IRQ_STATUS, writes it back to clear the flags and returns it (0 = none).
func (d *Si1132Full) PollInterrupt() (uint8, error) {
	st, err := d.readReg(si1132RegIrqStatus)
	if err != nil || st == 0 {
		return st, err
	}
	return st, d.writeReg(si1132RegIrqStatus, st)
}

// OnInterrupt subscribes callback to measurement-complete events.
//
// It enables the INT output and uses the connection's IntPin (falling edge);
// without a pin a polling goroutine calls PollInterrupt and delivers only when a
// flag is set. The callback receives the IRQ_STATUS value.
func (d *Si1132Full) OnInterrupt(callback func(uint8)) error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	if err := d.EnableInterrupt(); err != nil {
		return err
	}
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()

	pin := d.conn.IntPin()
	if pin == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		pin = poll
	}
	unsub := pin.OnEdge(connection.Falling, d.handleEdge)
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes from measurement events and releases the INT output.
func (d *Si1132Full) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	had := d.callback != nil
	d.callback = nil
	poll := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		_ = poll.Close()
	}
	if had {
		return d.DisableInterrupt()
	}
	return nil
}

func (d *Si1132Full) handleEdge() {
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb == nil {
		return
	}
	status, err := d.PollInterrupt()
	if err != nil || status == 0 {
		return
	}
	cb(status)
}
