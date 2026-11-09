package main

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"testing"
)

type fakeDirectory struct {
	running bool
	suffix  string
	peers   []Peer
	reads   int
}

func (f *fakeDirectory) Running() bool          { return f.running }
func (f *fakeDirectory) MagicDNSSuffix() string { return f.suffix }
func (f *fakeDirectory) Peers(ctx context.Context, refresh bool) []Peer {
	f.reads++
	return f.peers
}

func noDNS(context.Context, string) ([]netip.Addr, error) {
	return nil, errors.New("test DNS unavailable")
}

func samplePeers() []Peer {
	return []Peer{
		{ShortName: "nas", DNSName: "nas.tail1234.ts.net", HostName: "synology", IPs: []netip.Addr{netip.MustParseAddr("100.101.102.103"), netip.MustParseAddr("fd7a:115c:a1e0::1")}, Online: true},
		{ShortName: "laptop", DNSName: "laptop.tail1234.ts.net", HostName: "laptop", IPs: []netip.Addr{netip.MustParseAddr("100.64.0.9")}, Online: false},
	}
}

func TestIsTailnetAddr(t *testing.T) {
	for _, s := range []string{"100.64.0.0", "100.127.255.255", "100.101.102.103", "fd7a:115c:a1e0::1", "::ffff:100.64.0.1"} {
		if !IsTailnetAddr(netip.MustParseAddr(s)) {
			t.Errorf("%s 应属于 tailnet", s)
		}
	}
	for _, s := range []string{"100.63.255.255", "100.128.0.0", "10.0.0.1", "8.8.8.8", "fd7a:115c:a1e1::1", "::1"} {
		if IsTailnetAddr(netip.MustParseAddr(s)) {
			t.Errorf("%s 不应属于 tailnet", s)
		}
	}
}

func TestResolveRoutes(t *testing.T) {
	dir := &fakeDirectory{running: true, suffix: "tail1234.ts.net", peers: samplePeers()}
	r := &Router{Directory: dir, LookupIP: noDNS}
	cases := []struct {
		host    string
		tailnet bool
		addr    string
		err     error
	}{
		{"100.101.102.103", true, "100.101.102.103", nil},
		{"[fd7a:115c:a1e0::1]", true, "fd7a:115c:a1e0::1", nil},
		{"nas", true, "100.101.102.103", nil},
		{"NAS.tail1234.ts.net.", true, "100.101.102.103", nil},
		{"synology", true, "100.101.102.103", nil},
		{"ghost.tail1234.ts.net", true, "", errUnknownTailnet},
		{"other.tailffff.ts.net", true, "", errUnknownTailnet},
		{"router", false, "", nil},
		{"localhost", false, "", nil},
		{"example.com", false, "", nil},
		{"192.168.1.10", false, "", nil},
		{"8.8.8.8", false, "", nil},
	}
	for _, c := range cases {
		route := r.Resolve(context.Background(), c.host)
		if route.Tailnet != c.tailnet {
			t.Errorf("%s: tailnet=%v，期望 %v", c.host, route.Tailnet, c.tailnet)
		}
		if c.addr != "" && route.Addr.String() != c.addr {
			t.Errorf("%s: addr=%v，期望 %s", c.host, route.Addr, c.addr)
		}
		if !errors.Is(route.Err, c.err) {
			t.Errorf("%s: err=%v，期望 %v", c.host, route.Err, c.err)
		}
	}
}

func TestCustomControlServerSuffix(t *testing.T) {
	dir := &fakeDirectory{running: true, suffix: "home.example.org", peers: []Peer{
		{ShortName: "box", DNSName: "box.home.example.org", IPs: []netip.Addr{netip.MustParseAddr("100.70.0.2")}},
	}}
	r := &Router{Directory: dir, LookupIP: noDNS}
	if route := r.Resolve(context.Background(), "box.home.example.org"); !route.Tailnet || route.Addr.String() != "100.70.0.2" {
		t.Fatalf("Headscale 后缀应经 tailnet：%+v", route)
	}
	if route := r.Resolve(context.Background(), "missing.home.example.org"); !route.Tailnet || route.Err == nil {
		t.Fatalf("后缀内的未知节点应拒绝而不是走公网 DNS：%+v", route)
	}
	if route := r.Resolve(context.Background(), "www.example.org"); route.Tailnet {
		t.Fatalf("同一注册域下的公网名称应直连：%+v", route)
	}
}

type recordingDial struct {
	addr string
}

func (d *recordingDial) dial(ctx context.Context, network, address string) (net.Conn, error) {
	d.addr = address
	c1, c2 := net.Pipe()
	go c2.Close()
	return c1, nil
}

func TestDialSplitsTraffic(t *testing.T) {
	dir := &fakeDirectory{running: true, suffix: "tail1234.ts.net", peers: samplePeers()}
	tail, direct := &recordingDial{}, &recordingDial{}
	r := &Router{Directory: dir, Tailnet: tail.dial, Direct: direct.dial, LookupIP: noDNS}

	if _, err := r.Dial(context.Background(), "tcp", "nas:5666"); err != nil {
		t.Fatal(err)
	}
	if tail.addr != "100.101.102.103:5666" {
		t.Fatalf("tailnet 目标应按节点 IP 拨号，得到 %q", tail.addr)
	}
	if _, err := r.Dial(context.Background(), "tcp", "example.com:443"); err != nil {
		t.Fatal(err)
	}
	if direct.addr != "example.com:443" {
		t.Fatalf("公网域名应原样直连，交给系统 DNS 解析，得到 %q", direct.addr)
	}
	if _, err := r.Dial(context.Background(), "tcp", "laptop:22"); err != nil {
		t.Fatalf("tailnet 节点应经内置代理连接：%v", err)
	}
	if _, err := r.Dial(context.Background(), "udp", "nas:53"); !errors.Is(err, errUnsupportedNet) {
		t.Fatalf("UDP 应拒绝，得到 %v", err)
	}
	dir.running = false
	if _, err := r.Dial(context.Background(), "tcp", "nas:5666"); !errors.Is(err, errTailnetNotRunning) {
		t.Fatalf("未在线时 tailnet 目标应报告未登录，得到 %v", err)
	}
	if _, err := r.Dial(context.Background(), "tcp", "ghost.tail1234.ts.net:80"); !errors.Is(err, errTailnetNotRunning) {
		t.Fatalf("未在线时 MagicDNS 名称应报告未登录，得到 %v", err)
	}
	direct.addr = ""
	if _, err := r.Dial(context.Background(), "tcp", "example.com:80"); err != nil || direct.addr != "example.com:80" {
		t.Fatalf("未登录不影响直连：%v %q", err, direct.addr)
	}
}

func TestShortNameMissRefreshesPeersOnce(t *testing.T) {
	dir := &fakeDirectory{running: true, suffix: "tail1234.ts.net", peers: samplePeers()}
	r := &Router{Directory: dir, LookupIP: noDNS}
	r.Resolve(context.Background(), "printer")
	if dir.reads != 2 {
		t.Fatalf("短名未命中时应刷新一次节点列表，读取 %d 次", dir.reads)
	}
}

func TestOrdinaryDomainResolution(t *testing.T) {
	dir := &fakeDirectory{running: true}
	tail, direct := &recordingDial{}, &recordingDial{}
	lookups := 0
	r := &Router{Directory: dir, Tailnet: tail.dial, Direct: direct.dial,
		LookupIP: func(ctx context.Context, host string) ([]netip.Addr, error) {
			lookups++
			switch host {
			case "tail.example":
				return []netip.Addr{netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("100.70.0.1")}, nil
			case "public.example":
				return []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil
			default:
				return nil, errors.New("lookup failed")
			}
		}}
	for _, tc := range []struct{ host, wantTail, wantDirect string }{
		{"tail.example:443", "100.70.0.1:443", ""},
		{"public.example:80", "", "public.example:80"},
		{"bad.example:80", "", "bad.example:80"},
		{"localhost:8888", "", "localhost:8888"},
	} {
		tail.addr, direct.addr = "", ""
		conn, err := r.Dial(context.Background(), "tcp", tc.host)
		if err != nil {
			t.Fatalf("%s: %v", tc.host, err)
		}
		conn.Close()
		if tail.addr != tc.wantTail || direct.addr != tc.wantDirect {
			t.Errorf("%s: tail=%q direct=%q", tc.host, tail.addr, direct.addr)
		}
	}
	if lookups != 3 {
		t.Errorf("localhost 不应查询 DNS: %d", lookups)
	}
	dir.running = false
	tail.addr, direct.addr = "", ""
	_, err := r.Dial(context.Background(), "tcp", "tail.example:443")
	if !errors.Is(err, errTailnetNotRunning) || tail.addr != "" || direct.addr != "" {
		t.Errorf("离线时应拒绝并禁止直连: %v tail=%q direct=%q", err, tail.addr, direct.addr)
	}
}
