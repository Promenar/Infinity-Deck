package main

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strings"
	"time"
)

// 代理按目标分流：tailnet 地址经 tsnet，其它目标经系统网络直连（域名由 Android 系统 DNS 解析）。

var (
	// tailnetPrefix4 是 Tailscale 分配的 CGNAT 地址段。
	tailnetPrefix4 = netip.MustParsePrefix("100.64.0.0/10")
	// tailnetPrefix6 是 Tailscale 的 ULA 地址段。
	tailnetPrefix6 = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
)

// Tailscale 托管控制服务器的 MagicDNS 后缀；Headscale 等自建服务器的后缀从节点状态读取。
const tailscaleMagicDNSDomain = "ts.net"

var (
	errTailnetNotRunning = errors.New("Tailscale 未登录或未在线（tailnet_not_running）")
	errUnknownTailnet    = errors.New("tailnet 中没有这个节点（tailnet_unknown_host）")
	errUnsupportedNet    = errors.New("只支持 TCP 连接")
)

// IsTailnetAddr 判断地址是否属于 tailnet 地址段。
func IsTailnetAddr(addr netip.Addr) bool {
	addr = addr.Unmap()
	return tailnetPrefix4.Contains(addr) || tailnetPrefix6.Contains(addr)
}

// normalizeHost 统一大小写、去掉 IPv6 方括号与末尾的点。
func normalizeHost(host string) string {
	host = strings.TrimSuffix(strings.TrimPrefix(host, "["), "]")
	return strings.TrimSuffix(strings.ToLower(host), ".")
}

// Peer 是路由所需的节点信息（名称均已规范化）。
type Peer struct {
	ShortName       string // MagicDNS 名称第一段
	DNSName         string // MagicDNS 全名，不含末尾的点
	HostName        string // 节点上报的主机名
	IPs             []netip.Addr
	OS              string
	Online          bool
	Path            string
	DirectLAN       bool
	RelayRegion     string
	RelayRegionName string
}

// Directory 提供节点状态；由 tsnet 节点实现，测试使用假实现。
type Directory interface {
	// Running 表示节点已登录并在线，可以经 tsnet 拨号。
	Running() bool
	// MagicDNSSuffix 返回当前 tailnet 的 MagicDNS 后缀（不含首尾的点），未知时为空。
	MagicDNSSuffix() string
	// Peers 返回节点列表；refresh 为 true 时绕过缓存重新读取。
	Peers(ctx context.Context, refresh bool) []Peer
}

// DialFunc 与 net.Dialer.DialContext 同签名。
type DialFunc func(ctx context.Context, network, address string) (net.Conn, error)

// Route 是一次分流判定。
type Route struct {
	Tailnet bool
	// Addr 是 tailnet 目标解析得到的地址；直连目标为零值。
	Addr netip.Addr
	Peer *Peer
	Err  error
}

// Router 根据目标决定经 tsnet 还是直连。
type Router struct {
	Directory Directory
	Tailnet   DialFunc
	Direct    DialFunc
}

func findPeerByAddr(peers []Peer, addr netip.Addr) *Peer {
	for i := range peers {
		for _, ip := range peers[i].IPs {
			if ip == addr {
				return &peers[i]
			}
		}
	}
	return nil
}

func findPeerByName(peers []Peer, name, suffix string) *Peer {
	for i := range peers {
		p := &peers[i]
		if name == p.DNSName || name == p.ShortName {
			return p
		}
		if suffix != "" && name == p.ShortName+"."+suffix {
			return p
		}
	}
	// 主机名与 MagicDNS 名称不同时（MagicDNS 名称被改写或重复），再按主机名匹配。
	for i := range peers {
		if p := &peers[i]; p.HostName != "" && name == p.HostName {
			return p
		}
	}
	return nil
}

// isMagicDNSName 判断域名是否属于 tailnet 的 MagicDNS 命名空间（不论节点是否存在）。
func isMagicDNSName(name, suffix string) bool {
	if strings.HasSuffix(name, "."+tailscaleMagicDNSDomain) {
		return true
	}
	return suffix != "" && (name == suffix || strings.HasSuffix(name, "."+suffix))
}

// Resolve 判定 host 的路由。规则：
//   - tailnet 地址段内的 IP 经 tsnet；
//   - MagicDNS 全名（*.ts.net 或当前 tailnet 后缀）经 tsnet，节点不存在时拒绝，不回落到公网 DNS；
//   - 不含点的短名匹配到节点时经 tsnet，否则直连（保留局域网短名的原有行为）；
//   - 其它目标直连。
func (r *Router) Resolve(ctx context.Context, host string) Route {
	name := normalizeHost(host)
	if addr, err := netip.ParseAddr(name); err == nil {
		addr = addr.Unmap()
		if !IsTailnetAddr(addr) {
			return Route{}
		}
		peer := findPeerByAddr(r.Directory.Peers(ctx, false), addr)
		return Route{Tailnet: true, Addr: addr, Peer: peer}
	}
	if name == "" || name == "localhost" || strings.HasSuffix(name, ".localhost") {
		return Route{}
	}
	suffix := normalizeHost(r.Directory.MagicDNSSuffix())
	magic := isMagicDNSName(name, suffix)
	dotless := !strings.Contains(name, ".")
	if !magic && !dotless {
		return Route{}
	}
	peer := findPeerByName(r.Directory.Peers(ctx, false), name, suffix)
	if peer == nil {
		// 节点列表可能尚未刷新（新节点刚上线）；再读一次。
		peer = findPeerByName(r.Directory.Peers(ctx, true), name, suffix)
	}
	if peer == nil {
		if magic {
			return Route{Tailnet: true, Err: errUnknownTailnet}
		}
		return Route{}
	}
	route := Route{Tailnet: true, Peer: peer}
	if len(peer.IPs) > 0 {
		route.Addr = peer.IPs[0]
	} else {
		route.Err = errUnknownTailnet
	}
	return route
}

// Dial 按分流结果拨号；只支持 TCP。
func (r *Router) Dial(ctx context.Context, network, address string) (net.Conn, error) {
	switch network {
	case "tcp", "tcp4", "tcp6":
	default:
		return nil, errUnsupportedNet
	}
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	route := r.Resolve(ctx, host)
	if !route.Tailnet {
		return r.Direct(ctx, "tcp", address)
	}
	if route.Err != nil {
		if !r.Directory.Running() {
			return nil, errTailnetNotRunning
		}
		return nil, route.Err
	}

	if !r.Directory.Running() {
		return nil, errTailnetNotRunning
	}
	return r.Tailnet(ctx, "tcp", net.JoinHostPort(route.Addr.String(), port))
}

// directDialer 直连公网与局域网目标；net.DefaultResolver 在 Android 上由 androiddns 改为经系统 dnsproxyd 解析。
func directDialer() DialFunc {
	d := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
	return d.DialContext
}
