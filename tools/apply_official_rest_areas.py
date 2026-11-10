#!/usr/bin/env python3
"""expressway.tsv의 휴게소 이름·편의시설을 한국도로공사 공식 휴게소 정보로 맞춘다.

  python3 tools/apply_official_rest_areas.py <한국도로공사_휴게소정보.csv> [expressway.tsv]
"""
import csv
import math
import re
import sys
from collections import defaultdict

MATCH_WITHIN_M = 1000
MAX_OFFSET_M = 300

FACILITY_COLUMNS = [
    ("주유소유무", "주유소"), ("LPG충전소유무", "LPG 충전"), ("전기차충전소유무", "전기차 충전"),
    ("음식점유무", "식당"), ("매점유무", "편의점"), ("화장실유무", "화장실"), ("약국유무", "약국"),
]


def dist_m(a, b):
    mean = math.radians((a[0] + b[0]) / 2)
    return math.hypot((a[0] - b[0]) * 111320, (a[1] - b[1]) * 111320 * math.cos(mean))


def official_name(raw):
    return re.sub(r"\(.*\)", "", raw).strip() + "휴게소"


def facilities(row):
    return [label for column, label in FACILITY_COLUMNS if row.get(column, "").strip().upper() == "Y"]


def project(point, pts):
    """선 위에서 [point]에 가장 가까운 곳까지의 (거리, 처음부터 잰 길이)."""
    best = None
    along = 0.0
    for p, q in zip(pts, pts[1:]):
        seg = dist_m(p, q)
        if seg > 0:
            cos_lat = math.cos(math.radians(p[0]))
            ax, ay = (q[1] - p[1]) * cos_lat, q[0] - p[0]
            bx, by = (point[1] - p[1]) * cos_lat, point[0] - p[0]
            t = max(0.0, min(1.0, (ax * bx + ay * by) / (ax * ax + ay * ay)))
            off = dist_m(point, (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t))
            if best is None or off < best[0]:
                best = (off, along + seg * t)
        along += seg
    return best


def main():
    csv_path = sys.argv[1]
    tsv_path = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/resources/expressway.tsv"
    every = list(csv.DictReader(open(csv_path, encoding="utf-8-sig")))
    official = [r for r in every if r["휴게소종류"] != "화물차휴게소"]
    trucks = [r for r in every if r["휴게소종류"] == "화물차휴게소"]
    lines = open(tsv_path, encoding="utf-8").read().split("\n")

    ramp = {}
    shapes = {}
    edge_count = 0
    for line in lines:
        f = line.split("\t")
        if f[0] == "E":
            ramp[edge_count] = f[4] == "L"
            edge_count += 1
        elif f[0] == "G":
            shapes[int(f[1])] = [tuple(map(float, p.split(","))) for p in f[2].split(" ")]

    boxes = {e: (min(p[0] for p in pts), max(p[0] for p in pts), min(p[1] for p in pts), max(p[1] for p in pts)) for e, pts in shapes.items()}
    rest_rows = [i for i, line in enumerate(lines) if line.startswith("R\t")]
    # 일반 휴게소와 짝지을 때는 화물차 휴게소 줄(끝이 T)을 쓰지 않는다. 다시 돌려도 이름이 섞이지 않게.
    general_rows = [i for i in rest_rows if not lines[i].rstrip().endswith("\tT")]
    new_rows = []
    taken = set()

    def nearest_edge(at, used_edges):
        """가장 가까운 본선 구간(램프 제외, MAX_OFFSET_M 안)과 그 구간 처음부터의 거리. 없으면 None."""
        candidates = []
        for edge, pts in shapes.items():
            if ramp.get(edge, True) or edge in used_edges:
                continue
            lo_lat, hi_lat, lo_lng, hi_lng = boxes[edge]
            if not (lo_lat - 0.005 <= at[0] <= hi_lat + 0.005 and lo_lng - 0.005 <= at[1] <= hi_lng + 0.005):
                continue
            hit = project(at, pts)
            if hit and hit[0] <= MAX_OFFSET_M:
                candidates.append((hit[0], edge, hit[1]))
        if not candidates:
            return None
        _, edge, along = min(candidates)
        return edge, along

    renamed, added, skipped = [], [], []
    for row in official:
        at = (float(row["위도"]), float(row["경도"]))
        name = official_name(row["휴게소명"])
        near = [(dist_m(at, (float(lines[i].split("\t")[4]), float(lines[i].split("\t")[5]))), i) for i in general_rows if i not in taken]
        near = [x for x in near if x[0] < MATCH_WITHIN_M]
        if near:
            _, i = min(near)
            taken.add(i)
            f = lines[i].split("\t")
            if f[3] != name:
                renamed.append((f[3], name))
            f[3] = name
            while len(f) < 7:
                f.append("")
            f[6] = ",".join(facilities(row)) or f[6]
            lines[i] = "\t".join(f)
            continue
        # 기존 자료에 없다. 가까운 본선 구간에 붙인다. 같은 자리에 이미 붙인 구간은 피한다(두 방향 좌표가 같은 곳).
        nearby = [lines[i] for i in rest_rows] + new_rows
        used_edges = {int(r.split("\t")[1]) for r in nearby if dist_m(at, (float(r.split("\t")[4]), float(r.split("\t")[5]))) < 600}
        hit = nearest_edge(at, used_edges)
        if hit is None:
            skipped.append(row["휴게소명"])
            continue
        edge, along = hit
        new_rows.append(f"R\t{edge}\t{along:.0f}\t{name}\t{at[0]:.5f}\t{at[1]:.5f}\t{','.join(facilities(row))}")
        added.append(f"{row['휴게소명']} → 구간 {edge}")

    # 공식 휴게소와 짝이 안 된 반대 방향 줄도 같은 휴게소다. 이름에 공식 이름이 들어 있고 1km 안이면 공식 이름으로 맞춘다.
    # ("바이오시스와촌휴게소"처럼 후원사 이름이 붙었거나 "문산소문리휴게소"처럼 동네 이름이 붙은 경우)
    for i in general_rows:
        if i in taken:
            continue
        f = lines[i].split("\t")
        here = (float(f[4]), float(f[5]))
        for row in official:
            base = official_name(row["휴게소명"])[:-3]
            if base in f[3] and f[3] != base + "휴게소" and dist_m(here, (float(row["위도"]), float(row["경도"]))) < MATCH_WITHIN_M:
                renamed.append((f[3], base + "휴게소"))
                f[3] = base + "휴게소"
                lines[i] = "\t".join(f)
                break

    # 화물차 휴게소. 이미 넣은 화물차 휴게소(다시 돌린 경우)는 건너뛴다.
    existing_trucks = [lines[i].split("\t")[3] for i in rest_rows if lines[i].rstrip().endswith("\tT")]
    for row in trucks:
        name = official_name(row["휴게소명"])[:-3] + "화물차휴게소"
        at = (float(row["위도"]), float(row["경도"]))
        copies = 2 if row["도로노선방향"] == "양방향" else 1
        if existing_trucks.count(name) >= copies:
            continue
        for _ in range(copies):
            nearby = [r for r in new_rows if r.endswith("\tT")] + [lines[i] for i in rest_rows if lines[i].rstrip().endswith("\tT")]
            used_edges = {int(r.split("\t")[1]) for r in nearby if dist_m(at, (float(r.split("\t")[4]), float(r.split("\t")[5]))) < 600}
            hit = nearest_edge(at, used_edges)
            if hit is None:
                skipped.append(row["휴게소명"] + " 화물차")
                break
            edge, along = hit
            new_rows.append(f"R\t{edge}\t{along:.0f}\t{name}\t{at[0]:.5f}\t{at[1]:.5f}\t{','.join(facilities(row))}\tT")
            added.append(f"{row['휴게소명']} 화물차 → 구간 {edge}")

    # 새 휴게소 줄은 기존 R 줄 바로 뒤에 넣는다. 다른 줄의 순서는 그대로 둔다.
    last_r = max(rest_rows)
    lines[last_r + 1:last_r + 1] = new_rows
    open(tsv_path, "w", encoding="utf-8").write("\n".join(lines))

    print(f"이름 바꿈 {len(renamed)}곳")
    for old, new in sorted(set(renamed)):
        print(f"  {old} → {new}")
    print(f"새로 넣음 {len(added)}곳")
    for line in added:
        print(f"  {line}")
    print(f"붙일 구간을 못 찾음 {len(skipped)}곳: {', '.join(skipped)}")


if __name__ == "__main__":
    main()
