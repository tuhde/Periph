package imu

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus.
//
// Register-addressed reads (WriteRead([]byte{reg}, n)) are backed by a
// byte-addressable registers map, preloaded via setRegister. Every Write
// call is logged to writes for assertions, and 2+ byte writes are also
// applied to registers so a later WriteRead sees them.
type mockConnection struct {
	registers map[byte]byte
	writes    [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{registers: map[byte]byte{}}
}

func (m *mockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
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

// s16 encodes a signed 16-bit value as its two big-endian bytes.
func s16(value int16) (byte, byte) {
	u := uint16(value)
	return byte(u >> 8), byte(u & 0xFF)
}

func lastWrite(writes [][]byte) []byte {
	if len(writes) == 0 {
		return nil
	}
	return writes[len(writes)-1]
}

func newInitializedSensor(t *testing.T) (*mockConnection, *MPU6050Full) {
	t.Helper()
	conn := newMockConnection()
	conn.setRegister(regWhoAmI, whoAmIValue)
	sensor, err := NewMPU6050Full(conn)
	if err != nil {
		t.Fatalf("NewMPU6050Full: %v", err)
	}
	return conn, sensor
}

func TestMPU6050Init(t *testing.T) {
	conn, _ := newInitializedSensor(t)

	expected := [][]byte{
		{regPwrMgmt1, 0x80},
		{regPwrMgmt1, 0x01},
		{regWhoAmI},
		{regGyroConfig, 0x00},
		{regAccelConfig, 0x00},
		{regConfig, 0x03},
		{regSmplrtDiv, 0x04},
	}
	if len(conn.writes) != len(expected) {
		t.Fatalf("init write count = %d, want %d", len(conn.writes), len(expected))
	}
	for i, w := range expected {
		if string(conn.writes[i]) != string(w) {
			t.Errorf("init write[%d] = % X, want % X", i, conn.writes[i], w)
		}
	}
}

func TestMPU6050WhoAmIMismatch(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(regWhoAmI, 0x00)
	if _, err := NewMPU6050Full(conn); err == nil {
		t.Error("NewMPU6050Full with bad WHO_AM_I: expected error, got nil")
	}
}

func TestMPU6050Accel(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	// raw (16384, -8192, 4096) at default AFS_SEL=0 (16384 LSB/g).
	h, l := s16(16384)
	conn.setRegister(regAccelXoutH, h, l)
	h, l = s16(-8192)
	conn.setRegister(regAccelXoutH+2, h, l)
	h, l = s16(4096)
	conn.setRegister(regAccelXoutH+4, h, l)

	ax, ay, az, err := sensor.Accel()
	if err != nil {
		t.Fatalf("Accel: %v", err)
	}
	if abs32(ax-9.80665) > 1e-3 || abs32(ay-(-4.903325)) > 1e-3 || abs32(az-2.4516625) > 1e-3 {
		t.Errorf("Accel() = (%v, %v, %v), want (9.80665, -4.903325, 2.4516625)", ax, ay, az)
	}
}

func TestMPU6050Gyro(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	// raw (131, -131, 262) at default FS_SEL=0 (131.0 LSB/(deg/s)) -> (1, -1, 2) dps.
	h, l := s16(131)
	conn.setRegister(regGyroXoutH, h, l)
	h, l = s16(-131)
	conn.setRegister(regGyroXoutH+2, h, l)
	h, l = s16(262)
	conn.setRegister(regGyroXoutH+4, h, l)

	gx, gy, gz, err := sensor.Gyro()
	if err != nil {
		t.Fatalf("Gyro: %v", err)
	}
	deg2rad := func(d float32) float32 { return d * 3.141592653589793 / 180.0 }
	if abs32(gx-deg2rad(1)) > 1e-4 || abs32(gy-deg2rad(-1)) > 1e-4 || abs32(gz-deg2rad(2)) > 1e-4 {
		t.Errorf("Gyro() = (%v, %v, %v), want ~(%v, %v, %v)", gx, gy, gz, deg2rad(1), deg2rad(-1), deg2rad(2))
	}
}

func TestMPU6050ConfigureGyroChangesSensitivity(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	if err := sensor.ConfigureGyro(2); err != nil {
		t.Fatalf("ConfigureGyro: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{regGyroConfig, 2 << 3}) {
		t.Errorf("ConfigureGyro write = % X, want % X", lastWrite(conn.writes), []byte{regGyroConfig, 2 << 3})
	}
	// Sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps.
	h, l := s16(328)
	conn.setRegister(regGyroXoutH, h, l)
	conn.setRegister(regGyroXoutH+2, 0, 0)
	conn.setRegister(regGyroXoutH+4, 0, 0)
	gx, _, _, err := sensor.Gyro()
	if err != nil {
		t.Fatalf("Gyro: %v", err)
	}
	want := float32(10) * 3.141592653589793 / 180.0
	if abs32(gx-want) > 1e-3 {
		t.Errorf("Gyro() x = %v, want %v", gx, want)
	}
}

func TestMPU6050ConfigureAccelChangesSensitivity(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	if err := sensor.ConfigureAccel(1); err != nil {
		t.Fatalf("ConfigureAccel: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{regAccelConfig, 1 << 3}) {
		t.Errorf("ConfigureAccel write = % X, want % X", lastWrite(conn.writes), []byte{regAccelConfig, 1 << 3})
	}
	// Sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g.
	h, l := s16(8192)
	conn.setRegister(regAccelXoutH, h, l)
	conn.setRegister(regAccelXoutH+2, 0, 0)
	conn.setRegister(regAccelXoutH+4, 0, 0)
	ax, _, _, err := sensor.Accel()
	if err != nil {
		t.Fatalf("Accel: %v", err)
	}
	if abs32(ax-9.80665) > 1e-3 {
		t.Errorf("Accel() x = %v, want 9.80665", ax)
	}
}

func TestMPU6050ConfigureDLPFAndSampleRate(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	if err := sensor.ConfigureDLPF(5); err != nil {
		t.Fatalf("ConfigureDLPF: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{regConfig, 5}) {
		t.Errorf("ConfigureDLPF write = % X, want % X", lastWrite(conn.writes), []byte{regConfig, 5})
	}
	if err := sensor.ConfigureSampleRate(9); err != nil {
		t.Fatalf("ConfigureSampleRate: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{regSmplrtDiv, 9}) {
		t.Errorf("ConfigureSampleRate write = % X, want % X", lastWrite(conn.writes), []byte{regSmplrtDiv, 9})
	}
}

func TestMPU6050Temperature(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	// raw=340 -> 340/340 + 36.53 = 37.53 degC.
	h, l := s16(340)
	conn.setRegister(regTempOutH, h, l)
	temp, err := sensor.Temperature()
	if err != nil {
		t.Fatalf("Temperature: %v", err)
	}
	if abs32(temp-37.53) > 1e-3 {
		t.Errorf("Temperature() = %v, want 37.53", temp)
	}
}

func TestMPU6050RawReads(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	h, l := s16(100)
	conn.setRegister(regAccelXoutH, h, l)
	h, l = s16(-200)
	conn.setRegister(regAccelXoutH+2, h, l)
	h, l = s16(300)
	conn.setRegister(regAccelXoutH+4, h, l)
	ax, ay, az, err := sensor.AccelRaw()
	if err != nil || ax != 100 || ay != -200 || az != 300 {
		t.Errorf("AccelRaw() = (%d, %d, %d, %v), want (100, -200, 300, nil)", ax, ay, az, err)
	}

	h, l = s16(-50)
	conn.setRegister(regGyroXoutH, h, l)
	h, l = s16(60)
	conn.setRegister(regGyroXoutH+2, h, l)
	h, l = s16(-70)
	conn.setRegister(regGyroXoutH+4, h, l)
	gx, gy, gz, err := sensor.GyroRaw()
	if err != nil || gx != -50 || gy != 60 || gz != -70 {
		t.Errorf("GyroRaw() = (%d, %d, %d, %v), want (-50, 60, -70, nil)", gx, gy, gz, err)
	}
}

func TestMPU6050DataReady(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	conn.setRegister(regIntStatus, 0x01)
	if ready, err := sensor.DataReady(); err != nil || !ready {
		t.Errorf("DataReady() = %v, %v, want true, nil", ready, err)
	}
	conn.setRegister(regIntStatus, 0x00)
	if ready, err := sensor.DataReady(); err != nil || ready {
		t.Errorf("DataReady() = %v, %v, want false, nil", ready, err)
	}
}

func TestMPU6050SetSleep(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	// PWR_MGMT_1 is 0x01 in the register map after init.
	if err := sensor.SetSleep(true); err != nil {
		t.Fatalf("SetSleep(true): %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{regPwrMgmt1, 0x41}) {
		t.Errorf("SetSleep(true) write = % X, want % X", lastWrite(conn.writes), []byte{regPwrMgmt1, 0x41})
	}
	if err := sensor.SetSleep(false); err != nil {
		t.Fatalf("SetSleep(false): %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{regPwrMgmt1, 0x01}) {
		t.Errorf("SetSleep(false) write = % X, want % X", lastWrite(conn.writes), []byte{regPwrMgmt1, 0x01})
	}
}

func TestMPU6050SetStandby(t *testing.T) {
	conn, sensor := newInitializedSensor(t)
	if err := sensor.SetStandby(true, false, false, false, false, true); err != nil {
		t.Fatalf("SetStandby: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{regPwrMgmt2, 0x21}) {
		t.Errorf("SetStandby write = % X, want % X", lastWrite(conn.writes), []byte{regPwrMgmt2, 0x21})
	}
}

func TestMPU6050FIFO(t *testing.T) {
	conn, sensor := newInitializedSensor(t)

	conn.setRegister(regFifoCountH, 0x03, 0x45)
	count, err := sensor.FIFOcount()
	if err != nil {
		t.Fatalf("FIFOcount: %v", err)
	}
	if count != (uint16(0x03)&0x1F)<<8|0x45 {
		t.Errorf("FIFOcount() = %d, want %d", count, (uint16(0x03)&0x1F)<<8|0x45)
	}

	conn.setRegister(regFifoCountH, 0x00, 0x02)
	conn.setRegister(regFifoR_W, 0xAA, 0xBB)
	buf := make([]byte, 8)
	n, err := sensor.ReadFIFO(buf)
	if err != nil || n != 2 || buf[0] != 0xAA || buf[1] != 0xBB {
		t.Errorf("ReadFIFO() = %d, %v, buf=% X, want 2, nil, [AA BB ...]", n, err, buf)
	}

	conn.setRegister(regFifoCountH, 0x00, 0x00)
	n, err = sensor.ReadFIFO(buf)
	if err != nil || n != 0 {
		t.Errorf("ReadFIFO() (empty) = %d, %v, want 0, nil", n, err)
	}
}

func TestMPU6050EnableAndResetFIFO(t *testing.T) {
	conn, sensor := newInitializedSensor(t)

	if err := sensor.EnableFIFO(true, true, false); err != nil {
		t.Fatalf("EnableFIFO: %v", err)
	}
	n := len(conn.writes)
	// FIFO_EN write, then a USER_CTRL read (whose WriteRead phase also
	// appends a []byte{reg} entry), then the USER_CTRL write.
	if string(conn.writes[n-3]) != string([]byte{regFifoEn, (1 << 3) | (1 << 4)}) ||
		string(conn.writes[n-2]) != string([]byte{regUserCtrl}) ||
		string(conn.writes[n-1]) != string([]byte{regUserCtrl, 0x40}) {
		t.Errorf("EnableFIFO writes = %v", conn.writes[n-3:])
	}

	// USER_CTRL is 0x40 in the register map after EnableFIFO().
	if err := sensor.ResetFIFO(); err != nil {
		t.Fatalf("ResetFIFO: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{regUserCtrl, 0x44}) {
		t.Errorf("ResetFIFO write = % X, want % X", lastWrite(conn.writes), []byte{regUserCtrl, 0x44})
	}
}

func abs32(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}
