package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/netip"
	"strings"
	"testing"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func jsonHTTPResponse(t *testing.T, value any) *http.Response {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(string(data)))}
}

func TestPeerListConnectionPaths(t *testing.T) {
	// 公网使用文档保留地址；私网与回环地址仅为分类测试构造。
	cases := []struct {
		name, endpoint, peerRelay, relay string
		online, active                   bool
		path                             string
		lan                              bool
		region, regionName               string
	}{
		{"IPv4 私网", "10.0.0.1:12345", "", "hkg", true, true, "direct", true, "", ""},
		{"IPv4 公网", "192.0.2.1:12345", "", "hkg", true, true, "direct", false, "", ""},
		{"IPv6 私网", "[fd00:abcd::1]:12345", "", "", true, true, "direct", true, "", ""},
		{"IPv6 公网", "[2001:db8::1]:12345", "", "", true, true, "direct", false, "", ""},
		{"IPv4 链路本地", "169.254.1.1:12345", "", "", true, true, "direct", true, "", ""},
		{"IPv6 链路本地", "[fe80::1%test0]:12345", "", "", true, true, "direct", true, "", ""},
		{"IPv4 回环", "127.0.0.1:12345", "", "", true, true, "direct", true, "", ""},
		{"IPv6 回环", "[::1]:12345", "", "", true, true, "direct", true, "", ""},
		{"映射 IPv4", "[::ffff:10.0.0.1]:12345", "", "", true, true, "direct", true, "", ""},
		{"peer relay", "", "192.0.2.2:12345:vni:42", "hkg", true, true, "peer_relay", false, "", ""},
		{"直连优先", "192.0.2.1:12345", "192.0.2.2:12345:vni:42", "hkg", true, true, "direct", false, "", ""},
		{"DERP", "", "", "hkg", true, true, "derp", false, "hkg", "Hong Kong"},
		{"未知区域", "", "", "test", true, true, "derp", false, "test", ""},
		{"无近期连接", "", "", "hkg", true, false, "", false, "", ""},
		{"无路径", "", "", "", true, true, "", false, "", ""},
		{"离线直连", "10.0.0.1:12345", "", "hkg", false, true, "", false, "", ""},
		{"离线中转", "", "192.0.2.2:12345:vni:42", "hkg", false, true, "", false, "", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ps := &ipnstate.PeerStatus{HostName: "test-peer", TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2")},
				Online: c.online, Active: c.active, CurAddr: c.endpoint, PeerRelay: c.peerRelay, Relay: c.relay}
			n := &Node{}
			n.lcp.Store(&local.Client{OmitAuth: true, Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
				switch r.URL.Path {
				case "/localapi/v0/status":
					return jsonHTTPResponse(t, &ipnstate.Status{BackendState: "Running", Peer: map[key.NodePublic]*ipnstate.PeerStatus{{}: ps}}), nil
				case "/localapi/v0/derpmap":
					return jsonHTTPResponse(t, &tailcfg.DERPMap{Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{20: {RegionID: 20, RegionCode: "hkg", RegionName: "Hong Kong"}}}), nil
				default:
					t.Fatalf("意外的本地 API：%s", r.URL.Path)
					return nil, nil
				}
			})})
			peers := n.PeerList(context.Background())
			if len(peers) != 1 {
				t.Fatalf("应返回一个节点，实际 %d", len(peers))
			}
			data, err := json.Marshal(peers[0])
			if err != nil {
				t.Fatal(err)
			}
			var got map[string]any
			if err := json.Unmarshal(data, &got); err != nil {
				t.Fatal(err)
			}
			for field, want := range map[string]string{"path": c.path, "relayRegion": c.region, "relayRegionName": c.regionName} {
				if got[field] != want && !(want == "" && got[field] == nil) {
					t.Errorf("%s=%v，期望 %q", field, got[field], want)
				}
			}
			if got["directLan"] != c.lan && !(got["directLan"] == nil && !c.lan) {
				t.Errorf("directLan=%v，期望 %v", got["directLan"], c.lan)
			}
			for _, endpoint := range []string{c.endpoint, c.peerRelay} {
				if endpoint != "" && strings.Contains(string(data), endpoint) {
					t.Error("节点 JSON 泄露 endpoint")
				}
			}
		})
	}
}
