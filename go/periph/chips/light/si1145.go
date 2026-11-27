package light

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Si1145 register addresses, commands and parameter offsets.
const (
	si1145RegPartID    uint8 = 0x00
	si1145RegSeqID     uint8 = 0x02
	si1145RegIntCfg    uint8 = 0x03
	si1145RegIRQEnable uint8 = 0x04
	si1145RegHWKey     uint8 = 0x07
	si1145RegMeasRate0 uint8 = 0x08
	si1145RegPSRate    uint8 = 0x0A
	si1145RegPSLED21   uint8 = 0x0F
	si1145RegPSLED3    uint8 = 0x10
	si1145RegUCoef0    uint8 = 0x13
	si1145RegParamWr   uint8 = 0x17
	si1145RegCommand   uint8 = 0x18
	si1145RegResponse  uint8 = 0x20
	si1145RegIRQStatus uint8 = 0x21
	si1145RegData      uint8 = 0x22
	si1145RegParamRd   uint8 = 0x2E

	si1145HWKey uint8 = 0x17

	si1145CmdNop        uint8 = 0x00
	si1145CmdReset      uint8 = 0x01
	si1145CmdBusAddr    uint8 = 0x02
	si1145CmdPSForce    uint8 = 0x05
	si1145CmdALSForce   uint8 = 0x06
	si1145CmdPSALSForce uint8 = 0x07
	si1145CmdPSALSPause uint8 = 0x0B
	si1145CmdPSAuto     uint8 = 0x0D
	si1145CmdALSAuto    uint8 = 0x0E
	si1145CmdPSALSAuto  uint8 = 0x0F
	si1145CmdGetCal     uint8 = 0x12
	si1145CmdParamQuery uint8 = 0x80
	si1145CmdParamSet   uint8 = 0xA0

	si1145ParamI2CAddr          uint8 = 0x00
	si1145ParamChList           uint8 = 0x01
	si1145ParamPSLED12Select    uint8 = 0x02
	si1145ParamPSLED3Select     uint8 = 0x03
	si1145ParamPSEncoding       uint8 = 0x05
	si1145ParamALSEncoding      uint8 = 0x06
	si1145ParamPS1ADCMux        uint8 = 0x07
	si1145ParamPSADCCounter     uint8 = 0x0A
	si1145ParamPSADCGain        uint8 = 0x0B
	si1145ParamPSADCMisc        uint8 = 0x0C
	si1145ParamAuxADCMux        uint8 = 0x0F
	si1145ParamALSVisADCCounter uint8 = 0x10
	si1145ParamALSVisADCGain    uint8 = 0x11
	si1145ParamALSVisADCMisc    uint8 = 0x12
	si1145ParamALSIRADCCounter  uint8 = 0x1D
	si1145ParamALSIRADCGain     uint8 = 0x1E
	si1145ParamALSIRADCMisc     uint8 = 0x1F

	si1145SourceMask   uint8 = 0x3D
	si1145CmdPolls           = 50   // 1 ms each
	si1145MeasurePolls       = 1000 // 1 ms each; a gain-7 ALS measurement takes ~200 ms
)

// Si1145 channel list bits (CHLIST).
const (
	Si1145ChPS1    uint8 = 0x01 // proximity channel 1
	Si1145ChPS2    uint8 = 0x02 // proximity channel 2 (Si1146/47)
	Si1145ChPS3    uint8 = 0x04 // proximity channel 3 (Si1147)
	Si1145ChALSVis uint8 = 0x10 // visible ambient light
	Si1145ChALSIR  uint8 = 0x20 // infrared ambient light
	Si1145ChAux    uint8 = 0x40 // auxiliary channel
	Si1145ChUV     uint8 = 0x80 // UV index
)

// Si1145 interrupt sources (IRQ_ENABLE / IRQ_STATUS bits).
const (
	Si1145SourceALS uint8 = 0x01 // ALS/UV measurement complete
	Si1145SourcePS1 uint8 = 0x04 // PS1 complete
	Si1145SourcePS2 uint8 = 0x08 // PS2 complete (Si1146/47)
	Si1145SourcePS3 uint8 = 0x10 // PS3 complete (Si1147)
	Si1145SourceCmd uint8 = 0x20 // command complete
)

// Si1145 auxiliary channel selections (AUX_ADCMUX).
const (
	Si1145AuxTemperature uint8 = 0x65 // die temperature (relative)
	Si1145AuxVDD         uint8 = 0x75 // supply voltage
)

// Si1145UCoefDefault are the default UV coefficients: AUX_DATA = UV index × 100.
var Si1145UCoefDefault = [4]uint8{0x7B, 0x6B, 0x01, 0x00}

// Errors returned by the Si1145 driver.
var (
	// ErrSi1145NotFound is returned by Init when PART_ID is not 0x45, 0x46 or 0x47.
	ErrSi1145NotFound = errors.New("si1145: PART_ID mismatch")
	// ErrSi1145Timeout is returned when a command or measurement does not complete.
	ErrSi1145Timeout = errors.New("si1145: operation timed out")
	// ErrSi1145Command wraps an error code in RESPONSE; the message carries the code.
	ErrSi1145Command = errors.New("si1145: command error")
	// ErrSi1145ChannelDisabled is returned when the requested channel is not in CHLIST.
	ErrSi1145ChannelDisabled = errors.New("si1145: channel not enabled")
	// ErrSi1145InvalidArgument is returned for an argument out of range or not on this part.
	ErrSi1145InvalidArgument = errors.New("si1145: invalid argument")
)

// Si1145Reading holds one forced PS + ALS measurement.
type Si1145Reading struct {
	Proximity uint16  // raw PS1 count (rises as an object approaches)
	UVIndex   float64 // UV index (AUX_DATA / 100)
	Visible   uint16  // raw visible ambient count
	Infrared  uint16  // raw infrared ambient count
}

// Si1145Raw holds the data registers 0x22–0x2D.
type Si1145Raw struct {
	Visible, Infrared, PS1, PS2, PS3 uint16
	Aux                              uint16 // UV index × 100 while UV is enabled
}

// Si1145Response is the decoded RESPONSE register.
type Si1145Response struct {
	Counter uint8 // 4-bit command counter (0 on error)
	Error   uint8 // 0 = none, 0x80 invalid setting, 0x88–0x8E ADC overflow
}

// Si1145Minimal is the Si1145 proximity / UV index / ambient light sensor driver (Silicon
// Labs) — minimal interface: one call runs a forced PS + ALS measurement and returns the
// proximity count, the UV index and the visible/infrared counts.
//
// The constructor does no bus access. Init waits 25 ms, checks PART_ID (0x45/0x46/0x47),
// writes HW_KEY = 0x17, UCOEF0..3 = 7B 6B 01 00, CHLIST = 0xB1 (UV, ALS IR, ALS VIS, PS1),
// PSLED12_SELECT = 0x01, PS_LED21 = 0x03 (LED1 at level 3 of 15) and IRQ_ENABLE =
// ALS_IE | PS1_IE. Configuration is split between registers and the parameter RAM; every
// command increments the RESPONSE counter. I²C address 0x60 (older silicon: 0x5A).
type Si1145Minimal struct {
	conn      connection.RegisterConnection
	partID    uint8
	seqID     uint8
	chList    uint8
	irqEnable uint8
}

// NewSi1145Minimal creates the driver. conn must be a register-addressed I²C connection
// bound to address 0x60. It never returns an error (no bus access).
func NewSi1145Minimal(conn connection.RegisterConnection) (*Si1145Minimal, error) {
	return &Si1145Minimal{conn: conn, partID: 0x45}, nil
}

func (d *Si1145Minimal) readByte(reg uint8) (uint8, error) {
	b, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *Si1145Minimal) writeByte(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

// Init verifies the chip, unlocks it and configures UV, ALS and PS1.
func (d *Si1145Minimal) Init() error {
	time.Sleep(25 * time.Millisecond)
	part, err := d.readByte(si1145RegPartID)
	if err != nil {
		return err
	}
	if part < 0x45 || part > 0x47 {
		return fmt.Errorf("%w: 0x%02X", ErrSi1145NotFound, part)
	}
	d.partID = part
	if d.seqID, err = d.readByte(si1145RegSeqID); err != nil {
		return err
	}
	return d.applyDefaults()
}

func (d *Si1145Minimal) applyDefaults() error {
	if err := d.writeByte(si1145RegHWKey, si1145HWKey); err != nil {
		return err
	}
	if err := d.conn.WriteReg(uint32(si1145RegUCoef0), Si1145UCoefDefault[:]); err != nil {
		return err
	}
	chList := Si1145ChUV | Si1145ChALSIR | Si1145ChALSVis | Si1145ChPS1
	if err := d.paramSet(si1145ParamChList, chList); err != nil {
		return err
	}
	d.chList = chList
	if err := d.paramSet(si1145ParamPSLED12Select, 0x01); err != nil {
		return err
	}
	if err := d.writeByte(si1145RegPSLED21, 0x03); err != nil {
		return err
	}
	d.irqEnable = Si1145SourceALS | Si1145SourcePS1
	return d.writeByte(si1145RegIRQEnable, d.irqEnable)
}

func (d *Si1145Minimal) counter() (uint8, error) {
	r, err := d.readByte(si1145RegResponse)
	if err != nil {
		return 0, err
	}
	if r&0x80 != 0 {
		if err := d.writeByte(si1145RegCommand, si1145CmdNop); err != nil {
			return 0, err
		}
		if r, err = d.readByte(si1145RegResponse); err != nil {
			return 0, err
		}
	}
	return r & 0x0F, nil
}

func (d *Si1145Minimal) command(cmd uint8) error {
	before, err := d.counter()
	if err != nil {
		return err
	}
	if err := d.writeByte(si1145RegCommand, cmd); err != nil {
		return err
	}
	for i := 0; i < si1145CmdPolls; i++ {
		r, err := d.readByte(si1145RegResponse)
		if err != nil {
			return err
		}
		if r&0x80 != 0 {
			_ = d.writeByte(si1145RegCommand, si1145CmdNop)
			return fmt.Errorf("%w: command 0x%02X response 0x%02X", ErrSi1145Command, cmd, r)
		}
		if r&0x0F != before {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrSi1145Timeout
}

func (d *Si1145Minimal) paramSet(offset, value uint8) error {
	if err := d.writeByte(si1145RegParamWr, value); err != nil {
		return err
	}
	return d.command(si1145CmdParamSet | offset&0x1F)
}

func (d *Si1145Minimal) paramQuery(offset uint8) (uint8, error) {
	if err := d.command(si1145CmdParamQuery | offset&0x1F); err != nil {
		return 0, err
	}
	return d.readByte(si1145RegParamRd)
}

func (d *Si1145Minimal) clearIRQ() (uint8, error) {
	s, err := d.readByte(si1145RegIRQStatus)
	if err != nil {
		return 0, err
	}
	if s != 0 {
		if err := d.writeByte(si1145RegIRQStatus, s); err != nil {
			return 0, err
		}
	}
	return s, nil
}

func forceCommand(proximity, als bool) uint8 {
	switch {
	case proximity && als:
		return si1145CmdPSALSForce
	case proximity:
		return si1145CmdPSForce
	default:
		return si1145CmdALSForce
	}
}

func (d *Si1145Minimal) measure(proximity, als bool) error {
	if _, err := d.clearIRQ(); err != nil {
		return err
	}
	if _, err := d.counter(); err != nil {
		return err
	}
	if err := d.writeByte(si1145RegCommand, forceCommand(proximity, als)); err != nil {
		return err
	}
	var wait uint8
	if als && d.chList&0xF0 != 0 {
		wait |= Si1145SourceALS
	}
	if proximity {
		wait |= (d.chList & 0x07) << 2
	}
	wait &= d.irqEnable
	if wait == 0 {
		time.Sleep(100 * time.Millisecond)
		return nil
	}
	var seen uint8
	for i := 0; i < si1145MeasurePolls; i++ {
		s, err := d.clearIRQ()
		if err != nil {
			return err
		}
		seen |= s
		if seen&wait == wait {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrSi1145Timeout
}

func (d *Si1145Minimal) readData() ([6]uint16, error) {
	var v [6]uint16
	b, err := d.conn.ReadReg(uint32(si1145RegData), 12)
	if err != nil {
		return v, err
	}
	for i := range v {
		v[i] = uint16(b[2*i]) | uint16(b[2*i+1])<<8
	}
	return v, nil
}

// Read runs one forced PS + ALS measurement (PSALS_FORCE).
func (d *Si1145Minimal) Read() (Si1145Reading, error) {
	if err := d.measure(true, true); err != nil {
		return Si1145Reading{}, err
	}
	v, err := d.readData()
	if err != nil {
		return Si1145Reading{}, err
	}
	return Si1145Reading{Proximity: v[2], UVIndex: float64(v[5]) / 100, Visible: v[0], Infrared: v[1]}, nil
}

// Si1145Full extends Si1145Minimal with per-channel reads, the channel list, LED and
// photodiode selection, gains, ranges, alignment, UV coefficients, calibration,
// autonomous mode, the I²C address, reset and the INT interrupt (active low, driven
// after EnableInterrupt, released when the IRQ_STATUS bits are written back).
type Si1145Full struct {
	Si1145Minimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewSi1145Full creates the full driver; same argument as NewSi1145Minimal.
func NewSi1145Full(conn connection.RegisterConnection) (*Si1145Full, error) {
	return &Si1145Full{Si1145Minimal: Si1145Minimal{conn: conn, partID: 0x45}}, nil
}

func (d *Si1145Full) ledCount() uint8 { return d.partID - 0x44 }

// ReadID reads PART_ID (0x45/0x46/0x47), REV_ID and SEQ_ID (0x01 swaps the MEAS_RATE bytes).
func (d *Si1145Full) ReadID() (partID, revID, seqID uint8, err error) {
	b, err := d.conn.ReadReg(uint32(si1145RegPartID), 3)
	if err != nil {
		return 0, 0, 0, err
	}
	return b[0], b[1], b[2], nil
}

// ReadProximity runs a forced PS measurement (PS_FORCE) and returns one proximity
// channel (1–3; Si1145: 1), which must be enabled in CHLIST.
func (d *Si1145Full) ReadProximity(channel uint8) (uint16, error) {
	if channel < 1 || channel > d.ledCount() {
		return 0, ErrSi1145InvalidArgument
	}
	if d.chList&(1<<(channel-1)) == 0 {
		return 0, ErrSi1145ChannelDisabled
	}
	if err := d.measure(true, false); err != nil {
		return 0, err
	}
	v, err := d.readData()
	return v[1+channel], err
}

func (d *Si1145Full) alsValue(ch uint8, index int) (uint16, error) {
	if d.chList&ch == 0 {
		return 0, ErrSi1145ChannelDisabled
	}
	if err := d.measure(false, true); err != nil {
		return 0, err
	}
	v, err := d.readData()
	return v[index], err
}

// ReadUVIndex runs a forced ALS measurement and returns the UV index (AUX_DATA / 100).
func (d *Si1145Full) ReadUVIndex() (float64, error) {
	v, err := d.alsValue(Si1145ChUV, 5)
	return float64(v) / 100, err
}

// ReadVisible runs a forced ALS measurement and returns the raw visible count.
func (d *Si1145Full) ReadVisible() (uint16, error) { return d.alsValue(Si1145ChALSVis, 0) }

// ReadInfrared runs a forced ALS measurement and returns the raw infrared count.
func (d *Si1145Full) ReadInfrared() (uint16, error) { return d.alsValue(Si1145ChALSIR, 1) }

// ReadRaw reads the latest data registers without measuring.
func (d *Si1145Full) ReadRaw() (Si1145Raw, error) {
	v, err := d.readData()
	return Si1145Raw{Visible: v[0], Infrared: v[1], PS1: v[2], PS2: v[3], PS3: v[4], Aux: v[5]}, err
}

func (d *Si1145Full) readAux(mux uint8) (uint16, error) {
	saved := d.chList
	if err := d.paramSet(si1145ParamAuxADCMux, mux); err != nil {
		return 0, err
	}
	if err := d.paramSet(si1145ParamChList, Si1145ChAux); err != nil {
		return 0, err
	}
	d.chList = Si1145ChAux
	var v [6]uint16
	err := d.measure(false, true)
	if err == nil {
		v, err = d.readData()
	}
	rerr := d.paramSet(si1145ParamChList, saved)
	d.chList = saved
	if err != nil {
		return 0, err
	}
	return v[5], rerr
}

// ReadTemperatureRaw measures the die temperature on the auxiliary channel
// (AUX_ADCMUX 0x65, relative count); CHLIST is switched to EN_AUX and restored.
func (d *Si1145Full) ReadTemperatureRaw() (uint16, error) { return d.readAux(Si1145AuxTemperature) }

// ReadVDDRaw measures the supply voltage on the auxiliary channel (AUX_ADCMUX 0x75);
// CHLIST is restored afterwards.
func (d *Si1145Full) ReadVDDRaw() (uint16, error) { return d.readAux(Si1145AuxVDD) }

func b2u(b bool, v uint8) uint8 {
	if b {
		return v
	}
	return 0
}

// SetChannels selects the measured channels (CHLIST). uv and aux share AUX_DATA and
// exclude each other; ps2/ps3 need the Si1146/47.
func (d *Si1145Full) SetChannels(uv, visible, infrared, ps1, ps2, ps3, aux bool) error {
	if (uv && aux) || (ps2 && d.ledCount() < 2) || (ps3 && d.ledCount() < 3) {
		return ErrSi1145InvalidArgument
	}
	mask := b2u(uv, Si1145ChUV) | b2u(aux, Si1145ChAux) | b2u(infrared, Si1145ChALSIR) |
		b2u(visible, Si1145ChALSVis) | b2u(ps3, Si1145ChPS3) | b2u(ps2, Si1145ChPS2) | b2u(ps1, Si1145ChPS1)
	if err := d.paramSet(si1145ParamChList, mask); err != nil {
		return err
	}
	d.chList = mask
	return nil
}

// SetLEDCurrent sets an LED drive current (PS_LED21 / PS_LED3): led 1–3 (Si1145: 1),
// level 0 (off) … 15 (maximum, ~359 mA).
func (d *Si1145Full) SetLEDCurrent(led, level uint8) error {
	if led < 1 || led > d.ledCount() || level > 15 {
		return ErrSi1145InvalidArgument
	}
	if led == 3 {
		return d.writeByte(si1145RegPSLED3, level)
	}
	cur, err := d.readByte(si1145RegPSLED21)
	if err != nil {
		return err
	}
	if led == 1 {
		cur = cur&0xF0 | level
	} else {
		cur = cur&0x0F | level<<4
	}
	return d.writeByte(si1145RegPSLED21, cur)
}

// SetPSLED selects the LEDs a PS channel (1–3) drives (PSLED12_SELECT / PSLED3_SELECT);
// leds bit 0 = LED1, bit 1 = LED2, bit 2 = LED3, only the part's LEDs.
func (d *Si1145Full) SetPSLED(channel, leds uint8) error {
	if channel < 1 || channel > 3 || leds > (1<<d.ledCount())-1 {
		return ErrSi1145InvalidArgument
	}
	if channel == 3 {
		return d.paramSet(si1145ParamPSLED3Select, leds)
	}
	cur, err := d.paramQuery(si1145ParamPSLED12Select)
	if err != nil {
		return err
	}
	if channel == 1 {
		cur = cur&0x70 | leds
	} else {
		cur = cur&0x07 | leds<<4
	}
	return d.paramSet(si1145ParamPSLED12Select, cur)
}

// SetPSPhotodiode selects the photodiode of a PS channel (PSn_ADCMUX): large IR (0x03)
// or small (0x00).
func (d *Si1145Full) SetPSPhotodiode(channel uint8, large bool) error {
	if channel < 1 || channel > 3 {
		return ErrSi1145InvalidArgument
	}
	return d.paramSet(si1145ParamPS1ADCMux+channel-1, b2u(large, 0x03))
}

// SetGain sets the ADC gains 0–7 (integration time × 2^n) and the recommended recovery
// counts (one's complement of the gain). Do not combine a high PS gain with a high LED
// current.
func (d *Si1145Full) SetGain(visible, infrared, proximity uint8) error {
	if visible > 7 || infrared > 7 || proximity > 7 {
		return ErrSi1145InvalidArgument
	}
	for _, s := range [][3]uint8{
		{visible, si1145ParamALSVisADCGain, si1145ParamALSVisADCCounter},
		{infrared, si1145ParamALSIRADCGain, si1145ParamALSIRADCCounter},
		{proximity, si1145ParamPSADCGain, si1145ParamPSADCCounter},
	} {
		if err := d.paramSet(s[1], s[0]); err != nil {
			return err
		}
		if err := d.paramSet(s[2], (^s[0]&0x07)<<4); err != nil {
			return err
		}
	}
	return nil
}

// SetHighRange selects the high signal range (sensitivity ÷ 14.5): VIS_RANGE, IR_RANGE
// and PS_RANGE (PS_ADC_MODE stays 1).
func (d *Si1145Full) SetHighRange(visible, infrared, proximity bool) error {
	if err := d.paramSet(si1145ParamALSVisADCMisc, b2u(visible, 0x20)); err != nil {
		return err
	}
	if err := d.paramSet(si1145ParamALSIRADCMisc, b2u(infrared, 0x20)); err != nil {
		return err
	}
	return d.paramSet(si1145ParamPSADCMisc, 0x04|b2u(proximity, 0x20))
}

// SetAlignment reports the 16 LSBs instead of the 16 MSBs of the ADC result
// (ALS_ENCODING / PS_ENCODING align bits).
func (d *Si1145Full) SetAlignment(visible, infrared, ps1, ps2, ps3 bool) error {
	if err := d.paramSet(si1145ParamALSEncoding, b2u(infrared, 0x20)|b2u(visible, 0x10)); err != nil {
		return err
	}
	return d.paramSet(si1145ParamPSEncoding, b2u(ps3, 0x40)|b2u(ps2, 0x20)|b2u(ps1, 0x10))
}

// SetUVCoefficients writes UCOEF0..3 (Si1145UCoefDefault gives the UV index × 100).
func (d *Si1145Full) SetUVCoefficients(ucoef [4]uint8) error {
	return d.conn.WriteReg(uint32(si1145RegUCoef0), ucoef[:])
}

// ReadCalibration runs GET_CAL and returns the 12 factory calibration bytes (overwrites
// the data registers).
func (d *Si1145Full) ReadCalibration() ([12]uint8, error) {
	var cal [12]uint8
	if err := d.command(si1145CmdGetCal); err != nil {
		return cal, err
	}
	b, err := d.conn.ReadReg(uint32(si1145RegData), 12)
	if err != nil {
		return cal, err
	}
	copy(cal[:], b)
	return cal, nil
}

// StartAutonomous writes MEAS_RATE = round(interval / 31.25 µs) (bytes swapped when
// SEQ_ID = 0x01) and sends PS_AUTO, ALS_AUTO or PSALS_AUTO.
func (d *Si1145Full) StartAutonomous(intervalS float64, proximity, als bool) error {
	ticks := math.Round(intervalS / 31.25e-6)
	if ticks < 1 || ticks > 0xFFFF || !(proximity || als) {
		return ErrSi1145InvalidArgument
	}
	rate := uint16(ticks)
	if d.seqID == 0x01 {
		if err := d.writeByte(si1145RegPSRate, uint8(rate)); err != nil {
			return err
		}
		if err := d.writeByte(si1145RegMeasRate0, uint8(rate>>8)); err != nil {
			return err
		}
	} else if err := d.conn.WriteReg(uint32(si1145RegMeasRate0), []byte{uint8(rate), uint8(rate >> 8)}); err != nil {
		return err
	}
	cmd := si1145CmdPSALSAuto
	if !als {
		cmd = si1145CmdPSAuto
	} else if !proximity {
		cmd = si1145CmdALSAuto
	}
	return d.command(cmd)
}

// Pause pauses the autonomous loop (PSALS_PAUSE).
func (d *Si1145Full) Pause() error { return d.command(si1145CmdPSALSPause) }

// Force starts a forced measurement without waiting (PS_FORCE / ALS_FORCE / PSALS_FORCE).
func (d *Si1145Full) Force(proximity, als bool) error {
	if !(proximity || als) {
		return ErrSi1145InvalidArgument
	}
	return d.command(forceCommand(proximity, als))
}

// ReadResponse decodes RESPONSE.
func (d *Si1145Full) ReadResponse() (Si1145Response, error) {
	r, err := d.readByte(si1145RegResponse)
	if err != nil {
		return Si1145Response{}, err
	}
	if r&0x80 != 0 {
		return Si1145Response{Error: r}, nil
	}
	return Si1145Response{Counter: r & 0x0F}, nil
}

// SetI2CAddress writes I2C_ADDR and sends BUSADDR (volatile, 0x08–0x77). The chip answers
// on the new address at once; open a new connection afterwards.
func (d *Si1145Full) SetI2CAddress(address uint8) error {
	if address < 0x08 || address > 0x77 {
		return ErrSi1145InvalidArgument
	}
	if err := d.paramSet(si1145ParamI2CAddr, address); err != nil {
		return err
	}
	return d.writeByte(si1145RegCommand, si1145CmdBusAddr)
}

// Reset sends RESET, waits 10 ms, writes HW_KEY and re-applies the Minimal defaults.
func (d *Si1145Full) Reset() error {
	if err := d.writeByte(si1145RegCommand, si1145CmdReset); err != nil {
		return err
	}
	time.Sleep(10 * time.Millisecond)
	return d.applyDefaults()
}

// EnableInterrupt drives INT for sources (INT_CFG.INT_OE and IRQ_ENABLE, Si1145Source* mask).
func (d *Si1145Full) EnableInterrupt(source uint8) error {
	d.irqEnable = (d.irqEnable | source) & si1145SourceMask
	if err := d.writeByte(si1145RegIntCfg, 0x01); err != nil {
		return err
	}
	return d.writeByte(si1145RegIRQEnable, d.irqEnable)
}

// DisableInterrupt clears sources from IRQ_ENABLE. Forced reads only wait for enabled
// sources (none: a fixed 100 ms wait).
func (d *Si1145Full) DisableInterrupt(source uint8) error {
	d.irqEnable &^= source
	d.irqEnable &= si1145SourceMask
	return d.writeByte(si1145RegIRQEnable, d.irqEnable)
}

// PollInterrupt reads IRQ_STATUS, writes it back to clear it (releases INT) and returns
// the pending Si1145Source* mask among the enabled sources (0 = none).
func (d *Si1145Full) PollInterrupt() (uint8, error) {
	s, err := d.clearIRQ()
	return s & d.irqEnable, err
}

// OnInterrupt subscribes callback; it receives the Si1145Source* mask. With the
// connection's IntPin wired it fires on the falling edge; otherwise a polling goroutine
// checks IRQ_STATUS.
func (d *Si1145Full) OnInterrupt(callback func(status uint8)) error {
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
func (d *Si1145Full) OffInterrupt() error {
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

func (d *Si1145Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil || status == 0 {
		return
	}
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}
