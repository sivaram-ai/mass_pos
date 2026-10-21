"""Generates the login screen's circuit-board background as a static SVG.

Brand colours sampled from the company logo. Traces run at 45 degrees with right-angle
turns and end in a node, the way the rocket's own circuitry is drawn. The seed is fixed,
so running this again reproduces the same board exactly.

    python tools/make-login-bg.py frontend/public/login-bg.svg
"""
import random
import sys

W, H = 1600, 900
BLUE, ORANGE, GREEN, RED = "#1a59d3", "#fe6313", "#0a8c4b", "#c3142c"
BRIGHT = [BLUE, ORANGE, GREEN, RED]
FAINT = "#1d4e93"

rnd = random.Random(20261021)


def trace(x, y, dx, dy, steps):
    """A circuit run: diagonal, then straight, then diagonal again."""
    points = [(x, y)]
    for i in range(steps):
        if i % 2 == 0:
            d = rnd.choice((70, 100, 130, 170))
            x, y = x + d * dx, y + d * dy
        else:
            d = rnd.choice((50, 80, 110, 150))
            if rnd.random() < 0.55:
                x += d * dx
            else:
                y += d * dy
        points.append((x, y))
    return points


def polyline(points, colour, width, opacity):
    pts = " ".join(f"{x:.0f},{y:.0f}" for x, y in points)
    return (f'<polyline points="{pts}" fill="none" stroke="{colour}" stroke-width="{width}" '
            f'stroke-opacity="{opacity}" stroke-linecap="round" stroke-linejoin="round"/>')


def node(point, colour, r, opacity):
    x, y = point
    return (f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{r}" fill="none" stroke="{colour}" '
            f'stroke-width="{max(2, r // 2)}" stroke-opacity="{opacity}"/>')


parts = []

# Faint wiring over the whole board, so the bright runs have something to sit on.
for _ in range(34):
    from_left = rnd.random() < 0.5
    x = rnd.randint(-80, 420) if from_left else rnd.randint(W - 420, W + 80)
    y = rnd.randint(-60, H + 60)
    points = trace(x, y, 1 if from_left else -1, rnd.choice((1, -1)), rnd.randint(3, 6))
    parts.append(polyline(points, FAINT, rnd.choice((2, 3)), 0.55))
    parts.append(node(points[-1], FAINT, 5, 0.55))

# The bright runs, kept to the two sides.
for i in range(22):
    from_left = i % 2 == 0
    x = rnd.randint(-120, 300) if from_left else rnd.randint(W - 300, W + 120)
    y = rnd.randint(-80, H + 80)
    colour = BRIGHT[i % 4]
    points = trace(x, y, 1 if from_left else -1, rnd.choice((1, -1)), rnd.randint(3, 5))
    parts.append(polyline(points, colour, rnd.choice((4, 5, 6)), 0.9))
    parts.append(node(points[-1], colour, rnd.choice((6, 8)), 0.95))

# Broad ribbons at the corners, as on the brand's own artwork.
for colour, (x, y), sign in (
        (BLUE, (-120, 120), 1), (ORANGE, (-60, 300), 1), (GREEN, (60, -120), 1),
        (RED, (W + 120, 200), -1), (BLUE, (W + 60, 520), -1), (ORANGE, (W - 40, -80), -1)):
    parts.append(polyline([(x, y), (x + sign * 260, y + 260), (x + sign * 260, y + 420),
                           (x + sign * 420, y + 580)], colour, 16, 0.5))

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" preserveAspectRatio="xMidYMid slice">
  <defs>
    <linearGradient id="night" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#0b1f3f"/>
      <stop offset="0.55" stop-color="#081830"/>
      <stop offset="1" stop-color="#050f20"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.5" cy="0.45" r="0.6">
      <stop offset="0" stop-color="#1b4b8c" stop-opacity="0.45"/>
      <stop offset="1" stop-color="#1b4b8c" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="{W}" height="{H}" fill="url(#night)"/>
  <rect width="{W}" height="{H}" fill="url(#glow)"/>
  {chr(10).join("  " + part for part in parts)}
</svg>
'''

with open(sys.argv[1], "w", encoding="utf-8") as out:
    out.write(svg)
print(f"wrote {sys.argv[1]}: {len(parts)} shapes")
