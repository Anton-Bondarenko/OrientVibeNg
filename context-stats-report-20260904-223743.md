# Token Usage Analytics Report

Generated: 2026-09-04 22:37:43
Period: 2026-09-04 → 2026-09-04
Source: context-stats v1.25.0

## Executive Summary

| Metric | Value |
|--------|-------|
| Report Period | 2026-09-04 → 2026-09-04 |
| Total Spend | $58.46 |
| Total Sessions | 2 |
| Projects Analyzed | 1 |
| Cache Hit Ratio | 3.0% |
| Avg Session Cost | $29.23 |
| Avg Session Duration | 5h 25m 7s |
| Most Expensive Session | 4705f5ef... ($37.65, 64.4% of total) |
| Most Expensive Project | E:\git\orientvibeNg ($58.46, 100.0% of total) |

## Model Usage Breakdown

```mermaid
pie title Model Cost Distribution
    "Opus" : 20.81
    "Other" : 37.65
```

| Model Family | Sessions | Total Tokens | Cost | % of Total Cost |
|---|---|---|---|---|
| opus | 1 | 4,880 | $20.81 | 35.6% |
| other | 1 | 64,333 | $37.65 | 64.4% |

## Cost Optimization Analysis

### Key Findings

- **Real Sessions**: 2 sessions costing $58.46
- **Cache Hit Ratio**: 3.0% (room for improvement if <70%)

- **Cost per 1k tokens**: $0.845

### Top Cost Drivers (Top 10 Sessions)
| Session | Project | Cost | Cache % | Duration | Input | Output |
|---------|---------|------|---------|----------|-------|--------|
| 4705f5ef... | E:\git\orientvibeNg | $37.65 | 0% | 8h 6m 44s | 64,333 | 0 |
| 11be8a98... | E:\git\orientvibeNg | $20.81 | 41% | 2h 43m 30s | 2,050 | 784 |

### Optimization Opportunities

2. **Sessions with low cache efficiency** (avg 0%)
   - These sessions could benefit most from optimized prompts:

     - 4705f5ef... (E:\git\orientvibeNg): 0% cache hit

3. **Model efficiency by family**
   | Model | Sessions | $/1k tokens |
   |-------|----------|-------------|
   | opus | 1 | $4.264 |
   | other | 1 | $0.585 |

4. **High-spend projects to review**
   | Project | Sessions | Cost | Cache Hit % |
   |---------|----------|------|-------------|
   | E:\git\orientvibeNg | 2 | $58.46 | 3% |

```mermaid
xychart-beta
    title "Top 5 Projects by Cost ($)"
    x-axis ["E:\git\o"]
    bar [58.46]
```

## Cost Efficiency

```mermaid
pie title Token Serving: Cache vs Fresh
    "Cache Hit" : 3.0
    "Fresh (non-cached)" : 97.0
```

- **Overall cache efficiency**: 3.0% of tokens served from cache
- **Average tokens per dollar**: 1184 tokens/$

### Top 5 Most Efficient Sessions (lowest $/1k tokens)
|  Session | Project | $/1k tokens | Cost | Tokens |
|---|---|---|---|---|
| 4705f5ef... | E:\git\orientvibeNg | $0.585 | $37.65 | 64,333 |
| 11be8a98... | E:\git\orientvibeNg | $4.264 | $20.81 | 4,880 |

### Top 5 Least Efficient Sessions (highest $/1k tokens)
| Session | Project | $/1k tokens | Cost | Tokens |
|---|---|---|---|---|
| 11be8a98... | E:\git\orientvibeNg | $4.264 | $20.81 | 4,880 |
| 4705f5ef... | E:\git\orientvibeNg | $0.585 | $37.65 | 64,333 |

## Daily Activity Heatmap

```mermaid
xychart-beta
    title "Sessions by Day of Week"
    x-axis ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    bar [0, 0, 0, 0, 2, 0, 0]
```

### Sessions by Day of Week
| Day | Count | Activity |
|-----|-------|----------|
| Mon | 0 | .................... |
| Tue | 0 | .................... |
| Wed | 0 | .................... |
| Thu | 0 | .................... |
| Fri | 2 | #################### |
| Sat | 0 | .................... |
| Sun | 0 | .................... |

```mermaid
xychart-beta
    title "Sessions by Hour of Day"
    x-axis ["00h", "01h", "02h", "03h", "04h", "05h", "06h", "07h", "08h", "09h", "10h", "11h", "12h", "13h", "14h", "15h", "16h", "17h", "18h", "19h", "20h", "21h", "22h", "23h"]
    bar [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0]
```

### Sessions by Hour of Day
| Hour | Count | Activity |
|------|-------|----------|
| 00 | 0 | .................... |
| 01 | 0 | .................... |
| 02 | 0 | .................... |
| 03 | 0 | .................... |
| 04 | 0 | .................... |
| 05 | 0 | .................... |
| 06 | 0 | .................... |
| 07 | 0 | .................... |
| 08 | 0 | .................... |
| 09 | 0 | .................... |
| 10 | 0 | .................... |
| 11 | 1 | #################### |
| 12 | 0 | .................... |
| 13 | 0 | .................... |
| 14 | 1 | #################### |
| 15 | 0 | .................... |
| 16 | 0 | .................... |
| 17 | 0 | .................... |
| 18 | 0 | .................... |
| 19 | 0 | .................... |
| 20 | 0 | .................... |
| 21 | 0 | .................... |
| 22 | 0 | .................... |
| 23 | 0 | .................... |

## Weekly Activity Trend

```mermaid
xychart-beta
    title "Weekly Spend ($)"
    x-axis ["W36"]
    line [58.46]
```

```mermaid
xychart-beta
    title "Weekly Sessions Count"
    x-axis ["W36"]
    bar [2]
```

| Week | Sessions | Cost | Tokens | Spend Bar |
|------|----------|------|--------|-----------|
| 2026-W36 | 2 | $58.46 | 69,213 | #################### |

## Code Productivity

> Based on 2 sessions with git activity data.

- **Total lines changed**: 4,653 (+2,667 / -1,986)
- **Lines per dollar**: 79 lines/$
- **Lines per 1k tokens**: 67.2 lines/1k tokens

### Top 5 Projects by Lines/$ Efficiency
| Project | Lines Changed | Cost | Lines/$ |
|---------|--------------|------|---------|
| E:\git\orientvibeNg | 4,653 | $58.46 | 79 |

## Projects

| # | Project | Sessions | Cost | % Total | Tokens | Cache Hit % | Avg Cost | Dominant Model |
|---|---------|----------|------|---------|--------|-------------|----------|----------------|
| 1 | E:\git\orientvibeNg | 2 | $58.46 | 100.0% | 69,213 | 3.0% | $29.23 | opus |

---
*Report generated by context-stats*