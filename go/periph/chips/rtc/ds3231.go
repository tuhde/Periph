// Package rtc contains drivers for real-time clock chips (DS3231, etc.)
// over I²C.
package rtc

import (
	"fmt"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// DS3231 register addresses (0x00-0x12).
const (
	ds3231RegSeconds       uint8 = 0x00
	ds3231RegMinutes       uint8 = 0x01
	ds3231RegHours         uint8 = 0x02
	ds3231RegDay           uint8 = 0x03
	ds3231RegDate          uint8 = 0x04
	ds3231RegMonthCentury  uint8 = 0x05
	ds3231RegYear          uint8 = 0x06
	ds3231RegAlarm1Seconds uint8 = 0x07
	ds3231RegAlarm1Minutes uint8 = 0x08
	ds3231RegAlarm1Hours   uint8 = 0x09
	ds3231RegAlarm1DayDate uint8 = 0x0A
	ds3231RegAlarm2Minutes uint8 = 0x0B
	ds3231RegAlarm2Hours   uint8 = 0x0C
	ds3231RegAlarm2DayDate uint8 = 0x0D
	ds3231RegControl       uint8 = 0x0E
	ds3231RegControlStatus uint8 = 0x0F
	ds3231RegAgingOffset   uint8 = 0x10
	ds3231RegTempMSB       uint8 = 0x11
	ds3231RegTempLSB       uint8 = 0x12
)

// CONTROL register bits (0x0E).
const (
	ds3231CtrlEOSC  uint8 = 0x80
	ds3231CtrlBBSQW uint8 = 0x40
	ds3231CtrlCONV  uint8 = 0x20
	ds3231CtrlRS2   uint8 = 0x10
	ds3231CtrlRS1   uint8 = 0x08
	ds3231CtrlINTCN uint8 = 0x04
	ds3231CtrlA2IE  uint8 = 0x02
	ds3231CtrlA1IE  uint8 = 0x01
)

// CONTROL_STATUS register bits (0x0F).
const (
	ds3231StatusOSF     uint8 = 0x80
	ds3231StatusEN32kHz uint8 = 0x08
	ds3231StatusBSY     uint8 = 0x04
)

// DS3231SourceAlarm1 and DS3231SourceAlarm2 identify the two interrupt
// sources for OnInterrupt/PollInterrupt/EnableInterrupt/DisableInterrupt.
// Their values match A1F/A2F (CONTROL_STATUS) and A1IE/A2IE (CONTROL).
const (
	DS3231SourceAlarm1 uint8 = 0x01
	DS3231SourceAlarm2 uint8 = 0x02
)

// DS3231Alarm1Mode selects Alarm 1's match granularity (Table 2 of the
// datasheet). For MatchDateHoursMinutesSeconds/MatchDayHoursMinutesSeconds,
// the dayOrDate parameter of SetAlarm1 is a day-of-month or day-of-week
// (ISO 8601, 1=Monday..7=Sunday) respectively; it is ignored for every
// other mode.
type DS3231Alarm1Mode uint8

// DS3231Alarm1Mode values, in datasheet Table 2 order.
const (
	DS3231Alarm1EverySecond DS3231Alarm1Mode = iota
	DS3231Alarm1MatchSeconds
	DS3231Alarm1MatchMinutesSeconds
	DS3231Alarm1MatchHoursMinutesSeconds
	DS3231Alarm1MatchDateHoursMinutesSeconds
	DS3231Alarm1MatchDayHoursMinutesSeconds
)

// DS3231Alarm2Mode selects Alarm 2's match granularity (Table 2 of the
// datasheet). Alarm 2 has no seconds field — it always matches at :00
// seconds of the matched minute.
type DS3231Alarm2Mode uint8

// DS3231Alarm2Mode values, in datasheet Table 2 order.
const (
	DS3231Alarm2EveryMinute DS3231Alarm2Mode = iota
	DS3231Alarm2MatchMinutes
	DS3231Alarm2MatchHoursMinutes
	DS3231Alarm2MatchDateHoursMinutes
	DS3231Alarm2MatchDayHoursMinutes
)

func bcdToInt(b uint8) int { return int(b>>4)*10 + int(b&0x0F) }

func intToBCD(v int) uint8 { return uint8((v/10)<<4 | (v % 10)) }

func alarm1MaskBits(mode DS3231Alarm1Mode) (m1, m2, m3, m4 bool) {
	switch mode {
	case DS3231Alarm1EverySecond:
		return true, true, true, true
	case DS3231Alarm1MatchSeconds:
		return false, true, true, true
	case DS3231Alarm1MatchMinutesSeconds:
		return false, false, true, true
	case DS3231Alarm1MatchHoursMinutesSeconds:
		return false, false, false, true
	default: // MatchDateHoursMinutesSeconds / MatchDayHoursMinutesSeconds
		return false, false, false, false
	}
}

func decodeAlarm1Mode(m1, m2, m3, m4, dayOfWeek bool) DS3231Alarm1Mode {
	switch {
	case m1 && m2 && m3 && m4:
		return DS3231Alarm1EverySecond
	case !m1 && m2 && m3 && m4:
		return DS3231Alarm1MatchSeconds
	case !m1 && !m2 && m3 && m4:
		return DS3231Alarm1MatchMinutesSeconds
	case !m1 && !m2 && !m3 && m4:
		return DS3231Alarm1MatchHoursMinutesSeconds
	case dayOfWeek:
		return DS3231Alarm1MatchDayHoursMinutesSeconds
	default:
		return DS3231Alarm1MatchDateHoursMinutesSeconds
	}
}

func alarm2MaskBits(mode DS3231Alarm2Mode) (m2, m3, m4 bool) {
	switch mode {
	case DS3231Alarm2EveryMinute:
		return true, true, true
	case DS3231Alarm2MatchMinutes:
		return false, true, true
	case DS3231Alarm2MatchHoursMinutes:
		return false, false, true
	default: // MatchDateHoursMinutes / MatchDayHoursMinutes
		return false, false, false
	}
}

func decodeAlarm2Mode(m2, m3, m4, dayOfWeek bool) DS3231Alarm2Mode {
	switch {
	case m2 && m3 && m4:
		return DS3231Alarm2EveryMinute
	case !m2 && m3 && m4:
		return DS3231Alarm2MatchMinutes
	case !m2 && !m3 && m4:
		return DS3231Alarm2MatchHoursMinutes
	case dayOfWeek:
		return DS3231Alarm2MatchDayHoursMinutes
	default:
		return DS3231Alarm2MatchDateHoursMinutes
	}
}

// DS3231Minimal is the DS3231 extremely accurate I²C RTC/TCXO/crystal —
// minimal interface.
//
// Reads and sets the calendar clock, and reads the free on-chip
// temperature sensor, with no configuration required beyond the
// connection. Communicates over I²C at up to 400 kHz (Fast mode) at the
// fixed address 0x68 — the caller's Connection must already be bound to
// that address.
//
// This driver always operates the HOURS registers in 24-hour mode; the
// chip's native 12-hour/AM-PM encoding is never written or exposed. The
// DAY register is a free-running 1-7 counter with no hardware-enforced
// meaning — this driver defines 1=Monday..7=Sunday (ISO 8601) as its
// convention for weekday, in both GetDatetime/SetDatetime and the alarm
// day-of-week fields.
type DS3231Minimal struct {
	conn connection.Connection
}

// NewDS3231Minimal creates a DS3231Minimal and confirms the device answers
// on the bus (the DS3231 has no WHO_AM_I register, so this is a plain
// register read). No register writes are made — time/date register
// contents are undefined until SetDatetime is called.
func NewDS3231Minimal(conn connection.Connection) (*DS3231Minimal, error) {
	d := &DS3231Minimal{conn: conn}
	if _, err := d.readReg(ds3231RegControl); err != nil {
		return nil, fmt.Errorf("DS3231: device not responding: %w", err)
	}
	return d, nil
}

func (d *DS3231Minimal) readReg(reg uint8) (uint8, error) {
	b, err := d.conn.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *DS3231Minimal) writeReg(reg, val uint8) error {
	return d.conn.Write([]byte{reg, val})
}

func (d *DS3231Minimal) readBurst(reg uint8, n int) ([]byte, error) {
	return d.conn.WriteRead([]byte{reg}, n)
}

// GetDatetime reads the calendar clock. hour is always 0-23; weekday is
// 1-7 (ISO 8601, 1=Monday..7=Sunday); year is 2000-2099.
func (d *DS3231Minimal) GetDatetime() (year, month, day, weekday, hour, minute, second int, err error) {
	raw, err := d.readBurst(ds3231RegSeconds, 7)
	if err != nil {
		return
	}
	second = bcdToInt(raw[0] & 0x7F)
	minute = bcdToInt(raw[1] & 0x7F)
	hour = bcdToInt(raw[2] & 0x3F)
	weekday = int(raw[3] & 0x07)
	day = bcdToInt(raw[4] & 0x3F)
	month = bcdToInt(raw[5] & 0x1F)
	year = 2000 + bcdToInt(raw[6])
	return
}

// SetDatetime writes all seven clock/calendar registers, forces 24-hour
// mode, and clears OSF (the time is now known-good). year is the full
// year (e.g. 2026); only 2000-2099 is representable.
func (d *DS3231Minimal) SetDatetime(year, month, day, weekday, hour, minute, second int) error {
	buf := []byte{
		ds3231RegSeconds,
		intToBCD(second),
		intToBCD(minute),
		intToBCD(hour), // bit 6 left clear -> 24-hour mode
		uint8(weekday),
		intToBCD(day),
		intToBCD(month),
		intToBCD(year - 2000),
	}
	if err := d.conn.Write(buf); err != nil {
		return err
	}
	status, err := d.readReg(ds3231RegControlStatus)
	if err != nil {
		return err
	}
	return d.writeReg(ds3231RegControlStatus, status&^ds3231StatusOSF)
}

// ReadTemperature reads the last completed conversion from TEMP_MSB/LSB in
// degrees Celsius. No wait is performed — the chip converts autonomously
// every 64s and on power-up, so the value may be up to 64s stale. Use
// (*DS3231Full).ForceTemperatureConversion for an up-to-date reading.
func (d *DS3231Minimal) ReadTemperature() (float32, error) {
	raw, err := d.readBurst(ds3231RegTempMSB, 2)
	if err != nil {
		return 0, err
	}
	msb := int8(raw[0])
	frac := float32(raw[1]>>6) * 0.25
	return float32(msb) + frac, nil
}

// DS3231Full extends DS3231Minimal with alarms, square-wave/32kHz output
// control, oscillator/battery management, forced temperature conversion,
// aging-offset trim, and the Level-2 selectable-source interrupt API
// (DS3231SourceAlarm1 / DS3231SourceAlarm2).
type DS3231Full struct {
	*DS3231Minimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewDS3231Full creates a DS3231Full.
func NewDS3231Full(conn connection.Connection) (*DS3231Full, error) {
	m, err := NewDS3231Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &DS3231Full{DS3231Minimal: m}, nil
}

// GetAlarm1 decodes the Alarm 1 registers (0x07-0x0A).
func (d *DS3231Full) GetAlarm1() (second, minute, hour, dayOrDate int, mode DS3231Alarm1Mode, err error) {
	raw, err := d.readBurst(ds3231RegAlarm1Seconds, 4)
	if err != nil {
		return
	}
	second = bcdToInt(raw[0] & 0x7F)
	minute = bcdToInt(raw[1] & 0x7F)
	hour = bcdToInt(raw[2] & 0x3F)
	dayOrDate = bcdToInt(raw[3] & 0x3F)
	mode = decodeAlarm1Mode(raw[0]&0x80 != 0, raw[1]&0x80 != 0, raw[2]&0x80 != 0, raw[3]&0x80 != 0, raw[3]&0x40 != 0)
	return
}

// SetAlarm1 writes the Alarm 1 registers (0x07-0x0A). mode selects one of
// the 6 rows of the datasheet's Alarm 1 mask table; dayOrDate is only used
// by MatchDateHoursMinutesSeconds/MatchDayHoursMinutesSeconds.
func (d *DS3231Full) SetAlarm1(second, minute, hour, dayOrDate int, mode DS3231Alarm1Mode) error {
	m1, m2, m3, m4 := alarm1MaskBits(mode)
	dayOfWeek := mode == DS3231Alarm1MatchDayHoursMinutesSeconds

	secByte := intToBCD(second)
	if m1 {
		secByte |= 0x80
	}
	minByte := intToBCD(minute)
	if m2 {
		minByte |= 0x80
	}
	hourByte := intToBCD(hour)
	if m3 {
		hourByte |= 0x80
	}
	dayByte := intToBCD(dayOrDate)
	if m4 {
		dayByte |= 0x80
	}
	if dayOfWeek {
		dayByte |= 0x40
	}
	return d.conn.Write([]byte{ds3231RegAlarm1Seconds, secByte, minByte, hourByte, dayByte})
}

// GetAlarm2 decodes the Alarm 2 registers (0x0B-0x0D).
func (d *DS3231Full) GetAlarm2() (minute, hour, dayOrDate int, mode DS3231Alarm2Mode, err error) {
	raw, err := d.readBurst(ds3231RegAlarm2Minutes, 3)
	if err != nil {
		return
	}
	minute = bcdToInt(raw[0] & 0x7F)
	hour = bcdToInt(raw[1] & 0x3F)
	dayOrDate = bcdToInt(raw[2] & 0x3F)
	mode = decodeAlarm2Mode(raw[0]&0x80 != 0, raw[1]&0x80 != 0, raw[2]&0x80 != 0, raw[2]&0x40 != 0)
	return
}

// SetAlarm2 writes the Alarm 2 registers (0x0B-0x0D). mode selects one of
// the 5 rows of the datasheet's Alarm 2 mask table; dayOrDate is only used
// by MatchDateHoursMinutes/MatchDayHoursMinutes.
func (d *DS3231Full) SetAlarm2(minute, hour, dayOrDate int, mode DS3231Alarm2Mode) error {
	m2, m3, m4 := alarm2MaskBits(mode)
	dayOfWeek := mode == DS3231Alarm2MatchDayHoursMinutes

	minByte := intToBCD(minute)
	if m2 {
		minByte |= 0x80
	}
	hourByte := intToBCD(hour)
	if m3 {
		hourByte |= 0x80
	}
	dayByte := intToBCD(dayOrDate)
	if m4 {
		dayByte |= 0x80
	}
	if dayOfWeek {
		dayByte |= 0x40
	}
	return d.conn.Write([]byte{ds3231RegAlarm2Minutes, minByte, hourByte, dayByte})
}

// EnableSquareWave sets INTCN=0, configures RS2:RS1 for rateHz (1, 1024,
// 4096, or 8192), and sets BBSQW=batteryBacked. Mutually exclusive with
// alarm interrupts — overrides whichever of EnableInterrupt/
// EnableSquareWave/OnInterrupt/DisableSquareWave happened last, since they
// share the INTCN bit.
func (d *DS3231Full) EnableSquareWave(rateHz int, batteryBacked bool) error {
	var rs uint8
	switch rateHz {
	case 1:
		rs = 0
	case 1024:
		rs = ds3231CtrlRS1
	case 4096:
		rs = ds3231CtrlRS2
	case 8192:
		rs = ds3231CtrlRS2 | ds3231CtrlRS1
	default:
		return fmt.Errorf("DS3231: unsupported square-wave rate %d Hz", rateHz)
	}
	ctrl, err := d.readReg(ds3231RegControl)
	if err != nil {
		return err
	}
	ctrl &^= ds3231CtrlINTCN | ds3231CtrlRS2 | ds3231CtrlRS1 | ds3231CtrlBBSQW
	ctrl |= rs
	if batteryBacked {
		ctrl |= ds3231CtrlBBSQW
	}
	return d.writeReg(ds3231RegControl, ctrl)
}

// DisableSquareWave sets INTCN=1, returning INT/SQW to interrupt mode.
func (d *DS3231Full) DisableSquareWave() error {
	ctrl, err := d.readReg(ds3231RegControl)
	if err != nil {
		return err
	}
	return d.writeReg(ds3231RegControl, ctrl|ds3231CtrlINTCN)
}

// Is32kHzEnabled reads EN32kHz.
func (d *DS3231Full) Is32kHzEnabled() (bool, error) {
	s, err := d.readReg(ds3231RegControlStatus)
	if err != nil {
		return false, err
	}
	return s&ds3231StatusEN32kHz != 0, nil
}

// Enable32kHzOutput sets EN32kHz=1.
func (d *DS3231Full) Enable32kHzOutput() error {
	s, err := d.readReg(ds3231RegControlStatus)
	if err != nil {
		return err
	}
	return d.writeReg(ds3231RegControlStatus, s|ds3231StatusEN32kHz)
}

// Disable32kHzOutput clears EN32kHz.
func (d *DS3231Full) Disable32kHzOutput() error {
	s, err := d.readReg(ds3231RegControlStatus)
	if err != nil {
		return err
	}
	return d.writeReg(ds3231RegControlStatus, s&^ds3231StatusEN32kHz)
}

// OscillatorStopped reads OSF. true means timekeeping data may be invalid
// since the last check (first power-up, insufficient VCC/VBAT, EOSC
// disabled on battery, or external disturbance).
func (d *DS3231Full) OscillatorStopped() (bool, error) {
	s, err := d.readReg(ds3231RegControlStatus)
	if err != nil {
		return false, err
	}
	return s&ds3231StatusOSF != 0, nil
}

// ClearOscillatorStopped writes 0 to OSF only, preserving EN32kHz.
func (d *DS3231Full) ClearOscillatorStopped() error {
	s, err := d.readReg(ds3231RegControlStatus)
	if err != nil {
		return err
	}
	return d.writeReg(ds3231RegControlStatus, s&^ds3231StatusOSF)
}

// EnableBatteryOscillator clears EOSC — the oscillator keeps running on
// VBAT (power-on default).
func (d *DS3231Full) EnableBatteryOscillator() error {
	c, err := d.readReg(ds3231RegControl)
	if err != nil {
		return err
	}
	return d.writeReg(ds3231RegControl, c&^ds3231CtrlEOSC)
}

// DisableBatteryOscillator sets EOSC — the oscillator stops when switched
// to VBAT, saving battery current.
func (d *DS3231Full) DisableBatteryOscillator() error {
	c, err := d.readReg(ds3231RegControl)
	if err != nil {
		return err
	}
	return d.writeReg(ds3231RegControl, c|ds3231CtrlEOSC)
}

// ForceTemperatureConversion sets CONV and polls BSY until clear (max
// 200ms per the datasheet's AC Electrical Characteristics), so the very
// next ReadTemperature call returns a fresh value instead of a cached one.
func (d *DS3231Full) ForceTemperatureConversion() error {
	c, err := d.readReg(ds3231RegControl)
	if err != nil {
		return err
	}
	if err := d.writeReg(ds3231RegControl, c|ds3231CtrlCONV); err != nil {
		return err
	}
	deadline := time.Now().Add(200 * time.Millisecond)
	for {
		s, err := d.readReg(ds3231RegControlStatus)
		if err != nil {
			return err
		}
		if s&ds3231StatusBSY == 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("DS3231: temperature conversion did not complete within 200ms")
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// GetAgingOffset reads the raw signed 8-bit oscillator trim code from
// AGING_OFFSET. This is a two's-complement trim code, not a value with a
// fixed physical scale — its ppm-per-LSB effect is temperature-dependent.
func (d *DS3231Full) GetAgingOffset() (int8, error) {
	v, err := d.readReg(ds3231RegAgingOffset)
	if err != nil {
		return 0, err
	}
	return int8(v), nil
}

// SetAgingOffset writes AGING_OFFSET.
func (d *DS3231Full) SetAgingOffset(offset int8) error {
	return d.writeReg(ds3231RegAgingOffset, uint8(offset))
}

// OnInterrupt subscribes callback to be invoked with the pre-clear
// CONTROL_STATUS byte on each alarm match; also sets INTCN=1 so INT/SQW
// carries alarm interrupts instead of the square wave. Uses the
// connection's IntPin if wired, otherwise falls back to a polling
// goroutine.
func (d *DS3231Full) OnInterrupt(callback func(uint8)) error {
	c, err := d.readReg(ds3231RegControl)
	if err != nil {
		return err
	}
	if err := d.writeReg(ds3231RegControl, c|ds3231CtrlINTCN); err != nil {
		return err
	}

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
func (d *DS3231Full) OffInterrupt() error {
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

// PollInterrupt reads CONTROL_STATUS, clears A1F/A2F (leaving
// OSF/EN32kHz/BSY untouched), and returns the pre-clear byte. Mask the
// result against DS3231SourceAlarm1/DS3231SourceAlarm2 to test each
// source. A1F/A2F latch on a match regardless of INTCN/A1IE/A2IE, so this
// works even when INT/SQW is wired as a square wave or not wired at all.
func (d *DS3231Full) PollInterrupt() (uint8, error) {
	s, err := d.readReg(ds3231RegControlStatus)
	if err != nil {
		return 0, err
	}
	clear := s &^ (DS3231SourceAlarm1 | DS3231SourceAlarm2)
	if clear != s {
		if err := d.writeReg(ds3231RegControlStatus, clear); err != nil {
			return 0, err
		}
	}
	return s, nil
}

func (d *DS3231Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil {
		return
	}
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}

// EnableInterrupt sets A1IE or A2IE for the given source (DS3231SourceAlarm1
// / DS3231SourceAlarm2), and sets INTCN=1.
func (d *DS3231Full) EnableInterrupt(source uint8) error {
	c, err := d.readReg(ds3231RegControl)
	if err != nil {
		return err
	}
	c |= ds3231CtrlINTCN
	switch source {
	case DS3231SourceAlarm1:
		c |= ds3231CtrlA1IE
	case DS3231SourceAlarm2:
		c |= ds3231CtrlA2IE
	default:
		return fmt.Errorf("DS3231: unknown interrupt source 0x%02X", source)
	}
	return d.writeReg(ds3231RegControl, c)
}

// DisableInterrupt clears A1IE or A2IE for the given source.
func (d *DS3231Full) DisableInterrupt(source uint8) error {
	c, err := d.readReg(ds3231RegControl)
	if err != nil {
		return err
	}
	switch source {
	case DS3231SourceAlarm1:
		c &^= ds3231CtrlA1IE
	case DS3231SourceAlarm2:
		c &^= ds3231CtrlA2IE
	default:
		return fmt.Errorf("DS3231: unknown interrupt source 0x%02X", source)
	}
	return d.writeReg(ds3231RegControl, c)
}
