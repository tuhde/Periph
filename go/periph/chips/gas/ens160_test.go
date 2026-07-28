package gas

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus.
//
// Supports the two access patterns chip drivers in this repo use:
//   - Register-addressed reads (WriteRead([]byte{reg}, n)): backed by a
//     byte-addressable registers map. Preload it with setRegister.
//   - Plain streamed reads (Read(n), no register address): backed by a FIFO
//     queue. Preload responses with queueRead; each Read(n) call pops the
//     next one. Falls back to n zero bytes if the queue is empty.
//
// Every Write call (register writes and plain command writes alike) is
// appended to writes for assertions, and 2+ byte writes are also applied to
// registers so a later WriteRead sees them.
type mockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	readQueue [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{registers: map[byte]byte{}}
}

func (m *mockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *mockConnection) queueRead(data []byte) {
	m.readQueue = append(m.readQueue, data)
}

func (m *mockConnection) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	if len(data) >= 2 {
		reg := data[0]
		for i := 1; i < len(data); i++ {
			m.registers[reg+byte(i-1)] = data[i]
		}
	}
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
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func lastWriteTo(writes [][]byte, reg byte) []byte {
	var last []byte
	for _, w := range writes {
		if len(w) >= 1 && w[0] == reg {
			last = w
		}
	}
	return last
}

func TestENS160FullAPI(t *testing.T) {
	conn := newMockConnection()
	// PART_ID read during construction must return 0x0160 (LE: 0x60, 0x01).
	conn.setRegister(regPartID, 0x60, 0x01)

	sensor, err := NewENS160Full(conn)
	if err != nil {
		t.Fatalf("NewENS160Full: %v", err)
	}

	opmode := lastWriteTo(conn.writes, regOPMODE)
	if opmode == nil || opmode[1] != opModeStandard {
		t.Errorf("init: expected last OPMODE write to be STANDARD, got %v", opmode)
	}

	// DEVICE_STATUS: NEWDAT (bit1) set, VALIDITY_FLAG (bits 3:2) = 0 (OK).
	conn.setRegister(regDeviceStatus, 0x02)
	if v, err := sensor.Status(); err != nil || v != ValidityOK {
		t.Errorf("Status() = %d, %v, want %d, nil", v, err, ValidityOK)
	}

	// DATA_AQI block (5 bytes): aqi=2, tvoc=150 (0x0096 LE), eco2=500 (0x01F4 LE).
	conn.setRegister(regDataAQI, 2, 0x96, 0x00, 0xF4, 0x01)

	aqi, tvoc, eco2, err := sensor.ReadAirQuality()
	if err != nil {
		t.Fatalf("ReadAirQuality: %v", err)
	}
	if aqi != 2 || tvoc != 150.0 || eco2 != 500.0 {
		t.Errorf("ReadAirQuality() = (%d, %v, %v), want (2, 150, 500)", aqi, tvoc, eco2)
	}

	if v, _ := sensor.ReadTVOC(); v != 150.0 {
		t.Errorf("ReadTVOC() = %v, want 150", v)
	}
	if v, _ := sensor.ReadECO2(); v != 500.0 {
		t.Errorf("ReadECO2() = %v, want 500", v)
	}
	if v, _ := sensor.ReadAQI(); v != 2 {
		t.Errorf("ReadAQI() = %v, want 2", v)
	}
	if v, _ := sensor.ReadEthanol(); v != 150.0 {
		t.Errorf("ReadEthanol() = %v, want 150", v)
	}

	if err := sensor.SetCompensation(25.0, 50.0); err != nil {
		t.Fatalf("SetCompensation: %v", err)
	}
	tempRaw := uint16(19082) // round((25+273.15)*64)
	rhRaw := uint16(25600)   // round(50*512)
	if conn.registers[regTEMP_IN] != byte(tempRaw&0xFF) || conn.registers[regTEMP_IN+1] != byte(tempRaw>>8) {
		t.Errorf("SetCompensation: TEMP_IN registers wrong: %v", conn.registers)
	}
	if conn.registers[regRH_IN] != byte(rhRaw&0xFF) || conn.registers[regRH_IN+1] != byte(rhRaw>>8) {
		t.Errorf("SetCompensation: RH_IN registers wrong: %v", conn.registers)
	}

	// DATA_T/DATA_RH actuals (4 bytes): tempRaw=0x5202 (~55.0 degC), rhRaw=0x6400 (50.0 %RH).
	conn.setRegister(regDataT, 0x02, 0x52, 0x00, 0x64)
	temp, rh, err := sensor.ReadCompensationActuals()
	if err != nil {
		t.Fatalf("ReadCompensationActuals: %v", err)
	}
	wantTemp := float32(0x5202)/64.0 - 273.15
	if temp != wantTemp || rh != 0x6400/512.0 {
		t.Errorf("ReadCompensationActuals() = (%v, %v), want (%v, %v)", temp, rh, wantTemp, float32(0x6400)/512.0)
	}

	// GPR_READ sensor 1 (offset 0): raw=2048 -> resistance = 2**(2048/2048) = 2.0 Ohm.
	conn.setRegister(regGPR_READ, 0x00, 0x08)
	if v, _ := sensor.ReadRawResistance(1); v != 2.0 {
		t.Errorf("ReadRawResistance(1) = %v, want 2.0", v)
	}

	// GPR_READ sensor 4 (offset 6): raw=4096 -> resistance = 2**(4096/2048) = 4.0 Ohm.
	conn.setRegister(byte(regGPR_READ+6), 0x00, 0x10)
	if v, _ := sensor.ReadRawResistance(4); v != 4.0 {
		t.Errorf("ReadRawResistance(4) = %v, want 4.0", v)
	}

	if _, err := sensor.ReadRawResistance(2); err == nil {
		t.Errorf("ReadRawResistance(2): expected error for invalid sensor")
	}

	// GET_APPVER response at GPR_READ+4: major=1, minor=2, release=3.
	conn.setRegister(byte(regGPR_READ+4), 1, 2, 3)
	major, minor, release, err := sensor.GetFirmwareVersion()
	if err != nil || major != 1 || minor != 2 || release != 3 {
		t.Errorf("GetFirmwareVersion() = (%d, %d, %d, %v), want (1, 2, 3, nil)", major, minor, release, err)
	}

	if err := sensor.ConfigureInterrupt(true, true, true, true, true); err != nil {
		t.Fatalf("ConfigureInterrupt: %v", err)
	}
	if cfg := lastWriteTo(conn.writes, regCONFIG); cfg == nil || cfg[1] != 0x6B {
		t.Errorf("ConfigureInterrupt: expected CONFIG=0x6B, got %v", cfg)
	}

	if err := sensor.Sleep(); err != nil {
		t.Fatalf("Sleep: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; len(last) != 2 || last[1] != opModeDeepSleep {
		t.Errorf("Sleep: expected last write OPMODE=DEEP_SLEEP, got %v", last)
	}

	if err := sensor.Wake(); err != nil {
		t.Fatalf("Wake: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; len(last) != 2 || last[1] != opModeStandard {
		t.Errorf("Wake: expected last write OPMODE=STANDARD, got %v", last)
	}
}
