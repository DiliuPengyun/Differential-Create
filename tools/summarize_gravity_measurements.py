"""Summarize native GameTest entity samples; acceleration is derived from positions.

Run after gradlew runGravityServer -PgravitySelect=measure.*.
Python's standard library is enough for JSON/CSV output; --plot needs matplotlib.
"""
import argparse
import csv
import json
import math
from pathlib import Path


def quadratic_acceleration(rows, field="drop_m", start=.2, end=.8):
    """Least-squares quadratic on a symmetric, equally spaced time window."""
    points = [(r["time_s"], r[field]) for r in rows if start - 1e-9 <= r["time_s"] <= end + 1e-9]
    centre = sum(t for t, _ in points) / len(points)
    squares = [(t - centre) ** 2 for t, _ in points]
    mean_square = sum(squares) / len(squares)
    return 2 * sum((s - mean_square) * y for s, (_, y) in zip(squares, points)) / sum((s - mean_square) ** 2 for s in squares)


def read_samples(path):
    with path.open(encoding="utf-8") as stream:
        rows = [{k: (v == "true" if k == "root_removed" else float(v)) for k, v in row.items()} for row in csv.DictReader(stream)]
    if [r["tick"] for r in rows] != list(range(121)):
        raise ValueError(f"{path}: expected one sample per tick, from 0 through 120")
    if any(r["root_removed"] or any(not math.isfinite(v) for v in r.values()) for r in rows):
        raise ValueError(f"{path}: removed entity or nonfinite sample")
    return rows


def summarize(name, rows):
    drop = [r["drop_m"] for r in rows]
    interval_velocity = [(b - a) * 20 for a, b in zip(drop, drop[1:])]
    acceleration = [(b - a) * 20 for a, b in zip(interval_velocity, interval_velocity[1:])]
    result = dict(
        case=name, blocks=int(rows[0]["blocks"]), area_y_m2=rows[0]["area_y_m2"],
        acceleration_fit_02_08_m_s2=quadratic_acceleration(rows),
        velocity_1s_m_s=(drop[21] - drop[19]) * 10,
        velocity_last_interval_m_s=interval_velocity[-1],
        drop_1s_m=drop[20], drop_6s_m=drop[-1], maximum_drop_m=max(drop),
        raw_tick_acceleration_min_m_s2=min(acceleration), raw_tick_acceleration_max_m_s2=max(acceleration),
        first_visible_motion_s=next((r["time_s"] for r in rows if abs(r["drop_m"]) > 1e-8), None),
        first_98_m_s_interval_end_s=next(((i + 1) / 20 for i, v in enumerate(interval_velocity) if v >= 9.8 - 1e-5), None),
        max_output_rpm=max(abs(r["output_rpm"]) for r in rows))
    if "bearing_3" in name:
        # These two fixtures start with their COM one metre south of an X axis.
        pivot_z = rows[0]["centre_z_m"] - 1
        for row in rows:
            row["angle_rad"] = math.atan2(row["drop_m"], row["centre_z_m"] - pivot_z)
        result["angular_acceleration_fit_02_08_rad_s2"] = quadratic_acceleration(rows, "angle_rad")
        # Ignore small shaft oscillations: detect a sustained reversal of the entity pose.
        angular_velocity = [(rows[i+2]["angle_rad"] - rows[i-2]["angle_rad"]) / .2 for i in range(2, len(rows)-2)]
        forward = False
        result["first_visible_reversal_s"] = None
        for i in range(len(angular_velocity)-2):
            forward |= angular_velocity[i] > .001
            if forward and all(v < -.001 for v in angular_velocity[i:i+3]):
                result["first_visible_reversal_s"] = rows[i+2]["time_s"]
                break
    return result


def plot(series, destination, date):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({"font.family": "Microsoft YaHei", "axes.unicode_minus": False, "font.size": 11})
    fig, axes = plt.subplots(2, 2, figsize=(13, 8), layout="constrained")
    labels = {"measure_pulley_2": "滑轮 · 2 块", "measure_pulley_16_flat": "滑轮 · 16 块平板",
              "measure_pulley_64": "滑轮 · 64 块", "measure_pulley_64_flywheel": "滑轮 · 64 块＋飞轮",
              "measure_gantry_3": "起重机 · 3 块", "measure_gantry_65_flywheel": "起重机 · 65 块＋飞轮"}
    for name, label in labels.items():
        rows = series[name]; t = [r["time_s"] for r in rows]; d = [r["drop_m"] for r in rows]
        axes[0, 0].plot([(a + b) / 2 for a, b in zip(t, t[1:])], [(b-a)*20 for a,b in zip(d,d[1:])], label=label)
        # Moving 0.5-second position fit, separate from raw one-tick finite differences.
        centres = list(range(5, 116, 2))
        axes[0, 1].plot([t[i] for i in centres], [quadratic_acceleration(rows, start=t[i-5], end=t[i+5]) for i in centres], label=label)
    axes[0, 0].axhline(9.8, color="#777777", ls="--", lw=1)
    axes[0, 0].set(title="真实实体下落速度", ylabel="向下速度（m/s）")
    axes[0, 0].legend(fontsize=9, loc="upper left")
    axes[0, 1].set(title="运动阶段加速度（0.5 秒窗口拟合）", ylabel="向下加速度（m/s²）", xlim=(.4, 5.7))
    for name, label in [("measure_bearing_3", "偏心轴承 · 3 块"), ("measure_bearing_3_flywheel", "偏心轴承 · 3 块＋飞轮")]:
        rows = series[name]; axes[1, 0].plot([r["time_s"] for r in rows], [r["drop_m"] for r in rows], label=label)
    axes[1, 0].set(title="偏心轴承重心：下摆后回升", ylabel="相对初始位置下降（m）")
    axes[1, 0].legend(fontsize=9)
    rows = series["measure_gantry_3"][:10]
    axes[1, 1].plot([r["time_s"] for r in rows], [r["drop_m"] for r in rows], "o-", label="实体实际位移")
    axes[1, 1].plot([r["time_s"] for r in rows], [.5*9.81*r["time_s"]**2 for r in rows], "--", label="理想自由落体参考")
    axes[1, 1].set(title="起重机装配初期：前两 tick 位移为零", ylabel="下降距离（m）")
    axes[1, 1].legend(fontsize=9)
    for ax in axes.flat:
        ax.set_xlabel("游戏时间（s；20 tick = 1 s）"); ax.grid(alpha=.2)
    fig.suptitle("微分动力 · 游戏内重力实测" + (f"（{date}）" if date else ""), fontsize=17)
    fig.savefig(destination, dpi=160)
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("build/gravity-measurement/samples"))
    parser.add_argument("--output", type=Path, default=Path("build/gravity-measurement"))
    parser.add_argument("--plot", action="store_true")
    parser.add_argument("--date", help="Optional measurement date shown in the figure title")
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    series = {p.stem: read_samples(p) for p in sorted(args.input.glob("measure_*.csv"))}
    if not series: raise SystemExit("No measurement CSV files found")
    summary = [summarize(name, rows) for name, rows in series.items()]
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    columns = list(dict.fromkeys(key for record in summary for key in record))
    with (args.output / "summary.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns); writer.writeheader(); writer.writerows(summary)
    for row in summary:
        print(f"{row['case']}: a={row['acceleration_fit_02_08_m_s2']:.6f} m/s², "
              f"v(1s)={row['velocity_1s_m_s']:.6f} m/s, drop(6s)={row['drop_6s_m']:.6f} m")
    if args.plot: plot(series, args.output / "acceleration.png", args.date)


if __name__ == "__main__":
    main()
