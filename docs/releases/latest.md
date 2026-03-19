## What's new

### Tungsten pathfinder: parallel child generation

Node generation in A* search now runs in parallel via `parallelStream`.
Each child node's physics simulation (`Agent.tick`) is independent, so this
is an embarrassingly parallel workload.

#### Benchmarks (same route, same start position)

| raw children | Before (sequential) | After (parallel) | Speedup |
|---|---|---|---|
| 193 | 8.8 ms | 1.7 ms | 5.2x |
| 145 | 6.3 ms | 1.1 ms | 5.7x |
| 127 | 5.9 ms | 0.9 ms | 6.6x |

`getChildren` accounts for 95%+ of search time, so overall path search
is roughly 5-6x faster.

#### Profiling support

New setting: `;settings debugTime true` — logs per-node timing breakdown
to stdout (getChildren, filter, openSet+update).
