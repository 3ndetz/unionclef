"""Contact sheet with the source time stamped on each tile: sheet.py <video> <out.png> [fps] [start] [dur]

Tiles are the first frame of every 1/fps interval, stamped with its real time in the source.
"""
import subprocess
import sys
from pathlib import Path

v, o = sys.argv[1], sys.argv[2]
f, s, d = (sys.argv[3:] + ["1", "0", "60"][len(sys.argv[3:]):])[:3]
step = 1 / float(f) - 0.001
vf = (rf"select='isnan(prev_selected_t)+gte(t-prev_selected_t\,{step:.3f})',scale=384:-1,"
      rf"drawtext=fontfile=arial.ttf:text='%{{pts\:hms\:{s}}}':x=6:y=6:"
      r"fontsize=22:fontcolor=yellow:box=1:boxcolor=black@0.7,tile=6x6")
subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", s, "-t", d, "-i", str(Path(v).resolve()), "-vf", vf,
                "-vsync", "vfr", "-frames:v", "1", str(Path(o).resolve())],
               check=True, cwd=Path(__file__).parent / "work")
