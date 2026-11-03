package main

import (
	"context"
	"errors"
	"math"
	"net"
	"net/netip"
	"strings"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

const pingTimeout = 5 * time.Second

// pingFailure 对错误采用固定消息；本地 API 的错误可能含 URL、endpoint 或响应正文。
func pingFailure(ctx context.Context, err error) PingReply {
	var netErr net.Error
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(ctx.Err(), context.DeadlineExceeded) ||
		(errors.As(err, &netErr) && netErr.Timeout()) {
		return PingReply{TimedOut: true, Error: "Ping 超时"}
	}
	return PingReply{Error: "Ping 失败"}
}

func pingReply(result *ipnstate.PingResult, dm *tailcfg.DERPMap) PingReply {
	if result == nil {
		return PingReply{Error: "Ping 应答无效"}
	}
	if result.Err != "" {
		// 只保留已核验且不含地址的上游固定错误，其余错误输出简短分类。
		switch message := strings.TrimSpace(result.Err); message {
		case "unknown peer", "local tailscaled stopped", "peer's node key has expired", "ping size too big":
			return PingReply{Error: message}
		case "ping timeout", "ping timed out", "context deadline exceeded":
			return PingReply{TimedOut: true, Error: "Ping 超时"}
		default:
			return PingReply{Error: "Ping 失败"}
		}
	}
	if result.LatencySeconds < 0 || math.IsNaN(result.LatencySeconds) || math.IsInf(result.LatencySeconds, 0) {
		return PingReply{Error: "Ping 应答无效"}
	}
	reply := PingReply{}
	switch {
	case result.Endpoint != "":
		reply.Path, reply.DirectLAN = pathDirect, endpointIsLAN(result.Endpoint)
	case result.PeerRelay != "":
		reply.Path = pathPeerRelay
	case result.DERPRegionID != 0 || result.DERPRegionCode != "":
		reply.Path, reply.RelayRegion = pathDERP, result.DERPRegionCode
		if dm != nil {
			if region := dm.Regions[result.DERPRegionID]; region != nil {
				reply.RelayRegion, reply.RelayRegionName = region.RegionCode, region.RegionName
			}
		}
		if reply.RelayRegionName == "" {
			reply.RelayRegionName = derpRegionName(dm, reply.RelayRegion)
		}
	default:
		return PingReply{Error: "Ping 未返回连接路径"}
	}
	latency := result.LatencySeconds * 1000
	reply.LatencyMS = &latency
	return reply
}

// Ping 对已校验的 tailnet IP 发一次 disco ping，失败也作为成功协议应答中的结果返回。
func (n *Node) Ping(ctx context.Context, ip netip.Addr) PingReply {
	lc := n.lcp.Load()
	if lc == nil {
		return PingReply{Error: "Tailscale 未运行"}
	}
	ctx, cancel := context.WithTimeout(ctx, pingTimeout)
	defer cancel()
	result, err := lc.PingWithOpts(ctx, ip, tailcfg.PingDisco, local.PingOpts{})
	if err != nil {
		return pingFailure(ctx, err)
	}
	var dm *tailcfg.DERPMap
	if result != nil && result.Err == "" && (result.DERPRegionID != 0 || result.DERPRegionCode != "") {
		dm, _ = lc.CurrentDERPMap(ctx)
	}
	return pingReply(result, dm)
}
