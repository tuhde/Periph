package comms

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus.
//
// RDA5807M never issues a register-pointer write (see rda5807m.go's package
// doc), so only the plain Read/Write path matters here: every Write is
// logged to writes for assertions, and Read pops the next queued response
// (falling back to n zero bytes if the queue is empty).
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
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out, nil
	}
	return make([]byte, n), nil
}

func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	return make([]byte, n), nil
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func regsBytes(regs [6]uint16) []byte {
	buf := make([]byte, 12)
	for i := 0; i < 6; i++ {
		buf[i*2] = byte(regs[i] >> 8)
		buf[i*2+1] = byte(regs[i] & 0xFF)
	}
	return buf
}

func statusBytes(words ...uint16) []byte {
	buf := make([]byte, len(words)*2)
	for i, w := range words {
		buf[i*2] = byte(w >> 8)
		buf[i*2+1] = byte(w & 0xFF)
	}
	return buf
}

// newSensor constructs a fresh RDA5807MFull with a queued STC-set status so
// the blocking waitStc() inside the constructor resolves on its first poll,
// and returns (conn, sensor, regs, band, space, east50) where regs is the
// expected post-init shadow register array (TUNE already cleared, mirroring
// what the driver does once it observes STC).
func newSensor(t *testing.T, frequencyMhz float64, volume uint8) (*mockConnection, *RDA5807MFull, [6]uint16, uint8, uint8, bool) {
	t.Helper()
	conn := newMockConnection()
	conn.queueRead(statusBytes(rdaSTC))
	band, space, east50 := BandWorld, Space100K, false
	chan0 := freqToChan(band, space, east50, frequencyMhz)
	regs := [6]uint16{
		rdaDHIZ | rdaDMUTE | rdaSKMODE | rdaNEW_METHOD | rdaENABLE,
		(chan0 << 6) | rdaTUNE | (uint16(band) << 2) | uint16(space),
		rdaSOFTMUTE | rdaDE,
		rdaINT_MODE | (8 << 8) | uint16(volume&0x0F),
		0x0000,
		(16 << 10) | rdaBAND_65M_50M | 0x0002,
	}
	sensor, err := NewRDA5807MFull(conn, frequencyMhz, volume)
	if err != nil {
		t.Fatalf("NewRDA5807MFull: %v", err)
	}
	regs[1] &^= rdaTUNE
	return conn, sensor, regs, band, space, east50
}

func lastWrite(writes [][]byte) []byte {
	if len(writes) == 0 {
		return nil
	}
	return writes[len(writes)-1]
}

func TestRDA5807MInitWritesRegs(t *testing.T) {
	conn, _, regs, _, _, _ := newSensor(t, 100.0, 8)
	expected := regs
	expected[1] |= rdaTUNE // write happened before the shadow TUNE bit was cleared
	if string(conn.writes[0]) != string(regsBytes(expected)) {
		t.Errorf("init write = % X, want % X", conn.writes[0], regsBytes(expected))
	}
}

func TestRDA5807MFrequency(t *testing.T) {
	conn, sensor, _, band, space, east50 := newSensor(t, 100.0, 8)
	conn.queueRead(statusBytes(250))
	freq, err := sensor.Frequency()
	if err != nil {
		t.Fatalf("Frequency: %v", err)
	}
	if want := chanToFreq(band, space, east50, 250); freq != want {
		t.Errorf("Frequency() = %v, want %v", freq, want)
	}
}

func TestRDA5807MSetFrequency(t *testing.T) {
	conn, sensor, regs, band, space, east50 := newSensor(t, 100.0, 8)
	conn.queueRead(statusBytes(rdaSTC))
	if err := sensor.SetFrequency(103.5); err != nil {
		t.Fatalf("SetFrequency: %v", err)
	}
	chan1 := freqToChan(band, space, east50, 103.5)
	regs[1] = (chan1 << 6) | rdaTUNE | (uint16(band) << 2) | uint16(space)
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("SetFrequency write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}
}

func TestRDA5807MSetVolume(t *testing.T) {
	conn, sensor, regs, _, _, _ := newSensor(t, 100.0, 8)
	if err := sensor.SetVolume(5); err != nil {
		t.Fatalf("SetVolume: %v", err)
	}
	regs[3] = (regs[3] &^ 0x000F) | uint16(5&0x0F)
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("SetVolume write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}
}

func TestRDA5807MMute(t *testing.T) {
	conn, sensor, regs, _, _, _ := newSensor(t, 100.0, 8)
	if err := sensor.Mute(true); err != nil {
		t.Fatalf("Mute(true): %v", err)
	}
	regs[0] &^= rdaDMUTE
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("Mute(true) write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}
	if err := sensor.Mute(false); err != nil {
		t.Fatalf("Mute(false): %v", err)
	}
	regs[0] |= rdaDMUTE
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("Mute(false) write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}
}

func TestRDA5807MSeekFound(t *testing.T) {
	conn, sensor, regs, band, space, east50 := newSensor(t, 100.0, 8)
	conn.queueRead(statusBytes(rdaSTC | 300))
	result, err := sensor.Seek(true)
	if err != nil {
		t.Fatalf("Seek: %v", err)
	}
	regs[0] |= rdaSEEKUP
	regs[0] |= rdaSEEK
	firstWrite := regsBytes(regs)
	regs[0] &^= rdaSEEK
	secondWrite := regsBytes(regs)
	n := len(conn.writes)
	if n < 2 || string(conn.writes[n-2]) != string(firstWrite) || string(conn.writes[n-1]) != string(secondWrite) {
		t.Errorf("Seek writes = %v, want [% X, % X]", conn.writes, firstWrite, secondWrite)
	}
	if result == nil || *result != chanToFreq(band, space, east50, 300) {
		t.Errorf("Seek(true) = %v, want %v", result, chanToFreq(band, space, east50, 300))
	}
}

func TestRDA5807MSeekFails(t *testing.T) {
	conn, sensor, _, _, _, _ := newSensor(t, 100.0, 8)
	conn.queueRead(statusBytes(rdaSTC | rdaSF))
	result, err := sensor.Seek(false)
	if err != nil {
		t.Fatalf("Seek: %v", err)
	}
	if result != nil {
		t.Errorf("Seek(false) = %v, want nil (SF set)", *result)
	}
}

func TestRDA5807MConfigureRetunes(t *testing.T) {
	conn, sensor, regs, _, _, east50 := newSensor(t, 100.0, 8)
	conn.queueRead(statusBytes(500)) // Configure() reads current Frequency() first
	currentFreq := chanToFreq(BandWorld, Space100K, east50, 500)
	conn.queueRead(statusBytes(rdaSTC)) // for the resulting retune's waitStc

	band, space := BandUSEurope, Space50K
	deEmphasis, seekMode, afcDisable := false, false, true
	seekThreshold, clkMode := uint8(10), uint8(3)
	if err := sensor.Configure(&band, &space, &deEmphasis, &seekMode, &afcDisable, nil, &seekThreshold, &clkMode); err != nil {
		t.Fatalf("Configure: %v", err)
	}

	regs[2] &^= rdaDE
	regs[2] |= rdaAFCD
	regs[3] = (regs[3] &^ 0x0F00) | (uint16(10&0x0F) << 8)
	regs[0] &^= rdaSKMODE
	regs[0] = (regs[0] &^ 0x0070) | (uint16(3&0x07) << 4)
	chan2 := freqToChan(BandUSEurope, Space50K, east50, currentFreq)
	regs[1] = (chan2 << 6) | rdaTUNE | (uint16(BandUSEurope) << 2) | uint16(Space50K)
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("Configure (retune) write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}
}

func TestRDA5807MConfigureNoRetune(t *testing.T) {
	conn, sensor, regs, _, _, _ := newSensor(t, 100.0, 8)
	conn.queueRead(statusBytes(0)) // Configure() still reads Frequency() first
	seekThreshold := uint8(4)
	if err := sensor.Configure(nil, nil, nil, nil, nil, nil, &seekThreshold, nil); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	regs[3] = (regs[3] &^ 0x0F00) | (uint16(4&0x0F) << 8)
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("Configure (no retune) write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}
}

func TestRDA5807MBassMonoSoftmuteRds(t *testing.T) {
	conn, sensor, regs, _, _, _ := newSensor(t, 100.0, 8)

	if err := sensor.SetBassBoost(true); err != nil {
		t.Fatalf("SetBassBoost: %v", err)
	}
	regs[0] |= rdaBASS
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("SetBassBoost write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}

	if err := sensor.SetMono(true); err != nil {
		t.Fatalf("SetMono: %v", err)
	}
	regs[0] |= rdaMONO
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("SetMono write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}

	if err := sensor.SetSoftmute(false); err != nil {
		t.Fatalf("SetSoftmute: %v", err)
	}
	regs[2] &^= rdaSOFTMUTE
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("SetSoftmute write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}

	if err := sensor.EnableRds(true); err != nil {
		t.Fatalf("EnableRds: %v", err)
	}
	regs[0] |= rdaRDS_EN
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("EnableRds write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}
}

func TestRDA5807MRdsReadyAndGroup(t *testing.T) {
	conn, sensor, _, _, _, _ := newSensor(t, 100.0, 8)

	conn.queueRead(statusBytes(rdaRDSR))
	ready, err := sensor.RdsReady()
	if err != nil || !ready {
		t.Errorf("RdsReady() = %v, %v, want true, nil", ready, err)
	}
	conn.queueRead(statusBytes(0))
	ready, err = sensor.RdsReady()
	if err != nil || ready {
		t.Errorf("RdsReady() = %v, %v, want false, nil", ready, err)
	}

	conn.queueRead(statusBytes(rdaRDSR, 0, 0x1122, 0x3344, 0x5566, 0x7788))
	group, err := sensor.ReadRdsGroup()
	if err != nil {
		t.Fatalf("ReadRdsGroup: %v", err)
	}
	if group == nil || *group != [4]uint16{0x1122, 0x3344, 0x5566, 0x7788} {
		t.Errorf("ReadRdsGroup() = %v, want [1122 3344 5566 7788]", group)
	}
	conn.queueRead(statusBytes(0, 0, 0, 0, 0, 0))
	group, err = sensor.ReadRdsGroup()
	if err != nil || group != nil {
		t.Errorf("ReadRdsGroup() = %v, %v, want nil, nil", group, err)
	}
}

func TestRDA5807MStatusFlags(t *testing.T) {
	conn, sensor, _, _, _, _ := newSensor(t, 100.0, 8)

	conn.queueRead(statusBytes(rdaST))
	if stereo, err := sensor.IsStereo(); err != nil || !stereo {
		t.Errorf("IsStereo() = %v, %v, want true, nil", stereo, err)
	}
	conn.queueRead(statusBytes(0, rdaFM_TRUE))
	if station, err := sensor.IsStation(); err != nil || !station {
		t.Errorf("IsStation() = %v, %v, want true, nil", station, err)
	}
	conn.queueRead(statusBytes(0, rdaFM_READY))
	if ready, err := sensor.IsReady(); err != nil || !ready {
		t.Errorf("IsReady() = %v, %v, want true, nil", ready, err)
	}
	conn.queueRead(statusBytes(0, uint16((100<<9)&0xFFFF)))
	if rssi, err := sensor.SignalStrength(); err != nil || rssi != 100 {
		t.Errorf("SignalStrength() = %v, %v, want 100, nil", rssi, err)
	}
}

func TestRDA5807MStandby(t *testing.T) {
	conn, sensor, regs, band, space, east50 := newSensor(t, 100.0, 8)

	if err := sensor.Standby(true); err != nil {
		t.Fatalf("Standby(true): %v", err)
	}
	regs[0] &^= rdaENABLE
	if string(lastWrite(conn.writes)) != string(regsBytes(regs)) {
		t.Errorf("Standby(true) write = % X, want % X", lastWrite(conn.writes), regsBytes(regs))
	}

	conn.queueRead(statusBytes(rdaSTC)) // Standby(false)'s internal SetFrequency's waitStc
	if err := sensor.Standby(false); err != nil {
		t.Fatalf("Standby(false): %v", err)
	}
	regs[0] |= rdaENABLE
	enableWrite := regsBytes(regs)
	chan3 := freqToChan(band, space, east50, 100.0) // newSensor()'s default frequency, unchanged so far
	regs[1] = (chan3 << 6) | rdaTUNE | (uint16(band) << 2) | uint16(space)
	retuneWrite := regsBytes(regs)
	n := len(conn.writes)
	if n < 2 || string(conn.writes[n-2]) != string(enableWrite) || string(conn.writes[n-1]) != string(retuneWrite) {
		t.Errorf("Standby(false) writes = %v, want [% X, % X]", conn.writes, enableWrite, retuneWrite)
	}
}

func TestRDA5807MSoftReset(t *testing.T) {
	conn, sensor, regs, band, space, east50 := newSensor(t, 100.0, 8)
	conn.queueRead(statusBytes(rdaSTC)) // SoftReset()'s internal SetFrequency's waitStc
	if err := sensor.SoftReset(); err != nil {
		t.Fatalf("SoftReset: %v", err)
	}
	regs[0] |= rdaSOFT_RESET
	setWrite := regsBytes(regs)
	regs[0] &^= rdaSOFT_RESET
	clearWrite := regsBytes(regs)
	chan4 := freqToChan(band, space, east50, 100.0) // newSensor()'s default frequency, unchanged so far
	regs[1] = (chan4 << 6) | rdaTUNE | (uint16(band) << 2) | uint16(space)
	retuneWrite := regsBytes(regs)
	n := len(conn.writes)
	if n < 3 || string(conn.writes[n-3]) != string(setWrite) ||
		string(conn.writes[n-2]) != string(clearWrite) || string(conn.writes[n-1]) != string(retuneWrite) {
		t.Errorf("SoftReset writes = %v, want [% X, % X, % X]", conn.writes, setWrite, clearWrite, retuneWrite)
	}
}
