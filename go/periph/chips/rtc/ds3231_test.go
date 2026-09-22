package rtc

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. Backed by a FIFO queue: preload responses with
// queueRead; each WriteRead/Read call pops the next one.
type mockConnection struct {
	writes    [][]byte
	readQueue [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{}
}

func (m *mockConnection) queueRead(data []byte) {
	m.readQueue = append(m.readQueue, data)
}

func (m *mockConnection) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) {
	return m.pop(n), nil
}

func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return m.pop(n), nil
}

func (m *mockConnection) pop(n int) []byte {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out
	}
	return make([]byte, n)
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func (m *mockConnection) lastWrite() []byte {
	if len(m.writes) == 0 {
		return nil
	}
	return m.writes[len(m.writes)-1]
}

func (m *mockConnection) writesContain(expected []byte) bool {
	for _, w := range m.writes {
		if bytes.Equal(w, expected) {
			return true
		}
	}
	return false
}

func TestDS3231MinimalConstruct(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // CONTROL register presence check

	if _, err := NewDS3231Minimal(conn); err != nil {
		t.Fatalf("NewDS3231Minimal: %v", err)
	}
}

func TestDS3231GetSetDatetime(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // presence check
	sensor, err := NewDS3231Minimal(conn)
	if err != nil {
		t.Fatalf("NewDS3231Minimal: %v", err)
	}

	// 2026-09-22 (Tuesday=2), 14:30:05 BCD.
	conn.queueRead([]byte{0x05, 0x30, 0x14, 0x02, 0x22, 0x09, 0x26})
	year, month, day, weekday, hour, minute, second, err := sensor.GetDatetime()
	if err != nil {
		t.Fatalf("GetDatetime: %v", err)
	}
	if year != 2026 || month != 9 || day != 22 || weekday != 2 || hour != 14 || minute != 30 || second != 5 {
		t.Errorf("GetDatetime: got y=%d m=%d d=%d wd=%d h=%d m=%d s=%d",
			year, month, day, weekday, hour, minute, second)
	}

	conn.queueRead([]byte{0x00}) // status read inside SetDatetime
	if err := sensor.SetDatetime(2026, 9, 22, 2, 14, 30, 5); err != nil {
		t.Fatalf("SetDatetime: %v", err)
	}
	if !conn.writesContain([]byte{ds3231RegSeconds, 0x05, 0x30, 0x14, 0x02, 0x22, 0x09, 0x26}) {
		t.Errorf("SetDatetime: clock/calendar write not found: %v", conn.writes)
	}
}

func TestDS3231ReadTemperature(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // presence check
	sensor, err := NewDS3231Minimal(conn)
	if err != nil {
		t.Fatalf("NewDS3231Minimal: %v", err)
	}

	// 25.25 degC: MSB=25 (0x19), LSB fractional bits 7:6 = 01 (0.25).
	conn.queueRead([]byte{0x19, 0x40})
	temp, err := sensor.ReadTemperature()
	if err != nil {
		t.Fatalf("ReadTemperature: %v", err)
	}
	if temp != 25.25 {
		t.Errorf("ReadTemperature: got %v, want 25.25", temp)
	}
}

func TestDS3231Alarm1RoundTrip(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // presence check
	full, err := NewDS3231Full(conn)
	if err != nil {
		t.Fatalf("NewDS3231Full: %v", err)
	}

	if err := full.SetAlarm1(0, 30, 8, 0, DS3231Alarm1MatchHoursMinutesSeconds); err != nil {
		t.Fatalf("SetAlarm1: %v", err)
	}
	// mode=MatchHoursMinutesSeconds -> only A1M4 (day/date mask bit) set.
	if !conn.writesContain([]byte{ds3231RegAlarm1Seconds, 0x00, 0x30, 0x08, 0x80}) {
		t.Errorf("SetAlarm1: unexpected write: %v", conn.writes)
	}

	conn.queueRead([]byte{0x00, 0x30, 0x08, 0x80})
	second, minute, hour, dayOrDate, mode, err := full.GetAlarm1()
	if err != nil {
		t.Fatalf("GetAlarm1: %v", err)
	}
	if second != 0 || minute != 30 || hour != 8 || dayOrDate != 0 || mode != DS3231Alarm1MatchHoursMinutesSeconds {
		t.Errorf("GetAlarm1: got s=%d m=%d h=%d d=%d mode=%d", second, minute, hour, dayOrDate, mode)
	}
}

func TestDS3231Alarm2DayOfWeek(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // presence check
	full, err := NewDS3231Full(conn)
	if err != nil {
		t.Fatalf("NewDS3231Full: %v", err)
	}

	if err := full.SetAlarm2(0, 9, 1, DS3231Alarm2MatchDayHoursMinutes); err != nil {
		t.Fatalf("SetAlarm2: %v", err)
	}
	// dayOrDate=1 (Monday) BCD=0x01, DY/DT bit set (0x40), mask bits clear.
	if !conn.writesContain([]byte{ds3231RegAlarm2Minutes, 0x00, 0x09, 0x41}) {
		t.Errorf("SetAlarm2: unexpected write: %v", conn.writes)
	}
}

func TestDS3231EnableSquareWave(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // presence check
	full, err := NewDS3231Full(conn)
	if err != nil {
		t.Fatalf("NewDS3231Full: %v", err)
	}

	conn.queueRead([]byte{0x1C}) // CONTROL power-on default
	if err := full.EnableSquareWave(1, true); err != nil {
		t.Fatalf("EnableSquareWave: %v", err)
	}
	last := conn.lastWrite()
	if last[1]&ds3231CtrlINTCN != 0 {
		t.Errorf("EnableSquareWave: INTCN should be clear, got 0x%02X", last[1])
	}
	if last[1]&ds3231CtrlBBSQW == 0 {
		t.Errorf("EnableSquareWave: BBSQW should be set, got 0x%02X", last[1])
	}
}

func TestDS3231PollInterruptPreservesOtherBits(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // presence check
	full, err := NewDS3231Full(conn)
	if err != nil {
		t.Fatalf("NewDS3231Full: %v", err)
	}

	// OSF=1, EN32kHz=1, BSY=0, A2F=1, A1F=1 -> 0x8B
	conn.queueRead([]byte{0x8B})
	status, err := full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status != 0x8B {
		t.Errorf("PollInterrupt: got 0x%02X, want 0x8B", status)
	}
	if status&DS3231SourceAlarm1 == 0 || status&DS3231SourceAlarm2 == 0 {
		t.Errorf("PollInterrupt: expected both alarm sources set in 0x%02X", status)
	}
	last := conn.lastWrite()
	if last[1] != 0x88 {
		t.Errorf("PollInterrupt: expected A1F/A2F cleared, OSF/EN32kHz preserved -> 0x88, got 0x%02X", last[1])
	}
}

func TestDS3231EnableDisableInterrupt(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // presence check
	full, err := NewDS3231Full(conn)
	if err != nil {
		t.Fatalf("NewDS3231Full: %v", err)
	}

	conn.queueRead([]byte{0x1C})
	if err := full.EnableInterrupt(DS3231SourceAlarm1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	last := conn.lastWrite()
	if last[1]&ds3231CtrlA1IE == 0 || last[1]&ds3231CtrlINTCN == 0 {
		t.Errorf("EnableInterrupt: expected A1IE and INTCN set, got 0x%02X", last[1])
	}

	conn.queueRead([]byte{last[1]})
	if err := full.DisableInterrupt(DS3231SourceAlarm1); err != nil {
		t.Fatalf("DisableInterrupt: %v", err)
	}
	last = conn.lastWrite()
	if last[1]&ds3231CtrlA1IE != 0 {
		t.Errorf("DisableInterrupt: expected A1IE clear, got 0x%02X", last[1])
	}

	if err := full.EnableInterrupt(0xFF); err == nil {
		t.Errorf("EnableInterrupt: expected error for unknown source")
	}
}
