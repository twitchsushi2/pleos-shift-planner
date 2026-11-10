#!/usr/bin/env python3
"""OpenStreetMap 자료로 전국 고속도로망과 휴게소 데이터(expressway.tsv)를 만든다.

  python3 tools/gen_expressway.py <net.json> <osm.json> [출력.tsv] [coastline.geojson] [amenities.json]
"""
import heapq
import json
import math
import re
import sys
from collections import defaultdict

MAX_OFFSET_M = 300
MAX_END_GAP_M = 1500
# 큰 도로망과 이만큼 안에 있는 떨어진 고속도로 덩어리는 이어 붙인다. 이보다 먼 덩어리(북한 도로 등)는 버린다.
MAX_BRIDGE_M = 5000
MIN_ISLAND_KM = 0.3
# 덩어리 안의 점이 큰 도로망의 점과 이만큼 가까우면 그 자리도 잇는다(한쪽 방향만 이어진 분기점).
BRIDGE_TOUCH_M = 60
NAME_RE = re.compile(r"휴게소|만남의광장")
NOT_REST_AREA = re.compile(r"주유소|충전소|카트|모텔|식당")
DUPLICATE_WITHIN_M = 4000
SAME_PLACE_WITHIN_M = 1500
GRID = 0.01

CITIES = """서울 37.5665 126.9780|인천 37.4563 126.7052|수원 37.2636 127.0286|용인 37.2411 127.1776|평택 36.9921 127.1129
이천 37.2720 127.4350|여주 37.2983 127.6372|안성 37.0080 127.2797|원주 37.3422 127.9202|춘천 37.8813 127.7298
홍천 37.6970 127.8886|강릉 37.7519 128.8761|속초 38.2070 128.5918|동해 37.5247 129.1143|삼척 37.4500 129.1650
충주 36.9910 127.9260|청주 36.6424 127.4890|천안 36.8151 127.1139|세종 36.4800 127.2890|대전 36.3504 127.3845
공주 36.4465 127.1190|보령 36.3330 126.6128|서산 36.7848 126.4503|당진 36.8896 126.6458|논산 36.1872 127.0987
전주 35.8242 127.1480|군산 35.9676 126.7368|익산 35.9483 126.9576|정읍 35.5699 126.8560|광주 35.1595 126.8526
목포 34.8118 126.3922|순천 34.9506 127.4872|여수 34.7604 127.6622|남원 35.4164 127.3905|김천 36.1398 128.1136
구미 36.1195 128.3446|대구 35.8714 128.6014|안동 36.5684 128.7294|영주 36.8057 128.6241|포항 36.0190 129.3435
경주 35.8562 129.2247|울산 35.5384 129.3114|부산 35.1796 129.0756|양산 35.3350 129.0373|김해 35.2285 128.8894
창원 35.2281 128.6811|진주 35.1800 128.1076|통영 34.8544 128.4331|거제 34.8806 128.6211"""


def dist_m(a, b):
    mean = math.radians((a[0] + b[0]) / 2)
    return math.hypot((a[0] - b[0]) * 111320, (a[1] - b[1]) * 111320 * math.cos(mean))


def path_len(pts):
    return sum(dist_m(p, q) for p, q in zip(pts, pts[1:]))


def base_name(name):
    n = re.sub(r"\(.*?\)", "", name)
    # 방향은 도시 이름 두 글자다("부산방향"). 휴게소 이름에 붙어 와도("영주부산방향휴게소") 그 두 글자만 지운다.
    # 예전에는 앞의 한글을 모두 지워 "영주"까지 사라지고 "휴게소"만 남았다.
    n = re.sub(r"[가-힣]{2}\s*(방향|방면)", "", n)
    n = re.sub(r"(상행|하행)", "", n)
    n = n.replace(" ", "").strip()
    return n


def build(ways):
    """교차점에서 way를 잘라 방향 있는 구간으로 만든다."""
    touches = defaultdict(int)
    for w in ways:
        for i, n in enumerate(w["nodes"]):
            touches[n] += 2 if i in (0, len(w["nodes"]) - 1) else 1
    edges = []
    coords = {}
    for w in ways:
        tags = w.get("tags", {})
        nodes, geom = list(w["nodes"]), [(g["lat"], g["lon"]) for g in w["geometry"]]
        oneway = tags.get("oneway", "yes")
        if oneway == "-1":
            nodes.reverse(), geom.reverse()
        cuts = [i for i, n in enumerate(nodes) if i in (0, len(nodes) - 1) or touches[n] > 1]
        for a, b in zip(cuts, cuts[1:]):
            for n, p in ((nodes[a], geom[a]), (nodes[b], geom[b])):
                coords[n] = p
            item = {"u": nodes[a], "v": nodes[b], "pts": geom[a:b + 1], "name": tags.get("name", ""), "link": tags.get("highway") == "motorway_link"}
            edges.append(item)
            if oneway == "no":
                edges.append({**item, "u": nodes[b], "v": nodes[a], "pts": geom[a:b + 1][::-1]})
    for e in edges:
        e["len"] = path_len(e["pts"])
    return edges, coords


def connect_ends(edges, coords):
    """
    고속도로가 끝나는 곳은 일반도로로 이어져 도로망에 없다. 끝에서 반대 차로로 돌아오는 짧은 연결을 넣는다.
    막다른 끝마다 가까운 시작점으로 잇고, 들어올 길이 없는 시작점마다 가까운 막다른 끝에서 잇는다.
    한쪽만 이으면 막다른 끝이 램프 시작점에 이어지고 반대 차로 시작점은 들어올 길이 없어,
    그 구간 전체가 오갈 수 없는 도로로 버려진다(경부선 양재 부근, 남해선 영암~순천이 그랬다).
    """
    out, inc = defaultdict(int), defaultdict(int)
    for e in edges:
        out[e["u"]] += 1
        inc[e["v"]] += 1
    dead_ends = [n for n in inc if out[n] == 0]
    starts = [n for n in out if inc[n] == 0]
    pairs = set()
    for d in dead_ends:
        near = min(starts, key=lambda n: dist_m(coords[d], coords[n]), default=None)
        if near is not None and dist_m(coords[d], coords[near]) < MAX_END_GAP_M:
            pairs.add((d, near))
    for s in starts:
        near = min(dead_ends, key=lambda n: dist_m(coords[n], coords[s]), default=None)
        if near is not None and dist_m(coords[near], coords[s]) < MAX_END_GAP_M:
            pairs.add((near, s))
    added = []
    for d, s in pairs:
        pts = [coords[d], coords[s]]
        added.append({"u": d, "v": s, "pts": pts, "name": "", "link": True, "len": path_len(pts)})
    return edges + added


def bridge_islands(edges, coords, rounds=40):
    """
    서로 오갈 수 없어 버려질 고속도로 덩어리를 큰 도로망에 잇는다.
    분기점이 한쪽 방향만 이어져 있거나(경부선 서울 부근, 수도권제2순환선 일부),
    잇는 길이 일반도로로 그려져 있으면(남해선 영암~순천) 덩어리째 떨어진다.
    덩어리 안에서도 한 방향으로만 이어진 곳이 있어 한 번에 다 붙지 않으므로, 큰 도로망이 더 자라지 않을 때까지 되풀이한다.
    가까운 곳(BRIDGE_TOUCH_M 안)은 모두, 그 밖에는 가장 가까운 한 곳을 양방향 연결로 잇는다.
    """
    for _ in range(rounds):
        main_edges = largest_component(edges)
        main = {e["u"] for e in main_edges} | {e["v"] for e in main_edges}
        neighbours = defaultdict(set)
        for e in edges:
            neighbours[e["u"]].add(e["v"])
            neighbours[e["v"]].add(e["u"])
        grid = defaultdict(list)
        for n in main:
            grid[(int(coords[n][0] / GRID), int(coords[n][1] / GRID))].append(n)

        def near_main(n, radius_cells):
            cx, cy = int(coords[n][0] / GRID), int(coords[n][1] / GRID)
            found = []
            for dx in range(-radius_cells, radius_cells + 1):
                for dy in range(-radius_cells, radius_cells + 1):
                    found += grid.get((cx + dx, cy + dy), [])
            return found

        seen = set()
        added = []
        for start in list(neighbours):
            if start in main or start in seen:
                continue
            island, stack = set(), [start]
            seen.add(start)
            while stack:
                n = stack.pop()
                island.add(n)
                for m in neighbours[n]:
                    if m not in seen and m not in main:
                        seen.add(m)
                        stack.append(m)
            km = sum(e["len"] for e in edges if e["u"] in island) / 1000
            if km < MIN_ISLAND_KM:
                continue
            pairs = []
            for a in island:
                for b in near_main(a, 5):
                    pairs.append((dist_m(coords[a], coords[b]), a, b))
            if not pairs:
                continue
            pairs.sort()
            if pairs[0][0] > MAX_BRIDGE_M:
                continue
            chosen = [p for p in pairs if p[0] <= BRIDGE_TOUCH_M] or pairs[:1]
            for _, a, b in chosen:
                for u, v in ((a, b), (b, a)):
                    pts = [coords[u], coords[v]]
                    added.append({"u": u, "v": v, "pts": pts, "name": "", "link": True, "len": path_len(pts)})
            print(f"떨어진 덩어리 {km:.0f}km를 {len(chosen)}곳에서 이음(가장 가까운 {pairs[0][0]:.0f}m)", file=sys.stderr)
        if not added:
            break
        edges = edges + added
    return edges


def contract(edges):
    """들어오는 길과 나가는 길이 하나씩인 지점을 없애 파일을 줄인다."""
    out, inc = defaultdict(list), defaultdict(list)
    for e in edges:
        out[e["u"]].append(e)
        inc[e["v"]].append(e)

    def passthrough(n):
        return len(out[n]) == 1 and len(inc[n]) == 1 and out[n][0]["link"] == inc[n][0]["link"]

    result = []
    for start in [e for e in edges if not passthrough(e["u"])]:
        e = dict(start)
        while passthrough(e["v"]):
            n = out[e["v"]][0]
            e = {**e, "v": n["v"], "pts": e["pts"] + n["pts"][1:], "len": e["len"] + n["len"]}
        result.append(e)
    return result


def largest_component(edges):
    """서로 오갈 수 있는 가장 큰 덩어리만 남긴다(Kosaraju)."""
    out, inc = defaultdict(list), defaultdict(list)
    for e in edges:
        out[e["u"]].append(e["v"])
        inc[e["v"]].append(e["u"])
    order, seen = [], set()
    for root in list(out):
        if root in seen:
            continue
        stack = [(root, iter(out[root]))]
        seen.add(root)
        while stack:
            node, it = stack[-1]
            for nxt in it:
                if nxt not in seen:
                    seen.add(nxt)
                    stack.append((nxt, iter(out[nxt])))
                    break
            else:
                order.append(node)
                stack.pop()
    comp = {}
    for root in reversed(order):
        if root in comp:
            continue
        comp[root] = root
        stack = [root]
        while stack:
            for nxt in inc[stack.pop()]:
                if nxt not in comp:
                    comp[nxt] = root
                    stack.append(nxt)
    sizes = defaultdict(int)
    for c in comp.values():
        sizes[c] += 1
    main = max(sizes, key=sizes.get)
    keep = {n for n, c in comp.items() if c == main}
    return [e for e in edges if e["u"] in keep and e["v"] in keep]


def attach(edges, services):
    grid = defaultdict(list)
    for i, e in enumerate(edges):
        if e["link"]:
            continue
        along = 0.0
        for p, q in zip(e["pts"], e["pts"][1:]):
            seg = dist_m(p, q)
            for t in range(0, int(seg // 400) + 2):
                f = min(1.0, t * 400 / seg) if seg else 0
                grid[(int((p[0] + (q[0] - p[0]) * f) / GRID), int((p[1] + (q[1] - p[1]) * f) / GRID))].append((i, p, q, along, seg))
            along += seg

    found = []
    for s in services:
        name = s.get("tags", {}).get("name", "")
        if not NAME_RE.search(name) or NOT_REST_AREA.search(name):
            continue
        lat = s.get("lat", s.get("center", {}).get("lat"))
        lon = s.get("lon", s.get("center", {}).get("lon"))
        if lat is None:
            continue
        cx, cy = int(lat / GRID), int(lon / GRID)
        best = None
        for dx in range(-1, 2):
            for dy in range(-1, 2):
                for i, p, q, along, seg in grid.get((cx + dx, cy + dy), ()):
                    if seg == 0:
                        continue
                    cos_lat = math.cos(math.radians(p[0]))
                    ax, ay = (q[1] - p[1]) * cos_lat, q[0] - p[0]
                    bx, by = (lon - p[1]) * cos_lat, lat - p[0]
                    t = max(0.0, min(1.0, (ax * bx + ay * by) / (ax * ax + ay * ay)))
                    off = dist_m((lat, lon), (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t))
                    if best is None or off < best[0]:
                        best = (off, i, along + seg * t)
        if best is None or best[0] > MAX_OFFSET_M:
            continue
        found.append({"name": base_name(name), "raw": name, "off": best[0], "edge": best[1], "at": best[2], "lat": lat, "lon": lon})

    # 같은 휴게소가 점과 면으로 두 번 그려진 경우가 있다. 같은 이름이 같은 구간 가까이 있으면 도로에 가까운 하나만 남긴다.
    found.sort(key=lambda r: r["off"])
    kept = []
    for r in found:
        twin = any(k["name"] == r["name"] and k["edge"] == r["edge"] and abs(k["at"] - r["at"]) < DUPLICATE_WITHIN_M for k in kept)
        if not twin:
            kept.append(r)
    # 이름이 다르게 적힌 같은 자리(동서울만남의광장·하남드림 등)는 짧은 이름 하나로 줄인다.
    kept.sort(key=lambda r: (len(r["name"]), r["off"]))
    unique = []
    for r in kept:
        if not any(k["edge"] == r["edge"] and abs(k["at"] - r["at"]) < SAME_PLACE_WITHIN_M for k in unique):
            unique.append(r)
    return unique


FACILITY_LABELS = [
    ("fuel", "주유소"), ("charging_station", "전기차 충전"), ("restaurant", "식당"), ("food_court", "식당"),
    ("fast_food", "식당"), ("cafe", "카페"), ("convenience", "편의점"), ("toilets", "화장실"), ("pharmacy", "약국"),
]
FACILITY_WITHIN_M = 250


def attach_facilities(areas, path):
    """시설마다 가장 가까운 휴게소 하나에만 붙인다. 맞은편 휴게소로 번지는 걸 막는다."""
    labels = dict(FACILITY_LABELS)
    order = [label for _, label in FACILITY_LABELS]
    for r in areas:
        r["facilities"] = set()
    for e in json.load(open(path))["elements"]:
        tags = e.get("tags", {})
        label = labels.get(tags.get("amenity")) or labels.get(tags.get("shop"))
        lat = e.get("lat", e.get("center", {}).get("lat"))
        lon = e.get("lon", e.get("center", {}).get("lon"))
        if label is None or lat is None:
            continue
        best = min(areas, key=lambda r: dist_m((r["lat"], r["lon"]), (lat, lon)))
        if dist_m((best["lat"], best["lon"]), (lat, lon)) <= FACILITY_WITHIN_M:
            best["facilities"].add(label)
    for r in areas:
        r["facilities"] = [label for label in dict.fromkeys(order) if label in r["facilities"]]
    print(f"시설 있는 휴게소 {sum(1 for r in areas if r['facilities'])}/{len(areas)}곳", file=sys.stderr)


def simplify(pts, tolerance_m):
    """더글러스-포이커. 지도 미리보기에는 이 정도면 충분하고 파일이 크게 줄어든다."""
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        best, index = 0.0, -1
        ax, ay = pts[a][1], pts[a][0]
        bx, by = pts[b][1], pts[b][0]
        cos_lat = math.cos(math.radians(ay))
        dx, dy = (bx - ax) * cos_lat, by - ay
        norm = math.hypot(dx, dy) or 1e-12
        for i in range(a + 1, b):
            px, py = (pts[i][1] - ax) * cos_lat, pts[i][0] - ay
            d = abs(dx * py - dy * px) / norm * 111320
            if d > best:
                best, index = d, i
        if best > tolerance_m:
            keep[index] = True
            stack += [(a, index), (index, b)]
    return [p for p, k in zip(pts, keep) if k]


def coast_lines(path):
    lines = []
    for feature in json.load(open(path))["features"]:
        geometry = feature["geometry"]
        parts = geometry["coordinates"] if geometry["type"] == "MultiLineString" else [geometry["coordinates"]]
        for part in parts:
            current = []
            for lng, lat in part:
                if 32.7 <= lat <= 39.2 and 124.0 <= lng <= 131.5:
                    current.append((lat, lng))
                else:
                    if len(current) > 1:
                        lines.append(current)
                    current = []
            if len(current) > 1:
                lines.append(current)
    return [simplify(line, 500) for line in lines]


def dijkstra(adj, src, dst):
    dist, prev = {src: 0.0}, {}
    heap = [(0.0, src)]
    while heap:
        d, u = heapq.heappop(heap)
        if u == dst:
            break
        if d > dist[u]:
            continue
        for i, v, w in adj[u]:
            if d + w < dist.get(v, 1e18):
                dist[v] = d + w
                prev[v] = (u, i)
                heapq.heappush(heap, (d + w, v))
    path = []
    node = dst
    while node != src:
        node, i = prev[node]
        path.append(i)
    return path[::-1]


def main():
    ways = [w for w in json.load(open(sys.argv[1]))["elements"] if "geometry" in w]
    services = json.load(open(sys.argv[2]))["elements"]
    edges, coords = build(ways)
    print(f"way {len(ways)}, 구간 {len(edges)}", file=sys.stderr)
    edges = contract(largest_component(bridge_islands(connect_ends(edges, coords), coords)))
    print(f"압축 뒤 구간 {len(edges)}", file=sys.stderr)
    areas = attach(edges, services)
    print(f"휴게소 {len(areas)}곳", file=sys.stderr)
    if len(sys.argv) > 5:
        attach_facilities(areas, sys.argv[5])

    vertices = sorted({e["u"] for e in edges} | {e["v"] for e in edges})
    index = {n: i for i, n in enumerate(vertices)}
    points = [coords[n] for n in vertices]
    adj = defaultdict(list)
    for i, e in enumerate(edges):
        adj[index[e["u"]]].append((i, index[e["v"]], e["len"] * (1.3 if e["link"] else 1.0)))
    by_edge = defaultdict(list)
    for r in areas:
        by_edge[r["edge"]].append(r)

    cities = {}
    for part in CITIES.replace("\n", "|").split("|"):
        n, lat, lng = part.split()
        cities[n] = (float(lat), float(lng))

    def snap(p):
        return min(range(len(points)), key=lambda i: dist_m(points[i], p))

    for a, b in [("서울", "부산"), ("부산", "서울"), ("서울", "강릉"), ("서울", "목포"), ("광주", "부산"), ("대전", "속초"), ("인천", "강릉"), ("서울", "여수")]:
        path = dijkstra(adj, snap(cities[a]), snap(cities[b]))
        km = 0.0
        stops = []
        for i in path:
            for r in sorted(by_edge[i], key=lambda r: r["at"]):
                stops.append(f"{r['name'].replace('휴게소', '')}({(km + r['at']) / 1000:.0f})")
            km += edges[i]["len"]
        print(f"{a}→{b} {km / 1000:.0f}km 휴게소 {len(stops)}: {' '.join(stops)}", file=sys.stderr)

    if len(sys.argv) > 3:
        with open(sys.argv[3], "w") as f:
            for lat, lng in points:
                f.write(f"V\t{lat:.5f}\t{lng:.5f}\n")
            for e in edges:
                f.write(f"E\t{index[e['u']]}\t{index[e['v']]}\t{e['len']:.0f}\t{'L' if e['link'] else 'M'}\n")
            for r in areas:
                f.write(f"R\t{r['edge']}\t{r['at']:.0f}\t{r['name']}\t{r['lat']:.5f}\t{r['lon']:.5f}\t{','.join(r.get('facilities', []))}\n")
            for n, (lat, lng) in cities.items():
                f.write(f"P\t{n}\t{lat}\t{lng}\n")
            for i, e in enumerate(edges):
                shape = simplify(e["pts"], 120)
                f.write(f"G\t{i}\t" + " ".join(f"{lat:.4f},{lng:.4f}" for lat, lng in shape) + "\n")
            if len(sys.argv) > 4:
                for line in coast_lines(sys.argv[4]):
                    f.write("C\t" + " ".join(f"{lat:.3f},{lng:.3f}" for lat, lng in line) + "\n")


if __name__ == "__main__":
    main()
