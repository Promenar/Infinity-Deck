package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

func TestPingValidation(t *testing.T) {
	cases := []struct {
		name, request, errorCode string
		running                  bool
	}{
		{"缺少参数", `{"id":3,"op":"ping"}`, ErrBadRequest, true},
		{"空目标", `{"id":3,"op":"ping","ping":{"target":""}}`, ErrBadRequest, true},
		{"域名", `{"id":3,"op":"ping","ping":{"target":"nas"}}`, ErrBadRequest, true},
		{"端口", `{"id":3,"op":"ping","ping":{"target":"100.101.102.103:80"}}`, ErrBadRequest, true},
		{"未知地址", `{"id":3,"op":"ping","ping":{"target":"192.0.2.1"}}`, ErrBadRequest, true},
		{"离线节点", `{"id":3,"op":"ping","ping":{"target":"100.64.0.9"}}`, ErrBadRequest, true},
		{"未运行", `{"id":3,"op":"ping","ping":{"target":"100.101.102.103"}}`, ErrNotRunning, false},
		{"在线 IPv4", `{"id":3,"op":"ping","ping":{"target":"100.101.102.103"}}`, "", true},
		{"在线 IPv6", `{"id":3,"op":"ping","ping":{"target":"fd7a:115c:a1e0::1"}}`, "", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var out bytes.Buffer
			s := NewServer(NewWriter(&out), nil, nil)
			defer s.cancel()
			backend := &fakeBackend{fakeDirectory: fakeDirectory{running: c.running, peers: samplePeers()}}
			var req Request
			if err := json.Unmarshal([]byte(c.request), &req); err != nil {
				t.Fatal(err)
			}
			s.handle(&req, backend)
			var reply Response
			if err := json.Unmarshal(out.Bytes(), &reply); err != nil {
				t.Fatal(err)
			}
			if reply.ID != 3 || reply.Error != c.errorCode || reply.OK != (c.errorCode == "") {
				t.Fatalf("应答错误：%s", out.String())
			}
			if c.errorCode == "" {
				if len(backend.pingTargets) != 1 || backend.pingTargets[0].String() != req.Ping.Target {
					t.Fatal("应只探测请求中的在线节点地址")
				}
			} else if len(backend.pingTargets) != 0 {
				t.Fatal("无效请求不得触发 ping")
			}
		})
	}
}

func TestNodePingProtocolResults(t *testing.T) {
	cases := []struct {
		name                        string
		result                      *ipnstate.PingResult
		err                         error
		mapFails                    bool
		path                        string
		lan, timedOut               bool
		latency                     *float64
		region, regionName, message string
	}{
		{name: "私网直连", result: &ipnstate.PingResult{Endpoint: "10.0.0.1:12345", LatencySeconds: 0.00125}, path: "direct", lan: true, latency: floatPointer(1.25)},
		{name: "公网直连", result: &ipnstate.PingResult{Endpoint: "192.0.2.1:12345", LatencySeconds: 0.0025}, path: "direct", latency: floatPointer(2.5)},
		{name: "零延迟", result: &ipnstate.PingResult{Endpoint: "[2001:db8::1]:12345"}, path: "direct", latency: floatPointer(0)},
		{name: "peer relay", result: &ipnstate.PingResult{PeerRelay: "192.0.2.2:12345:vni:42", LatencySeconds: 0.003}, path: "peer_relay", latency: floatPointer(3)},
		{name: "DERP", result: &ipnstate.PingResult{DERPRegionID: 20, DERPRegionCode: "hkg", LatencySeconds: 0.02}, path: "derp", latency: floatPointer(20), region: "hkg", regionName: "Hong Kong"},
		{name: "DERP 区域代码补全", result: &ipnstate.PingResult{DERPRegionID: 20}, path: "derp", latency: floatPointer(0), region: "hkg", regionName: "Hong Kong"},
		{name: "DERP map 不可用", result: &ipnstate.PingResult{DERPRegionID: 20, DERPRegionCode: "hkg"}, mapFails: true, path: "derp", latency: floatPointer(0), region: "hkg"},
		{name: "上游错误", result: &ipnstate.PingResult{Err: "unknown peer", Endpoint: "192.0.2.1:12345", LatencySeconds: 3}, message: "unknown peer"},
		{name: "错误含直连地址", result: &ipnstate.PingResult{Err: "failed to reach 192.0.2.1:12345"}, message: "Ping 失败"},
		{name: "错误含中转地址", result: &ipnstate.PingResult{Err: "failed via [2001:db8::1]:12345:vni:42"}, message: "Ping 失败"},
		{name: "空应答", message: "Ping 应答无效"},
		{name: "无路径", result: &ipnstate.PingResult{}, message: "Ping 未返回连接路径"},
		{name: "无效延迟", result: &ipnstate.PingResult{Endpoint: "192.0.2.1:12345", LatencySeconds: -1}, message: "Ping 应答无效"},
		{name: "上游超时", result: &ipnstate.PingResult{Err: "ping timed out"}, timedOut: true, message: "Ping 超时"},
		{name: "本地调用超时", err: context.DeadlineExceeded, timedOut: true, message: "Ping 超时"},
		{name: "调用错误含地址", err: errors.New("dial 192.0.2.1:12345 failed"), message: "Ping 失败"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			n := &Node{}
			calls := 0
			n.lcp.Store(&local.Client{OmitAuth: true, Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
				switch r.URL.Path {
				case "/localapi/v0/ping":
					calls++
					if r.Method != "POST" || r.URL.Query().Get("ip") != "100.64.0.2" || r.URL.Query().Get("type") != string(tailcfg.PingDisco) {
						t.Fatal("必须对指定 tailnet IP 发起 disco ping")
					}
					deadline, ok := r.Context().Deadline()
					if !ok || time.Until(deadline) > 5*time.Second {
						t.Fatal("单次 ping 必须限制为 5 秒")
					}
					if c.err != nil {
						return nil, c.err
					}
					return jsonHTTPResponse(t, c.result), nil
				case "/localapi/v0/derpmap":
					if c.mapFails {
						return nil, errors.New("unavailable at 192.0.2.2:12345")
					}
					return jsonHTTPResponse(t, &tailcfg.DERPMap{Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{20: {RegionCode: "hkg", RegionName: "Hong Kong"}}}), nil
				default:
					t.Fatalf("意外的本地 API：%s", r.URL.Path)
					return nil, nil
				}
			})})
			reply := n.Ping(context.Background(), netip.MustParseAddr("100.64.0.2"))
			if calls != 1 {
				t.Fatalf("应只发一次 ping，实际 %d", calls)
			}
			if reply.Path != c.path || reply.DirectLAN != c.lan || reply.TimedOut != c.timedOut || reply.Error != c.message ||
				reply.RelayRegion != c.region || reply.RelayRegionName != c.regionName {
				t.Fatalf("路径或错误分类错误：%+v", reply)
			}
			if (reply.LatencyMS == nil) != (c.latency == nil) || (c.latency != nil && *reply.LatencyMS != *c.latency) {
				t.Fatalf("延迟应只在成功时返回，实际 %+v", reply.LatencyMS)
			}
			var out bytes.Buffer
			NewWriter(&out).Reply(7, reply)
			var response map[string]any
			if err := json.Unmarshal(out.Bytes(), &response); err != nil {
				t.Fatal(err)
			}
			if response["ok"] != true || response["id"] != float64(7) {
				t.Fatal("探测成功、失败与超时都应编码为 ok: true")
			}
			for _, privateField := range []string{"10.0.0.1", "192.0.2.1", "192.0.2.2", "2001:db8::1", "12345", "Endpoint", "PeerRelay"} {
				if strings.Contains(out.String(), privateField) {
					t.Fatal("Ping JSON 泄露 endpoint 或原始字段")
				}
			}
		})
	}
}

func floatPointer(value float64) *float64 { return &value }

func TestPingBeforeStart(t *testing.T) {
	var out bytes.Buffer
	s := NewServer(NewWriter(&out), nil, nil)
	defer s.cancel()
	s.dispatch(&Request{ID: 4, Op: "ping"})
	var reply Response
	if err := json.Unmarshal(out.Bytes(), &reply); err != nil {
		t.Fatal(err)
	}
	if reply.OK || reply.Error != ErrNotRunning {
		t.Fatalf("未启动时应返回未运行：%s", out.String())
	}
}

func TestPingRejectsStaleOnlineCacheAfterRefreshFailure(t *testing.T) {
	var out bytes.Buffer
	s := NewServer(NewWriter(&out), nil, nil)
	defer s.cancel()
	n := &Node{peers: samplePeers(), peersAt: time.Now()}
	n.running.Store(true)
	pingCalls := 0
	n.lcp.Store(&local.Client{OmitAuth: true, Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/localapi/v0/ping" {
			pingCalls++
		}
		return nil, errors.New("本地状态读取失败")
	})})
	s.handle(&Request{ID: 5, Op: OpPing, Ping: &PingOptions{Target: "100.101.102.103"}}, n)
	var reply Response
	if err := json.Unmarshal(out.Bytes(), &reply); err != nil {
		t.Fatal(err)
	}
	if reply.OK || reply.Error != ErrBadRequest || pingCalls != 0 {
		t.Fatalf("强制刷新失败必须拒绝旧缓存并避免探测：ok=%v, error=%s, calls=%d", reply.OK, reply.Error, pingCalls)
	}
	if peers := n.Peers(context.Background(), false); len(peers) != 2 {
		t.Fatal("普通缓存读取应保留已有节点")
	}
}
