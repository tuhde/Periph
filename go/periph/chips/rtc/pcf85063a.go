package rtc

import (
	"errors"
	"fmt"
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCF85063A register addresses (0x00-0x11).
const (
	pcf85063aRegControl1    uint8 = 0x00
	pcf85063aRegControl2    uint8 = 0x01
	pcf85063aRegOffset      uint8 = 0x02
	pcf85063aRegRAM         uint8 = 0x03
	pcf85063aRegSeconds     uint8 = 0x04
	pcf85063aRegSecondAlarm uint8 = 0x0B
	pcf85063aRegTimerValue  uint8 = 0x10
	pcf85063aRegTimerMode   uint8 = 0x11
)

// Control_1/Control_2/Timer_mode bit fields.
const (
	pcf85063aC1Stop   uint8 = 0x20
	pcf85063aC1SR     uint8 = 0x10
	pcf85063aC1CIE    uint8 = 0x04
	pcf85063aC11224   uint8 = 0x02
	pcf85063aC1CapSel uint8 = 0x01

	pcf85063aC2AIE   uint8 = 0x80
	pcf85063aC2AF    uint8 = 0x40
	pcf85063aC2MI    uint8 = 0x20
	pcf85063aC2HMI   uint8 = 0x10
	pcf85063aC2TF    uint8 = 0x08
	pcf85063aC2COF   uint8 = 0x07
	pcf85063aC2Flags uint8 = 0x48 // AF|TF: write 0 clears, 1 keeps

	pcf85063aTMTCFMask uint8 = 0x18
	pcf85063aTMTE      uint8 = 0x04
	pcf85063aTMTIE     uint8 = 0x02
	pcf85063aTMTITP    uint8 = 0x01

	pcf85063aSecondsOS uint8 = 0x80
)

// PCF85063AAddress is the fixed 7-bit I²C address.
const PCF85063AAddress uint8 = 0x51

// PCF85063A interrupt sources for PollInterrupt/EnableInterrupt/
// DisableInterrupt; combine with bitwise OR. Minute, half-minute and
// correction are enable-only: the timer, minute and half-minute sources all
// report as PCF85063ASourceTimer in the status mask.
const (
	PCF85063ASourceAlarm      uint8 = 0x01
	PCF85063ASourceTimer      uint8 = 0x02
	PCF85063ASourceMinute     uint8 = 0x04
	PCF85063ASourceHalfMinute uint8 = 0x08
	PCF85063ASourceCorrection uint8 = 0x10
)

// PCF85063AAlarmDisabled marks an alarm field that is ignored in the match.
const PCF85063AAlarmDisabled = -1

// ErrPCF85063AYear is returned by SetDatetime for a year outside 2000-2099.
var ErrPCF85063AYear = errors.New("PCF85063A: year must be 2000-2099")

// PCF85063AAlarm is the alarm configuration. A field set to
// PCF85063AAlarmDisabled is ignored; the alarm fires when every enabled
// field matches. Weekday is 0=Sunday..6=Saturday.
type PCF85063AAlarm struct {
	Second, Minute, Hour, Day, Weekday int
}

// PCF85063ASourceClock is a timer source clock (TCF encoding).
type PCF85063ASourceClock uint8

// PCF85063ASourceClock values.
const (
	PCF85063AClock4096Hz PCF85063ASourceClock = 0x00 // 244 µs … 62.256 ms
	PCF85063AClock64Hz   PCF85063ASourceClock = 0x01 // 15.625 ms … 3.984 s
	PCF85063AClock1Hz    PCF85063ASourceClock = 0x02 // 1 s … 255 s
	PCF85063AClock1_60Hz PCF85063ASourceClock = 0x03 // 1 min … 4 h 15 min
)

// PCF85063AOffsetMode is the offset correction mode.
type PCF85063AOffsetMode uint8

// PCF85063AOffsetMode values.
const (
	PCF85063AOffsetNormal PCF85063AOffsetMode = iota // every two hours, 4.34 ppm per LSB
	PCF85063AOffsetCourse                            // every four minutes, 4.069 ppm per LSB
)

// PCF85063AMinuteInterrupt selects the minute / half-minute interrupt.
type PCF85063AMinuteInterrupt uint8

// PCF85063AMinuteInterrupt values.
const (
	PCF85063AMinuteOff PCF85063AMinuteInterrupt = iota
	PCF85063AMinuteEvery
	PCF85063AMinuteHalf
)

// PCF85063AMinimal is the PCF85063A low-power I²C real-time clock — minimal
// interface.
//
// Reads and sets the calendar clock with no configuration beyond the
// connection, which must already be bound to the fixed address 0x51. The
// constructor forces 24-hour mode with the clock running (STOP=0); it
// writes no time register and does not reset the chip. There is no battery
// switch-over — the chip is fed from a single VDD supply.
//
// weekday follows the datasheet's suggested assignment, 0=Sunday..6=Saturday.
type PCF85063AMinimal struct {
	conn connection.RegisterConnection
}

// NewPCF85063AMinimal creates a PCF85063AMinimal, confirms the device
// answers (plain Control_1 read — no identity register), and forces
// 24-hour mode with STOP=0 (read-modify-write, other bits preserved).
func NewPCF85063AMinimal(conn connection.RegisterConnection) (*PCF85063AMinimal, error) {
	d := &PCF85063AMinimal{conn: conn}
	if _, err := d.readReg(pcf85063aRegControl1); err != nil {
		return nil, fmt.Errorf("PCF85063A: device not responding: %w", err)
	}
	c1, err := d.readControl1()
	if err != nil {
		return nil, err
	}
	if err := d.writeReg(pcf85063aRegControl1, c1&^(pcf85063aC1Stop|pcf85063aC11224)); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *PCF85063AMinimal) readReg(reg uint8) (uint8, error) {
	b, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *PCF85063AMinimal) writeReg(reg, val uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{val})
}

// readControl1 returns Control_1 with SR masked, safe for read-modify-write.
func (d *PCF85063AMinimal) readControl1() (uint8, error) {
	c, err := d.readReg(pcf85063aRegControl1)
	return c &^ pcf85063aC1SR, err
}

// GetDatetime reads the calendar clock (one 7-byte burst). year is
// 2000-2099; hour is 0-23; weekday is 0=Sunday..6=Saturday.
func (d *PCF85063AMinimal) GetDatetime() (year, month, day, weekday, hour, minute, second int, err error) {
	raw, err := d.conn.ReadReg(uint32(pcf85063aRegSeconds), 7)
	if err != nil {
		return
	}
	second = bcdToInt(raw[0] & 0x7F)
	minute = bcdToInt(raw[1] & 0x7F)
	hour = bcdToInt(raw[2] & 0x3F)
	day = bcdToInt(raw[3] & 0x3F)
	weekday = int(raw[4] & 0x07)
	month = bcdToInt(raw[5] & 0x1F)
	year = 2000 + bcdToInt(raw[6])
	return
}

// SetDatetime sets the calendar clock using the STOP-bit precision start:
// freeze the divider chain (STOP=1), write all seven time/date registers in
// one transaction, then release STOP. Forces 24-hour mode and clears the OS
// flag (the time is now known-good). It returns ErrPCF85063AYear, writing
// nothing, for a year outside 2000-2099.
func (d *PCF85063AMinimal) SetDatetime(year, month, day, weekday, hour, minute, second int) error {
	if year < 2000 || year > 2099 {
		return ErrPCF85063AYear
	}
	c1, err := d.readControl1()
	if err != nil {
		return err
	}
	c1 &^= pcf85063aC11224
	if err := d.writeReg(pcf85063aRegControl1, c1|pcf85063aC1Stop); err != nil {
		return err
	}
	buf := []byte{
		pcf85063aRegSeconds,
		intToBCD(second) & 0x7F, // OS = 0
		intToBCD(minute),
		intToBCD(hour) & 0x3F,
		intToBCD(day),
		uint8(weekday) & 0x07,
		intToBCD(month),
		intToBCD(year - 2000),
	}
	if err := d.conn.WriteReg(uint32(buf[0]), buf[1:]); err != nil {
		return err
	}
	return d.writeReg(pcf85063aRegControl1, c1&^pcf85063aC1Stop)
}

// PCF85063AFull extends PCF85063AMinimal with the five-field alarm, the
// countdown timer, minute / half-minute interrupts, programmable CLKOUT,
// offset calibration, oscillator-load selection, oscillator-stop detection,
// clock stop/start, the free RAM byte, software reset and the interrupt API.
//
// The INT pin is open-drain and active-low; CLKOUT is a separate pin.
type PCF85063AFull struct {
	*PCF85063AMinimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewPCF85063AFull creates a PCF85063AFull. See NewPCF85063AMinimal.
func NewPCF85063AFull(conn connection.RegisterConnection) (*PCF85063AFull, error) {
	m, err := NewPCF85063AMinimal(conn)
	if err != nil {
		return nil, err
	}
	return &PCF85063AFull{PCF85063AMinimal: m}, nil
}

func (d *PCF85063AFull) updateControl1(clearMask, setBits uint8) error {
	c1, err := d.readControl1()
	if err != nil {
		return err
	}
	return d.writeReg(pcf85063aRegControl1, (c1&^clearMask)|setBits)
}

// updateControl2 read-modify-writes Control_2. AF and TF are written 1
// (unchanged) unless explicitly cleared through clearMask (AND semantics).
func (d *PCF85063AFull) updateControl2(clearMask, setBits uint8) error {
	r, err := d.readReg(pcf85063aRegControl2)
	if err != nil {
		return err
	}
	return d.writeReg(pcf85063aRegControl2, (r&^clearMask)|setBits|(pcf85063aC2Flags&^clearMask))
}

func (d *PCF85063AFull) updateTimerMode(clearMask, setBits uint8) error {
	r, err := d.readReg(pcf85063aRegTimerMode)
	if err != nil {
		return err
	}
	return d.writeReg(pcf85063aRegTimerMode, ((r&^clearMask)|setBits)&0x1F)
}

// OscillatorStopped reports the OS flag (bit 7 of Seconds) — the time may
// be invalid; cleared by SetDatetime or ClearOscillatorStop.
func (d *PCF85063AFull) OscillatorStopped() (bool, error) {
	s, err := d.readReg(pcf85063aRegSeconds)
	return s&pcf85063aSecondsOS != 0, err
}

// ClearOscillatorStop clears OS, keeping the current seconds value. If
// OscillatorStopped is still true afterwards the oscillator is not running.
func (d *PCF85063AFull) ClearOscillatorStop() error {
	s, err := d.readReg(pcf85063aRegSeconds)
	if err != nil {
		return err
	}
	return d.writeReg(pcf85063aRegSeconds, s&0x7F)
}

// StopClock freezes the clock (STOP=1); the divider chain is held in reset.
func (d *PCF85063AFull) StopClock() error {
	return d.updateControl1(0, pcf85063aC1Stop)
}

// StartClock releases the clock (STOP=0).
func (d *PCF85063AFull) StartClock() error {
	return d.updateControl1(pcf85063aC1Stop, 0)
}

// GetAlarm decodes the alarm registers (0x0B-0x0F); disabled fields read as
// PCF85063AAlarmDisabled.
func (d *PCF85063AFull) GetAlarm() (PCF85063AAlarm, error) {
	raw, err := d.conn.ReadReg(uint32(pcf85063aRegSecondAlarm), 5)
	if err != nil {
		return PCF85063AAlarm{}, err
	}
	field := func(b, mask uint8, bcd bool) int {
		switch {
		case b&0x80 != 0:
			return PCF85063AAlarmDisabled
		case bcd:
			return bcdToInt(b & mask)
		default:
			return int(b & mask)
		}
	}
	return PCF85063AAlarm{
		Second:  field(raw[0], 0x7F, true),
		Minute:  field(raw[1], 0x7F, true),
		Hour:    field(raw[2], 0x3F, true),
		Day:     field(raw[3], 0x3F, true),
		Weekday: field(raw[4], 0x07, false),
	}, nil
}

// SetAlarm writes the alarm registers (0x0B-0x0F) and clears AF. Fields set
// to PCF85063AAlarmDisabled are ignored (AEN_x=1); an alarm with every field
// disabled never fires.
func (d *PCF85063AFull) SetAlarm(a PCF85063AAlarm) error {
	enc := func(v int, mask uint8) uint8 {
		if v < 0 {
			return 0x80
		}
		return intToBCD(v) & mask
	}
	wd := uint8(0x80)
	if a.Weekday >= 0 {
		wd = uint8(a.Weekday) & 0x07
	}
	if err := d.conn.WriteReg(uint32(pcf85063aRegSecondAlarm),
		[]byte{enc(a.Second, 0x7F), enc(a.Minute, 0x7F), enc(a.Hour, 0x3F), enc(a.Day, 0x3F), wd}); err != nil {
		return err
	}
	return d.updateControl2(pcf85063aC2AF, 0)
}

// ConfigureTimer configures and starts the countdown timer with a value
// (1-255) ticking at sourceClock. The timer is disabled first so
// Timer_value can be changed safely; pulsed selects a pulsed instead of a
// level interrupt. It does not set TIE — use EnableInterrupt with
// PCF85063ASourceTimer.
func (d *PCF85063AFull) ConfigureTimer(value uint8, sourceClock PCF85063ASourceClock, pulsed bool) error {
	if err := d.updateTimerMode(pcf85063aTMTE, 0); err != nil {
		return err
	}
	if err := d.writeReg(pcf85063aRegTimerValue, value); err != nil {
		return err
	}
	set := uint8(sourceClock)<<3 | pcf85063aTMTE
	if pulsed {
		set |= pcf85063aTMTITP
	}
	return d.updateTimerMode(pcf85063aTMTCFMask|pcf85063aTMTITP, set)
}

// DisableTimer stops the timer (TE=0, TIE=0) and selects the 1/60 Hz clock
// for power saving.
func (d *PCF85063AFull) DisableTimer() error {
	return d.updateTimerMode(pcf85063aTMTE|pcf85063aTMTIE|pcf85063aTMTCFMask, pcf85063aTMTCFMask)
}

// ReadTimer returns the live countdown value (not the loaded one); it reads
// until two consecutive reads agree.
func (d *PCF85063AFull) ReadTimer() (uint8, error) {
	prev, err := d.readReg(pcf85063aRegTimerValue)
	if err != nil {
		return 0, err
	}
	for {
		cur, err := d.readReg(pcf85063aRegTimerValue)
		if err != nil {
			return 0, err
		}
		if cur == prev {
			return cur, nil
		}
		prev = cur
	}
}

// SetMinuteInterrupt selects the minute / half-minute interrupt (requires
// offset mode PCF85063AOffsetNormal).
func (d *PCF85063AFull) SetMinuteInterrupt(mode PCF85063AMinuteInterrupt) error {
	var bits uint8
	switch mode {
	case PCF85063AMinuteEvery:
		bits = pcf85063aC2MI
	case PCF85063AMinuteHalf:
		bits = pcf85063aC2HMI
	}
	return d.updateControl2(pcf85063aC2MI|pcf85063aC2HMI, bits)
}

// SetClockOutput drives CLKOUT at one of 32768, 16384, 8192, 4096, 2048,
// 1024 or 1 Hz (any other value disables it).
func (d *PCF85063AFull) SetClockOutput(frequencyHz int) error {
	cof := uint8(7)
	switch frequencyHz {
	case 32768:
		cof = 0
	case 16384:
		cof = 1
	case 8192:
		cof = 2
	case 4096:
		cof = 3
	case 2048:
		cof = 4
	case 1024:
		cof = 5
	case 1:
		cof = 6
	}
	return d.updateControl2(pcf85063aC2COF, cof)
}

// DisableClockOutput disables CLKOUT (COF=111, pin low) to save power.
func (d *PCF85063AFull) DisableClockOutput() error {
	return d.updateControl2(pcf85063aC2COF, pcf85063aC2COF)
}

// GetOffset reads the offset calibration register: the two's-complement
// correction (-64..+63 LSB) and its mode.
func (d *PCF85063AFull) GetOffset() (int8, PCF85063AOffsetMode, error) {
	r, err := d.readReg(pcf85063aRegOffset)
	if err != nil {
		return 0, 0, err
	}
	offset := int8(r<<1) >> 1 // sign-extend bit 6
	mode := PCF85063AOffsetNormal
	if r&0x80 != 0 {
		mode = PCF85063AOffsetCourse
	}
	return offset, mode, nil
}

// SetOffset writes the offset calibration register (offset -64..+63 LSB;
// 4.34 ppm/LSB in normal mode, 4.069 ppm/LSB in course mode).
func (d *PCF85063AFull) SetOffset(offset int8, mode PCF85063AOffsetMode) error {
	v := uint8(offset) & 0x7F
	if mode == PCF85063AOffsetCourse {
		v |= 0x80
	}
	return d.writeReg(pcf85063aRegOffset, v)
}

// SetOscillatorLoad selects the oscillator load capacitance (CAP_SEL):
// 12.5 selects 12.5 pF, anything else 7 pF. Match the crystal's specified
// load.
func (d *PCF85063AFull) SetOscillatorLoad(picofarads float64) error {
	var set uint8
	if picofarads > 10 {
		set = pcf85063aC1CapSel
	}
	return d.updateControl1(pcf85063aC1CapSel, set)
}

// ReadRAM returns the free general-purpose RAM byte.
func (d *PCF85063AFull) ReadRAM() (uint8, error) {
	return d.readReg(pcf85063aRegRAM)
}

// WriteRAM writes the free general-purpose RAM byte.
func (d *PCF85063AFull) WriteRAM(value uint8) error {
	return d.writeReg(pcf85063aRegRAM, value)
}

// SoftwareReset sends the software-reset sequence (0x58 to Control_1).
// Resets ALL registers including the time (2000-01-01 00:00:00, OS=1).
func (d *PCF85063AFull) SoftwareReset() error {
	return d.writeReg(pcf85063aRegControl1, 0x58)
}

// OnInterrupt subscribes callback to be invoked with the pre-clear status
// mask (PCF85063ASourceAlarm / PCF85063ASourceTimer) on each interrupt.
// Uses the connection's IntPin if wired, otherwise falls back to a polling
// goroutine.
func (d *PCF85063AFull) OnInterrupt(callback func(uint8)) error {
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
	pin := d.conn.IntPin()
	if pin == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.pollPin = poll
		pin = poll
	}
	d.mu.Unlock()

	d.unsubscribe = pin.OnEdge(connection.Falling, func() { d.handleEdge() })
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *PCF85063AFull) OffInterrupt() error {
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

func (d *PCF85063AFull) handleEdge() {
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

// PollInterrupt reads Control_2, clears the set AF/TF flags (enable bits
// and COF preserved), and returns the pre-clear status mask —
// PCF85063ASourceAlarm and/or PCF85063ASourceTimer (the timer, minute and
// half-minute sources all report as PCF85063ASourceTimer).
func (d *PCF85063AFull) PollInterrupt() (uint8, error) {
	c2, err := d.readReg(pcf85063aRegControl2)
	if err != nil {
		return 0, err
	}
	var status uint8
	if c2&pcf85063aC2AF != 0 {
		status |= PCF85063ASourceAlarm
	}
	if c2&pcf85063aC2TF != 0 {
		status |= PCF85063ASourceTimer
	}
	if status != 0 {
		// Write 0 only to the flags seen set, 1 to the others, so a flag
		// that sets between the read and this write is not lost.
		if err := d.writeReg(pcf85063aRegControl2, (c2&^pcf85063aC2Flags)|(pcf85063aC2Flags&^c2)); err != nil {
			return 0, err
		}
	}
	return status, nil
}

// EnableInterrupt enables one or more interrupt sources (bitwise OR of
// PCF85063ASource*): AIE, TIE, MI, HMI, CIE.
func (d *PCF85063AFull) EnableInterrupt(source uint8) error {
	return d.setInterruptEnables(source, true)
}

// DisableInterrupt disables one or more interrupt sources (bitwise OR of
// PCF85063ASource*).
func (d *PCF85063AFull) DisableInterrupt(source uint8) error {
	return d.setInterruptEnables(source, false)
}

func (d *PCF85063AFull) setInterruptEnables(source uint8, enable bool) error {
	pick := func(bits uint8) (clear, set uint8) {
		if enable {
			return 0, bits
		}
		return bits, 0
	}
	var bits2 uint8
	if source&PCF85063ASourceAlarm != 0 {
		bits2 |= pcf85063aC2AIE
	}
	if source&PCF85063ASourceMinute != 0 {
		bits2 |= pcf85063aC2MI
	}
	if source&PCF85063ASourceHalfMinute != 0 {
		bits2 |= pcf85063aC2HMI
	}
	if bits2 != 0 {
		c, s := pick(bits2)
		if err := d.updateControl2(c, s); err != nil {
			return err
		}
	}
	if source&PCF85063ASourceTimer != 0 {
		c, s := pick(pcf85063aTMTIE)
		if err := d.updateTimerMode(c, s); err != nil {
			return err
		}
	}
	if source&PCF85063ASourceCorrection != 0 {
		c, s := pick(pcf85063aC1CIE)
		if err := d.updateControl1(c, s); err != nil {
			return err
		}
	}
	return nil
}
