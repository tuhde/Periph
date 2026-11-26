package light

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Si1133 register addresses, commands and parameter addresses.
const (
	si1133RegPartID    uint8 = 0x00
	si1133RegHostIn0   uint8 = 0x0A
	si1133RegCommand   uint8 = 0x0B
	si1133RegIRQEnable uint8 = 0x0F
	si1133RegResponse1 uint8 = 0x10
	si1133RegResponse0 uint8 = 0x11
	si1133RegIRQStatus uint8 = 0x12
	si1133RegHostOut0  uint8 = 0x13

	si1133PartID uint8 = 0x33

	si1133CmdResetCmdCtr uint8 = 0x00
	si1133CmdResetSW     uint8 = 0x01
	si1133CmdForce       uint8 = 0x11
	si1133CmdPause       uint8 = 0x12
	si1133CmdStart       uint8 = 0x13
	si1133CmdParamQuery  uint8 = 0x40
	si1133CmdParamSet    uint8 = 0x80

	si1133ParamI2CAddr     uint8 = 0x00
	si1133ParamChanList    uint8 = 0x01
	si1133ParamADCConfig0  uint8 = 0x02
	si1133ParamADCSens0    uint8 = 0x03
	si1133ParamADCPost0    uint8 = 0x04
	si1133ParamMeasConfig0 uint8 = 0x05
	si1133ParamMeasRateH   uint8 = 0x1A
	si1133ParamMeasRateL   uint8 = 0x1B
	si1133ParamMeasCount0  uint8 = 0x1C
	si1133ParamThreshold0H uint8 = 0x25
	si1133ParamBurst       uint8 = 0x2B

	si1133CmdPolls     = 50    // 1 ms each
	si1133MeasurePolls = 15000 // 1 ms each; covers HW_GAIN 11 with SW_GAIN 128
	si1133Scratch      = 5
)

// Si1133 RESPONSE0 bits.
const (
	// Si1133RespRunning is set while the sequencer runs.
	Si1133RespRunning uint8 = 0x80
	// Si1133RespSuspend is set while suspended.
	Si1133RespSuspend uint8 = 0x40
	// Si1133RespSleep is set while idle.
	Si1133RespSleep uint8 = 0x20
	// Si1133RespCmdErr is set on a command error.
	Si1133RespCmdErr uint8 = 0x10
)

// Si1133 photodiode selections (ADCMUX).
const (
	Si1133MuxSmallIR    uint8 = 0x00 // small IR photodiode
	Si1133MuxMediumIR   uint8 = 0x01 // medium IR photodiode
	Si1133MuxLargeIR    uint8 = 0x02 // large IR photodiode
	Si1133MuxWhite      uint8 = 0x0B // white photodiode
	Si1133MuxLargeWhite uint8 = 0x0D // large white photodiode
	Si1133MuxUV         uint8 = 0x18 // UV photodiode
	Si1133MuxUVDeep     uint8 = 0x19 // deep UV photodiode
)

// Si1133 interrupt sources (IRQ_ENABLE / IRQ_STATUS bits).
const (
	Si1133SourceChannel0 uint8 = 0x01 // channel 0 result ready
	Si1133SourceChannel1 uint8 = 0x02 // channel 1 result ready
	Si1133SourceChannel2 uint8 = 0x04 // channel 2 result ready
	Si1133SourceChannel3 uint8 = 0x08 // channel 3 result ready
	Si1133SourceChannel4 uint8 = 0x10 // channel 4 result ready
	Si1133SourceChannel5 uint8 = 0x20 // channel 5 result ready
)

// Errors returned by the Si1133 driver.
var (
	// ErrSi1133NotFound is returned by Init when PART_ID is not 0x33.
	ErrSi1133NotFound = errors.New("si1133: PART_ID mismatch")
	// ErrSi1133Timeout is returned when a command, reset or measurement does not complete.
	ErrSi1133Timeout = errors.New("si1133: operation timed out")
	// ErrSi1133Command wraps a CMD_ERR response; the message carries the error code.
	ErrSi1133Command = errors.New("si1133: command error")
	// ErrSi1133ChannelsDisabled is returned by Read when channel 0 or 1 is disabled.
	ErrSi1133ChannelsDisabled = errors.New("si1133: channels 0 and 1 must be enabled for Read")
	// ErrSi1133InvalidArgument is returned for an argument out of range.
	ErrSi1133InvalidArgument = errors.New("si1133: invalid argument")
)

// Si1133Reading holds the UV and white counts of one forced measurement.
type Si1133Reading struct {
	UV    int32 // signed 24-bit raw count of channel 0 (UV)
	White int32 // signed 24-bit raw count of channel 1 (white)
}

// Si1133Response is the decoded RESPONSE0 register.
type Si1133Response struct {
	State   uint8 // Si1133RespRunning / Si1133RespSuspend / Si1133RespSleep bits
	Error   bool  // CMD_ERR flag
	Counter uint8 // command counter (the error code when Error is set)
}

// Si1133Minimal is the Si1133 UV index and ambient light sensor driver (Silicon Labs) —
// minimal interface: one call runs a forced measurement and returns the UV and white
// counts.
//
// The constructor does no bus access. Init waits 25 ms, checks PART_ID (0x33), sends
// RESET_SW and configures channel 0 = UV (ADCMUX 0x18, HW_GAIN 9, 24-bit output),
// channel 1 = white (ADCMUX 0x0B, HW_GAIN 7, 24-bit), CHAN_LIST = 0x03 and
// IRQ_ENABLE = 0x3F (so IRQ_STATUS flags finished channels). All configuration goes
// through the indirect parameter table; every command is confirmed by the RESPONSE0
// counter. Results are raw ADC counts. INT is open-drain and must be high at power-up.
// I²C address 0x55 (AD pulled up) or 0x52 (AD pulled down).
type Si1133Minimal struct {
	conn      connection.RegisterConnection
	chanList  uint8
	bits24    uint8
	irqEnable uint8
}

// NewSi1133Minimal creates the driver. conn must be a register-addressed I²C connection
// bound to address 0x55 or 0x52. It never returns an error (no bus access).
func NewSi1133Minimal(conn connection.RegisterConnection) (*Si1133Minimal, error) {
	return &Si1133Minimal{conn: conn}, nil
}

func (d *Si1133Minimal) readByte(reg uint8) (uint8, error) {
	b, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *Si1133Minimal) writeByte(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

// Init verifies the chip, resets it and configures the UV and white channels.
func (d *Si1133Minimal) Init() error {
	time.Sleep(25 * time.Millisecond)
	part, err := d.readByte(si1133RegPartID)
	if err != nil {
		return err
	}
	if part != si1133PartID {
		return fmt.Errorf("%w: 0x%02X", ErrSi1133NotFound, part)
	}
	if err := d.softwareReset(); err != nil {
		return err
	}
	return d.applyDefaults()
}

func (d *Si1133Minimal) softwareReset() error {
	if err := d.writeByte(si1133RegCommand, si1133CmdResetSW); err != nil {
		return err
	}
	time.Sleep(10 * time.Millisecond)
	d.chanList, d.bits24, d.irqEnable = 0, 0, 0
	for i := 0; i < si1133CmdPolls; i++ {
		r, err := d.readByte(si1133RegResponse0)
		if err != nil {
			return err
		}
		if r&Si1133RespSleep != 0 {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrSi1133Timeout
}

func (d *Si1133Minimal) applyDefaults() error {
	if err := d.setChannel(0, Si1133MuxUV, 9, 0, 0, false, true, 0); err != nil {
		return err
	}
	if err := d.setChannel(1, Si1133MuxWhite, 7, 0, 0, false, true, 0); err != nil {
		return err
	}
	if err := d.paramSet(si1133ParamChanList, 0x03); err != nil {
		return err
	}
	d.chanList = 0x03
	d.irqEnable = 0x3F
	return d.writeByte(si1133RegIRQEnable, d.irqEnable)
}

func (d *Si1133Minimal) counter() (uint8, error) {
	r, err := d.readByte(si1133RegResponse0)
	if err != nil {
		return 0, err
	}
	if r&Si1133RespCmdErr != 0 {
		if err := d.writeByte(si1133RegCommand, si1133CmdResetCmdCtr); err != nil {
			return 0, err
		}
		if r, err = d.readByte(si1133RegResponse0); err != nil {
			return 0, err
		}
	}
	return r & 0x0F, nil
}

func (d *Si1133Minimal) waitCounter(before uint8) error {
	for i := 0; i < si1133CmdPolls; i++ {
		r, err := d.readByte(si1133RegResponse0)
		if err != nil {
			return err
		}
		if r&Si1133RespCmdErr != 0 {
			_ = d.writeByte(si1133RegCommand, si1133CmdResetCmdCtr)
			return fmt.Errorf("%w 0x%02X", ErrSi1133Command, r&0x1F)
		}
		if r&0x0F != before {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrSi1133Timeout
}

func (d *Si1133Minimal) command(cmd uint8) error {
	before, err := d.counter()
	if err != nil {
		return err
	}
	if err := d.writeByte(si1133RegCommand, cmd); err != nil {
		return err
	}
	return d.waitCounter(before)
}

func (d *Si1133Minimal) paramSet(address, value uint8) error {
	before, err := d.counter()
	if err != nil {
		return err
	}
	if err := d.conn.WriteReg(uint32(si1133RegHostIn0), []byte{value, si1133CmdParamSet | address&0x3F}); err != nil {
		return err
	}
	return d.waitCounter(before)
}

func (d *Si1133Minimal) paramQuery(address uint8) (uint8, error) {
	if err := d.command(si1133CmdParamQuery | address&0x3F); err != nil {
		return 0, err
	}
	return d.readByte(si1133RegResponse1)
}

func (d *Si1133Minimal) setChannel(channel, adcmux, hwGain, swGain, decimRate uint8, highSignal, bits24 bool, postshift uint8) error {
	base := 4 * channel
	if err := d.paramSet(si1133ParamADCConfig0+base, decimRate<<5|adcmux); err != nil {
		return err
	}
	var hsig, out uint8
	if highSignal {
		hsig = 0x80
	}
	if bits24 {
		out = 0x40
	}
	if err := d.paramSet(si1133ParamADCSens0+base, hsig|swGain<<4|hwGain); err != nil {
		return err
	}
	if err := d.paramSet(si1133ParamADCPost0+base, out|postshift<<3); err != nil {
		return err
	}
	if bits24 {
		d.bits24 |= 1 << channel
	} else {
		d.bits24 &^= 1 << channel
	}
	return nil
}

func (d *Si1133Minimal) readOutputs() ([]int32, error) {
	n := 0
	for ch := uint(0); ch < 6; ch++ {
		if d.chanList&(1<<ch) != 0 {
			if d.bits24&(1<<ch) != 0 {
				n += 3
			} else {
				n += 2
			}
		}
	}
	values := []int32{}
	if n == 0 {
		return values, nil
	}
	b, err := d.conn.ReadReg(uint32(si1133RegHostOut0), n)
	if err != nil {
		return nil, err
	}
	pos := 0
	for ch := uint(0); ch < 6; ch++ {
		if d.chanList&(1<<ch) == 0 {
			continue
		}
		if d.bits24&(1<<ch) != 0 {
			u := uint32(b[pos])<<16 | uint32(b[pos+1])<<8 | uint32(b[pos+2])
			values = append(values, int32(u<<8)>>8)
			pos += 3
		} else {
			values = append(values, int32(uint16(b[pos])<<8|uint16(b[pos+1])))
			pos += 2
		}
	}
	return values, nil
}

func (d *Si1133Minimal) measure() ([]int32, error) {
	if _, err := d.readByte(si1133RegIRQStatus); err != nil { // clear stale flags
		return nil, err
	}
	if err := d.command(si1133CmdForce); err != nil {
		return nil, err
	}
	wait := d.chanList & d.irqEnable
	if wait == 0 {
		time.Sleep(100 * time.Millisecond)
	} else {
		var seen uint8
		for i := 0; ; i++ {
			s, err := d.readByte(si1133RegIRQStatus)
			if err != nil {
				return nil, err
			}
			seen |= s
			if seen&wait == wait {
				break
			}
			if i+1 >= si1133MeasurePolls {
				return nil, ErrSi1133Timeout
			}
			time.Sleep(time.Millisecond)
		}
	}
	return d.readOutputs()
}

// Read runs one forced measurement and returns the signed 24-bit UV and white counts of
// channels 0 and 1 (near zero or slightly negative in the dark).
func (d *Si1133Minimal) Read() (Si1133Reading, error) {
	if d.chanList&0x03 != 0x03 {
		return Si1133Reading{}, ErrSi1133ChannelsDisabled
	}
	v, err := d.measure()
	if err != nil {
		return Si1133Reading{}, err
	}
	return Si1133Reading{UV: v[0], White: v[1]}, nil
}

// Si1133Full is the full Si1133 driver: per-channel configuration, single-photodiode
// reads, autonomous and burst modes, thresholds, direct parameter access, the I²C
// address and the INT interrupt (active low, open-drain, released when IRQ_STATUS is read).
type Si1133Full struct {
	Si1133Minimal

	counterIndex [6]uint8
	mu           sync.Mutex
	callback     func(uint8)
	unsubscribe  func()
	pollPin      *connection.PollingInputPin
}

// NewSi1133Full creates the full driver; same argument as NewSi1133Minimal.
func NewSi1133Full(conn connection.RegisterConnection) (*Si1133Full, error) {
	return &Si1133Full{Si1133Minimal: Si1133Minimal{conn: conn}}, nil
}

// ReadID reads PART_ID (0x33), HW_ID (bits 4:0 = 0x03 for the Si1133-AA00) and REV_ID.
func (d *Si1133Full) ReadID() (partID, hwID, revID uint8, err error) {
	b, err := d.conn.ReadReg(uint32(si1133RegPartID), 3)
	if err != nil {
		return 0, 0, 0, err
	}
	return b[0], b[1], b[2], nil
}

// ConfigureChannel writes ADCCONFIG, ADCSENS and ADCPOST of one channel and clears its
// threshold select. channel 0–5, adcmux a Si1133Mux* value, hwGain 0–11 (48.8 µs × 2^n),
// swGain 0–7 (2^n accumulated samples), decimRate 0–3, highSignal divides the
// sensitivity by 14.5, bits24 selects 24-bit signed (else 16-bit unsigned) output,
// postshift 0–7 right-shifts the result.
func (d *Si1133Full) ConfigureChannel(channel, adcmux, hwGain, swGain, decimRate uint8, highSignal, bits24 bool, postshift uint8) error {
	if channel > 5 || adcmux > 0x1F || hwGain > 11 || swGain > 7 || decimRate > 3 || postshift > 7 {
		return ErrSi1133InvalidArgument
	}
	return d.setChannel(channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24, postshift)
}

// EnableChannels selects the channels that run (CHAN_LIST, bit c = channel c, 0x00–0x3F).
func (d *Si1133Full) EnableChannels(mask uint8) error {
	if mask > 0x3F {
		return ErrSi1133InvalidArgument
	}
	if err := d.paramSet(si1133ParamChanList, mask); err != nil {
		return err
	}
	d.chanList = mask
	return nil
}

// ReadChannels returns the results of all enabled channels in channel order. With force
// a forced measurement runs first; otherwise only HOSTOUT is decoded (autonomous mode).
func (d *Si1133Full) ReadChannels(force bool) ([]int32, error) {
	if force {
		return d.measure()
	}
	return d.readOutputs()
}

func (d *Si1133Full) scratchRead(adcmux, hwGain uint8) (int32, error) {
	saved := d.chanList
	if err := d.setChannel(si1133Scratch, adcmux, hwGain, 0, 0, false, true, 0); err != nil {
		return 0, err
	}
	if d.counterIndex[si1133Scratch] != 0 {
		if err := d.paramSet(si1133ParamMeasConfig0+4*si1133Scratch, 0); err != nil {
			return 0, err
		}
		d.counterIndex[si1133Scratch] = 0
	}
	if err := d.paramSet(si1133ParamChanList, 1<<si1133Scratch); err != nil {
		return 0, err
	}
	d.chanList = 1 << si1133Scratch
	v, merr := d.measure()
	rerr := d.paramSet(si1133ParamChanList, saved)
	d.chanList = saved
	if merr != nil {
		return 0, merr
	}
	if rerr != nil {
		return 0, rerr
	}
	return v[0], nil
}

// ReadUV measures the UV photodiode once (channel 5 used as scratch, HW_GAIN 9).
func (d *Si1133Full) ReadUV() (int32, error) { return d.scratchRead(Si1133MuxUV, 9) }

// ReadWhite measures the white photodiode once (channel 5 used as scratch, HW_GAIN 7).
func (d *Si1133Full) ReadWhite() (int32, error) { return d.scratchRead(Si1133MuxWhite, 7) }

// ReadIR measures an IR photodiode once (Si1133MuxSmallIR, Si1133MuxMediumIR or
// Si1133MuxLargeIR; channel 5 used as scratch, HW_GAIN 7).
func (d *Si1133Full) ReadIR(photodiode uint8) (int32, error) {
	if photodiode > Si1133MuxLargeIR {
		return 0, ErrSi1133InvalidArgument
	}
	return d.scratchRead(photodiode, 7)
}

// Force sends FORCE without waiting for the results.
func (d *Si1133Full) Force() error { return d.command(si1133CmdForce) }

// SetChannelCounter assigns a rate counter to a channel (MEASCONFIG.COUNTER_INDEX:
// 0 = none, 1–3 = MEASCOUNT0..2).
func (d *Si1133Full) SetChannelCounter(channel, index uint8) error {
	if channel > 5 || index > 3 {
		return ErrSi1133InvalidArgument
	}
	if err := d.paramSet(si1133ParamMeasConfig0+4*channel, index<<6); err != nil {
		return err
	}
	d.counterIndex[channel] = index
	return nil
}

// StartAutonomous writes MEASRATE (rateS in seconds, 800 µs units, 800 µs … 52.4 s) and
// MEASCOUNT0..2 (1–255 each), assigns counter 1 to every enabled channel without one and
// sends START. Channel period = rate × MEASCOUNTk.
func (d *Si1133Full) StartAutonomous(rateS float64, counters [3]uint8) error {
	t := rateS/800e-6 + 0.5
	if !(t >= 1 && t < 65536) || counters[0] == 0 || counters[1] == 0 || counters[2] == 0 {
		return ErrSi1133InvalidArgument
	}
	ticks := uint16(t)
	if err := d.paramSet(si1133ParamMeasRateH, uint8(ticks>>8)); err != nil {
		return err
	}
	if err := d.paramSet(si1133ParamMeasRateL, uint8(ticks)); err != nil {
		return err
	}
	for i, c := range counters {
		if err := d.paramSet(si1133ParamMeasCount0+uint8(i), c); err != nil {
			return err
		}
	}
	for ch := uint8(0); ch < 6; ch++ {
		if d.chanList&(1<<ch) != 0 && d.counterIndex[ch] == 0 {
			if err := d.SetChannelCounter(ch, 1); err != nil {
				return err
			}
		}
	}
	return d.command(si1133CmdStart)
}

// Pause pauses autonomous measurements (PAUSE).
func (d *Si1133Full) Pause() error { return d.command(si1133CmdPause) }

// SetBurst configures burst mode (BURST): count 1–127 readings, 0 disables.
func (d *Si1133Full) SetBurst(count uint8) error {
	if count > 127 {
		return ErrSi1133InvalidArgument
	}
	var v uint8
	if count != 0 {
		v = 0x80 | count
	}
	return d.paramSet(si1133ParamBurst, v)
}

// SetThreshold sets one of the three 16-bit thresholds (index 0–2).
func (d *Si1133Full) SetThreshold(index uint8, value uint16) error {
	if index > 2 {
		return ErrSi1133InvalidArgument
	}
	a := si1133ParamThreshold0H + 2*index
	if err := d.paramSet(a, uint8(value>>8)); err != nil {
		return err
	}
	return d.paramSet(a+1, uint8(value))
}

// SetChannelThreshold selects the threshold a channel's interrupt compares against
// (ADCPOST.THRESH_SEL: 0 = off, 1–3 = THRESHOLD0..2).
func (d *Si1133Full) SetChannelThreshold(channel, index uint8) error {
	if channel > 5 || index > 3 {
		return ErrSi1133InvalidArgument
	}
	a := si1133ParamADCPost0 + 4*channel
	v, err := d.paramQuery(a)
	if err != nil {
		return err
	}
	return d.paramSet(a, v&0xFC|index)
}

// ReadParameter reads a parameter-table byte (PARAM_QUERY, address 0x00–0x3F).
func (d *Si1133Full) ReadParameter(address uint8) (uint8, error) {
	if address > 0x3F {
		return 0, ErrSi1133InvalidArgument
	}
	return d.paramQuery(address)
}

// WriteParameter writes a parameter-table byte (PARAM_SET, address 0x00–0x3F). Writing
// channel parameters directly bypasses the driver's record of the channel layout;
// prefer ConfigureChannel.
func (d *Si1133Full) WriteParameter(address, value uint8) error {
	if address > 0x3F {
		return ErrSi1133InvalidArgument
	}
	return d.paramSet(address, value)
}

// ReadResponse decodes RESPONSE0.
func (d *Si1133Full) ReadResponse() (Si1133Response, error) {
	r, err := d.readByte(si1133RegResponse0)
	if err != nil {
		return Si1133Response{}, err
	}
	return Si1133Response{State: r & 0xE0, Error: r&Si1133RespCmdErr != 0, Counter: r & 0x0F}, nil
}

// DataReady reads IRQ_STATUS (clears it): bit c set when channel c has a new result.
func (d *Si1133Full) DataReady() (uint8, error) {
	s, err := d.readByte(si1133RegIRQStatus)
	return s & 0x3F, err
}

// SetI2CAddress changes the I²C address (volatile, 0x08–0x77). The chip answers on the
// new address at once, so no completion check is made; open a new connection afterwards.
func (d *Si1133Full) SetI2CAddress(address uint8) error {
	if address < 0x08 || address > 0x77 {
		return ErrSi1133InvalidArgument
	}
	if _, err := d.readByte(si1133RegResponse0); err != nil {
		return err
	}
	return d.conn.WriteReg(uint32(si1133RegHostIn0), []byte{address, si1133CmdParamSet | si1133ParamI2CAddr})
}

// Reset sends RESET_SW, waits for SLEEP and re-applies the Minimal defaults.
func (d *Si1133Full) Reset() error {
	if err := d.softwareReset(); err != nil {
		return err
	}
	d.counterIndex = [6]uint8{}
	return d.applyDefaults()
}

// EnableInterrupt enables INT for channels (IRQ_ENABLE, Si1133SourceChannel* mask).
func (d *Si1133Full) EnableInterrupt(source uint8) error {
	d.irqEnable = (d.irqEnable | source) & 0x3F
	return d.writeByte(si1133RegIRQEnable, d.irqEnable)
}

// DisableInterrupt disables INT for channels. Forced reads only wait for channels whose
// interrupt is enabled (none: a fixed 100 ms wait).
func (d *Si1133Full) DisableInterrupt(source uint8) error {
	d.irqEnable &^= source
	d.irqEnable &= 0x3F
	return d.writeByte(si1133RegIRQEnable, d.irqEnable)
}

// PollInterrupt reads IRQ_STATUS (clears it, releases INT) and returns the channel mask
// (0 = nothing pending).
func (d *Si1133Full) PollInterrupt() (uint8, error) { return d.DataReady() }

// OnInterrupt subscribes callback; it receives the IRQ_STATUS channel mask. With the
// connection's IntPin wired it fires on the falling edge; otherwise a polling goroutine
// checks IRQ_STATUS.
func (d *Si1133Full) OnInterrupt(callback func(status uint8)) error {
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
func (d *Si1133Full) OffInterrupt() error {
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

func (d *Si1133Full) handleEdge() {
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
