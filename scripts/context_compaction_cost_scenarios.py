# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Reproduce the seven 10-turn cost scenarios in the context compaction design.

Run: uv run scripts/context_compaction_cost_scenarios.py
Prices and cache behavior are fixed scenario assumptions from the design,
not live pricing, observed usage, or the AgentLlmCallStream reporting helper.
"""

TURNS = 10
PRICES = {  # $/1M: input, cache_read, cache_write (Anthropic 1.25x; OpenAI none), output, provider
    "Claude Sonnet 5": (2.00, 0.20, 2.50, 10.00, "anthropic"),
    "Claude Opus 5":   (5.00, 0.50, 6.25, 25.00, "anthropic"),
    "GPT-5.4":         (2.50, 0.25, 2.50, 15.00, "openai"),
    "GPT-5.5":         (5.00, 0.50, 5.00, 30.00, "openai"),
}

def price(t, m):
    pi, pr, pw, po, prov = PRICES[m]
    if prov == "anthropic":   # write tokens are a subset of fresh; bill them at the write rate instead
        return ((t["fresh"]-t["write"])*pi + t["write"]*pw + t["cached"]*pr + t["output"]*po) / 1e6
    return (t["fresh"]*pi + t["cached"]*pr + t["output"]*po) / 1e6

def parler(workload="text", cache="warm"):
    sys_t = 31_500/3.5; eph = 5_200/3.5
    hist = 12_000/3.5 + (2_300 if workload == "chart" else 0)
    extras = [0, 2_500, 5_000] + ([7_300] if workload == "chart" else [])
    out = [150, 150] + ([150] if workload == "chart" else []) + [700]
    tools_full = 58_200/3.5
    tools_a, tools_b = 10_400/3.5, (10_400 + 17_000)/3.5   # lazy: catalog face, then catalog + loaded schemas
    fresh = cached = write = tot = 0
    for n in range(1, TURNS+1):
        H = hist*(n-1)
        rounds = list(enumerate(extras))
        if cache == "lazy2":
            rounds = [(-1, 0)] + rounds
        for k, (r, extra) in enumerate(rounds):
            notool = (cache == "warm+notool-final" and r == len(extras)-1)
            tools_now = (tools_a if k == 0 else tools_b) if cache == "lazy2" else (0 if notool else tools_full)
            inp = sys_t + tools_now + eph + H + extra
            tot += inp
            if cache == "cold" or notool:
                f, w, c = inp, 0, 0
            elif cache == "warm+ttl" and k == 0:
                w = inp - eph; f = eph + w; c = 0
            elif cache == "lazy2":
                if k == 0:
                    delta = hist if n > 1 else sys_t + tools_a
                elif k == 1:
                    delta = (hist + extra) if n > 1 else (sys_t + tools_b + extra)
                else:
                    delta = extra - extras[r-1]
                w = delta; f = eph + delta; c = inp - f
            else:
                delta = (extra - extras[r-1]) if r > 0 else (hist if n > 1 else sys_t + tools_now + H)
                w = delta; f = eph + delta; c = inp - f
            fresh += f; write += w; cached += c
    output = sum(out)*TURNS + (150*TURNS if cache == "lazy2" else 0)
    return dict(input=tot, fresh=fresh, cached=cached, write=write, output=output)

for label, t in [
    ("text cold", parler("text", "cold")),
    ("text warm", parler("text", "warm")),
    ("text warm+ttl", parler("text", "warm+ttl")),
    ("text warm+notool-final", parler("text", "warm+notool-final")),
    ("text lazy2", parler("text", "lazy2")),
    ("chart cold", parler("chart", "cold")),
    ("chart warm", parler("chart", "warm")),
]:
    print(label, {m: round(price(t, m), 2) for m in PRICES})
