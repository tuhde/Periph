package rtc

import (
	"fmt"
	"math"
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCF8523 register addresses (0x00-0x13).
const (
	pcf8523RegControl1      uint8 = 0x00
	pcf8523RegControl2      uint8 = 0x01
	pcf8523RegControl3      uint8 = 0x02
	pcf8523RegSeconds       uint8 = 0x03
	pcf8523RegMinuteAlarm   uint8 = 0x0A
	pcf8523RegOffset        uint8 = 0x0E
	pcf8523RegTmrClkoutCtrl uint8 = 0x0F
	pcf8523RegTmrAFreqCtrl  uint8 = 0x10
	pcf8523RegTmrAReg       uint8 = 0x11
	pcf8523RegTmrBFreqCtrl  uint8 = 0x12
	pcf8523RegTmrBReg       uint8 = 0x13
)

// CONTROL_1/2/3 and TMR_CLKOUT_CTRL bit fields.
const (
	pcf8523C1T    uint8 = 0x40
	pcf8523C1Stop uint8 = 0x20
	pcf8523C1SR   uint8 = 0x10
	pcf8523C11224 uint8 = 0x08
	pcf8523C1SIE  uint8 = 0x04
	pcf8523C1AIE  uint8 = 0x02

	pcf8523C2WTAF      uint8 = 0x80
	pcf8523C2CTAF      uint8 = 0x40
	pcf8523C2CTBF      uint8 = 0x20
	pcf8523C2SF        uint8 = 0x10
	pcf8523C2AF        uint8 = 0x08
	pcf8523C2WTAIE     uint8 = 0x04
	pcf8523C2CTAIE     uint8 = 0x02
	pcf8523C2CTBIE     uint8 = 0x01
	pcf8523C2Clearable uint8 = 0x78 // CTAF|CTBF|SF|AF: write 0 clears, 1 keeps
	pcf8523C2Enables   uint8 = 0x07

	pcf8523C3PMMask uint8 = 0xE0
	pcf8523C3BSF    uint8 = 0x08
	pcf8523C3BLF    uint8 = 0x04
	pcf8523C3BSIE   uint8 = 0x02
	pcf8523C3BLIE   uint8 = 0x01

	pcf8523SecondsOS uint8 = 0x80

	pcf8523TmrTAM          uint8 = 0x80
	pcf8523TmrTBM          uint8 = 0x40
	pcf8523TmrCOFMask      uint8 = 0x38
	pcf8523TmrTACMask      uint8 = 0x06
	pcf8523TmrTACCountdown uint8 = 0x02
	pcf8523TmrTACWatchdog  uint8 = 0x04
	pcf8523TmrTBC          uint8 = 0x01
)

// PCF8523Address is the fixed 7-bit I²C address.
const PCF8523Address uint8 = 0x68

// PCF8523 interrupt sources for PollInterrupt/EnableInterrupt/
// DisableInterrupt; combine with bitwise OR.
const (
	PCF8523SourceSecond        uint8 = 0x01
	PCF8523SourceTimerA        uint8 = 0x02
	PCF8523SourceTimerB        uint8 = 0x04
	PCF8523SourceAlarm         uint8 = 0x08
	PCF8523SourceBatterySwitch uint8 = 0x10
	PCF8523SourceBatteryLow    uint8 = 0x20
)

// PCF8523AlarmDisabled marks an alarm field that is ignored in the match.
const PCF8523AlarmDisabled = -1

// PCF8523Alarm is the alarm configuration. A field set to
// PCF8523AlarmDisabled is ignored; the alarm fires when every enabled field
// matches. Weekday is 0=Sunday..6=Saturday.
type PCF8523Alarm struct {
	Minute, Hour, Day, Weekday int
}

// PCF8523TimerAMode selects Timer A's operating mode.
type PCF8523TimerAMode uint8

// PCF8523TimerAMode values.
const (
	PCF8523TimerACountdown PCF8523TimerAMode = iota
	PCF8523TimerAWatchdog
)

// PCF8523SourceClock is a timer source clock (TAQ/TBQ encoding).
type PCF8523SourceClock uint8

// PCF8523SourceClock values.
const (
	PCF8523Clock4096Hz   PCF8523SourceClock = 0x00 // 244 µs … 62.256 ms
	PCF8523Clock64Hz     PCF8523SourceClock = 0x01 // 15.625 ms … 3.984 s
	PCF8523Clock1Hz      PCF8523SourceClock = 0x02 // 1 s … 255 s
	PCF8523Clock1_60Hz   PCF8523SourceClock = 0x03 // 1 min … 255 min
	PCF8523Clock1_3600Hz PCF8523SourceClock = 0x07 // 1 h … 255 h
)

// PCF8523OffsetMode is the offset correction interval.
type PCF8523OffsetMode uint8

// PCF8523OffsetMode values.
const (
	PCF8523OffsetEveryTwoHours PCF8523OffsetMode = iota // 4.34 ppm per LSB
	PCF8523OffsetEveryMinute                            // 4.069 ppm per LSB
)

// PCF8523BatteryMode is the battery switch-over mode.
type PCF8523BatteryMode uint8

// PCF8523BatteryMode values.
const (
	PCF8523BatteryStandard PCF8523BatteryMode = iota // switch when VDD < VBAT and VDD < 2.5 V
	PCF8523BatteryDirect                             // switch whenever VDD < VBAT
	PCF8523BatteryDisabled                           // VDD only — tie VBAT to VDD
)

// pcf8523TBWWidthsMs maps TBW[2:0] to the low-pulse width in ms (datasheet
// Table 36; not uniformly spaced).
var pcf8523TBWWidthsMs = [8]float64{46.875, 62.5, 78.125, 93.75, 125, 156.25, 187.5, 218.75}

// PCF8523Minimal is the PCF8523 low-power I²C real-time clock — minimal
// interface.
//
// Reads and sets the battery-backed calendar clock with no configuration
// beyond the connection, which must already be bound to the fixed address
// 0x68. The constructor enables battery switch-over in standard mode with
// battery-low detection (PM[2:0]=000) — a deliberate override of the
// chip's single-supply power-on default.
//
// The HOURS registers are always operated in 24-hour mode. weekday follows
// the datasheet's suggested assignment, 0=Sunday..6=Saturday.
type PCF8523Minimal struct {
	conn connection.Connection
}

// NewPCF8523Minimal creates a PCF8523Minimal, confirms the device answers
// (plain CONTROL_1 read — no identity register), and writes CONTROL_3=0x00
// (battery switch-over standard mode, battery-low detection enabled).
func NewPCF8523Minimal(conn connection.Connection) (*PCF8523Minimal, error) {
	d := &PCF8523Minimal{conn: conn}
	if _, err := d.readReg(pcf8523RegControl1); err != nil {
		return nil, fmt.Errorf("PCF8523: device not responding: %w", err)
	}
	if err := d.writeReg(pcf8523RegControl3, 0x00); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *PCF8523Minimal) readReg(reg uint8) (uint8, error) {
	b, err := d.conn.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *PCF8523Minimal) writeReg(reg, val uint8) error {
	return d.conn.Write([]byte{reg, val})
}

// readControl1 returns CONTROL_1 with T and SR masked, safe for
// read-modify-write.
func (d *PCF8523Minimal) readControl1() (uint8, error) {
	c, err := d.readReg(pcf8523RegControl1)
	return c &^ (pcf8523C1T | pcf8523C1SR), err
}

// GetDatetime reads the calendar clock. year is 2000-2099; hour is 0-23;
// weekday is 0=Sunday..6=Saturday.
func (d *PCF8523Minimal) GetDatetime() (year, month, day, weekday, hour, minute, second int, err error) {
	raw, err := d.conn.WriteRead([]byte{pcf8523RegSeconds}, 7)
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
// flag (the time is now known-good).
func (d *PCF8523Minimal) SetDatetime(year, month, day, weekday, hour, minute, second int) error {
	c1, err := d.readControl1()
	if err != nil {
		return err
	}
	c1 &^= pcf8523C11224
	if err := d.writeReg(pcf8523RegControl1, c1|pcf8523C1Stop); err != nil {
		return err
	}
	buf := []byte{
		pcf8523RegSeconds,
		intToBCD(second) & 0x7F, // OS = 0
		intToBCD(minute),
		intToBCD(hour) & 0x3F,
		intToBCD(day),
		uint8(weekday) & 0x07,
		intToBCD(month),
		intToBCD(year - 2000),
	}
	if err := d.conn.Write(buf); err != nil {
		return err
	}
	return d.writeReg(pcf8523RegControl1, c1&^pcf8523C1Stop)
}

// PCF8523Full extends PCF8523Minimal with the alarm, Timer A (countdown or
// watchdog), Timer B, programmable CLKOUT, offset calibration, battery
// backup control/status, oscillator-stop detection, software reset, and
// the Level-3 interrupt API.
//
// INT1 is shared with CLKOUT: interrupts only reach INT1 once CLKOUT is
// disabled (DisableClockOutput); the driver never does that implicitly.
// Timer B additionally drives the dedicated INT2 pin.
type PCF8523Full struct {
	*PCF8523Minimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewPCF8523Full creates a PCF8523Full. See NewPCF8523Minimal.
func NewPCF8523Full(conn connection.Connection) (*PCF8523Full, error) {
	m, err := NewPCF8523Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &PCF8523Full{PCF8523Minimal: m}, nil
}

func (d *PCF8523Full) updateTmrClkout(clearMask, setBits uint8) error {
	r, err := d.readReg(pcf8523RegTmrClkoutCtrl)
	if err != nil {
		return err
	}
	return d.writeReg(pcf8523RegTmrClkoutCtrl, (r&^clearMask)|setBits)
}

// writeControl3 writes CONTROL_3 with BSF=1 so the flag is left unchanged.
func (d *PCF8523Full) writeControl3(v uint8) error {
	return d.writeReg(pcf8523RegControl3, (v&(pcf8523C3PMMask|pcf8523C3BSIE|pcf8523C3BLIE))|pcf8523C3BSF)
}

// GetAlarm decodes the alarm registers (0x0A-0x0D); disabled fields read as
// PCF8523AlarmDisabled.
func (d *PCF8523Full) GetAlarm() (PCF8523Alarm, error) {
	raw, err := d.conn.WriteRead([]byte{pcf8523RegMinuteAlarm}, 4)
	if err != nil {
		return PCF8523Alarm{}, err
	}
	field := func(b, mask uint8, bcd bool) int {
		switch {
		case b&0x80 != 0:
			return PCF8523AlarmDisabled
		case bcd:
			return bcdToInt(b & mask)
		default:
			return int(b & mask)
		}
	}
	return PCF8523Alarm{
		Minute:  field(raw[0], 0x7F, true),
		Hour:    field(raw[1], 0x3F, true),
		Day:     field(raw[2], 0x3F, true),
		Weekday: field(raw[3], 0x07, false),
	}, nil
}

// SetAlarm writes the alarm registers (0x0A-0x0D). Fields set to
// PCF8523AlarmDisabled are ignored (AEN_x=1); an alarm with every field
// disabled never fires.
func (d *PCF8523Full) SetAlarm(a PCF8523Alarm) error {
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
	return d.conn.Write([]byte{pcf8523RegMinuteAlarm, enc(a.Minute, 0x7F), enc(a.Hour, 0x3F), enc(a.Day, 0x3F), wd})
}

// ConfigureTimerA configures and starts Timer A with a countdown value
// (0-255) ticking at sourceClock; pulsed selects a pulsed instead of a
// permanently-active interrupt.
func (d *PCF8523Full) ConfigureTimerA(mode PCF8523TimerAMode, value uint8, sourceClock PCF8523SourceClock, pulsed bool) error {
	tac := pcf8523TmrTACCountdown
	if mode == PCF8523TimerAWatchdog {
		tac = pcf8523TmrTACWatchdog
	}
	if err := d.writeReg(pcf8523RegTmrAFreqCtrl, uint8(sourceClock)); err != nil {
		return err
	}
	if err := d.writeReg(pcf8523RegTmrAReg, value); err != nil {
		return err
	}
	set := tac
	if pulsed {
		set |= pcf8523TmrTAM
	}
	return d.updateTmrClkout(pcf8523TmrTAM|pcf8523TmrTACMask, set)
}

// DisableTimerA stops Timer A (TAC=00).
func (d *PCF8523Full) DisableTimerA() error {
	return d.updateTmrClkout(pcf8523TmrTACMask, 0)
}

// ReadTimerA returns Timer A's live countdown value (not the loaded one).
func (d *PCF8523Full) ReadTimerA() (uint8, error) {
	return d.readReg(pcf8523RegTmrAReg)
}

// ConfigureTimerB configures and starts Timer B (also drives INT2).
// pulseWidthMs is snapped to the nearest of the eight hardware widths
// (46.875-218.75 ms; 46.875 is the chip default).
func (d *PCF8523Full) ConfigureTimerB(value uint8, sourceClock PCF8523SourceClock, pulseWidthMs float64, pulsed bool) error {
	tbw := 0
	for i, w := range pcf8523TBWWidthsMs {
		if math.Abs(w-pulseWidthMs) < math.Abs(pcf8523TBWWidthsMs[tbw]-pulseWidthMs) {
			tbw = i
		}
	}
	if err := d.writeReg(pcf8523RegTmrBFreqCtrl, uint8(tbw)<<4|uint8(sourceClock)); err != nil {
		return err
	}
	if err := d.writeReg(pcf8523RegTmrBReg, value); err != nil {
		return err
	}
	set := pcf8523TmrTBC
	if pulsed {
		set |= pcf8523TmrTBM
	}
	return d.updateTmrClkout(pcf8523TmrTBM|pcf8523TmrTBC, set)
}

// DisableTimerB stops Timer B (TBC=0).
func (d *PCF8523Full) DisableTimerB() error {
	return d.updateTmrClkout(pcf8523TmrTBC, 0)
}

// ReadTimerB returns Timer B's live countdown value (not the loaded one).
func (d *PCF8523Full) ReadTimerB() (uint8, error) {
	return d.readReg(pcf8523RegTmrBReg)
}

// SetClockOutput drives CLKOUT on the shared INT1/CLKOUT pin at one of
// 32768, 16384, 8192, 4096, 1024, 32 or 1 Hz (any other value disables it).
func (d *PCF8523Full) SetClockOutput(frequencyHz int) error {
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
	case 1024:
		cof = 4
	case 32:
		cof = 5
	case 1:
		cof = 6
	}
	return d.updateTmrClkout(pcf8523TmrCOFMask, cof<<3)
}

// DisableClockOutput disables CLKOUT (COF=111), freeing INT1 for interrupts.
func (d *PCF8523Full) DisableClockOutput() error {
	return d.updateTmrClkout(pcf8523TmrCOFMask, pcf8523TmrCOFMask)
}

// GetOffset reads the offset calibration register: the two's-complement
// correction (-64..+63 LSB) and its interval.
func (d *PCF8523Full) GetOffset() (int8, PCF8523OffsetMode, error) {
	r, err := d.readReg(pcf8523RegOffset)
	if err != nil {
		return 0, 0, err
	}
	offset := int8(r<<1) >> 1 // sign-extend bit 6
	mode := PCF8523OffsetEveryTwoHours
	if r&0x80 != 0 {
		mode = PCF8523OffsetEveryMinute
	}
	return offset, mode, nil
}

// SetOffset writes the offset calibration register (offset -64..+63 LSB;
// 4.34 ppm/LSB every two hours or 4.069 ppm/LSB every minute).
func (d *PCF8523Full) SetOffset(offset int8, mode PCF8523OffsetMode) error {
	v := uint8(offset) & 0x7F
	if mode == PCF8523OffsetEveryMinute {
		v |= 0x80
	}
	return d.writeReg(pcf8523RegOffset, v)
}

// ConfigureBatteryBackup selects the battery switch-over mode (PM[2:0]),
// with or without battery-low detection.
func (d *PCF8523Full) ConfigureBatteryBackup(mode PCF8523BatteryMode, lowDetection bool) error {
	pm := [3][2]uint8{{0x00, 0x04}, {0x01, 0x05}, {0x02, 0x07}}
	idx := 0
	if !lowDetection {
		idx = 1
	}
	c3, err := d.readReg(pcf8523RegControl3)
	if err != nil {
		return err
	}
	return d.writeControl3((c3 &^ pcf8523C3PMMask) | pm[mode][idx]<<5)
}

// IsBatterySwitchedOver reports BSF — a switch-over to VBAT occurred since
// it was last cleared.
func (d *PCF8523Full) IsBatterySwitchedOver() (bool, error) {
	c3, err := d.readReg(pcf8523RegControl3)
	return c3&pcf8523C3BSF != 0, err
}

// ClearBatterySwitchover clears BSF only, leaving PM and the enable bits
// unchanged.
func (d *PCF8523Full) ClearBatterySwitchover() error {
	c3, err := d.readReg(pcf8523RegControl3)
	if err != nil {
		return err
	}
	return d.writeReg(pcf8523RegControl3, c3&(pcf8523C3PMMask|pcf8523C3BSIE|pcf8523C3BLIE))
}

// IsBatteryLow reports BLF (read-only) — VBAT is below the detection
// threshold.
func (d *PCF8523Full) IsBatteryLow() (bool, error) {
	c3, err := d.readReg(pcf8523RegControl3)
	return c3&pcf8523C3BLF != 0, err
}

// OscillatorStopped reports the OS flag (bit 7 of SECONDS) — the time may
// be invalid; cleared by SetDatetime.
func (d *PCF8523Full) OscillatorStopped() (bool, error) {
	s, err := d.readReg(pcf8523RegSeconds)
	return s&pcf8523SecondsOS != 0, err
}

// SoftwareReset sends the software-reset sequence (0x58 to CONTROL_1).
// Resets all control/configuration registers to power-on defaults —
// including PM=111 (battery backup disabled) — but keeps the time/date/
// alarm/timer values.
func (d *PCF8523Full) SoftwareReset() error {
	return d.writeReg(pcf8523RegControl1, 0x58)
}

// OnInterrupt subscribes callback to be invoked with the pre-clear status
// mask (test with the PCF8523Source* constants) on each interrupt. Uses the
// connection's IntPin if wired, otherwise falls back to a polling
// goroutine. Call DisableClockOutput first if INT1 still carries CLKOUT.
func (d *PCF8523Full) OnInterrupt(callback func(uint8)) error {
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
func (d *PCF8523Full) OffInterrupt() error {
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

func (d *PCF8523Full) handleEdge() {
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

// PollInterrupt reads CONTROL_2/CONTROL_3, clears the set CTAF/CTBF/SF/AF/
// BSF flags (WTAF/BLF are read-only; enable bits untouched), and returns
// the pre-clear status mask — test with the PCF8523Source* constants.
func (d *PCF8523Full) PollInterrupt() (uint8, error) {
	raw, err := d.conn.WriteRead([]byte{pcf8523RegControl2}, 2)
	if err != nil {
		return 0, err
	}
	c2, c3 := raw[0], raw[1]
	var status uint8
	if c2&pcf8523C2SF != 0 {
		status |= PCF8523SourceSecond
	}
	if c2&(pcf8523C2CTAF|pcf8523C2WTAF) != 0 {
		status |= PCF8523SourceTimerA
	}
	if c2&pcf8523C2CTBF != 0 {
		status |= PCF8523SourceTimerB
	}
	if c2&pcf8523C2AF != 0 {
		status |= PCF8523SourceAlarm
	}
	if c3&pcf8523C3BSF != 0 {
		status |= PCF8523SourceBatterySwitch
	}
	if c3&pcf8523C3BLF != 0 {
		status |= PCF8523SourceBatteryLow
	}
	// Write 0 only to the flags seen set, 1 to the rest, so a flag that
	// sets between the read and this write is not lost.
	if c2&pcf8523C2Clearable != 0 {
		if err := d.writeReg(pcf8523RegControl2, (pcf8523C2Clearable&^c2)|(c2&pcf8523C2Enables)); err != nil {
			return 0, err
		}
	}
	if c3&pcf8523C3BSF != 0 {
		if err := d.writeReg(pcf8523RegControl3, c3&(pcf8523C3PMMask|pcf8523C3BSIE|pcf8523C3BLIE)); err != nil {
			return 0, err
		}
	}
	return status, nil
}

// EnableInterrupt enables one or more interrupt sources (bitwise OR of
// PCF8523Source*). PCF8523SourceTimerA sets WTAIE or CTAIE depending on
// Timer A's configured mode — call ConfigureTimerA first.
func (d *PCF8523Full) EnableInterrupt(source uint8) error {
	return d.setInterruptEnables(source, true)
}

// DisableInterrupt disables one or more interrupt sources (bitwise OR of
// PCF8523Source*).
func (d *PCF8523Full) DisableInterrupt(source uint8) error {
	return d.setInterruptEnables(source, false)
}

func (d *PCF8523Full) setInterruptEnables(source uint8, enable bool) error {
	apply := func(r, bits uint8) uint8 {
		if enable {
			return r | bits
		}
		return r &^ bits
	}
	if source&(PCF8523SourceSecond|PCF8523SourceAlarm) != 0 {
		var bits uint8
		if source&PCF8523SourceSecond != 0 {
			bits |= pcf8523C1SIE
		}
		if source&PCF8523SourceAlarm != 0 {
			bits |= pcf8523C1AIE
		}
		c1, err := d.readControl1()
		if err != nil {
			return err
		}
		if err := d.writeReg(pcf8523RegControl1, apply(c1, bits)); err != nil {
			return err
		}
	}
	if source&(PCF8523SourceTimerA|PCF8523SourceTimerB) != 0 {
		var bits uint8
		if source&PCF8523SourceTimerA != 0 {
			if enable {
				t, err := d.readReg(pcf8523RegTmrClkoutCtrl)
				if err != nil {
					return err
				}
				if t&pcf8523TmrTACMask == pcf8523TmrTACWatchdog {
					bits |= pcf8523C2WTAIE
				} else {
					bits |= pcf8523C2CTAIE
				}
			} else {
				bits |= pcf8523C2WTAIE | pcf8523C2CTAIE
			}
		}
		if source&PCF8523SourceTimerB != 0 {
			bits |= pcf8523C2CTBIE
		}
		c2, err := d.readReg(pcf8523RegControl2)
		if err != nil {
			return err
		}
		// Re-supply the enable bits; write 1 to every clearable flag so
		// none is cleared by accident (AND semantics).
		if err := d.writeReg(pcf8523RegControl2, pcf8523C2Clearable|apply(c2&pcf8523C2Enables, bits)); err != nil {
			return err
		}
	}
	if source&(PCF8523SourceBatterySwitch|PCF8523SourceBatteryLow) != 0 {
		var bits uint8
		if source&PCF8523SourceBatterySwitch != 0 {
			bits |= pcf8523C3BSIE
		}
		if source&PCF8523SourceBatteryLow != 0 {
			bits |= pcf8523C3BLIE
		}
		c3, err := d.readReg(pcf8523RegControl3)
		if err != nil {
			return err
		}
		if err := d.writeControl3(apply(c3, bits)); err != nil {
			return err
		}
	}
	return nil
}
